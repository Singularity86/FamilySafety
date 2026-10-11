#!/usr/bin/env python3
"""
Downloads vehicle-CG accelerometer traces from NHTSA's Vehicle Crash Test Database.

The database exposes, for each instrumented test, a set of sensor channels; the one we want is
the accelerometer bolted at the vehicle's centre of gravity, because it is the closest available
proxy for what a phone riding in the cabin experiences during the crash. Dummy head/chest
channels measure the occupant, not the vehicle, so they are ignored.

Everything is cached under cache/ so re-runs are free and the API is only hit once per resource.

API docs: https://nrd.api.nhtsa.dot.gov/nhtsa/vehicle/swagger-ui/index.html
"""
import argparse, json, os, sys, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor

BASE = "https://nrd.api.nhtsa.dot.gov/nhtsa/vehicle/api/v1/vehicle-database-test-results"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
CURVES = os.path.join(CACHE, "curves")


def _cache_path(url, ext):
    name = url.split("//", 1)[-1].replace("/", "_").replace("?", "_").replace("&", "_")
    return os.path.join(CACHE, name[-180:] + ext)


def fetch(url, binary=False, retries=4):
    path = _cache_path(url, ".bin" if binary else ".json")
    if os.path.exists(path):
        return open(path, "rb").read() if binary else json.load(open(path))
    last = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=120) as r:
                raw = r.read()
            os.makedirs(os.path.dirname(path), exist_ok=True)
            if binary:
                open(path, "wb").write(raw)
                return raw
            d = json.loads(raw)
            json.dump(d, open(path, "w"))
            return d
        except Exception as e:                      # noqa: BLE001 - retry anything transient
            last = e
            time.sleep(2 ** attempt)
    raise RuntimeError(f"failed after {retries}: {url}: {last}")


def search_tests(query, pages, per_page=50):
    out = []
    for page in range(pages):
        d = fetch(f"{BASE}/by-search?{query}&count={per_page}&pageNumber={page}")
        rows = d.get("results", [])
        if not rows:
            break
        out += rows
        if len(rows) < per_page:
            break
    return out


# NHTSA runs far more side-pole tests than anything else, so an unstratified scan returns a
# corpus that is ~90% pole impacts at 32 kph. Detection difficulty varies enormously by crash
# mode and severity, so quotas are drawn per stratum instead.
STRATA = [
    ("frontal NCAP (50-70 kph)",   "testConfiguration=VTB&closingSpeedFrom=50&closingSpeedTo=70", 45),
    ("frontal moderate (30-50)",   "testConfiguration=VTB&closingSpeedFrom=30&closingSpeedTo=50", 25),
    ("frontal low speed (<30)",    "testConfiguration=VTB&closingSpeedFrom=1&closingSpeedTo=30",  20),
    ("frontal high speed (>70)",   "testConfiguration=VTB&closingSpeedFrom=70&closingSpeedTo=200", 15),
    ("side pole",                  "testConfiguration=VTP",                                       30),
    ("side moving barrier",        "testConfiguration=ITV",                                       30),
    ("vehicle to vehicle",         "testConfiguration=VTV",                                       25),
]


def vehicle_cg_channels(test_no):
    """The vehicle-CG accelerometer channels for a test that actually carry data."""
    try:
        d = fetch(f"{BASE}/get-instrumentation-info/{test_no}")
    except Exception:
        return []
    keep = []
    for c in d.get("results", []):
        if c.get("sensorType") != "ACCELEROMETER":
            continue
        if str(c.get("sensorAttachment", "")).strip().upper() != "VEHICLE CG":
            continue
        if str(c.get("dataStatus", "")).upper() == "NO DATA":
            continue
        if str(c.get("dataMeasurementUnits", "")).upper() not in ("G'S", "GS", "G"):
            continue
        keep.append(c)
    return keep


def curve_series(test_no, curve_no):
    """(times_s, accel_g) for one channel, via the database's ASCII export."""
    d = fetch(f"{BASE}/get-instrumentation-detail-info/{curve_no}/{test_no}")
    rows = d.get("results", [])
    if not rows:
        return None
    meta = rows[0]
    url = meta.get("asciiFile")
    if not url:
        return None
    raw = fetch(url, binary=True).decode("utf-8", "replace")
    ts, gs = [], []
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        try:
            t, g = float(parts[0]), float(parts[1])
        except ValueError:
            continue
        ts.append(t)
        gs.append(g)
    if len(ts) < 50:
        return None
    return meta, ts, gs


def collect(test, want_axes=("X", "Y", "Z")):
    """All available CG axes for one test, keyed by axis letter."""
    no = test["testNo"]
    chans = vehicle_cg_channels(no)
    if not chans:
        return None
    axes = {}
    for c in chans:
        axis = str(c.get("axisDirofSensor", "")).strip()[:1].upper()
        if axis not in want_axes or axis in axes:
            continue
        try:
            got = curve_series(no, c["curveNo"])
        except Exception:
            continue
        if got:
            meta, ts, gs = got
            axes[axis] = {"times": ts, "g": gs, "dt_us": meta.get("timeIncrement")}
    if "X" not in axes:
        return None                                  # longitudinal axis is the one crashes live on
    return {
        "testNo": no,
        "testDate": test.get("testDate"),
        "testType": test.get("testType"),
        "title": test.get("contractorStudyTitle"),
        "closingSpeedKph": test.get("closingSpeed"),
        "configuration": test.get("testConfiguration"),
        "impactAngle": test.get("impactAngle"),
        "axes": axes,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pages", type=int, default=12, help="search pages (50 tests each) per stratum")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--out", default=os.path.join(HERE, "crash_pulses.json"))
    args = ap.parse_args()

    os.makedirs(CURVES, exist_ok=True)
    found, seen = [], set()

    for label, query, quota in STRATA:
        tests = search_tests(query, args.pages)
        # Newest first: modern tests are far more likely to have digitised curves.
        tests.sort(key=lambda t: t.get("testNo", 0), reverse=True)
        tests = [t for t in tests if t.get("testNo") not in seen]
        got = 0
        print(f"\n## {label}: scanning {len(tests)} candidates for {quota}", file=sys.stderr)
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            for res in pool.map(collect, tests):
                if not res or res["testNo"] in seen:
                    continue
                seen.add(res["testNo"])
                res["stratum"] = label
                found.append(res)
                got += 1
                print(f"  [{len(found):3}] test {res['testNo']} "
                      f"{res['closingSpeedKph']}kph axes={''.join(sorted(res['axes']))} "
                      f"{str(res['title'])[:46]}", file=sys.stderr)
                if got >= quota:
                    break

    json.dump(found, open(args.out, "w"))
    print(f"\nwrote {len(found)} crash pulses to {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()

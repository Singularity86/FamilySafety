#!/usr/bin/env python3
"""
Turns NHTSA crash pulses into CrashTrace fixtures the Kotlin test suite can replay.

The synthetic fixtures in src/test/resources/crash-traces are hand-shaped and therefore prove
only that the rule is self-consistent. These are real measured collisions, degraded through the
same pipeline decimate.py uses, so they assert something the synthetic ones cannot: that genuine
crash motion clears the threshold after a phone has finished mangling it.

Attenuation is applied deliberately and named in the file header, because an undamped lab pulse
would be a trivially easy fixture.
"""
import argparse, json, math, os, sys

import numpy as np
from scipy.signal import butter, filtfilt

G = 9.80665
HERE = os.path.dirname(os.path.abspath(__file__))
HEADER = "elapsed_ms,x,y,z,magnitude_ms2,speed_ms,speed_elapsed_ms"


def cfc(sig, dt_s, hz):
    nyq = 0.5 / dt_s
    if hz is None or hz >= nyq * 0.95 or len(sig) <= 27:
        return sig
    b, a = butter(4, hz / nyq, btype="low")
    return filtfilt(b, a, sig)


def export(pulse, attenuation, bandwidth_hz, phase, out_path, note):
    axes = pulse["axes"]
    n = min(len(axes[a]["g"]) for a in axes)
    dt = (axes["X"].get("dt_us") or 100) / 1e6
    comps = {a: cfc(np.array(axes[a]["g"][:n], dtype=float), dt, bandwidth_hz) * attenuation
             for a in axes}
    for a in ("X", "Y", "Z"):
        comps.setdefault(a, np.zeros(n))

    step = max(1, int(round(0.02 / dt)))            # 50 Hz
    speed_ms = (pulse["closingSpeedKph"] or 0) / 3.6

    lines = [
        f"# familysafety crash trace v1",
        f"# NHTSA test {pulse['testNo']} — {pulse.get('title')}",
        f"# {pulse.get('testType')} | {pulse.get('configuration')} | "
        f"closing speed {pulse['closingSpeedKph']} kph",
        f"# vehicle-CG accelerometer, native {round(1/dt)} Hz, CFC {bandwidth_hz} Hz low-pass,",
        f"# attenuated to {attenuation:g} of vehicle motion, decimated to 50 Hz at phase {phase}.",
        f"# {note}",
        f"# source: https://nrd.api.nhtsa.dot.gov/nhtsa/vehicle/api/v1/"
        f"vehicle-database-test-results/get-instrumentation-info/{pulse['testNo']}",
        HEADER,
    ]
    idx = range(phase, n, step)
    for out_i, i in enumerate(idx):
        x, y, z = (float(comps[a][i]) * G for a in ("X", "Y", "Z"))
        mag = math.sqrt(x * x + y * y + z * z)
        lines.append(f"{out_i*20},{x:.4f},{y:.4f},{z:.4f},{mag:.4f},{speed_ms:.2f},0")
    open(out_path, "w").write("\n".join(lines) + "\n")
    return len(lines) - 9


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pulses", default=os.path.join(HERE, "crash_pulses.json"))
    ap.add_argument("--out-dir", default=os.path.join(
        HERE, "..", "..", "app", "src", "test", "resources", "crash-traces", "nhtsa"))
    args = ap.parse_args()
    out_dir = os.path.abspath(args.out_dir)
    os.makedirs(out_dir, exist_ok=True)

    pulses = {p["testNo"]: p for p in json.load(open(args.pulses))}

    # Chosen to span the range: a textbook NCAP frontal, a side pole, and the least
    # favourable case in the population the speed guard actually lets through.
    picks = [
        (455, 0.25, 100, 0, "frontal-ncap.csv",
         "Textbook 56 kph NCAP frontal, damped to a quarter of vehicle motion."),
        (14505, 0.25, 100, 0, "side-pole.csv",
         "Side pole impact, damped to a quarter of vehicle motion."),
        (558,   1.00, 100, 0, "hardest-eligible.csv",
         "Weakest pulse above the speed guard, undamped: the margin case."),
    ]
    available = set(pulses)
    for test_no, att, bw, phase, name, note in picks:
        if test_no not in available:
            # Fall back to something comparable rather than failing the export.
            print(f"  test {test_no} not in corpus; skipping {name}", file=sys.stderr)
            continue
        n = export(pulses[test_no], att, bw, phase, os.path.join(out_dir, name), note)
        print(f"  wrote {name}: {n} samples (test {test_no}, att={att})", file=sys.stderr)


if __name__ == "__main__":
    main()

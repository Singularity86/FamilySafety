#!/usr/bin/env python3
"""
Runs real NHTSA crash pulses through FamilySafety's crash-detection rule, degraded to what an
Android phone would actually have seen.

The question this answers is narrow and specific: *is the 30 m/s2 threshold set too high to catch
real collisions?* It says nothing about false positives, because every pulse here is a real crash.

Pipeline, per test:

  1. Vehicle-CG accelerometer, 3 axes where available, at the lab's native rate (1-20 kHz).
  2. SAE J211 CFC low-pass, applied zero-phase. Lab data is unfiltered and rings at frequencies
     no phone MEMS part can see; leaving it in would flatter the detector. The phone's effective
     bandwidth is not something we know, so it is swept.
  3. Attenuation, modelling the phone being a loosely-coupled mass in a cupholder or pocket
     rather than bolted to the floor pan. Also swept, because it is the biggest unknown.
  4. Clip to the sensor's full-scale range.
  5. Decimate to 50 Hz (SENSOR_DELAY_GAME) at every possible phase offset.
  6. Fire if any surviving sample reaches the threshold.

Step 5 matters more than it looks. A crash pulse lasts ~100 ms and 50 Hz samples are 20 ms apart,
so where the sampling clock happens to land changes what you see. Rather than pick one alignment,
every phase is evaluated and the result is reported as the fraction that detect.

The phase sweep is exact, not Monte Carlo: a phase p detects iff some sample index that clears the
threshold is congruent to p modulo the decimation step, so the residues of the "hot" indices give
the answer in one pass.
"""
import argparse, json, math, os, sys
from collections import defaultdict

import numpy as np
from scipy.signal import butter, filtfilt

G = 9.80665
HERE = os.path.dirname(os.path.abspath(__file__))

# The rule under test (mirrors ImpactDecider.kt).
SENSITIVITY_LOW, SENSITIVITY_MEDIUM, SENSITIVITY_HIGH = 40.0, 30.0, 20.0
PHONE_RATE_HZ = 50.0            # SENSOR_DELAY_GAME

# Swept assumptions.
BANDWIDTHS_HZ = [None, 300, 100, 25, 10]   # None = unfiltered lab data
ATTENUATIONS = [1.0, 0.5, 0.25, 0.1, 0.05, 0.02]
FULL_SCALES_G = [16, 8, 4]


def cfc_filter(sig, dt_s, cutoff_hz):
    """Zero-phase 4-pole Butterworth, the SAE J211 style of channel filtering."""
    if cutoff_hz is None:
        return sig
    nyq = 0.5 / dt_s
    if cutoff_hz >= nyq * 0.95:
        return sig
    b, a = butter(4, cutoff_hz / nyq, btype="low")
    if len(sig) <= 27:                       # filtfilt needs padlen headroom
        return sig
    return filtfilt(b, a, sig)


def magnitude(pulse):
    """Resultant acceleration in g at the native sample rate, plus dt in seconds."""
    axes = pulse["axes"]
    n = min(len(axes[a]["g"]) for a in axes)
    stack = np.array([axes[a]["g"][:n] for a in sorted(axes)], dtype=float)
    dt_us = axes["X"].get("dt_us") or 100
    return np.sqrt((stack ** 2).sum(axis=0)), dt_us / 1e6


def clean(pulses):
    """
    Drops channels that are not usable crash pulses. Every rule here is about instrumentation
    faults, not about inconvenient results:

      - peak < 5 g          a dead or disconnected channel; no crash is that gentle at the CG
      - rms/peak > 0.5      a constant DC offset rather than a transient (e.g. the GM fire tests,
                            which are not crashes at all)
      - peak > 500 g        physically implausible at a vehicle CG even unfiltered; indicates a
                            broken sensor or a units error
    """
    kept, dropped = [], []
    for p in pulses:
        mag, dt = magnitude(p)
        if len(mag) < 100:
            dropped.append((p, "too few samples")); continue
        peak = float(mag.max())
        rms = float(np.sqrt((mag ** 2).mean()))
        if peak < 5:
            dropped.append((p, f"dead channel (peak {peak:.2f} g)")); continue
        if peak > 0 and rms / peak > 0.5:
            dropped.append((p, f"constant offset, not a transient (rms/peak {rms/peak:.2f})")); continue
        if peak > 500:
            dropped.append((p, f"implausible peak ({peak:.0f} g)")); continue
        p["_mag"], p["_dt"] = mag, dt
        kept.append(p)
    return kept, dropped


def detect_fraction(mag_ms2, dt_s, threshold):
    """
    Fraction of 50 Hz sampling phases that would catch this pulse.

    A phase detects iff at least one sample index clearing `threshold` falls on that phase, so the
    distinct residues of the hot indices modulo the decimation step give the exact answer.
    """
    step = int(round((1.0 / PHONE_RATE_HZ) / dt_s))
    if step < 1:
        step = 1
    hot = np.flatnonzero(mag_ms2 >= threshold)
    if hot.size == 0:
        return 0.0, step
    return len(np.unique(hot % step)) / step, step


def max_survivable_attenuation(mag_g, dt_s, threshold_ms2):
    """
    The strongest damping at which this pulse is still detected at *every* sampling phase.

    This is the honest margin number: how much coupling loss the signal can absorb before the
    detector starts depending on luck.
    """
    step = int(round((1.0 / PHONE_RATE_HZ) / dt_s)) or 1
    # For all phases to detect, every residue class must contain a hot index. Binary search the
    # attenuation at which that stops being true.
    lo, hi = 1e-4, 1.0
    def all_phases(att):
        hot = np.flatnonzero(mag_g * att * G >= threshold_ms2)
        return hot.size > 0 and len(np.unique(hot % step)) == step
    if not all_phases(hi):
        return 0.0
    for _ in range(40):
        mid = math.sqrt(lo * hi)
        if all_phases(mid): hi = mid
        else: lo = mid
    return hi


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pulses", default=os.path.join(HERE, "crash_pulses.json"))
    ap.add_argument("--out", default=os.path.join(HERE, "results.json"))
    ap.add_argument("--threshold", type=float, default=SENSITIVITY_MEDIUM)
    args = ap.parse_args()

    pulses = json.load(open(args.pulses))
    kept, dropped = clean(pulses)
    print(f"loaded {len(pulses)} pulses; kept {len(kept)}, dropped {len(dropped)}", file=sys.stderr)
    for p, why in dropped:
        print(f"  dropped test {p['testNo']}: {why}", file=sys.stderr)

    results = {"threshold_ms2": args.threshold, "n_pulses": len(kept),
               "dropped": [{"testNo": p["testNo"], "reason": w} for p, w in dropped],
               "sweep": [], "per_test": []}

    # Pre-filter each pulse once per bandwidth.
    filtered = {}
    for bw in BANDWIDTHS_HZ:
        for p in kept:
            filtered[(p["testNo"], bw)] = cfc_filter(p["_mag"], p["_dt"], bw)

    for bw in BANDWIDTHS_HZ:
        for att in ATTENUATIONS:
            for fs in FULL_SCALES_G:
                rates = []
                for p in kept:
                    sig = np.clip(filtered[(p["testNo"], bw)] * att, None, fs) * G
                    frac, _ = detect_fraction(sig, p["_dt"], args.threshold)
                    rates.append(frac)
                rates = np.array(rates)
                results["sweep"].append({
                    "bandwidth_hz": bw, "attenuation": att, "full_scale_g": fs,
                    "mean_phase_detect_rate": float(rates.mean()),
                    "tests_detected_always": int((rates >= 0.9999).sum()),
                    "tests_detected_sometimes": int(((rates > 0) & (rates < 0.9999)).sum()),
                    "tests_never_detected": int((rates == 0).sum()),
                })

    # Per-test margin at the realistic operating point.
    ref_bw = 100
    for p in kept:
        sig = filtered[(p["testNo"], ref_bw)]
        results["per_test"].append({
            "testNo": p["testNo"], "stratum": p.get("stratum"),
            "title": p.get("title"), "closingSpeedKph": p.get("closingSpeedKph"),
            "peak_g_raw": float(p["_mag"].max()),
            "peak_g_cfc100": float(np.abs(sig).max()),
            "max_survivable_attenuation": max_survivable_attenuation(sig, p["_dt"], args.threshold),
            "native_rate_hz": round(1.0 / p["_dt"]),
        })

    json.dump(results, open(args.out, "w"), indent=1)
    print(f"wrote {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()

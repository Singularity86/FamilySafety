# NHTSA decimation harness

Answers one question: **is FamilySafety's 30 m/s² crash threshold set too high to catch real
collisions?** It runs measured crash pulses from NHTSA's Vehicle Crash Test Database through
`ImpactDecider`'s rule, after degrading them into what an Android phone would actually have seen.

It says nothing about false positives — every pulse here is a real crash. That half of the problem
needs traces recorded from ordinary driving (see `CrashTraceRecorder` and the **Crash Detection
Testing** section of `CLAUDE.md`).

## Running it

```bash
pip install numpy scipy
python3 fetch_nhtsa.py        # ~15 min, caches everything under cache/
python3 decimate.py           # writes results.json
python3 export_fixtures.py    # regenerates the Kotlin test fixtures
```

`fetch_nhtsa.py` draws quotas per crash mode and severity. An unstratified scan comes back ~90%
side-pole tests at 32 kph, which would make the corpus look far more uniform than crashes are.

## What the pipeline does

1. Pull the vehicle-CG accelerometer channels — the closest available proxy for a phone riding in
   the cabin. Dummy head/chest channels measure the occupant, not the vehicle.
2. Drop instrumentation faults (dead channels, DC offsets, implausible peaks). The rules are in
   `clean()` and are about faults, not about inconvenient results.
3. SAE J211 style zero-phase low-pass. Lab data is unfiltered and rings at frequencies no phone
   MEMS part can resolve; leaving it in would flatter the detector.
4. Attenuate, to model the phone as a loosely-coupled mass rather than something bolted to the
   floor pan. **This is the least grounded assumption in the whole exercise and it dominates the
   result**, so it is swept rather than picked.
5. Clip to the sensor's full-scale range.
6. Decimate to 50 Hz at *every* phase offset, and report the fraction that detect. A crash pulse
   is ~100 ms and 50 Hz samples are 20 ms apart, so where the sampling clock lands matters. The
   sweep is exact rather than Monte Carlo: a phase detects iff some above-threshold sample index
   is congruent to it modulo the decimation step.

## Data source

NHTSA Vehicle Crash Test Database, public API:
<https://nrd.api.nhtsa.dot.gov/nhtsa/vehicle/swagger-ui/index.html>

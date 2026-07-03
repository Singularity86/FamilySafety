# FamilySafety — iOS

Everything specific to the iOS version of FamilySafety lives in this folder.
The Android app (rest of this repo) is the reference implementation; the two apps
interoperate over the same MQTT broker with identical crypto and wire formats.

## Contents

- **[IOS_PORT_SPEC.md](IOS_PORT_SPEC.md)** — the complete port specification and interop
  contract: key derivation, E2EE envelope, MQTT topic/payload matrix, group-state security
  rules, join flow, verified cross-platform test vectors, iOS library mapping, and a
  phased build plan (§14) with per-phase acceptance criteria.
- **[tools/gen_test_vectors.py](tools/gen_test_vectors.py)** — regenerates the spec's
  test vectors using real libsodium (`pip install pynacl`, then `python gen_test_vectors.py`).

## Ground rules

1. Wire formats, topics, and validation rules in the spec are a **compatibility contract**
   with shipped Android clients — never "improve" them on the iOS side.
2. If the Android code changes any wire format, topic, or validation rule, update
   IOS_PORT_SPEC.md in the same change.
3. Build in the phase order from spec §14; each phase has acceptance tests, starting with
   the crypto test vectors (§12), which require no Android device to verify.

The Xcode project will live in this folder (e.g. `ios/FamilySafety/`) once Phase 0 begins.

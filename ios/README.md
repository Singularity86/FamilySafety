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
- **[FamilySafety/](FamilySafety/)** — the Swift package, scaffolded for Phase 0 (crypto
  core). Open `FamilySafety/` directly in Xcode (SwiftPM packages don't need an
  `.xcodeproj`), then run the tests:
  ```
  cd ios/FamilySafety
  swift test
  ```
  **This has not been run yet** — it was scaffolded on Windows, which has no Swift
  toolchain. The first thing to do on a Mac is run `swift test` and fix whatever the
  compiler or a failing vector turns up; see IOS_PORT_SPEC.md §14 Phase 0.

## Ground rules

1. Wire formats, topics, and validation rules in the spec are a **compatibility contract**
   with shipped Android clients — never "improve" them on the iOS side.
2. If the Android code changes any wire format, topic, or validation rule, update
   IOS_PORT_SPEC.md in the same change.
3. Build in the phase order from spec §14; each phase has acceptance tests, starting with
   the crypto test vectors (§12), which require no Android device to verify.
4. The vectors in `VectorTests.swift` are ground truth (generated with real libsodium) —
   if a test fails, the Swift implementation has a bug; the vector is not to be "fixed"
   to match broken code.

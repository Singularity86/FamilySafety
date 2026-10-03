# FamilySafety iOS Port — Project Status

Snapshot date: 2026-10-03. Branch: `main`, HEAD `a71b5c0`. Companion to
[IOS_PORT_SPEC.md](IOS_PORT_SPEC.md) (the interop contract and phase plan) — this document
tracks progress against that plan and records what's been verified, how, and what's
still outstanding. Built from `git log`, the working tree, and this session's own test
runs; nothing here is user-reported unless marked so.

## Where this stands against the phase plan (spec §14)

| Phase | Status | Verified how |
|---|---|---|
| 0 — Crypto core | ✅ Done | 7 test vectors (ground truth from `tools/gen_test_vectors.py`, real libsodium), macOS + iPhone Simulator |
| 1 — Models & validation | ✅ Done | 20 validator tests + 24 wire-format round-trip tests |
| 2 — Transport | ✅ Done | 12 offline unit tests, **plus live verification against the real EMQX broker** — see below |
| 3 — Group sync + join | Not started | — |
| 4 — Location & map | Not started | — |
| 5 — Chat | Not started | — |
| 6 — Files | Not started | — |
| 6.5 — Vault | Not started | — |
| 7 — Polish / App Store prep | Not started | — |

**66 unit tests, 0 failures**, verified on both `swift test` (macOS host) and the iOS
Simulator (iPhone 18 Pro, iOS 27) via `xcodebuild test -scheme FamilySafety-Package`.

## Phase 2's live-broker verification

The spec's own acceptance test for Phase 2 is two clients seeing each other's presence
flip online/offline over the real broker, with the last-will observed on an abrupt kill.
This was run twice, both against the real EMQX Cloud broker
(`r161feb1.ala.us-east-1.emqxsl.com:8883`) with credentials supplied by the user at
runtime (never written to disk):

1. **CLI harness** (`ios/FamilySafety/Sources/PresenceHarness/`) — two macOS processes
   sharing the production `MqttTransport`/crypto code. Confirmed mutual signed presence,
   then confirmed the last-will after `kill -9` on one process.
2. **Visual two-simulator harness** (`ios/FamilySafetyApp/`) — a small SwiftUI app running
   the same code on two actual iOS Simulator instances (iPhone 17 = Alice, iPhone 18 Pro =
   Bob), installed via `xcrun simctl`. Screenshotted both mid-test showing mutual "Online,
   signature valid," then SIGKILLed Bob's simulator process and re-screenshotted Alice
   showing Bob flip to "Offline, signature valid."

Both harnesses are throwaway scaffolding, not part of the shipping app — kept in the repo
because they're the only thing that's actually exercised the transport layer against a
live broker, and they'll be useful again for Phase 3+'s own live-broker acceptance tests.

## Bugs found and fixed this session (2026-09-26)

Before starting Phase 3, audited the iOS port against the actual Android Kotlin source
directly rather than trusting the spec's own prose summary. Found two real
incompatibilities, both now fixed and covered by tests (commit `a71b5c0`):

1. **Creator succession on quorum removal was entirely missing.** Android computes a
   deterministic successor when the creator is quorum-removed and accepts
   `creatorMemberId` changing in the *same* update as that removal
   (`GroupDefinition.computeSuccessorCreator`). The iOS validator rejected any creator
   change bundled with a new tombstone, which would have rejected every legitimate
   Android-originated quorum-succession update.
2. **`computeStateHash`'s tombstone section repeated `"removed:"` per tombstone** instead
   of once before the whole list. Coincidentally matched Android for exactly one removal
   (the only case previously tested) and silently diverged for two or more — a family
   with multiple departures would have had iOS and Android permanently disagree about the
   state hash. Fixed in both the Swift code and the spec's own prose, which read
   ambiguously in a way that led to the wrong implementation the first time.

Confirmed via `git log b2d0807..HEAD` that no Android commits had landed since the spec
was last synced (`28b75fa`) — these were latent bugs in the existing port, not drift from
new Android work.

**Open question from this audit:** only Phase 0/1/2 code has been checked against Kotlin
source directly; Phase 3+ work should get the same treatment as it's built, not just
trust the spec's summary.

## Known outstanding items

- **`main` has diverged from `origin/main`.** Local `main` is 5 commits ahead of the
  merge-base (`98b54b3`) with the iOS work; `origin/main` is 3 commits ahead with
  location-history UI work (`b18bfc6`, `238d129`, `20fb14d`). Nothing has been pushed from
  this branch yet — will need a merge or rebase before it is.
- **Phase 3's own acceptance test** (an iOS device joining a group created on the Android
  build via a real QR code) will need an actual Android device or emulator alongside the
  iOS Simulator — out of scope for what's been built so far.
- A billing/subscription branch was mentioned as existing on another of the user's
  machines, not yet pushed to GitHub. Not part of this repo's history as of this
  snapshot — nothing to reconcile here until it's pushed.

## Repo layout added by the iOS port

- `ios/IOS_PORT_SPEC.md` — the interop contract and phase plan (source of truth for wire
  formats; never "improve" them on the iOS side without updating Android too).
- `ios/FamilySafety/` — the SwiftPM package (`FamilySafetyCore` library + its test target).
  Open directly in Xcode or `cd` in and run `swift test`.
- `ios/FamilySafetyApp/` — throwaway SwiftUI two-simulator harness (XcodeGen-generated;
  `project.yml` is the source of truth if it needs regenerating).
- `ios/tools/gen_test_vectors.py` — regenerates the spec's cross-platform ground-truth
  test vectors using real libsodium (`pip install pynacl`, then `python gen_test_vectors.py`).

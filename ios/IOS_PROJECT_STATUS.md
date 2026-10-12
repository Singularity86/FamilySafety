# FamilySafety iOS Port — Project Status

Snapshot date: 2026-10-11. Branch `ios/phase3-group-sync`, cut from `main` at `12e1543`
(Android 1.15.3 / versionCode 44). Companion to [IOS_PORT_SPEC.md](IOS_PORT_SPEC.md) (the
interop contract and phase plan) and [REMEDIATION_PLAN.md](REMEDIATION_PLAN.md) (what the
2026-10-11 code review asks of iOS). Built from `git log`, the working tree and this session's
own test runs; nothing here is user-reported unless marked so.

## Where this stands against the phase plan (spec §14)

| Phase | Status | Verified how |
|---|---|---|
| 0 — Crypto core | ✅ Done | 7 cross-platform test vectors (libsodium ground truth) |
| 1 — Models & validation | ✅ Done | Validator re-ported from Android 1.15.3 (memberships, removal proofs); 24 validator tests |
| 2 — Transport | ✅ Done | Unit tests, plus the live EMQX broker: presence/LWT (two-simulator harness) and, after the wildcard change, `ReconnectHarness` — all phases passed (commit `7da375d`) |
| 3 — Group sync + join | **Partly done (core library only)** | See below. 114 tests, macOS host and iOS Simulator |
| 4 — Location & map | Not started | — |
| 5 — Chat | Not started | — |
| 6 — Files | Not started | — |
| 6.5 — Vault | Not started | — |
| 7 — Polish / App Store prep | Not started | — |

**114 unit tests, 0 failures**, on `swift test` (macOS) and
`xcodebuild test -scheme FamilySafety-Package` on the iPhone 18 Pro Simulator.

## Phase 3 in detail

**Done, in `FamilySafetyCore` (no UI, no app wiring yet):**

- **Models at Android 1.15.3:** `FamilyMember.joinEpoch`, `GroupDefinition.removalProofs`,
  `Tombstones` (`"<id>#<n>"` keys), `RemovalProof`. The state hash appends `,#n` for memberships
  after the first, checked against a hash computed independently from Android's canonical form.
  `CREATOR_TRANSFERRED` added; before this, iOS could not decode that Android message.
- **`GroupTransitionValidator`** re-ported line for line from Android 1.15.3. Two behaviours
  changed from the old iOS port to match Android:
  - a state that lacks our tombstones is accepted, and the store unions them back in;
  - the creator may drop a member without a tombstone.
- **`GroupStateMerge`**, **`GroupStateStore`** (apply, concurrent and diverged-branch
  reconciliation, tombstone preservation) and **`GroupSyncEngine`** (decrypt, verify, apply, ack,
  rebroadcast, refresh).
- **Persistence:** `FileGroupStatePersistence`, AES-GCM with the key in the Keychain
  (`KeychainGroupStateKeyProvider`).
- **Joiner side of joining:** `JoinFlow.makeRequest` (signed `JOIN:v1`, like Android 1.15.1+),
  `JoinFlow.evaluate` (approval/rejection checks ported from `MembershipViewModel`), and
  `JoinCoordinator` (re-publish every 30 s, save then clear retained messages).
- **Join v2** (Android 2.1, PR #27; spec §8.2–§8.4), in `JoinV2.swift`:
  - joiner: `JoinFlow.prepare` sends a v2 request for a v2 invite (encrypted to the inviter,
    signed over `JOIN:v2:…`, HMAC under the invite secret), and a v1 request only for a v1
    invite from an older inviter. It refuses an expired invite or one whose keys don't hash to
    `inviterMemberId`, and pins the inviter's X25519 from the QR on the approval and rejection.
    `JoinFlow.fingerprint` is for the pending screen.
  - inviter core: `InviteBook` issues one-time 24 h invites (pruned, capped at 20) and
    `JoinV2Verifier` runs §8.4 step 0's seven checks in order. v1 requests are not accepted.
  - the §12 vectors (fingerprint 367443, HMAC) pass.

**Review rules built in (iOS-only, wire-compatible):**

| Rule | Ref |
|---|---|
| A roster that drops this device without a tombstone is refused and a refresh requested — never obeyed | F26 |
| Storage errors throw; nothing deletes data or keys on an error; "unreadable" is never "no group" | F14 |
| The envelope sender must be the update's `updaterMemberId` | F25 (part) |
| Refresh requests answered only for members, rate-limited per requester and overall; stale replays rebroadcast at most once a minute; no refresh on decrypt failure | F16 |
| Acks recorded, never waited on; inbound handled serially by the engine actor | E5 |
| The joiner refuses an approval whose roster lists it with different keys | F13 (joiner's half) |
| Join v2: the joiner pins the inviter's X25519 from the QR, not only the Ed25519 hash | F13 |

**Not done in Phase 3:**

- **Inviter side, beyond verification:** `RejoinPolicy`, adding the member (`ADD:v2` signature),
  publishing the approval or rejection, using up the invite, and storing the `InviteBook` in
  the Keychain (this device only, excluded from backup).
- **Removal votes** — casting and tallying. Receiving a quorum removal *is* supported:
  the votes ride in the sync message and are verified.
- **Local edits** — rename, colour, avatar, leave, transfer creator.
- **App wiring:** connecting `GroupSyncEngine` and `JoinCoordinator` to `MqttTransport` inbound
  topics, persisting `PendingJoin`, and any UI.
- **Acceptance test:** an iPhone joining a family created on Android, via a real QR code. It
  needs the app wiring above and an Android phone.

## Other changes this session

- Plan Part A:
  - `Hex` works on UTF-8 bytes (linear; non-ASCII can't pass as hex);
  - a wrong-length seed throws `IdentityError` instead of trapping;
  - the shared-secret cache is keyed by member *and* key.
- `HarnessSupport`'s Keychain lookup is macOS-only. It shells out to `security`, and had
  stopped `FamilySafety-Package` building for iOS since PR #13.

## Known outstanding items

- **The subscription count still grows with family size** (2 + one presence filter per
  peer), so a family of 10+ exceeds the broker quota. The fix needs Android to move presence
  under the group topic first (`ios/REMEDIATION_PLAN.md` A.1, `app/REMEDIATION_PLAN.md` 4.2).
- **Phase 3+ code should be checked against Kotlin source,** not just the spec's summary.
  That is how two latent hash/validator bugs were found on 2026-09-26, and it's how this
  session's port was done.

## Repo layout added by the iOS port

- `ios/IOS_PORT_SPEC.md` — the interop contract and phase plan. It is the source of truth for
  wire formats; never "improve" them on iOS without updating Android too.
- `ios/FamilySafety/` — the SwiftPM package: `FamilySafetyCore` library, test target, and two
  macOS-only live-broker harnesses (`PresenceHarness`, `ReconnectHarness`; credentials from
  env vars or the Keychain entry `FamilySafety-MQTT`).
- `ios/FamilySafetyApp/` — throwaway SwiftUI two-simulator harness (XcodeGen; `project.yml`).
- `ios/Vendor/CocoaMQTT` — CocoaMQTT 2.4.1 with a small patch exposing CONNACK session-present
  (`ios/Vendor/PATCHES.md`).
- `ios/tools/gen_test_vectors.py` — regenerates the cross-platform test vectors with libsodium.

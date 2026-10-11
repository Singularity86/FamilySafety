# iOS Remediation Plan

From `CODE_REVIEW_2026-10-11.md` (same folder). IDs (F#, E#, A#) refer to that review.

The iOS port is at Phase 2 (crypto, models, transport). Most findings are in Android code that
iOS hasn't written yet. **The cheapest fix is never to port the flaw.** This plan has two parts:
fixes to code that exists today, and rules for Phases 3–7 so each one is built correctly the first
time.

**Wire** items must land in Android first (or in the same release) via `ios/IOS_PORT_SPEC.md`, so
the two apps keep interoperating. Check `app/REMEDIATION_PLAN.md` for the matching Android item
before implementing.

Paths are relative to `ios/FamilySafety/Sources/`.

---

## Part A — Fix in existing code (Phases 0–2)

### A.1 Constant subscriptions — E6 (before PR #13 merges)
- **What:** `MqttTransport.subscriptionSet` subscribes 2 wildcards + 1 presence filter per peer, so
  at ≥10 family members the broker's 10-filter quota is exceeded.
- **How:** follow Android item 4.2 — presence under `familysafe/group/{gid}/presence/{memberId}`,
  subscribe to exactly the two wildcards. Until Android ships it, at least fail loudly: surface
  `lastSubscribeFailures` in the UI or log, not only in a harness.
- **Done when:** a test asserts `subscriptionSet` has 2 entries for any family size.

### A.2 Refuse the legacy file key — F22
- **What:** `FamilySafetyCore/Files/SharedFileCrypto.swift` exposes only `deriveFileKey(groupId:)`,
  the public v1 key.
- **How:** when Phase 6 adds files, implement versions 2/3 only, and refuse v1/v2 for any group
  that has a group key. Mark `deriveFileKey` "legacy groups only" now so nobody builds on it.

### A.3 Shared-secret cache keyed by memberId only — crypto hygiene
- **What:** `E2ECrypto.sharedSecret` caches by `otherMemberId`. If a caller ever passes a different
  key for the same ID, the first secret wins forever.
- **How:** key the cache by `memberId + Hex(publicKey)`, or verify that
  `memberId == SHA-256(ed25519)[0..16]` before caching.

### A.4 Don't crash on bad input
- `Identity.swift`: `precondition(seed.count == 64)` → throw.
- `Hex.decode`: iterate over `utf8`, not `String.Index` (it's grapheme-based, so it's slow and
  odd-length checks are wrong for non-ASCII). `Hex.encode`: use a lookup table, not
  `String(format:)` per byte.

### A.5 Harness hygiene
- `ReconnectHarness` and `PresenceHarness` must use test identities only (they do) and must clear
  every retained message they plant (they do). Keep Keychain-only credentials. Never add a fallback
  that reads `keystore/mqtt.properties`.

## Part B — Build-in rules for Phases 3–7

| Phase | Rule | Ref |
|---|---|---|
| 3 Group sync + join | Implement **join v2** (QR secret + MAC + mandatory signature + fingerprint; approval signs both keys and the name) — Android 2.1. Never accept an unsigned or un-MACed join request. | F13 |
| 3 | Sign the **full** roster (Android 3.1); require envelope sender == `updaterMemberId`; members may only edit their own cosmetic fields; `addedAtEpochMs` immutable. | F25 |
| 3 | Treat "absent from roster" as **stale, not removed**. Only an authorized tombstone naming this device triggers leave/wipe. | F26 |
| 3 | Support `RemovalProof` and joinEpoch tombstones (PR #14), including quorum proofs. | PR #14 |
| 3 | `GroupStateRefreshRequest` and acks inside the E2EE envelope; apply per-sender rate limits; no auto-refresh on decrypt failure. | F16 |
| 3 | Envelope replay window: per-sender `seq`/`ts` high-water mark, persisted. | F16, A6 |
| 3 | Keychain storage: `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`; **never delete keychain items on a read error**. Distinguish `errSecInteractionNotAllowed` (locked, retry later) from `errSecItemNotFound`; a destructive reset only after the user re-enters the recovery phrase. | F14 |
| 3 | Recovery phrase: show only after `LAContext` device-owner authentication; hide content when `UIScreen.isCaptured` or on app backgrounding (iOS can't block screenshots — blur on `userDidTakeScreenshotNotification` and warn). | F15 |
| 3 | Presence carries a per-install `deviceId`; announce one identity active on two devices. | F15 |
| 4 Location | Drop any decrypted location whose `memberId` ≠ authenticated sender. Publish only the newest pending fix after reconnect. | F23, E2 |
| 4 | Drive time: on-device estimate or explicit opt-in; no silent OSRM. Map: MapKit (Apple's privacy terms) rather than public OSM tiles. | F28 |
| 5 Chat | **No push replication** of received messages. Author-signed messages (Android 3.2); dedup by messageId; verify the author on replicated insert. | E1, F24 |
| 5 | Emergency/crash alerts: durable outbox, highest priority, retried until receipt, per-recipient status. | F27 |
| 6 Files | v3 (group subkey) only for keyed groups; AAD = `fileId‖index‖keyVersion` once Android adds it; per-group manifest high-water mark. | F22, F12 |
| 6.5 Vault | Reject container `version > now + 1 day`; never replace an unreadable container with a random one; atomic replace (`replaceItemAt`). | F29, F31 |
| 7 Polish | LAN (if built — Network.framework/Bonjour): authenticated frames only, rotating opaque service names, no memberId in TXT, no unauthenticated avatar server. Consider skipping LAN on iOS entirely. | F18–F21 |
| 7 | Group key epochs (Android 3.3) for presence/manifest/vault metadata. | F17 |
| All | Bounded, ordered inbound processing (one `AsyncStream` per topic class); event-driven acks, no polling. | E5 |

## Part C — Spec work

Each Android wire item (2.1, 2.2, 3.1–3.4, 4.2) must update `ios/IOS_PORT_SPEC.md` in the same PR.
Track which items are in the spec and which are implemented on iOS in `IOS_PROJECT_STATUS.md`.

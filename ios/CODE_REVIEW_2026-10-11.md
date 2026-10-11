# FamilySafety — Code Review, 2026-10-11

Security, efficiency and architecture review of the whole codebase. **Identical copies live in
`app/` and `ios/`** — edit both or neither. This file replaces the old root `SECURITY_REVIEW.md`
(2026-08-08 / 2026-08-17); its findings F1–F12 and the F2 risk decision are carried forward in
§5 so nothing is lost. The full old text is in git history (`git show 81dad04:SECURITY_REVIEW.md`).

The action lists derived from this review are `app/REMEDIATION_PLAN.md` (Android) and
`ios/REMEDIATION_PLAN.md` (iOS).

## 1. Scope and method

- Reviewed against `main` at `81dad04` (Android 1.15.1) plus the iOS branch `ios/reconnect-harness`
  (`7da375d`) and the diffs of open PRs #12 (Android wildcard subscriptions) and #14 (relayed
  removals / rejoin).
- **Read in full:** every Android non-UI package — `crypto`, `core`, `group`, `sync`, `invite`,
  `transport` (MQTT + LAN + routing), `storage`, `location`, `replication`, `chat`, `files`,
  `vault`, `billing`, `backup`, `crash`, `geofence`, `avatar`, `routing`, `offline`,
  `AppInitializer`, the manifest and `build.gradle.kts`; all iOS Swift (`FamilySafetyCore`,
  harnesses, harness app).
- **Searched, not read line by line:** the ~9k lines of Compose screens in `main/` and
  `onboarding/` — targeted at secret display, clipboard, intents, external storage and the join flow.
- Code reading only; nothing was exploited on the live broker. Findings marked *(insider)* need a
  family member or someone holding a member's unlocked phone; *(outsider)* needs only the shared
  broker credential (F2, extractable from the APK) or presence on the same Wi-Fi.

Paths below are relative to `app/src/main/java/com/example/familysafety/` unless they start with
`ios/`.

## 2. Priority summary

| # | ID | Finding | Kind | Severity |
|---|---|---|---|---|
| 1 | F13 | Outsider can be approved into the family in place of a real joiner | Security | **High** |
| 2 | F14 | One storage exception silently wipes identity, recovery phrase and all data | Data loss | **High** |
| 3 | F15 | Recovery phrase shown with no re-auth; restoring it is auto-approved → invisible clone | Security (insider) | **High** |
| 4 | E1 | Every received group chat message is re-broadcast by every recipient (N²) | Efficiency | **High** |
| 5 | F16 | Outsider-triggered roster re-broadcast storms (unsigned refresh, replay, forged decrypt failure) | Security / cost | Medium |
| 6 | F17 | Group key never rotates when a member is removed | Security | Med-High |
| 7 | F18–F21 | LAN: unauthenticated injection, stable tracking ID, avatar token broadcast, blackholing | Security / privacy | Medium |
| 8 | F22 | Legacy public file key still accepted → outsider file poisoning loop | Security / cost | Medium |
| 9 | F23–F25 | Insider spoofing: location memberId, replicated chat sender, unsigned roster fields | Security (insider) | Medium |
| 10 | F26 | Device wipes its own family data when briefly absent from a roster | Availability | Medium |
| 11 | F27 | Crash alert rides best-effort chat; failed group messages never retried | Safety | Medium |

## 3. Security findings (new)

### F13 — Join requests are unauthenticated; an outsider can be approved instead of the joiner (High, outsider)

- The QR invite is base64 JSON `{groupId, groupName, inviterMemberId, inviterName, timestamp}` —
  **no secret** (`invite/InviteManager.kt:216`).
- The join request is published **plaintext and retained** on `familysafe/{inviterId}/join_request`
  and decoded directly (`invite/InviteManager.kt:111`).
- The joiner signs `JOIN:v1:…` binding both keys (`onboarding/OnboardingViewModel.kt`), but the
  inviter only verifies that signature for *existing* members (`invite/RejoinPolicy.kt`). New members
  fall to `AskInviter` (`InviteManager.kt:140`) with no check, and `signature` is optional, so it can
  be stripped.
- The approval prompt shows only the unauthenticated `displayName`. The approval signature
  `ADD:{gid}:{ver}:{ed25519}` does not cover the X25519 key or the name.

**Attack:** subscribe to `familysafe/+/join_request`. When a retained request appears, republish it
(overwriting the retained message) with the attacker's own keys/memberId and the same display
name. The inviter approves "Sascha". The attacker receives the roster including `fileEncryptionKey`
and, as a member, every family member's live location, chat, files and presence. A lighter variant
swaps only the X25519 key and reads everything addressed to the real joiner.

**Fix:** carry a one-time secret in the QR (out-of-band channel) and require the joiner to prove it
(MAC over `signingBytes()`, or encrypt the request to the inviter's X25519 key published in the
QR); make the request signature mandatory; show a short key fingerprint on both screens; sign the
full `FamilyMember` in the approval. Wire change → spec + iOS.

### F14 — Generic exceptions wipe keys; two stores share one Keystore alias (High, data loss)

- `storage/FamilySafetyDatabase.kt:292` catches **any** exception while reading the DB passphrase
  and calls `clearAll()`, which deletes `MasterKey.DEFAULT_MASTER_KEY_ALIAS` (`:307`) and the
  SQLCipher database.
- The identity store (`group/AndroidKeyStoreLocalKeyStore.kt`) encrypts keys and the mnemonic with
  that **same default alias**. Once it's gone, the identity store throws `GeneralSecurityException`,
  and its own catch (`:63`) wipes keys, mnemonic and onboarding state.
- So one transient Keystore failure means a silent, total loss of identity, recovery phrase,
  history, chat and files. The cascade also runs the other way.
- **Same pattern elsewhere:** `GroupStatePersistence.loadGroupDefinition` returns `null` ("no
  group") on any error, which then permits `createGroup` to overwrite the stored family; and
  `VaultContainer.readLocked` (F31).

**Fix:** a separate alias per store; never delete keys on a generic exception; distinguish
"corrupt" from "temporarily unavailable"; surface it to the user and require confirming the
recovery phrase before any destructive reset.

### F15 — Recovery phrase reveal has no re-authentication; restore is auto-approved (High, insider / physical access)

- Settings → "View Recovery Phrase" shows the mnemonic after a warning dialog only
  (`main/SettingsScreen.kt:1126`). There is no device-credential/biometric gate and no `FLAG_SECURE`
  (only `VaultScreen` sets it; `GenerateMnemonicScreen` doesn't either).
- Restoring that phrase on a second phone is **auto-approved** (`invite/RejoinPolicy.kt:52`), with no
  prompt to anyone. Ten seconds with an unlocked phone gives a persistent, invisible clone that
  receives every member's live location — the abuse scenario a family-safety app must assume.
- Export Backup is likewise ungated.

**Fix:** `BiometricPrompt` / `KeyguardManager` gate on reveal and export; `FLAG_SECURE` on all phrase
screens; detect and announce one memberId active on two devices.

### F16 — Outsider-triggered roster re-broadcast storms (Medium, outsider / cost)

Each of the following makes a member re-broadcast the whole roster to all N−1 peers, plus a 30 s
ack wait:

- `GroupStateRefreshRequest` on `sync_request` is plaintext and unsigned; the requester isn't
  checked to be a member (`sync/GroupSyncManager.kt:253`). `groupId` is public (topic names).
- No envelope-level replay protection: replaying a captured older sync message triggers
  `VERSION_SYNC` (`GroupSyncManager.kt:332`).
- Garbage on `location_inbox` carrying a real member's `senderMemberId` fails decryption and
  triggers a refresh request to every peer (`transport/MqttTransport.kt:977`, 60 s cooldown per
  claimed sender). It also fills the Security screen with false "decrypt failure" alarms.
- `RateLimiters.groupSync` and `joinRequests` exist (`core/RateLimiter.kt:98`) but are never applied.
- Acks (`GroupUpdateAck`) are plaintext and forgeable (fake "Synced").

**Fix:** encrypt+sign refresh requests; a replay window (per-sender monotonic counter or timestamp
plus seen-cache) in the envelope layer; apply the existing rate limiters; drop the decrypt-failure
auto-refresh or rate-limit it globally.

### F17 — The group key never rotates on removal (Med-High)

`fileEncryptionKey` is generated once and "never rotates" (`group/GroupStateMerge.kt:117`). A
removed member keeps it, and with the shared broker credential (F2) can still decrypt sealed
presence, the file manifest (names), and file contents shared after their removal. Location and chat
are per-recipient, so they do stop. **Fix:** epoch-based group key rotated on every removal,
distributed per-recipient; key version on every group-key message (already exists for files).

### F18–F21 — LAN transport and avatar discovery (Medium, outsider on same Wi-Fi)

- **F18 — injection.** `LocalTransport` accepts unauthenticated TCP from anyone on the network and
  passes the frame's attacker-chosen `topic` straight to the router
  (`transport/LocalTransport.kt:228`). That gives broker-outsider powers (plaintext join requests,
  sync requests, acks, pre-signature presence) without needing the broker credential. The router
  never checks that a topic is addressed to this device.
- **F19 — tracking.** NSD advertises `familysafety-<16 hex of memberId>` (`LocalTransport.kt:310`), and
  the avatar service `fs-<16 hex>` adds the **full** memberId in TXT. That's a stable cross-network
  identifier, and it reveals that a family-tracking app is installed. Use rotating opaque names
  (e.g. `HMAC(groupKey, day)`) or only advertise on networks the family has used.
- **F20 — avatar photos.** `AvatarHttpServer` (plain HTTP, all interfaces) "protects" the photo with
  a token that is broadcast in cleartext in the mDNS TXT record (`avatar/AvatarDiscovery.kt:69`), so
  anyone on the Wi-Fi can fetch every member's (children's) photo. The body is read unbounded →
  OOM DoS. (Hash check against the roster prevents spoofing — good.)
- **F21 — blackholing.** A device advertising a victim's prefix becomes their LAN address. `send()`
  returns true, so the MQTT fallback never runs, and the member's sync/chat/location is silently
  dropped while on that network.
- Also: concurrent `send()` calls share one socket without a lock, so frames interleave and the
  receiver desyncs; there's no inbound connection limit.

**Fix:** authenticated LAN handshake (Noise/ECDH with roster keys, or wrap frames in the existing
signed envelope and require the sender be a roster member), check topic ownership in the router,
delivery acks before declaring success, a per-socket write lock, and a size cap on avatar fetch.

### F22 — Legacy public file key still accepted (Medium, outsider / cost)

`keyForVersion` still returns the version-1 key `SHA-256(groupId + "familysafety-files-v1")`, built
entirely from public values, for chunks and manifests even in groups with a group key
(`files/SharedFileRepository.kt:1416`).

**Attack:** an outsider (who knows `groupId` and `fileId` from topic names) publishes v1 chunks. They
pass GCM; the first pins `blobKeyVersion = 1`, so genuine v3 chunks are dropped. The final hash then
mismatches, the file resets to PENDING and re-downloads — a repeatable poisoning loop that burns
quota. Chunks also have no AAD binding to `(fileId, index, keyVersion)`.

**Fix:** refuse v1/v2 once the group has a key; add AAD.

### F23–F25 — Insider spoofing (Medium, insider)

- **F23 — location.** `handleEncryptedLocation` never checks that the decrypted
  `LocationUpdate.memberId` equals the authenticated sender (`transport/MqttTransport.kt:999`). Any
  member can publish a location *as* another member (e.g. a child "at school"). A fix stamped +4 min
  (the validator allows +5) also blocks the victim's real fixes for minutes. One-line fix.
- **F24 — chat via replication.** Replicated messages keep their claimed `senderId`
  (`replication/ReplicationProtocol.kt:179`). Unsolicited responses are accepted, so any member can
  inject group chat "from" anyone — including from the recipient themselves, which renders as their
  own outgoing message — and inject location history for any member. **Fix:** author signatures on
  messages and fixes, verified on replicated insert.
- **F25 — roster fields outside the signature.** `computeStateHash` (`group/Models.kt:183`) and the
  sync signature (`sync/GroupSyncManager.kt:702`) omit `displayName`, `avatarHash`, `colorHue`,
  `addedAtEpochMs` (which decides quorum succession), `fileEncryptionKey`, `previousStateHash`,
  billing fields and `grantCode`. The validator never checks other members' records are unchanged.
  The envelope sender isn't required to equal `updaterMemberId`, so a member can re-wrap another
  member's signed message with altered unsigned fields under *their* authority.
  **Fix:** sign the full canonical state (or per-field ownership rules), require envelope sender ==
  updater, make `fileEncryptionKey` immutable except via rotation.

### F26 — Absence from a roster wipes the device (Medium, availability)

`commitStateLocked` emits `RemovedFromGroup` whenever the new roster lacks us
(`group/GroupStateManager.kt:975`). `AppInitializer` then stops sharing and deletes group state.

**Scenario:** a creator's device holding a stale roster broadcasts a version jump. The chain check
only applies at +1, and creators may remove without a tombstone, so a member added elsewhere gets
"removed" and their phone silently wipes itself. **Fix:** treat as removal only with an authorized
tombstone naming us; otherwise reconcile.

### F27 — Crash alerts are best-effort (Medium, safety)

`CrashAlertActivity` sends the emergency alert via `sendGroupTextMessage` and ignores the result
(`crash/CrashAlertActivity.kt:105`). If offline, the message is FAILED and **never retried** —
`retrySendingFailedMessages` skips group messages (`chat/ChatRepository.kt:362`). The transport's
copy sits in an in-memory bulk queue (200 max, 1 h) and dies with the process.

**Fix:** durable outbox, control-plane priority, retry until acknowledged, per-recipient receipts
shown on screen.

### F28 — Third-party location disclosure (Medium, privacy)

"Drive time" sends both members' exact coordinates to the public OSRM demo server
(`routing/RoutingService.kt:104`), whose usage policy also forbids app use. Map tiles come from
`tile.openstreetmap.org`, so requests reveal the areas viewed — i.e. where family members are. The
tile cache lives in external app storage. **Fix:** disclose, estimate on-device, or self-host.

### F29–F33 — Vault, storage, misc (Low–Medium)

- **F29 — vault freeze (insider):** any member can publish a signed container with
  `version = Long.MAX_VALUE`. The persisted high-water mark then rejects every later container
  forever (`vault/VaultRepository.kt:406`).
- **F30 — vault lost update:** whole-container last-writer-wins (`version = max(cur+1, now)`), so
  concurrent edits by two members lose one; a clock-skewed device wins.
- **F31 — vault wipe on read error:** `readLocked` returns a fresh random container when
  `readBytes()` throws or the size is wrong (`vault/VaultContainer.kt:42`). `update()` then writes
  it back, destroying every slot. `writeLocked` deletes before `renameTo` (non-atomic window).
- **F32 — geofence race:** one shared `FloatArray` and a plain map across concurrent `checkLocation`
  calls (`geofence/GeofenceMonitor.kt:42`) → wrong distances → false or missed alerts.
- **F33 — misc low:**
  - Retained removal votes and join approvals are never cleared.
  - Join attempts use random clientIds with `cleanSession=false`, leaving orphan broker sessions.
  - `BootRecoveryReceiver` is exported and ignores `intent.action`.
  - The backup file is left in external app storage with no password floor (PBKDF2 200k vs Argon2id
    elsewhere).
  - Incoming rosters get no size/shape validation (`validateGroupDefinition` is never called).
  - `sanitizeDisplayName` strips non-ASCII names.
  - `fallbackToDestructiveMigrationOnDowngrade` deletes all data on a downgrade.
  - Diagnostics copied to the clipboard include member IDs/keys.
  - Release builds have R8 disabled.
- **Billing is honor-system by design:** any modified client can set
  `subscriptionConfirmedAtEpochMs`, backdate `createdAtEpochMs` or move the clock. A business risk,
  recorded rather than queued.

## 4. Efficiency

| ID | Finding | Where | Fix |
|---|---|---|---|
| E1 | Each receiver re-broadcasts every group chat message to all N−1 peers; the sender sends N−1 live + N−1 replication copies. ≈143 encrypted messages per chat line for 12 people. Likely a major part of the 1 GB/month overage. | `chat/ChatRepository.kt:466`, `replication/ReplicationManager.kt` | Remove push replication; rely on announce→request gap filling. |
| E2 | After an offline stretch every queued fix is sent to every peer; receivers keep only the newest. | `location/LocationService.kt:750` | Send the newest only; replication backfills history. |
| E3 | Wire format ≈2.5× payload: hex ciphertext in JSON, base64 again on LAN, padded JSON inside; per-recipient fan-out per fix. | `crypto/E2EEManager.kt`, `transport/` | Binary/base64 envelope at the next wire bump; group-epoch key (A2). |
| E4 | `announceDataAvailability` loads every message of every conversation to count them, every 30 min; location summary is 3 queries per member. | `replication/ReplicationManager.kt:598` | SQL `MIN/MAX/COUNT GROUP BY`. |
| E5 | `waitForAcknowledgments` busy-polls 500 ms for up to 30 s inside the message-handling path; acks go to the whole group (N²); every inbound message gets its own unbounded, unordered coroutine. | `sync/GroupSyncManager.kt:462`, `transport/MqttTransport.kt` | Event-driven acks, ack to sender only, bounded ordered inbound channel. |
| E6 | PR #12 subscribes 2 wildcards + 1 presence filter per peer, so at ≥10 members it exceeds the 10-filter quota again. | PR #12 | Move presence under `familysafe/group/{gid}/presence/{memberId}` (LWT can use any topic) → constant 2 filters. |
| E7 | Small stuff: LAN peers get duplicates (broadcast goes MQTT + LAN); decrypt retried though deterministic; hex via `String.format` per byte; QR drawn with `setPixel` ×262k; R8 off; `hasMore` never followed (backfill capped at 500 per 30 min). | various | — |

## 5. Architecture

- **A1 — Roster replication without carried proof.** The whole `GroupDefinition` is replicated and
  authorization is judged against whoever sent it last. Fields outside the signature are mutable by
  any member (F25). Billing and cosmetic updates bump the security-critical version and raise
  conflict rates. A signed operation log (or per-change proofs, which PR #14 starts for removals)
  would close F25, F26 and the relay gaps together. Move billing and cosmetics out of the versioned
  roster.
- **A2 — Keys.** The never-rotated group key (F17) means per-recipient fan-out buys no revocation for
  group-key data while costing N−1 encryptions per fix. An epoch group key, rotated on membership
  change and delivered per-recipient, fixes F17 and E3 together.
- **A3 — "Catch everything, then wipe or treat as empty"** recurs in the keystore, DB, group
  persistence and vault (F14, F31). Adopt a rule: never destroy data on a generic exception.
- **A4 — Wiring.** `AppInitializer` hand-wires 16 singletons, with setter injection for cycles
  (`MqttTransport.groupSyncManager`). `isInitialized` is set after async work and isn't
  synchronized, so `LocationService` and `MainActivity` can initialize concurrently.
  `MqttTransport` handles location and presence itself while `UnifiedTransportManager` routes the
  rest by topic suffix.
- **A5 — Duplication and dead code.**
  - **Duplicated:** ~8 hex helpers, 6 `LazySodiumAndroid` instances, two E2E schemes
    (`CryptoProvider.encryptForRecipient` is unused), regex sender extraction duplicating
    `peekSenderMemberId`.
  - **Dead:** `ConnectivityHandler`, `OfflineCache` (would store the roster incl. group key in
    *plaintext* if ever wired), `validateGroupDefinition`, unused rate limiters, three identical
    `BrokerConfig` environments, old `subscribeToOwnTopics`/`subscribeToMember` paths.
- **A6 — Envelope layer.** No replay window, plaintext control messages (join, sync request, ack),
  and dispatch by topic suffix. One shared "authenticated, replay-checked, addressed-to-me" ingress
  step would close F16, F18 and parts of F13.
- **A7 — Test gaps:** `LocalTransport`, `UnifiedTransportManager` routing, storage wipe paths,
  `AppInitializer`, `VaultRepository`, geofence concurrency, join impersonation.

### Open PRs

- **#14 (relayed removals):** sound — `RemovalProof` binds tombstone + joinEpoch and is signed by the
  creator or the member themselves. Remaining gaps:
  - Quorum removals carry no proof, so they still can't be relayed.
  - A proof from a creator who later transferred the role fails on lagging devices (the authorizer
    must be the *current* creator).
  - Any member can re-add a removed member at a new epoch without the creator's consent — risky until
    F13 is fixed.
  - F26 is unchanged.
- **#12 (wildcards):** see E6.

## 6. Earlier findings carried forward (from the old `SECURITY_REVIEW.md`)

| ID | Finding | Status |
|---|---|---|
| F1 | Shared files decryptable from public `groupId` | Fixed 1.12.0 for groups with a key — **but see F22: v1 is still accepted** |
| F2 | One shared broker credential in every APK; no ACLs | **Accepted risk** (decision below) |
| F3 | Metadata exposed (member IDs in topics, movement timing) | Online/offline fixed 1.12.5; IDs and timing open (structural) |
| F4 | Presence forgeable | Fixed 1.12.3 (Ed25519-signed, replay + downgrade checks) |
| F5 | Retained-message pollution | Fixed 1.12.4 (old litter needs manual clearing) |
| F6 | Message length leaked behaviour | Fixed (fixed-size padding: presence and location 512 B). Chat, replication and sync still unpadded by choice |
| F7 | File names in cleartext manifest | Fixed 1.12.2 (group-key-encrypted, padded) |
| F8 | Replayed vault container rolled back after restart | Fixed (persisted high-water mark) — **see F29** |
| F9 | Vault chunks unauthenticated and unbounded | Fixed (bounded); member-driven storage use is inherent |
| F10 | Vault container = permanent offline guessing target | Inherent; mitigated 2026-08-18 (16-char floor, footer) |
| F11 | Vault eligible for device transfer | Fixed |
| F12 | File manifest not replay-protected | **Open** (needs a per-group high-water mark like F8) |

**F2 decision (2026-08-10, reaffirmed 2026-08-21 with the vault in view): accepted.** The broker
operator is outside the threat model. App-layer signing and encryption make the credential an
abuse-control measure, not a security boundary — except for the retained vault container, which
anyone holding the credential can guess offline forever (Argon2id INTERACTIVE + 16-char floor are the
only costs).

**Reopen if:**
- the broker serves people beyond friends and family;
- the threat model comes to include the operator or anyone who compromises it;
- the user population can't accept "the relay learns your household's rhythm";
- a family keeps something in the vault whose disclosure matters more than recreating the group.

The known fix: per-device credentials (username = memberId) plus ACLs (`subscribe familysafe/{me}/#`,
publish only own presence) and an ACL on the vault container.

**Note for this review:** F13, F16, F18 and F22 are all *outsider* attacks that the F2 credential
makes available to anyone who downloads the app. F2's original reasoning ("every message is signed
and encrypted, so the credential is not a boundary") does not hold for the plaintext control
messages those findings use. That is a reason to fix those message types; it doesn't by itself
reopen F2.

## 7. What's solid

- **Presence:** signed, with replay and downgrade checks.
- **Padding:** fixed-size padding for presence and location.
- **Vault:** Argon2id with deniable slots.
- **Removals:** tombstones and quorum votes bound to version and previous hash.
- **Subscriptions:** SUBACK 0x80 checking and the resubscribe skip.
- **Signed objects:** manifests and vault containers are signed.
- **Storage:** SQLCipher at rest; `allowBackup=false`.
- **Chat:** dedup by messageId on the live path, with sender bound to the authenticated envelope.
- **Purchases:** on-device purchase signature verification.
- **iOS:** decrypt takes the sender explicitly instead of trusting the envelope's claim.

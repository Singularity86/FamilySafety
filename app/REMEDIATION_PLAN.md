# Android Remediation Plan

From `CODE_REVIEW_2026-10-11.md` (same folder). IDs (F#, E#, A#) refer to that review. Work top
to bottom; each item says **what**, **how**, and **done when**.

**Wire** marks a change to what goes over the broker. That needs an `ios/IOS_PORT_SPEC.md` update
in the same PR, plus a matching item in `ios/REMEDIATION_PLAN.md`. Keep `PROTOCOL_VERSION` checks
in mind: older peers must keep working, or the release notes must say why not.

Paths are relative to `app/src/main/java/com/example/familysafety/`.

---

## Phase 1 — Stop the bleeding (small, no wire change)

### 1.1 Never wipe keys on a generic exception — F14 (High)
- **How:**
  - `storage/FamilySafetyDatabase.kt`: delete `clearAll()`'s Keystore deletion. Use a dedicated
    `MasterKey` alias for the DB-passphrase prefs (e.g. `familysafety_db_master`).
  - `group/AndroidKeyStoreLocalKeyStore.kt`: use its own alias (`familysafe_identity_master`); keep
    reading the old default alias for existing installs, then migrate.
  - Both stores, on failure: throw a typed `StorageUnavailable` instead of wiping. The UI shows
    "storage temporarily unavailable — retry"; offer a reset only after the user re-enters their
    recovery phrase.
  - `group/GroupStatePersistence.kt`: distinguish "no file" from "decrypt failed". On failure,
    refuse `createGroup` and surface the error instead of returning `null`.
- **Done when:** unit tests simulate a `KeyStoreException` and a `GeneralSecurityException` on each
  store and assert that no file or alias is deleted and that the other store is untouched.

### 1.2 Bind location sender — F23 (one line)
- **How:** in `transport/MqttTransport.kt` `handleEncryptedLocation`, drop the update unless
  `locationUpdate.memberId == senderId`.
- **Done when:** a test sends B-signed `memberId=A` and asserts A's location is unchanged.

### 1.3 Stop N² group-chat replication — E1 (High efficiency)
- **How:** remove the `replicateChatMessage` call from the receive path
  (`chat/ChatRepository.kt:466`) and the send paths. Gap-filling already works through
  `announceDataAvailability` → `ReplicationRequest`.
- **Done when:** sending one group message produces exactly N−1 publishes (test via a fake
  `TransportProvider`).

### 1.4 Gate the recovery phrase — F15 (High)
- **How:**
  - `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` before `getMnemonic()`
    (`main/SettingsScreen.kt:1126`) and before Export Backup.
  - `FLAG_SECURE` on the phrase dialog, `GenerateMnemonicScreen` and `ConfirmMnemonicScreen` (same
    `DisposableEffect` pattern as `VaultScreen`).
  - Enforce the 16-char floor on the backup password.
- **Done when:** the phrase can't be shown without the device credential, and a screenshot of those
  screens is black.

### 1.5 Crash alert must be durable — F27 (Safety)
- **How:**
  - Fix `retrySendingFailedMessages` to retry group messages: iterate over members when
    `recipientId == null`.
  - Give emergency messages their own durable outbox row (reuse the `pending_location_publishes`
    pattern), make them control-plane priority (`MqttConfig.isControlPlaneTopic`), and retry with
    backoff until each recipient's DELIVERED receipt arrives.
  - Show per-person delivery on `CrashAlertActivity`.
- **Done when:** an alert raised offline is delivered after reconnect, including after a process
  kill.

### 1.6 Refuse the legacy file key when a group key exists — F22
- **How:** `files/SharedFileRepository.kt` `keyForVersion`: if `groupKeyHex() != null`, return
  `null` for versions 1, 2 and 0. Apply the same rule in `decodeManifest`.
- **Done when:** `FileKeyVersionTest` covers a v1 chunk being refused in a keyed group.

### 1.7 Apply the rate limiters that already exist — F16 (partial)
- **How:** `RateLimiters.groupSync` around `broadcastGroupUpdate` triggered from
  `handleSyncRequestMessage` and the `VERSION_SYNC` path; `RateLimiters.joinRequests` keyed by
  requester in `handleIncomingJoinRequest`. Remove the decrypt-failure auto-refresh in
  `MqttTransport` (`:977`), or make its limit global rather than per claimed sender.
- **Done when:** 100 forged refresh requests produce at most the limiter's quota of broadcasts.

### 1.8 Small correctness fixes
- `geofence/GeofenceMonitor.kt` — local `FloatArray` per call; `ConcurrentHashMap` for state (F32).
- `vault/VaultContainer.kt` — on read error **throw**, never fall back to `fresh()` for an existing
  file; drop the `delete()` before `renameTo` (F31).
- `vault/VaultRepository.kt` — reject `version > now + 1 day` (F29).
- `transport/LocalTransport.kt` — a per-peer `Mutex` around socket writes; write header + body in
  one buffered write.
- `avatar/AvatarRepository.kt` — cap the HTTP body (e.g. 512 KB) before `readBytes()`.
- `location/LocationRepository.kt` — `_memberLocations.update { }`; `getTodayHistory` uses local
  midnight.
- `location/BootRecoveryReceiver.kt` — ignore unexpected actions.
- `location/LocationService.kt` — on flush, publish only the newest pending fix and delete older
  ones (E2).

## Phase 2 — Join and identity hardening (wire)

### 2.1 Authenticated join — F13 (High)
- **How:**
  1. Invite QR v2 adds `inviteSecret` (32 random bytes, base64), `inviterEd25519` and
     `inviterX25519`, and an expiry. The inviter stores the pending secret locally (single use, 24 h).
  2. The joiner encrypts the `JoinRequest` to `inviterX25519` with the existing E2EE envelope. It
     includes `mac = HMAC-SHA256(inviteSecret, signingBytes())`, and the signature is now mandatory.
  3. The inviter decrypts, verifies the signature and the MAC, and consumes the secret.
  4. Both screens show a 6-digit fingerprint (`SHA-256(ed25519‖x25519)` truncated) for an optional
     visual check.
  5. The approval signs `ADD:v2:{gid}:{ver}:{ed25519}:{x25519}:{displayName}`.
  6. Keep accepting v1 requests for one release behind a visible "unverified request" warning, then
     refuse them.
- **Done when:** a test replaces the retained request with attacker keys and the inviter refuses it.
  Spec §8 is updated.

### 2.2 Announce cloned identities — F15
- **How:** presence carries a random per-install `deviceId` (inside the signed payload). Peers that
  see two `deviceId`s for one memberId within N minutes notify the family: "<name> is signed in on
  another device". `RejoinPolicy.AutoApprove` posts the same notice.
- **Done when:** a restore on a second phone raises the notice on other members' phones.

### 2.3 Treat removal only via tombstone — F26
- **How:** in `GroupStateManager.commitStateLocked`, emit `RemovedFromGroup` only if
  `newState.removedMemberIds` contains our tombstone (with PR #14, `Tombstones.key(us)`) and the
  transition was authorized. If we are merely absent, keep our state and request a refresh.
- **Done when:** a stale creator broadcast without us no longer wipes the device (regression test).

## Phase 3 — Sign what matters (wire)

### 3.1 Sign the full roster — F25, A1
- **How:**
  - Add `computeFullStateHash()` over a canonical encoding of every field except
    `previousStateHash`; sign that in `createSyncSignaturePayload`, with a version flag.
  - Require the envelope sender == `syncMessage.updaterMemberId`.
  - In the validator, a member may change only their own `displayName`/`avatarHash`/`colorHue`;
    `addedAtEpochMs`/`addedByMemberId` are immutable after add; `fileEncryptionKey` changes only via
    rotation (3.3); billing fields may only be set by the member named in `subscriberMemberId`.
- **Done when:** validator tests cover each rule.

### 3.2 Author-signed chat and location history — F24
- **How:** add `authorSig = Ed25519(author, messageId|conversationId|timestamp|contentHash)` to
  `ChatMessagePayload` and `ReplicatedMessage`, and the same idea for `ReplicatedLocation`. Verify on
  insert from replication and drop anything without a valid signature (after a one-release grace).
- **Done when:** a replicated message claiming another sender is dropped.

### 3.3 Rotate the group key on removal — F17, A2
- **How:**
  - `GroupDefinition.groupKeys: Map<epoch, keyHex>` replaces `fileEncryptionKey` (keep reading the
    old field as epoch 0). On every removal, the remover generates epoch+1 and broadcasts it inside
    the per-recipient group-sync envelope.
  - Presence, manifest, files and vault-container metadata carry `keyEpoch`.
  - Decrypt with the matching epoch; encrypt with the newest.
- **Done when:** after a removal, the removed member's key can't open a newly sealed presence or
  manifest (test).

### 3.4 Replay window and authenticated control messages — F16, A6
- **How:**
  - Add `seq` (per-sender monotonic, persisted) and `ts` inside the E2EE plaintext of every
    envelope. Receivers keep a per-sender high-water mark and drop older ones.
  - Move `GroupStateRefreshRequest` and `GroupUpdateAck` into the E2EE envelope; send acks to the
    updater's inbox only.
- **Done when:** a replayed sync message produces no broadcast.

## Phase 4 — Network and privacy

### 4.1 LAN authentication and anonymity — F18–F21
- **How:**
  - Frames must be complete E2EE envelopes from a roster member, addressed to us. Reject any frame
    whose topic isn't `familysafe/{me}/…`.
  - Replace NSD names with `fs-` + `HMAC(groupKey, utcDay)[0..8]`; drop `mid` from TXT.
  - Avatar: serve only over the authenticated LAN channel (or fetch via MQTT), and drop the TXT token.
  - Treat a LAN send as delivered only after a LAN ack; otherwise fall back to MQTT.
  - Cap concurrent inbound connections.
- **Done when:** a test peer without roster keys can't inject; NSD names change daily.

### 4.2 Constant subscriptions — E6 (fix PR #12 before merge)
- **How:**
  - Publish presence (and the LWT) to `familysafe/group/{gid}/presence/{memberId}`; subscribe to
    exactly `familysafe/{me}/#` and `familysafe/group/{gid}/#`.
  - Dual-publish to the old presence topic for one release.
- **Done when:** a 12-member family uses 2 subscriptions.

### 4.3 Third-party location leaks — F28
- **How:**
  - Drive time: on-device estimate (haversine × road factor, speed profile), or a self-hosted OSRM
    behind an explicit opt-in that names the provider.
  - Tiles: a self-hosted or commercial tile provider with terms that permit app use, disclosed in
    `PRIVACY_POLICY.md`.
  - Move the tile cache to internal storage.

## Phase 5 — Efficiency and architecture cleanup

| Item | How | Ref |
|---|---|---|
| Announce queries | `SELECT conversationId, MIN(ts), MAX(ts), COUNT(*) GROUP BY` in `ChatMessageDao`; same for locations | E4 |
| Acks | Replace polling with a `CompletableDeferred` per version; ack to updater only | E5 |
| Inbound processing | One bounded `Channel` per topic class, consumed in order; drop when full | E5, F16 |
| Wire size | At the next protocol bump: base64 instead of hex in `EncryptedMessage`, no base64 re-wrap on LAN | E3 |
| `hasMore` | Follow `hasMore` with the next page request | E7 |
| Billing out of roster | Move subscription/grant fields to their own signed, unversioned message | A1 |
| Startup | Make `AppInitializer.initialize` single-flight (`Mutex` + `AtomicBoolean` set at start) | A4 |
| Dedupe | One `Hex` util, one `LazySodiumAndroid`, delete `CryptoProvider.encryptForRecipient/decryptFromSender` | A5 |
| Dead code | Delete `ConnectivityHandler`, `OfflineCache`, unused validators, extra `BrokerConfig` environments, old subscribe paths | A5 |
| Build | `isMinifyEnabled = true` with keep rules for kotlinx-serialization, Room, Hilt, Lazysodium/JNA | E7 |
| Hygiene | Clear retained votes after tally; ephemeral join clients use `cleanSession=true`; delete the exported backup file after the share sheet returns | F33 |
| Tests | Add coverage for `LocalTransport`, `UnifiedTransportManager`, storage failure paths, `AppInitializer`, `VaultRepository`, join impersonation | A7 |

## Coordination with open PRs

- **#12:** fold in 4.2 before merge, or merge and follow immediately — at 10+ members it exceeds the
  quota.
- **#14:** before merge, issue a `RemovalProof` for quorum removals too (the quorum signatures are
  the proof), and accept proofs from a creator valid *at the proof's version*. Consider requiring
  creator consent to re-add a removed member until 2.1 lands.

## Not planned (accepted)

- **F2** shared broker credential — accepted; reopen conditions are in the review §6.
- **F3** remainder (IDs in topics, movement timing) — structural.
- **F10** vault offline guessing — inherent while F2 stands.
- Billing is honor-system by design.

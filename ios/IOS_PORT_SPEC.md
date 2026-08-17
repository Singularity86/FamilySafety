# FamilySafety — iOS Port Specification & Interop Contract

**Version 1.10 — generated 2026-07-03 from the Android codebase (branch `ui-refactor`),
revised 2026-08-16 against Android `main` at 4f3c1f0, which is 1.12.10 (versionCode 28) plus
the unreleased shared-file rebuild and the family vault.**

> The shared-file rebuild that landed in six phases is now fully written up: targeted repair
> and the durable outbox (§6.7), `isEssential` and holdings announcements (§6.7), manifest
> signatures and deletion tombstones (§6.7), and the vault (§6.8). Nothing in the file or
> vault subsystems is left to be read out of the Android source.

All additions since 1.0 are optional fields that older senders omit, so the wire stays
backward compatible. Two of them still change what a correct implementation must do:

- **1.1**, shared files (§6.5, §6.7, §7.1): `GroupDefinition.fileEncryptionKey` and
  `FileChunkMessage.keyVersion`. Ignoring `keyVersion` means failing to decrypt files
  from Android 1.12.0+.
- **1.2**, envelope padding (§6.1): `MessageEnvelope.pad`. Ignoring it on *receive* is
  harmless, but failing to emit it on *send* leaks online/offline and movement through
  message length — see §6.1 for the algorithm and why.
- **1.3**, manifest encryption (§6.7): `EncryptedFileManifest` now wraps `FileManifest`
  on the retained manifest topic, and `FileManifest` gains `pad`. This one is **not**
  purely additive in effect — a receiver that only understands the bare manifest will
  see no files at all from Android 1.12.2+, because the published shape has changed.
- **1.4**, presence signatures (§6.2): `PresenceUpdate.signature`, with the canonical
  signing string and the replay/downgrade rules receivers must apply. Also raises the
  presence padding target from 256 to **512** bytes (§6.1), since the signature no longer
  fits in 256 — a client still padding presence to 256 will be distinguishable by size.
- **1.5**, retained-message hygiene (§4): the joiner must clear its retained
  `join_approval` and `join_request` on **success** as well as rejection, after the group
  is durably saved. No wire-format change; a client that skips it leaves a multi-kilobyte
  approval on the broker after every join.
- **1.6**, sealed presence (§6.2): the presence topic now carries `SealedPresence`
  wrapping the envelope, encrypted under a group subkey. Like the manifest change this is
  **not** additive — a client expecting a bare envelope sees no presence at all from
  Android 1.12.5+.
- **1.8**, removal tombstones and concurrent-edit reconciliation (§6.4, §7.3):
  `GroupDefinition.removedMemberIds`, and the rule that two states sharing a version must be
  merged rather than arbitrated. **A client that skips this forks the group.** It is not a
  theoretical risk — it happened on 2026-08-12 with two members approving joins at the same
  moment, and the affected device sat on a stale roster indefinitely, receiving the missing
  member's traffic and discarding it. Tombstones also change `computeStateHash` for any
  group that has ever removed someone, so an implementation that drops the field will
  disagree about the state hash and fail every chain check. `PROTOCOL_VERSION` is now **3**.
- **1.9**, manifest signatures and deletion tombstones (§6.7): `EncryptedFileManifest` gains
  `signerMemberId` and `signature`, and the manifest now carries entries for **deleted** files
  rather than omitting them. Both are breaking in effect. An unsigned manifest is refused, so
  a client that does not sign will silently stop updating anyone's file list; a client that
  does not honour tombstones will keep documents the family has deleted, and will republish
  them. Also defines file `keyVersion` **3** as readable-but-not-written.
- **1.10a**, targeted chunk repair and pinning (§6.7): `ChunkRepairRequest` and
  `FileHoldingsAnnouncement`, both wrapped in `EncryptedFileMessage`, on two new
  per-recipient topics; and `isEssential` on `SharedFile`. Additive on the wire — a client
  that implements none of it still transfers files — but it will never repair a gap, never
  answer a peer's repair request, and never be counted as holding a copy, so a family
  containing it sees "on 2 of 3 devices" indefinitely. `isEssential` defaults to **true** on
  absence, which matches what an older peer actually does.
- **1.10b**, the family vault (§6.8): a fixed-size slot container synced on its own retained
  topic, with document bytes on their own chunk path. **The three vault topics must be
  subscribed unconditionally by every device**, and the container must be created full of
  random bytes on first run whether or not a code is ever set — a client that creates or
  subscribes lazily announces that a vault exists, which is the one thing the design cannot
  allow. A client that skips the vault entirely is safe as long as it still stores and serves
  vault chunks; a client that stores the container but never republishes it is not, because
  a family member's additions will not reach the devices behind it.
- **1.7**, protocol version advertisement (§6.2): `PresenceUpdate.protocolVersion`.
  Additive on the wire, but an iOS client that omits it is read as generation 1 and every
  Android peer will permanently label the user "needs to update" — so emit it. Set it to
  the generation whose *rules you actually implement*, not to whatever is newest: claiming
  2 without 1.1, 1.3, 1.4 and 1.6 turns a legible warning back into silent invisibility.

This document is the single source of truth for building the iOS version of FamilySafety.
It captures everything the iOS app must implement **byte-for-byte identically** to
interoperate with the Android app over the shared MQTT broker: key derivation, the E2EE
envelope, every topic and JSON wire format, the group-state security rules, and verified
cross-platform test vectors (generated with real libsodium against the published BIP-39
reference vector).

**How to use this document to conserve model budget:** each phase in §14 is self-contained.
Start a fresh (cheaper) coding session per phase, give it this file plus the phase number,
and require the phase's acceptance tests to pass before moving on. Phase 0's test vectors
(§12) catch virtually all crypto-porting mistakes without needing an Android device.
Do not let any session "improve" a wire format — compatibility with shipped Android
clients is the contract.

---

## 1. Product summary

Privacy-first family location sharing. No app server: all traffic is relayed by a plain
MQTT broker and (except presence) end-to-end encrypted. Identity is a 12-word BIP-39
mnemonic; keys are derived deterministically, so restoring the mnemonic restores the
identity. Features: live family map, presence, 1:1 and group chat with delivery/read
receipts, join-by-QR-invite with approval, offline history replication between devices,
shared encrypted file library, geofences, location history.

---

## 2. Identity & key derivation (must match exactly)

### 2.1 Mnemonic → seed (BIP-39)
- 12 words, official BIP-39 English wordlist (2048 words; bundle it as a resource).
- Generation: 128 bits entropy + first 4 bits of SHA-256(entropy) = 132 bits = 12×11-bit indices.
- Seed: `PBKDF2-HMAC-SHA512(password = normalized mnemonic, salt = "mnemonic" + passphrase, iterations = 2048, dkLen = 64)`.
  - Normalization: trim, lowercase, collapse internal whitespace to single spaces.
  - The app always uses an **empty passphrase**.

### 2.2 Seed → key pairs (SLIP-10, ed25519 curve)
Path: `m/44'/1984'/0'/keyType'` — **all indices hardened** (`index | 0x80000000`).

- Master: `HMAC-SHA512(key = "ed25519 seed", data = seed)` → left 32 = private key, right 32 = chain code.
- Child: `HMAC-SHA512(key = chainCode, data = 0x00 || parentPrivKey(32) || index(4, big-endian))`.
- `keyType 0` → **Ed25519 signing key**: the 32-byte SLIP-10 output is the Ed25519 *seed*;
  expand with `crypto_sign_seed_keypair` (libsodium) to get the public key and 64-byte secret key.
- `keyType 1` → **X25519 encryption key**: run the *same* SLIP-10 ed25519 derivation on path
  `m/44'/1984'/0'/1'`, then **clamp** the 32-byte output per RFC 7748
  (`k[0] &= 0xF8; k[31] = (k[31] & 0x7F) | 0x40`). Public key = `crypto_scalarmult_base(clamped)`.
  ⚠️ The X25519 key is **not** converted from the Ed25519 key — it's an independent derivation.

### 2.3 Member ID
```
memberId = lowercase_hex( SHA-256(ed25519_public_key_bytes)[0..15] )   // 32 hex chars
```
Member ID is routing identity (topic names). It is only trustworthy when the
corresponding Ed25519 key hashes to it — enforced at join time and in group-state
validation (§7.3).

### 2.4 Encoding conventions
- **All keys, nonces, ciphertexts, signatures, hashes on the wire are lowercase hex** —
  with one exception: `JoinRequest.ed25519PublicKey` / `x25519PublicKey` are
  **Base64 (standard alphabet, with padding)**. See §8.3.
- Timestamps are epoch **milliseconds** (JSON numbers).

---

## 3. E2EE message envelope (used by everything except presence, acks, sync-requests, join-requests, file wrappers)

JSON (field order irrelevant; decode must ignore unknown keys):
```json
{
  "senderMemberId": "<hex32>",
  "nonce":          "<hex, 24 bytes>",
  "ciphertext":     "<hex, MAC(16) || ct>",
  "signature":      "<hex, 64 bytes Ed25519>"
}
```

**Encrypt (sender):**
1. `shared = crypto_box_beforenm(recipient_x25519_pub, my_x25519_priv)` — this is
   X25519 scalarmult **plus the HSalsa20 hash step**; do not use raw scalarmult output.
   Cache `shared` per peer.
2. `nonce = random 24 bytes`.
3. `ciphertext = crypto_secretbox_easy(plaintext_utf8, nonce, shared)` (XSalsa20-Poly1305; output = 16-byte MAC ‖ ct).
4. `signature = crypto_sign_detached(nonce || ciphertext, my_ed25519_secret64)`.

**Decrypt (recipient):**
1. Read `senderMemberId` (plaintext, **unauthenticated** — use only to look up the sender
   in the **local roster**; never take keys from the message itself).
2. **Verify signature first** against the roster's Ed25519 key for that sender; reject on failure.
3. Compute the same `shared` with the roster's X25519 key; `crypto_secretbox_open_easy`.

The same envelope also wraps the join approval/rejection payloads, where the sender is not
yet in a roster — there the sender's keys arrive alongside and are authenticated by hashing
against the QR invite's `inviterMemberId` (§8.4).

---

## 4. MQTT session contract

- Library on Android: Eclipse Paho (MQTT **3.1.1**). iOS: CocoaMQTT works.
- Broker (dev): `ssl://broker.hivemq.com:8883` (public HiveMQ, TLS). Staging/prod URLs in `BrokerConfig.kt`.
- Client ID: `familysafe_{memberId}`. **Stable** — combined with `cleanSession = false` this
  gives the broker-side persistent session that provides offline delivery.
- `cleanSession = false`, keep-alive **30 s**, connect timeout 30 s, QoS **1** for everything.
- Reconnect: app-managed exponential backoff (no library auto-reconnect).
- **Last Will**: topic `familysafe/{memberId}/presence`, payload = offline `PresenceUpdate`
  envelope (§6.2), QoS 1, **retained = true**.
- On connect: subscribe own topics (§5), subscribe each peer's `location` (legacy) +
  `presence` topics, flush the pending-message queue, publish online presence (retained).
- Outbound messages that fail/queue while offline: in-memory queue, max **200** entries,
  **1 h** expiry.
- **Clearing a retained message** = publish a zero-length payload with `retained = true`
  to the same topic.

  The joiner **must** clear both `join_approval` and its own `join_request` on **success**
  as well as on rejection, and must do so **after** the group definition is durably saved,
  never before — clearing first leaves a device that dies mid-join with neither a saved
  group nor a retained approval to recover from. Android only did this on rejection until
  1.12.4, so every successful join left a multi-kilobyte approval retained on the broker
  permanently, replayed to anything that subscribed. An implementation that skips this
  produces the same litter.
- Joiner onboarding uses a separate **ephemeral** client `familysafe_join_{memberId[0..7]}_{epochMs}` (§8.3).

---

## 5. Topic & payload matrix

`{id}` = recipient memberId unless noted. QoS 1 everywhere.

| Topic | Payload | E2EE | Retained |
|---|---|---|---|
| `familysafe/{id}/location_inbox` | Envelope(§3) of `MessageEnvelope{type:"location_update"}` | ✅ per-recipient | no |
| `familysafe/{sender}/location` | legacy — **subscribe only**, never publish | ✅ | no |
| `familysafe/{sender}/presence` | plaintext `MessageEnvelope{type:"presence_update"}` | ❌ (LWT can't be E2EE) | **yes** |
| `familysafe/{id}/chat` | Envelope of `ChatMessagePayload` | ✅ | no |
| `familysafe/{id}/chat/receipt` | Envelope of `DeliveryReceipt` (DELIVERED) | ✅ | no |
| `familysafe/{id}/chat/read` | Envelope of `DeliveryReceipt` (READ) | ✅ | no |
| `familysafe/{id}/group_sync` | Envelope of `GroupSyncMessage` | ✅ per-recipient | no |
| `familysafe/group/{groupId}/ack` | plaintext `GroupUpdateAck` | ❌ | no |
| `familysafe/{id}/sync_request` | plaintext `GroupStateRefreshRequest` | ❌ | no |
| `familysafe/{inviterId}/join_request` | plaintext `JoinRequest` | ❌ (contains only public data) | **yes** (cleared after decision) |
| `familysafe/{joinerId}/join_approval` | plaintext `JoinApprovalMessage` **or** `JoinRejectionMessage` (each carries an inner Envelope) | inner ✅ | **yes** (joiner clears once consumed — §8.3) |
| `familysafe/{id}/replication/request` | Envelope of `ReplicationRequest` | ✅ | no |
| `familysafe/{id}/replication/data` | Envelope of `ReplicationResponse` | ✅ | no |
| `familysafe/{id}/replication/announce` | Envelope of `DataAvailabilityAnnouncement` | ✅ per-recipient | no |
| `familysafe/group/{groupId}/files/manifest` | `EncryptedFileManifest` since 1.12.2; bare `FileManifest` from older senders | ✅ group key (was ❌) | **yes** |
| `familysafe/group/{groupId}/files/chunk/{fileId}/{chunkIndex}` | plaintext `FileChunkMessage` (data field AES-GCM encrypted) | contents ✅ (group key — **version 1 is not confidential**, see §6.7) | no |
| `familysafe/{id}/files/request` | plaintext `FileRequestMessage` | ❌ | no |
| `familysafe/{id}/files/repair` | `EncryptedFileMessage` wrapping `ChunkRepairRequest` | ✅ group key | no |
| `familysafe/{id}/files/availability` | `EncryptedFileMessage` wrapping `FileHoldingsAnnouncement` | ✅ group key | no |
| `familysafe/group/{groupId}/vault/container` | `VaultContainerMessage` | container is already opaque; signed | **yes** |
| `familysafe/group/{groupId}/vault/chunk/{fileId}/{chunkIndex}` | plaintext `VaultChunkMessage` (data field AES-GCM under the item's content key) | contents ✅ (per-item key, not the group key) | no |
| `familysafe/{id}/vault/repair` | plaintext `VaultChunkRequest` | ❌ (names only an opaque id) | no |

Own subscriptions on connect: `chat`, `location_inbox`, `chat/receipt`, `chat/read`,
`replication/request|data|announce`, `join_request`, `join_approval`, `sync_request`,
`group_sync`, group `ack`, files `manifest`, files `chunk/#` (wildcard), `files/request`,
`files/repair`, `files/availability`, `vault/container`, `vault/chunk/#` (wildcard),
`vault/repair`.
Per-peer subscriptions: `location` (legacy), `presence`.

The three vault topics are subscribed **unconditionally, by every device, in every family**,
whether or not anyone has ever set a vault code. A client that subscribed to them only once a
vault existed would announce that one exists — see §6.8.

---

## 6. Wire schemas (JSON)

Serialization semantics (kotlinx.serialization on Android — mirror these):
decode ignores unknown keys; nulls may appear explicitly (`"speed":null`) or be absent —
accept both; enums serialize as their UPPERCASE names; encode all fields including defaults.

### 6.1 MessageEnvelope (location/presence inner wrapper)
```json
{ "type": "location_update" | "presence_update", "payload": "<JSON string — double-encoded>",
  "pad": "......" }
```
⚠️ `payload` is a JSON **string** containing the serialized inner object.

`pad` was added in Android 1.12.1 (19). It is meaningless filler and MUST be ignored on
receive. It is **absent** from older senders, so decode it as optional defaulting to `""`.

**Senders must pad.** Message length is a side channel that survives encryption, and it
was leaking real behaviour: presence was 136 bytes online and 137 offline (`"true"` vs
`"false"`), and location payloads grew when `speed`/`bearing` were populated, which marks
a device as moving. Coordinate precision leaked the same way.

Algorithm, which must match byte-for-byte to be useful:

1. Serialize the envelope with `pad` set to `""`; measure its length in **UTF-8 bytes**.
2. Round that up to the next multiple of the target for the message type — **512** for
   both `presence_update` and `location_update`. (Presence was 256 before 1.12.3; the
   Ed25519 signature added in §6.2 does not fit in 256, and leaving it there would split
   presence across two buckets, making signed and unsigned senders distinguishable.)
3. Re-serialize with `pad` set to `.` repeated (target − unpadded) times.

`.` needs no JSON escaping, so N characters add exactly N bytes. Rounding up to a
multiple, rather than failing or truncating, keeps an oversized message on a fixed grid
instead of revealing its true length.

Pad the **plaintext**, before encryption. Padding ciphertext achieves nothing — its
length already reflects the plaintext by that point.

Not yet padded on Android, so do not expect uniform sizes there: chat, replication and
group sync. Chat is a deliberate deferral (its lengths span orders of magnitude, so
padding is a bandwidth trade rather than a free win).

### 6.2 LocationUpdate / PresenceUpdate
```json
{ "memberId": "...", "latitude": 0.0, "longitude": 0.0, "accuracy": 0.0,
  "timestamp": 0, "speed": null, "bearing": null }          // LocationUpdate
{ "memberId": "...", "isOnline": true, "timestamp": 0,
  "signature": "<hex128chars>", "protocolVersion": 2 }       // PresenceUpdate
```

#### Protocol version advertisement (since 1.12.6)

```
PROTOCOL_VERSION = 2      // 1 = pre-1.12.x; 2 = padded + signed + sealed + encrypted manifests
```

Three of the changes above (manifest encryption, sealed presence, and the group file key)
**replace** a message shape rather than extending it, so a peer on an older build and a
peer on a current one simply do not see each other. Until this field existed that failed
*silently* — the other person never appeared, which is indistinguishable from the app
being broken, and the whole of §6 offers no way to tell the two apart. There is no
connect-time handshake to carry a version in: this is pub/sub, peers never negotiate. So
the version rides on presence, which every peer publishes anyway and which is already
authenticated.

Senders set `protocolVersion` to `PROTOCOL_VERSION` on every presence update, live and
last-will alike.

Receivers:

1. **Default a missing field to 1.** Builds predating it omit the field entirely, so 1 is
   the correct reading of "old client" — not a fallback, but exactly the case being
   detected. Deserialization must tolerate unknown fields for the same reason (Android
   uses kotlinx `ignoreUnknownKeys = true`); a future generation 3 must not make a
   generation 2 client fail to parse presence at all.
2. **Record it only after the presence passes authentication** — signature, replay and
   downgrade checks all first. Otherwise a forged presence claiming version 1 lets anyone
   who can publish to the topic paint a current peer as outdated, and an unauthenticated
   version claim is a free denial-of-trust.
3. **Surface any peer below your own `PROTOCOL_VERSION` in the UI**, naming the member.
   The point of the field is that the failure becomes legible; recording it and staying
   quiet reproduces the original bug with extra steps.

The field is covered by kotlinx's default-value handling, **not** by the presence
signature — see §6.2's canonical signing string, which was deliberately left byte-identical
so signatures from already-shipped builds keep verifying. A relay that can already read
which members exist and when they connect gains nothing from an unauthenticated version
number, and rule 2 keeps a forged one from having any effect.

#### Sealed presence (since 1.12.5)

The presence topic no longer carries the bare envelope. It carries:

```json
{ "v": 2, "data": "<base64 of nonce ‖ ciphertext ‖ tag over the presence envelope>" }
```

Presence was plaintext, so the relay could simply read `isOnline` — who is awake and when
they leave, the sharpest behavioural signal on the broker. (Envelope padding did not
address this; it closed the length channel, which for presence was redundant with reading
the payload.)

Encrypted under a **group** subkey, not per recipient, because the last-will is published
by the broker once the device is gone — there is no send-time at which to encrypt to each
peer. Key:

```
presenceKey = SHA-256( groupFileKeyBytes ‖ "presence" )
```

`groupFileKeyBytes` is `GroupDefinition.fileEncryptionKey` hex-decoded. The purpose label
gives domain separation, so recovering the presence key does not yield the file key.
AES-256-GCM, 12-byte nonce, 128-bit tag, `nonce ‖ ciphertext ‖ tag` — the same layout as
file chunks.

Seal the will at **connect** time, together with its signature.

Receivers accept both shapes: a payload that parses as `SealedPresence` is opened,
anything else is treated as a legacy plaintext envelope. A group with no
`fileEncryptionKey` publishes plaintext; do not seal with the version 1 file key, which is
broker-derivable.

#### Presence signatures (since 1.12.3)

Presence is the only message type outside the encrypted envelope, because an MQTT
last-will cannot be encrypted. It was therefore forgeable: the receiver only checked that
the payload's `memberId` matched the topic, which anyone able to publish to an arbitrary
topic satisfies by publishing to the victim's own presence topic. No broker ACL fixes
this in general — even per-device credentials leave one family member able to forge
another's presence — so it is authenticated by signature.

`signature` is Ed25519-detached, lowercase hex, over the **canonical UTF-8 string**:

```
presence:{memberId}:{isOnline}:{timestamp}
```

`isOnline` renders as `true`/`false`. Built from the fields, **not** from the serialized
JSON, so key order and future additive fields cannot change what was signed. Verify
against the sender's `ed25519PublicKey` from the group definition.

Senders must sign both the live update and the **last-will**. The will is signed at
connect time and handed to the broker pre-signed — the device is gone when the broker
publishes it and cannot sign then.

Receivers must apply three checks:

1. **Replay** — reject a presence whose `timestamp` is older than the newest accepted one
   for that member, or a captured "offline" can be republished later at will. Allow equal
   timestamps: the broker redelivers retained messages on every resubscribe.
2. **Downgrade** — once a member has been seen with a *valid* signature, reject a later
   unsigned update claiming to be them. That is an attacker stripping the field, not an
   old client.
3. **Signature** — reject if present and invalid.

`signature` is absent from senders predating this, and unsigned presence is accepted until
that member's first valid signature, so peers on older builds do not appear permanently
offline. Both pieces of per-member state are in-memory only and reset on restart.
Location flow: build `LocationUpdate` → wrap in `MessageEnvelope` → E2EE-encrypt **once per
peer** → publish each ciphertext to that peer's `location_inbox`. Presence: `MessageEnvelope`
published plaintext + retained on **own** presence topic.

### 6.3 Chat
```json
// ChatMessagePayload (plaintext inside envelope)
{ "messageId": "<uuid>", "content": "...", "messageType": "TEXT|LOCATION|SYSTEM",
  "timestamp": 0, "conversationId": null, "replyToMessageId": null }
// conversationId: null = 1-to-1 (conversation is the peer's memberId);
//                 groupId = group chat (send one E2EE copy to every member).
// LOCATION message content = {"latitude":0.0,"longitude":0.0} JSON string.

// DeliveryReceipt (plaintext inside envelope; status DELIVERED → chat/receipt, READ → chat/read)
{ "messageId": "...", "recipientId": "...", "status": "PENDING|SENT|DELIVERED|READ|FAILED", "timestamp": 0 }
```
Receipt handling must reject receipts for message IDs that were never addressed to the
receipt's sender (anti-forgery, see ChatRepository).

### 6.4 Group sync
```json
// GroupSyncMessage (plaintext inside per-recipient envelope)
{ "groupId": "...", "version": 0, "groupDefinition": { ...§6.5... },
  "updaterMemberId": "...", "changeType": "MEMBER_ADDED|MEMBER_REMOVED|NAME_CHANGED|VERSION_SYNC|CONFLICT_RESOLUTION|FULL_SYNC",
  "changedMemberId": null, "timestamp": 0, "signature": "<hex64>" }

// GroupUpdateAck — plaintext broadcast on the group ack topic
{ "groupId": "...", "version": 0, "memberId": "<acker>", "timestamp": 0 }

// GroupStateRefreshRequest — plaintext on each peer's sync_request topic
{ "groupId": "...", "requesterMemberId": "...", "minimumVersion": 0,
  "changedMemberId": null, "reason": "...", "timestamp": 0 }
```

### 6.5 GroupDefinition / FamilyMember
```json
{ "groupId": "<uuid>", "groupName": "...", "createdAtEpochMs": 0, "creatorMemberId": "...",
  "members": [ { "memberId": "...", "displayName": "...",
                 "ed25519PublicKey": "<hex64chars>", "x25519PublicKey": "<hex64chars>",
                 "addedAtEpochMs": 0, "addedByMemberId": null,
                 "avatarHash": null, "colorHue": null } ],
  "version": 1, "previousStateHash": null,
  "fileEncryptionKey": "<hex64chars>",
  "removedMemberIds": ["<memberId>", "..."] }
```

`fileEncryptionKey` was added in Android 1.12.0 (18): a random 32-byte AES key, hex
encoded, generated once when the group is created. It is **absent or null** for groups
created before that, and decoders must treat it as optional.

`removedMemberIds` was added in Android 1.12.7 (25) and defaults to the empty set. It is
**append-only**: every removal adds the member's ID and nothing ever takes one out. It must
be preserved on re-serialize — a client that drops it will readmit removed members through
the merge in §7.4 and will disagree about the state hash (§7.1) for any group that has ever
removed anyone. A removal is permanent for that member ID, which costs nothing in practice:
identities are self-authenticating, so anyone rejoining generates a new mnemonic and a new
ID regardless.

It lives on the definition because the definition only ever travels inside the
per-recipient encrypted envelope (join approval, group sync) and is persisted encrypted,
so it needs no separate distribution path. Two consequences for an implementation:

- It is secret material. Never log it, never show it in a UI, and never put it in a
  diagnostics export.
- It is **not** covered by `computeStateHash` (§7.1) — see the note there.

### 6.6 Replication (all inside per-peer envelopes)
```json
// ReplicationRequest
{ "requestId": "<uuid>", "requesterId": "...", "dataType": "LOCATION_HISTORY|CHAT_MESSAGES",
  "targetMemberId": null, "conversationId": null, "afterTimestamp": 0, "limit": 500, "timestamp": 0 }
// ReplicationResponse
{ "requestId": "...", "senderId": "...", "dataType": "...",
  "locations": [ {LocationUpdate-shaped, no envelope} ], "messages": [ ReplicatedMessage... ],
  "hasMore": false, "newestTimestamp": null, "timestamp": 0 }
// ReplicatedMessage
{ "messageId": "...", "conversationId": "...", "senderId": "...", "recipientId": null,
  "content": "...", "messageType": "TEXT", "status": "SENT", "timestamp": 0,
  "isOutgoing": false, "replyToMessageId": null }
// DataAvailabilityAnnouncement
{ "announcerId": "...", "locationDataSummary": [ {"memberId":"...","oldestTimestamp":0,"newestTimestamp":0,"count":0} ],
  "chatDataSummary":   [ {"conversationId":"...","oldestTimestamp":0,"newestTimestamp":0,"count":0} ], "timestamp": 0 }
```

### 6.7 Shared files
```json
// FileManifest — retained on the group manifest topic. ENCRYPTED since 1.12.2; see below.
{ "groupId": "...", "files": [ SharedFile... ], "version": 0, "pad": "..." }  // version = epoch ms
// EncryptedFileManifest — what is actually published when the group has a file key.
// signerMemberId + signature added in 1.9 and REQUIRED on receive; see "Manifest
// authenticity" below. Older senders omit both, which decodes to "" and is refused.
{ "keyVersion": 2, "data": "<base64 of AES-GCM blob over the FileManifest JSON>",
  "signerMemberId": "...", "signature": "<hex Ed25519 over \"{keyVersion}|{data}\">" }
// SharedFile
// isEssential added in 1.10a. ABSENT from older senders and MUST default to TRUE — an older
// peer replicates everything, so reading its files as essential matches what it will do.
{ "fileId": "<uuid>", "name": "...", "mimeType": "...", "sizeBytes": 0,
  "contentHash": "<sha256 hex of plaintext>", "uploaderMemberId": "...", "uploadedAt": 0,
  "chunkCount": 0, "isDeleted": false, "deletedByMemberId": null, "deletedAt": null,
  "isEssential": true }
// EncryptedFileMessage — the wrapper for every file control message below. Same shape and
// same key as EncryptedFileManifest: a bare request names a file and how much of it a device
// is missing, which is the metadata the manifest was encrypted to stop leaking.
{ "keyVersion": 2, "data": "<base64 of AES-GCM blob over the inner JSON>" }
// ChunkRepairRequest — inside EncryptedFileMessage, on familysafe/{peerId}/files/repair
{ "requestId": "<uuid>", "requesterId": "...", "fileId": "...",
  "contentHash": "<sha256 hex>", "totalChunks": 0, "missing": [3, 7, 11], "timestamp": 0 }
// FileHoldingsAnnouncement — inside EncryptedFileMessage, on familysafe/{peerId}/files/availability
{ "announcerId": "...", "holdings": [ FileHolding... ], "timestamp": 0 }
// FileHolding — state is "COMPLETE" or "PARTIAL"
{ "fileId": "...", "contentHash": "<sha256 hex>", "state": "COMPLETE",
  "chunksHeld": 0, "chunkCount": 0 }
// FileChunkMessage — plaintext wrapper, encrypted data
// keyVersion added in Android 1.12.0 (18). ABSENT on the wire from older senders and
// MUST default to 1 when decoding.
{ "fileId": "...", "chunkIndex": 0, "totalChunks": 0, "data": "<base64 of AES-GCM blob>",
  "keyVersion": 2 }
// FileRequestMessage
{ "requesterId": "..." }
```
Chunks are the plaintext split into **32 KiB** pieces; each encrypted independently with
AES-256-GCM, random **12-byte** nonce, **128-bit** tag; blob layout `nonce ‖ ciphertext ‖ tag`
(identical to CryptoKit `AES.GCM.SealedBox.combined`). Group storage cap 500 MB.

#### File key versions

`keyVersion` selects the key. **Decrypt with the version the chunk declares, not with
whatever key the group currently holds** — during rollout a group contains files under
both.

| `keyVersion` | Key | Notes |
|---|---|---|
| 1 (default when field absent) | `SHA-256(groupId + "familysafety-files-v1")` | Legacy. Both inputs are public — the salt is a constant in the binary and the groupId appears in the topic name — so this provides **no confidentiality against anyone who can reach the broker**. Implement for read compatibility only. |
| 2 | `GroupDefinition.fileEncryptionKey`, hex-decoded to 32 bytes | Random per group, distributed only inside the encrypted group definition. **What Android publishes today.** |
| 3 | `SHA-256(fileEncryptionKey ‖ "files")` — the §6.2 subkey derivation with purpose `files` | Purpose separation, so recovering a file key does not also yield presence. **Implement the reader now; do not write it yet.** Android 1.12.10 and earlier map an unrecognised version to the legacy key, so a device that publishes version 3 makes its files undecryptable to every peer that has not updated. The writer flips in a later release, once readers are everywhere. |

Rules for an implementation:

- **Never encrypt with version 1.** New uploads use version 2 when
  `fileEncryptionKey` is non-null. A group whose definition still has a null key
  (created before 1.12.0 and never recreated) falls back to version 1 and stays exposed;
  this is a known gap, not a target state.
- A version 2 chunk received while `fileEncryptionKey` is null cannot be decrypted.
  Drop the chunk and surface it; do not attempt the version 1 key, which fails GCM
  authentication anyway.
- Re-broadcast (`files/request`) and chunk repair both retransmit **stored ciphertext
  verbatim**, tagged with the key version the stored copy actually holds. Android used to
  re-encrypt these from a local plaintext copy under the current key, which migrated legacy
  files opportunistically; it no longer keeps plaintext to re-encrypt from, so a legacy file
  stays version 1 until the family is recreated. Do not "upgrade" a chunk in transit — the
  requester writes what arrives into a per-file blob that cannot mix key versions.
- Decoders must tolerate the absent field. Android decodes with
  `ignoreUnknownKeys = true`; the Swift side should use an optional with a default of 1.

See `SECURITY_REVIEW.md` finding F1 for why version 1 exists and why it is not safe.

#### Manifest encryption (since 1.12.2)

The manifest used to be published as bare `FileManifest` JSON — file names, MIME types,
exact sizes, uploader and timestamps — retained, so any broker client received it on
subscribe. It is now wrapped in `EncryptedFileManifest`, encrypted **symmetrically with
the group file key** rather than per recipient, which keeps it a single retained
broadcast so a joining member still catches up instantly.

Sending:

1. Pad the `FileManifest` to a **2 KiB** multiple using the same `pad` scheme as §6.1
   (measure with `pad` empty, round up, fill with `.`). This stops ciphertext length from
   revealing the file count and name lengths. *(Was 1 KiB; widened when `isEssential` was
   added to each entry, because the extra bytes per file pushed a three-file manifest out of
   the bucket a one-file manifest sits in and made the two distinguishable by length again.
   A client still padding to 1 KiB reintroduces that leak.)*
2. AES-256-GCM the serialized manifest with the group key, 12-byte nonce, 128-bit tag,
   blob layout `nonce ‖ ciphertext ‖ tag`.
3. Sign and publish as described under "Manifest authenticity" below, retained.

Receiving — **both shapes appear on this topic**, since a retained plaintext manifest
from before the upgrade outlives it and older senders still publish plaintext:

1. Try to decode `EncryptedFileManifest`. If it parses, **verify the signature first**, then
   decrypt with the key for its `keyVersion`; if no such key is held, drop the message.
2. Otherwise decode as a bare `FileManifest` — **only if this group has no
   `fileEncryptionKey`.** Once the group has one, a plaintext manifest is either a stale
   retained message or an attempt, and must be refused.

The two shapes have disjoint required fields, so the attempt order is unambiguous.

A group with no `fileEncryptionKey` (created before 1.12.0) publishes plaintext and cannot
sign in a way anyone can check against a key nobody shares. Do not "fix" that by encrypting
with the version 1 key — it is derivable by anyone on the broker, so it would look like
protection without providing any. Recreating the family is the fix.

#### Manifest authenticity (since 1.9)

The manifest is the file library's index. It names every document, and an entry marked
deleted in it deletes that document on **every device in the family**. Until 1.9 it was
encrypted but unsigned, with an unconditional plaintext fallback, so anyone able to publish
to the broker could rewrite or empty a family's library.

Signing, over the **ciphertext** rather than the plaintext, so a manifest is authenticated
before anything is decrypted:

```
signingPayload = UTF8("{keyVersion}|{data}")     // data = the base64 string as published
signature      = hex(Ed25519_sign(signingPayload, ourEd25519PrivateKey))
signerMemberId = our memberId
```

The key version is inside the payload deliberately: without it, a valid signature could be
kept while relabelling the manifest to a weaker key.

Verifying, in this order:

1. If `signature` or `signerMemberId` is empty, **refuse**. Absent is not valid — treating it
   as valid means forging a manifest requires only leaving the field out. During rollout this
   means a peer on an older build stops updating this device's file list until it updates,
   which is the correct trade.
2. Look `signerMemberId` up in **our own current roster**. If they are not a member, refuse.
   Never verify against a key carried in the message, for the same reason §7.3 gives for
   group sync.
3. Verify the signature over `signingPayload` with that member's `ed25519PublicKey`. Refuse
   on failure. Only then decrypt.

#### Deletion tombstones (since 1.9)

Deletion propagates as an entry that is **present and flagged**, not as an absence — an
absent entry is indistinguishable from a file the publisher has never heard of.

- The publisher includes every non-deleted file **plus** every `isDeleted` entry whose
  `deletedAt` is within the retention window. Android's window is **90 days**: a tombstone has
  to outlive any device that might still be holding the file, and the cost of being generous
  is a few hundred bytes in one retained message. Past the window the row is dropped entirely.
- On receive, for each entry:
  - **known locally and not yet deleted, arriving deleted** → mark deleted, erase the stored
    blob and any decrypted cache copy. Do not ask the user.
  - **unknown locally and arriving deleted** → record the tombstone anyway. Without it, a
    slower peer that still holds the file republishes it and it comes back.
  - a device holding a tombstone must not serve, request or accept chunks for that `fileId`.

Before 1.9 Android built the manifest from a query filtering `isDeleted = 0`, so tombstones
never left the device while the confirmation dialog said "delete for everyone in the family".
The receive-side branch existed the whole time and no sender ever reached it. For medical
records and IDs that is a privacy failure rather than a missing feature, and an
implementation that omits it is not merely incomplete — it is the same failure.

#### Targeted chunk repair (since 1.10a)

Chunks are broadcast once. Before this there was no gap detection and no retry: the only
recovery was a user tapping an undownloaded file, which made *every* peer re-publish *every
chunk of every complete file* to the whole group. A file was observed taking half a day to
arrive — it was not slow, it had stopped, and it finished the moment somebody touched it.

An implementation needs three things:

1. **Know which chunks it holds**, per file, by index. A count is not enough; the question
   that matters when a transfer stalls is *which* chunks are missing. Android stores a bitmap
   (bit *i* = chunk *i*, LSB-first within each byte) alongside the row. The representation is
   local — nothing about it crosses the wire — but the capability is not optional.
2. **Ask one peer for exactly what is missing**, by publishing a `ChunkRepairRequest` wrapped
   in `EncryptedFileMessage` to `familysafe/{peerId}/files/repair`. Cap the index list so it
   fits one message (Android sends at most 256) and re-ask until the file completes.
   `contentHash` binds the request to exact bytes: if the responder's copy hashes differently
   the two are looking at different versions, and sending chunks would corrupt the requester's
   copy rather than repair it, so the responder must **refuse** on mismatch.
3. **Answer** by publishing the requested chunks to the ordinary group chunk topic, not back
   to the requester alone. A repair reply is then indistinguishable from an original upload,
   needs no new receive path, and any other device with the same gap picks it up for free.
   Android serves at most 64 chunks per request and leaves the requester to re-ask.

Retries must survive process death and back off — Android stores the schedule on the file row
and drains it from a `WorkManager` job with a network constraint, 30 s base backoff to a
30-minute ceiling, rotating between peers so one that cannot help is not asked forever.

**Pace the publish loop.** Paho's default in-flight window is 10 unacknowledged messages and
Android treats a publish exception as a dropped connection, so an unpaced loop over thousands
of chunks disconnects the client from its own broker. Android sleeps 15 ms between chunk
publishes. Any implementation that streams faster than the old byte-boxing path needs the
equivalent.

#### Essential pinning and availability (since 1.10a)

`SharedFile.isEssential` is set by the uploader and editable afterwards by anyone.

- **Essential** files download automatically on every device.
- **Non-essential** files are listed and fetched only when someone opens them.

This is a deliberate behaviour change from "everything auto-downloads", and it is what makes
the guarantee affordable: an insurance card is only useful if it is already on the phone when
there is no signal, but pushing a 2 GB video to four phones is not.

Peers announce what they hold by publishing a `FileHoldingsAnnouncement`, wrapped in
`EncryptedFileMessage`, to each peer's `familysafe/{peerId}/files/availability`. It is a state
summary, not an event: it self-heals on the next tick and a member who has just joined gets
the whole picture at once. Android debounces to at most one announcement per 30 s.

Two anti-forgery rules, both worth copying:

- Count a peer as holding a copy only when `state` is `COMPLETE` **and** its `contentHash`
  matches the local row. A peer holding different bytes under the same id is not a copy, and
  counting it reports a document as safe when it is not.
- **Absence of an announcement is not absence of a copy.** Until at least one peer has
  announced anything, a count of zero means "nobody has told us" — an implementation that
  renders that as "this document exists nowhere else" will alarm people about files that are
  fine, which for emergency documents is worse than saying nothing.

#### At-rest storage (Android behaviour, no wire effect)

Recorded because it changes what the two implementations can assume about each other, not
because it crosses the wire: Android no longer keeps a plaintext copy of any shared document.
A file is stored as one sparse blob of wire-format chunk ciphertexts, decrypted to a private
cache file only while another app is opening it, and that cache is cleared on launch. A peer
therefore cannot expect a re-broadcast to arrive re-encrypted under the current key — see the
`keyVersion` rules above.

---

### 6.8 The family vault

Documents shared with everyone who knows a code, and invisible to everyone who does not —
including to the devices holding the bytes. It is optional to *use* and **not optional to
support**: a client that ignores it stops vault documents reaching the devices behind it.

```json
// VaultContainerMessage — retained on familysafe/group/{groupId}/vault/container
{ "version": 0, "data": "<base64 of the whole 131072-byte container>",
  "signerMemberId": "...", "signature": "<hex Ed25519 over \"{version}|{data}\">" }
// VaultChunkMessage — on familysafe/group/{groupId}/vault/chunk/{fileId}/{chunkIndex}
{ "fileId": "<uuid>", "chunkIndex": 0, "totalChunks": 0,
  "data": "<base64 of nonce ‖ ciphertext ‖ tag under the item's content key>" }
// VaultChunkRequest — on familysafe/{peerId}/vault/repair
{ "requesterId": "...", "fileId": "<uuid>", "missing": [0, 1, 2], "timestamp": 0 }
// VaultSlotPayload — never on the wire; this is what is sealed into a slot
{ "fileId": "<uuid>", "name": "...", "mimeType": "...", "sizeBytes": 0,
  "contentHash": "<sha256 hex of plaintext>", "contentKeyHex": "<64 hex>", "chunkCount": 0,
  "addedAt": 0, "addedBy": "<memberId>", "state": "ACTIVE", "revision": 0, "pad": "..." }
```

#### The container

| | |
|---|---|
| Slots | **256** |
| Slot size | **512** bytes (12-byte nonce + ciphertext + 16-byte tag) |
| Container size | **131072** bytes, always, forever |
| Plaintext room per slot | **484** bytes — every payload is padded to exactly this |
| Replicas per item | **2** |
| Documents per code | 64 |

Created full of `SecureRandom` bytes **on first run, on every device, in every family**,
whether or not a code is ever set. It has no header, no magic number and no version byte.
Nothing anywhere records that a vault was created, so there is no setup state to find and no
question about it that can be answered.

#### Opening, and why there is no wrong code

```
vaultKey(code) = Argon2id(
    password = UTF8(code),
    salt     = SHA-256(UTF8(groupId))[0..15],     // crypto_pwhash SALTBYTES
    outLen   = 32,
    opslimit = crypto_pwhash_OPSLIMIT_INTERACTIVE,
    memlimit = crypto_pwhash_MEMLIMIT_INTERACTIVE, // 64 MiB; MODERATE's 256 MiB thrashes a phone
    alg      = crypto_pwhash_ALG_ARGON2ID13)
```

Opening means attempting AES-256-GCM on **every** slot and keeping whatever authenticates.
The family code opens the real slots, a decoy code opens the decoy slots, and any other code
opens nothing and yields an empty vault.

Rules an implementation must not break:

- **Never report a wrong code.** There is no such thing. An empty vault is the ordinary result
  and must be presented as an empty vault. An error message would prove that a correct code
  exists.
- **Never exit early.** Derive, then try all 256 slots, regardless of outcome. A timing
  difference is as good as an error message.
- **Never store the code, the key, or a hash of either.** A code in the binary would be the F2
  broker-credential mistake again.
- **Never offer an "enter code" prompt.** The prompt itself proves a vault exists. Android's
  entry point is the file search box: typing filters the list, submitting (IME "Go", 6+
  characters) opens a vault. Any string opens one, so submitting distinguishes nothing.

An item appears in `REPLICAS` slots; when several copies of one `fileId` open, the **highest
`revision` wins**. `state` is `ACTIVE` or `RECLAIMED` — deletion flips the state and keeps the
bytes rather than overwriting the slot, so it can be undone.

#### Slot order, and the collision you cannot avoid

Each key walks the container in its own order, so a vault returns to its own slots instead of
scattering writes. It must match across platforms, so it is **not** a seeded platform RNG:

```
order = [0 .. 255]
block = SHA-256(key ‖ "slots"); offset = 0
for i from 255 down to 1:
    if offset + 4 > 32: block = SHA-256(block); offset = 0
    v = big-endian uint32 of block[offset .. offset+3]; offset += 4
    j = v mod (i + 1)
    swap order[i], order[j]
```

Allocation walks that order and takes the first slot that does **not** open under this key.
That is the only test available — and it means writing to one vault can overwrite a slot
belonging to a vault whose code this device does not know. **This is inherent, not a defect:**
the indistinguishability that makes the decoy credible is the same property that makes
allocation blind. Two replicas per item turn a collision into loss of redundancy rather than
loss of the document. Do not attempt to "fix" it with an occupancy map — that would reveal how
many vaults exist.

#### Container sync

Retained, versioned, signed, on rules identical to the file manifest:

- Sign over `"{version}|{data}"`; the version is inside the payload so a signature cannot be
  replayed at a higher version to roll the family back.
- Verify `signerMemberId` against **our own current roster**, never a key from the message.
- **Refuse an unsigned container.** Accepting one lets anyone reaching the broker replace a
  family's vault with random bytes, which is indistinguishable from erasing it.
- **Ignore any version at or below the highest already seen**, and refuse anything that is not
  exactly 131072 bytes.

`data` is deliberately *not* encrypted under the group key. It is already nothing but GCM
ciphertext and random filler, and wrapping it would only hide from the relay that a container
exists — which the topic already implies.

#### Document bytes

Stored in a blob store of their own under a random UUID, encrypted with the item's
`contentKeyHex`, chunked at 32 KiB exactly like shared files. They are deliberately **not**
shared files: a `SharedFile` row would put them in the manifest, which every member can read,
telling the whole family how many documents a vault holds.

Receiving a vault chunk **asks no questions and does no authentication**. A device very likely
holds no code that can read what is arriving, and refusing to store what it cannot verify
would mean vault documents never left the phone that added them. The content key
authenticates every chunk at the moment someone opens the vault; a corrupt chunk fails there.

Serving a `VaultChunkRequest` likewise consults only the blob store. Holding the bytes is the
entire qualification, and answering reveals nothing — every device holds every vault document,
so answering does not imply being able to read it.

A device that was offline when a document was added fetches it the next time someone opens the
vault **on that device**. It cannot do better: without a code it does not know the blob is
referenced by anything.

#### Runtime rules

Screen marked `FLAG_SECURE` (iOS: keep it out of the app switcher snapshot). Key in memory
only, dropped when the screen stops and after an idle timeout (Android: 3 minutes). Decrypt to
a private cache only while another app opens the document, and wipe that cache on close and at
launch. No thumbnails, no photo-library or Files-app export, no notifications, and vault items
never appear in any transfer log or status board.

---

## 7. Group state machine & security rules

### 7.1 Canonical state hash (`GroupDefinition.computeStateHash`)
SHA-256 (lowercase hex) of the UTF-8 canonical string:
```
{groupId}|{groupName}|{createdAtEpochMs}|{creatorMemberId}|{version}|
  then for each member sorted ASCENDING by memberId:
{memberId},{ed25519PublicKey},{x25519PublicKey};
```
then, **only when `removedMemberIds` is non-empty**, append:
```
|removed:{memberId};   for each tombstone sorted ASCENDING, each ending with ';'
```
(no newlines; every member entry ends with `;` including the last; `previousStateHash`,
`fileEncryptionKey`, displayName, avatar, etc. are **not** hashed).

`fileEncryptionKey` is excluded deliberately and must stay excluded. Hashing it would
change the hash of every group that predates it, breaking the chain across the upgrade,
and would fold a secret into a value that is compared and logged freely.

Tombstones, by contrast, **are** hashed: anything outside this hash is outside the
signature (§7.2), so an unhashed tombstone could be stripped in transit to readmit a
removed member. The non-empty guard is what keeps that from breaking existing groups — a
group that has never removed anyone hashes exactly as it did before the field existed.

### 7.2 Sync signature
`GroupSyncMessage.signature` = Ed25519-detached over the UTF-8 string
```
{groupId}|{version}|{updaterMemberId}|{timestamp}|{computeStateHash(groupDefinition)}
```
⚠️ Verify with the updater's key from **your current local roster** — never from the
incoming definition. Unknown updater ⇒ reject.

### 7.3 Transition validation (apply before accepting any remote GroupDefinition)
Reject the update if any of:
- `groupId`, `creatorMemberId`, or `createdAtEpochMs` changed;
- updater is not in the current local roster;
- `remote.version == current.version + 1` but `remote.previousStateHash != current.computeStateHash()`;
- any surviving member's keys changed (key rotation unsupported);
- any **added** member's `memberId != SHA-256(their ed25519 key)[0..15]` hex;
- members were removed and the updater is neither the creator nor removing only itself;
- any tombstone present locally is **missing** from the remote (append-only);
- any member appears in the roster **and** in `removedMemberIds`;
- a **new** tombstone appears and the updater is neither the creator nor tombstoning only
  itself (same authority as the removal it records);
- `groupName` changed and updater is not the creator.

**Concurrent siblings are validated differently.** When `remote.version == local.version`
the two states are siblings, not successors, and two of the rules above must be relaxed or
reconciliation is impossible:

- skip the `previousStateHash` chain rule — siblings share your parent, they do not descend
  from you;
- skip the untombstoned-removal rule — at an equal version, a member you hold and they lack
  has not been removed, only not yet learned about. Real removals travel as tombstones, and
  the tombstone rules above still apply in full.

Everything protecting identity — immutable group fields, updater membership, no key
rotation, added members hashing to their own ID, append-only tombstones — is enforced
unchanged for siblings.

### 7.4 Version ladder (incoming `GroupSyncMessage`)
- `version < local` → sender is stale: rebroadcast own state (`VERSION_SYNC`).
- `version == local` **and the state hashes match** → send plaintext ack on the group ack
  topic. Nothing to do.
- `version == local` **and the hashes differ** → two members edited concurrently.
  **Reconcile — never acknowledge and drop.** See below.
- `version == local + 1` → validate (§7.3), apply, ack; if `MEMBER_ADDED`, also fire a
  `GroupStateRefreshRequest` so laggards catch up.
- `version > local + 1` → flag conflict state, still validate & apply.
- If the local member is absent from an accepted new roster → emit **RemovedFromGroup**
  (wipe group state, return to onboarding).
- After broadcasting an update, wait up to **30 s** (poll 500 ms) for acks from
  `memberCount − 1` peers before reporting synced.

#### Reconciling a concurrent edit (required, since 1.12.7)

Any member may approve a join, so two members approving at the same moment both produce a
state at version N+1 from the same parent. Acknowledging a same-version message and moving
on — which is what Android did until 1.12.7 — leaves both sides believing they are in sync
while holding different rosters, and every later update then fails the chain check because
it descends from the other branch. **This is not a rare edge case:** it happened in a
four-member family the first day two devices were tested together.

Both sides run the same procedure and converge in one round:

1. **Pick the winner**: the state whose `computeStateHash()` is lexicographically smaller.
   The hash is already deterministic across platforms, so both devices choose identically
   with no coordination, clock, or tie-break authority. Equal hashes mean equal states —
   not a conflict.
2. **If your state is the winner**, change nothing and rebroadcast it. The peer will merge
   onto it.
3. **If theirs is the winner**, validate it as a sibling (§7.3), then build:
   - `members` = union of both rosters, minus the union of both tombstone sets, preferring
     the winner's record where both hold the same member ID (keys are immutable, so the
     records can only differ cosmetically, and preferring one side consistently keeps the
     result order-independent);
   - `removedMemberIds` = union of both tombstone sets;
   - `version` = `winner.version + 1`; `previousStateHash` = `winner.computeStateHash()`;
   - `fileEncryptionKey` = the winner's, or yours if the winner has none.

   If that yields exactly the winner's roster and tombstones, **adopt the winner unchanged
   instead of emitting a version bump** — otherwise two devices holding equivalent states
   will each "merge" the other forever.
4. Persist, then broadcast the result whenever it differs from what arrived.

The merged state is an ordinary version+1 successor of the winner, so the winner accepts it
through §7.3 with no special case: from its point of view the peer simply added the members
it was missing. Removals beat additions — a tombstoned member is dropped even if the other
side still lists them — which is the safe direction, since the alternative silently
readmits someone who was just removed.
- Incoming refresh request: respond (full `FULL_SYNC` broadcast) only if
  `local.version >= request.minimumVersion` and groupId matches.

---

## 8. Onboarding & join flow

### 8.1 Create family
Generate mnemonic → derive keys → `GroupDefinition{groupId = random UUID, version = 1,
creatorMemberId = self, members = {self}, previousStateHash = null}` → persist → connect.

### 8.2 Invite code / QR
Base64 (standard, padded) of a JSON **object of strings**:
```json
{ "groupId": "...", "groupName": "...", "inviterMemberId": "...",
  "inviterName": "...", "timestamp": "<epoch ms as string>" }
```
The QR encodes this Base64 string. (An `InviteData` class with a signature field exists in
the codebase but is *not* what the current invite path emits — match the map format above.)

### 8.3 Joiner
1. Scan QR → decode → extract `groupId`, `inviterMemberId`.
2. Generate own mnemonic/keys (`requesterId = own memberId`).
3. Ephemeral MQTT client (`familysafe_join_{id8}_{ts}`, cleanSession=false):
   subscribe `familysafe/{self}/join_approval`, then publish **retained** plaintext
   `JoinRequest` to `familysafe/{inviterMemberId}/join_request`, disconnect.
```json
// JoinRequest — NOTE: keys are Base64, not hex!
{ "requestId": "<uuid>", "requesterId": "...", "displayName": "...",
  "ed25519PublicKey": "<base64>", "x25519PublicKey": "<base64>",
  "groupId": "...", "timestampMs": 0 }
```
4. Wait on the pending screen (re-publish request every ~30 s), listening on `join_approval`.

### 8.4 Inviter (approval)
1. Validate `deriveMemberId(request.ed25519PublicKey) == request.requesterId`; reject otherwise.
2. Clear the retained join_request (empty retained publish).
3. Add member locally (approval signature payload, checked by GroupStateManager:
   `"ADD:{groupId}:{preAddVersion}:{newMemberEd25519Hex}"`, Ed25519-signed by inviter).
4. Publish **retained** to `familysafe/{joiner}/join_approval`:
```json
{ "inviterEd25519PublicKey": "<hex>", "inviterX25519PublicKey": "<hex>",
  "encryptedGroupDefinition": "<Envelope JSON (§3) wrapping the GroupDefinition JSON>" }
```
5. Broadcast `MEMBER_ADDED` group sync to existing members + refresh request.

Rejection: same topic, retained, distinct field names:
```json
{ "inviterEd25519PublicKey": "<hex>", "inviterX25519PublicKey": "<hex>",
  "encryptedRejection": "<Envelope wrapping {\"requestId\":\"...\",\"joinerMemberId\":\"...\",\"timestamp\":0}>" }
```
Track closed requestIds (persisted, cap ~200) to ignore broker redeliveries.

### 8.5 Joiner completes
Verify `SHA-256(inviterEd25519PublicKey)[0..15] == inviterMemberId` **from the scanned QR**
(this is the root of trust); decrypt/verify the envelope with the inviter's keys; adopt the
GroupDefinition; reconnect as a normal member. Rejection payloads must match the joiner's
own pending `requestId` + memberId (anti-replay).

---

## 9. Security invariants checklist (verify on iOS before shipping)

1. Signature verified **before** decryption; envelope sender keys always from local roster.
2. `senderMemberId` treated as unauthenticated routing metadata until signature check passes.
3. All of §7.3 enforced on every remote group state, including sync-delivered ones.
4. Sync signatures verified against the **local** roster (§7.2).
5. Join request ID↔key binding checked on both sides (§8.4.1, §7.3).
6. Joiner authenticates the inviter via the QR-scanned memberId, nothing else.
7. Rejections bound to requestId + joinerMemberId (no replay canceling future joins).
8. Receipts validated against known message IDs addressed to the receipt sender.
9. Private keys in Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`); DB encrypted.
10. The only personal signals left in the clear are the topic's `memberId` and the timing of
    publishes — the presence body itself is sealed as of 1.6, and the version it advertises
    is only acted on after authentication (§6.2). Keep it that way; adding a plaintext field
    to the presence topic is a wire change, not a convenience.

---

## 10. iOS technology mapping

| Android | iOS recommendation |
|---|---|
| Kotlin + Compose | Swift 5.10+ / SwiftUI (iOS 16+) |
| Hilt DI | Simple environment/initializer injection (don't over-engineer) |
| Lazysodium (libsodium) | **swift-sodium**'s `Clibsodium` product, called via raw C functions directly (not swift-sodium's Swift wrapper — see `ios/FamilySafety/Sources/FamilySafetyCore/SodiumRaw.swift`) |
| BouncyCastle PBKDF2 | Pure-Swift PBKDF2-HMAC-SHA512 built on CryptoKit `HMAC<SHA512>` (not CommonCrypto — avoids an SPM bridging-header/module-map requirement; see `Bip39.swift`) |
| HMAC-SHA512 (SLIP-10) | CryptoKit `HMAC<SHA512>` |
| AES-256-GCM (files) | CryptoKit `AES.GCM` (`SealedBox.combined` == Android blob layout) |
| Paho MQTT | **CocoaMQTT** (3.1.1, cleanSession=false, LWT, QoS1) |
| Room + SQLCipher | **GRDB.swift + SQLCipher** (or Core Data + file protection) |
| DataStore (group persistence) | File/`UserDefaults` for non-secret state; Keychain for keys |
| FusedLocationProvider + foreground service | CoreLocation: `allowsBackgroundLocationUpdates`, significant-change relaunch |
| GeofencingClient | `CLCircularRegion` monitoring (max 20 regions) |
| osmdroid/map | MapKit |
| ZXing / CameraX QR | `AVCaptureMetadataOutput` (scan), CoreImage `CIQRCodeGenerator` (generate) |
| kotlinx.serialization | `Codable` (custom `CodingKeys` not needed — names already match) |

Raw libsodium calls needed (via Clibsodium): `crypto_sign_seed_keypair`,
`crypto_sign_detached`, `crypto_sign_verify_detached`, `crypto_scalarmult_base`,
`crypto_box_beforenm`, `crypto_secretbox_easy`, `crypto_secretbox_open_easy`.

Swift gotchas:
- `JSONEncoder` field order differs from Android — irrelevant, everything is key-based.
- Emit `null` or omit optionals freely; **decode must accept both**.
- Enums: define `String`-raw-value enums with UPPERCASE cases.
- Double-encoded `MessageEnvelope.payload`: encode inner object to `String(data:)` first.
- Hex must be lowercase on encode; accept any case on decode.

---

## 11. iOS platform divergences (design decisions, not interop)

- **No foreground service.** Continuous background location requires the Always
  authorization + `allowsBackgroundLocationUpdates = true` + background mode
  `location`. Use significant-location-change + region monitoring to get relaunched
  after termination.
- **No persistent background MQTT.** iOS suspends sockets in background. Mitigations,
  in order of value: (1) publish location whenever CoreLocation wakes the app (connect →
  drain → publish → disconnect, ~10 s background window); (2) rely on broker persistent
  session + QoS 1 + retained presence/approvals so nothing is lost while suspended;
  (3) `BGAppRefreshTask` for periodic pulls; (4) later, an optional APNs relay would need
  a server — out of scope, note the tradeoff in-app ("updates may be delayed when the
  app is closed").
- LWT fires when iOS drops the socket → peers correctly see the device offline; retained
  presence flips back on next wake.
- Info.plist: `NSLocationAlwaysAndWhenInUseUsageDescription`,
  `NSLocationWhenInUseUsageDescription`, `NSCameraUsageDescription`,
  `UIBackgroundModes = [location, fetch, processing]`.
- App Store review: expect scrutiny on Always-location; the privacy story (no server,
  E2EE) is the justification — write the purpose strings accordingly.

---

## 12. Cross-platform test vectors (ground truth — generated with libsodium; BIP-39 step verified against the published reference vector)

Generator script: `ios/tools/gen_test_vectors.py` (run with `pip install pynacl`).

### Identity A ("Alice")
```
mnemonic:      abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about
bip39 seed:    5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4
ed25519 seed (m/44'/1984'/0'/0'): a214b6228010d923e78eb067d734b926fcdcce2833a3da0b671e9dce9a2953c8
ed25519 pub:   061cca3d77168020734b44ea483a73797f262b0a2f1bf055f60cc7c3f27dd4f0
x25519 priv (m/44'/1984'/0'/1', clamped): 2075e909585ee3c89e45c00107c1c2d620453fe0f872ac39fd5b831b9bc3b57c
x25519 pub:    edabb2cfdf3bc943d86e63be6f7cc71fabb3a4d287b4c86845d8749697d47a28
memberId:      240dc306e6f9d2af4309c8ec30f0058e
```

### Identity B ("Bob")
```
mnemonic:      legal winner thank year wave sausage worth useful legal winner thank yellow
bip39 seed:    878386efb78845b3355bd15ea4d39ef97d179cb712b77d5c12b6be415fffeffe5f377ba02bf3f8544ab800b955e51fbff09828f682052a20faa6addbbddfb096
ed25519 seed:  2414440fdec6d167a475fc99cb23734af50aefbcd66687795735bec07efa4d46
ed25519 pub:   f93f2139ee8553887745ef5210f10bcd3efb5201fe39522dc3d7289f2826edae
x25519 priv:   584dbb39684be08e09bc43daf8314a94242dca6ea8a59fa887f92486fff18c67
x25519 pub:    bc5d571ecdb705f52f6119bae82be2d5aa92c61a0ed98b979489c9e57d671e69
memberId:      146002794e05db8fea5ac6f48c319e8c
```

### E2EE envelope, Alice → Bob (fixed nonce for the test)
```
shared secret (crypto_box_beforenm): 9c57a8617544ec897c2ed42534ad889f1858dd860bd3eac7480cd06087cbe9d2
plaintext (utf8): hello from alice
nonce:      000102030405060708090a0b0c0d0e0f1011121314151617
ciphertext: 10763b3494d286388ea8a19697f07b9ba88288a51f51014b0d3c69e854226deb
signature:  a9e6dc38c05b1e2425fc35c6795d52fbcba01032853adcd7acd55207ac76d5fea84af4a2070da75fc58f92a14a7b88d8138fd583a04f384bef769ac77159af09
```
Assertions: shared secret symmetric (Bob computes the same); signature verifies with
Alice's Ed25519 pub over `nonce ‖ ciphertext`; secretbox opens to the plaintext.

### GroupDefinition.computeStateHash
Group: `groupId=11111111-2222-3333-4444-555555555555`, `groupName=Test Family`,
`createdAtEpochMs=1700000000000`, `creatorMemberId=<Alice>`, `version=2`,
members = {Alice, Bob} (note Bob sorts **first** by memberId).
```
canonical: 11111111-2222-3333-4444-555555555555|Test Family|1700000000000|240dc306e6f9d2af4309c8ec30f0058e|2|146002794e05db8fea5ac6f48c319e8c,f93f2139ee8553887745ef5210f10bcd3efb5201fe39522dc3d7289f2826edae,bc5d571ecdb705f52f6119bae82be2d5aa92c61a0ed98b979489c9e57d671e69;240dc306e6f9d2af4309c8ec30f0058e,061cca3d77168020734b44ea483a73797f262b0a2f1bf055f60cc7c3f27dd4f0,edabb2cfdf3bc943d86e63be6f7cc71fabb3a4d287b4c86845d8749697d47a28;
state_hash: 932d4307c8ceb3e574515bb734267d7d94f0ed876ab5844b7a4ef5cf9c4c0cbd
```

### GroupSyncMessage signature (Alice, timestamp 1700000001000)
```
payload:   11111111-2222-3333-4444-555555555555|2|240dc306e6f9d2af4309c8ec30f0058e|1700000001000|932d4307c8ceb3e574515bb734267d7d94f0ed876ab5844b7a4ef5cf9c4c0cbd
signature: 7369363820e6719d024dbee66f5935fbe0846ed861183264038d04616beefd66e68afffa32f08e92a20cc7a7f4146b2e2cb0ae4e124b33921aa56ea4e24ca30d
```

### Member-add approval signature (Alice approves Bob at pre-add version 1)
```
payload:   ADD:11111111-2222-3333-4444-555555555555:1:f93f2139ee8553887745ef5210f10bcd3efb5201fe39522dc3d7289f2826edae
signature: 34d343dff90717fc067e47957c947a83f10d42336d26d3e0130793213f93f5c3bfd66d430dc8a5957dd525c274007530643e2ecaca21bac9e907b3bd34efb90a
```

### Shared-file key
```
groupId:  11111111-2222-3333-4444-555555555555
file_key: 615d9ec6399937470f89418ab98e10f3107c56d7a09b6bf234263d8ad842becd
```

---

## 13. Project structure (scaffolded)

`ios/FamilySafety/` is a SwiftPM package — open the folder directly in Xcode (no
`.xcodeproj` needed). Structure actually on disk, mapped against the original plan:

```
ios/FamilySafety/
  Package.swift                              depends on swift-sodium (Clibsodium product only)
  Sources/FamilySafetyCore/
    Hex.swift                                 hex codec
    SodiumRaw.swift                           raw libsodium C calls (Core)
    Bip39.swift                               BIP-39 + pure-Swift PBKDF2 (Core)
    Slip10.swift                              SLIP-10 derivation (Core)
    Identity.swift                            FamilySafeIdentity / key derivation (Core)
    SodiumCrypto.swift                        E2EE envelope encrypt/decrypt (Core)
    Resources/bip39_english.txt               byte-for-byte copy of the Android wordlist
    Group/Models.swift                        GroupDefinition/FamilyMember + stateHash (Group, partial — Phase 1 adds GroupTransitionValidator, GroupStateStore)
    Files/SharedFileCrypto.swift               file-key derivation only (Files, partial — Phase 6 adds the rest)
    Transport/, Sync/, Invite/, Chat/, Replication/   empty — fill in per phase below
  Tests/FamilySafetyCoreTests/
    VectorTests.swift                         every §12 vector as an XCTest — this is Phase 0's acceptance test
```

Not yet created (add when that phase starts): `Location/`, `Storage/`, and the SwiftUI
`UI/` target + app target with Info.plist (§11) — those need an actual app target, not
just a library package, and are Phase 4/6/7 concerns respectively.

## 14. Phased build plan (one budget-friendly session per phase)

**Phase 0 — Crypto core.** Bip39 + Slip10 + Identity + E2EE envelope.
**Scaffolded already** (§13) — Bip39.swift, Slip10.swift, Identity.swift,
SodiumCrypto.swift, SodiumRaw.swift, plus the Group/Models.swift + Files/SharedFileCrypto.swift
needed for the state-hash/signature/file-key vectors. **Not yet done:** this was
scaffolded on Windows, which has no Swift toolchain — nobody has run `swift test` yet.
*Done when:* on a Mac, `cd ios/FamilySafety && swift test` passes every case in
`VectorTests.swift` (identities, shared secret, envelope encrypt with the fixed nonce,
decrypt+verify, state hash, both signatures, file key). Fix whatever the compiler or a
failing assertion turns up — the vectors are ground truth, the Swift code is not yet
proven correct.

**Phase 1 — Models & validation.** GroupDefinition/FamilyMember Codable + `computeStateHash`
+ GroupTransitionValidator + all wire structs in §6 with round-trip tests (including
null-vs-absent tolerance, Base64 JoinRequest keys, double-encoded MessageEnvelope).
*Done when:* hash vector passes and each §7.3 rejection rule has a failing-input test.

**Phase 2 — Transport.** CocoaMQTT wrapper: connect (cleanSession=false, LWT), subscriptions
(§5), pending queue (200/1 h), retained-clear helper, reconnect backoff, presence publish.
*Done when:* against `broker.hivemq.com:8883`, two iOS simulator instances see each other's
presence flip online/offline (kill one → LWT observed), each reading the other's
`protocolVersion` as the current generation rather than 1 (§6.2) — a simulator that shows
its twin as outdated is emitting presence wrong, and against a real Android peer that
mistake is invisible from the iOS side.

**Phase 3 — Group sync + join.** GroupSyncManager (version ladder, acks, refresh) + invite
QR + joiner/inviter flows. *Done when:* **interop test** — iOS device joins a group created
on the Android build via QR, appears in Android's member list, and survives an
Android-side rename + member-remove correctly.

**Phase 4 — Location & presence UI.** CoreLocation publisher (per-peer fan-out), map screen.
*Done when:* Android sees live iOS location and vice versa.

**Phase 5 — Chat.** Messages, group chat, delivery/read receipts, local store.
*Done when:* bidirectional chat with Android incl. receipts.

**Phase 6 — Replication + files.** Includes the whole of §6.7, not just the happy path:
per-index chunk accounting, targeted repair with a durable retry schedule, `isEssential`,
holdings announcements, manifest **signing** (unsigned manifests are refused by Android, so an
iOS client that does not sign will silently stop updating anyone's file list) and deletion
tombstones. *Done when:* a fresh iOS install backfills history from an Android peer; a file
uploaded on Android opens on iOS (hash-verified); killing iOS mid-transfer and relaunching
resumes to completion **without a user tap**; deleting on Android removes it from iOS; and an
Android peer's status board reports the iOS device as holding a copy.

**Phase 6.5 — Vault** (§6.8). Separable from Phase 6 and worth its own session, because the
failure modes are unlike anything else in the app: every assertion is about something *not*
being observable. *Done when:* Argon2id matches Android for a shared code (compare derived key
hex on both), a code that opens nothing shows an empty vault with no error and no measurable
timing difference, a document added on Android opens on iOS under the same code and is
invisible under a different one, and the container is byte-identical on both devices after a
write from either. **Do not ship the container creation lazily** — it must exist, full of
random bytes, from first launch.

**Phase 7 — Background strategy, geofences, history, polish, App Store prep** (§11).

Every phase: test against the **Android app on the dev broker** as the reference
implementation. When behavior is ambiguous, the Android source file named in the relevant
section of this spec is the tiebreaker — do not invent.

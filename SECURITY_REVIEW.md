# Security Review — relay exposure

Review date: 2026-08-08. Reviewed against `main` at `8abdad3` (1.11.4 / 17).

Scope: what an attacker learns and can do with (a) a copy of the published APK
and (b) network access. It does not cover device compromise, Play account
compromise, or the local Room/SQLCipher store.

Credentials, group IDs and member IDs are redacted here on purpose — this file
is committed. The live values are in `keystore/mqtt.properties` and in the
broker console.

## Method

Findings below were confirmed empirically, not inferred. Broker credentials were
read out of the release `BuildConfig`, a TLS MQTT client was connected from a
desktop, and `familysafe/#` was subscribed for several minutes. Only topic
names, payload sizes and plaintext presence bodies were read; no encrypted
payload was attacked. That session observed **11 distinct member IDs across 3
distinct group IDs** — i.e. traffic belonging to families other than the
tester's.

## Trust model as built

| Asset | Protection | Holds against a broker observer? |
|---|---|---|
| Location payloads | X25519 per recipient + Ed25519 signature | Yes |
| Chat, receipts, group sync | Same envelope | Yes |
| **Shared files** | AES-256-GCM, key = `SHA-256(groupId + constant)` | **No — see F1** |
| Presence | Plaintext by design (MQTT will cannot be encrypted) | No, and forgeable |
| Member IDs, group membership | Encoded in topic names | No |
| Movement timing | Publish cadence on `location_inbox` | No |

The per-recipient envelope is sound. Everything below is about what sits
*outside* it.

Status at time of writing: F1, F4, F6 and F7 are fixed; F2, F3 and F5 are open. The F1
and F7 fixes only take effect for families created on 1.12.0 or later, because both
depend on the group key generated at group creation — see Phase 0.

F4's fix changes the conclusion of Phase 1/2 below. Broker ACLs were the proposed answer
to presence forgery, but signatures address it more completely — no credential scheme
prevents one family member forging another's presence, whereas a signature does. Phases
1 and 2 remain worth doing for F3 (metadata), not for F4.

## F1 — Shared files are decryptable by anyone on the broker (high) — FIXED in 1.12.0

`SharedFileRepository.deriveFileKey`:

```kotlin
val material = (groupId + FILE_KEY_SALT).toByteArray(Charsets.UTF_8)
return MessageDigest.getInstance("SHA-256").digest(material)
```

Both inputs are public to an attacker:

- `FILE_KEY_SALT` is a compile-time constant, recoverable from the APK.
- `groupId` is published in cleartext as part of the topic name:
  `familysafe/group/{groupId}/files/manifest` and `.../files/chunk/#`.

So the file key is derived entirely from public values. An attacker subscribes
to `familysafe/group/+/files/chunk/#`, reads `groupId` off the topic, derives
the key, reassembles chunks by `fileId`/`chunkIndex`, and decrypts. Three group
IDs were observable in a single short session.

Unlike location and chat, files have **no** per-recipient encryption. This is
the one place where the "no central server sees plaintext" property does not
hold.

Fix: see Phase 0.

## F2 — One shared broker credential for every install (high)

`BuildConfig.MQTT_USERNAME` / `MQTT_PASSWORD` are baked into every APK and are
identical for all users. Consequences:

- Anyone who downloads the app gets full broker read/write.
- There are no per-topic ACLs, so that access spans every family, not just
  their own.
- Rotation means shipping a new build and breaking every older install
  simultaneously.

## F3 — Metadata is fully exposed (medium)

Even with F1 fixed, an observer still learns, without decrypting anything:

- every member ID and which group each belongs to
- who is online and the exact moment they go offline
- when each member's location changes, since a publish implies movement
- message sizes and cadence per feature

For a location product, movement timing alone reconstructs a household routine.

## F4 — Presence is forgeable (medium) — FIXED in 1.12.3

`MqttTransport.handlePresence` rejects payloads whose `memberId` disagrees with
the topic:

```kotlin
if (presenceUpdate.memberId != senderId) { /* ignore */ }
```

That defends against a member lying about *others* on their own topic. It does
not help when the attacker can publish to arbitrary topics: publishing to
`familysafe/{victim}/presence` with `memberId = {victim}` satisfies the check.

As of `79bb50f` presence also drives `ConnectionState`, so a forged offline
message now marks a member offline in peers' UI.

Fixed by signing presence with Ed25519 rather than by ACL. An ACL would have
been the wrong tool even if one were available: a publish rule stops an
outsider, but every per-device credential scheme still lets one family member
forge another's presence, and inside the family is where this matters most.
The group definition already distributes every member's Ed25519 key, so the
check can be cryptographic rather than positional.

- Signed over a canonical `presence:{memberId}:{isOnline}:{timestamp}` rather
  than the serialized JSON, so field order or a future additive field cannot
  change what was signed.
- The last-will is signed at **connect** time and handed to the broker
  pre-signed, since the device is by definition gone when the broker publishes
  it.
- **Replay** rejected by timestamp: a captured "offline" message cannot be
  republished later. Equal timestamps are allowed, because the broker
  redelivers retained messages on every resubscribe.
- **Downgrade** rejected by memory: once a member has been seen signing, an
  unsigned update claiming to be them is an attacker stripping the signature,
  not an old client. Before that first signature unsigned is accepted, so peers
  on older builds do not appear permanently offline.

Residual: a member never yet seen signing can still be impersonated, which is
unavoidable while older clients exist. That window closes per member on their
first signed update after upgrading.

Presence padding moved from 256 to 512 bytes as a consequence — the 128-character
signature pushes the envelope past 256, and leaving it would have split presence
into two buckets, making signed and unsigned senders distinguishable by size.

## F5 — Retained-message pollution (low)

`join_approval` and file manifests are published with `retained = true`.
Retained messages persist until overwritten or explicitly cleared, and an
attacker can write them. At review time **7 stale `join_approval` messages**
were retained on the broker, the oldest several weeks old. These replay to any
client that subscribes.

## F6 — Message length leaked behaviour through encryption (medium) — FIXED

Observed on the live broker, not theorised. Presence messages were **136 bytes when
online and 137 when offline**, because `"true"` is four characters and `"false"` is
five. The correlation held for all 13 member IDs visible at the time. Online/offline for
every member was therefore readable without parsing anything at all.

The same channel applied to encrypted location. `LocationUpdate.speed` and `.bearing`
are optional and only populated when the device is moving, so a moving device emitted a
measurably longer ciphertext than a stationary one — 745 vs 763 bytes in one capture.
Coordinate precision leaked similarly: `1.0` and `37.77491234567` serialize to different
lengths, so payload size varied with position, not just motion.

Encryption does not help here. Ciphertext length tracks plaintext length, so the signal
survives intact.

Fixed by padding the envelope to a fixed size, applied to the **plaintext** so ciphertext
inherits the constant length: presence to 256 bytes, location to 512. Fixed sizes rather
than buckets — a variation straddling a bucket boundary still leaks. Oversized messages
round up to a multiple instead of going out at their true length.
`MessageProtocolPaddingTest` asserts on byte counts so the leak cannot silently return.

**Still unpadded:** chat (leaks message length), replication, and group sync. Chat is a
deliberate deferral — its lengths span orders of magnitude, so padding is a bandwidth
decision rather than a free fix.

## F7 — Shared file names are published in cleartext (high) — FIXED in 1.12.2

`SharedFileRepository.broadcastManifest` publishes the manifest with no encryption:

```kotlin
transportProvider.broadcastMessage(
    topic, json.encodeToString(manifest).toByteArray(), QOS_AT_LEAST_ONCE,
    true  // retained
)
```

`SharedFile` carries `name`, `mimeType`, `sizeBytes`, `contentHash`, `uploaderMemberId`
and `uploadedAt`. So for every file any family has ever shared, an observer on the broker
learns the filename, its type, its exact size, who uploaded it and when — and because the
message is **retained**, it is served to any new subscriber immediately, indefinitely,
with no need to be listening at the time.

This survives the F1 fix. Encrypting file *contents* with a proper group key does nothing
for a manifest that was never encrypted in the first place. Filenames are frequently more
revealing than contents (`custody_agreement.pdf`, `passport_scan.jpg`), so in practice
this may leak more than F1 did.

Chunk payloads are largely uniform already — `CHUNK_SIZE` is 32 KiB and every chunk but
the last is exactly that — so the incremental leak from chunk sizing is small next to the
manifest. `chunkCount` in the manifest already gives file size anyway.

Fixed by encrypting the manifest **symmetrically with the group key** rather than per
recipient. Per-recipient encryption would have cost N publishes and the retained
catch-up that lets a joining member sync instantly; a symmetric group key keeps the
manifest a single retained broadcast while making it unreadable off the broker.

- `EncryptedFileManifest{keyVersion, data}` replaces the bare manifest on the topic, with
  `data` = AES-256-GCM over the serialized `FileManifest`.
- The plaintext is padded to a 1 KiB grid first, so ciphertext length no longer reveals
  how many files the family has or how long their names are.
- Receivers try the encrypted shape first and fall back to plaintext, because a retained
  plaintext manifest from before the upgrade can outlive it.

**Legacy groups still publish plaintext.** Their only available key is the version 1
derivation, which anyone on the broker can compute, so encrypting with it would be
theatre rather than protection. Recreating the family on 1.12.0+ remains the fix, exactly
as for F1.

## Remediation

### Phase 0 — File encryption (no broker changes)

Stop deriving the file key from public data. Requires a real secret shared by
group members and distributed over the existing encrypted channel. Wire-format
affecting: needs a key version on chunk messages and a matching update to
`IOS_PORT_SPEC.md`. Detailed below.

### Phase 1 — Per-device broker credentials

Issue `username = memberId` plus a random secret generated at onboarding and
held in Android Keystore next to the identity keys. EMQX supports HTTP- or
database-backed auth.

The hard part is bootstrap: a joining device needs broker access *before* it is
a member. Options are a narrowly-scoped bootstrap credential that may only
publish to `join_request`, or issuing credentials out of band inside the invite
payload.

### Phase 2 — ACLs

Once identity is per-device:

```
subscribe:  familysafe/{memberId}/#         own inboxes only
publish:    familysafe/+/location_inbox     fan-out to peers
            familysafe/{memberId}/presence  own presence only
```

The presence rule kills F4 structurally rather than by payload validation.
Phase 2 is what actually contains F3 — Phase 1 alone only makes access
attributable.

### Phase 3 — Retained hygiene

Clear stale retained messages, and give `join_approval` an expiry so an
un-consumed approval does not sit on the broker indefinitely.

## Not findings

- The per-recipient envelope resisted inspection; signature verification in
  `E2EEManager` rejects forged encrypted payloads, so F2/F4 permit disruption
  and metadata poisoning, not content impersonation.
- Presence being plaintext is a protocol constraint, not an oversight — MQTT
  last-will cannot be encrypted. The problem is who may write it, not that it
  is readable.

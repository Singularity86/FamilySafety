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

## F1 — Shared files are decryptable by anyone on the broker (high)

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

## F4 — Presence is forgeable (medium)

`MqttTransport.handlePresence` rejects payloads whose `memberId` disagrees with
the topic:

```kotlin
if (presenceUpdate.memberId != senderId) { /* ignore */ }
```

That defends against a member lying about *others* on their own topic. It does
not help when the attacker can publish to arbitrary topics: publishing to
`familysafe/{victim}/presence` with `memberId = {victim}` satisfies the check.

As of `79bb50f` presence also drives `ConnectionState`, so a forged offline
message now marks a member offline in peers' UI. The payload check cannot fix
this; only a publish ACL can.

## F5 — Retained-message pollution (low)

`join_approval` and file manifests are published with `retained = true`.
Retained messages persist until overwritten or explicitly cleared, and an
attacker can write them. At review time **7 stale `join_approval` messages**
were retained on the broker, the oldest several weeks old. These replay to any
client that subscribes.

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

# Security Review — relay exposure

Review date: 2026-08-08. Reviewed against `main` at `8abdad3` (1.11.4 / 17).
**Second pass 2026-08-17** against `main` at `92e6d9f`, covering the shared-file
correctness work and the family vault (F8–F12). Those two changes altered the
threat surface more than anything since the original review: one added
authenticity to the file index and moved documents out of plaintext at rest, the
other added an entire deniable-storage feature with its own offline attack target.
**F10 mitigated 2026-08-18** — passphrase floor raised and the vault now says what
protects it — which also produced a dated re-read of the F2 decision, below.

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

Status at time of writing: F1, F4, F5, F6 and F7 are fixed. **F3 is partially fixed** —
online/offline is no longer readable, but member IDs and movement timing still are.
**F2 remains open** and is the only finding that cannot be advanced in the app at all.

The F1, F3 and F7 fixes only take effect for families created on 1.12.0 or later, because
all three depend on the group key generated at group creation — see Phase 0. For a legacy
family, recreating it is the single action that switches on the most protection.

## Threat model decision (2026-08-10)

**The broker operator is not in the threat model.** F2 and the remainder of F3 are
therefore accepted risk, not backlog, and Phases 1 and 2 below are deliberately not
being done. To be revisited if the app gets real adoption beyond friends and family.

The reasoning, recorded so it need not be rederived:

- Every message is now signed and encrypted at the application layer, so the broker
  credential is no longer a security boundary. Its remaining job is abuse control —
  keeping strangers off the EMQX quota — and a shared credential is a defensible answer
  to that, since abuse is handled by rotation and quota alerts rather than by identity.
- Member IDs are already self-authenticating: `memberId = SHA-256(ed25519PublicKey)[0..15]`.
  Identity does not depend on the broker vouching for anyone, which is what made the
  missing issuer survivable in the first place.
- What per-device credentials would actually buy is ACLs, and what ACLs would buy is
  metadata reduction — F3, not integrity. With the broker operator out of scope, that
  spend has no threat to answer.
- The remaining exposure is disclosed rather than hidden: `PRIVACY_POLICY.md` states that
  the relay sees connection times, pseudonymous identifiers and send frequency, and can
  infer that someone is moving but never where.

What would reopen this: running the broker for people who are not friends and family;
a threat model that includes the broker operator or anyone who compromises it; or a user
population where "the relay learns your household's daily rhythm" is not an acceptable
answer.

### Re-read on 2026-08-18, prompted by the vault

F10 said the vault "is exactly the kind of change the F2 decision said should
trigger a re-read". This is that re-read. **No decision is changed here** — the
2026-08-10 call stands, and reversing it is not this document's to do. What
follows is what the re-read found, so the next person deciding has it.

None of the three named reopening conditions has occurred: the broker still
serves friends and family, the threat model still excludes its operator, and the
metadata answer is unchanged.

**One of the four reasoning bullets has acquired an exception.** "Every message
is now signed and encrypted at the application layer, so the broker credential is
no longer a security boundary" is true of every message except one. Everywhere
else, broker access yields ciphertext under keys derived from a 256-bit secret
that nobody guesses. The retained vault container yields ciphertext under a key
derived from something a person chose and can remember — and yields it as a
fixed artifact that can be attacked forever at leisure. For that one object the
credential *is* a boundary, and it is a shared one.

**The fourth bullet has also changed.** It said ACLs would buy metadata
reduction — "F3, not integrity" — and that with the operator out of scope, "that
spend has no threat to answer". The vault is the first thing on the broker that
gives per-topic ACLs a threat to answer which is neither metadata nor integrity:
restricting who may fetch `familysafe/group/{groupId}/vault/container` is the
only structural fix for F10, and it needs per-device credentials, which is F2's
fix. The container cannot simply stop being retained — retention is how a device
that joins later receives a vault it was never online for.

So the honest statement of the position is narrower than before: F2 is accepted,
and one consequence of accepting it is that the family's vault passphrase is
subject to unlimited offline guessing by anyone holding the shared credential.
The 2026-08-18 mitigation raises what that guessing costs. It does not remove
the exposure, and no change inside the app can.

Worth deciding explicitly before Phase 6 ships, since shipping it is what turns
this from a property of the code into a property of real families' documents.

Prior analysis of the issuer problem is kept below for that day.

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

## F3 — Metadata is fully exposed (medium) — PARTIALLY FIXED in 1.12.5

Even with F1 fixed, an observer still learns, without decrypting anything:

| Leak | Status |
|---|---|
| Who is online, and the moment they go offline | **Fixed in 1.12.5** |
| Every member ID, and which group each belongs to | Open — structural |
| When each member's location changes (a publish implies movement) | Open |
| Message sizes and cadence per feature | Partly addressed by F6 padding |

For a location product, movement timing alone reconstructs a household routine.

### Online/offline — fixed

Presence was published as **plaintext JSON**, so `isOnline` was simply readable. Note the
F6 padding did *not* address this: it closed the message-*length* channel, which for
presence was redundant with just reading the payload. The claim that padding hid
online/offline was overstated for presence; it mattered for location, where length was the
only leak.

Presence is now sealed with AES-256-GCM under a presence-specific subkey derived from the
group key — `SHA-256(groupKey ‖ "presence")`, so recovering it does not hand over the file
key. Sealed under a *group* key rather than per recipient because the last-will is
published by the broker after the device is gone, leaving no send-time at which to encrypt
to anyone. The will is sealed at connect time, alongside its signature.

Legacy groups with no shared key keep publishing plaintext, for the same reason as F1 and
F7: their only alternative key is broker-derivable, so sealing with it would be theatre.

### What remains, and why

**Member IDs in topic names** is structural — every topic is `familysafe/{memberId}/…`.
Hiding it needs pseudonymous topic identifiers (e.g. `HMAC(groupKey, memberId)`), which
unlinks topics from identities and families from each other, but leaves a stable
per-entity handle and is a wholesale change to every topic. Not attempted.

**Movement timing** is only fixable with cover traffic — publishing on a fixed schedule
whether or not anything moved. That costs battery and bandwidth continuously to hide a
signal, which is a product decision rather than a bug fix.

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

## F5 — Retained-message pollution (low) — FIXED in 1.12.4

`join_approval` and file manifests are published with `retained = true`.
Retained messages persist until overwritten or explicitly cleared, and an
attacker can write them. At review time **7 stale `join_approval` messages**
were retained on the broker, the oldest several weeks old (8 by the time this
was fixed). These replay to any client that subscribes.

Root cause was an asymmetry in `MembershipViewModel`, not the retention itself.
The rejection path cleared both the retained approval and the joiner's own
retained join request; the **approval path cleared neither**. So every
*successful* join left a 2.4–3.9 KB approval on the broker permanently — one
per member ever added, each replayed to anything that subscribes, and each a
standing record that the member joined and when.

Fixed by mirroring the rejection cleanup on success. Ordering matters and is
deliberate: the clear happens **after** the group definition is durably saved,
because clearing first would leave a device that died mid-join with neither a
saved group nor a retained approval to recover from.

**Existing litter is not removed by the fix** — those 8 messages sit on topics
belonging to members, and deleting one that has not yet been consumed would
break an in-progress join. Clearing them is a manual, deliberate action.

Not addressed: an approval for a joiner who never returns still sits forever.
A real expiry would have to live inside the signed envelope to be meaningful —
a timestamp outside it is attacker-editable and therefore worthless as a
control — which is a larger wire change than this finding justifies.

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

## F8 — A replayed vault container rolls the vault back after a restart (high) — FIXED

`VaultRepository` guarded container updates with a monotonic version:

```kotlin
if (message.version <= containerVersion) return@launch
```

`containerVersion` was `@Volatile private var containerVersion: Long = 0` - held
in memory only. Every process start reset it to zero, so the guard silently
stopped applying: the first container to arrive after a launch was accepted
whatever its version, and versions are wall-clock milliseconds, so **every**
previously published container qualifies.

Anyone able to subscribe can capture a container message. Replaying one after a
target device restarts overwrites that device's container wholesale, dropping
every vault document added since - with a signature that verifies, because it is
a genuine earlier message from a real member. The device may then publish the
rolled-back state onward.

The doc comment above the check claimed "a replayed message cannot roll the
family back", which was false in exactly the case that matters. Found by reading
the code back, not by a test: the whole subsystem was green.

Fix: the high-water mark is persisted in a sidecar beside the container and read
before the first comparison, with `-1` for "not yet loaded" so a genuine zero is
distinguishable from an unread one. Regression tests in `VaultContainerTest` pin
that it survives a restart, that an unreadable sidecar degrades to zero rather
than throwing, and that it does not change the container's fixed size.

## F9 — Vault chunks are stored unauthenticated and were unbounded (medium) — FIXED

`handleIncomingChunk` on the vault path deliberately does no authentication. That
is not an oversight: a device very likely holds no code that can read what is
arriving, and refusing to store what it cannot verify would mean vault documents
never left the phone that added them. Verification happens when someone opens the
vault, under the item's content key.

The cost of that decision was not paid. `totalChunks` arrives from the peer and
sizes both the blob and its bitmap; nothing capped it, nothing capped how many
distinct `fileId`s a device would take on, and nothing capped total bytes.
`MAX_ITEM_BYTES` was enforced only on the local add path. Any peer able to
publish could fill every family device's storage with data those devices can
neither read nor attribute.

Fix: `totalChunks` is bounded by `MAX_ITEM_CHUNKS`, derived from
`MAX_ITEM_BYTES`; a blob whose chunk count disagrees with what is already stored
is refused, since slot offsets are `chunkIndex * stride` and a changed count
reinterprets everything already written; and a new blob is accepted only within a
document-count cap, a total-bytes cap, and a free-space check.

**Not fixed, and inherent:** a member who knows a code can still add documents
that consume every other device's storage up to those caps. That is what "shared
by all the family" means here, and it is the same trust the file library already
extends.

## F10 — The vault container is a permanent offline target for guessing the code (medium) — INHERENT; MITIGATED 2026-08-18

The container is published retained and unencrypted on
`familysafe/group/{groupId}/vault/container`. It is encrypted at slot level, so
this leaks no contents - but it hands anyone who can subscribe a fixed, complete
artifact against which codes can be guessed **offline, without limit, forever**.
There is no rate limit to hit and no lockout to trigger, because there is
deliberately no server-side notion of a correct code.

Argon2id at libsodium's INTERACTIVE preset (64 MiB, 2 passes) is the only cost
per guess. That is a real cost and the right preset for a phone, but it is not
enough to protect a code a person picked casually. `MIN_CODE_LENGTH` is 6, which
is a floor against triviality, not a strength requirement - and the app cannot
enforce strength without admitting which codes are real.

This interacts with F2: while the broker credential is shared, "anyone who can
subscribe" is a much larger set than "the family". F2 was accepted on 2026-08-10
on the reasoning that the operator sees only metadata. **The vault changes what
is at stake in that decision** - it is no longer metadata, it is an offline
attack on the family's most sensitive documents. This does not by itself reopen
F2, but it is exactly the kind of change the F2 decision said should trigger a
re-read.

### Mitigation, 2026-08-18

The attack is inherent and remains. What was buildable was the input to it and
the user's understanding of it, and both are now built.

**The floor is a passphrase, not a word.** `MIN_CODE_LENGTH` 6 became
`MIN_PASSPHRASE_LENGTH` 16 — long enough that a single word, or a name and a
year, does not reach it, and short enough to admit a three-word phrase. Length
is a blunt instrument (`aaaaaaaaaaaaaaaa` passes) and it is the only one
available: rejecting a *weak* passphrase means judging a specific one, and the
app cannot judge one without admitting which ones are real.

This cost nothing to change because **no shipped build has ever had a vault** —
versionCode 28 predates Phase 6. Had one shipped, there would have been no
migration to write: a passphrase that is not stored cannot be re-asked for by a
device that has already forgotten it, so raising the floor after release would
have stranded every vault below it. The window for this change closes when
Phase 6 ships.

**The app now says what protects a vault**, in the only place it can. Not at the
entry point — that is the file search box, and a minimum length, a strength
meter or even the word "passphrase" there is a prompt in disguise, which is
exactly what the design refuses to show. Inside the vault the reader has already
submitted something, so it is not news to them:

- A footer on every vault screen: what was typed is the only thing protecting
  anything kept there, it is stored nowhere and can never be reset, anyone who
  guesses it sees the same view, and several unrelated words beat one.
- A sentence in the first-write dialog, which is the moment a passphrase stops
  being a guess and becomes the only way back — the last moment saying so can
  still change what someone picks.

Both are shown unconditionally, with no dismissal and no stored state. A note
that appears for some vaults and not others is an oracle, and a "seen it" flag
on disk is one more thing a used vault has that an empty one does not. The
wording never distinguishes a full vault from an empty one and never suggests
that what was typed might have been wrong.

**What is not fixed, and cannot be by this route:** the container is still
published retained, still a fixed artifact, still guessable offline without
limit by anyone who can subscribe. Argon2id at INTERACTIVE is still the only
cost per guess. A 16-character floor raises the cost of the cheapest attack; it
does not bound the best one, and a family that picks sixteen predictable
characters is no better off. The only structural fix is to stop handing the
artifact to everyone — see the F2 re-read below, which this change triggered.

## F11 — The vault was eligible for device-to-device transfer (low) — FIXED

`data_extraction_rules.xml` excluded identity keys, the SQLCipher store and the
external file library from both channels, but the vault lives in internal storage
under `familysafety_vault/` and matched no rule, so it was eligible for
device-to-device copy.

The container is opaque without a code, so this leaked nothing directly. It did
put a permanent offline guessing target (F10) onto a device that may not be in
the family and may not be the user's. A vault should follow the family, not the
hardware: a new device receives the container over the retained topic once it
joins.

Fix: excluded from both `cloud-backup` and `device-transfer`.

## F12 — The file manifest is not replay-protected (low)

`FileManifest` carries a `version` (epoch ms of last change) and nothing compares
it against anything. A signed manifest can therefore be replayed indefinitely by
anyone who captured one.

Impact is bounded by the receive logic rather than by design, which is worth
stating honestly. Deletion is sticky - there is no branch that un-deletes, and a
tombstone's `downloadState` is `COMPLETE`, so a replayed older manifest cannot
resurrect a deleted document. What a replay can do is revert `isEssential`,
changing which documents a device fetches automatically.

The one case that is not bounded: once a tombstone passes the 90-day retention
window and is purged, a replayed pre-deletion manifest re-lists the file and the
device fetches it again. That window is documented in `SharedFileDao`, but as
protection against a long-offline peer, not against a replay.

Not fixed. The fix is a stored high-water mark per group, the same shape as F8's.

## What Phase 5 closed

Worth recording, because it was never written up as a numbered finding: before
2026-08-16 the file manifest was accepted from anyone. It was encrypted but
unsigned, and `decodeManifest` fell back to parsing plaintext unconditionally.
Anyone able to publish to the broker could rewrite a family's entire file list,
including marking every document deleted. It is now signed with Ed25519 over the
ciphertext and verified against the local roster, unsigned manifests are refused,
and the plaintext fallback is refused once a group has a key. Documents are also
no longer stored in plaintext at rest.

## A claim in the code that was too strong

`VaultRepository.open` and its documentation state that a correct and an
incorrect code are indistinguishable by timing. The dominant costs - Argon2id,
then 256 GCM attempts - are identical either way, and no branch depends on the
outcome. But `openAll` groups and sorts whatever it found, so a full vault does
microseconds more work than an empty one.

That is not remotely measurable across a network, or plausibly through an app's
own UI, and it is not being treated as a finding. It is recorded because the
comment said "no timing difference" without qualification, and a security comment
that overstates its guarantee is how the next person stops checking.

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

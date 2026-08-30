# FamilySafety (Jibaro Family Safety) — Project Status

Snapshot date: 2026-08-29. Branch: `main`, HEAD `e0965c8`, **1.13.1 / versionCode 30**.
Rewritten in part from the 2026-08-18 snapshot: the F2 decision was made on the 21st,
**29 shipped through Play**, and diagnosing a real family's broken sync turned up a
transport defect that explains several symptoms previously filed as unrelated.

Supersedes the 2026-08-17 and 2026-08-13 snapshots. The file redesign is finished — all six
phases plus the vault are in `main` — and that work is now shipped rather than pending.

## Scope of this document

Built from `git log`, the repo's docs, the working tree, and this machine's Claude Code
memory. It does not include claude.ai web-chat history outside Claude Code.

Facts marked *(user-reported)* were done by the user and are not verifiable from the tree.
Everything else was observed directly — several claims in earlier snapshots turned out to be
wrong when checked, so the distinction is kept deliberately.

## What the app is, right now

Privacy-first, end-to-end-encrypted family location/chat/file app.
`applicationId jibaro.spacepirate.love`, **1.13.1 / versionCode 30** (29 is what the family is
running), targetSdk 36, minSdk 26.

- No accounts — BIP-39 mnemonic → SLIP-10 → Ed25519/X25519 identity.
- Location, chat, files, group membership and presence are all E2EE. An MQTT relay moves
  ciphertext between per-recipient inboxes; LAN-first direct routing (NSD + TCP) runs
  alongside it.
- `MessageProtocol.PROTOCOL_VERSION = 3`. Peers below it are named on the Family screen as
  "needs to update" rather than silently failing to appear.
- Encrypted local history (Room + SQLCipher, **schema v8**), replicated between family devices.
- Shared documents are stored encrypted at rest and never assembled in the clear.
- A shared family vault, opened by a code that is never stored anywhere.

**444 unit tests, 0 failures** at HEAD.

## Track 1 — Play: 29 shipped, family recreation still outstanding

26 was uploaded 2026-08-13, 28 followed with the member-list fixes, and **29 — Phase 5, the
vault, and the map bubble — went out through Play** *(user-reported)*. 30 is built and
waiting. Because 29 went through Play, both family devices run Google-signed builds: a
locally-built APK cannot be installed over them, and the only way past that is an uninstall,
which destroys identity keys and recovery phrases. Everything now reaches those phones
through Play or not at all.

**Still outstanding — the family recreation.** `GroupDefinition.fileEncryptionKey` is generated
in exactly one place — `GroupStateManager.createGroup` — and there is no backfill anywhere, so
a family created before 1.12.0 carries a null key permanently. Without it: shared documents
fall back to file key version 1, `SHA-256(groupId + "familysafety-files-v1")`, whose inputs are
both public; the manifest is published as **plaintext and unsigned**, since Phase 5's signing
only applies on the encrypted path; and presence sealing and the repair/holdings control
messages have no key either.

**The vault does *not* depend on it** — it salts Argon2id with the groupId, signs the container
with Ed25519 identity keys, and encrypts each document under its own random content key.
Nothing in `vault/` references `fileEncryptionKey`. An earlier draft of this document claimed
otherwise.

To switch the rest on: everyone updates → everyone leaves → **one person creates the family on
the current build** → re-invite → re-share the documents, which were encrypted under a key that
no longer exists.

Leaving destroys local group data *and identity keys*: new member IDs, new mnemonics, and
**old recovery phrases stop working**. Tell people before they leave.

Recreating also clears the forked group state described in Track 3.

`PLAY_STORE_CHECKLIST.md` remains stale — it lists all four launch blockers as outstanding and
claims targetSdk 35. Refresh it into a per-release checklist or delete it.

## Track 2 — The Pixel outage: closed

16 KB memory pages on Pixel 9/10 made 1.12.0 (18) and 1.12.4 (24) unable to create a family at
all. Fixed in `1e52602` by moving to lazysodium-android 5.2.0, `net.zetetic:sqlcipher-android`
4.9.0, ML Kit 17.3.0 and CameraX 1.4.2; arm64-v8a verified 16 KB-clean by reading ELF program
headers of every library in the bundle. Shipped in 26. Nothing outstanding.

Two traps recorded so they are not rediscovered:

- **Maven's search index lists lazysodium-android only to 5.1.0 (2022)**, making it look
  abandoned. 5.2.0 shipped May 2025 and is aligned — visible only in the repository listing.
- **`clean` is not sufficient.** A `clean assembleDebug` once produced an APK missing the
  entire `storage` package; it installed and crashed with `ClassNotFoundException` for a class
  Kotlin had compiled. Build release bundles with `--rerun-tasks`, **and verify the artifact
  rather than the build log** — the shipped AAB was checked by counting class definitions in
  its dex. The same check was run on the debug APK at this HEAD for the new `vault` package
  and everything Phase 5 touched: all present.

## Track 3 — Group state: forking fixed, one fork may still be live

Any member may approve a join, so two members approving at the same moment each produced a
state at the same version with different contents; the sync layer acknowledged the same-version
message and dropped it. Both sides then reported themselves in sync while holding different
rosters, permanently.

`90850ef` makes devices reconcile onto the smaller state hash and re-parent, ending at the
union, with append-only tombstones so merging cannot readmit someone just removed. `89c5f74`
stops rejecting updates from peers that predate tombstones, and `2d9bb9d` handles the harder
case: two branches that diverged and *then both moved on*.

**A fork already formed does not self-heal.** That device needs to leave and rejoin, which the
recreation in Track 1 handles.

## Track 3b — The 2026-08-29 diagnosis: it was the transport, not a fork

Two devices in one real family held different rosters for days: the creator's XCover at
**v5 with 5 members**, an S25 at **v2 with 2 members**, same group id. Both showed a
connected relay, a local route to each other, and healthy decrypts a minute old. It looked
exactly like the fork described above. It was not.

The evidence that settled it was one line of the creator's diagnostics —
`Sync: error: Failed to broadcast update` — a string set in exactly one place, when *zero*
of the peer sends succeeded. So the request path worked: the refresh request arrived and
the creator tried to answer. The broadcast itself failed, for everyone at once, while
locations and presence flowed normally.

The cause is in **Track 9** below. The reason it produced a permanent-looking divergence
rather than a transient one: group state is event-driven, so a missed update is never
re-sent, and every subsequent broadcast failed the same way.

Two useful diagnostic facts fell out of this, worth keeping:

- **`Sync: idle` on the device that is behind is itself evidence.** A group-sync message
  that arrives and is rejected leaves `conflict` or `error:`, because the version-jump
  branch sets `Conflict` *before* it applies. `idle` means nothing ever reached the
  handler.
- **A version gap does not block catch-up.** `handleGroupSyncMessage` handles
  `version > current + 1` explicitly and applies it; the `previousStateHash` chain check
  only governs a direct successor. So a device three versions behind can be repaired by
  one broadcast, and if it is not, the reason is delivery or validation, not the gap.

**Not yet confirmed on device.** The fix removes the failure we can see. Whether the S25
then *accepts* v5 is untested — its validation path has never run against the creator's
state. If it arrives and is rejected, that is the genuine-branch case and that device
needs to leave and rejoin.

**The ghost member.** The family roster carries a member called "Debug" whose keys no
longer exist — a debug-build identity whose app data was cleared without using Leave
Family, so no self-removal was ever broadcast. It cannot remove itself (that needs a
signature from a destroyed key) and removals are creator-or-self, so only the creator can
clear it. It is not cosmetic: it takes a slot in every per-recipient fan-out, and
`waitForAcknowledgments` waits for `memberCount - 1` acks with a 30-second timeout, so
every group broadcast on that device pays the full 30 seconds waiting for an ack that
cannot come.

## Track 4 — Transport and onboarding reliability: closed

- **MQTT self-eviction (`368bc9a`).** Four racing `initialize()` callers each built a client
  under the same deterministic client ID, so the broker evicted the app's own session in a
  loop; Paho reported "already connected" through `onFailure`, so the retry counted a healthy
  connection as a failure. Verified on device: before, disconnect every few seconds; after,
  one connect and zero disconnects over 45 s.
- **Onboarding write durability (`7fa0488`).** Identity keys, the initialized flag and the
  recovery phrase were written with `apply()` and then lost to `restartProcess()`. All
  identity-critical writes now `commit()`. `17ac806` adds a test that leaving the family
  actually erases the identity.

## Track 5 — Shared files and the vault: complete

The subsystem that started as a fire-and-forget broadcast pipe — a file was once observed
taking half a day to arrive, because it had *stopped* and finished only when someone tapped it
— is now finished. All phases of `C:\Users\omarj\.claude\plans\greedy-doodling-wombat.md` are
in `main`.

- **Phase 0 (`b19f5d7`)** — backup rules naming non-existent stores, a LAN oversized frame that
  killed the whole socket, filenames wrong for every Storage Access Framework URI, and removal
  of `fallbackToDestructiveMigration()` with schemas committed.
- **Phase 1 (`2267af5`)** — streaming I/O and a sparse blob store of wire-format chunk
  ciphertexts. Killed the `bytes.toList().chunked()` OOM; explicit per-index chunk accounting
  via a bitmap, treated as a cache over the blob so the DB and filesystem self-reconcile.
- **Phase 2 (`debaedb`)** — targeted repair: ask one peer for exactly the missing indices,
  replacing a whole-library re-broadcast. Durable retry schedule drained by WorkManager.
- **Phase 3 (`f1b819b`)** — `isEssential` pinning and per-peer availability, so the app can
  answer "this document is on three of four phones".
- **Phase 4 (`20d5193`, polished in `c5dcf52`)** — status chips, the status board and the
  transfer log.
- **Phase 5 (`218780e`)** — deletion that actually propagates, a signed manifest, and documents
  encrypted at rest. Details below.
- **Phase 6 (`4f3c1f0`)** — the family vault. Details below.

### Phase 5, in more detail

Three things were wrong and are now not:

- **Deletion never left the device.** The manifest was built from a query filtering
  `isDeleted = 0`, so tombstones were dropped on the way out while the dialog promised "delete
  for everyone in the family". The receive-side branch had existed since the feature shipped
  and no sender ever reached it. Tombstones now ride the manifest for 90 days.
- **Any manifest was accepted from anyone** — encrypted but unsigned, with an unconditional
  plaintext fallback. Now signed with Ed25519 over the ciphertext, verified against our own
  roster; unsigned is refused and the plaintext fallback is refused once the group has a key.
- **Documents sat in plaintext on external storage** beside a SQLCipher-encrypted database
  describing them. The encrypted blob is now the stored form; opening decrypts into a private
  cache cleared on launch. A resumable startup pass migrates older plaintext copies one file
  at a time, deleting the plaintext only after the encrypted copy verifies.

**File key version 3** — the group key with purpose separation — is readable but deliberately
**not written**. 1.12.10 maps an unrecognised version to the legacy key, so publishing v3 now
would make new files undecryptable to peers that have not updated. *This is the one piece of
deferred work in the file subsystem: flip the writer once every device is past 28.*

Rollout note: **peers on 1.12.10 publish unsigned manifests and will stop updating a newer
device's file list until they update.** That is the intended trade — the Family screen already
names them.

### Phase 6, the vault, in more detail

A 128 KiB container of 256 fixed-size slots, created full of `SecureRandom` bytes on first run
on every device whether or not anyone ever sets a code, with no header or version byte. Opening
means attempting AES-GCM on every slot and keeping what authenticates, so **every code is
valid**: the family code opens the real slots, a decoy code opens the decoy slots, anything
else opens an empty vault. There is no error path and no timing difference. Codes are never
stored; a code is input to Argon2id (libsodium `crypto_pwhash`, INTERACTIVE limits, salted with
the group id) and nothing else.

Document bytes live under random ids encrypted with per-item keys that exist only inside a
slot, synced on their own chunk path — deliberately not as shared files, since a `SharedFile`
row would list them in the manifest that every member can read.

Deletes are recoverable by decision: anyone with the code can delete anything, which no code
can prevent with a shared key, so removal flips a slot's state and keeps the bytes.

Four deviations from the plan, all recorded in the plan file: the container gets its own
retained topic; document bytes get their own chunk path; slots are 256×512 rather than 64×256
with two replicas per item; and entry is the search box's submit action. The third exists
because **allocation is blind** — no device can tell filler from another vault's slot, so
writing to one vault can overwrite another's. That is inherent: what makes the decoy credible
is what makes allocation blind. Replication turns it from data loss into loss of redundancy.

### The landmine went off — see Track 9

This section used to read: "Paho's `maxInflight` is never set (default 10) and `publishRaw`
treats any publish exception as connection loss… anything that speeds up publishing needs
to revisit this first." Nothing sped up publishing. It went off anyway, in an ordinary
five-member family, and cost a real household its group sync for days. Fixed in `fdd190b`.

## Track 6 — Security review

`SECURITY_REVIEW.md` is current for the relay-exposure findings. F1, F4, F5, F6, F7 fixed; F3
partially fixed (online/offline sealed, member IDs and timing remain); **F2 accepted risk** —
the broker operator was ruled out of the threat model on 2026-08-10, with the conditions that
would reopen it recorded. Do not "fix" F2 without revisiting that decision.

A second pass on 2026-08-17 covers Phases 5 and 6 as **F8–F12**. Three defects introduced by
that work were found by reading it back rather than by any test — the subsystem was green
throughout — and all three are fixed:

- **F8 (high)**: the vault container's monotonic-version guard lived in memory only, so it
  reset to zero on every launch and stopped applying. Replaying a captured older container
  after a restart rolled the vault back, with a signature that verifies because it is a
  genuine earlier message. Now persisted, with regression tests.
- **F9 (medium)**: vault chunks are stored without authentication by design, but nothing
  bounded them — any peer could fill every family device's storage. Now capped by chunk
  count, document count, total bytes and free space.
- **F11 (low)**: the vault directory matched no backup rule and was eligible for
  device-to-device transfer, putting an offline guessing target on a phone that may not be in
  the family. Now excluded from both channels.

**F10 (medium, inherent) — mitigated 2026-08-18.** The retained container is a permanent
offline target for guessing the code, with Argon2id INTERACTIVE as the only cost per guess.
The 6-character minimum was a floor against triviality rather than a strength requirement; it
is now 16, which a single word does not reach. That change was free only because **no shipped
build has ever carried a vault** — 28 predates Phase 6 — and it could not have been made after
release, since a passphrase that is nothing but a key-derivation input cannot be re-asked for
by a device that has already forgotten it. **The window closes when Phase 6 ships.** The vault
also now states what protects it: a footer on every vault screen and a sentence in the
first-write dialog, both shown unconditionally, because a note that appears for some vaults and
not others is an oracle. Nothing is said at the entry point — that is the file search box, and
a hint there is a prompt in disguise.

The attack itself is untouched and cannot be fixed in the app. The only structural fix is to
restrict who can fetch the retained container, which needs per-topic ACLs, which needs F2.

**The F2 re-read is done and recorded** in `SECURITY_REVIEW.md`, and changed no decision by
itself. What it found: none of the three named reopening conditions has occurred, but two of
F2's four reasoning bullets have acquired exceptions. "The broker credential is no longer a
security boundary" is true of every message except the vault container, which is the one object
encrypted under something a person chose rather than a 256-bit secret. And "ACLs have no threat
to answer" is no longer true — the vault is the first thing on the broker giving them one that
is neither metadata nor integrity.

**Decided 2026-08-21: accepted.** F2 stays accepted with the vault's consequence explicitly in
scope — the family's vault passphrase can be guessed offline, without limit, by anyone holding
the shared broker credential, and Phase 6 ships on that. The exposure did not shrink; the trade
is now a knowing one. The 2026-08-10 reopening conditions still stand, plus one added by this
decision: a family keeping something in the vault whose disclosure would matter more than the
cost of recreating the group on per-device credentials. **This no longer gates the release.**

**F12 (low)** is filed and not fixed: the file manifest has a version field that nothing
compares, so a signed manifest can be replayed; impact is bounded because deletion is sticky,
except past the 90-day tombstone window.

Still open from the June audit: `androidx.security:security-crypto` remains at
`1.1.0-alpha06` (`app/build.gradle.kts`). 8 stale retained `join_approval` messages remain on
the broker from before `c0dbe6c`; deliberately untouched, since deleting one mid-join breaks
that join.

## Track 7 — iOS port

`ios/IOS_PORT_SPEC.md` is at **1.10** and current with the wire format as of `4f3c1f0`,
including the previously undocumented Phase 2–4 additions and the vault (§6.8). The port phase
plan gained a Phase 6.5 for the vault, which is worth its own session because every assertion
in it is about something *not* being observable.

Phase 0 of the port was scaffolded on Windows, which has no Swift toolchain. CI exists
(`.github/workflows/ios-swift-tests.yml`) but **whether it is green is still unconfirmed** —
`gh` is not authenticated on this machine. Unchanged for four snapshots now; it stays the
correct next step for that track. Phases 1–7 not started.

## Track 8 — UI

`UI_REDESIGN_PLAN.md`, `UI_SCREEN_BEFORE_AFTER.md` and `SESSION_CONTEXT.md` were untracked
local files and are gone; they were never in git and cannot be recovered from it. Treat the
redesign as **undocumented rather than done**.

New since the last snapshot: the Files screen has a search box (which is also the vault's
entry point), a status board, per-file status chips and availability counts.

### Map: overlapping pins, and the four questions left open

People standing close enough to overlap are now drawn as one bubble of small faces with a
count, instead of as pins hiding each other — grouped by pixel distance at the current
zoom, so it is a fact about the screen rather than about the ground. Tapping a bubble opens
a card that names them. Committed at `38d3d3f`; the arithmetic has 15 tests, and the
drawing was checked on device with `MarkerRenderHarness`, which writes a sheet of every
marker state to `getExternalFilesDir` for a person to look at.

**Refined 2026-08-29 (`22187fd`), after seeing it rendered on a phone.** The bubble was too
wide — three faces in a row with gaps is a banner, not a pin. Closed, the faces overlap by a
third and each gets a white collar so two accent rings do not read as one smeared shape;
tapping spreads them apart, so the tap changes the marker and not only the card. Faces draw
right to left so the leftmost is on top, which is where the asked-for member sits. The tail
was two subpaths, and a stroke outlines each one separately — so a line was drawn across the
top of it and the point read as something hanging off the bubble; it is unioned with
`Path.op` now, the triangle starting well inside the capsule since shapes that merely touch
can still seam, and widened to suit the shorter capsule. The card is half-opaque. The whole
thing is explained once, on the first bubble a device ever draws, with the flag in the same
preference file the tips use.

**Item 1 below was fixed at the same time**, because it would otherwise have shipped: the
card is bottom-centre at ≤320 dp and the two map buttons are pinned to the same 80 dp on both
edges, clearing each other only above 432 dp of width — no phone in use. The map controls now
stand down while a group card is open.

**Still tabled:**

1. **Tapping the map does not dismiss the card.** Only the X, picking a name, or the group
   dissolving closes it. `LongPressOverlay` already handles touches, so a single-tap
   dismissal belongs there.
2. **What tapping a name should do.** Today it asks for a drive estimate — the same thing
   tapping that person's pin did — and closes. Alternatives were panning to them, or
   zooming until the group splits.
3. **Zoom-to-separate as a card action.** `clusterPins` can say exactly which zoom level
   breaks a group apart, so "show them apart" is cheap and deterministic. Open question is
   whether it earns its place, since the card already answers "who is here".

Deferred deliberately: anchoring the card to the bubble instead of the bottom of the
screen. It reads better and costs re-projection on every pan plus edge handling, and
Life360 — the app this was modelled on — uses a bottom sheet anyway.

## Track 9 — Transport: a full in-flight window read as a dead connection

Fixed in `fdd190b`. The defect that produced Track 3b, and the explanation for the
intermittent "disconnected" status on the busiest device that nothing in the network could
account for.

Every message the app sends is QoS 1 — 51 call sites, none at QoS 0 — and every one is
published per recipient, so one event becomes N publishes and a file share becomes
hundreds. Paho's in-flight window defaults to 10 and was never set. At the ceiling, a
publish throws immediately; the socket is fine.

`publishRaw` read that as connection loss and set the state to `Disconnected`. Its own
first act is to bail out when the state is not `Connected`, so **every later recipient in
the same loop returned false without an attempt** — one refused publish became all of them.
Then `scheduleReconnect` incremented the backoff, the reconnect found the client still
connected, logged "already connected" and restored the state — without resetting
`reconnectAttempts`, which only resets on a real connect. So the phantom outages grew
geometrically toward the five-minute cap, appearing and healing with nothing behind them.

Five changes:

- Publish failures ask `mqttClient.isConnected` rather than assuming. A failure on a live
  connection is backpressure: queue it and carry on.
- `maxInflight` is set, but **only to 32**. The low ceiling was never the defect, and the
  client uses `MemoryPersistence`, so unacknowledged messages are held in RAM — at 32 KB
  chunks a generous window is megabytes on a phone. The old ceiling was also doing
  accidental backpressure that the 15 ms chunk pacing compensates for.
- **Two offline queues.** One queue drops by age, so an outage full of location updates
  evicts the group-state message that must not be lost. Control-plane topics — group sync,
  sync requests, join requests and approvals, group acks — now have their own bounded queue,
  drained first. Chat and files stay on the bulk side deliberately: both are replicated and
  backfilled, so a dropped one is recoverable and a missed roster change is not.
- **Locations and presence drop to QoS 0.** A position supersedes itself and the replay
  guard discards anything older than what is held, so at-least-once was paying three times
  over — an in-flight slot per recipient, a broker session queue per offline peer, and a
  burst on their reconnect that gets thrown away on arrival. Presence is retained, which
  the broker keeps regardless of QoS.
- A broadcast nobody could receive is `SyncState.Deferred`, not `Error`. It is queued on
  the control path and drains on reconnect; calling it a failure put red text on screen for
  an ordinary offline moment.

### What this does not fix, and the real scaling ceiling

QoS tuning does not change the shape of the problem:

- **Group state is O(N²).** Every sync carries the entire group definition, per recipient,
  and every member rebroadcasts during reconciliation.
- **File sharing is the ceiling.** A 10 MB file is 320 chunks pushed to *every* peer — at
  five members roughly 1,280 publishes and ~57 MB upstream for one share. Phase 2 built the
  right primitive (targeted repair: ask one peer for specific indices); the initial share is
  still push-to-everyone. Turning that into a pull matters far more for scale than anything
  in the list above.

Also fixed on the way: `GroupStateManager.recordLocationUpdate` had **zero callers**, so the
diagnostics report's "last location" line read `never` for every member on every device
whether locations were flowing or not. It is stamped now, after the replay guard so a replay
cannot make a member look present. Two hours were spent treating that output as evidence
before the dead field was noticed — the same shape as the `RemovedFromGroup` event that once
had no collectors.

## Housekeeping

- **Untracked**: `AGENTS.md` (a Codex-facing near-duplicate of `CLAUDE.md`),
  `.claude/settings.json`, `FamiliySafetyIcon.png` (note the typo), and `.idea/` noise that
  belongs in `.gitignore`.
- `.claude/settings.local.json` is tracked but carries personal overrides, and has uncommitted
  local modifications.
- Local branches `ui-refactor` and `ui-checkpoint-current` are merged history;
  `origin/claude/family-safety-invite-bug-lsvy7p` is a leftover remote branch.

## Deliberately parked

Chat message length is unpadded (F6 covered presence and location; chat was excluded by
decision). AES-GCM is now implemented in **three** places — `crypto/GroupCipher.kt`,
`files/SharedFileRepository.kt` and `vault/VaultSlots.kt`. They agree today, which is exactly
why the duplication is worth removing before they stop agreeing.

## Suggested next actions, in order

1. **Ship 30 and confirm the sync repair on the real family.** The bundle is built and
   verified; `RELEASE_NOTES.md` carries the copy. Then, on the device that is behind:
   Security Dashboard → Check Family List, and re-read the diagnostics. `v5` means Track 3b
   is closed. `conflict`/`error:` means the update now arrives and is *rejected*, which is
   the genuine-branch case and that device must leave and rejoin. Still `idle` means the
   diagnosis was incomplete.
2. **Have the creator remove the "Debug" ghost member** (Track 3b). It costs a slot in every
   fan-out and 30 seconds of ack timeout on every group broadcast.
3. ~~Cut a release with Phases 5 and 6.~~ **Done — 29 shipped through Play.** Which means
   the passphrase floor is now permanent for any device where someone has already written to
   a vault, and the accepted F2 exposure is live rather than theoretical.
4. **Run the family recreation** (Track 1). Everything shipped since 1.12.0 — file-name
   privacy, presence sealing, at-rest keys, the vault — does nothing for a family created
   before it. Note this family's document key is **present**, so it is *not* one of those;
   confirm before putting anyone through it.
5. Check the iOS CI run (needs `gh auth login`); if green, Phase 0 is done and Phase 1 is next.
6. Flip the file key writer to version 3 once every device is past 28.
7. **Make the initial file share a pull rather than a push** — the O(N) fan-out per chunk is
   the real scaling ceiling (Track 9), and the targeted-repair primitive it needs already
   exists from Phase 2.
8. Refresh or delete `PLAY_STORE_CHECKLIST.md`; `.gitignore` for `.idea/`; decide whether
   `AGENTS.md` and this file belong in git.

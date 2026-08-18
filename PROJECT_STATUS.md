# FamilySafety (Jibaro Family Safety) — Project Status

Snapshot date: 2026-08-17. Branch: `main`, level with `origin/main`, HEAD `92e6d9f` plus the
security pass committed alongside this update.

Supersedes the 2026-08-13 snapshot, which was written when Phase 0 of the file redesign was
the newest thing in the tree. Sixteen commits since. **The file redesign is finished — all
six phases plus the vault are in `main`** — and versionCode 28 is uploaded to Play.

## Scope of this document

Built from `git log`, the repo's docs, the working tree, and this machine's Claude Code
memory. It does not include claude.ai web-chat history outside Claude Code.

Facts marked *(user-reported)* were done by the user and are not verifiable from the tree.
Everything else was observed directly — several claims in earlier snapshots turned out to be
wrong when checked, so the distinction is kept deliberately.

## What the app is, right now

Privacy-first, end-to-end-encrypted family location/chat/file app.
`applicationId jibaro.spacepirate.love`, **1.12.10 / versionCode 28**, targetSdk 36, minSdk 26.

- No accounts — BIP-39 mnemonic → SLIP-10 → Ed25519/X25519 identity.
- Location, chat, files, group membership and presence are all E2EE. An MQTT relay moves
  ciphertext between per-recipient inboxes; LAN-first direct routing (NSD + TCP) runs
  alongside it.
- `MessageProtocol.PROTOCOL_VERSION = 3`. Peers below it are named on the Family screen as
  "needs to update" rather than silently failing to appear.
- Encrypted local history (Room + SQLCipher, **schema v8**), replicated between family devices.
- Shared documents are stored encrypted at rest and never assembled in the clear.
- A shared family vault, opened by a code that is never stored anywhere.

**422 unit tests, 0 failures** at HEAD.

## Track 1 — Play: 28 shipped, family recreation still outstanding

versionCode 26 was uploaded 2026-08-13 and **28 is now the shipped build** *(user-reported)*.
28 carries the member-list fixes from Track 3.

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

### Known landmine, still unaddressed

Paho's `maxInflight` is never set (default 10) and `publishRaw` treats any publish exception as
connection loss. The publish loop is paced at 15 ms per chunk, which is what currently keeps a
large upload from knocking the app off its own broker. **`maxInflight` itself is still not
raised.** Anything that speeds up publishing needs to revisit this first.

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

Two are filed and not fixed. **F10 (medium, inherent)**: the retained container is a permanent
offline target for guessing the code, with Argon2id INTERACTIVE as the only cost per guess and
a 6-character minimum that is a floor against triviality rather than a strength requirement.
It also raises what is at stake in the F2 decision — no longer metadata, but the family's most
sensitive documents — which is exactly the kind of change that decision said should trigger a
re-read. **F12 (low)**: the file manifest has a version field that nothing compares, so a
signed manifest can be replayed; impact is bounded because deletion is sticky, except past the
90-day tombstone window.

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

1. **Run the family recreation** (Track 1). Everything shipped in the last three releases —
   file-name privacy, presence sealing, at-rest keys, the vault — does nothing for a family
   created before 1.12.0 until someone recreates the group. It also clears the live fork.
2. **Cut a release with Phases 5 and 6.** Build the bundle with `--rerun-tasks` and verify the
   dex; the release notes need to say plainly that everyone must update, because unsigned
   manifests from older peers are refused.
3. Decide on F10: the vault should ask for a passphrase rather than a word, and say plainly
   that its strength is the only thing protecting the contents. Re-read the F2 decision while
   doing it — the vault changed what is at stake in it.
4. Check the iOS CI run (needs `gh auth login`); if green, Phase 0 is done and Phase 1 is next.
5. Flip the file key writer to version 3 once every device is past 28.
6. Raise Paho's `maxInflight` before anything else touches transfer throughput.
7. Refresh or delete `PLAY_STORE_CHECKLIST.md`; `.gitignore` for `.idea/`; decide whether
   `AGENTS.md` and this file belong in git.

# FamilySafety (Jibaro Family Safety) — Project Status

Snapshot date: 2026-08-13. Branch: `main` (level with `origin/main`, HEAD `b19f5d7`).

Supersedes the 2026-08-12 snapshot. Seven commits since, and the situation changed
materially: **versionCode 26 is uploaded to Play**, closing a gap where the shipped build was
unusable on current Pixel hardware.

## Scope of this document

Built from `git log`, the repo's docs, the working tree, and this machine's Claude Code
memory. It does not include claude.ai web-chat history outside Claude Code.

Facts marked *(user-reported)* were done by the user and are not verifiable from the tree.
Everything else was observed directly — several claims in earlier snapshots turned out to be
wrong when checked, so the distinction is kept deliberately.

## What the app is, right now

Privacy-first, end-to-end-encrypted family location/chat/file app.
`applicationId jibaro.spacepirate.love`, **1.12.8 / versionCode 26**, targetSdk 36, minSdk 26.

- No accounts — BIP-39 mnemonic → SLIP-10 → Ed25519/X25519 identity.
- Location, chat, files, group membership and presence are all E2EE. An MQTT relay moves
  ciphertext between per-recipient inboxes; LAN-first direct routing (NSD + TCP) runs
  alongside it.
- `MessageProtocol.PROTOCOL_VERSION = 3`. Peers below it are named on the Family screen as
  "needs to update" rather than silently failing to appear.
- Encrypted local history (Room + SQLCipher), replicated between family devices.

## Track 1 — Play: 26 shipped, rollout outstanding

**versionCode 26 uploaded 2026-08-13** *(user-reported)*. Before that Play served 1.12.0 (18),
with 24 uploaded but not rolled out.

This mattered more than a normal release: **24 and 18 cannot create a family on a Pixel 9/10**
(see Track 2). Anyone on recent Pixel hardware was locked out at the point of naming their
family, with no error — the app died and relaunched at the welcome screen.

**Still outstanding — the family recreation.** The file-content, file-name and presence
protections all depend on a group key generated when a family is *created*. Families made
before 1.12.0 carry no key and silently fall back to old behaviour. To switch them on:
everyone updates to 26 → everyone leaves → **one person creates the family on 26** →
re-invite. Doing this on an older build produces no key and achieves nothing.

Leaving destroys local group data *and identity keys*: new member IDs, new mnemonics, and
**old recovery phrases stop working**. Tell people before they leave.

Recreating also clears the forked group state described in Track 3.

`PLAY_STORE_CHECKLIST.md` remains stale — it lists all four launch blockers as outstanding and
claims targetSdk 35. Refresh it into a per-release checklist or delete it.

## Track 2 — The Pixel outage: 16 KB memory pages

Reported as "entering the family name sends me back to the start screen, every time" on a
Pixel 10a running Android 17.

Pixel 10 devices use **16 KB memory pages**; the linker refuses to load a native library
aligned for 4 KB pages. Four of six shipped libraries were: libsodium, libsqlcipher, ML Kit's
barcode scanner, and a CameraX helper. Nothing in onboarding touches native code until
"Create Family" is pressed — BIP-39 is pure JVM — so the failure landed exactly there. It threw
`UnsatisfiedLinkError`, an `Error` rather than an `Exception`, so the surrounding `catch`
blocks never saw it.

Fixed in `1e52602` by moving to lazysodium-android **5.2.0**, `net.zetetic:sqlcipher-android`
**4.9.0**, ML Kit 17.3.0 and CameraX 1.4.2. **arm64-v8a verified 16 KB-clean end to end** by
reading ELF program headers of every library in the bundle.

Two traps recorded so they are not rediscovered:

- **Maven's search index lists lazysodium-android only to 5.1.0 (2022)**, making it look
  abandoned. 5.2.0 shipped May 2025 and is aligned — visible only in the repository listing.
- **`clean` is not sufficient.** A `clean assembleDebug` produced an APK missing the entire
  `storage` package; it installed and crashed with `ClassNotFoundException` for a class Kotlin
  had compiled. Only `--rerun-tasks` produced a correct artifact. **Build release bundles with
  `--rerun-tasks` and verify the dex** — the shipped AAB was checked directly (162 storage
  class definitions present) rather than inferred from a successful build.

Also relevant: 16 KB support is a Play requirement for apps targeting Android 15+, and this
targets 36.

## Track 3 — Group state: forking fixed, one fork still live

Any member may approve a join, so two members approving at the same moment each produced a
state at the same version with different contents. The sync layer acknowledged a same-version
message and dropped it — correct when the states agree, silently wrong when they differ. Both
sides then reported themselves in sync while holding different rosters, permanently.

Observed in the four-member family the first day two devices ran together: one device sat at
v5 with three members while the rest ran v6 with four, receiving the fourth member's traffic
and discarding it as an unknown sender.

`90850ef` makes devices reconcile: both independently pick the same state to build on (the
smaller state hash — already deterministic across devices, so no coordinator is needed) and
the other re-parents onto it, ending at the union. Removals now leave **append-only tombstones**
covered by the state hash, because merging rosters would otherwise readmit anyone just removed.

**The existing fork does not self-heal** — the new code prevents new forks, it cannot repair one
already formed. That device needs to leave and rejoin, which the recreation in Track 1 handles.

## Track 4 — Transport and onboarding reliability

- **MQTT self-eviction (`368bc9a`).** `initialize()` is called from four places that race at
  startup; the only guard was "skip if already connected", which three concurrent callers pass
  while the state still reads *connecting*. Each built a new client under the same deterministic
  client ID, and MQTT requires the broker to evict the older session — so the app killed its own
  connection in a loop. Paho compounded it by reporting "already connected" through `onFailure`,
  so the retry counted a healthy connection as a failure and scheduled a reconnect that tore it
  down. Verified on device: before, disconnect every few seconds; after, one connect and zero
  disconnects over 45 s.
- **Onboarding write durability (`7fa0488`).** Identity keys, the initialized flag and the
  recovery phrase were written with `apply()`, then the process was hard-killed by
  `restartProcess()`, which never flushes it. `saveOnboardingComplete` already used `commit()`,
  and that asymmetry produced a relaunch where the flag was set but the keys were missing —
  back to the welcome screen. The severe version is the lost write taking the **mnemonic** with
  it, after the user was shown it and asked to confirm. All identity-critical writes now
  `commit()`, and key init moved off the main thread.

## Track 5 — Shared files: planned, Phase 0 landed

A file was observed taking **half a day to sync**. It was not syncing slowly — it was stopped.
Chunks are broadcast once with no gap detection and no retry; the only recovery is a user
tapping an undownloaded file, and that recovery re-broadcasts *every chunk of every file* to
the whole group.

A full redesign is planned at `C:\Users\omarj\.claude\plans\greedy-doodling-wombat.md`, scoped
to the stated purpose — **emergency and safety documents**, where the property that matters is
"is this on my family's phones, intact, and can I prove it?" Phases: streaming I/O and a sparse
blob store (never OOM, explicit chunk accounting) → targeted repair and a durable outbox →
essential pinning and availability tracking → status board with chips and a transfer log →
signed manifests, working deletion and at-rest encryption → a shared family vault with decoy
access.

Confirmed defects documented there, several serious:

- **Will OOM on a large file** — `bytes.toList().chunked()` costs ~8–9× transient on top of
  holding the whole file in RAM several times.
- **Deletion never propagates.** The manifest is built from a query filtering `isDeleted = 0`,
  so tombstones never leave the device, while the dialog says "Delete for everyone in the
  family?" For medical records and IDs that is a privacy failure, not a bug.
- **Any manifest is accepted from anyone** — unsigned, with an unconditional plaintext fallback.
- **Documents are stored in plaintext** while the database describing them is encrypted.

**Phase 0 landed (`b19f5d7`)** — independent safety fixes, no transfer behaviour change:
backup rules that named non-existent stores (plus a `device-transfer` section that was missing
entirely, which `allowBackup` does not govern); a LAN oversized frame that closed the whole
socket and discarded every later message on it; filenames taken from `uri.lastPathSegment`,
wrong for every Storage Access Framework URI; and removal of `fallbackToDestructiveMigration()`
with `exportSchema` enabled and `app/schemas/3.json` committed, so the coming v3→v4 migration
can be tested rather than verified by shipping.

**Known landmine for Phase 1:** Paho's `maxInflight` is never set (default 10) and `publishRaw`
treats any publish exception as connection loss. Today's byte-boxing is accidentally slow
enough to mask it; streaming will unmask it and start disconnecting the client mid-upload.

## Track 6 — Relay-exposure security review

`SECURITY_REVIEW.md` is current. F1, F4, F5, F6, F7 fixed; F3 partially fixed (online/offline
sealed, member IDs and timing remain); **F2 accepted risk** — the broker operator was ruled out
of the threat model on 2026-08-10, with the conditions that would reopen it recorded. Do not
"fix" F2 without revisiting that decision.

Still open from the June audit: `androidx.security:security-crypto` remains at
`1.1.0-alpha06` (`app/build.gradle.kts`). 8 stale retained `join_approval` messages remain on
the broker from before `c0dbe6c`; deliberately untouched, since deleting one mid-join breaks
that join.

## Track 7 — iOS port

`ios/IOS_PORT_SPEC.md` is at **1.8** and current with the wire format. Its header still says
"revised against 1.12.7 (25)" — cosmetic, since 26 changed no wire format. Worth documenting
the new LAN oversized-frame behaviour (skip, do not close) when next touched.

Phase 0 of the port was scaffolded on Windows, which has no Swift toolchain. CI exists
(`.github/workflows/ios-swift-tests.yml`) but **whether it is green is still unconfirmed** —
`gh` is not authenticated on this machine. Unchanged for three snapshots now; it stays the
correct next step for that track. Phases 1–7 not started.

## Track 8 — UI

`UI_REDESIGN_PLAN.md`, `UI_SCREEN_BEFORE_AFTER.md` and `SESSION_CONTEXT.md` were untracked
local files and are gone; they were never in git and cannot be recovered from it. Treat the
redesign as **undocumented rather than done** — reconstructing the old plan from memory would
invent a baseline.

## Housekeeping

- **Untracked**: `AGENTS.md` (a Codex-facing near-duplicate of `CLAUDE.md`), this file,
  `.claude/settings.json`, `FamiliySafetyIcon.png` (note the typo), and `.idea/` noise that
  belongs in `.gitignore`.
- Local branches `ui-refactor` and `ui-checkpoint-current` are merged history;
  `origin/claude/family-safety-invite-bug-lsvy7p` is a leftover remote branch.
- `.claude/settings.local.json` is tracked but carries personal overrides.

## Deliberately parked

Chat message length is unpadded (F6 covered presence and location; chat was excluded by
decision). AES-GCM is implemented twice — `crypto/GroupCipher.kt` and inside
`files/SharedFileRepository.kt`. They agree today, which is exactly why the duplication is
worth removing before they stop agreeing.

## Suggested next actions, in order

1. **Run the family recreation on 26** (Track 1). The shipped protections do nothing for
   existing families until someone recreates the group, and it also clears the live fork.
2. **Phase 1 of the file redesign** — the largest remaining piece, with a storage migration over
   real user data. Start it fresh, not at the end of a long session.
3. Check the iOS CI run (needs `gh auth login`); if green, Phase 0 is done and Phase 1 is next.
4. Refresh or delete `PLAY_STORE_CHECKLIST.md`.
5. Replace `security-crypto:1.1.0-alpha06` — the last open item from the June audit.
6. `.gitignore` for `.idea/`; decide whether `AGENTS.md` and this file belong in git.

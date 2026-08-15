# Release notes — 1.12.9 (versionCode 27)

Play currently serves **1.12.8 (26)**. Everything here is new since it.

Almost all of this is the shared-file subsystem, rebuilt around one question it could not
previously answer: *is this document actually on my family's phones?*

## Play Console "What's new"

```
Shared files, rebuilt.

• Files no longer get stuck — if a piece goes missing it is fetched again automatically, in the background
• Large files no longer risk crashing the app while sharing
• A new File status screen shows every file, how many devices have it, and what is still arriving
• Mark a file as pinned and it is kept on every phone; everything else downloads when you open it
• Fixed unnamed "?" pins appearing on the map
```

## Read this before shipping

**Everyone is jumping four database upgrades at once.** Version 26 shipped database
version 3; this build is version 7. Each step was verified on a device individually, but the
**3 → 7 path in a single launch has not been tested end to end**. There is deliberately no
destructive fallback any more — a failed migration now fails loudly instead of silently
wiping chat and location history — so a mistake here is a crash on launch, not silent data
loss. Test an upgrade from 26 before rolling this out widely.

**Two devices have still never exchanged a file through the new code.** Everything below is
verified by unit tests, by inspecting the built bundle, and by running on one phone. The
repair path, the availability announcements and the pinning behaviour have never had a
second device to talk to.

## Shared files

- **Nothing gets stuck any more.** Chunks used to be broadcast once with no record of what
  arrived; a single lost piece stranded a file indefinitely, and the only recovery was a user
  tapping it — which is why one file took half a day. The app now tracks exactly which pieces
  it holds, asks one specific peer for the ones it is missing, and keeps retrying in the
  background across restarts.
- **Large files will not exhaust memory.** Sharing used to load the whole file into memory
  several times over. Everything now streams in fixed buffers.
- **Partly-downloaded files are no longer stored unencrypted.** Pieces in transit used to sit
  on disk in the clear; they are now kept exactly as they arrived over the network, still
  encrypted.
- **Pinning.** A pinned file is kept on every phone automatically — the point of the feature,
  for documents like an insurance card that must be there when there is no signal. Everything
  else is listed and downloads when opened, so a large file is not pushed onto every device.
  Existing files are all treated as pinned, so nothing stops replicating.
- **File status screen.** Every file, its state, how many devices hold it, and a transfer log
  recording what was tried and what came back. Reached from the Files screen.
- **Corrupted downloads recover.** A file that failed its integrity check used to be stuck
  permanently, leaving its partial data behind. It is now fetched again.
- **Repair costs a fraction of what it did.** Asking for missing pieces used to make a peer
  re-send every chunk of every file it had, to the whole family.

## Fixes

- **"?" pins on the map.** Location history is kept for 30 days and outlives group membership,
  and the map drew a marker for every member ID it had ever seen — including people who had
  left and members missing from a device's roster. Those appeared as anonymous pins at stale
  positions. The map now shows only current members. **Location history itself is untouched.**
- **Leaving the family now verifies it erased this device's identity.** The wipe was
  best-effort inside a silent catch while the "you have left" state was set unconditionally, so
  the two could disagree. Found on a real device: after leaving, the signing keys and the
  BIP-39 recovery phrase were still in storage while the app showed the welcome screen. It now
  confirms the wipe and deletes the storage outright if the normal path fails.
- **Shared file names.** Files picked from the system picker were saved under an internal
  document ID rather than their real name. New uploads are correct; files shared before this
  keep the old name.
- **A single oversized message on Wi-Fi no longer kills the connection**, which used to
  discard every later message to that device until it reconnected.

## Compatibility

`PROTOCOL_VERSION` stays at **3**. The new topics are additive — older peers simply do not
take part in repair or availability reporting — so this deliberately does not mark every
current device as needing an update.

Because peers on 26 do not report what they hold, copy counts read as unknown until everyone
updates. The app says so rather than claiming a file exists nowhere else.

## Still outstanding

- Deleting a file still only deletes it on your own phone, despite the dialog saying
  "for everyone in the family". The fix is the next piece of work.
- The manifest is not yet signed.
- Completed files are still stored unencrypted at rest (pieces in transit now are not).
- The family recreation described in the 1.12.6 notes is still required to switch on the
  file, file-name and presence protections for families created before 1.12.0.

## Verification

370 unit tests, 0 failures. Bundle inspected directly rather than assumed: every arm64
library 16 KB-aligned, and the new code present in the dex — a plain build has produced an
APK missing an entire package in this project before, so **build release bundles with
`--rerun-tasks`**.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.9 / 27 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| Database version | 7 (from 3 in code 26) |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |

# Release notes — 1.12.10 (versionCode 28)

Play currently serves **1.12.8 (26)**. Code 27 was built and never uploaded, so this covers
everything since 26 and supersedes the 1.12.9 notes.

**The headline is the first section.** Family members were disappearing from each other's
lists, and it could not fix itself. Everything else in this release is the shared-file rebuild.

## Play Console "What's new"

```
Fixes family members disappearing from each other's lists.

• Devices that had drifted apart now reconcile automatically instead of staying out of sync forever
• Fixes a chain reaction where missing one member meant never receiving anyone they added
• Shared files no longer get stuck — missing pieces are fetched again in the background
• New File status screen: what each file is doing and how many devices have it
• Pin a file to keep it on every phone; everything else downloads when opened
• Fixed unnamed "?" pins on the map

Everyone should update — devices cannot repair this on their own.
```

## 1. Family members going missing

Reported with three devices holding three different member lists:

```
Daddy (created the family) ──added──> Courage Phone ──added──> Extra Phone

Daddy:        had Courage Phone, missing Extra Phone
Second phone: missing both
```

That pattern is a chain reaction, and it is what identified the cause. Second phone never
received Courage Phone. An update signed by a member a device does not recognise is refused
outright — so *everything Courage Phone signed* was unusable to Second phone, and Extra Phone
could never arrive. Missing one member compounds into never catching up.

Underneath were two separate faults, both of which made a temporary disagreement permanent.

**Devices that drifted apart could never rejoin.** Every change to the family list is chained
to the one before it. When two devices disagreed and then *both* carried on making changes,
neither could prove its history led to the other's, and each rejected the other from then on.
Version 1.12.7 taught devices to reconcile when they disagreed at the same point — but not
when they had both moved on since, which is the case that actually happens. A broken chain is
now treated as evidence of drift rather than as something suspicious, and the two lists are
merged: everyone either device knows about, minus anyone genuinely removed.

**A removal froze out older devices.** Removing someone leaves a permanent marker. Devices
running a build from before those markers existed do not send them, and that absence was read
as an attempt to erase them — so the whole update was refused. The moment one person left the
family, a device stopped accepting *any* change from anyone on an older version. What matters
is that a removed person cannot reappear, and that is still enforced; the marker no longer has
to be present in every message for the message to be trusted.

Nothing else was relaxed. A member's keys still cannot change, an added member's identity must
still match their key, the family's identity is still immutable, and a removed member still
cannot come back.

**Affected devices cannot repair themselves on the old version.** Every phone needs this
update. After that they reconcile on their own — recreating the family is not required to fix
this.

## 2. Shared files

Rebuilt around a question the old design could not answer: *is this document actually on my
family's phones?*

- **Nothing gets stuck.** Pieces used to be sent once with no record of what arrived, so one
  lost piece stranded a file indefinitely and the only recovery was opening it by hand — which
  is why one file took half a day. The app now tracks exactly which pieces it has, asks one
  specific device for what is missing, and keeps retrying in the background across restarts.
- **Large files will not exhaust memory.** Sharing used to load the whole file into memory
  several times over; everything now streams.
- **Partly-downloaded files are no longer stored unencrypted.**
- **Pinning.** A pinned file is kept on every phone automatically — the point of the feature
  for something like an insurance card that has to be there when there is no signal. Everything
  else downloads when opened, so a large file is not pushed onto every device. Existing files
  are all treated as pinned.
- **File status screen**, reached from the Files screen: every file, its state, how many
  devices hold it, and a log of what was tried and what came back.
- **Corrupted downloads recover** instead of failing permanently and leaving their partial
  data behind.
- Asking for a missing piece no longer makes another phone re-send every file it has.

## 3. Other fixes

- **"?" pins on the map.** Location history is kept for 30 days and outlives family
  membership, and the map drew a marker for every member it had ever seen — including people
  who had left. Those showed as anonymous pins at stale positions. Only current members are
  shown now. **Location history itself is untouched.**
- **Leaving the family now confirms it erased this device's identity.** The wipe was
  best-effort inside a silent catch while the "you have left" state was set regardless, so the
  two could disagree. Found on a real device: after leaving, the signing keys and the recovery
  phrase were still in storage while the app showed the welcome screen.
- **Shared file names.** Files chosen from the system picker were saved under an internal
  document ID instead of their real name. New uploads are correct; files shared before this
  keep the old name.
- **A single oversized message on Wi-Fi no longer kills the connection**, which used to
  discard every later message to that device until it reconnected.

## Before shipping

**Everyone jumps four database upgrades at once.** Code 26 shipped database version 3; this is
version 7. Each step was verified individually on a device, but **the 3 → 7 path in a single
launch has not been tested**. There is deliberately no destructive fallback any more — a failed
migration fails loudly rather than silently wiping chat and location history — so the failure
mode is a crash on launch, not silent data loss. Install 26, let it create data, then install
this over it before rolling out widely.

**No two devices have exchanged a file through the new code.** The repair path, availability
reporting and pinning are covered by unit tests and verified on one phone. None has had a
second device to talk to.

## Compatibility

`PROTOCOL_VERSION` stays at **3**. The new topics are additive — older devices simply do not
take part in file repair or availability reporting — so this deliberately does not mark every
current device as out of date.

Because devices on 26 do not report what they hold, file copy counts read as unknown until
everyone updates. The app says so rather than claiming a file exists nowhere else.

## Still outstanding

- Deleting a file still only deletes it on your own phone, despite the dialog saying "for
  everyone in the family".
- The file list is not yet signed.
- Completed files are still stored unencrypted at rest; pieces in transit now are not.
- The family recreation described in the 1.12.6 notes is still what switches on the file,
  file-name and presence protections for families created before 1.12.0. It is **not** needed
  for the member-list fix above.

## Verification

381 unit tests, 0 failures, including a test class that reproduces the reported three-device
topology by name and checks that the chain reaction unwinds once the missing member is
recovered.

Build the bundle and **inspect it** rather than trusting a successful build: a plain build has
produced an APK missing an entire package in this project before. Check that the new classes
are present in the dex and that every arm64 library is 16 KB-aligned.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.10 / 28 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| Database version | 7 (from 3 in code 26) |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |

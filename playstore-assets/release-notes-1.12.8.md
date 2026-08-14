# Release notes — 1.12.8 (versionCode 26)

**Ship this one urgently.** Play currently serves **1.12.0 (18)**; the last build uploaded
was **24**. Both are unusable on recent Pixels — see below. Code 25 was built but never
uploaded, so this note covers everything since 24.

## Play Console "What's new"

```
Important fixes.

• Fixed the app closing and returning to the welcome screen when creating a family on newer Pixel phones
• Fixed the app disconnecting from the network over and over, so location and messages could not get through
• Fixed family members going missing from each other's lists when two people accepted a join at the same time
• Removing someone now sticks

Everyone should update.
```

## 1. Newer Pixels could not use the app at all

Reported on a Pixel 10a running Android 17: entering the family name returned the user to
the welcome screen, every attempt.

Pixel 10 devices use **16 KB memory pages**. Android's linker refuses to load a native
library whose segments are aligned for 4 KB pages, and four of the six shipped libraries
were: libsodium, libsqlcipher, ML Kit's barcode scanner and a CameraX helper.

Nothing in onboarding touches native code until "Create Family" is pressed — recovery-phrase
generation is pure Java. The first thing that does is libsodium, so the crash landed exactly
there. It threw `UnsatisfiedLinkError`, which is an `Error` rather than an `Exception`, so
the surrounding error handling never saw it; the app died and relaunched at the welcome
screen. Deterministic, which is why it failed every attempt while other phones were fine.

Fixed by moving to `lazysodium-android` 5.2.0, `net.zetetic:sqlcipher-android` 4.9.0,
ML Kit 17.3.0 and CameraX 1.4.2. **arm64-v8a is now verified 16 KB-clean end to end** by
reading the ELF program headers of every library in the bundle.

Two traps worth recording. Maven's search index lists lazysodium-android only up to 5.1.0
(2022), so the library looks abandoned; 5.2.0 exists and is aligned, but only the repository
listing shows it. And `android-database-sqlcipher` is retired — the replacement artifact
changes package name and no longer loads its own native library, so the database code now
does that explicitly.

This also affects Play compliance: 16 KB support is required for apps targeting Android 15+,
and this app targets 36.

## 2. The app kept disconnecting itself from the relay

Reported as "left the group, rejoined, and it won't go on the network". The connection was
being made and then killed every few seconds, forever, so nothing needing the relay worked.
LAN peers kept working, which disguised it.

Connection setup is triggered from four places that race at startup, and the only guard was
"skip if already connected" — which three simultaneous callers all slip past while the state
still says *connecting*. Each built its own client under the same connection ID, and the
protocol requires the broker to kick the older session in that case. The app evicted itself
in a loop.

The retry logic made it worse: the MQTT library reports "already connected" as a *failure*,
so a healthy connection was counted as a failed attempt, and after three the code scheduled
a reconnect that tore down the connection that had just succeeded.

Now serialised, with "already connected" understood as success. Measured on a device over
the same 45-second window: before, repeated connects with a disconnect every few seconds;
after, one connect and zero disconnects.

## 3. Families could split in two

Any member can approve a join, so two people approving at the same moment each produced a
different version of the family list with the same version number. The old rule for "a
message at the version I already have" was to acknowledge it and move on — right when the
two agree, silently wrong when they differ. Both sides then believed they were in sync while
holding different member lists, permanently, and every later change failed its integrity
check.

Seen in a four-member family the first day two devices were tested together: one device sat
on three members while everyone else had four, receiving the missing member's messages and
discarding them.

Devices now reconcile automatically: both independently pick the same state to build on and
the other merges its changes onto it, ending at the union of both lists. Nothing for anyone
to decide.

Removals now leave a permanent marker, because merging two lists would otherwise put back
anyone who had just been removed — "they removed this person" and "they haven't heard of
this person yet" are indistinguishable without one. The marker is covered by the signature so
it cannot be stripped in transit.

## 4. Smaller fixes

- Identity keys, the recovery phrase and the setup-complete flag are now written to disk
  synchronously. Onboarding ends by restarting the app with a hard kill, which does not
  flush a background write, so those could be lost — taking the user's only copy of their
  recovery phrase with them.
- Clearing an invite no longer looks like a corrupt message to every other device.
- Tapping "Show on map" now draws that person's pin above any overlapping ones.

## Compatibility

`PROTOCOL_VERSION` is now **3**. Older builds cannot reconcile and drop the removal markers,
so they will show as "needs to update" on the Family screen.

## Rollout

The family recreation described for 1.12.6 is still required to enable the file, file-name
and presence protections for families created before 1.12.0. Do it **on 26**: recreating a
family means several people joining in quick succession, which is exactly the case that
caused the split in item 3.

Everyone updates → everyone leaves → **one person creates the family on 26** → re-invite.
Leaving destroys local group data and identity keys, and **old recovery phrases stop
working**. Tell people before they leave.

## Verification

329 unit tests, 0 failures. Confirmed on a physical device: no crash, the existing encrypted
database opens and writes after the SQLCipher change (history preserved), and MQTT holds a
single stable connection.

**Build the bundle with `--rerun-tasks`.** A `clean` build produced an APK missing an entire
package — it installed and then crashed with `ClassNotFoundException` — and the bundle
shipped from a stale dexing step would do the same. The uploaded bundle was checked directly:
162 storage classes present, every arm64 library 16 KB-aligned.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.8 / 26 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |

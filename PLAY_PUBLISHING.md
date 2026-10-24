# Uploading to Google Play

One command builds the release bundle and uploads it to Play's **internal testing** track:

```
./gradlew publishReleaseBundle
```

Every release goes to internal testing first. After it has been checked on two phones, the
same bundle (not a rebuild) is promoted to production as a 20% staged rollout:

```
./gradlew promoteReleaseArtifact --from-track internal --promote-track production --release-status inProgress --user-fraction 0.2
```

Managed publishing is on, so nothing goes live from here: Play reviews the release, then the
owner presses **Publish** in Play Console → Publishing overview. Raise the rollout to 100%, or
halt it, in Play Console. The store listing, in-app products and the subscription are edited in
Play Console only; their publish tasks are switched off in `app/build.gradle.kts`.

Why the gate: this PC is shared with other administrators, who can read the robot's key. With
Managed publishing, a stolen key can at most queue a release; only the owner's Play login can
send it to the family's phones.

## Before each upload

1. The release branch is merged: `versionCode` and `versionName` are bumped, and the new entry
   is at the top of `RELEASE_NOTES.md`. Play refuses a `versionCode` it has seen before.
2. The new entry has a **Play copy** block of at most 500 characters. The build copies it into
   Play's "What's new" (the copy in `app/src/main/play/release-notes/` is generated, and ignored
   by git), and stops if it's missing or too long.
3. The upload keystore is in `keystore/`, as for any release build.

The command prints the "What's new" length, then uploads. Keep the bundle's
`app/build/outputs/mapping/release/mapping.txt` once R8 is on (PR #67): Play needs it to show
readable crash reports.

## One-time setup (owner only: needs the Play Console login)

1. **Google Cloud Console.** In a project of your own, enable the **Google Play Android
   Developer API** (APIs & Services → Library).
2. **IAM & Admin → Service accounts.** Create a service account (for example `play-upload`).
   Under **Keys → Add key → JSON**, download its key.
3. **Play Console → Users and permissions → Invite new users.** Invite the service account's
   email address. Give it **this app only** and these permissions only:
   - Release apps to testing tracks
   - Release to production, exclude devices, and use Play App Signing
   - (nothing for the store listing, financial data, or account permissions)
4. **Play Console → the app → Publishing overview → Managed publishing: on.** Never turn it
   off while the robot has production rights.
5. Save the key as `keystore/play-service-account.json`. `keystore/` is ignored by git, and
   `play-service-account*.json` is ignored anywhere as a second lock.

Never paste the key into a chat, an issue or a commit: the repository is public. If it ever
leaks, delete the key in Google Cloud Console and make a new one; the service account itself
can stay.

## Checking the setup without uploading

```
./gradlew :app:generateReleasePlayResources
```

This builds nothing and uploads nothing. It prints the "What's new" length and fails if the
Play copy is missing or too long. Then `./gradlew :app:tasks --group publishing` should list
`publishReleaseBundle`.

## If an upload fails

- **"No credentials"**: `keystore/play-service-account.json` is missing.
- **403, "The caller does not have permission"**: the service account wasn't invited for this
  app, the app permissions weren't saved (**Apply**, then **Save changes** at the bottom of the
  page), or the invitation is still being processed (it can take a while after the first setup).
- **"Version code … has already been used"**: bump `versionCode` (see "Before each upload").

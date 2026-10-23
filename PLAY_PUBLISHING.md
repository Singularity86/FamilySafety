# Uploading to Google Play

One command builds the release bundle and uploads it to Play's **internal testing** track:

```
./gradlew publishReleaseBundle
```

It uploads to internal testing and nowhere else. Moving a release on to closed testing or
production stays a button in Play Console, pressed after the release has been checked on two
phones. The store listing, in-app products and the subscription are edited in Play Console
only; their publish tasks are switched off in `app/build.gradle.kts`.

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

1. **Play Console → Setup → API access.** Link a Google Cloud project (create one if asked).
2. **Google Cloud Console → IAM & Admin → Service accounts.** Create a service account (for
   example `play-upload`). Under **Keys → Add key → JSON**, download its key.
3. **Play Console → Users and permissions → Invite new users.** Invite the service account's
   email address. Give it **this app only** and these permissions only:
   - Release apps to testing tracks
   - (nothing for production, the store listing, or financial data)
4. Save the key as `keystore/play-service-account.json`. `keystore/` is ignored by git, and
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
  app, or the invitation is still being processed (it can take a while after the first setup).
- **"Version code … has already been used"**: bump `versionCode` (see "Before each upload").

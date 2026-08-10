# Release notes — 1.12.4 (versionCode 22)

Covers changes since 1.12.3 (versionCode 21).

**Supersedes 18 through 21.** If none has been uploaded, ship this one. The rollout
sequence in `release-notes-1.12.0.md` is unchanged and still required.

## Play Console "What's new"

Paste as-is. 198 characters; the per-language limit is 500.

```
Housekeeping and privacy fixes.

• Joining a family no longer leaves a copy of the invite approval on the relay afterwards
• Includes the presence, file and message-size protections from recent updates
```

## Full changelog

### Fixed

- **Successful joins left an approval message on the relay permanently.** When a join was
  declined, the app tidied up after itself — it cleared both the retained approval and the
  pending request. When a join *succeeded*, it cleared neither. So every member ever added
  left a 2.4–3.9 KB approval sitting on the broker indefinitely, replayed to anything that
  subscribed, and standing as a permanent record that they joined and when. Eight had
  accumulated. Both are now cleared on success too.
  (finding F5 in SECURITY_REVIEW.md)

  The clear happens **after** the family definition is saved, not before. Clearing first
  would leave a device that died mid-join with neither a saved family nor an approval to
  recover from.

### Known gaps

- **The eight existing messages are not removed by this.** They sit on topics belonging to
  members, and deleting one that has not yet been consumed would break a join in progress.
  Clearing them is a deliberate manual step.
- An approval for someone who never completes the join still sits indefinitely. A real
  expiry has to live inside the signed part of the message to mean anything, which is a
  larger change than this warrants.
- **F2 and F3 remain open** and cannot be closed in the app alone: one shared broker
  credential ships in every install, with no per-topic permissions, which is also what
  leaves traffic metadata visible. Closing them needs a decision about how per-device
  credentials would be issued.

## Wire format

No change. This is protocol *behaviour*: `IOS_PORT_SPEC.md` §4 now requires the joiner to
clear both retained topics on success as well as rejection, and to do it after the group
is durably saved.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.4 / 22 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |

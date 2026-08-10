# Release notes — 1.12.5 (versionCode 23)

Covers changes since 1.12.4 (versionCode 22).

**Supersedes 18 through 22.** If none has been uploaded, ship this one. The rollout
sequence in `release-notes-1.12.0.md` is unchanged and still required.

## Play Console "What's new"

Paste as-is. 224 characters; the per-language limit is 500.

```
Stronger privacy on the network.

• Whether you are online or offline is now encrypted, so the relay can no longer see who is awake or when anyone leaves
• Includes the file, presence and message-size protections from recent updates
```

## Full changelog

### Security

- **Online/offline status is no longer readable by the relay.** Presence was published as
  plaintext, so anyone able to reach the broker could read `isOnline` directly for every
  member — who is awake, and the moment they leave. Over time that reconstructs a
  household's daily routine without decrypting a single location. It is now encrypted.
  (finding F3 in SECURITY_REVIEW.md, partially closed)

  Encrypted under a key the whole family shares rather than per recipient, because the
  "went offline" message is published by the relay *after* the phone is gone — there is no
  moment at which the phone could encrypt it to each person. The key is derived
  specifically for presence, so it does not expose the file key.

### Correction to 1.12.1's notes

1.12.1 said uniform message sizes stopped the relay inferring who was online. That was
overstated: padding closed the message-*length* channel, but the presence payload was
plaintext, so the status could simply be read. Padding did what was claimed for
*location*, where length was the only leak. This release is what actually closes presence.

### Known gaps

- **Member IDs are still visible in topic names**, so an observer still sees how many
  devices a family has and when each one publishes — just not who is online. Hiding those
  means renaming every topic, which is a hard cutover where old and new apps cannot see
  each other at all. Not attempted.
- **Movement timing is still visible.** A location publish implies movement; hiding that
  needs constant cover traffic, which costs battery to obscure.
- **F2 remains open**: one shared relay credential ships in every install, with no
  per-topic permissions. Closing it needs a decision about how per-device credentials
  would be issued.
- Legacy families get none of this — presence, file contents and file names all fall back
  to unprotected for families created before 1.12.0. Recreating the family is the fix.

## Wire format

The presence topic now carries `SealedPresence{v, data}` instead of a bare envelope.
Receivers accept both shapes. Like the manifest change in 1.12.2, this is **not** additive:
a client expecting a bare envelope sees no presence at all. `IOS_PORT_SPEC.md` §6.2 has
the key derivation and the algorithm.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.5 / 23 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |

# Release notes — 1.12.1 (versionCode 19)

Covers changes since 1.12.0 (versionCode 18).

**If 18 has not been uploaded yet, ship this instead — 19 contains everything 18 did.**
The rollout sequence in `release-notes-1.12.0.md` still applies unchanged: every device
must be on the new build before a family is recreated.

## Play Console "What's new"

Paste as-is. 213 characters; the per-language limit is 500.

```
Privacy improvements.

• Messages are now a uniform size, so the relay can no longer infer who is online or who is moving from traffic patterns alone
• Includes the shared-file protection from the previous update
```

## Full changelog

### Security

- **Message length no longer leaks behaviour.** Presence messages were 136 bytes when
  online and 137 when offline, because `"true"` is four characters and `"false"` is
  five — so anyone watching the broker could read every member's online state without
  decrypting anything. Location had the same problem in encrypted form: `speed` and
  `bearing` are only populated when moving, so a moving device emitted a longer
  ciphertext than a stationary one, and coordinate precision leaked position the same
  way. Envelopes are now padded to a fixed size — presence 256 bytes, location 512 —
  applied to the plaintext so the ciphertext inherits the constant length.
  (finding F6 in SECURITY_REVIEW.md)

### Known gaps recorded this release

- **F7 — shared file names are published in cleartext.** The file manifest is broadcast
  unencrypted and retained, exposing every shared file's name, type, exact size,
  uploader and timestamp to anyone on the broker. This survives the 1.12.0 fix, which
  protected file *contents* only. Not fixed here; it needs the manifest moved onto the
  per-recipient encrypted path, which costs the retained catch-up that lets a new member
  sync instantly.
- Chat, replication and group sync are still unpadded. Chat leaks message length; that
  is a deliberate deferral, since chat lengths span orders of magnitude and padding them
  is a bandwidth decision rather than a free fix.

## Wire format

`MessageEnvelope` gains `pad`. It is optional, absent from older senders, and ignored on
receive, so the change is backward compatible in both directions. `IOS_PORT_SPEC.md` §6.1
carries the exact padding algorithm — an implementation that does not emit `pad` will
reintroduce the leak for its own traffic.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.1 / 19 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |

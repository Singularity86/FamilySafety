# Release notes — 1.12.3 (versionCode 21)

Covers changes since 1.12.2 (versionCode 20).

**Supersedes 18, 19 and 20.** If none has been uploaded, ship this one. The rollout
sequence in `release-notes-1.12.0.md` is unchanged and still required.

## Play Console "What's new"

Paste as-is. 243 characters; the per-language limit is 500.

```
Privacy and integrity improvements.

• Online/offline status is now signed by each device, so it cannot be faked by anyone else
• File names, types and sizes are encrypted, not just file contents
• Messages are a uniform size on the network
```

## Full changelog

### Security

- **Online/offline status can no longer be faked.** Presence is the one message type that
  cannot be encrypted — an MQTT last-will is published by the broker after the device is
  gone, so the device is not there to encrypt it. That left it forgeable: the only check
  was that the message named the same member as the topic it arrived on, which anyone able
  to publish to that topic satisfies trivially. Presence is now signed with each device's
  Ed25519 key and verified against the key already distributed in the family definition.
  (finding F4 in SECURITY_REVIEW.md)

  Replay is rejected by timestamp, so a captured "went offline" message cannot be
  replayed later to make someone appear offline. Downgrade is rejected by memory: once a
  device has been seen signing, an unsigned message claiming to be it is treated as an
  attack rather than an old client.

  Notably this is **not** something broker permissions could have fixed. Even per-device
  broker credentials would still let one family member forge another's presence, and
  inside the family is where it matters most.

### Changed

- Presence padding moved from 256 to 512 bytes. The signature does not fit in 256, and
  leaving it would have split presence across two sizes, making signed and unsigned
  devices distinguishable on the network — undoing part of the 1.12.1 change.

### Known gaps

- A device never yet seen signing can still be impersonated. Unavoidable while older
  clients exist; the window closes per device on its first signed update after updating.
- F2, F3 and F5 in SECURITY_REVIEW.md remain open: one shared broker credential in every
  install, metadata exposure, retained-message pollution.
- Legacy families still publish plaintext file manifests and use the derivable file key.
  Recreating the family on 1.12.0+ is the fix.

## Wire format

`PresenceUpdate` gains `signature`, optional and absent from older senders. The canonical
signing string and the replay/downgrade rules are in `IOS_PORT_SPEC.md` §6.2 — a client
that signs a differently-built string produces signatures that silently fail to verify.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.3 / 21 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |

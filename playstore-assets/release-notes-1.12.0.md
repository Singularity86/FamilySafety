# Release notes — 1.12.0 (versionCode 18)

Covers changes since 1.11.4 (versionCode 17).

Minor version rather than a patch on purpose: this changes the file-transfer
wire format and every device must be on it before a new family is created.
See "Rollout" below — this release is not fire-and-forget.

## Play Console "What's new"

Paste as-is. 268 characters; the per-language limit is 500.

```
Stronger protection for shared files.

• Shared files now use a private key held only by your family, instead of one derived from the family's public identifier
• Everyone in the family needs this update before sharing files again
```

Wording is deliberately non-specific about the weakness. The details are in
SECURITY_REVIEW.md; a public store listing is not the place to hand out a
recipe, and the exposure it closes is still present in older installs.

## Full changelog

### Security

- **Shared files no longer use a key anyone could compute.** The file key was
  `SHA-256(groupId + constant)`. The constant is compiled into the app and the
  groupId is published in cleartext inside MQTT topic names, so the key was
  derivable by anyone who could reach the broker — location and chat were
  unaffected, but file contents were readable. Groups now carry a random
  256-bit key distributed inside the already-encrypted group definition.
  (`3972f21`, finding F1 in SECURITY_REVIEW.md)

### Added

- `SECURITY_REVIEW.md` — full review of relay exposure, five findings, and a
  four-phase remediation plan. This release implements Phase 0 only.

## Rollout

This release needs coordination. Two things bite otherwise.

**Mixed builds break file sharing.** Chunk messages carry a `keyVersion`, and
`SharedFileRepository` decodes with `ignoreUnknownKeys = true`. A device on 17
or earlier receiving a `keyVersion: 2` chunk silently ignores the field,
decrypts with the old key, and fails — the download just does not complete,
with nothing useful shown.

**The key is only generated when a family is created.** Existing families
carry a null key and keep falling back to the old derivation, so they stay
readable. There is no in-place migration in this release.

To actually close the hole on an existing family:

1. Ship 18 and confirm **every** device has it (Settings, or the app version
   on the Security screen).
2. Everyone leaves the family.
3. One person creates the new family **on 18**. Creating it on an older build
   silently produces a null key and achieves nothing.
4. Re-invite everyone.
5. Share one file end to end and confirm it downloads, before relying on it.

Leaving wipes local group data **and cryptographic keys**, so everyone gets a
new member ID and a new recovery mnemonic. Chat history, shared files and
location history do not survive. Old mnemonics become useless — make sure
nobody is depending on one they have written down.

## Known gaps

- Existing families are not migrated; recreating the family is the only route.
- Findings F2 (one shared broker credential in every install), F3 (metadata
  exposure), F4 (presence forgery) and F5 (retained-message pollution) are
  untouched and need Phases 1–3.
- `IOS_PORT_SPEC.md` does not yet document `keyVersion` or
  `fileEncryptionKey`, both of which are wire-format changes.
- The new key path is compile- and unit-test-verified only. No test covers
  file transfer; step 5 above is the real verification.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.0 / 18 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |
| Signing key | upload key, `CN=FamilySafety, O=Courage On Purpose, C=US` |

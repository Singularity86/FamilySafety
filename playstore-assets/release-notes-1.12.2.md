# Release notes — 1.12.2 (versionCode 20)

Covers changes since 1.12.1 (versionCode 19).

**Supersedes 18 and 19.** If neither has been uploaded yet, ship this one — it contains
everything they did. The rollout sequence in `release-notes-1.12.0.md` is unchanged and
still required.

## Play Console "What's new"

Paste as-is. 231 characters; the per-language limit is 500.

```
Privacy improvements for shared files.

• File names, types and sizes are now encrypted, not just file contents
• Messages are a uniform size, so traffic patterns no longer reveal who is online or moving
```

## Full changelog

### Security

- **Shared file names are no longer published in the clear.** The file manifest went out
  as plaintext JSON — every file's name, MIME type, exact size, uploader and timestamp —
  and it was *retained*, so the broker served it to any client on subscribe, whether or
  not they were listening when it was published. The 1.12.0 fix protected file
  *contents*; the manifest had never been encrypted at all, and a file name is often more
  revealing than the file. It is now encrypted with the group key.
  (finding F7 in SECURITY_REVIEW.md)

  Encrypted symmetrically rather than per recipient, deliberately: per-recipient
  encryption would mean one publish per member and would lose the retained catch-up that
  lets a joining member see the file list instantly. The plaintext is also padded to a
  1 KiB grid, so ciphertext length no longer reveals how many files a family has.

### Known gaps

- **Legacy families still publish a plaintext manifest.** Their only available key is
  derivable by anyone on the broker, so encrypting with it would look like protection
  without being any. Recreating the family on 1.12.0+ remains the fix, as it is for the
  file-contents issue.
- Chat, replication and group sync remain unpadded; chat leaks message length.
- F2–F5 in SECURITY_REVIEW.md are untouched: one shared broker credential in every
  install, metadata exposure, presence forgery, retained-message pollution.

## Wire format

The manifest topic now carries `EncryptedFileManifest{keyVersion, data}` instead of a
bare `FileManifest`. Receivers try the encrypted shape first and fall back to plaintext,
since a retained plaintext manifest from before the upgrade can outlive it.

Unlike the earlier additive changes, this one **changes the published shape**: a client
that only understands the bare manifest will see no files from 1.12.2+. `IOS_PORT_SPEC.md`
§6.7 carries the details.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.2 / 20 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |

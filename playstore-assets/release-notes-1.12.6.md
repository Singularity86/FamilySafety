# Release notes — 1.12.6 (versionCode 24)

**This is the consolidated note for the build that actually ships.**

Play currently serves **1.12.0 (code 18)** — confirmed by reading the installed package
off a device, not assumed. So the file-*contents* fix (F1) is already live and is **not**
new to your users; everything from code 19 onward is. Codes 19–23 were built during
development and never uploaded, and 24 contains all of them, so this file covers that
whole span rather than just the last increment. The per-version files alongside it are
development history.

## Play Console "What's new"

Paste as-is. 447 characters; the per-language limit is 500. Scoped to what is new since
**1.12.0**, which is what users actually have — the file-contents protection shipped there
and is deliberately not repeated here.

```
A security and privacy release.

• Your online/offline status is now encrypted, so the relay can no longer see who is awake or when anyone leaves
• Online/offline updates are signed and cannot be faked by anyone else
• Shared file names, types and sizes are encrypted, not just file contents
• The Family screen now tells you when someone needs to update

Everyone should update — older versions can't share with newer ones.
```

The wording stays non-specific about the weaknesses being closed. Details belong in
`SECURITY_REVIEW.md`; a public store listing is not the place for them while older
installs remain unpatched.

## What changed, 18 → 24

Every item below came out of a review of what the MQTT relay could observe. Findings are
numbered as in `SECURITY_REVIEW.md`.

### Shared files

- **File contents (F1, code 18).** The file key was `SHA-256(groupId + constant)` — the
  constant compiled into the app, the groupId published in cleartext in the topic name.
  Both inputs were public, so anyone able to reach the relay could derive the key and
  decrypt every file any family had shared. Groups now carry a random 256-bit key
  distributed inside the already-encrypted group definition.
- **File names (F7, code 20).** The manifest was published unencrypted and retained, so
  every shared file's name, type, exact size, uploader and timestamp sat on the relay
  permanently and was served to any client on subscribe. A file name is often more
  revealing than the file. Now encrypted with the group key, and padded so its size does
  not reveal how many files exist.

### Presence

- **Forgery (F4, code 21).** The only authenticity check was that a message named the same
  member as the topic it arrived on — which anyone able to publish to that topic satisfies
  trivially. Presence is now signed with each device's Ed25519 key, with replay and
  downgrade both rejected. Broker permissions could not have fixed this: no credential
  scheme prevents one family member forging another's.
- **Readability (F3, code 23).** Presence was plaintext, so the relay could read
  `isOnline` directly — who is awake, and the moment they leave. Now sealed under a
  presence-specific key derived from the group key.

### Network

- **Message length (F6, code 19).** Length leaked behaviour through encryption: presence
  was 136 bytes online and 137 offline, and location payloads grew when speed and bearing
  were populated, marking a device as moving. Envelopes are padded to fixed sizes,
  applied to the plaintext so the ciphertext inherits the constant length.
- **Retained invites (F5, code 22).** Declining a join cleaned up after itself; accepting
  one did not, so every member ever added left a multi-kilobyte approval on the relay
  permanently. Both are now cleared, after the family definition is durably saved.

### Diagnosability

- **"Needs to update" (code 24).** Three of the changes above replace a message shape
  rather than adding a field, so a device on an older build and one on 24 simply cannot
  see each other — and until now that failed *silently*: the other person just never
  appeared, which is indistinguishable from the app being broken. Devices now advertise
  their wire-format version in presence, and the Family screen says plainly when someone
  is behind. Borrowed from the version handshake in the R!sk codebase, which had this
  right from the start.

## Rollout — required, not optional

Two things bite if this is treated as a normal update.

**Mixed versions break features.** Three of these changes replace a message shape rather
than adding a field. A device on 17 and a device on 24 will not see each other's files or
presence. Everyone updates, or the family is split — the app now says so on the Family
screen rather than leaving it to be guessed.

**Existing families get almost none of it.** The file-content, file-name and presence
protections all depend on a group key generated when a family is *created*. Families made
before 1.12.0 carry no key and fall back to the old behaviour. To actually switch the
protections on:

1. Ship 24 and confirm **every** device has it — the Security screen shows the app version.
2. Everyone leaves the family.
3. One person creates the new family **on 24**. Doing this on an older build silently
   produces no key and achieves nothing.
4. Re-invite everyone.
5. Share one file end to end and confirm it downloads before relying on it.

Leaving wipes local group data **and cryptographic keys**: everyone gets a new member ID
and a new recovery mnemonic, and chat history, shared files and location history do not
survive. **Old mnemonics become worthless** — tell people before they leave, not after.

## Known gaps

- **F2 is accepted risk, not a bug.** One shared relay credential ships in every install
  with no per-topic permissions. The broker operator is out of the threat model as of
  2026-08-10; the reasoning and what would reopen it are recorded in `SECURITY_REVIEW.md`.
- The relay still sees connection times, pseudonymous member identifiers, and how often
  each device sends — so it can infer *that* someone is moving, never where.
  `PRIVACY_POLICY.md` discloses this.
- Chat message length is still unpadded.
- **None of this has been verified between two devices.** Every change is compile- and
  unit-test-verified only. Step 5 above is the first real test.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.6 / 24 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |
| Signing key | upload key, `CN=FamilySafety, O=Courage On Purpose, C=US` |

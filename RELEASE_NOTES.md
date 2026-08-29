# Release Notes

The "What's new" text that goes in the Play Console, kept here so it is written
deliberately rather than in the upload dialog, and so the reasoning behind a release
survives after the field itself has been overwritten by the next one.

Play's field is **500 characters**, counted per language. The block marked *Play copy* in
each entry fits; everything under it is for us.

---

## 1.13.0 (29) — the vault, encrypted documents, and the map

Built from `ca7e788`. Supersedes 1.12.10 (28), which is what everyone is on today.

### Play copy

```
Everyone in the family needs this update. A phone still on 1.12.10 sends a file list this
version rejects, so its shared files stop updating for everyone else.

New in this version:
• A family vault — documents kept behind a passphrase stored nowhere and never resettable
• Shared documents are now encrypted on the phone itself, and the file list is signed
• Deleting a document now deletes it for the whole family
• People standing in the same place no longer hide each other on the map
```

### Why the update warning leads

It is not a courtesy. 1.12.10 publishes its file manifest unsigned, and this version
refuses unsigned manifests — that refusal is the point of the change, since an accepted
manifest from anyone was how a stranger could have rewritten what the family thought it
had. The cost is that a half-updated family sees a file list that silently stops moving
for the members who did update. The Family screen already names peers that are behind, so
the app says who is holding things up; the release note is what stops that being a
surprise.

### What is actually in it

- **Phase 5** — deletions propagate (they were being filtered out of the manifest on the
  way out, while the dialog promised "delete for everyone"), the manifest is signed with
  Ed25519 and verified against our own roster, and documents are stored encrypted rather
  than sitting in plaintext beside an encrypted database describing them. A resumable
  startup pass migrates older plaintext copies, deleting each only after its encrypted
  copy verifies.
- **Phase 6, the vault** — 256 fixed-size slots of random bytes, created on every device
  whether or not anyone sets a code. Every code opens something: the family code opens the
  real slots, a decoy code opens the decoy slots, anything else opens an empty vault.
  There is no error path, because an error path is an oracle.
- **The map** — people close enough to overlap are drawn as one bubble of small faces
  rather than as pins hiding each other, and tapping it names them.
- **Joining** — a pasted invite code with a stray newline or a line wrap in it now works.
  It used to fail as though the code were wrong.

### What this release makes permanent

The vault passphrase floor is 16 characters. It can be raised only while no vault exists
in the wild, because a passphrase that is nothing but a key-derivation input cannot be
re-asked for by a device that has already forgotten it. **Uploading this closes that
window.**

It also turns the accepted F2 exposure into a live one: the vault container is retained on
the broker, so anyone holding the shared broker credential can guess the family's
passphrase offline, forever. That was decided and accepted on 2026-08-21 with this
consequence in view — see `SECURITY_REVIEW.md`, "Decision, 2026-08-21". Not a surprise;
recorded here because a release is when a decision stops being about code.

### Still true after this release

A family created before 1.12.0 carries no `fileEncryptionKey`, and nothing in this build
backfills one. Until such a family is recreated, its documents fall back to a key derived
from public inputs and its manifest goes out unsigned — so most of what this release is
for does not reach it. The Security screen says which kind of family this is.

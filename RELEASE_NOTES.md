# Release Notes

The "What's new" text that goes in the Play Console, kept here so it is written
deliberately rather than in the upload dialog, and so the reasoning behind a release
survives after the field itself has been overwritten by the next one.

Play's field is **500 characters**, counted per language. The block marked *Play copy* in
each entry fits; everything under it is for us.

---

## 1.13.4 (33) — a warmer icon and a clearer header

Built from `565f9dc` and `5019688` + the version bump. Supersedes 1.13.3 (32).

No wire-format change, so devices on any recent version keep working together — this is
a purely visual release.

### Play copy

```
New app icon and a warmer color scheme. Dark mode moves from navy to deep green with
amber accents; light mode keeps its white background with the same amber. The top bar
now leads with your family's name instead of the app name.
```

### What changed, and why it's worth a release

- **New launcher icon** — a house-in-a-pin with a lit, off-center window and light
  spilling onto the step below, replacing the navy shield-and-family badge. Ships with a
  proper adaptive foreground/background split (the background layer had been hardcoded
  transparent despite a color resource already existing for it, unused) and the Android
  13+ monochrome themed-icon layer, neither of which existed before.
- **Recolored theme** — dark surfaces move from navy-black to the icon's own pine-green;
  a new brand amber (`PorchAmber`) replaces teal as the primary color everywhere: buttons,
  the selected nav tab, onboarding CTAs. Light mode's white surfaces are unchanged, same
  amber accent.
- **A second accent, on purpose** — teal had quietly been doing double duty as both the
  brand color and the "healthy/connected" status color. Collapsing both onto one amber
  would have made the LAN/Relay badge show the same color for both states, and made
  "Synced" and "Warning" read the same on the Security screen. A distinct `SuccessGreen`
  now carries sync status, LAN connection, and the "E2EE active" chip, so status colors
  stay legible instead of blending into the brand color.
- **Top bar hierarchy swapped** — your family's name is now the bold headline (shrinking
  its own font size instead of wrapping or truncating if it's long), and "Jibaro Family
  Safety" moved to a small caption strip under the whole bar instead of competing for
  space directly beneath the family name.

---

## 1.13.3 (32) — shared documents get their own key

Built from `5f3cbae` + the version bump. Supersedes 1.13.2 (31).

**This one changes the wire format for new file uploads**, and unlike most such changes it
is one-way. Everyone must be on 29 or later — confirmed for this family on 2026-08-30
before the flip was made.

### Play copy

```
Shared documents are now encrypted with their own key rather than the family key directly.
Nothing changes in how you use the app, and documents you already shared stay readable.

Everyone should be on the latest version — a device more than a few versions behind will
stop receiving newly shared documents.
```

### What changed, and why it is worth a release

Until now, shared documents were encrypted with the family key *itself*. That same key is
the parent of every other key the app derives — presence has always used its own subkey
rather than the raw secret. So the file key was effectively the master: anything that
recovered it also yielded everything derived from it.

Files now use `deriveSubkey(familyKey, "files")`. Identical secret underneath, but files
become a leaf instead of the root, so a flaw in the file path costs the files and nothing
else.

### Why this took three releases to land

The code that *reads* version 3 shipped in **29**; only the code that *writes* it lands
here. That gap is the point. If both had shipped together, every device still on the
previous release would have stopped being able to decrypt new files — and stopped
silently, because a device on 28 does not recognise the new version and reject it. It falls
through to the legacy key, fails authentication, and drops the chunk. Files would simply
never arrive, with nothing to explain why.

**Not reversible for anything published under it.** A device that stayed on 28 could never
read those files, even after updating, unless they were shared again.

Families created before 1.12.0 have no family key to derive from and are unaffected — they
remain on the legacy key, which is the condition that makes recreating such a family worth
doing in the first place.

---

## 1.13.2 (31) — a mistyped recovery phrase no longer restores a stranger

Built from the working tree at `339243e` + the version bump. Supersedes 1.13.1 (30).

No wire-format change, so devices on 30 and 31 work together.

### Play copy

```
Restoring an account now checks your recovery phrase properly. Before this, one mistyped
word could quietly set up a brand-new empty account instead of restoring yours — with no
error to tell you. If that happened to you, restore again with the correct phrase.

Also in this version:
• Fixes for the libraries flagged on 16 KB devices
```

### What was wrong

Restoring checked that every word you typed appeared in the BIP-39 wordlist, and nothing
else. No word count, and no checksum — which is what the last word of a recovery phrase is
*for*. And that check was never called anyway: the Restore button required only that all
twelve boxes were non-empty, and then derived keys from whatever was in them.

Nothing can fail here the way a wrong password fails, because there is no account on a
server to check against. Any twelve words derive *a* key. So one wrong word — still a real
word, just not yours — derived a different identity, saved it, marked setup complete, and
put the user into the app as somebody who had never existed: new member ID, not in their
family, no error anywhere. Their real account was still there, reachable only if they
worked out what had happened.

Now the phrase is checked for length, wordlist membership and checksum before anything is
derived, and a phrase that fails is refused with an explanation. For a twelve-word phrase
the checksum is four bits, so roughly fifteen in sixteen single-word mistakes are caught.

**This does not repair an account already restored wrongly** — nothing was lost, but the
device is holding the wrong identity. Restoring again with the correct phrase recovers it.

### 16 KB, continued

Carries the JNA 5.19.1 upgrade and the x86_64 drop described under 1.13.1, for anyone whose
30 upload predated them.

---

## 1.13.1 (30) — the disconnect that was not one

Built from `e0965c8`. Supersedes 1.13.0 (29), which is what the family is running.

Everyone can update at their own pace this time. 30 changes nothing about the wire
format, so a phone on 29 and a phone on 30 work together — unlike the 28 → 29 step,
which had to be taken by everybody.

### Play copy

```
Fixes a bug that could leave one phone showing an out-of-date family list while every
device reported a healthy connection. If someone in your family is missing from the
list, update and open the app.

Also in this version:
• Fewer false "disconnected" moments, and shorter ones
• People standing in the same place are grouped into a smaller, clearer bubble
• Invite codes with a stray line break in them now work when pasted
```

### What was actually wrong

Every message this app sends is QoS 1, published once per recipient, and Paho's
in-flight window was left at its default of ten. A five-member family turns one event
into four publishes; a file share turns it into hundreds. Once ten publishes are
awaiting acknowledgement the next one throws immediately — the socket is fine, the
client is simply refusing to hold another.

The transport read that as connection loss. It marked itself disconnected, and since
the first thing a publish does is give up when not connected, every remaining recipient
in the same loop was skipped without an attempt. One refused publish became all of
them, so a group-state broadcast to four reachable peers sent to none, and the device
displayed "Failed to broadcast update" while every peer was online.

The same false disconnect explains the intermittent connection drops nobody could
account for: the reconnect found the client still connected and quietly restored the
state — but without resetting the attempt counter, so the backoff grew, and the phantom
outages grew with it toward the five-minute cap.

Four changes: publish failures ask the client whether it is connected instead of
assuming; the in-flight window is raised, though only modestly, since unacknowledged
messages are held in memory; the offline queue is split so family-list changes cannot be
evicted by a flood of location updates; and locations and presence drop to at-most-once,
because a position supersedes itself and the receiver discards stale ones anyway.

**A device already holding an out-of-date family list is not repaired by installing
this.** It stops the divergence happening again and lets the correction through — the
correction still has to be sent. Open the app on both devices, and use Check Family List
on the Security screen from the device that is behind.

### 16 KB page sizes

Play flagged release 29 for `libjnidispatch.so` on arm64-v8a and x86_64 — JNA, the layer
lazysodium calls libsodium through. Two things were true and only one was known:

- **x86_64 was never 16 KB-aligned at all** (4 KB), which earlier verification missed by
  only ever inspecting arm64. JNA 5.13.0 → 5.19.1 aligns every ABI and carries the fix for
  the SIGSEGV JNA hit on Android 15 (java-native-access/jna#1618, #1647).
- **x86_64 is no longer shipped in release.** Every phone this app is for is arm64;
  x86_64 reached emulators and some Chromebooks, and Chromebooks can run ARM through
  translation. Debug still builds it, because an emulator is where a second test family
  member comes from.

**The warning is expected to survive both changes.** JNA 5.19.1 is built by the same GCC
4.9 toolchain from 2015 with no NDK marker at all — its AAR still ships `mips` and
`mips64`, ABIs the NDK deleted in 2018 — so Play will still see "compiled using an older
Android NDK version" on the arm64 copy. Clearing it means building `jnidispatch` from
source against NDK r28+, or leaving JNA behind entirely. Both are recorded in
`PROJECT_STATUS.md` rather than rushed.

Also checked and *not* the problem: SQLCipher 4.18.0 — the newest release — is still built
with NDK r25c, so upgrading it would change nothing. CameraX 1.6.2 and graphics-path 1.1.0
would move two libraries from r25c to r27, but neither was flagged.

### The map

People close enough that their pins would cover each other are grouped into one bubble.
It is smaller now than when it first shipped: the faces overlap by a third, and spread
apart when tapped. The bubble is explained once, on the first one a device ever draws,
and never again.

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

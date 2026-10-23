# Release Notes

The "What's new" text that goes in the Play Console, kept here so it is written
deliberately rather than in the upload dialog, and so the reasoning behind a release
survives after the field itself has been overwritten by the next one.

Play's field is **500 characters**, counted per language. The block marked *Play copy* in
each entry fits; everything under it is for us.

---

## 1.15.6 (47) — Local Wi-Fi security, presence for big families, security log

Builds on 1.15.5 (46). Contains #49 (catch-up on a stale roster), #50 (4.1 authenticated LAN),
#53 + #54 (4.2 presence under the group topic, with the retained-copy fix), #56 (sudden
disconnect shows as offline), #58 + #59 + #60 (Phase 5 cleanup and tests), #62 (security log
with Send report) and #63 (catch-up gives up on a stranger for 24 h). Not included: #64
(inbound queues, needs a two-phone test) and #67 (R8 shrinking, checklist unfinished); each
ships on its own later.

Wire format: **changes. `PROTOCOL_VERSION` goes to 5**; phones on 4 and earlier keep working:
- Presence goes to both the old and the group topic until every member who counts is on 5
  (30-day rule); only then does a phone move to the group topic and two subscriptions.
- Online presence gains an optional signed `sessionStart`; presence padding goes from 640 to
  768 bytes. Older phones ignore both.
- The LAN channel is now authenticated. Phones on 1.15.5 or earlier aren't found on the LAN, so
  a mixed pair talks through the broker until both are updated.

Database: unchanged (11).

**Crash fix, found 2026-10-22 on 1.15.5:** the app aborted with "JNI ERROR: global reference
table overflow (max=102400)" in JNA's `Native.registerMethod`, usually as it was opened (seen on
the S9 as a hang at start-up). Every LocationService start (app open, heartbeat, watchdog) built
a key store, which built a new libsodium binding, and each binding keeps JNI global references
forever. 1.15.6 has one binding per process (`crypto/Sodium.kt`, from #58), so the table no longer
grows.

Checked on 2026-10-21: full unit suite on main; the debug build installed over 1.13.6-debug on
the Galaxy S9 kept its family and identity, connected to the broker with 2 subscriptions, and the
security log and Send report worked. **Not run on a device:** anything between two phones
(chat, location, presence, sudden disconnect, LAN). The PC had too little free memory for an
emulator; do a two-phone check before promoting past internal testing.

### Play copy

```
Security and reliability update.
• Fixes the app freezing or closing on start after running for a day or so
• Phones on the same Wi-Fi now prove who they are before talking directly
• Once everyone updates, big families see every member online, not just the first ten
• If a phone dies suddenly, the family sees it go offline
• New Settings → Security log: see what your phone refused, and send a report if you want
• A phone that missed a new member catches up on its own
```

---

## 1.15.5 (46) — Authenticated joins, signed history, key rotation, replay protection

Builds on 1.15.4 (45). Contains everything open on 2026-10-16: #27 (2.1 authenticated join),
#40 (2.2b client ID per install), #38 (3.2 author-signed history), #42 (3.3 group-key rotation),
#43 (3.4 replay window) and #44 (top bar title wrap).

Wire format: **changes. `PROTOCOL_VERSION` goes to 4**; phones on 3 (1.15.4, current iOS) keep
working in both directions:
- Join v2 is negotiated by the invite, and v1 requests are still accepted (marked "Unverified").
- To peers on 3, envelopes stay unsequenced and refresh requests and acks stay plaintext.
- Key rotation and the dropping of unsigned history switch on only once every member advertises 4,
  so they stay off while anyone in the family is on 1.15.4 or iOS.

Database: **9 → 10 → 11**, two migrations. Upgrade from 1.15.4 checked on an emulator on 2026-10-16:
roster, names and colours kept, and a protocol-3 member isn't flagged "needs update". The first launch
after the update froze once ("isn't responding"); the trace showed the emulator's renderer stalled
while the app ran uncompiled, and later launches were clean. Watch for it on a real phone.

**Not yet run on a device:** 3.3 rotation and 3.4 replay protection live between two protocol-4
phones (joint test T7); they stay off until iOS advertises 4.

### Play copy

```
Security update.
• Joining a family now checks a one-time invite code, so no one else can slip in
• Messages and location history prove who wrote them
• Once every phone is updated, removing someone changes the family's key
• Between updated phones, old messages replayed by someone else are ignored
• Each install gets its own connection, so two phones can't knock each other offline
• Long family names wrap instead of being cut off
```

---

## 1.15.4 (45) — Security and reliability fixes

Builds on 1.15.3 (44). Contains #17–#35: the Android fixes from the 2026-10-11 code review
(Phase 1, plus 2.2, 2.3 and 3.1), and two efficiency changes. **#27 (2.1) is not included**; it is
waiting on its two-phone test.

Wire format: **changes, all additive.** `PROTOCOL_VERSION` stays 3, and phones on 1.15.3 work
with this release in both directions.
- Presence gains an optional signed `deviceId`, and its padding grows from 512 to 640 bytes.
- Members sign their own profile (`profileSignature`), and subscribers sign confirmations
  (`subscriptionSignature`).
- Receive rules are stricter:
  - A location is accepted only from the member it describes.
  - Group chat is no longer re-broadcast during replication.
  - A phone treats itself as removed only on a tombstone, not because it's missing from a list.

One known effect: a 1.15.3 phone doesn't sign profiles. A rename it makes still arrives directly
from that phone, but other phones can't pass it on.

Database: **8 → 9**, one migration.

**Not yet run on a device as a combined build.** Check with two phones:
1. **Upgrade.** Install 44, create a family and send some chat, then install 45 over it. It
   launches, and the history is still there.
2. **Second phone.** Restore the same recovery phrase on a second phone. Both phones show the
   notice that this identity is on two phones.
3. **Profile edits.** Rename yourself and change your colour on A. Both show on B, including
   after B force-stops and reopens.
4. **Crash alert.** Fire the alert from the debug card with B offline, then bring B online. B
   still receives it.
5. **Recovery phrase.** Settings → recovery phrase asks for the PIN or fingerprint first.

### Play copy

```
Security and reliability update.
• Crash alerts keep sending until each family member's phone confirms them
• Viewing your recovery phrase now asks for your PIN or fingerprint
• You're told if your account is signed in on a second phone
• A storage problem can no longer erase your keys or history
• Fixes a blank invite screen, and says why a backup export failed
• Uses less data and battery in the background
```

### What changed, and why

**Crash alerts could be lost.** An alert was sent once. A phone that was offline at that moment
never got it. Alerts are now stored and re-sent until each member's phone confirms receipt
(#23).

**The recovery phrase was shown to whoever held the phone.** The phrase is the whole identity.
Showing it now asks for the device PIN or fingerprint first (#21).

**One identity, two phones, nobody told.** If someone restored your phrase on another phone,
nothing showed it. Presence now carries a signed device ID. The family sees a notice when one
member appears on two devices within ten minutes, and so do you when it's your own identity
(#31).

**Anyone could rewrite parts of someone else's profile.** Fields outside the signed family
record, such as name, photo, colour and subscriptions, were taken from whoever sent them. They
are now taken only from their author, or with the author's newer signature. When added, and by
whom, can't be changed, and an existing file key can't be replaced (#35).

**Narrower trust elsewhere:**
- A location is accepted only from the member it belongs to (#19).
- Replies an outsider can trigger are rate-limited (#25).
- The public legacy file key is refused in families that have a group key (#24).
- Shared secrets are cached by member and key, not by a claimed name alone (#29).
- A phone leaves its family only on a tombstone, never because a list arrived without it (#28).

**A storage error could wipe everything.** A failure opening the encrypted database or key
store was treated as corruption, and the data was erased. Errors are now surfaced and nothing is
deleted (#18).

**Smaller fixes:**
- The invite screen could come up blank (#17).
- A backup export failure now says why: no family, or no recovery phrase (#22).
- Phase 1 correctness fixes for the vault, a few races, LAN, avatars, starting at boot, and the
  location outbox (#26).
- Sensitive operations clean up what they leave in memory and on disk (#33).

**Efficiency:**
- Every group chat message used to be re-broadcast during replication, multiplying traffic
  with each member. That has stopped (#20).
- Sending waits for acknowledgements instead of polling for them, so the phone wakes less
  (#32).

---

## 1.15.3 (44) — Leaving and coming back

Builds on 1.15.2 (43), the wildcard-subscription fix. If 42 never went to production, ship this
instead and use the *Play copy (if 1.15.1 never shipped)* block, adding a line for the 1.15.2 fix.

Wire format: **changes.** `FamilyMember.joinEpoch` and `GroupDefinition.removalProofs`, both
optional; tombstones gain a `"<memberId>#<n>"` form. `PROTOCOL_VERSION` stays 3. Everyday traffic
works with older phones in both directions. A phone before 1.15.3 rejects a member being let back
in after leaving, so **update every phone in the family before anyone who left rejoins.**

**Not yet run on a device.** Check with three phones (A creator, B, C):
1. C leaves while online, restores from the phrase, scans A's invite, and A approves. C shows on
   A *and* B, and is still there after B force-stops and reopens.
2. With B in airplane mode, A removes C. Turn B back on. B drops C within a few minutes, even if
   A goes offline as soon as B reconnects.
3. Put C in airplane mode with Wi-Fi off and tap Leave Family. The offline warning appears;
   Cancel leaves C exactly as it was, still sharing.

### Play copy

```
If you leave your family and later restore from your 12-word recovery phrase, you can now be
let back in. Removing someone, or leaving, now reaches every phone in the family even if some
were offline at the time. Leaving while offline now warns you first, because your family
can't be told once the phone is erased.
```

### Play copy (if 1.15.1 never shipped)

```
Restoring from your 12-word recovery phrase now brings you back into your family, even if
you'd left it. Removals and departures reach every phone, even ones that were offline.
Phones on the same WiFi stay connected directly. Leaving while offline warns you first.
Also fixes a case where a join request could be approved for the wrong phone, and improves
crash detection.
```

### What changed, and why

**Leaving was forever.** Leaving recorded the member ID as removed, permanently. That was
fine when rejoining meant a new recovery phrase and therefore a new ID, but since 1.15.1
restoring brings back the *same* ID. The inviter's phone added them back; every other phone
saw a removed member reappear, refused the update, and dropped them again at the next sync.
So the 1.15.1 restore fix only worked for phones that had never left. Removals now end a
*membership*, and someone let back in starts the next one.

**A removal only counted if you heard it first-hand.** Any other phone repeating it looked like
a forgery and was refused. A phone that missed the original kept the removed member, and
someone who left was stuck on those phones for good, because the only phone that could vouch
for the departure had erased itself. Removals now carry a signed proof from the creator or
from the person who left, so any member can pass them on.

**Leaving offline was silent.** The departure was sent best effort, then the keys were erased,
so offline it was never sent at all. The phone now checks it can reach the family (relay, or a
member on the same Wi-Fi) before erasing anything, and says what happens if you go ahead.

Not fixed: a removal made by a majority vote (when the creator is gone) still can't be
relayed. Its votes are tied to one version of the family. That case is rare and still
reaches every phone through the original broadcast.

---

## 1.15.2 (43) — Family sync, files and online status over the internet

Wire format: unchanged. Only what each phone subscribes to changes, so phones on 1.15.1 and
earlier keep working with it in both directions. `PROTOCOL_VERSION` stays 3.

**Not yet run on a device.** Check before uploading, with two phones on 1.15.2 and WiFi off on
both (so the LAN path can't hide anything): logcat shows `Subscribed to N topics`, never
`subscribe incomplete`; each phone shows the other going online and offline; a group change
(rename, colour) reaches the other phone; a shared file arrives.

### Play copy

```
Fixes family updates, shared files, the vault and online status not arriving when phones
are on different networks. Your phone now listens for everything it needs from the family
in a way that fits within the limits of the relay service.
```

### What changed, and why it's worth a release

**Most of the relay was silently off.** The relay (EMQX Serverless) accepts at most 10
subscriptions per connection and refuses the rest without any error. Each phone asked for 21
of its own plus 2 per family member, so only the first 10 were granted. Refused on every
phone: group sync and removal votes, group acks, shared files, the vault, and every other
member's online status. Location kept working (it is in the first 10), and since 1.15.1 the
WiFi path covers phones at home, which is why this went unnoticed.
- A phone now subscribes to two wildcards (everything addressed to it, everything for its
  family) plus each member's online status: 2 + one per member, enough for a family of nine.
- Phones keep their relay session between connections, and it still holds the old
  subscriptions, which use up the 10. They are removed first; if anything is still refused,
  the next connection starts a fresh session once.
- The old shared location topic is no longer subscribed; nothing has published to it since
  per-recipient location inboxes.

Found while testing the iOS reconnect path against the live relay, 2026-10-11.

---

## 1.15.1 (42) — Restoring from the recovery phrase works

Supersedes 1.15.0 (40/41). Includes the crash-detection rework merged after 41.

Wire format: one new optional field (`JoinRequest.signature`); `PROTOCOL_VERSION` stays 3.
Older phones ignore it and keep working in both directions.

**Not yet run on a device.** Builds and passes the unit tests. Check before uploading, with two
phones: restore a phone that is still in the family (should rejoin with no prompt on the other
phone); leave and restore (should prompt); and a normal new join. On the same WiFi: each phone
shows the other as Local within seconds, and again within a minute of waking after ~10 minutes
locked.

### Play copy

```
Restoring from your 12-word recovery phrase now brings you back into your family: scan an
invite from anyone in the family and you're let straight back in. Phones on the same WiFi
stay connected to each other directly, even when the internet is down. Also fixes a case
where a join request could be approved for the wrong phone, and improves crash detection.
```

### What changed, and why it's worth a release

**Restore never worked.** Entering the recovery phrase restored the identity, marked setup
complete, and promised the family would sync once others came online. It couldn't: a phone
with no stored family never connects to the relay, and drops every group update it is sent.
Restored phones showed an empty family forever, which means the recovery phrase could not
replace a lost phone.
- Restore now continues to Join Family: scan any member's invite, and the join request goes
  out under the restored identity, signed with its key.
- The inviting phone lets a member back in automatically when the request comes from that
  member's own keys (`RejoinPolicy`). Someone who left, or a newcomer, still needs approval.

**Security fix.** Approving a join request that named an existing member sent the family
definition, file key included, encrypted to whatever key the request carried. Someone with a
member's public key could have asked to "join" as that person and, if the prompt was
approved, read the family. Such requests are now refused outright and never shown.

**Same-WiFi connections dropped and never came back** ("Local peers: 0" on a shared WiFi).
One failed send to a phone that was dozing deleted its address, and Android's discovery
reports a phone only once, so it stayed lost until an app restarted.
- A lost peer is looked up again after a failure, waiting 15 s, then longer, up to 5 minutes.
- The WiFi route starts immediately instead of after the relay's ~2 minutes of connection
  attempts when the internet is down.
- Discovery retries when it fails to start, restarts when WiFi reconnects, and recognises a
  phone whose listing Android renamed ("… (2)").
- The diagnostics no longer show a phone as offline because the relay said so while it is
  reachable on the WiFi.

**Crash detection** — the detection rule was separated out and tested against recorded motion
and 122 real lab crashes. A GPS speed older than 90 seconds no longer counts toward the 25 mph
guard, so a phone dropped after parking can't trigger an alert from a stale highway speed.

---

## 1.15.0 (40) — Porch Light, private messages, and a lighter connection

Built from the commits after `0c67128` + the version bump. Supersedes 1.14.0 (39).

Wire format: unchanged (`PROTOCOL_VERSION` stays 3). Phones on 1.14.0 and earlier keep
working with this build in both directions. Two behaviours differ between versions, neither
breaking: older builds still back up private messages to every phone, and this build drops
those copies on arrival unless it is one of the two participants; and a colour picked on this
build is stored as an ordinary hue, which older builds draw as a close match.

**Not yet run on a device.** Every change below builds and passes the unit tests (563) and
lint (0 errors), but none has been seen on a phone. Check before uploading: the map with
colour-blind patterns on and off, the Family and Chat lists, Settings, both themes, and a
private conversation between two phones.

### Play copy

```
A fresh look: new colours and clearer type, and each family member keeps one colour
everywhere, with an optional colour-blind pattern mode in Settings > Appearance. Send
private messages to one person alongside the family chat. Location keeps updating more
reliably in the background and uses much less data. Fixes a chat badge that could count
messages that weren't there.
```

### What changed, and why it's worth a release

**Broker traffic** — the family was exceeding the broker's 1 GB monthly allowance with 6–12
people. The largest cause: on every reconnect the app re-subscribed to its topics, and MQTT
makes the broker re-send every matching retained message, including the ~175 KB vault
container, many times a day per phone.
- Reconnects skip re-subscribing when the broker kept the session and the topic set is
  unchanged (`MqttTransport.restoreSubscriptions`).
- MQTT keepalive 30 s → 120 s, so a short CPU sleep no longer costs a full reconnect. An
  abruptly dead phone now shows offline after ~3 minutes instead of ~45 seconds.
- Fixes that haven't moved (< 30 m, under 5 minutes old) are no longer published.
- Replication: data announcements every 30 min (was 5), full sync at most every 15 min (was
  1), a member's history requested from that member only, duplicate requests suppressed.

**Location reliability**
- Opening the app sent the service a start command that cancelled its movement, reconnect
  and heartbeat jobs while GPS stayed registered, so tracking degraded quietly until Android
  restarted the service. A running session is now left alone.
- Motion detection ("Physical activity") was started without the permission, which the app
  never requested. On Android 10+ it has therefore never run: the service relied on GPS
  speed, and **crash detection never armed**, since it arms only while motion detection
  reports being in a vehicle. Setup now asks for the permission (a "Driving & Movement"
  step after location), and the service checks for it and skips cleanly when it's denied.
  Families who finished setup on an earlier version are not asked again; they can grant
  it in Android Settings → Apps → Jibaro Family Safety → Permissions → Physical activity.
- The reduced-motion check used an API that is only public from Android 13; on Android
  8–12 it reached a hidden method, which a device or update could block and crash the tab
  bar. Now uses `ValueAnimator.areAnimatorsEnabled()`.

**Chat**
- Private one-to-one messages: the Chat tab lists the family chat and private
  conversations; each member has a Message button; notifications open the exact thread.
  Private messages are stored only on their two participants' phones.
- The Chat badge could show a count with nothing behind it: on a cold start it was built
  before the group loaded and counted every unread row in the database. Messages synced in
  while a chat is open are now marked read, and chats from a previous family no longer
  spread between phones.
- A message from someone in both the family chat and a private chat no longer replaces the
  other's notification. A chat left open in the background no longer swallows notifications.
- Profile photos on the chat screens.

**Design (Porch Light — see `DESIGN.md`)**
- Pine-tinted greys replace the cool blue-grey defaults; text field and button borders now
  meet the 3:1 contrast minimum.
- Bundled typefaces (Bitter for headings, Atkinson Hyperlegible Next for text, Atkinson
  Hyperlegible Mono for codes), loaded from the app itself rather than downloaded.
- Corner radius by role; the floating button's metal sheen removed.
- Twelve person colours at equal perceived brightness, the same for a person everywhere
  (avatar, map pin, history trail, chat name). Existing choices map to the nearest colour;
  two pairs of old presets merge (blue/indigo → Periwinkle, magenta/pink → Rose), and
  members who never chose a colour get a new automatic one.
- Colour-blind patterns (Settings → Appearance), per phone.
- Family and Chat drawn as lists instead of stacked cards.

**Smaller fixes** — notifications check their permission on Android 13+; the camera is
declared optional in the app's own manifest; QR scanning opts in to CameraX's experimental
image API explicitly.

---

## 1.14.0 (39) — the family subscription goes live

Supersedes 1.13.6 (35) and its own three predecessors on this branch: 36 (uploaded only to
register a binary with Play Console, billing fully inert), 37 (crashed on every launch past
onboarding — see below, never distributed), 38 (fixed that crash, still inert). 39 is the
first build where the paywall can actually gate anything.

Adds the billing/entitlement machinery (`billing/`): trial and subscription state on
`GroupDefinition`, a Play Billing wrapper, grant-code redemption, and gates on Chat,
History and Files. Live location sharing, alerts, crash detection and the Vault are never
gated. **Every family that exists as of this release — including every real family already
using the app — is grandfathered permanently**: `PAYWALL_INTRODUCED_AT_EPOCH_MS` is fixed
to the instant this was built, and the rule is `createdAtEpochMs < that instant`. Only a
family created from this release onward gets the 60-day free trial, then needs a
subscription ($4/month) or a developer-issued grant code to keep Chat, History and Files.

The 37 crash: `BillingManager`'s constructor called `PendingPurchasesParams.newBuilder()
.build()` with neither toggle set. Play Billing Library 8.0.0 throws
`IllegalArgumentException("Pending purchases for one-time products must be supported.")` in
that case, undocumented as far as we found — caught from a real crash log, not the
library's release notes. Fixed by calling `enableOneTimeProducts()` unconditionally, even
though this app sells only the one subscription.

### Play copy

```
Adds an optional family membership ($4/month after a 60-day free trial) for Chat, Location
History, and File Sharing. Live location sharing and safety alerts are always free.
Existing families are unaffected.
```

---

## 1.13.6 (35) — a real pause switch, and privacy text that matches the app

Built from the commits after `582ec70` + the version bump. Supersedes 1.13.5 (34).

Includes everything in 1.13.5 (34), including majority removal, which is the one additive
wire change (see that entry). Nothing else on the wire changed.

### Play copy

```
Location updates no longer go quiet for hours: a stalled relay connection can't block
reconnecting. A majority of your family can now remove a member without the creator.
"Share my location" in Settings really pauses sharing, the location permission screen
lists every use of location, and the Privacy screen and policy match how the app works.
```

### What changed, and why it's worth a release

- **Share my location switch** did nothing before. It now saves its state, stops the
  location service when off, and every restart path (boot, alarms, watchdog) honors it.
  Place, speed and crash alerts from that phone are off while paused; chat still works.
- **Background-location explanation** now names every use: sharing, 30-day history,
  place and speed alerts, and crash detection.
- **Privacy screen** corrected (30-day history, chat is encrypted, outside services
  listed) and links to the full policy.
- **Privacy policy** now discloses OpenStreetMap map tiles and OSRM drive-time
  estimates, the crash-detection sensor, alarms and full-screen alerts, and the pause.

---

## 1.13.5 (34) — location updates that keep flowing in the background

Built from `b3dd517`..`59bf9fc` + the version bump. Supersedes 1.13.4 (33).

Wire format: one additive change (majority removal, below). Devices on 1.13.4 or earlier
keep working with this build, but they reject a majority-removal update, so that feature
only takes effect once everyone has updated.

### Play copy

```
Fixes location updates going quiet for hours until the app was reopened. A stalled
connection to the relay can no longer block reconnecting, and phones with extra
background limits (Samsung, Xiaomi, Oppo and others) now show step-by-step settings
help in Settings.
```

### What changed, and why it's worth a release

- **Stuck relay connection** — a connect attempt that never called back left the
  transport at "Connecting" and held the connection lock forever, so the 5-minute
  heartbeat, backoff and network-restored reconnects all queued behind it until the
  process died. Every attempt is now time-bounded and aborted, and the heartbeat
  force-aborts a Connecting state too old to be live.
- **A majority of the family can remove a member without the creator** (`1571763`). Until
  now only the creator could, and creator authority could not move, so a family whose
  creator's device was lost could never remove anyone. Members now vote; once a strict
  majority of the others (excluding the person being removed) has signed, the removal goes
  through, and if the removed member was the creator the longest-standing remaining member
  becomes creator automatically. The creator can also hand the role to someone else. Renaming
  the group stays creator-only. New `removal_vote` topic and a `quorumSignatures` field; see
  `ios/IOS_PORT_SPEC.md` section 7.5.
- **Silent group-load failure** now logs an error instead of looking like "no group".
- **Unsent-location backlog** capped at the newest 100 per member.
- **Rate limiter** uses a monotonic clock, so a clock adjustment can't lock it out.
- **Activity recognition** can retry after a failed registration.
- **Settings** permanently shows OEM background-limit guidance on affected phones.

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
consequence in view — see `app/CODE_REVIEW_2026-10-11.md` §6 (the former `SECURITY_REVIEW.md`), "Decision, 2026-08-21". Not a surprise;
recorded here because a release is when a decision stops being about code.

### Still true after this release

A family created before 1.12.0 carries no `fileEncryptionKey`, and nothing in this build
backfills one. Until such a family is recreated, its documents fall back to a key derived
from public inputs and its manifest goes out unsigned — so most of what this release is
for does not reach it. The Security screen says which kind of family this is.

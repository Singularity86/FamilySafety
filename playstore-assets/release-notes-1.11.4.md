# Release notes — 1.11.4 (versionCode 17)

Covers everything since 1.11.1 (versionCode 13, commit `cea5fbf`).

Version history for this release line:

| Code | Name | Fate |
|---|---|---|
| 14 | 1.11.2 | Burned on an upload Play would not accept |
| 15 | 1.11.2 | Uploaded to Play; superseded by 16 |
| 16 | 1.11.3 | Uploaded to Play |
| 17 | 1.11.4 | Current — adds the status and diagnostics work below |

14 and 15 were byte-identical in app content, so they shared a versionName.
Every code that reached Play with different content gets its own name, so 17
ships as 1.11.4 rather than reusing 1.11.3.

Sections below are cumulative: users coming from 1.11.2 get everything here,
users already on 1.11.3 get only the "Status reporting and diagnostics" items.

## Play Console "What's new"

Paste as-is. 389 characters; the per-language limit is 500. Scoped to what is
new in 17, since 16 already shipped the map, invite and battery fixes.

```
Clearer family status.

• Members who are sharing normally no longer look stuck or "waiting"
• Members who are offline now say so plainly
• The Security screen shows this device's own ID, key and app version
• New "Copy diagnostics" button gathers everything needed to report a problem, without including any location data
```

## Full changelog

### Fixed

- **Map showed the world two or three times.** At low zoom levels — including the
  default view before any family member's location has arrived — osmdroid tiled the
  world edge-to-edge, so duplicate continents appeared across and down the screen.
  Repetition is off, panning is clamped to the real world bounds, and the minimum
  zoom now guarantees a single world fills the screen. (`420563f`)
- **Settings reported version 1.0.0** regardless of the actual build. It now reads
  from `BuildConfig.VERSION_NAME`, so it cannot go stale again as versionName is
  bumped. (`953b2a5`)

### Improved

- **"Learn more" on status chips.** The encryption, network-routing, and family-list
  sync chips already showed a brief status popup; each state now offers an optional
  longer explanation in plain language, so it is clear what end-to-end encryption,
  local vs. relay routing, and sync actually mean. (`713037c`)
- **Two-line brand mark** — "Jibaro:" / "Family Safety" — on the Welcome screen and
  the shared top bar across Map, Members, Files, Chat, and Settings. Jibaro is the
  umbrella brand with room for future sibling products; layout and alignment are
  unchanged. (`f710e70`)

### Under the hood

- **Targets Android 16 (API 36).** Play Console flagged the app for rejection after
  Aug 30, 2026 without this, so the app is compliant ahead of the deadline.
  (`8bda871`)

## Late additions (versionCode 16)

- **Invite codes with whitespace no longer fail.** `Base64.getDecoder()` is the
  strict RFC 4648 decoder and throws on any whitespace, so a code copy-pasted
  from SMS or line-wrapped by a share target was rejected as invalid even when
  it was correct. Whitespace is stripped before decoding. (`bf21dd7`)
- **Join-request notifications open the invite screen.** The notification pointed
  at the Family tab, which has no approve/reject UI. It now lands directly on the
  invite screen, where the pending request is waiting above the QR code. The same
  change fixes deep links being swallowed on repeat taps, which also affected
  chat notifications from a repeat sender. (`a8574dd`)
- **Off-screen tabs are no longer kept alive.** `HorizontalPager` was composing all
  five tabs at all times, so the Map tab's GPS listener, tile loading, and marker
  rebuilds ran continuously in the background while the user was on another tab.
  Only the adjacent tab is kept warm now. (`76de126`)

## Status reporting and diagnostics (versionCode 17)

- **Members who were fine looked broken.** `ConnectionState.Unknown` means "the route
  is not pinned down yet", not "something is wrong", but it was rendered as the literal
  word "Waiting" and appended to the status line. A member whose location updates were
  arriving and decrypting correctly read "Seen just now · Waiting". This directly cost a
  debugging session chasing a transport fault that did not exist. The route is now
  omitted when unknown rather than filled with a placeholder. (`aa34bcb`)
- **Offline members now say "Offline".** Incoming presence only ever updated presence
  status, never connection state — that was left to LAN discovery alone. So a member the
  broker had already reported as offline still showed as unknown. Presence now sets the
  connection state too, without downgrading a peer that is reachable on the LAN.
  (`79bb50f`)
- **Security screen shows this device.** A new "This Device" card gives the member ID in
  full with a copy button, the signing key in the same grouped form used for everyone
  else, and the app version. There was previously no way to learn your own member ID
  from inside the app, which made cross-device debugging guesswork.
- **"Copy diagnostics" button** puts one pasteable snapshot on the clipboard: identity,
  network and relay state, group version and state hash, and per-member route, last-seen
  and decrypt health. Deliberately excludes coordinates and message contents so it is
  safe to share when asking for help.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.11.4 / 17 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |
| Signing key | upload key, `CN=FamilySafety, O=Courage On Purpose, C=US` |

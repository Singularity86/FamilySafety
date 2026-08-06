# Release notes — 1.11.2 (versionCode 16)

Covers everything since 1.11.1 (versionCode 13, commit `cea5fbf`).

Version code history for this release:

| Code | Fate |
|---|---|
| 14 | Burned on an upload Play would not accept |
| 15 | Uploaded to Play; superseded before wide rollout |
| 16 | Current — adds the three fixes in "Late additions" below |

The app is unchanged between 14 and 15, hence the shared versionName. 16 adds
real fixes on top; see the open question on versionName at the bottom.

## Play Console "What's new"

Paste as-is. 447 characters; the per-language limit is 500.

```
Map fixes, smoother invites, and better battery.

• The map no longer draws repeated copies of the world when zoomed out
• Invite codes with extra spaces or line breaks now work
• Tapping a join request notification takes you straight to the approval screen
• Background tabs no longer stay active, saving battery
• Tap the encryption, network, or sync chips to learn what each means
• Settings now shows the real app version
• Updated for Android 16
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

## Open question — versionName

versionCode 15 already went to Play as versionName **1.11.2**. Code 16 carries
three additional user-facing fixes but currently reports the same 1.11.2, so two
materially different builds share one user-visible version. Bumping to **1.11.3**
would be more honest; left as-is pending a decision.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.11.2 / 16 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |
| Signing key | upload key, `CN=FamilySafety, O=Courage On Purpose, C=US` |

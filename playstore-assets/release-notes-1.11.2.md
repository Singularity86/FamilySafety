# Release notes — 1.11.2 (versionCode 15)

Covers everything since 1.11.1 (versionCode 13, commit `cea5fbf`).

versionCode 14 was burned on an upload Play would not accept, so this release
ships as 15. The app itself is unchanged between the two, hence the shared
versionName.

## Play Console "What's new"

Paste as-is. 330 characters; the per-language limit is 500.

```
Map fixes and clearer status info.

• The map no longer draws repeated copies of the world when zoomed out
• Tap the encryption, network, or sync chips for a plain-language explanation of what each one means
• Settings now shows the real app version
• New "Jibaro: Family Safety" title
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

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.11.2 / 15 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |
| Signing key | upload key, `CN=FamilySafety, O=Courage On Purpose, C=US` |

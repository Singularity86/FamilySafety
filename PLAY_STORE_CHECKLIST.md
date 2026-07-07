# Play Store Launch Checklist

Status of each item as of 2026-07-06. Code-side items are DONE; the rest need
accounts/infrastructure only you can create.

## Done (in the repo)

- [x] `applicationId` = `jibaro.spacepirate.love` (Play "package name"; permanent)
- [x] Upload keystore + release signing (`keystore/` — gitignored; **BACK IT UP off this machine**)
- [x] Signed AAB builds: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
- [x] `targetSdk 35` (meets current Play target-API requirement)
- [x] Background-location prominent disclosure wording follows Google's required formula (PermissionOnboardingFlow — don't reword casually)
- [x] Broker credential support (`keystore/mqtt.properties` → username/password)
- [x] Store assets: `playstore-assets/icon-512.png`, `playstore-assets/feature-graphic-1024x500.png`
- [x] Privacy policy text: `PRIVACY_POLICY.md` (needs public hosting — see below)

## Blockers you must do (in order)

1. **Broker: DONE** — EMQX Cloud serverless at
   `ssl://r161feb1.ala.us-east-1.emqxsl.com:8883` (all environments). Remaining:
   create a username/password in the EMQX console (Access Control →
   Authentication), put it in `keystore/mqtt.properties`, rebuild, reinstall on
   all family devices together.
2. **Host the privacy policy** at a public URL (e.g. courageonpurpose.org/familysafety/privacy).
   Required field; app is rejected without it.
3. **Play developer account** (play.google.com/console, $25 one-time + ID verification).
   ⚠ New *personal* accounts must run a closed test with **12+ testers for 14
   continuous days** before production access. An organization account (needs
   D-U-N-S) skips this.
4. **Create the app** in the Console and upload the AAB to Internal testing first.

## Console declarations (copy-paste ready)

- **Background location declaration**: core feature = "real-time family location
  sharing chosen and configured by the user; members expect to see each other's
  location while the app is closed." You'll likely need a short screen-recording
  of: onboarding → the in-app disclosure card → the system permission prompt.
- **Foreground service (location) declaration**: "continuous location sharing
  with the user's family group, started explicitly by the user."
- **Full-screen intent** (crash alerts): declare "time-sensitive safety alert
  (vehicle crash detection)".
- **Exact alarms** (`SCHEDULE_EXACT_ALARM`): "watchdog to keep user-initiated
  safety location sharing alive."
- **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** is the riskiest permission for
  review. Justification: safety app whose core function breaks under OEM battery
  management. If review rejects it, remove the permission and rely on the
  OemBatteryHelper guidance flow instead.

## Data safety form (answers)

- Collects: Location (precise, background) — YES, **encrypted in transit**, user data
  **not collected by developer** (E2EE, device-to-device; you never receive it).
  Play's form distinguishes "collected" (leaves the device to *you*) — it does not;
  it's shared device-to-device. Declare: Location shared with other app users
  (family members), end-to-end encrypted, required for core functionality, user
  can request deletion (leave family / uninstall).
- Messages: same treatment (shared E2EE with family, not collected by developer).
- Photos (avatar): shared with family only, E2EE.
- No ads SDKs, no analytics, no data sold.

## Store listing

- App name: currently "Family Safety" — ⚠ collides with **Microsoft Family
  Safety**; consider a distinct name (listing title, `app_name` string) to avoid
  impersonation flags and to be findable.
- Target audience: **18+ account holders** (parents). Do NOT opt into
  child-directed / Designed for Families — a kids-targeted location app triggers
  far stricter rules.
- Need: short description (80 chars), full description (4000), 2–8 phone
  screenshots (capture via `adb exec-out screencap -p > s1.png` once UI is final).
- Content rating questionnaire: no violence/gambling/etc.; discloses location
  sharing.

## Release path

Internal testing (instant, up to 100 testers) → Closed testing (the 14-day/12-tester
gate if personal account) → Production. Expect the first background-location
review to take days and possibly one rejection/resubmit cycle on the
disclosure video.

## Before each store build

- All family/tester devices must be on compatible wire-format versions.
- `./gradlew testDebugUnitTest` green.
- Bump `versionCode` (Play requires strictly increasing).
- Test the actual **release** AAB on a device (`bundletool` or Play internal
  testing) — release packaging exercises JNA/lazysodium differently than debug.

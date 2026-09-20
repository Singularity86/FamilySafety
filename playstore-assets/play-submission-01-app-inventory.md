# Play submission 01 - App inventory (factual foundation)

Package: `jibaro.spacepirate.love` (`app/build.gradle.kts:41`). Version at time of writing: 1.13.5 / versionCode 34 (bump commit `582ec70`, 2026-09-19). **Sections 6-10 were written against 1.13.5.** Version 1.13.6 / 35 later changed: the background-location disclosure text (section 8 now lists sharing, 30-day history, place and speed alerts and crash detection), the Settings "Share my location" switch (section 9 item 1 is fixed: it persists via `LocationService.PREF_SHARING_PAUSED` and stops/starts the service), and the in-app Privacy screen and privacy policy (section 10 items fixed). The findings text is otherwise unchanged and describes the pre-fix state.
Everything below was read from the repo on 2026-09-19. Nothing in the app source was modified to produce this document. "Verified" means I read the cited code; "not verified" means I did not or could not.

---

## 1. SDK levels and the target-API requirement

| Item | Value | Evidence |
|---|---|---|
| minSdk | 26 | `app/build.gradle.kts:42` |
| targetSdk | 36 (Android 16) | `app/build.gradle.kts:43` |
| compileSdk | 36 | `app/build.gradle.kts:37` |

**Google's requirement (read 2026-09-19):** "target Android 16 (API level 36) or higher to be submitted to Google Play" as of August 31, 2026, with an extension available to November 1, 2026 (https://support.google.com/googleplay/android-developer/answer/11926878). The July 15, 2026 announcement repeats "all apps to meet the latest target API level requirements by August 31, 2026" without naming the level (https://support.google.com/googleplay/android-developer/answer/17134731).
**Result: we meet it** (targetSdk 36). Note `PLAY_STORE_CHECKLIST.md` still says "targetSdk 35"; that line is stale (commit `8bda871`, 2026-07-22, moved to 36).

Other release facts (from code/git, not re-tested by me): 16 KB page-size alignment commit `1e52602` and JNA ABI commit `ffb7e1b`; release build has `isMinifyEnabled = false` (`app/build.gradle.kts:101`); `allowBackup="false"` (`AndroidManifest.xml:40`).

---

## 2. Permissions (main manifest `app/src/main/AndroidManifest.xml`)

The debug manifest (`app/src/debug/AndroidManifest.xml`) declares no permissions; it only overrides `CrashAlertActivity` to `exported="true"` for debug builds. Release keeps `exported="false"` (`AndroidManifest.xml:~46`). Because `src/debug` is only merged into debug variants, this does not reach the Play build (not verified against a merged release manifest).

| Permission (manifest line) | Used in code? | Where / notes |
|---|---|---|
| INTERNET (6) | Yes | MQTT over TLS (`transport/MqttTransport.kt`), OSM tiles, OSRM routing |
| ACCESS_NETWORK_STATE (7) | Yes | `core/NetworkMonitor.kt` |
| ACCESS_FINE_LOCATION (8) | Yes | `location/LocationService.kt` (HIGH_ACCURACY requests), `main/MapScreen.kt:101-165` |
| ACCESS_COARSE_LOCATION (9) | Yes (requested together with fine) | `PermissionOnboardingFlow.kt:136-141`, `LocationPermissionHelper.kt:20,45` |
| ACCESS_BACKGROUND_LOCATION (11) | Yes | `LocationPermissionHelper.hasAlwaysOnLocationPrerequisites`; requested via Settings trip on API 30+ (`PermissionOnboardingFlow.kt:152-161`) |
| FOREGROUND_SERVICE (12) | Yes | `LocationService` |
| FOREGROUND_SERVICE_LOCATION (13) | Yes | `LocationService`, WorkManager `SystemForegroundService` |
| WAKE_LOCK (14) | Yes | `LocationService.kt:85-88` partial wake lock (60 s timeout) |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (17) | Yes | `onboarding/BatteryOptimizationScreen.kt`, `main/SettingsScreen.kt` "Fix Now" |
| RECEIVE_BOOT_COMPLETED (18) | Yes | `location/BootRecoveryReceiver.kt` |
| CAMERA (19) | Yes | QR invite scanning only (`onboarding/QrScannerScreen.kt:37`, `invite/QrCodeScanner.kt`) |
| POST_NOTIFICATIONS (21) | Yes | onboarding step `PermissionOnboardingFlow.kt:166-175` |
| ACTIVITY_RECOGNITION (23) | **Declared, never requested at runtime** | `location/ActivityRecognitionManager.kt` registers transition updates, but no code requests this runtime permission (grep for `ACTIVITY_RECOGNITION` in `*.kt` returns nothing). On API 29+ it is a runtime permission, so the feature is likely inert unless a user grants it by hand in system settings. `PRIVACY_POLICY.md` lists it as a requested permission. |
| ACCESS_WIFI_STATE (25), CHANGE_WIFI_MULTICAST_STATE (26) | Yes | `group/NsdPermissions.kt`, local avatar sync via NSD |
| NEARBY_WIFI_DEVICES (28-31, `neverForLocation`) | Yes | `avatar/AvatarRepository.kt:180-194`, onboarding step |
| USE_FULL_SCREEN_INTENT (33) | Yes | `crash/CrashDetectionMonitor.kt:108-112` (`CATEGORY_ALARM` + `setFullScreenIntent`). No check of `canUseFullScreenIntent` found (grep). |
| SCHEDULE_EXACT_ALARM (36) | Yes | `location/LocationHeartbeatReceiver.kt` (5-min `setExactAndAllowWhileIdle`), `ServiceWatchdogReceiver.scheduleSoon`; falls back to inexact on `SecurityException` |

Runtime-requested in the first-run flow (`PermissionOnboardingFlow.kt`): location (fine+coarse), background location, notifications, nearby Wi-Fi, battery exemption. Camera is requested when the QR scanner opens. Nothing in the flow requests ACTIVITY_RECOGNITION.

---

## 3. Foreground services

| Service | Declared type | Evidence |
|---|---|---|
| `.location.LocationService` | `location` | `AndroidManifest.xml:67-70` |
| `androidx.work.impl.foreground.SystemForegroundService` (merged, used by `ServiceWatchdogWorker.setForeground`) | `location` | `AndroidManifest.xml:76-79` |

Start paths: user action (onboarding / `MainActivity`), `BootRecoveryReceiver` (boot and package replace), `LocationHeartbeatReceiver` and `ServiceWatchdogReceiver` (alarms), `ServiceWatchdogWorker` (WorkManager, 15 min), and OS restart (`START_STICKY`, `LocationService.kt:243`).
Notification: `LocationService.createNotification` (`LocationService.kt:277-289`): ongoing, channel importance LOW, title "Jibaro Family Safety - Location active", text "Notification required for location sharing to work. Tap to open."

**Observed on a real device (2026-09-19, debug build, after `adb install -r`):** logcat showed `ActivityManager: Foreground service started from background can not have location/camera/microphone access: service jibaro.spacepirate.love.debug/...LocationService` when the service restarted itself right after the package was replaced. GPS fixes still arrived afterwards, so I do not know whether this was a permanent loss of access or a cosmetic warning. This matters for the FGS declaration because several start paths above are background starts. Recommend checking on a release build before recording the declaration video.

Google's FGS declaration page (read 2026-09-19) lists for `TYPE_LOCATION` three use cases: "Background Location Updates: User-initiated location sharing", "Navigation", and "Geofencing", and notes geofencing was removed as an approved use case in the April 15, 2026 announcement. The app's "geofence" alerts are computed in `geofence/GeofenceMonitor.kt` from ordinary location fixes; it does not use the Geofencing API.

---

## 4. Third-party libraries (from `app/build.gradle.kts`, lines ~190-290)

| Library | Version | Touches data or network? |
|---|---|---|
| Eclipse Paho MQTT v3 | 1.2.5 | Yes - the only path for locations, chat, presence, group sync, files |
| play-services-location (FusedLocationProviderClient, ActivityRecognition) | 21.0.1 | Yes - Google Play services supplies device location to the app; not a data sink for us |
| ML Kit barcode-scanning | 17.3.0 | On-device QR decode (bundled model; not verified whether it phones home) |
| CameraX | `cameraxVersion` (see gradle) | On-device |
| ZXing core / zxing-android-embedded | 3.5.2 / 4.3.0 | On-device QR generation |
| osmdroid-android | 6.1.18 | Yes - downloads map tiles from openstreetmap.org (see section 5) |
| NanoHTTPD | 2.3.1 | Local-network HTTP server for avatar transfer (LAN only) |
| Lazysodium + JNA | 5.2.0 / 5.19.1 | Crypto (libsodium); local |
| BouncyCastle bcprov | 1.76 | Crypto; local |
| Room + SQLCipher (`net.zetetic:sqlcipher-android`) | see `roomVersion` / 4.9.0 | Local encrypted DB |
| androidx.security:security-crypto | 1.1.0-alpha06 | Local |
| WorkManager | 2.9.0 | Local scheduling |
| DataStore, Hilt 2.48.1, Timber 5.0.1, Coil 2.6.0, Material 1.11.0 | as listed | Local. Coil could fetch images by URL, but I found no remote-URL image loading (avatars come from the LAN or the encrypted channel) |

**Not present (verified by grep across `app/`):** Firebase, Crashlytics, Analytics, AdMob, Sentry, Bugsnag, Mixpanel, Amplitude, `google-services`, FCM. No ad SDK, no analytics SDK, no crash-reporting SDK.
**Logging:** Timber `DebugTree` is planted only when `BuildConfig.DEBUG` (`FamilySafetyApplication.kt:21-22`), so release builds do not write verbose logs.

---

## 5. Network endpoints and protocols

| Endpoint | Protocol | What is sent | Encrypted end-to-end? | Evidence |
|---|---|---|---|---|
| `r161feb1.ala.us-east-1.emqxsl.com:8883` (EMQX Cloud serverless MQTT broker, developer-operated account) | MQTT over TLS | Ciphertext envelopes: locations, chat, presence, group sync, file chunks, join requests/approvals; plus metadata (client ID `familysafe_{memberId}`, topics `familysafe/{memberId}/...`, timing, sizes - messages are padded) | Payloads yes (libsodium X25519/XSalsa20-Poly1305 + Ed25519 signatures, `crypto/E2EEManager.kt`, `transport/MessageProtocol.kt`). Metadata visible to the broker. | `transport/BrokerConfig.kt` (`EMQX_URL`), `transport/MqttConfig.kt` |
| `https://a.tile.openstreetmap.org/`, `b.`, `c.` | HTTPS | Standard map-tile requests for whatever area the user is viewing: device IP address, User-Agent (= package name), tile x/y/zoom | **No.** Not sent through the E2EE channel. | `main/MapScreen.kt:71-76`, `FamilySafetyApplication.kt:25-41` |
| `https://router.project-osrm.org` (public OSRM demo server) | HTTPS | On the user tapping a drive-time estimate for a member: **precise coordinates of the user and of the other member, in the URL** (`%.6f,%.6f;%.6f,%.6f`), device IP | **No.** | `routing/RoutingService.kt:73-93,105`, called from `main/MainViewModel.kt:229` |
| `http://<lan-host>:<port>/avatar?token=...` | HTTP, LAN only | Avatar image bytes between family devices on the same Wi-Fi, integrity-checked by SHA-256 hash | Plain HTTP on the LAN; the hash is checked against the value in the (signed) group state | `avatar/AvatarRepository.kt:205-235` |
| `https://cash.app/$OERev` | Opens in an external browser/app via `Intent.ACTION_VIEW` | Nothing from the app; user leaves the app | n/a | `main/SettingsScreen.kt:1014` |

There is no push (FCM), no crash-reporting endpoint, and no analytics endpoint in the code.

**Broker credentials:** the MQTT username/password come from `keystore/mqtt.properties` at build time into `BuildConfig` (`BrokerConfig.kt`), so they ship inside the APK/AAB. `SECURITY_REVIEW.md` (2026-08-08, finding F2, "decided again on 2026-08-21: still accepted") documents that they are extractable and that the broker operator is treated as outside the threat model because payloads are E2EE.

---

## 6. Data collected, storage, transit, deletion

| Data | Leaves device? | Where stored | Encrypted in transit | Encrypted at rest | Retention / deletion | Evidence |
|---|---|---|---|---|---|---|
| Precise location (foreground and background) | Yes, to each family member's device via the relay | Local Room DB (SQLCipher) on the sender and on every family member's device | Yes (TLS to the relay + E2EE payload) | Yes (SQLCipher, keys in Android Keystore) | History 30 days (`AppInitializer.kt:216`); unsent-publish queue now capped at 100 per member (`storage/LocationPublishOutboxRepository.kt`, commit `dfdc999`) | `location/LocationService.kt`, `storage/` |
| Chat messages | Yes (E2EE) | Local DB, replicated to family devices | Yes | Yes | 90 days (`AppInitializer.kt:217`) | `chat/`, `replication/` |
| Shared files and vault documents | Yes (E2EE) | Local encrypted storage, replicated | Yes | Yes (vault: passphrase-derived; see section 9) | Per-item deletion propagates (commit `218780e`) | `files/`, `vault/` |
| Display name, avatar, color | Yes (E2EE / LAN) | Local + group state | Yes | Yes | Removed on leaving | `group/`, `avatar/` |
| Presence (online/offline) | Yes; sealed under the group key and signed (commits `d296836`, `704ea28`), but the MQTT last-will is registered at connect time | Not persisted | Yes | n/a | n/a | `transport/MqttTransport.kt` (`sealPresence`, `setWill`) |
| Accelerometer (crash detection) | Only a crash alert message to the family if the user does not respond within 60 s | Not stored | n/a | n/a | n/a | `crash/CrashDetectionMonitor.kt`, `crash/CrashAlertActivity.kt:97-117` |
| Activity state (still/vehicle) | No | In memory | n/a | n/a | n/a | `location/ActivityRecognitionManager.kt` (see permission caveat above) |
| Identity keys / recovery phrase | No (12-word BIP-39 phrase is shown to the user; keys derive from it) | Android Keystore-backed store (`group/AndroidKeyStoreLocalKeyStore.kt`) | n/a | Yes | Erased on Leave Family | `MainViewModel.leaveFamily` |

**Accounts:** none. Identity is a locally generated key pair; `PRIVACY_POLICY.md`: "We operate no accounts". So Google's account-deletion requirement (read 2026-09-19: applies to "apps allowing account creation") does not appear to apply, but confirm this reading with the Data safety form's own deletion question.

**How a user deletes data:** Settings -> Leave Family stops tracking, wipes the local group state and keys, and tells the group (`SettingsScreen.kt:1240-1275`, `MainViewModel.kt:~469`, `AppInitializer.kt:291-303`). Uninstalling removes app data (`allowBackup=false`). Copies already replicated to other members' devices remain there until those devices' retention windows expire (`PRIVACY_POLICY.md`, "Your controls"). There is no developer-held copy to delete.

---

## 7. Joining a group and consent

1. A creator generates an invite code / QR (`invite/InviteManager.generateInviteCode`). Joiner enters or scans it (`onboarding/JoinFamilyScreen.kt`: "Ask a family member to share their invite code or QR code with you").
2. The joiner sends a join request over the relay. Existing members get a high-importance notification "Alerts when someone wants to join your family group" (`InviteManager.kt:createNotificationChannel`) and must **approve or reject** in the app (`main/MembersScreen.kt:145-196`, `MainScreen.kt:161-231`). Commit history records "authenticated join approval" hardening (see `PROJECT_STATUS.md`).
3. Each member must themselves grant the OS location permissions on their own device, after the in-app disclosure (section 8). No member's location is collected without that device's own permission grant.
4. Removal: the creator, or a majority of members (commit `1571763`, 2026-09-11), can remove a member. Anyone can leave.

Consent gaps to be aware of: the join screen itself says nothing about location being shared with the group; that is covered only by the location disclosure that follows. **The Settings "Share my location" switch does nothing** (see section 9).

---

## 8. Prominent disclosure as implemented

Text: `onboarding/PermissionCopy.kt:28-37`:
"Jibaro Family Safety collects location data to enable real-time location sharing with your family group, even when the app is closed or not in use. Your location is end-to-end encrypted, is shared only with your family members, and is never sold or given to anyone else."
Shown as a modal dialog card before the runtime prompt, buttons "Not Now" and "Continue" (`ui/components/PermissionRationaleCard.kt:76-87`), non-dismissible except by those buttons (`PermissionOnboardingFlow.kt:125-128`). On API 30+ "Continue" opens the app's system Settings page (no runtime dialog exists for background location there), with coaching text quoting the OS's own label.

Compared with Google's requirements (background location page, read 2026-09-19): includes "location", says "even when the app is closed or not in use", appears before the permission request, and offers a decline option. It lists **one** feature ("real-time location sharing"). Other features that use background location in this codebase are not named: geofence arrival/departure alerts and speed alerts (`geofence/GeofenceMonitor.kt`), crash detection (`crash/`), and 30-day location history (`main/HistoryScreen.kt`). Google asks the disclosure to "list all of the app features that use location in the background". See risks in the summary.

---

## 9. Behavior while the app is closed, and any hiding / covert capability

**Runs while closed:** foreground location service publishing locations every 30 s while moving and every 5 min when still (`LocationService.kt:94-95`), plus a 5-min exact-alarm heartbeat, a 15-min inexact watchdog alarm and 15-min WorkManager watchdog, boot recovery, passive location listener, MQTT connection, geofence/speed alert evaluation on incoming locations, crash detection while in a vehicle.

**Visible indicators:** an ongoing foreground-service notification is always shown while tracking (`LocationService.kt:287`). Presence is shown to other members.

**No app-icon hiding, disguise, or stealth mode was found**: grep for `setComponentEnabledSetting`, `hideIcon`, `stealth`, `ghost`, `pause` returned nothing.

**Things a reviewer could read as concealment or as misleading controls (documented precisely):**
1. **"Share my location" switch is inert.** `main/SettingsScreen.kt:514-526`: `var locationEnabled by remember { mutableStateOf(true) }` bound to a `Switch`; the value is never read anywhere else in the file or codebase. Turning it off changes nothing and tracking continues. The only ways to stop sharing are Leave Family, revoking the OS permission, or force-stopping.
2. **The Family Vault** (`vault/`): a deliberately deniable encrypted container - "one fixed-size file that always exists ... created full of random bytes ... no header, no version byte ... a wrong code opens an empty one rather than an error" (`vault/VaultContainer.kt` header comment, commit `4f3c1f0`). It conceals *documents inside the app*, not the app or the location sharing. It is worth describing accurately in the store listing if the vault is mentioned.
3. Consent is per device: a member cannot be tracked unless that device installed the app, joined via an approved invite, and granted the OS permissions.

---

## 10. Store-listing-relevant items found in code

- App label: `res/values/strings.xml:2` `app_name` = "Jibaro Family Safety"; the requested Console title is "Jibaro: Family Safety". Make sure the Console title, in-app label and privacy policy name agree.
- Privacy policy URL referenced in `PLAY_STORE_CHECKLIST.md`: https://singularity86.github.io/FamilySafety/privacy.html (source `docs/privacy.html`; text mirrors `PRIVACY_POLICY.md`, last updated July 6, 2026). **Not verified that the URL currently resolves.**
- **In-app privacy policy link: not found.** Settings has a "Privacy & Data" screen (`ui/screens/PrivacyScreen.kt`, reached via `MainScreen.kt:446,568-578`), but it is a short summary, contains no URL, and grep for `singularity86.github.io` in `app/src/main` returns nothing. Google requires the policy to be "linked on your app's store listing page and within your app" (background location page, read 2026-09-19).
- **The in-app Privacy & Data screen contradicts the code and the written policy** (`PrivacyScreen.kt:46-66,75`):
  - "Location history (last 24 hours)" - code and `PRIVACY_POLICY.md` say 30 days (`AppInitializer.kt:216`).
  - Under "Never Transmitted": "Message content" - chat messages are transmitted (encrypted) to family members.
  - "Never Transmitted: Any data not listed above" - precise coordinates go to `router.project-osrm.org` and map-viewing metadata to `tile.openstreetmap.org`.
- Contact given in policy: coloredhatsociety@gmail.com.

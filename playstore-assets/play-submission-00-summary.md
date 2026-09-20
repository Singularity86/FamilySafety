# Play submission 00 - Summary (Jibaro Family Safety, `jibaro.spacepirate.love`)

Prepared 2026-09-19 from the repo at branch `ui/design-system-pass` (HEAD `582ec70`, 1.13.5 / versionCode 34). No app source file was changed to produce this packet. Companion files: `play-submission-01-app-inventory.md` (evidence, cited), `play-submission-02-production-access-application.md` (form drafts).

**Scope note:** your request was cut off partway through deliverable 3 ("...so mark those as"). I wrote deliverables 1, 2 and 3 as far as I could tell they were meant to go. Anything you listed after that point never reached me, so it is not in this packet. Please resend it. Separately, I read deliverable 3's last sentence as "mark unconfirmed tester-feedback claims with a placeholder"; I did that ("[OMAR TO CONFIRM]").

**Folder:** an existing folder was found and used: `playstore-assets/` (repo root). I created no new folder. I did not modify, move, rename or delete anything already in it. Files already there at the start: `feature-graphic-1024x500.png`, `icon-512.png`, `release-notes-1.11.4.md`, `release-notes-1.12.0.md`, `-1.12.1`, `-1.12.10`, `-1.12.2`, `-1.12.3`, `-1.12.4`, `-1.12.6`, `-1.12.7`, `-1.12.8`, `-1.12.9` (each `.md`), and `screenshot-plan.md`. My three new files are the `play-submission-*` ones.

---

## What the app does (plain language)

A private family location-sharing app. A family creates a group, other members join by invite and an existing member's approval, and members see each other on a map. It also has location history, arrive/leave and speed alerts, chat, file sharing, an encrypted vault, and a crash-detection alert to the family. Location, chat, files and group data are end-to-end encrypted on the device and routed through a message relay (an MQTT broker run under the developer's EMQX Cloud account). There are no accounts, ads, analytics or crash-reporting SDKs. Location sharing runs in the background through a foreground service with a persistent notification. Two features do talk to third parties outside that encrypted channel: map tiles from OpenStreetMap and a drive-time estimate from the public OSRM server (see Risks 1-2).

---

## Sources read (and limits)

All read 2026-09-19 with the fetch tool, which returns a summary of each page rather than raw text, so the quoted phrases are the tool's extraction. Re-check exact wording in the live Console before pasting.

| Page | Result |
|---|---|
| Production access (14151465) | Read. 12 testers, 14 continuous days; three-part form. |
| Background location (9799150) | Read. One feature only; short video showing feature activated from the background, disclosure and runtime prompt; disclosure must precede the prompt, include "location", indicate background use, and list all features that use background location. |
| Prominent disclosure (11150561) | Read. Why / what / how; a decline option; not in the description or website. |
| Foreground services (13392821) | Read. Per-type description, user impact of deferral/interruption, demo video, use case; location use cases listed. |
| isMonitoringTool (12955211) | Read. Flag values `child_monitoring`, `enterprise_management`, `other`. Required for apps that monitor individuals by collecting and transmitting personal data, with two exceptions: apps "exclusively designed and marketed for parents to monitor their children" and enterprise management. The summary did not mention notification or hiding rules. |
| Spyware policy (14745000) | Read. The extract contained nothing specific to location or family apps. |
| Minimum scope location (17033915) | Read. Precise location only for core functionality; enforcement January 27, 2027. |
| Developer Program Policy (17517561, plus 16933379, 13986130) | Read. Privacy policy in Console and in the app; accurate Data safety including SDKs; account deletion applies to apps that allow account creation; Play's location policy: background location only for features "beneficial to the user and relevant to the core functionality". |
| April 15, 2026 and July 15, 2026 announcements (16926792, 17134731) | Read. April: location-button guidance, geofencing removed as an FGS use case. July: target API 36 by August 31, 2026 (level from https://support.google.com/googleplay/android-developer/answer/11926878; extension to November 1, 2026). |
| Data safety form (10787469) | Read. "Collected" = leaves the device; end-to-end-encrypted data unreadable by anyone but sender and recipient need not be disclosed; ephemeral processing exemption; user-initiated sharing exemption; deletion question. |
| Battery optimization exemption | **No Play Console Help page on this permission was found.** The Play policy text I found is on the Android developer site (https://developer.android.com/training/monitoring-device-state/doze-standby): "Google Play policies prohibit apps from requesting direct exemption from Power Management features ... unless the core function of the app is adversely affected", and its acceptable list includes "Safety app: Apps that keep their users and their families safe". That helps the app, but it is not a Play Help page. |
| Exact alarms | Play policy (13986130): `USE_EXACT_ALARM` is for alarm/timer/calendar apps; "evaluate if using `SCHEDULE_EXACT_ALARM` as an alternative is an option". The app uses `SCHEDULE_EXACT_ALARM`. |
| Full-screen intent | Play policy (13986130): auto-granted only for alarm or call apps; others must request user permission. |
| Families policy, stalkerware wording | Not found in the pages fetched. Not analyzed. |

---

## Declarations Play will require, with status

Status is what I can tell from the repo. "Console" items must be done by you.

| Declaration | Status |
|---|---|
| Production access application (3 parts) | Drafted; needs your data (tester count, feedback, install range). See file 02. |
| Background location declaration + video | **Not ready**: disclosure incomplete (Risk 1), video needs recording. The declared feature should be the single one: "real-time family location sharing that the user configures". |
| Foreground service (`location`) declaration + video | Drafted use case fits "User-initiated location sharing"; **verify** the background-start behavior first (inventory section 3). Two services declared: `LocationService` and WorkManager's `SystemForegroundService`. |
| Full-screen intent declaration | Needed (`USE_FULL_SCREEN_INTENT`, crash alert). Not a calling/alarm app, so not auto-granted. |
| Privacy policy | Exists (`PRIVACY_POLICY.md`, hosted at `singularity86.github.io/FamilySafety/privacy.html` per `PLAY_STORE_CHECKLIST.md`). **Must be corrected** (Risks 1-2) and linked in-app (Risk 4). |
| Data safety | Not drafted in this packet (it was not in the part of your message I received). Inputs are in inventory sections 4-6; the OSM/OSRM sends are the hard part. |
| `isMonitoringTool` | Not declared in the manifest. Decision needed (Risk 9). |
| Battery-optimization exemption justification | Manifest permission declared; justification text in `PLAY_STORE_CHECKLIST.md`. |
| Target API | Met (36). |
| Others the Console typically asks for (ads, content rating, target audience, app access, etc.) | Not verified from a fetched page; I did not draft them. |

---

## Top compliance risks, ranked by likelihood of rejection

**Update 2026-09-19 (version 1.13.6 / 35):** Risks 1, 3 and 4 were fixed in code and Risk 2 was fixed in the written policy (see status lines under each). Their descriptions below are kept as originally found. The Data safety form and an up-front notice before the OSRM request are still open. Re-verify on a device before recording the Play videos.

1. **[FIXED in 1.13.6: `PermissionCopy.kt` now lists sharing, 30-day history, place and speed alerts, and crash detection.]** **Background-location disclosure lists one feature; the code uses background location for more.** `onboarding/PermissionCopy.kt:28-37` names only "real-time location sharing". Google requires listing "all of the app features that use location in the background". The code also uses it for arrive/leave and speed alerts (`geofence/GeofenceMonitor.kt`), crash detection (`crash/`) and location history. The declared feature must still be only one; the fix is to make the disclosure complete or to stop the other features from relying on background fixes. Buttons say "Not Now"/"Continue" (Google prefers a friendly explicit choice such as "Agree"). Needs a 30-second video.
2. **[Policy FIXED in 1.13.6 (`PRIVACY_POLICY.md`, `docs/privacy.html`, dated September 19, 2026; republish the hosted page). Data safety answers not yet drafted; the OSRM notice still appears only after the request is sent.]** **Privacy policy and Data safety would be false as written.** `PRIVACY_POLICY.md` says "There are no third parties beyond the message relay" and that the relay "never sees locations". But: (a) tapping a drive-time estimate sends the **precise coordinates of two family members** in the URL to the public OSRM demo server `router.project-osrm.org` (`routing/RoutingService.kt:73-105`, unencrypted-by-E2EE); (b) the map downloads tiles from `*.tile.openstreetmap.org` for whatever area is viewed, exposing IP and viewed area (`main/MapScreen.kt:71-76`). Neither is E2EE, so the Data safety end-to-end-encryption exemption does not cover them. Google's user-initiated-sharing exemption might cover (a) partly, but one of the two coordinates belongs to another person. This is the most likely cause of a Data safety / policy mismatch finding.
3. **[FIXED in 1.13.6: the switch now persists, stops and restarts the location service, and restart paths honor it. Not yet tested on a device.]** **"Share my location" switch does nothing.** `main/SettingsScreen.kt:514-526`: state is never read. A user cannot pause sharing; a reviewer testing consent and control will find a control that lies. The only real stop is Leave Family (erases keys) or revoking the OS permission.
4. **[FIXED in 1.13.6: `PrivacyScreen.kt` corrected (30 days, encrypted chat, outside services) and links to the hosted policy.]** **In-app privacy content is inaccurate and there is no in-app policy link.** `ui/screens/PrivacyScreen.kt:46-66` says location history is "last 24 hours" (code: 30 days), that "Message content" is never transmitted (it is, encrypted), and that "any data not listed above" is never transmitted (see Risk 2). No URL to the hosted policy exists in `app/src/main`; Google requires the policy to be linked "within your app".
5. **Full-screen intent.** The crash alert uses `setFullScreenIntent` with `CATEGORY_ALARM` (`crash/CrashDetectionMonitor.kt:108-112`). The app is not a calling or alarm app, so Google will not auto-grant; users must grant it, and I found no `canUseFullScreenIntent` check or degrade path. Declare accurately and expect scrutiny.
6. **Foreground-service start from the background.** Logcat on a real device (2026-09-19) warned "Foreground service started from background can not have location/camera/microphone access" for `LocationService`. Several start paths are background starts (boot, alarms, WorkManager). GPS still worked in that run, so I cannot say how serious it is. Confirm on a release build before recording the FGS declaration video.
7. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.** Moderate risk, arguably favorable: the Android docs list family safety apps as acceptable (source above), but I found no Play Help text confirming it. It should be defended on the ground that Doze breaks the core function and FCM is not an option for an E2EE, serverless relay. The permission is requested from onboarding (`BatteryOptimizationScreen.kt`) and Settings.
8. **`SCHEDULE_EXACT_ALARM`.** Low. Play restricts `USE_EXACT_ALARM` to alarm/calendar apps and points others to `SCHEDULE_EXACT_ALARM`, which the app uses, with a fallback to inexact alarms on denial.
9. **`isMonitoringTool` is undeclared - a decision for you.** Google requires the flag for apps that monitor individuals by collecting and transmitting personal data unless "exclusively designed and marketed for parents to monitor their children". This app is a mutual family circle, not parent-only. Facts that bear on the question: persistent notification while tracking; no icon hiding or stealth mode found; join requires existing-member approval; each device grants its own permissions. Whether Google expects `other` (or none) is not something I can determine from the pages I read. [OMAR TO DECIDE.]
10. **`ACTIVITY_RECOGNITION` declared but never requested at runtime.** Feature likely inert; the permission is unnecessary as shipped, and the privacy policy lists it. Either request it with a disclosure or remove it.
11. **External tip link to Cash App** (`main/SettingsScreen.kt:1014`). I did not read Play's payments policy, so I cannot say whether this is allowed. Check before submitting. [OMAR TO VERIFY.]
12. **Stale documents.** `PLAY_STORE_CHECKLIST.md` says targetSdk 35 (actual 36); policy dated July 6, 2026 predates the vault, crash detection, map/OSRM use and the 30-day history; `PROJECT_STATUS.md` and `PLAY_STORE_CHECKLIST.md` disagree on some details.

Other observations, not policy issues: broker credentials ship in the APK (documented and accepted in `SECURITY_REVIEW.md`, F2); release builds are not minified; the 1.13.5 background fix is unproven over a long idle period.

---

## [OMAR TO FILL] / [OMAR TO CONFIRM] - all placeholders in one list

Production access application (file 02):
1. Number of testers and continuous days opted in (must be at least 12 and 14).
2. How easy testers were to recruit (form option).
3. Which features testers used; any that were not exercised; how usage compared with expected production use.
4. Summary of tester feedback and how it was collected.
5. Estimated first-year install range.
6. Which versionCode(s) testers ran, and whether 34 is what goes to production.
7. For each change in the Part 3 table: whether it came from tester feedback.
8. Devices/builds you tested on, and for how long.
9. Confirm target-audience wording (adults 18+, not child-directed) matches your Console answers.

Compliance decisions and checks:
10. `isMonitoringTool`: declare or not, and which value.
11. Cash App tip link: check against Play's payments policy.
12. Verify the privacy policy URL resolves and shows the current text.
13. Run `./gradlew lint`; smoke-test the release AAB through internal testing.
14. Re-test the foreground-service background start on a release build.
15. Resend the rest of your request (it was cut off after deliverable 3).

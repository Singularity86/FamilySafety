# Jibaro Family Safety — Play Store Screenshot Plan

## Specs (Google Play, phone screenshots)
- 2–8 images, JPEG or 24-bit PNG (no alpha)
- Each side: 320px–3840px; aspect ratio between 16:9 and 9:16 (portrait: use 1080×2340 or your device's native res)
- **Screenshot #1 and #2 matter most** — they're what shows in search results before anyone taps into the listing

## Order & content (grounded in the actual screens in this repo)

**1. MapScreen.kt — the hook**
Headline: "See where your family is — instantly"
Subcopy: "Real-time location, end-to-end encrypted"
Capture: map view with 2-3 member pins placed, `EncryptionChip`/`ConnectionBadge` visible in frame.

**2. SecurityScreen.kt — the differentiator**
Headline: "No cloud. No servers holding your data."
Subcopy: "Every update is encrypted device-to-device — check the status yourself, anytime"
Capture: the "Needs attention" / all-clear verdict card + Network Status card, showing a live "secure" state. This screen is rare among competitors (Life360, Bark, etc. don't expose this) — it's your strongest trust signal because it's *provable*, not just a claim.

**3. MembersScreen.kt — the daily-use screen**
Headline: "Know who's home, who's on the way"
Subcopy: "Live status, distance, and arrival estimates for everyone in your group"
Capture: member list with avatars, a couple of members "on the way" (DriveEstimateDialog/ETA visible).

**4. GeofenceListScreen.kt ("Zones")**
Headline: "Get notified the moment they arrive or leave"
Subcopy: "Set zones for home, school, or grandma's — no constant tracking required"
Capture: Zones list with 2-3 named zones (Home, School).

**5. CrashAlertActivity.kt**
Headline: "Automatic crash detection, even behind the wheel"
Subcopy: "If something goes wrong, your family finds out immediately"
Capture: the full-screen crash alert card.

**6. ChatScreen.kt**
Headline: "Message your family without leaving the app"
Subcopy: "Fully encrypted group chat, just for your circle"

**7. WelcomeScreen.kt / GenerateMnemonicScreen.kt — the second big differentiator**
Headline: "No account. No phone number. No email."
Subcopy: "Your family's identity is a recovery phrase only you control"
Capture: Welcome screen or the recovery-phrase display screen (blur/placeholder words if it's a real phrase).

**8. Marketing slide (not a raw screengrab) — closes on the pricing decision**
Headline: "Pay once. Own it forever."
Subcopy: "$79 one-time — no subscriptions, ever" (adjust once you land on final price)
This one's a designed graphic over a blurred map background, not a literal screen capture — standard practice for a closing "why us" slide.

## Why this order
Play surfaces screenshots #1–2 before anyone opens the listing, so they need to sell the core value (live location) and your rarest asset (verifiable encryption) before a scroller ever taps in. Zones/crash detection/chat are supporting proof you have real safety features, not just a map. The recovery-phrase screen and pricing slide close the deal for privacy-conscious buyers comparing you against subscription competitors.

## Capturing them
Per `PLAY_STORE_CHECKLIST.md`: once UI is final, `adb exec-out screencap -p > s1.png` per screen on a real device.

For device frames + headline/subcopy overlays, use **fastlane `frameit`** — open-source, runs entirely locally (no cloud upload of your screenshots), reads frames from a local asset pack, and batch-outputs framed screenshots from a folder. Fits your no-cloud preference better than the web-based screenshot generators.

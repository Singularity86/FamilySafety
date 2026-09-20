# Play submission 02 - Production access application (draft answers)

**Form fields source:** https://support.google.com/googleplay/android-developer/answer/14151465, read 2026-09-19. Requirement stated there: a closed test with at least 12 testers opted in continuously for at least 14 days, then the three-part application below. The form's own wording (paraphrased by the page fetch tool, which summarizes; open the live form and copy the exact prompts before pasting):

- Part 1 - About your closed test: (a) how easy it was to recruit testers; (b) tester engagement, including whether testers used all available features and whether usage matched expected production behavior, with differences; (c) a summary of the feedback received and how it was collected.
- Part 2 - About your app: (a) target audience, "as specific as possible"; (b) how the app provides value; (c) estimated install range in the first year.
- Part 3 - Production readiness: (a) changes made based on what you learned from the closed test; (b) how you determined the app was ready for production.

I have no data about the closed test itself: no tester count, dates, recruitment method, feedback, or which build the testers ran. None of that is in the repo. Every such answer is a placeholder. I did not invent any of it.

---

## Facts the answers rely on (all from the repo)

- **Which build did testers have?** Not recorded in the repo. Release history: 1.13.0 (29) 2026-08-22, 1.13.1 (30) 2026-08-29, 1.13.2 (31) and 1.13.3 (32) 2026-08-30, 1.13.4 (33) 2026-09-04 (`git log` "Bump" commits, `RELEASE_NOTES.md`). `PROJECT_STATUS.md:26` says of 1.13.1 (30) "29 is what the family is running", so 29-33 were distributed to some group of devices, but the repo does not say through which Play track. **Latest build prepared for production: 1.13.6 (35)** (1.13.5 (34) was built earlier the same day; 1.13.6 adds the compliance fixes in `play-submission-00-summary.md`), built 2026-09-19 from commits `b3dd517`..`59bf9fc`, signed AAB (`jarsigner` verified), pushed to `origin/ui/design-system-pass`. As of this writing I have not uploaded it anywhere. [OMAR TO FILL: the exact versionCode(s) your testers ran, and whether 34 is what you're submitting for production.]
- **Latest commits after the last documented release** (1.13.4, 2026-09-04): `1571763` (2026-09-11, majority removal), `50354c8`/`5620ef5`/`28587f2` (2026-09-13, theme/type/colour), `b6f8db0` (2026-09-18, keyboard Go action), `62912ac` (crash rule tests), and the six 2026-09-19 reliability commits. All are included in 1.13.5 (34).
- Automated verification on 2026-09-19 (this session): `./gradlew testDebugUnitTest` finished `BUILD SUCCESSFUL`; the debug build installed and ran on a connected device and produced GPS fixes. `./gradlew lint` was **not** run. [OMAR TO FILL / RUN: lint, and a smoke test of the *release* AAB via internal testing, which `PLAY_STORE_CHECKLIST.md` says is needed because release packaging differs.]

---

## Part 1 - About your closed test

**(a) How easy was it to recruit testers?**
[OMAR TO FILL: pick the option on the form that matches what really happened.] The repo shows the app was designed for and used by a family group (`PROJECT_STATUS.md:26`), so recruiting may have relied on relatives and friends. Only answer that if it is true.

**(b) Tester engagement**
Draft, to be edited to reality:

> [OMAR TO FILL: number] testers stayed opted in for [OMAR TO FILL: days, must be at least 14 continuous] days. The app's features are: creating a family and joining by invite (QR or code) with existing-member approval, live location sharing on a map, location history, place alerts (arrive/leave) and speed alerts, encrypted family chat, file sharing and an encrypted document vault, emergency crash-detection alerts, and member removal by vote. [OMAR TO FILL: which of these testers actually used. Say plainly if some (for example crash detection, which needs a real impact, or the vault) were not exercised.]
>
> Expected production usage is a household or extended family who leave the app running in the background for days at a time. [OMAR TO FILL: whether testers behaved that way. One difference the code makes obvious: many features can only be exercised with two or more devices in the same group, so any tester who ran a single device could not have tested sharing, chat or approvals.]

**(c) Feedback received and how it was collected**
[OMAR TO FILL: the real feedback and the channel (Play testing feedback, email, chat, in person).] I found no feedback log, issue tracker export or tester notes in the repo, so I cannot summarize any.

---

## Part 2 - About your app

**(a) Target audience**
From `PLAY_STORE_CHECKLIST.md` ("Target audience: 18+ account holders (parents). Do NOT opt into child-directed / Designed for Families") and `PRIVACY_POLICY.md` ("Children": intended to be set up and administered by adults; a parent or guardian may install it on a child's device):

> Families and households who want to see where each other are for everyday safety. Adults aged 18 and over set up and manage a private family group; a parent or guardian may choose to install the app on a family member's phone as part of that group. It is not directed to children and is not offered as a children's app.

[OMAR TO CONFIRM: that this matches the target-audience and content-rating answers you gave elsewhere in the Console. If children under 13 are meant to use the app themselves, the Families policy questions become relevant; I did not analyze that.]

**(b) How the app provides value** (each sentence traceable to code):

> Jibaro Family Safety lets a family share live locations with each other, and only with each other. Locations, messages, files and group membership are end-to-end encrypted on the device before they leave it, so the message relay cannot read them (`crypto/E2EEManager.kt`, `transport/MessageProtocol.kt`); there are no accounts, no advertising and no analytics SDKs. Family members can see each other on a map, review recent location history, get arrival/leave and speed alerts for places the family defines, and chat. If a member's phone detects a probable vehicle crash while they are driving and they do not respond within 60 seconds, the family is alerted (`crash/CrashAlertActivity.kt`). New members join only with an invite and the approval of an existing member, and any member can leave and erase their keys at any time.

Do not add claims the code cannot back (for example that location is "always accurate" or "never delayed"). The 1.13.5 changes exist because locations could go quiet in the background; see Part 3.

**(c) Estimated installs in the first year**
[OMAR TO FILL: choose the range. The repo contains no install projection, waitlist or user count, and I will not guess.]

---

## Part 3 - Production readiness

**(a) Changes made because of the closed test**
Google asks for changes "based on what you learned from your closed test". I can list what changed in the code and when, but I **cannot** tell which changes were driven by tester feedback. Below, the right-hand column is a decision only you can make. Anything not confirmed should not be presented as a tester-driven fix.

| Date | Change (commit) | Why the code says it was made | Driven by tester feedback? |
|---|---|---|---|
| 2026-08-29 | Stop a full in-flight window from looking like a dead connection (`fdd190b`); disconnect fixes in 1.13.1 (`RELEASE_NOTES.md`, "the disconnect that was not one") | Members appeared disconnected | [OMAR TO CONFIRM] |
| 2026-08-30 | A mistyped recovery phrase no longer restores a stranger (`339243e`, 1.13.2) | Restore accepted any 12 valid words | [OMAR TO CONFIRM] |
| 2026-08-30 | Shared documents get their own key (`5f3cbae`, 1.13.3) | Security hardening (`SECURITY_REVIEW.md`) | [OMAR TO CONFIRM] |
| 2026-09-04 | New icon, warmer colour scheme, family name in the top bar (1.13.4) | Visual redesign | [OMAR TO CONFIRM] |
| 2026-09-11 | A majority of the family can remove a member without the creator (`1571763`) | Removes dependence on the creator | [OMAR TO CONFIRM] |
| 2026-09-18 | Keyboard "Go" submits name/invite/recovery phrase fields (`b6f8db0`) | Usability | [OMAR TO CONFIRM] |
| 2026-09-19 | **Background location reliability (1.13.5):** a stalled connection to the relay could block all reconnects for hours (`b3dd517`); failures loading the saved group are now logged (`e914887`); unsent-location queue capped (`dfdc999`); rate limiter uses a monotonic clock (`afb6be5`); activity recognition can retry (`43571c8`); phone-maker background-limit guidance permanently in Settings (`59bf9fc`) | Reported symptom: locations going hours without an update across all family members unless the app was opened (raised by you in this session on 2026-09-18). These are code-review findings; **not yet confirmed on devices** (`RELEASE_NOTES.md` 1.13.5) | The symptom report came from you, [OMAR TO CONFIRM: whether from testers or from your own family use] |

Draft wording once confirmed:

> During the test we found that location updates could stop reaching family members for hours until the app was reopened. We traced this to a connection to our message relay that could hang without ever timing out, and fixed it by bounding every connection attempt and recovering automatically. We also capped an unbounded queue of unsent locations, fixed a rate-limiter edge case, and added phone-specific background-settings guidance. [Only keep the sentence "we traced this" if you agree it is true; the fix is untested over a multi-hour idle period.]

**(b) How you determined the app was ready for production**
What I can support:
- Unit tests pass (`testDebugUnitTest`, `BUILD SUCCESSFUL`, 2026-09-19), including new tests for the connection-stale rule (`app/src/test/.../transport/MqttConnectingStaleTest.kt`) and crash trigger rule (`crash/`).
- Signed release AAB builds and verifies (`jarsigner`: "jar verified"), targetSdk 36 (meets the 2026 requirement, see inventory section 1).
- Two written security reviews with mitigations tracked (`SECURITY_REVIEW.md`, 2026-08-08 and 2026-08-17).
- Device checks: [OMAR TO FILL: which devices, which builds, for how long. The only device run I saw was a debug-build smoke test on 2026-09-19.]

What I could **not** support and you should not claim without doing it: a multi-hour idle test showing the 1.13.5 background fix works; a lint run; a release-AAB test through Play's internal track; and the compliance items in `play-submission-00-summary.md` (in particular the privacy policy and Data safety mismatches), which I recommend resolving before you click Apply. Google's own form asks about readiness, and a reviewer who then opens the app and the policy will see the same mismatches.

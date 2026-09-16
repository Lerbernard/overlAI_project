# Google Play: listing, data safety and release checklist

Everything the Play Console asks for, written to match what the app actually does in this
version (no ads, optional Google sign-in, media analysed by Sightengine through our relay).
Keep this file in step with the code: if a future version adds ads or analytics, update the
data safety section *and* the privacy policy on the website first.

## URLs the console asks for

| Field | Value |
|---|---|
| Privacy policy | https://lmbtechnology.com/overlai/privacy |
| Support / website | https://lmbtechnology.com/overlai/support |
| Account deletion | https://lmbtechnology.com/overlai/delete-account |
| Terms of use (in-app link) | https://lmbtechnology.com/overlai/terms |
| Support email | hello@lmbtechnology.com |
| Developer name shown on Play | LMB Technology (matches the Florida fictitious-name filing) |

## Store listing

**App name** (30 max): `OverlAI - AI image detector`

**Short description** (80 max):
`Check anything on your screen for AI generation in one tap.`

**Full description** (4000 max):

```
Is this picture real or AI-generated? OverlAI answers that question anywhere on your phone.

FLOATING BUBBLE
A small draggable bubble that works over any app. Double-tap it to check what's on screen,
crop to the part you're unsure about, or record a short clip. The score appears right in the
bubble.

SHARE SHEET
Long-press a picture or video in any app, share it to OverlAI, and get the score without
leaving what you were doing.

QUICK-SETTINGS TILE
One tap from the notification shade: screenshot, check, save the result, done.

GALLERY DETECTOR
Pick any image or video from your phone and get a verdict with likelihood and confidence.

HISTORY AND WIDGETS
Every result with its thumbnail, grouped by day. A home-screen widget shows this month's
checks and your last result.

PRIVATE BY DESIGN
• No account needed. Sign-in is optional.
• What you check is analysed and not stored by us.
• Results, thumbnails and settings live only on your phone.
• No ads, no analytics, no tracking.
• Android asks before every screenshot; nothing is captured in the background.

HONEST ABOUT LIMITS
Scores are estimates from an AI model, not proof. They can be wrong in both directions, so
treat them as one signal alongside your own judgement.

Made by LMB Technology. Support: https://lmbtechnology.com/overlai/support
```

**Category:** Tools. **Tags:** AI, image detection, deepfake, screenshot.

**Graphics needed:** app icon 512×512 PNG (export from `docs/logo.svg`), feature graphic
1024×500, at least four phone screenshots (overlay bubble over another app, the detector
result, History, the quick-settings tile), optionally a 7-inch tablet set. Take them on a
Pixel emulator with a clean status bar.

**Contact details:** email hello@lmbtechnology.com, website https://lmbtechnology.com/overlai.

## App content section

| Question | Answer |
|---|---|
| Privacy policy | https://lmbtechnology.com/overlai/privacy |
| Ads | **No**, the app does not contain ads (BuildConfig.ADS_ENABLED is false; the SDK is never initialised). |
| App access | All functionality is available without special access. Provide a note: "No login required. Sign-in is optional." |
| Content rating (IARC) | Utility/productivity. No violence, sexual content, language, controlled substances, gambling, or user-to-user interaction. Does not share location. Users can't share content with each other through the app. Expected rating: Everyone / PEGI 3. |
| Target audience | 18 and over (simplest; the app isn't designed for children). Answer "No" to "appeal to children". |
| News app | No |
| COVID-19 contact tracing | No |
| Data safety | See below |
| Government app | No |
| Financial features | None |
| Health | No health features |

### Foreground service and permission declarations

Play asks for a declaration (with a short screen-recorded video) for these:

- **`FOREGROUND_SERVICE_MEDIA_PROJECTION`** — task: "Screen capture initiated by the user."
  Text: *"OverlAI captures the screen only when the user taps the floating bubble, the
  quick-settings tile or the share sheet. Android's system consent dialog is shown before every
  capture. The captured frame is sent for AI-generation analysis and the result is shown to the
  user. Nothing is captured in the background."*
- **`FOREGROUND_SERVICE_SPECIAL_USE`** — subtype text is in the manifest: *"Screen overlay
  tool for on-demand AI-content detection."* Explain: *"The service keeps the floating overlay
  bubble alive while the user has turned it on. It shows a persistent notification and does no
  work until the user taps the bubble. No existing foreground service type describes an
  always-available overlay tool."*
- **`SYSTEM_ALERT_WINDOW`** — no console form, but the video should show the overlay
  permission prompt and the bubble being used over another app.

Record one 30–60 s video on a phone or emulator: turn the overlay on (permission prompt), open
another app, double-tap the bubble, accept the capture prompt, see the score; then use the
quick-settings tile. Upload it as an unlisted YouTube video and paste the link into both forms.

## Data safety form

**Overview questions**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes (HTTPS everywhere) |
| Do you provide a way for users to request that their data is deleted? | Yes (in-app Delete account, plus https://lmbtechnology.com/overlai/delete-account) |
| Independent security review | No |

**Data types**

| Data type | Collected? | Shared? | Optional? | Purpose | Ephemeral? | Notes |
|---|---|---|---|---|---|---|
| Photos and videos | Yes | No | Required for the feature | App functionality | **Yes** — processed for the request and not stored | Sent to our relay and on to Sightengine (a service provider acting on our instructions, so not "shared" in Play's sense). |
| Personal info: Email address | Yes | No | Optional (only with Google sign-in) | Account management | No | Stored in Firebase Auth / Firestore until the account is deleted. |
| Personal info: Name | Yes | No | Optional | Account management | No | Google display name from sign-in. |
| Personal info: User IDs | Yes | No | Optional | Account management, App functionality | No | Firebase UID. |
| App activity: Other user-generated content | Yes | No | Optional (only if the user sends a bug report) | App functionality (bug fixing) | No | Bug report text and attached screenshots. |
| App info and performance: Diagnostics | Yes | No | Optional (bug reports only) | App functionality | No | Device model, Android version, app version inside a bug report. |
| Device or other IDs | No | | | | | No advertising ID; ads SDK never initialised. |
| Location, Contacts, Messages, Calendar, Audio, Files, Health, Financial | No | | | | | |

If you later turn ads on: add "Device or other IDs" (advertising ID) collected and shared for
Advertising, mark "Ads: Yes", add the UMP consent flow, and update the website policy.

## Release checklist

1. **Proxy.** Rotate `APP_TOKEN` in the Cloudflare worker (`overlai-proxy`) and put the new one
   in `local.properties`. The token ships inside the APK, so treat it as a rate-limit key, not
   a secret: keep the worker's per-IP and per-token limits on, and reject requests whose
   `X-App-Version` header is a version you've retired.
2. **Firestore rules.** Publish `firestore.rules` in the Firebase console before the first
   release. Test in the Rules playground: a signed-in user can `get` and `delete`
   `users/{their uid}` and cannot `update` it; `bug_reports` accepts a valid create and
   rejects a read.
3. **Firebase Auth.** In the Firebase console add the *release* signing certificate's SHA-1 and
   SHA-256 to the Android app (and the Play App Signing certificate once the app exists in the
   console), then download the updated `google-services.json`. Google sign-in fails silently
   without them.
4. **Upload key.** One time, never commit it:
   ```
   keytool -genkeypair -v -keystore overlai-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```
   Then `keystore.properties` next to `build.gradle.kts`:
   ```
   storeFile=../overlai-upload.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```
   Both files are gitignored. Back the keystore up somewhere safe; enrol in Play App Signing
   when creating the app so Google holds the final signing key.
5. **Build.** `gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
   Bump `versionCode` (integer, always increasing) and `versionName` for every upload.
6. **Test the release build** on a device: overlay on/off, capture, share sheet, tile, widget
   button, sign-in, delete account, bug report, both launcher icons.
7. **Play Console.** Create the app (free, Tools), fill *App content* from this file, upload the
   bundle to Internal testing first, add yourself as a tester, then promote to Production.
   New personal developer accounts must run a closed test with 12 testers for 14 days before
   production access is granted.
8. **After publishing.** Put the Play URL into `PLAY_URL` in the website's
   `app/overlai/page.js` so the "Coming to Google Play" button becomes "Get it on Google Play".

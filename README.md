<p align="center">
  <img src="docs/logo.svg" width="120" alt="OverlAI logo" />
</p>

<h1 align="center">OverlAI</h1>

<p align="center">
  <b>Check anything on your screen for AI generation — in one tap.</b><br/>
  A floating overlay, gallery detector, share-sheet target, and quick-settings tiles,<br/>
  all backed by a single detection pipeline with caching and quota tracking.
</p>

<p align="center">
  <a href="https://lmbtechnology.com/overlai">Website</a> ·
  <a href="https://lmbtechnology.com/overlai/privacy">Privacy policy</a> ·
  <a href="https://lmbtechnology.com/overlai/support">Support</a> ·
  <a href="docs/play-store.md">Play Store listing &amp; release checklist</a>
</p>

---

## What it does

OverlAI answers one question everywhere on your phone: **"is this AI-generated?"**

| | |
|---|---|
| 🔮 **Floating overlay** | A draggable bubble over any app. Tap for the menu, **double-tap for an instant check**. Capture a screenshot, crop the part you care about, or record a short clip — the AI-likelihood score appears right in the bubble. |
| 🔍 **Detector** | Pick any image or video from your device. Scan-line animation while analysing, then a verdict banner with likelihood, confidence, and media type. |
| 📤 **Share target** | Long-press media in any app → Share → *Check with OverlAI* → instant score card. |
| ⚡ **Quick check tile** | A Quick Settings tile that screenshots, detects, saves to History, and notifies you with the score. |
| 🕓 **History** | Every result with its thumbnail, grouped by day, with share, delete, and re-check actions. |
| 📊 **Widgets** | A toggle widget and a dashboard widget with monthly usage and your last result. |

## Design details

- **One detection pipeline** (`DetectionClient`): human-readable errors, MD5-based result caching (re-checking the same image never costs quota), and per-month usage counters shown in Settings.
- **Themed from one place**: every color routes through `colors.xml` / `colors-dark.xml` via `ThemeHelper` — change two files, restyle the entire app, widgets included.
- **Overlay engineering**: content-sized window (never blocks touches around the bubble), throw physics with edge glide, inset-aware clamping, drag-to-trash with proximity detection, and expansion that grows away from the button without moving it.
- **Onboarding**: a five-page first-run tutorial covering the bubble, its gestures, and every entry point.

## Building it

Requirements: Android Studio with JDK 17+ (the bundled JBR is fine), Android SDK platform 36.
Gradle 8.11.1 and AGP 8.9.1 are pinned in the wrapper and version catalog.

1. Clone, open in Android Studio.
2. Create `local.properties` (gitignored) with the SDK path and the detection relay:
   ```properties
   sdk.dir=C:\\Users\\you\\AppData\\Local\\Android\\Sdk
   PROXY_BASE=https://<your-worker>.workers.dev
   APP_TOKEN=<token the worker expects in X-App-Token>
   ```
   The relay is the same Cloudflare worker that serves the web detector on lmbtechnology.com.
   It holds the Sightengine credentials; the app never sees them.
3. Run. On first activation the app walks you through the overlay permission.

Release builds are signed from `keystore.properties` (gitignored) when it exists; see
[docs/play-store.md](docs/play-store.md) for the keystore, Play Console and Firebase steps.

## Security model

- **No provider keys in the app.** Detection goes to the relay with `X-App-Token` and
  `X-App-Version` headers. The token identifies the build and is rate-limited per IP and per
  token on the worker; rotate it per release and retire old versions at the worker.
- **Premium can't be self-granted.** The app only *reads* `users/{uid}.premium`;
  [`firestore.rules`](firestore.rules) make the document read-only and delete-only for its
  owner. Purchases will be verified server-side when Play Billing lands.
- **Ads compiled out.** `BuildConfig.ADS_ENABLED = false`: the Mobile Ads SDK is never
  initialised, the Remove-ads card is hidden, and no advertising ID is collected. Flip it on
  only with real AdMob unit IDs, a consent (UMP) flow, and an updated data-safety form.
- **Components locked down.** Widget providers only receive system `APPWIDGET_*` broadcasts;
  the toggle button goes through a non-exported `WidgetActionReceiver`. Overlay, capture and
  crop components are non-exported. `allowBackup` is off so thumbnails and cached scores never
  leave the device.
- **Bug reports are bounded.** Rules cap field sizes and let clients create but never read.
- `google-services.json` is committed on purpose: Firebase treats it as configuration, not a
  secret, and the rules plus the OAuth client's SHA restrictions are what protect the project.

## Privacy

Media you check is sent over HTTPS to the relay and on to Sightengine for analysis; neither we
nor the relay store it. Results and thumbnails are stored **only on your device**. Sign-in is
optional and only remembers a premium flag. Full policy:
https://lmbtechnology.com/overlai/privacy

## Tech

Kotlin · Foreground services (`specialUse` + `mediaProjection`) · MediaProjection & MediaRecorder · Custom `View` drawing (every overlay icon is drawn in code) · RemoteViews widgets · TileService · OkHttp · Firebase Auth/Firestore (optional account)

## Roadmap

- [x] Backend proxy for API keys
- [ ] Play Billing with server-side verification (then `ADS_ENABLED` and real AdMob IDs)
- [ ] Alternative / self-hosted detection models

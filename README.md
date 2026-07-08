<p align="center">
  <img src="docs/logo.svg" width="120" alt="OverlAI logo" />
</p>

<h1 align="center">OverlAI</h1>

<p align="center">
  <b>Check anything on your screen for AI generation — in one tap.</b><br/>
  A floating overlay, gallery detector, share-sheet target, and quick-settings tiles,<br/>
  all backed by a single detection pipeline with caching and quota tracking.
</p>

---

## What it does

OverlAI answers one question everywhere on your phone: **"is this AI-generated?"**

| | |
|---|---|
| 🔮 **Floating overlay** | A draggable bubble over any app. Tap for the menu, **double-tap for an instant check**. Capture a screenshot, crop the part you care about, or record a short clip — the AI-likelihood score appears right in the bubble. |
| 🔍 **Detector** | Pick any image or video from your device. Scan-line animation while analysing, then a verdict banner with likelihood, confidence, and media type. |
| 📤 **Share target** | Long-press media in any app → Share → *Check with overlAI* → instant score card. |
| ⚡ **Quick check tile** | A Quick Settings tile that screenshots, detects, saves to History, and notifies you with the score. |
| 🕓 **History** | Every result with its thumbnail, grouped by day, with share, delete, and re-check actions. |
| 📊 **Widgets** | A Proton-style toggle widget and a dashboard widget with monthly usage and your last result. |

## Design details

- **One detection pipeline** (`DetectionClient`): human-readable errors, MD5-based result caching (re-checking the same image never costs quota), and per-month usage counters shown in Settings.
- **Themed from one place**: every color routes through `colors.xml` / `colors-dark.xml` via `ThemeHelper` — change two files, restyle the entire app, widgets included.
- **Overlay engineering**: content-sized window (never blocks touches around the bubble), throw physics with edge glide, inset-aware clamping, drag-to-trash with proximity detection, and expansion that grows away from the button without moving it.
- **Onboarding**: a five-page first-run tutorial covering the bubble, its gestures, and every entry point.

## Building it

1. Clone, open in Android Studio.
2. Add your [Sightengine](https://sightengine.com/) credentials to `local.properties`:
   ```properties
   SE_API_USER=your_user
   SE_API_SECRET=your_secret
   ```
3. Run. On first activation the app walks you through the overlay permission.

> Keys stay out of version control (`local.properties` is gitignored) and are injected via `BuildConfig`.

## Privacy

Media you check is uploaded to Sightengine for analysis. Results and thumbnails are stored **only on your device** — there is no account, no analytics, and nothing else leaves your phone.

## Tech

Kotlin · Foreground services (`specialUse` + `mediaProjection`) · MediaProjection & MediaRecorder · Custom `View` drawing (every overlay icon is drawn in code) · RemoteViews widgets · TileService · OkHttp

## Roadmap

- [ ] Backend proxy for API keys
- [ ] Alternative / self-hosted detection models

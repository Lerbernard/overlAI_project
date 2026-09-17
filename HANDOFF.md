# Handoff notes for the OverlAI app

Written for whoever (or whatever) picks this project up next. Last updated 2026-09-17.
Start here, then read [README.md](README.md) for what the app does and
[docs/play-store.md](docs/play-store.md) for everything Google Play asks for.

## Where things stand

The app builds and runs, targets Android 16 (API 36), and is ready to be prepared for a Play
Store release. It has **not** been published yet, and no release keystore exists in this repo.

Two things happened recently, both already committed here:

1. **Play readiness and security fixes.**
2. **Video removed.** The app is images and screenshots only now.

## What changed, and why

### Security and Play requirements

- **Targets API 36** (compileSdk and targetSdk), with AGP 8.9.1 and Gradle 8.11.1. Google Play
  requires API 36 for new apps. `MediaProjectionManager.getMediaProjection` is nullable on the
  new SDK, so a used or revoked token now fails cleanly instead of crashing.
- **Widget buttons are locked down.** The two widget providers used to accept a custom broadcast
  action, which meant any app on the phone could toggle the overlay. The button now goes through
  `WidgetActionReceiver`, which is `exported="false"`; the providers only handle the system's
  `APPWIDGET_*` events.
- **Premium can't be self-granted.** `Account.setCloudPremium` is gone. The app only *reads*
  `users/{uid}.premium`. [`firestore.rules`](firestore.rules) makes that document read-and-delete
  only for its owner, and bounds what a bug report may contain. **These rules are not deployed
  yet**: paste them into the Firebase console (project `overlai-ddd41`) before release.
- **Ads are compiled out.** `BuildConfig.ADS_ENABLED = false`, so the Mobile Ads SDK is never
  initialised, the "Remove ads" card is hidden, and no advertising ID is collected. The manifest
  still carries Google's sample AdMob app id because the SDK requires one to be present. Turning
  ads on means: real AdMob ids, a UMP consent flow, and an updated data-safety form and privacy
  policy, in that order.
- **Manifest cleanup.** The deprecated `package=` attribute and `requestLegacyExternalStorage`
  are gone, `allowBackup="false"` with explicit backup and data-extraction rules, so History
  thumbnails and cached scores never leave the device.
- **The relay, not keys.** Detection goes to the Cloudflare worker with `X-App-Token` and
  `X-App-Version` headers; Sightengine's credentials live in the worker. The token ships inside
  the APK, so treat it as a rate-limit key, not a secret: rotate it per release, keep the
  worker's per-IP and per-token limits on, and retire old app versions there.
- Error messages no longer mention API keys.

### Images only

Screen recording, video detection, the video share-sheet target, gallery video picks, the video
player dialog and the media3 dependency are all gone. `HistoryManager` still deletes video files
left behind by older installs. If a request for video comes back, it is a new feature, not a
revert: the Play listing, privacy policy and data-safety answers all say images only now.

## Where things live

| What | Where |
|---|---|
| Kotlin sources | `app/src/main/java/` (flat, package `com.example.test103`; the installed id is `com.lerbernard.overlai`) |
| Play listing, data safety, release steps | `docs/play-store.md` |
| Firestore rules (not deployed) | `firestore.rules` |
| Website pages for the app | the separate `LMB-website` repo, under `app/ai-image-detector/app/` |

The app's privacy policy, terms, support and account-deletion pages are on the website at
`lmbtechnology.com/ai-image-detector/app/...`. **Those website pages are not published yet**;
they sit on an unpushed branch in the website repo while it waits for Google AdSense approval.
Links from the app will 404 until that branch goes live. `docs/*.html` in this repo are just
redirects to those pages.

## Building it

```
sdk.dir=...            # in local.properties, plus:
PROXY_BASE=https://<your-worker>.workers.dev
APP_TOKEN=<token the worker expects>
```

```
gradlew assembleDebug        # or bundleRelease for a Play upload
```

Use JDK 17 or newer (Android Studio's bundled JBR works). `local.properties`,
`keystore.properties` and `*.jks` are gitignored and must stay that way.

## What's left before a release

The full list is in `docs/play-store.md`. The short version:

1. Rotate `APP_TOKEN` in the worker.
2. Publish `firestore.rules` in the Firebase console.
3. Add the release signing certificate's SHA-1 and SHA-256 to Firebase, then download a fresh
   `google-services.json`, or Google sign-in fails silently.
4. Create the upload keystore and `keystore.properties` (never committed).
5. `gradlew bundleRelease`, test the release build on a device.
6. Play Console: app content, data safety, the foreground-service declarations with a short
   screen-recorded video, then internal testing before production.
7. After publishing, put the store URL into `PLAY_URL` in the website repo.

## House rules

- Don't commit `local.properties`, `keystore.properties` or any `.jks`.
- `google-services.json` is committed on purpose: Firebase treats it as configuration. What
  protects the project is the Firestore rules and the OAuth client's SHA restrictions.
- Keep `docs/play-store.md` in step with the code. If a change alters what the app collects or
  sends, update the data-safety answers and the website privacy policy in the same pass.

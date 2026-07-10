# ============================================================================
# OverlAI - R8 / ProGuard rules for release builds
# ============================================================================

# --- Fix: R8 errors on classes the ads SDK references but that don't exist
#     on this compileSdk (e.g. LoudnessCodecController). Treat as warnings. ---
-dontwarn android.media.LoudnessCodecController
-dontwarn com.google.android.gms.internal.ads.**
-dontwarn com.google.android.gms.**

# --- Google Mobile Ads (AdMob) ---
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.gms.internal.ads.** { *; }

# --- Firebase Auth + Firestore ---
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.firebase.**
# Firestore serializes model classes via reflection; keep any you pass to it.
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName *;
}

# --- OkHttp / Okio (networking to the proxy) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# --- org.json (used to parse Sightengine responses) ---
-dontwarn org.json.**

# --- Media3 / ExoPlayer (video preview) ---
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# --- Keep your custom Views (referenced from XML by name via reflection) ---
-keep class com.example.test103.PillToggleView { *; }
-keep class com.example.test103.OutlineIconView { *; }

# --- Keep TileService + Service classes referenced from the manifest ---
-keep class com.example.test103.OverlayTileService { *; }
-keep class com.example.test103.QuickCheckTile { *; }
-keep class com.example.test103.OverlayService { *; }
-keep class com.example.test103.QuickShotService { *; }
-keep class com.example.test103.ScreenshotActivity { *; }
-keep class com.example.test103.ShareDetectActivity { *; }

# Keep line numbers for readable crash reports (optional but recommended)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

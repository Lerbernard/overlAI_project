import java.util.Properties

plugins {
    id("com.google.gms.google-services")
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
}

// ✅ Read secrets from local.properties (which is git-ignored by default)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// ✅ keys no longer live in the app; only the proxy URL + a shared app token do
val proxyBase: String = localProps.getProperty("PROXY_BASE", "")
val appToken: String = localProps.getProperty("APP_TOKEN", "")

// ✅ Signing config, read from keystore.properties (git-ignored, never committed)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.example.test103"
    compileSdk = 36

    defaultConfig {
        // Installed package ID. The Kotlin namespace stays com.example.test103 so no source
        // files need to move; only the applicationId is what users and Play see.
        applicationId = "com.lerbernard.overlai"
        minSdk = 24
        targetSdk = 36   // Google Play requires API 36 for new apps since 31 Aug 2026
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ✅ Keys are injected at build time — never committed to git
        buildConfigField("String", "PROXY_BASE", "\"$proxyBase\"")
        buildConfigField("String", "APP_TOKEN", "\"$appToken\"")
        // Ads stay off until real AdMob unit IDs exist and a consent (UMP) flow is in place.
        // With ADS_ENABLED = false the Mobile Ads SDK is never initialised and the premium card is hidden.
        buildConfigField("boolean", "ADS_ENABLED", "false")
    }

    buildFeatures {
        buildConfig = true   // required for buildConfigField on AGP 8+
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true   // ✅ shrink + obfuscate release builds
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ✅ sign the release build if keystore.properties is present
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Networking (✅ duplicate okhttp line removed; dotenv-kotlin removed — it
    // doesn't work on Android, BuildConfig replaces it)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // ✅ Google Mobile Ads (AdMob)
    implementation("com.google.android.gms:play-services-ads:23.6.0")

    // ✅ Firebase (Auth + Firestore) and Google Sign-In
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
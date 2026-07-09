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
val seApiUser: String = localProps.getProperty("SE_API_USER", "")
val seApiSecret: String = localProps.getProperty("SE_API_SECRET", "")

android {
    namespace = "com.example.test103"
    compileSdk = 34

    defaultConfig {
        // ✅ REAL app identity. namespace stays com.example.test103 so no
        // source files need to change — only the installed package ID is renamed.
        applicationId = "com.lerbernard.overlai"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ✅ Keys are injected at build time — never committed to git
        buildConfigField("String", "SE_API_USER", "\"$seApiUser\"")
        buildConfigField("String", "SE_API_SECRET", "\"$seApiSecret\"")
    }

    buildFeatures {
        buildConfig = true   // required for buildConfigField on AGP 8+
    }

    buildTypes {
        release {
            isMinifyEnabled = true   // ✅ shrink + obfuscate release builds
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
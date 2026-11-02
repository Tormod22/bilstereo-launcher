plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "no.bil.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "no.bil.launcher"
        minSdk = 26          // dekker Android 8–13 som disse enhetene kjører
        targetSdk = 34
        versionCode = 11
        versionName = "3.5"
        // Bilstereoen har ARM-prosessor; dropper x86 for å holde appen mindre (kartmotoren har egne biblioteker)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    // Fast signatur for bygg på GitHub: nøkkelen ligger som hemmelighet (Secrets) og gis hit via miljøvariabler.
    // Android godtar bare oppdateringer med samme signatur som appen som er installert.
    // Lokale bygg uten hemmeligheten får vanlig utviklersignatur (kan ikke oppdatere en GitHub-installert T'OS).
    val keystore = System.getenv("TOS_KEYSTORE")
    val keystorePass = System.getenv("TOS_KEYSTORE_PASSWORD")
    signingConfigs {
        if (keystore != null && keystorePass != null) create("tos") {
            storeFile = file(keystore)
            storePassword = keystorePass
            keyAlias = "tos"
            keyPassword = keystorePass
        }
    }
    buildTypes {
        debug { signingConfigs.findByName("tos")?.let { signingConfig = it } }
        release { isMinifyEnabled = false; signingConfigs.findByName("tos")?.let { signingConfig = it } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")   // QR-kode for parkeringsplassen
    implementation("org.maplibre.gl:android-sdk:11.8.0")   // kart uten nett (PMTiles fra OpenStreetMap)
}

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
        // På GitHub: byggnummeret (1000 + nr.) så hver nye versjon alltid får høyere tall enn den forrige.
        // Lokalt: tallet under. (versionName = «x.y» lokalt, «x.y.nr» på GitHub.)
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (ciRun != null) 1000 + ciRun else 37
        versionName = if (ciRun != null) "5.15.$ciRun" else "5.15"
        // Bilstereoen er 64-bit ARM (kartleggingen: ro.product.cpu.abi = arm64-v8a). Bare den kopien av kartmotoren
        // (libmaplibre.so) tas med – 32-bit-kopien var 8 MB ekstra i hver nedlasting. T'OS kjører allerede som 64-bit der.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Fast signatur for bygg på GitHub: nøkkelen ligger som hemmelighet (Secrets) og gis hit via miljøvariabler.
    // Android godtar bare oppdateringer med samme signatur som appen som er installert.
    // Lokale bygg uten hemmeligheten får vanlig utviklersignatur (kan ikke oppdatere en GitHub-installert T'OS).
    val keystore = System.getenv("TOS_KEYSTORE")
    val keystorePass = System.getenv("TOS_KEYSTORE_PASSWORD")
    // Nøkkelens eget passord: eget hvis TOS_KEY_PASSWORD er satt, ellers det samme som for nøkkelfila
    val keyPass = System.getenv("TOS_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: keystorePass
    signingConfigs {
        if (keystore != null && keystorePass != null) create("tos") {
            storeFile = file(keystore)
            storePassword = keystorePass
            keyAlias = "tos"
            keyPassword = keyPass
        }
    }
    buildTypes {
        debug { signingConfigs.findByName("tos")?.let { signingConfig = it } }
        // GitHub publiserer release-bygget (raskere, ikke «debuggable»). Samme nøkkel og samme applicationId som før,
        // så det installeres oppå tidligere T'OS. Lokalt uten nøkkel: vanlig utviklersignatur.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("tos") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Testene bruker Android-klasser bare indirekte; tomme svar i stedet for krasj
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric (ScreensSmokeTest): ekte Android-ressurser og manifest i JVM-testene
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            // En feilet test skal vises med hele feilmeldingen i GitHub-loggen (ikke bare «FAILED»)
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Bare kjernen (Icons, materialIcon/materialPath). Ikonene T'OS bruker ligger i no.bil.launcher.icons (kopiert fra
    // material-icons-extended) – hele biblioteket ga ≈ 30 MB ekstra dex og tregere kaldstart på stereoen.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")   // QR-kode for parkeringsplassen
    implementation("org.maplibre.gl:android-sdk:11.8.0")   // kart uten nett (PMTiles fra OpenStreetMap)
    implementation("dev.mobile:dadb:1.2.9") {   // «Ta over stereoen»: T'OS kobler seg til sin egen ADB (Apache-2.0)
        exclude(group = "org.graalvm.buildtools")   // bare en testhjelper i dadb sitt oppsett, trengs ikke i appen
    }
    testImplementation("junit:junit:4.13.2")
    // «Alle skjermer åpner uten krasj»: Compose-UI kjøres i Robolectric (ingen stereo/emulator nødvendig)
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

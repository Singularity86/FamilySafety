import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.compose.compiler)  // ADD THIS

}

// Upload-key signing for Play. keystore/keystore.properties and the .jks are
// gitignored — back both up outside the repo; losing the upload key means a
// support ticket with Google to rotate it.
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore/keystore.properties")
    if (propsFile.exists()) load(FileInputStream(propsFile))
}

// Optional broker credentials, kept out of git (keystore/ is ignored).
// Create keystore/mqtt.properties with:
//   username=...
//   password=...
// Absent file → anonymous connection (current dev behavior on public HiveMQ).
// Avoid double quotes and backslashes in the password.
val mqttProperties = Properties().apply {
    val propsFile = rootProject.file("keystore/mqtt.properties")
    if (propsFile.exists()) load(FileInputStream(propsFile))
}

android {
    // namespace stays com.example.* on purpose: it only scopes source code and R,
    // is never seen by Play, and changing it would mean moving every package.
    namespace = "com.example.familysafety"
    compileSdk = 36

    defaultConfig {
        // Play identity — permanent once published. Must never be com.example.*.
        applicationId = "jibaro.spacepirate.love"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "1.12.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // Include all ABIs shipped by lazysodium-android and JNA.
            // arm64-v8a covers modern phones (S20 FE and most devices since ~2017).
            // armeabi-v7a covers older 32-bit ARM phones.
            // x86_64 covers emulators.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Separate applicationId so a debug build installs ALONGSIDE the Play
            // release instead of being refused for having a different signing key.
            //
            // This is what makes the app testable at all. Without it, trying a local
            // build on a device that has the Play version means uninstalling first,
            // which wipes the group and the identity keys — so the only way to exercise
            // a change was to ship it to the family and watch. The debug app has its own
            // storage and its own identity, so it joins as an ordinary extra member: a
            // phone plus an emulator, both on debug, form a throwaway test family while
            // the real one carries on untouched on the release build.
            //
            // Release builds do not plant Timber, so this is also the only build that
            // produces app logs.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "MQTT_ENVIRONMENT", "\"DEVELOPMENT\"")
            buildConfigField("String", "MQTT_USERNAME", "\"${mqttProperties.getProperty("username", "")}\"")
            buildConfigField("String", "MQTT_PASSWORD", "\"${mqttProperties.getProperty("password", "")}\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "MQTT_ENVIRONMENT", "\"PRODUCTION\"")
            buildConfigField("String", "MQTT_USERNAME", "\"${mqttProperties.getProperty("username", "")}\"")
            buildConfigField("String", "MQTT_PASSWORD", "\"${mqttProperties.getProperty("password", "")}\"")
            // Unsigned release builds still work when the keystore is absent
            // (e.g. a fresh clone); Play uploads require the keystore.
            signingConfig = if (keystoreProperties.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else null

            // Play warns that the bundle carries native code without debug symbols.
            // This setting is the documented fix, but it currently collects nothing and
            // the warning will persist: every .so we ship (libsodium, libsqlcipher,
            // libjnidispatch, ML Kit, CameraX) is a prebuilt binary from a dependency,
            // already stripped by its vendor, and this module compiles no native code of
            // its own for AGP to extract symbols from. Kept anyway — it costs one
            // no-op task and starts producing symbols automatically the day we add
            // native sources or a dependency ships unstripped libraries.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Room needs somewhere to write the exported schema JSON. Committing app/schemas/ is what
// makes migrations testable: MigrationTestHelper reconstructs the old schema from the file,
// runs the migration and asserts the result, instead of the migration only being exercised
// on a user's device after release.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")
    
    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.48.1")
    ksp("com.google.dagger:hilt-android-compiler:2.48.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    
    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // Cryptography - Lazysodium
    implementation("com.goterl:lazysodium-android:5.2.0@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    
    // Location Services
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Map - OpenStreetMap via osmdroid (no API key required)
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    
    // MQTT - core client only; the Android service wrapper (org.eclipse.paho.android.service)
    // is incompatible with Android 12+ (AlarmPingSender uses PendingIntent without
    // FLAG_IMMUTABLE). MqttAsyncClient from the core library is used directly instead.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    
    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Room Database with encryption
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // SQLCipher for encrypted database.
    // net.zetetic:sqlcipher-android supersedes the retired android-database-sqlcipher
    // artifact; the old one's last release (4.5.4) ships a 4 KB-aligned libsqlcipher.so,
    // which will not load at all on a 16 KB-page device. Package name changed with it:
    // net.sqlcipher.database -> net.zetetic.database.sqlcipher.
    implementation("net.zetetic:sqlcipher-android:4.9.0")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    
    // QR Codes
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // ML Kit Barcode Scanning — 17.3.0 is the first release whose libbarhopper_v3.so is
    // 16 KB-page aligned.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // CameraX — 1.4.x aligns libimage_processing_util_jni.so for 16 KB pages.
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    
    // NanoHTTPD — lightweight embedded HTTP server for local avatar serving
    implementation("org.nanohttpd:nanohttpd:2.3.1")


    // WorkManager — periodic watchdog to revive LocationService if killed by the OS
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Timber Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.76")

    // Coil — Compose-native image loading (for file thumbnails)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Material Design (for XML themes)
    implementation("com.google.android.material:material:1.11.0")    

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    // MigrationTestHelper — reads the exported schemas above to verify migrations.
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("io.mockk:mockk-android:1.13.8")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

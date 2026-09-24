plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// No server addresses or Firebase settings are built into the app: people type the servers at login,
// and the friends server supplies its Firebase settings. The only private build setting is the
// release signing key, kept outside the repository in ~/.gradle/gradle.properties:
//   ISAIPETTI_KEYSTORE, ISAIPETTI_KEYSTORE_PASSWORD, ISAIPETTI_KEY_ALIAS
fun privateSetting(name: String): String? = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.devasenan134.isaipetti"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.devasenan134.isaipetti"
        minSdk = 26
        targetSdk = 36
        versionCode = 39
        versionName = "0.10.0"

        // Where the app looks for new versions (GitHub Releases).
        buildConfigField("String", "GITHUB_REPO", "\"${privateSetting("ISAIPETTI_GITHUB_REPO") ?: "devasenan134/isaipetti"}\"")
    }

    signingConfigs {
        val keystore = privateSetting("ISAIPETTI_KEYSTORE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = privateSetting("ISAIPETTI_KEYSTORE_PASSWORD")
                keyAlias = privateSetting("ISAIPETTI_KEY_ALIAS") ?: "isaipetti"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds may talk to a test server over plain http (e.g. http://10.0.2.2:8095 from the emulator).
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            manifestPlaceholders["cleartext"] = "false"
            // Every release must be signed with the same key, or phones refuse the update.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.androidx.palette) // colours from cover art, for the player's background

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging) // push notifications
}

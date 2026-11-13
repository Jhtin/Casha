plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Private signing material is read only after the owner explicitly opts in.
val useProductionSigning = providers.gradleProperty("enableProductionSigning").orNull == "true"
fun signingEnvironment(name: String): String = providers.environmentVariable(name).orNull
    ?.takeIf { it.isNotBlank() } ?: error("Production signing requires $name to be set locally.")

android {
    namespace = "com.example.casha"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.casha"
        minSdk = 26
        targetSdk = 36
        versionCode = 38
        versionName = "2.12.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (useProductionSigning) {
            create("production") {
                storeFile = file(signingEnvironment("CASHA_RELEASE_STORE_FILE"))
                require(storeFile!!.canonicalFile != file("debug.keystore").canonicalFile) {
                    "Use the approved release keystore, not the personal debug keystore."
                }
                storePassword = signingEnvironment("CASHA_RELEASE_STORE_PASSWORD")
                keyAlias = signingEnvironment("CASHA_RELEASE_KEY_ALIAS")
                keyPassword = signingEnvironment("CASHA_RELEASE_KEY_PASSWORD")
            }
        }
        if (file("debug.keystore").exists()) create("firebaseAuth") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            if (useProductionSigning) signingConfig = signingConfigs.getByName("production")
            optimization {
                enable = false
            }
        }
        debug {
            signingConfigs.findByName("firebaseAuth")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.work)
    implementation(libs.mlkit.text)
    implementation(libs.androidx.biometric)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

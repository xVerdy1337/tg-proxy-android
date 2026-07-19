plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tgwsproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tgwsproxy"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }

    }

    // Pin the NDK so CI installs it in a dedicated, cached step instead of AGP auto-provisioning
    // it mid-build (a corrupt sdkmanager download fails the whole configuration phase otherwise).
    ndkVersion = "25.1.8937393"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Release signing pulls its keystore + passwords ONLY from environment variables
        // (populated from CI secrets). There is no default/fallback password: if no keystore
        // is provided the release is left unsigned rather than signed with a public key.
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            // isNullOrEmpty: an unset secret arrives as "" (empty), and file("") throws
            // "path may not be null or empty" — treat empty the same as absent (unsigned release).
            if (!ksPath.isNullOrEmpty() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Uses the SDK's auto-generated per-developer debug keystore (never shipped).
        }
        release {
            isMinifyEnabled = true
            // Attach the release signing config only when a keystore was actually provided
            // (KEYSTORE_PATH points at an existing file); otherwise leave the release unsigned
            // instead of falling back to a public debug key.
            val releaseKsPath = System.getenv("KEYSTORE_PATH")
            if (!releaseKsPath.isNullOrEmpty() && file(releaseKsPath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // OkHttp for WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Unit tests (pure-JVM proxy logic: crypto framing, TLS records, handshake)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.22")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

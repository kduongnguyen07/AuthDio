plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.mdownload"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.mdownload"
        minSdk = 35
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            isDefault = true
            ndk {
                // Personal / modern 64-bit ARM build (vivo X200s & Android 15+ arm64-v8a devices).
                // Strips unused armeabi-v7a/x86/x86_64 native payloads (~138.8 MB savings).
                abiFilters += listOf("arm64-v8a")
            }
        }
        create("universal") {
            dimension = "abi"
            ndk {
                // Multi-ABI build preserved for emulators, cross-architecture APKs, and Play AAB distribution.
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }

    packaging {
        jniLibs {
            // Required by youtubedl-android so libpython.so, libqjs.so, and libffmpeg.so are extracted as executables.
            useLegacyPackaging = true
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // yt-dlp + embedded Python + QuickJS + FFmpeg stack (Android counterpart to HiFi Deck)
    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)

    // Media3 ExoPlayer + MediaSessionService for offline-first music player & system media controls
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    // Coil for async source artwork & thumbnails
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

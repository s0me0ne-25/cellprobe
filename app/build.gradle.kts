// SPDX-License-Identifier: GPL-3.0-or-later

// Kotlin compilation is provided by AGP's built-in Kotlin support,
// so the org.jetbrains.kotlin.android plugin is not applied here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.s0me0ne25.cellprobe"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.s0me0ne25.cellprobe"
        minSdk = 24
        targetSdk = 37
        // versionCode = MAJOR * 10000 + MINOR * 100 + PATCH
        versionCode = 1
        versionName = "0.0.1"
    }

    // Distribution channels. Channel-specific code lives only in flavor
    // source sets (e.g. the self-updater will go to src/github).
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
        }
        create("github") {
            dimension = "distribution"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Do not embed the git commit hash: builds from a source
            // tarball must produce the same APK as builds from a clone.
            vcsInfo {
                include = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time and other newer JDK APIs on API 24-25
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    lint {
        // AGP follows the latest stable Android Studio, not the latest AGP.
        disable += "AndroidGradlePluginVersion"
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    // The dependency metadata block is encrypted with a Google key and
    // breaks reproducible builds; F-Droid requires it to be absent.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.conscrypt.android)
    implementation(libs.brotli.dec)
    implementation(libs.zstd.okio)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.okhttp.mockwebserver)
}

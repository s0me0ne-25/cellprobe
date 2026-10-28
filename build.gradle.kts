// SPDX-License-Identifier: GPL-3.0-or-later

// Plugins are declared here once with versions and applied in the modules.
// Declaring kotlin-jvm here also pins the Kotlin Gradle plugin version
// that AGP's built-in Kotlin support uses for the :app module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

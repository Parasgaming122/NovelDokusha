// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        // BC1 fix: removed jcenter() — deprecated since 2021, sunset in 2022. No buildscript
        // dependency actually used it.
        mavenCentral()
    }
}

plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.scripting) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    // BC10 fix: dependency-analysis plugin was declared with `apply false` but never actually
    // applied anywhere, so `./gradlew buildHealth` did nothing. Removed the declaration to
    // avoid confusion. Re-add with `apply true` at the root project if you want it active.
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

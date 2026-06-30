package my.noveldoksuha.convention.plugin

import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal fun Project.applyKSP() {
    with(pluginManager) {
        apply("com.google.devtools.ksp")
    }
}

internal fun Project.applyHilt() {
    applyKSP()

    with(pluginManager) {
        apply("com.google.dagger.hilt.android")
    }
    dependencies {
        implementation(libs.findLibrary("hilt.android").get())
        "ksp"(libs.findLibrary("hilt-compiler").get())
        "ksp"(libs.findLibrary("hilt-androidx-compiler").get())
        // BC3 fix: also add hilt-workmanager so modules that use @HiltWorker don't have to
        // declare it themselves. Currently only :app uses @HiltWorker, but having it in the
        // convention plugin future-proofs any library module that wants to define a Worker.
        // The androidx.hilt:hilt-compiler (ksp) above generates the HiltWorkerFactory bindings.
        implementation(libs.findLibrary("hilt-workmanager").get())
    }
}
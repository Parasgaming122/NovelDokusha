plugins {
    alias(libs.plugins.noveldokusha.android.library)
    alias(libs.plugins.noveldokusha.android.compose)
}

android {
    namespace = "my.noveldokusha.tooling.text_translator"
}

dependencies {
    implementation(projects.core)
    implementation(projects.networking)
    implementation(projects.tooling.textTranslator.domain)

    // OkHttp for API calls (Gemini, OpenAI, Google PA)
    implementation(libs.okhttp)

    // Gson for JSON parsing
    implementation(libs.gson)
}

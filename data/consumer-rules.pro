# Consumer ProGuard rules for the :data module.
#
# These rules are applied automatically to any module that depends on :data.
# They keep the classes that :data relies on for JSON parsing (Moshi + Gson
# + Kotlin reflection) and Room's generated implementations working under
# R8 shrinking.

# ── Moshi (used by PersistentCacheDataLoader via KotlinJsonAdapterFactory) ───
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class *
-keepclassmembers class * {
    @com.squareup.moshi.Json <methods>;
    @com.squareup.moshi.JsonClass <fields>;
}
-keepattributes Signature
-keepattributes *Annotation*

# ── Gson (used by GeminiApiClient for paragraph JSON serialization) ─────────
-keep class com.google.gson.** { *; }
-keep class my.noveldokusha.scraper.sources.GeminiApiClient$ParagraphItem { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Kotlinx Serialization metadata ──────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ── Room (DAOs + entity tables in :data) ────────────────────────────────────
-keep class androidx.room.** { *; }
-keep class my.noveldoksuha.data.** { *; }
-keep class my.noveldoksuha.data.storage.** { *; }

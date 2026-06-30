# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep public class * extends androidx.lifecycle.ViewModel { *; }




#### Kotlin Serialization
# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-dontwarn org.slf4j.impl.StaticLoggerBinder

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

-assumenosideeffects class timber.log.Timber* {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

#### Gemini API Client (TimoTxt Gemini source)
# Gson uses reflection to serialize/deserialize ParagraphItem. Keep its fields.
-keep class my.noveldokusha.scraper.sources.GeminiApiClient$ParagraphItem { *; }
-keep class my.noveldokusha.scraper.sources.GeminiApiClient { *; }
-keep class my.noveldokusha.scraper.sources.TimoTxtGemini { *; }
-keep class my.noveldokusha.scraper.sources.TimoTxtTranslate { *; }

#### AllNovel source (added 2026-06-30)
-keep class my.noveldokusha.scraper.sources.AllNovel { *; }

# Gson itself
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**

#### Cloudflare interceptor — keep WebView-related classes (R8 sometimes strips
#### WebSettings listeners when minify is enabled). The interceptor is the only
#### runtime path that can solve CF JS challenges for the catalog sources.
-keep class my.noveldokusha.network.interceptors.CloudFareVerificationInterceptor { *; }
-keep class my.noveldokusha.network.interceptors.UserAgentInterceptor { *; }
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}

# Project-level ProGuard rules for the :data module.
#
# This file is intentionally a superset of consumer-rules.pro — it adds rules
# that are only safe at app-level (e.g. keeping entire packages for reflection)
# but kept here too in case :data is built as a standalone module for testing.

-keep class com.squareup.moshi.** { *; }
-keep class com.google.gson.** { *; }
-keep class androidx.room.** { *; }
-keep class my.noveldoksuha.data.** { *; }

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

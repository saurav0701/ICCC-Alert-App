# ProGuard / R8 rules for the ICCC Alert App.
#
# isMinifyEnabled is currently false, so none of this is applied yet. It is
# written and kept in sync so that enabling minification is a one-line change
# rather than a debugging session.
#
# Before turning minification on: build a release APK, install it, and walk
# through login, the channel list, a VA event, a VTS event, the map, saving a
# comment, and PDF export. R8 problems surface only in release builds.

# ── Keep line numbers in crash reports ────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Gson ──────────────────────────────────────────────────────────────────
# Gson reads and writes these classes reflectively. If R8 renames the fields,
# @SerializedName no longer lines up with the JSON and every event silently
# fails to parse.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Fields annotated for Gson must keep their names.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Generic signatures of TypeToken are needed for collection deserialisation.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# ── App models ────────────────────────────────────────────────────────────
# Every class that crosses the WebSocket or HTTP boundary as JSON.
-keep class com.example.iccc_alert_app.Event { *; }
-keep class com.example.iccc_alert_app.Channel { *; }
-keep class com.example.iccc_alert_app.SubscriptionFilter { *; }
-keep class com.example.iccc_alert_app.SubscriptionRequestV2 { *; }
-keep class com.example.iccc_alert_app.SyncStateInfo { *; }
-keep class com.example.iccc_alert_app.GpsEvent { *; }
-keep class com.example.iccc_alert_app.GpsEventData { *; }
-keep class com.example.iccc_alert_app.GpsLocation { *; }
-keep class com.example.iccc_alert_app.GeofenceInfo { *; }
-keep class com.example.iccc_alert_app.GeofenceAttributes { *; }
-keep class com.example.iccc_alert_app.GeoJsonGeometry { *; }
-keep class com.example.iccc_alert_app.SavedMessage { *; }
-keep class com.example.iccc_alert_app.CameraInfo { *; }

# Auth request/response models.
-keep class com.example.iccc_alert_app.auth.** { *; }

# ── OkHttp / Okio ─────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepclassmembers class okhttp3.internal.** { *; }

# ── osmdroid ──────────────────────────────────────────────────────────────
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ── ExoPlayer ─────────────────────────────────────────────────────────────
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ── Kotlin coroutines ─────────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── Android components referenced from the manifest ───────────────────────
-keep class com.example.iccc_alert_app.WebSocketService { *; }
-keep class com.example.iccc_alert_app.BootReceiver { *; }
-keep class com.example.iccc_alert_app.DozeExemptionReceiver { *; }
-keep class com.example.iccc_alert_app.MyApplication { *; }

# ── Enums used by name (priority levels, alert types) ─────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── JARVIS ProGuard Rules v7.0 ──────────────────────────────────

# ─── Keep all JARVIS classes ──────────────────────────────────────
-keep class com.jarvis.assistant.** { *; }
-keepclassmembers class com.jarvis.assistant.** { *; }

# ─── JNI / Rust Bridge ──────────────────────────────────────────
# Keep all classes with native methods — the Rust JNI functions
# depend on exact class+method names matching the JNI naming convention.
# CRITICAL: This prevents R8 from stripping JNI methods in release builds.
-keepclassmembers class com.jarvis.assistant.jni.RustBridge {
    native <methods>;
}
-keep class com.jarvis.assistant.jni.RustBridge { *; }
-keep class com.jarvis.assistant.jni.RustBridge$* { *; }

# ─── Shizuku ────────────────────────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep class com.jarvis.assistant.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn rikka.sui.**

# ─── MQTT (Paho) ────────────────────────────────────────────────
-keep class org.eclipse.paho.** { *; }
-keep class com.jarvis.assistant.smarthome.** { *; }
-dontwarn org.eclipse.paho.**
-dontwarn org.eclipse.paho.client.mqttv3.**
-keepclassmembers class org.eclipse.paho.client.mqttv3.** {
    *;
}

# ─── Gson ───────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-dontwarn com.google.gson.**
# Gson specific classes
-dontwarn sun.misc.**
-keep class com.google.gson.stream.** { *; }
# Prevent R8 from stripping interface information from TypeAdapter
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
# Keep classes with @SerializedName
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ─── Serializable / Parcelable ───────────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ─── Kotlin Coroutines ──────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ─── Compose ────────────────────────────────────────────────────
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.animation.** { *; }
-keep @androidx.compose.runtime.Composable class *
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ─── AndroidX ───────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.appwidget.AppWidgetProvider

# ─── JARVIS Models (used in JSON serialization) ─────────────────
-keep class com.jarvis.assistant.ui.screens.ChatMessage { *; }
-keep class com.jarvis.assistant.ui.screens.ChatSession { *; }
-keep class com.jarvis.assistant.ui.screens.SmartDevice { *; }
-keep class com.jarvis.assistant.ui.screens.DeviceType { *; }
-keep class com.jarvis.assistant.automation.RoutineEngine$* { *; }
-keep class com.jarvis.assistant.viewmodel.JarvisViewModel$HistoryEntry { *; }

# ─── Action Handler (sealed class with JSON parsing) ────────────
-keep class com.jarvis.assistant.actions.ActionHandler { *; }
-keep class com.jarvis.assistant.actions.ActionHandler$ActionResult { *; }
-keep class com.jarvis.assistant.actions.ActionHandler$ActionResult$* { *; }

# ─── Audio Engine (VAD state enum) ──────────────────────────────
-keep class com.jarvis.assistant.audio.AudioEngine { *; }
-keep class com.jarvis.assistant.audio.AudioEngine$VadState { *; }
-keep class com.jarvis.assistant.audio.VoicePatternCallback { *; }

# ─── Brain State enum ──────────────────────────────────────────
-keep class com.jarvis.assistant.ui.orb.BrainState { *; }

# ─── General Android ────────────────────────────────────────────
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Don't strip any native methods — ensures JNI bridges are never removed
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── Command Router sealed classes (RouteResult) ────────────────
-keep class com.jarvis.assistant.router.CommandRouter { *; }
-keep class com.jarvis.assistant.router.CommandRouter$RouteResult { *; }
-keep class com.jarvis.assistant.router.CommandRouter$RouteResult$* { *; }

# ─── Notification Data classes ──────────────────────────────────
-keep class com.jarvis.assistant.notifications.NotificationData { *; }
-keep class com.jarvis.assistant.notifications.NotificationReaderService { *; }

# ─── App Registry (fuzzy matching) ─────────────────────────────
-keep class com.jarvis.assistant.actions.AppRegistry { *; }

# ─── Task Executor Bridge sealed classes ────────────────────────
-keep class com.jarvis.assistant.automation.TaskExecutorBridge { *; }
-keep class com.jarvis.assistant.automation.TaskExecutorBridge$StepResult { *; }
-keep class com.jarvis.assistant.automation.TaskExecutorBridge$StepResult$* { *; }

# ─── Gemini Function Caller ────────────────────────────────────
-keep class com.jarvis.assistant.automation.GeminiFunctionCaller { *; }
-keep class com.jarvis.assistant.automation.GeminiFunctionCaller$ProcessResult { *; }
-keep class com.jarvis.assistant.automation.GeminiFunctionCaller$ProcessResult$* { *; }

# ─── Room Database entities ─────────────────────────────────────
-keep class com.jarvis.assistant.data.local.MessageEntity { *; }
-keep class com.jarvis.assistant.data.local.JarvisDatabase { *; }
-keep class com.jarvis.assistant.data.local.MessageDao { *; }
-keep class com.jarvis.assistant.data.local.ConversationMemory { *; }
-keep class com.jarvis.assistant.data.local.MemoryDao { *; }
-keep @androidx.room.Entity class *
-dontwarn androidx.room.**
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao class *
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * {
    <fields>;
}
-keep @androidx.room.Database class *
-keep @androidx.room.TypeConverter class *

# ─── OkHttp ─────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ─── System Action Executor ─────────────────────────────────────
-keep class com.jarvis.assistant.router.SystemActionExecutor { *; }

# ─── Gemini API Client ─────────────────────────────────────────
-keep class com.jarvis.assistant.network.GeminiApiClient { *; }
-keep class com.jarvis.assistant.network.GeminiApiClient$ApiResult { *; }
-keep class com.jarvis.assistant.network.GeminiApiClient$ApiResult$* { *; }

-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.StringConcatFactory

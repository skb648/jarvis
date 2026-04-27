# ─── JARVIS ProGuard Rules ───────────────────────────────────────

# ─── JNI / Rust Bridge ──────────────────────────────────────────
# Keep all classes with native methods — the Rust JNI functions
# depend on exact class+method names matching the JNI naming convention.
-keepclassmembers class com.jarvis.assistant.jni.RustBridge {
    native <methods>;
}
-keep class com.jarvis.assistant.jni.RustBridge { *; }

# ─── Shizuku ────────────────────────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep class com.jarvis.assistant.shizuku.** { *; }

# ─── MQTT (Paho) ────────────────────────────────────────────────
-keep class org.eclipse.paho.** { *; }
-keep class com.jarvis.assistant.smarthome.** { *; }

# ─── Gson ───────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

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

# ─── Compose ────────────────────────────────────────────────────
-dontwarn androidx.compose.**

# ─── AndroidX ───────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.appwidget.AppWidgetProvider

# ─── JARVIS Models (used in JSON serialization) ─────────────────
-keep class com.jarvis.assistant.ui.screens.ChatMessage { *; }
-keep class com.jarvis.assistant.ui.screens.SmartDevice { *; }
-keep class com.jarvis.assistant.automation.RoutineEngine$* { *; }

# ─── General Android ────────────────────────────────────────────
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.StringConcatFactory

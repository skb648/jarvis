/**
 * jni_bridge_stub.c — Stub JNI bridge when Rust .so is not available.
 *
 * Provides safe no-op implementations for ALL JNI functions declared
 * in RustBridge.kt. This prevents UnsatisfiedLinkError crashes when
 * the Rust core hasn't been compiled yet.
 *
 * Each stub logs a warning and returns a safe default value.
 * This allows the app to run in "fallback mode" with the Kotlin-only
 * implementation while the Rust native core is being built.
 */
#include <android/log.h>
#include <jni.h>

#define LOG_TAG "JarvisJNI"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

__attribute__((constructor))
static void on_library_load(void) {
    LOGI("JARVIS JNI stub library loaded (Rust core not built). Run rust/build.sh first.");
}

/* ─── JNI Stub Implementations ──────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_jarvis_assistant_jni_RustBridge_nativeInitialize(
    JNIEnv *env, jclass clazz, jstring geminiKey, jstring elevenLabsKey) {
    LOGI("STUB: nativeInitialize called — Rust core not available, using Kotlin fallback");
    // FIX v12: Return JNI_FALSE so the app knows Rust is NOT actually available.
    // Previously returned JNI_TRUE which caused the app to think Rust was loaded,
    // leading to failed native calls and confusing error messages.
    // The Kotlin HTTP fallback path is used when RustBridge.isNativeReady() is false,
    // so we must be honest that the stub doesn't actually implement AI functionality.
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_jarvis_assistant_jni_RustBridge_nativeProcessQuery(
    JNIEnv *env, jclass clazz, jstring query, jstring context, jstring historyJson) {
    LOGI("STUB: nativeProcessQuery called — Rust core not available, use Kotlin HTTP fallback");
    return (*env)->NewStringUTF(env, "Please use the Kotlin HTTP fallback for AI queries. Rust core is not built.");
}

JNIEXPORT jstring JNICALL
Java_com_jarvis_assistant_jni_RustBridge_nativeProcessQueryWithImage(
    JNIEnv *env, jclass clazz, jstring query, jstring imageBase64, jstring mimeType) {
    LOGI("STUB: nativeProcessQueryWithImage called — Rust core not available");
    return (*env)->NewStringUTF(env, "[ERROR] Vision queries require the Rust core. Please build with rust/build.sh.");
}

JNIEXPORT jstring JNICALL
Java_com_jarvis_assistant_jni_RustBridge_nativeAnalyzeAudio(
    JNIEnv *env, jclass clazz, jbyteArray audioData, jint sampleRate) {
    LOGI("STUB: nativeAnalyzeAudio called — Rust core not available, returning neutral");
    return (*env)->NewStringUTF(env, "{\"emotion\":\"neutral\",\"confidence\":0.5,\"energy\":0.0}");
}

JNIEXPORT jboolean JNICALL
Java_com_jarvis_assistant_jni_RustBridge_nativeDetectWakeWord(
    JNIEnv *env, jclass clazz, jbyteArray audioData, jint sampleRate) {
    LOGW("STUB: nativeDetectWakeWord called — Rust core not available");
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_jarvis_assistant_jni_RustBridge_nativeAnalyzeEmotion(
    JNIEnv *env, jclass clazz, jstring text) {
    LOGW("STUB: nativeAnalyzeEmotion called — Rust core not available");
    return (*env)->NewStringUTF(env, "{\"emotion\":\"neutral\",\"confidence\":0.5}");
}

JNIEXPORT jstring JNICALL
Java_com_jarvis_assistant_jni_RustBridge_nativeSynthesizeSpeech(
    JNIEnv *env, jclass clazz, jstring text, jstring voiceId,
    jfloat stability, jfloat similarityBoost) {
    LOGW("STUB: nativeSynthesizeSpeech called — Rust core not available");
    return (*env)->NewStringUTF(env, "");
}

JNIEXPORT jdouble JNICALL
Java_com_jarvis_assistant_jni_RustBridge_nativeGetAudioAmplitude(
    JNIEnv *env, jclass clazz, jbyteArray audioData) {
    LOGW("STUB: nativeGetAudioAmplitude called — Rust core not available");
    return 0.0;
}

JNIEXPORT jboolean JNICALL
Java_com_jarvis_assistant_jni_RustBridge_nativeHealthCheck(
    JNIEnv *env, jclass clazz) {
    LOGI("STUB: nativeHealthCheck called — stub is healthy");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_jarvis_assistant_jni_RustBridge_nativeShutdown(
    JNIEnv *env, jclass clazz) {
    LOGW("STUB: nativeShutdown called — Rust core not available");
}

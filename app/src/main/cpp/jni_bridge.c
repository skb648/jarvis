/**
 * jni_bridge.c — Thin C JNI shim that ensures the Rust shared library is loaded.
 *
 * When CMake builds this file, it links against libjarvis_rust.so (imported).
 * The Android linker automatically loads libjarvis_rust.so when
 * libjarvis_jni_bridge.so is loaded via System.loadLibrary().
 *
 * All actual JNI function implementations live in Rust (lib.rs).
 * This shim exists solely to:
 *   1. Provide a guaranteed load order (bridge → Rust core)
 *   2. Allow building a stub when Rust .so is not available
 */
#include <android/log.h>

#define LOG_TAG "JarvisJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* Called when the JVM loads libjarvis_jni_bridge.so */
__attribute__((constructor))
static void on_library_load(void) {
    LOGI("jarvis_jni_bridge loaded — Rust core linked and loaded automatically");
}

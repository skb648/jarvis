# ─── CMakeLists.txt — Rust JNI Shared Library Linker ──────────
# This file tells the Android Gradle Plugin how to find and package
# the pre-built Rust shared libraries (libjarvis_rust.so) into the APK.
#
# Strategy:
#   - If Rust .so exists in jniLibs/<abi>/, we build jarvis_jni_bridge
#     that links against it. Android linker loads both .so files when
#     System.loadLibrary("jarvis_jni_bridge") is called.
#   - If Rust .so is NOT found (dev mode), we compile a stub that
#     implements ALL JNI method stubs with safe defaults.
#     The stub has the SAME library name so Kotlin code works
#     in both cases.
#
# NOTE: RustBridge.kt tries System.loadLibrary("jarvis_rust") FIRST,
# then falls back to System.loadLibrary("jarvis_jni_bridge").
# The Rust .so contains all JNI implementations directly, so when
# built, loading jarvis_rust alone is sufficient.
# ────────────────────────────────────────────────────────────────

cmake_minimum_required(VERSION 3.22.1)
project("jarvis_jni_bridge")

# ─── Locate pre-built Rust .so per ABI ────────────────────────
# Android Gradle sets ANDROID_ABI during native build.
# The .so files are placed by rust/build.sh into jniLibs/<abi>/
set(RUST_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/${ANDROID_ABI}")

if(EXISTS "${RUST_LIB_DIR}/libjarvis_rust.so")
    # ── Rust .so found: build bridge that links against it ──────
    # Import the pre-built Rust .so
    add_library(jarvis_rust SHARED IMPORTED)
    set_target_properties(jarvis_rust PROPERTIES
        IMPORTED_LOCATION "${RUST_LIB_DIR}/libjarvis_rust.so"
    )

    # Build the C bridge shim — Android linker will auto-load
    # libjarvis_rust.so when libjarvis_jni_bridge.so is loaded
    # (because of the DT_NEEDED entry from target_link_libraries).
    add_library(jarvis_jni_bridge SHARED
        jni_bridge.c
    )
    target_link_libraries(jarvis_jni_bridge
        jarvis_rust
        log
    )

    message(STATUS "✅ Rust JNI library found: ${RUST_LIB_DIR}/libjarvis_rust.so")
else()
    # ── Rust .so NOT found: build stub library with JNI stubs ───
    # The stub has the same library name (jarvis_jni_bridge) so
    # System.loadLibrary("jarvis_jni_bridge") works in both cases.
    # The stub implements ALL JNI methods with safe default returns,
    # preventing UnsatisfiedLinkError crashes.
    message(STATUS "⚠️ Rust JNI library NOT found at ${RUST_LIB_DIR}/libjarvis_rust.so")
    message(STATUS "Building stub library with JNI stubs. Run 'rust/build.sh' first to compile Rust.")

    add_library(jarvis_jni_bridge SHARED
        jni_bridge_stub.c
    )
    target_link_libraries(jarvis_jni_bridge log)
endif()

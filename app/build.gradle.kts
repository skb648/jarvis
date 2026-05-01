import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 800
        versionName = "8.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // CMake build config — only set if NDK is available
        val hasNdk = (System.getenv("ANDROID_NDK_HOME") ?: System.getenv("NDK_HOME"))?.let { File(it).exists() } == true
        if (hasNdk) {
            externalNativeBuild {
                cmake {
                    cppFlags += ""
                    arguments += listOf(
                        "-DANDROID_STL=none"
                    )
                }
            }
        }
    }

    signingConfigs {
        create("release") {
            // Production signing — CI/CD will inject actual keystore values
            // Use environment variables or gradle.properties for real builds
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
                ?: project.findProperty("RELEASE_KEYSTORE_PATH")?.toString()
                ?: ""
            val keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                ?: project.findProperty("RELEASE_KEYSTORE_PASSWORD")?.toString()
                ?: ""
            val keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                ?: project.findProperty("RELEASE_KEY_ALIAS")?.toString()
                ?: ""
            val keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                ?: project.findProperty("RELEASE_KEY_PASSWORD")?.toString()
                ?: ""

            if (keystorePath.isNotEmpty() && File(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                logger.lifecycle("Release signing configured with keystore: $keystorePath")
            } else {
                logger.lifecycle("No release keystore found — falling back to debug signing")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            packaging {
                jniLibs {
                    keepDebugSymbols += "**/*.so"
                }
            }
        }
        release {
            // NOTE: isMinifyEnabled and isShrinkResources are set to false
            // to prevent the APK from being stripped too aggressively.
            // Previous builds with these enabled resulted in APKs that
            // wouldn't install (missing components, corrupt DEX).
            // Once the build is stable, re-enable these and test carefully.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (signingConfigs.findByName("release")?.storeFile?.exists() == true) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // CMake external native build — compiles the JNI stub bridge
    // Only configure if NDK is available; skip gracefully otherwise
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("NDK_HOME")
    if (ndkHome != null && File(ndkHome).exists()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else {
        logger.lifecycle("⚠️ NDK not found — skipping CMake external native build. JNI stub will not be compiled.")
    }

    packaging {
        jniLibs {
            // Use legacy packaging (uncompressed .so) for maximum compatibility.
            // This increases APK size but ensures reliable native library loading
            // and avoids issues with compressed .so on some Android versions.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Rust NDK Build Integration — MANUAL ONLY
//
// The Rust build tasks are available as MANUAL tasks only.
// They are NOT automatically triggered during ./gradlew assembleDebug/Release.
//
// This is because cargo-ndk is NOT available in CI environments (GitHub Actions),
// and the auto-dependency caused build failures.
//
// To build Rust .so files locally, run manually:
//   ./gradlew buildRustDebug
//   ./gradlew buildRustRelease
//
// When Rust .so is not available, CMake builds the stub library
// (jni_bridge_stub.c) which provides safe no-op JNI implementations.
// ═══════════════════════════════════════════════════════════════════════════════

val rustDir = file("${project.projectDir}/../rust")
val jniLibsDir = file("${project.projectDir}/src/main/jniLibs")

// Only build for arm64-v8a and armeabi-v7a (the two most common Android ABIs)
// x86 and x86_64 are excluded to reduce build time and APK size
val abiTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi"
)

fun createRustBuildTask(abi: String, target: String, buildType: String): TaskProvider<Exec> {
    val taskName = "buildRust_${abi.replace('-', '_')}_$buildType"
    return tasks.register<Exec>(taskName) {
        group = "rust"
        description = "Build Rust .so for $abi ($buildType)"
        workingDir = rustDir

        val ndkHome = System.getenv("ANDROID_NDK_HOME")
            ?: System.getenv("NDK_HOME")
            ?: try {
                project.android.ndkDirectory.absolutePath
            } catch (e: Exception) {
                ""

            }

        environment("NDK_HOME", ndkHome)
        environment("ANDROID_NDK_HOME", ndkHome)

        val cargoArgs = mutableListOf(
            "ndk",
            "-t", target,
            "-o", jniLibsDir.absolutePath,
            "--manifest-path", "${rustDir}/Cargo.toml",
            "build"
        )

        if (buildType == "release") {
            cargoArgs.add("--release")
        }

        commandLine("cargo", *cargoArgs.toTypedArray())

        // Skip Gradle Rust build if the .so was already pre-built by CI.
        // The CI workflow (build.yml / release.yml) runs cargo-ndk directly
        // and places the .so files into jniLibs/ BEFORE Gradle runs.
        // Running cargo again here would be redundant and might overwrite
        // the pre-built binaries with a different configuration.
        val prebuiltSo = file("${jniLibsDir}/${abi}/libjarvis_rust.so")
        if (prebuiltSo.exists()) {
            logger.lifecycle("✅ Pre-built libjarvis_rust.so found for $abi — skipping Gradle Rust build")
        }

        isIgnoreExitValue = true

        inputs.dir("${rustDir}/src")
        inputs.file("${rustDir}/Cargo.toml")
        outputs.file("${jniLibsDir}/${abi}/libjarvis_rust.so")

        // FIX: Check exit value after execution — fail the build if cargo-ndk crashes
        doLast {
            val result = executionResult.get()
            if (result.exitValue != 0) {
                logger.error("❌ Rust build FAILED for $abi (exit code ${result.exitValue})")
                // Only throw if the .so was NOT produced (build genuinely failed vs. just skipped)
                val producedSo = file("${jniLibsDir}/${abi}/libjarvis_rust.so")
                if (!producedSo.exists()) {
                    throw GradleException("Rust build for $abi failed with exit code ${result.exitValue}. " +
                        "Check logs above. If cargo-ndk is not installed, run: cargo install cargo-ndk")
                }
            }
        }

        // Only run if: (1) .so not already pre-built by CI, AND (2) cargo-ndk is available
        onlyIf {
            // If the .so already exists (pre-built by CI), skip this task
            if (prebuiltSo.exists()) {
                logger.lifecycle("Skipping Rust build for $abi — pre-built .so found at ${prebuiltSo.absolutePath}")
                return@onlyIf false
            }
            try {
                val proc = ProcessBuilder("cargo", "ndk", "--version")
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor()
                proc.exitValue() == 0
            } catch (e: Exception) {
                logger.lifecycle("⚠️ cargo-ndk not found — skipping Rust build for $abi. CMake stub will be used.")
                false
            }
        }
    }
}

val debugTasks = abiTargets.map { (abi, target) ->
    createRustBuildTask(abi, target, "debug")
}

val releaseTasks = abiTargets.map { (abi, target) ->
    createRustBuildTask(abi, target, "release")
}

tasks.register("buildRustDebug") {
    group = "rust"
    dependsOn(debugTasks)
}

tasks.register("buildRustRelease") {
    group = "rust"
    dependsOn(releaseTasks)
}

// ═══════════════════════════════════════════════════════════════════════
// CRITICAL FIX: Auto-wire Rust build as a pre-build dependency
//
// This ensures that ./gradlew assembleRelease and ./gradlew assembleDebug
// ALWAYS attempt to build the Rust .so files before CMake runs.
//
// IMPORTANT: Debug builds use debug Rust .so (~83MB+63MB with symbols),
// Release builds use release Rust .so (~2.6MB+1.8MB stripped).
// We must NOT let release Rust overwrite debug .so — that was causing
// the debug APK to be only 23MB instead of 66MB.
// ═══════════════════════════════════════════════════════════════════════

// NOTE: Rust build tasks are MANUAL ONLY. Do NOT auto-wire them as
// pre-build dependencies. CI environments (GitHub Actions) do not have
// cargo-ndk, and auto-wiring causes the build to fail.
// The CMake stub (jni_bridge_stub.c) provides fallback JNI implementations.

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.runtime.livedata)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.collection.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.paho.mqtt)
    implementation(libs.paho.mqtt.service)

    implementation(libs.gson)

    // Room Database — Persistent Brain Memory
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // CameraX — Vision Mode (Camera + AI)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Google Play Services Location — Location Awareness
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // OkHttp — Modern HTTP client (replaces HttpURLConnection for better reliability)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 500
        versionName = "5.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        // CMake build for JNI stub (when Rust .so is not available)
        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf(
                    "-DANDROID_STL=c++_shared"
                )
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
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
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

// ─── Rust build tasks (optional — only runs if cargo-ndk is available) ────
// These tasks are registered but NOT automatically triggered.
// The CMake JNI stub provides fallback when Rust .so is absent.
// To build Rust manually: ./gradlew buildRustDebug

val rustDir = file("${project.projectDir}/../rust")
val jniLibsDir = file("${project.projectDir}/src/main/jniLibs")

val abiTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android"
)

fun createRustBuildTask(abi: String, target: String, buildType: String): TaskProvider<Exec> {
    val taskName = "buildRust_${abi.replace('-', '_')}_$buildType"
    return tasks.register<Exec>(taskName) {
        group = "rust"
        description = "Build Rust .so for $abi ($buildType)"
        workingDir = rustDir

        val ndkHome = System.getenv("ANDROID_NDK_HOME")
            ?: System.getenv("NDK_HOME")
            ?: project.android.ndkDirectory.absolutePath

        environment("NDK_HOME", ndkHome)

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

        inputs.dir("${rustDir}/src")
        inputs.file("${rustDir}/Cargo.toml")
        outputs.file("${jniLibsDir}/${abi}/libjarvis_rust.so")
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

// NOTE: We do NOT automatically depend on Rust build tasks anymore.
// The CMake JNI stub bridge handles fallback when Rust .so is missing.
// Rust build is now OPT-IN via explicit: ./gradlew buildRustDebug

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
    implementation(libs.media3.exoplayer)
}

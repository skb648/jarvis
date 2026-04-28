# JARVIS APP — FULL X-RAY POSTMORTEM

## ═══════════════════════════════════════════════════════════════
## EXECUTIVE SUMMARY
## ═══════════════════════════════════════════════════════════════

The JARVIS app has a well-structured architecture but suffers from several
critical bugs that make it non-functional in production. The 5 surgeries
requested are partially addressed in the current code but still have gaps.

## ═══════════════════════════════════════════════════════════════
## BUG #1: API KEY HOT-SWAP — PARTIALLY FIXED
## ═══════════════════════════════════════════════════════════════

**Status**: MOSTLY WORKING, but with edge cases

**What works**:
- Rust `gemini.rs` uses `lazy_static! { RwLock<ApiKeys> }` ✓
- `set_api_keys()` overwrites via RwLock write guard ✓
- `nativeInitialize` calls `set_api_keys()` ✓
- Kotlin `saveAndApplyApiKeys()` calls `RustBridge.initialize()` ✓
- Error parsing handles 429, 403, etc. ✓

**Remaining bugs**:
1. `parseErrorResponse` uses `lower.contains("429")` which can false-positive
   on any text containing "429" (e.g., "flight 429 is delayed")
2. When Rust returns `ERROR: Gemini API error 429 ...`, the raw JSON body
   bleeds through before the parser catches it
3. The `isError` check `parsed !== rawResponse` uses reference equality on
   Strings, which in Kotlin is NOT reliable — should use `!=` (structural)

## ═══════════════════════════════════════════════════════════════
## BUG #2: AUDIO RECORDING — PARTIALLY FIXED, TTS WORKS
## ═══════════════════════════════════════════════════════════════

**Status**: AudioEngine WORKS, but transcription pipeline is BROKEN

**What works**:
- AudioEngine has real AudioRecord background thread ✓
- VAD (Voice Activity Detection) is implemented ✓
- Wake word detection feeds PCM to Rust ✓
- TTS: ElevenLabs → Base64 → ExoPlayer playback ✓

**Critical bugs**:
1. **TRANSCRIPTION IS FAKE**: `handleCommandReady()` does NOT actually transcribe
   speech to text. It sends audio analysis JSON to Gemini and asks "what did
   they likely say?" — this is a hallucination pipeline, not transcription!
   
   The code literally says:
   ```
   "Based on the conversation context, what did they likely say?"
   ```
   This means Gemini GUESSES what was said based on audio metadata (pitch,
   volume, emotion) — it cannot possibly know the words spoken.

2. **SpeechRecognizer is declared but NEVER used**:
   ```kotlin
   private var speechRecognizer: android.speech.SpeechRecognizer? = null
   ```
   It's initialized to null, never created, never used. It's destroyed in
   onCleared() but was never alive.

3. **The PCM audio from VAD goes nowhere useful**: The 44100Hz PCM bytes are
   sent to `RustBridge.analyzeAudio()` which returns pitch/volume/emotion —
   NOT transcription. Then this metadata is sent as a prompt to Gemini.

4. **AudioEngine sample rate mismatch**: AudioEngine records at 44100Hz, but
   speech recognition typically works best at 16000Hz. The `analyzeAudio`
   call passes 44100 which is correct for the Rust side, but if we were to
   use Android SpeechRecognizer, we'd need a different approach.

## ═══════════════════════════════════════════════════════════════
## BUG #3: SHIZUKU PERMISSION FLOW — PARTIALLY FIXED
## ═══════════════════════════════════════════════════════════════

**Status**: MOSTLY WORKING, but missing Activity-level permission request

**What works**:
- `ShizukuManager.hasPermission()` checks `checkSelfPermission()` ✓
- `ShizukuManager.requestPermission()` calls `Shizuku.requestPermission(0)` ✓
- Permission result listener is registered ✓
- `executeShellCommand()` checks both `isReady()` and `hasPermission()` ✓

**Remaining bugs**:
1. `Shizuku.requestPermission()` requires an Activity context to show the
   permission dialog. The current code calls it from ShizukuManager (object)
   which has no Activity reference. This means the permission dialog may
   never appear on screen.
   
2. The `requestPermission()` method in ShizukuManager doesn't pass an
   Activity — it just calls `Shizuku.requestPermission(requestCode)`. On
   Shizuku 13+, this requires a hosting Activity.

## ═══════════════════════════════════════════════════════════════
## BUG #4: ACTION HANDLER — MOSTLY WORKING
## ═══════════════════════════════════════════════════════════════

**Status**: WELL IMPLEMENTED

**What works**:
- JSON action block parsing ✓
- Natural language pattern matching ✓
- Real Intent-based app launching ✓
- PackageManager search for unknown apps ✓
- Shizuku fallback for app opening ✓
- Toggle, volume, brightness, screenshot, navigation ✓
- Call dialer opening ✓
- URL opening ✓

**Minor bugs**:
1. `executeOpenApp` with Shizuku uses `ShizukuManager.openApp()` which uses
   `monkey -p ... 1` — this works but is unreliable on some devices.
   Should also try `am start` with the package's main activity.

2. The `searchPackageByName` method searches `getInstalledApplications` but
   doesn't filter out system apps that can't be launched.

## ═══════════════════════════════════════════════════════════════
## BUG #5: WAKE WORD INTEGRATION — WORKS BUT CRUDE
## ═══════════════════════════════════════════════════════════════

**Status**: FUNCTIONAL but high false-positive rate

**What works**:
- PCM buffer is fed to `nativeDetectWakeWord` from AudioEngine ✓
- Energy-based pre-filter skips silence ✓
- Spectral analysis validates speech-like characteristics ✓
- When detected, triggers VAD recording mode ✓
- Background wake word monitor exists ✓

**Problems**:
1. The wake word detection is energy + spectral + ZCR based — it detects
   ANY speech, not specifically the word "Jarvis". Every time someone
   speaks near the phone, it triggers. This is a known limitation noted
   in the code comments, but it makes the feature nearly unusable in
   practice.

2. `isRecordingCommand` flag is NOT set when wake word triggers from
   background monitor. When wake word is detected, `onWakeWordDetected`
   calls `audioEngine?.startCommandRecording()`, but this is on the
   engine that detected the wake word, not the one that will record
   the command. The background wake word engine is stopped and a new
   AudioEngine is created for listening — but the new engine's VAD
   recording isn't always properly synchronized.

## ═══════════════════════════════════════════════════════════════
## ADDITIONAL BUGS FOUND
## ═══════════════════════════════════════════════════════════════

### BUG #6: Memory Leak — ExoPlayer
`playMp3Audio` creates a new ExoPlayer instance ONLY if null, but never
resets the media item between plays. If TTS plays twice in rapid succession,
the first media item may still be playing when the second is set.

### BUG #7: Temp file leak
`playMp3Audio` creates temp MP3 files in cache but only deletes on
`STATE_ENDED`. If playback fails or ExoPlayer is released, the file
leaks. Should use `deleteOnExit()` or clean up in more places.

### BUG #8: buildHistoryJson uses Gson but creates invalid JSON
```kotlin
val escaped = com.google.gson.Gson().toJson(m.content)
"""{"role":"$role","content":$escaped}"""
```
This manually constructs JSON with string interpolation. While `Gson.toJson()`
escapes the content, the surrounding structure is not validated. Could break
if content contains special characters. Should use proper JSON serialization.

### BUG #9: ViewModel Factory is referenced but not shown
The code uses `JarvisViewModel.Factory(settingsRepository)` but the Factory
class is not visible in the code. If it's missing, the app will crash at
runtime with `NoSuchMethodError`.

### BUG #10: CMake JNI stub may conflict with Rust .so
The `CMakeLists.txt` builds `jarvis_jni_bridge` which provides stub
implementations. But if both `libjarvis_rust.so` and `libjarvis_jni_bridge.so`
define the same JNI functions, the linker will fail with duplicate symbol
errors. The load order in RustBridge tries Rust first, then stub — but on
some devices, both libraries may be loaded.

### BUG #11: Shizuku AIDL imports may not resolve
```kotlin
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.ShizukuRemoteProcess
```
These are internal Shizuku classes that may not be part of the public API.
The `shizuku-api` and `shizuku-provider` dependencies may not expose these
classes, causing compilation errors.

### BUG #12: MQTT Paho service declared but MQTT is never actually connected
The MqttManager and HomeAssistantBridge exist but are never initialized
or connected. The SmartHomeScreen shows devices but they're always empty.

### BUG #13: ProGuard may strip JNI methods
The release build uses `isMinifyEnabled = true` and `isShrinkResources = true`.
ProGuard/R8 may remove or rename the JNI bridge methods since they appear
unused from Java/Kotlin's perspective. Need proper ProGuard keep rules.

### BUG #14: GitHub Actions build.yml uses NDK 27 but build.gradle.kts
specifies CMake 3.22.1 which may not be compatible with NDK 27.

### BUG #15: The `toggleVoiceMode()` legacy no-context version
This version just flips the flag without starting audio — any code path
that calls this will result in a dead mic button.

## ═══════════════════════════════════════════════════════════════
## FIX PRIORITY ORDER
## ═══════════════════════════════════════════════════════════════

1. Fix transcription pipeline (BUG #2) — CRITICAL, app is deaf
2. Fix Shizuku permission with Activity context (BUG #3) — system commands broken
3. Fix ProGuard rules (BUG #13) — release APK will crash
4. Fix Shizuku AIDL compilation (BUG #11) — won't compile
5. Fix error parser reference equality (BUG #1 #3)
6. Fix ExoPlayer cleanup (BUG #6, #7)
7. Fix ViewModel Factory (BUG #9)
8. Fix GitHub Actions build (BUG #14)
9. Fix wake word false positives (BUG #5) — UX improvement
10. Fix buildHistoryJson (BUG #8)

mod audio;
mod emotion;
mod gemini;
mod jni_helpers;
mod wake_word;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jdouble, jfloat, jint, jstring};
use jni::JNIEnv;
use jni::JavaVM;
use log::LevelFilter;
use once_cell::sync::OnceCell;

static RUNTIME: OnceCell<tokio::runtime::Runtime> = OnceCell::new();

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .build()
            .expect("Failed to create Tokio runtime")
    })
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _: *mut std::ffi::c_void) -> jint {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(LevelFilter::Info)
            .with_tag("JarvisRust"),
    );
    log::info!("JARVIS Rust Core loaded — API keys are hot-swappable via RwLock");
    let _ = runtime();
    jni::sys::JNI_VERSION_1_6
}

/// JNI: nativeInitialize(geminiKey, elevenLabsKey)
///
/// This is the HOT-SWAP entry point. Every time the user taps "SAVE & APPLY"
/// in Settings, Kotlin calls this function with the new keys.
///
/// The Rust backend stores keys in `lazy_static! { RwLock<ApiKeys> }`
/// (see gemini.rs), so calling this multiple times simply overwrites
/// the previous values — no OnceCell rejection, no app restart needed.
#[no_mangle]
pub extern "system" fn Java_com_jarvis_assistant_jni_RustBridge_nativeInitialize<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    gemini_key: JString<'local>,
    elevenlabs_key: JString<'local>,
) -> jboolean {
    log::info!("nativeInitialize — hot-swapping API keys...");
    let gemini_api_key = jni_helpers::jstring_to_string(&mut env, &gemini_key);
    let elevenlabs_api_key = jni_helpers::jstring_to_string(&mut env, &elevenlabs_key);

    if let Err(e) = gemini::set_api_keys(&gemini_api_key, &elevenlabs_api_key) {
        log::error!("Failed to set API keys: {}", e);
        return 0; // false
    }
    log::info!(
        "API keys hot-swapped successfully — gemini={} chars, elevenlabs={} chars",
        gemini_api_key.len(),
        elevenlabs_api_key.len()
    );
    1 // true
}

#[no_mangle]
pub extern "system" fn Java_com_jarvis_assistant_jni_RustBridge_nativeProcessQuery<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    query: JString<'local>,
    context: JString<'local>,
    history_json: JString<'local>,
) -> jstring {
    let query_str = jni_helpers::jstring_to_string(&mut env, &query);
    let context_str = jni_helpers::jstring_to_string(&mut env, &context);
    let history_str = jni_helpers::jstring_to_string(&mut env, &history_json);

    let rt = runtime();
    let result =
        rt.block_on(async { gemini::process_query(&query_str, &context_str, &history_str).await });

    match result {
        Ok(response) => jni_helpers::string_to_jstring(&mut env, &response).into_raw(),
        Err(e) => {
            log::error!("Gemini query failed: {}", e);
            jni_helpers::string_to_jstring(&mut env, &format!("ERROR: {}", e)).into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_jarvis_assistant_jni_RustBridge_nativeProcessQueryWithImage<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    query: JString<'local>,
    image_base64: JString<'local>,
    mime_type: JString<'local>,
) -> jstring {
    let query_str = jni_helpers::jstring_to_string(&mut env, &query);
    let image_b64 = jni_helpers::jstring_to_string(&mut env, &image_base64);
    let mime = jni_helpers::jstring_to_string(&mut env, &mime_type);

    let rt = runtime();
    let result = rt
        .block_on(async { gemini::process_query_with_image(&query_str, &image_b64, &mime).await });

    match result {
        Ok(response) => jni_helpers::string_to_jstring(&mut env, &response).into_raw(),
        Err(e) => {
            log::error!("Multimodal query failed: {}", e);
            jni_helpers::string_to_jstring(&mut env, &format!("ERROR: {}", e)).into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_jarvis_assistant_jni_RustBridge_nativeAnalyzeAudio<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    audio_data: JByteArray<'local>,
    sample_rate: jint,
) -> jstring {
    let audio_bytes = jni_helpers::jbytearray_to_vec(&mut env, &audio_data);
    let result = audio::analyze_audio_chunk(&audio_bytes, sample_rate as u32);

    match result {
        Ok(analysis) => {
            let json = serde_json::json!({
                "pitch_hz": analysis.pitch_hz,
                "volume_db": analysis.volume_db,
                "energy": analysis.energy,
                "emotion": analysis.emotion,
                "confidence": analysis.confidence,
            });
            jni_helpers::string_to_jstring(&mut env, &json.to_string()).into_raw()
        }
        Err(e) => {
            log::error!("Audio analysis failed: {}", e);
            jni_helpers::string_to_jstring(&mut env, &format!("{{\"error\": \"{}\"}}", e))
                .into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_jarvis_assistant_jni_RustBridge_nativeDetectWakeWord<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    audio_data: JByteArray<'local>,
    sample_rate: jint,
) -> jboolean {
    let audio_bytes = jni_helpers::jbytearray_to_vec(&mut env, &audio_data);
    let detected = wake_word::detect(&audio_bytes, sample_rate as u32);
    if detected {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_jarvis_assistant_jni_RustBridge_nativeAnalyzeEmotion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jstring {
    let text_str = jni_helpers::jstring_to_string(&mut env, &text);
    let result = emotion::analyze_text_emotion(&text_str);
    let json = serde_json::json!({
        "emotion": result.emotion,
        "confidence": result.confidence,
        "valence": result.valence,
        "arousal": result.arousal,
    });
    jni_helpers::string_to_jstring(&mut env, &json.to_string()).into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_jarvis_assistant_jni_RustBridge_nativeSynthesizeSpeech<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
    voice_id: JString<'local>,
    stability: jfloat,
    similarity_boost: jfloat,
) -> jstring {
    let text_str = jni_helpers::jstring_to_string(&mut env, &text);
    let voice = jni_helpers::jstring_to_string(&mut env, &voice_id);

    let rt = runtime();
    let result = rt.block_on(async {
        gemini::synthesize_speech(&text_str, &voice, stability, similarity_boost).await
    });

    match result {
        Ok(audio_base64) => jni_helpers::string_to_jstring(&mut env, &audio_base64).into_raw(),
        Err(e) => {
            log::error!("TTS synthesis failed: {}", e);
            jni_helpers::string_to_jstring(&mut env, "").into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_jarvis_assistant_jni_RustBridge_nativeGetAudioAmplitude<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    audio_data: JByteArray<'local>,
) -> jdouble {
    let audio_bytes = jni_helpers::jbytearray_to_vec(&mut env, &audio_data);
    audio::calculate_rms(&audio_bytes) as jdouble
}

#[no_mangle]
pub extern "system" fn Java_com_jarvis_assistant_jni_RustBridge_nativeHealthCheck<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    1
}

#[no_mangle]
pub extern "system" fn Java_com_jarvis_assistant_jni_RustBridge_nativeShutdown<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    log::info!("JARVIS Rust core shutting down...");
}

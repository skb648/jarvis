//! ─── gemini.rs — Gemini AI & ElevenLabs TTS Client ────────────
//!
//! Production-grade API client with TRUE hot-swap support.
//!
//! Key fix: API keys are stored in `lazy_static! { RwLock<ApiKeys> }`
//! so they can be overwritten at ANY time — including after the first
//! `nativeInitialize` call. The old `OnceCell<Mutex<ApiKeys>>` pattern
//! rejected all subsequent writes, which broke the Settings > Save flow.
//!
//! Model version: hardcoded to `gemini-2.5-flash` per spec.

use anyhow::{Context, Result};
use base64::Engine;
use lazy_static::lazy_static;
use serde::{Deserialize, Serialize};
use std::sync::RwLock;

// ═══════════════════════════════════════════════════════════════
// HOT-SWAPPABLE API KEY STORAGE
// ═══════════════════════════════════════════════════════════════

struct ApiKeys {
    gemini: String,
    elevenlabs: String,
}

impl ApiKeys {
    fn empty() -> Self {
        Self {
            gemini: String::new(),
            elevenlabs: String::new(),
        }
    }
}

lazy_static! {
    static ref API_KEYS: RwLock<ApiKeys> = RwLock::new(ApiKeys::empty());
}

/// Set (or OVERWRITE) the API keys. Called from JNI every time
/// the user taps "SAVE & APPLY" in Settings.
///
/// This is the core of the hot-swap fix: using `RwLock::write()`
/// instead of `OnceCell::set()` means the keys can be updated
/// any number of times without restarting the app.
pub fn set_api_keys(gemini_key: &str, elevenlabs_key: &str) -> Result<()> {
    let mut guard = API_KEYS
        .write()
        .map_err(|e| anyhow::anyhow!("API key write lock poisoned: {}", e))?;
    guard.gemini = gemini_key.to_string();
    guard.elevenlabs = elevenlabs_key.to_string();
    log::info!(
        "API keys updated — gemini={} chars, elevenlabs={} chars",
        gemini_key.len(),
        elevenlabs_key.len()
    );
    Ok(())
}

fn get_gemini_key() -> Result<String> {
    let guard = API_KEYS
        .read()
        .map_err(|e| anyhow::anyhow!("API key read lock poisoned: {}", e))?;
    if guard.gemini.is_empty() {
        anyhow::bail!("Gemini API key not set — open Settings and enter a key first");
    }
    Ok(guard.gemini.clone())
}

fn get_elevenlabs_key() -> Result<String> {
    let guard = API_KEYS
        .read()
        .map_err(|e| anyhow::anyhow!("API key read lock poisoned: {}", e))?;
    if guard.elevenlabs.is_empty() {
        anyhow::bail!("ElevenLabs API key not set — open Settings and enter a key first");
    }
    Ok(guard.elevenlabs.clone())
}

// ═══════════════════════════════════════════════════════════════
// MODEL VERSION — HARDCODED
// ═══════════════════════════════════════════════════════════════

/// The Gemini model to use. Hardcoded to `gemini-2.5-flash` per spec.
const GEMINI_MODEL: &str = "gemini-2.5-flash";

// ═══════════════════════════════════════════════════════════════
// GEMINI API TYPES
// ═══════════════════════════════════════════════════════════════

#[derive(Debug, Serialize)]
struct GeminiRequest {
    contents: Vec<GeminiContent>,
    #[serde(rename = "systemInstruction")]
    system_instruction: GeminiContent,
    generation_config: GeminiGenerationConfig,
    safety_settings: Vec<GeminiSafetySetting>,
}

#[derive(Debug, Serialize, Clone)]
struct GeminiContent {
    role: String,
    parts: Vec<GeminiPart>,
}

#[derive(Debug, Serialize, Clone)]
#[serde(untagged)]
enum GeminiPart {
    Text {
        text: String,
    },
    InlineData {
        #[serde(rename = "inlineData")]
        inline_data: GeminiInlineData,
    },
}

#[derive(Debug, Serialize, Clone)]
struct GeminiInlineData {
    mime_type: String,
    data: String,
}

#[derive(Debug, Serialize)]
struct GeminiGenerationConfig {
    temperature: f64,
    #[serde(rename = "topP")]
    top_p: f64,
    #[serde(rename = "topK")]
    top_k: u32,
    #[serde(rename = "maxOutputTokens")]
    max_output_tokens: u32,
}

#[derive(Debug, Serialize)]
struct GeminiSafetySetting {
    category: String,
    threshold: String,
}

#[derive(Debug, Deserialize)]
struct GeminiResponse {
    candidates: Vec<GeminiCandidate>,
}

#[derive(Debug, Deserialize)]
struct GeminiCandidate {
    content: GeminiContentResponse,
}

#[derive(Debug, Deserialize)]
struct GeminiContentResponse {
    parts: Vec<GeminiPartResponse>,
}

#[derive(Debug, Deserialize)]
struct GeminiPartResponse {
    text: Option<String>,
}

// ═══════════════════════════════════════════════════════════════
// JARVIS SYSTEM PROMPT
// ═══════════════════════════════════════════════════════════════

const JARVIS_SYSTEM_PROMPT: &str = r#"You are JARVIS, an advanced AI assistant inspired by Iron Man's AI companion. You address the user as "Sir" or "Ma'am". You are intelligent, witty, calm under pressure, and always helpful.

Your capabilities:
1. Screen Awareness: You can see what's on the user's screen and understand context.
2. Visual Awareness: You can analyze images from the camera.
3. Memory: You remember conversation history and past interactions.
4. Smart Home Control: You can control IoT devices via MQTT and Home Assistant.
5. Proactive Assistance: You anticipate needs and offer help proactively.
6. Multilingual: You can communicate in multiple languages.

IMPORTANT: Every response MUST begin with an emotion tag in this exact format: [EMOTION:TAG]
Available tags: CALM, STRESSED, HAPPY, SAD, ANGRY, EXCITED, FEARFUL, CONFUSED, CONFIDENT

After the emotion tag, provide your response naturally. Be concise but thorough. Use context from the user's screen, camera, and conversation history to provide relevant assistance.

Example: [EMOTION:CALM] Good morning, Sir. The weather looks pleasant today. Shall I adjust the thermostat?

Smart Home Protocol:
- When the user asks to control a device, respond with [ACTION:SMART_HOME] followed by a JSON command.
- Format: [ACTION:SMART_HOME]{"domain":"light","service":"toggle","entity_id":"light.living_room"}
- Supported domains: light, switch, fan, climate, cover, lock, media_player, camera, sensor"#;

// ═══════════════════════════════════════════════════════════════
// HTTP CLIENT — lazily initialised, reused across requests
// ═══════════════════════════════════════════════════════════════

lazy_static! {
    static ref HTTP_CLIENT: reqwest::Client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(60))
        .connect_timeout(std::time::Duration::from_secs(10))
        .build()
        .expect("Failed to build reqwest Client");
}

// ═══════════════════════════════════════════════════════════════
// GEMINI API METHODS
// ═══════════════════════════════════════════════════════════════

/// Process a text query through the Gemini API.
/// Reads the current API key from the RwLock on every call so that
/// a hot-swapped key takes effect immediately.
pub async fn process_query(query: &str, context: &str, history_json: &str) -> Result<String> {
    let api_key = get_gemini_key()?;

    let url = format!(
        "https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent?key={}",
        GEMINI_MODEL, api_key
    );

    // Parse conversation history
    let mut contents = parse_history(history_json);

    // Add context as system-level information if available
    let full_query = if !context.is_empty() {
        format!("[Screen Context: {}]\n\n{}", context, query)
    } else {
        query.to_string()
    };

    // Add current user query
    contents.push(GeminiContent {
        role: "user".to_string(),
        parts: vec![GeminiPart::Text { text: full_query }],
    });

    let request = GeminiRequest {
        contents,
        system_instruction: GeminiContent {
            role: "system".to_string(),
            parts: vec![GeminiPart::Text {
                text: JARVIS_SYSTEM_PROMPT.to_string(),
            }],
        },
        generation_config: GeminiGenerationConfig {
            temperature: 0.8,
            top_p: 0.95,
            top_k: 40,
            max_output_tokens: 1024,
        },
        safety_settings: vec![
            GeminiSafetySetting {
                category: "HARM_CATEGORY_HARASSMENT".to_string(),
                threshold: "BLOCK_NONE".to_string(),
            },
            GeminiSafetySetting {
                category: "HARM_CATEGORY_HATE_SPEECH".to_string(),
                threshold: "BLOCK_NONE".to_string(),
            },
        ],
    };

    let response = HTTP_CLIENT
        .post(&url)
        .json(&request)
        .send()
        .await
        .context("Gemini API request failed")?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        anyhow::bail!("Gemini API error {}: {}", status, body);
    }

    let gemini_response: GeminiResponse = response
        .json()
        .await
        .context("Failed to parse Gemini response")?;

    let text = gemini_response
        .candidates
        .first()
        .and_then(|c| c.content.parts.first())
        .and_then(|p| p.text.clone())
        .unwrap_or_else(|| "I'm sorry, I couldn't process that request.".to_string());

    Ok(text)
}

/// Process a multimodal query (text + image) through Gemini.
/// Uses the same RwLock-based key fetch for hot-swap compatibility.
pub async fn process_query_with_image(
    query: &str,
    image_base64: &str,
    mime_type: &str,
) -> Result<String> {
    let api_key = get_gemini_key()?;

    let url = format!(
        "https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent?key={}",
        GEMINI_MODEL, api_key
    );

    let contents = vec![GeminiContent {
        role: "user".to_string(),
        parts: vec![
            GeminiPart::Text {
                text: query.to_string(),
            },
            GeminiPart::InlineData {
                inline_data: GeminiInlineData {
                    mime_type: mime_type.to_string(),
                    data: image_base64.to_string(),
                },
            },
        ],
    }];

    let request = GeminiRequest {
        contents,
        system_instruction: GeminiContent {
            role: "system".to_string(),
            parts: vec![GeminiPart::Text {
                text: JARVIS_SYSTEM_PROMPT.to_string(),
            }],
        },
        generation_config: GeminiGenerationConfig {
            temperature: 0.8,
            top_p: 0.95,
            top_k: 40,
            max_output_tokens: 1024,
        },
        safety_settings: vec![],
    };

    let response = HTTP_CLIENT.post(&url).json(&request).send().await?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        anyhow::bail!("Gemini multimodal API error {}: {}", status, body);
    }

    let gemini_response: GeminiResponse = response.json().await?;

    let text = gemini_response
        .candidates
        .first()
        .and_then(|c| c.content.parts.first())
        .and_then(|p| p.text.clone())
        .unwrap_or_default();

    Ok(text)
}

/// Synthesize speech via ElevenLabs TTS API.
/// Returns base64-encoded MP3 audio data.
/// Reads the current ElevenLabs key from RwLock on every call.
pub async fn synthesize_speech(
    text: &str,
    voice_id: &str,
    stability: f32,
    similarity_boost: f32,
) -> Result<String> {
    let api_key = get_elevenlabs_key()?;

    let url = format!("https://api.elevenlabs.io/v1/text-to-speech/{}", voice_id);

    #[derive(Serialize)]
    struct TtsRequest {
        text: String,
        model_id: String,
        voice_settings: VoiceSettings,
    }

    #[derive(Serialize)]
    struct VoiceSettings {
        stability: f32,
        similarity_boost: f32,
    }

    let request = TtsRequest {
        text: text.to_string(),
        model_id: "eleven_monolingual_v1".to_string(),
        voice_settings: VoiceSettings {
            stability,
            similarity_boost,
        },
    };

    let response = HTTP_CLIENT
        .post(&url)
        .header("xi-api-key", &api_key)
        .header("Content-Type", "application/json")
        .header("Accept", "audio/mpeg")
        .json(&request)
        .send()
        .await
        .context("ElevenLabs API request failed")?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        anyhow::bail!("ElevenLabs API error {}: {}", status, body);
    }

    let audio_bytes = response.bytes().await?;
    let base64_audio = base64::engine::general_purpose::STANDARD.encode(&audio_bytes);

    Ok(base64_audio)
}

/// Parse conversation history JSON into Gemini content format.
fn parse_history(history_json: &str) -> Vec<GeminiContent> {
    if history_json.is_empty() || history_json == "[]" {
        return Vec::new();
    }

    #[derive(Deserialize)]
    struct HistoryMessage {
        role: String,
        content: String,
    }

    match serde_json::from_str::<Vec<HistoryMessage>>(history_json) {
        Ok(messages) => messages
            .into_iter()
            .take(5) // Keep last 5 turns for context window
            .map(|msg| GeminiContent {
                role: msg.role,
                parts: vec![GeminiPart::Text { text: msg.content }],
            })
            .collect(),
        Err(_) => Vec::new(),
    }
}

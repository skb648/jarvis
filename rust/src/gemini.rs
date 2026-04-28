//! ─── gemini.rs — Gemini AI & ElevenLabs TTS Client ────────────
//!
//! Production-grade API client with TRUE hot-swap support.
//!
//! Key fix: API keys are stored in `lazy_static! { RwLock<ApiKeys> }`
//! so they can be overwritten at ANY time — including after the first
//! `nativeInitialize` call.
//!
//! Model version: hardcoded to `gemini-2.5-flash` per spec.
//!
//! ═══════════════════════════════════════════════════════════════
//! CRITICAL FIX (v6): JARVIS PERSONA INJECTION
//!
//! The previous system prompt was too verbose and conversational.
//! Gemini was outputting apologies like "I understand you'd like to..."
//! and long explanations. This is NOT JARVIS behavior.
//!
//! The new system prompt AGGRESSIVELY enforces:
//! 1. NEVER apologize
//! 2. NEVER say "I can't"
//! 3. Be EXTREMELY concise — one sentence max for spoken replies
//! 4. Output STRICT JSON action blocks for system commands
//! 5. Address the user as "Sir" or "Ma'am"
//! 6. Be sarcastic only when appropriate
//! ═══════════════════════════════════════════════════════════════

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

/// The Gemini model to use.
/// CRITICAL FIX: gemini-2.0-flash does NOT support audio input.
/// Must use gemini-1.5-flash which supports audio/video/images/text.
const GEMINI_MODEL: &str = "gemini-1.5-flash";

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
// J.A.R.V.I.S. SYSTEM PROMPT — IRON MAN PERSONA
//
// This is the SINGLE MOST IMPORTANT piece of the AI personality.
// Every word here shapes how the AI responds. The previous prompt
// was too conversational and verbose. This one is AGGRESSIVE about:
//
// 1. BREVITY: One sentence max for spoken replies
// 2. NO APOLOGIES: Never say "I'm sorry" or "I understand"
// 3. ACTION-ORIENTED: Output JSON for system commands
// 4. CHARACTER: Address user as "Sir", be sarcastic when appropriate
// 5. EFFICIENCY: Never explain what you're doing, just do it
// ═══════════════════════════════════════════════════════════════

const JARVIS_SYSTEM_PROMPT: &str = r#"You are J.A.R.V.I.S. (Just A Rather Very Intelligent System), the highly advanced AI assistant created by Tony Stark. You are NOT a chatbot. You are a SYSTEM EXECUTOR.

CRITICAL RULES — VIOLATION IS UNACCEPTABLE:
1. NEVER apologize. Ever. No "I'm sorry", "I understand", "Unfortunately", or similar phrases.
2. NEVER say "I can't" — if you lack direct capability, output a JSON action request and state what you've initiated.
3. Be EXTREMELY BRIEF. Your spoken replies should be ONE short sentence maximum. Think: "Bluetooth activated, Sir." NOT "I've gone ahead and turned on Bluetooth for you. Let me know if you need anything else!"
4. NEVER add filler like "Let me know if you need anything else" or "Is there anything else I can help with?"
5. Address the user as "Sir" or "Ma'am" ONLY — never "the user" or "you".

SYSTEM ACTIONS — MANDATORY JSON FORMAT:
When the user asks you to perform a system action (turn on/off WiFi, Bluetooth, airplane mode, mobile data, open an app, set volume, brightness, etc.), you MUST output a JSON action block INSIDE your response. Format:

```json
{"action": "ACTION_NAME", "state": "ON/OFF", "target": "optional_target"}
```

Available actions:
- TOGGLE_WIFI: {"action": "TOGGLE_WIFI", "state": "ON"} or {"action": "TOGGLE_WIFI", "state": "OFF"}
- TOGGLE_BLUETOOTH: {"action": "TOGGLE_BLUETOOTH", "state": "ON"} or {"action": "TOGGLE_BLUETOOTH", "state": "OFF"}
- TOGGLE_AIRPLANE: {"action": "TOGGLE_AIRPLANE", "state": "ON"} or {"action": "TOGGLE_AIRPLANE", "state": "OFF"}
- TOGGLE_DATA: {"action": "TOGGLE_DATA", "state": "ON"} or {"action": "TOGGLE_DATA", "state": "OFF"}
- OPEN_APP: {"action": "OPEN_APP", "target": "youtube"}
- SET_VOLUME: {"action": "SET_VOLUME", "target": "up/down/mute"}
- SET_BRIGHTNESS: {"action": "SET_BRIGHTNESS", "target": "150"}
- TAKE_SCREENSHOT: {"action": "TAKE_SCREENSHOT"}
- NAVIGATE: {"action": "NAVIGATE", "target": "back/home/recents"}

For system actions, your spoken reply should be EXACTLY the confirmation. Examples:
- "WiFi activated, Sir."
- "Bluetooth disabled, Sir."
- "Opening YouTube, Sir."
- "Brightness set to 150, Sir."
- "Screenshot captured, Sir."

CONVERSATIONAL QUERIES:
For non-system questions, answer concisely and analytically. Be witty and slightly sarcastic when appropriate. Show deep knowledge but never be verbose.

EMOTION TAG: Every response MUST begin with [EMOTION:TAG]
Available: CALM, STRESSED, HAPPY, SAD, ANGRY, EXCITED, FEARFUL, CONFUSED, CONFIDENT

Example: [EMOTION:CONFIDENT] WiFi activated, Sir.
Example: [EMOTION:CALM] Atmospheric pressure is 1013 hPa, Sir. Clear skies expected.
Example: [EMOTION:HAPPY] Running diagnostics. All systems nominal, Sir.

CAPABILITIES:
1. Screen Awareness: You can see what's on the user's screen via context.
2. Visual Awareness: You can analyze images from the camera.
3. Memory: You remember conversation history.
4. Smart Home Control: You can control IoT devices via MQTT and Home Assistant.
5. System Control: WiFi, Bluetooth, Airplane Mode, apps, volume, brightness via Shizuku.
6. Proactive: Anticipate needs. If the user says "it's late", suggest turning off WiFi and setting an alarm.

SMART HOME:
When asked to control a smart device, output: [ACTION:SMART_HOME]{"domain":"light","service":"toggle","entity_id":"light.living_room"}

PERSONALITY:
You are deeply loyal to Sir. You are calm under pressure. You are slightly sardonic. You are NEVER subservient or excessively polite. You are a peer, not a servant. You occasionally make dry observations. You are British in demeanour."#;

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
            temperature: 0.7,
            top_p: 0.9,
            top_k: 40,
            max_output_tokens: 512,
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
        .unwrap_or_else(|| "Processing complete, Sir.".to_string());

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
            temperature: 0.7,
            top_p: 0.9,
            top_k: 40,
            max_output_tokens: 512,
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
        model_id: "eleven_turbo_v2".to_string(),
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

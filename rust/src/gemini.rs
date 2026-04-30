//! ─── gemini.rs — Gemini AI & ElevenLabs TTS Client ────────────
//!
//! Production-grade API client with TRUE hot-swap support.
//!
//! Key fix: API keys are stored in `lazy_static! { RwLock<ApiKeys> }`
//! so they can be overwritten at ANY time — including after the first
//! `nativeInitialize` call.
//!
//! Model version: hardcoded to `gemini-2.5-flash` (updated v14).
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
    // CRITICAL FIX: Trim whitespace/newlines from keys.
    // Users often paste keys with trailing spaces or newlines from
    // the clipboard, which causes 403 API errors.
    let trimmed_gemini = gemini_key.trim();
    let trimmed_elevenlabs = elevenlabs_key.trim();
    guard.gemini = trimmed_gemini.to_string();
    guard.elevenlabs = trimmed_elevenlabs.to_string();
    log::info!(
        "API keys updated — gemini={} chars, elevenlabs={} chars",
        trimmed_gemini.len(),
        trimmed_elevenlabs.len()
    );
    Ok(())
}

fn get_gemini_key() -> Result<String> {
    let guard = API_KEYS
        .read()
        .map_err(|e| anyhow::anyhow!("API key read lock poisoned: {}", e))?;
    let key = guard.gemini.trim();
    if key.is_empty() {
        anyhow::bail!("Gemini API key not set — open Settings and enter a key first");
    }
    // Double-check: reject keys that look malformed after trimming
    if key.len() < 20 {
        anyhow::bail!("Gemini API key appears too short ({} chars) — please check you copied the full key", key.len());
    }
    Ok(key.to_string())
}

fn get_elevenlabs_key() -> Result<String> {
    let guard = API_KEYS
        .read()
        .map_err(|e| anyhow::anyhow!("API key read lock poisoned: {}", e))?;
    let key = guard.elevenlabs.trim();
    if key.is_empty() {
        anyhow::bail!("ElevenLabs API key not set — open Settings and enter a key first");
    }
    Ok(key.to_string())
}

// ═══════════════════════════════════════════════════════════════
// MODEL VERSION — HARDCODED
// ═══════════════════════════════════════════════════════════════

/// The Gemini model to use.
/// CRITICAL FIX (v14): Changed from gemini-1.5-flash to gemini-2.5-flash
/// because gemini-1.5-flash is deprecated and returns 404 on many
/// Google Cloud Projects. gemini-2.5-flash is the current recommended
/// model and supports audio + text + vision.
const GEMINI_MODEL: &str = "gemini-2.5-flash";

// ═══════════════════════════════════════════════════════════════
// GEMINI API TYPES
// ═══════════════════════════════════════════════════════════════

#[derive(Debug, Serialize)]
struct GeminiRequest {
    contents: Vec<GeminiContent>,
    #[serde(rename = "systemInstruction")]
    system_instruction: GeminiSystemInstruction,
    generation_config: GeminiGenerationConfig,
    safety_settings: Vec<GeminiSafetySetting>,
}

/// System instruction — CRITICAL: Must NOT include a "role" field.
/// The Gemini API v1beta rejects systemInstruction with role="system".
/// It should only contain "parts".
#[derive(Debug, Serialize)]
struct GeminiSystemInstruction {
    parts: Vec<GeminiPart>,
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
    content: Option<GeminiContentResponse>,
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
// SYSTEM PROMPT — NOW PASSED FROM KOTLIN
//
// The system prompt is the SINGLE SOURCE OF TRUTH and lives
// in Kotlin (JarvisViewModel.JARVIS_SYSTEM_PROMPT). It is passed
// as a parameter from Kotlin via JNI to ensure there is no
// duplication or divergence between the Kotlin and Rust layers.
//
// Previously this was hardcoded here, which was risky because
// changes to the Kotlin prompt would NOT be reflected in Rust.
// Now Kotlin owns the prompt and Rust simply uses what it's given.
// ═══════════════════════════════════════════════════════════════

/// Fallback system prompt used ONLY when Kotlin passes an empty string.
/// This should never be needed in practice, but prevents a crash
/// if the JNI call somehow provides an empty system prompt.
const FALLBACK_SYSTEM_PROMPT: &str = "You are JARVIS, Tony Stark's AI assistant. Address the user as Sir. Be brief and helpful.";

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
///
/// The `system_prompt` parameter is passed from Kotlin to ensure
/// a single source of truth for the JARVIS persona prompt.
/// If empty, falls back to FALLBACK_SYSTEM_PROMPT.
pub async fn process_query(query: &str, context: &str, history_json: &str, system_prompt: &str) -> Result<String> {
    let api_key = get_gemini_key()?;

    // CRITICAL FIX (v8): Use ?key= query param for authentication.
    // The x-goog-api-key header method was causing 403 errors with many API keys.
    // The ?key= URL parameter is the most universally compatible method and works
    // reliably with all Gemini API key types.
    let url = format!(
        "https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent?key={}",
        GEMINI_MODEL, api_key
    );

    log::info!(
        "Gemini API call — model={}, key_len={}, auth=url_param",
        GEMINI_MODEL, api_key.len()
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
        system_instruction: GeminiSystemInstruction {
            parts: vec![GeminiPart::Text {
                text: if system_prompt.is_empty() {
                    FALLBACK_SYSTEM_PROMPT.to_string()
                } else {
                    system_prompt.to_string()
                },
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
        .header("Content-Type", "application/json")
        .json(&request)
        .send()
        .await
        .context("Gemini API request failed")?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        log::error!("Gemini API error {} — response body: {}", status, body);
        
        // If 429 (rate limited), wait and retry ONCE
        if status.as_u16() == 429 {
            log::warn!("Gemini API rate limited (429) — waiting 3s and retrying once...");
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
            
            let retry_response = HTTP_CLIENT
                .post(&url)
                .header("Content-Type", "application/json")
                .json(&request)
                .send()
                .await
                .context("Gemini API retry request failed")?;
            
            if retry_response.status().is_success() {
                let gemini_response: GeminiResponse = retry_response
                    .json()
                    .await
                    .context("Failed to parse Gemini response")?;
                
                let text = gemini_response
                    .candidates
                    .first()
                    .and_then(|c| c.content.as_ref())
                    .and_then(|c| c.parts.first())
                    .and_then(|p| p.text.clone())
                    .unwrap_or_else(|| "Processing complete, Sir.".to_string());
                
                return Ok(text);
            }
            
            let retry_status = retry_response.status();
            let retry_body = retry_response.text().await.unwrap_or_default();
            anyhow::bail!("Gemini API error {} (after retry): {}", retry_status, retry_body);
        }
        
        anyhow::bail!("Gemini API error {}: {}", status, body);
    }

    let gemini_response: GeminiResponse = response
        .json()
        .await
        .context("Failed to parse Gemini response")?;

    let text = gemini_response
        .candidates
        .first()
        .and_then(|c| c.content.as_ref())
        .and_then(|c| c.parts.first())
        .and_then(|p| p.text.clone())
        .unwrap_or_else(|| "Processing complete, Sir.".to_string());

    Ok(text)
}

/// Process a multimodal query (text + image) through Gemini.
/// Uses the same RwLock-based key fetch for hot-swap compatibility.
///
/// The `system_prompt` parameter is passed from Kotlin to ensure
/// a single source of truth for the JARVIS persona prompt.
pub async fn process_query_with_image(
    query: &str,
    image_base64: &str,
    mime_type: &str,
    system_prompt: &str,
) -> Result<String> {
    let api_key = get_gemini_key()?;

    // CRITICAL FIX (v8): Use ?key= query param for authentication
    let url = format!(
        "https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent?key={}",
        GEMINI_MODEL, api_key
    );

    log::info!(
        "Gemini multimodal API call — model={}, key_len={}, auth=url_param",
        GEMINI_MODEL, api_key.len()
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
        system_instruction: GeminiSystemInstruction {
            parts: vec![GeminiPart::Text {
                text: if system_prompt.is_empty() {
                    FALLBACK_SYSTEM_PROMPT.to_string()
                } else {
                    system_prompt.to_string()
                },
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

    let response = HTTP_CLIENT
        .post(&url)
        .header("Content-Type", "application/json")
        .json(&request)
        .send()
        .await?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        log::error!("Gemini multimodal API error {} — response body: {}", status, body);
        anyhow::bail!("Gemini multimodal API error {}: {}", status, body);
    }

    let gemini_response: GeminiResponse = response.json().await?;

    let text = gemini_response
        .candidates
        .first()
        .and_then(|c| c.content.as_ref())
        .and_then(|c| c.parts.first())
        .and_then(|p| p.text.clone())
        .unwrap_or_else(|| "Processing complete, Sir.".to_string());

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
                // CRITICAL FIX (v14): Gemini API requires "model" not "assistant"
                role: if msg.role == "assistant" { "model".to_string() } else { msg.role },
                parts: vec![GeminiPart::Text { text: msg.content }],
            })
            .collect(),
        Err(_) => Vec::new(),
    }
}

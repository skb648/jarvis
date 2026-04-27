//! ─── emotion.rs — Emotion Analysis Engine ─────────────────────
//!
//! Text-based emotion detection using lexicon and pattern matching.
//! For production, this should use a trained NLP model (e.g., via ONNX Runtime).
//!
//! Supported emotions: calm, stressed, happy, sad, angry, excited,
//! fearful, confused, confident, neutral

use serde::Serialize;

/// Emotion analysis result
#[derive(Debug, Clone, Serialize)]
pub struct EmotionResult {
    pub emotion: String,
    pub confidence: f64,
    /// Valence: -1.0 (negative) to +1.0 (positive)
    pub valence: f64,
    /// Arousal: 0.0 (calm) to 1.0 (excited)
    pub arousal: f64,
}

/// Emotion lexicon: keyword → (emotion, valence, arousal)
const EMOTION_LEXICON: &[(&str, &str, f64, f64)] = &[
    // Happy / Positive
    ("happy", "happy", 0.8, 0.6),
    ("glad", "happy", 0.7, 0.5),
    ("joy", "happy", 0.9, 0.7),
    ("wonderful", "happy", 0.8, 0.6),
    ("great", "happy", 0.7, 0.5),
    ("excellent", "happy", 0.8, 0.6),
    ("amazing", "excited", 0.8, 0.8),
    ("love", "happy", 0.9, 0.6),
    ("awesome", "excited", 0.8, 0.7),
    ("fantastic", "excited", 0.8, 0.7),
    ("thanks", "happy", 0.6, 0.3),
    ("thank", "happy", 0.6, 0.3),
    // Sad / Negative
    ("sad", "sad", -0.7, 0.2),
    ("unhappy", "sad", -0.6, 0.2),
    ("depressed", "sad", -0.8, 0.1),
    ("disappointed", "sad", -0.6, 0.3),
    ("sorry", "sad", -0.4, 0.2),
    ("miss", "sad", -0.5, 0.3),
    ("lonely", "sad", -0.7, 0.1),
    // Angry
    ("angry", "angry", -0.8, 0.9),
    ("furious", "angry", -0.9, 0.9),
    ("hate", "angry", -0.8, 0.8),
    ("annoyed", "angry", -0.6, 0.7),
    ("frustrated", "angry", -0.7, 0.7),
    ("irritated", "angry", -0.6, 0.7),
    // Fear
    ("afraid", "fearful", -0.7, 0.7),
    ("scared", "fearful", -0.7, 0.8),
    ("worried", "fearful", -0.5, 0.5),
    ("anxious", "stressed", -0.5, 0.6),
    ("nervous", "stressed", -0.4, 0.5),
    // Calm / Confident
    ("calm", "calm", 0.3, 0.1),
    ("relaxed", "calm", 0.4, 0.1),
    ("peaceful", "calm", 0.5, 0.1),
    ("confident", "confident", 0.6, 0.4),
    ("sure", "confident", 0.5, 0.4),
    ("certain", "confident", 0.5, 0.3),
    // Excited
    ("excited", "excited", 0.8, 0.8),
    ("thrilled", "excited", 0.9, 0.9),
    ("eager", "excited", 0.6, 0.7),
    // Confused
    ("confused", "confused", -0.2, 0.4),
    ("unsure", "confused", -0.2, 0.3),
    ("what", "confused", -0.1, 0.3),
    ("how", "confused", -0.1, 0.2),
    // Stressed
    ("stressed", "stressed", -0.5, 0.6),
    ("overwhelmed", "stressed", -0.6, 0.7),
    ("busy", "stressed", -0.3, 0.5),
    ("tired", "stressed", -0.4, 0.2),
];

/// Analyze text for emotional content.
pub fn analyze_text_emotion(text: &str) -> EmotionResult {
    let lower = text.to_lowercase();
    let words: Vec<&str> = lower.split_whitespace().collect();

    if words.is_empty() {
        return EmotionResult {
            emotion: "neutral".to_string(),
            confidence: 0.5,
            valence: 0.0,
            arousal: 0.3,
        };
    }

    // Score each emotion based on lexicon matches
    let mut emotion_scores: std::collections::HashMap<String, f64> =
        std::collections::HashMap::new();
    let mut total_valence = 0.0;
    let mut total_arousal = 0.0;
    let mut match_count = 0;

    for word in &words {
        for (keyword, emotion, valence, arousal) in EMOTION_LEXICON {
            if word.contains(keyword) {
                *emotion_scores.entry(emotion.to_string()).or_insert(0.0) += 1.0;
                total_valence += valence;
                total_arousal += arousal;
                match_count += 1;
                break; // One match per word
            }
        }
    }

    if match_count == 0 {
        return EmotionResult {
            emotion: "neutral".to_string(),
            confidence: 0.5,
            valence: 0.0,
            arousal: 0.3,
        };
    }

    // Find dominant emotion
    let (dominant_emotion, max_score) = emotion_scores
        .iter()
        .max_by(|a, b| a.1.partial_cmp(b.1).unwrap_or(std::cmp::Ordering::Equal))
        .map(|(e, s)| (e.clone(), *s))
        .unwrap_or_else(|| ("neutral".to_string(), 0.0));

    // Confidence = proportion of words matching the dominant emotion
    let confidence = (max_score / words.len() as f64).min(1.0).max(0.3);
    let avg_valence = total_valence / match_count as f64;
    let avg_arousal = total_arousal / match_count as f64;

    EmotionResult {
        emotion: dominant_emotion,
        confidence,
        valence: avg_valence,
        arousal: avg_arousal,
    }
}

/// Parse emotion tag from Gemini response text.
/// Expected format: [EMOTION:TAG] at the start of the response.
#[allow(dead_code)]
pub fn parse_emotion_tag(response: &str) -> (String, String) {
    // Look for [EMOTION:TAG] pattern
    if let Some(start) = response.find("[EMOTION:") {
        if let Some(end) = response[start..].find(']') {
            let tag = &response[start + 9..start + end];
            let clean_text = response[start + end + 1..].trim().to_string();
            return (tag.to_string(), clean_text);
        }
    }

    // No emotion tag found, try to detect from text content
    let result = analyze_text_emotion(response);
    (result.emotion, response.to_string())
}

/// Map emotion string to ElevenLabs voice ID.
#[allow(dead_code)]
pub fn emotion_to_voice_id(emotion: &str) -> (&'static str, f32, f32) {
    match emotion {
        "CALM" | "calm" => ("21m00Tcm4TlvDq8ikWAM", 0.75, 0.75), // Rachel
        "HAPPY" | "happy" => ("yoZ06aMxZJJ28mfd3POQ", 0.5, 0.75), // Sam
        "EXCITED" | "excited" => ("jBpfuIE2acCO8z3wKNLl", 0.3, 0.75), // Gigi
        "SAD" | "sad" => ("AZnzlk1XvdvUeBnXmlld", 0.6, 0.8),     // Domi
        "ANGRY" | "angry" => ("ErXwobaYiN019PkySvjV", 0.4, 0.7), // Antoni
        "STRESSED" | "stressed" => ("MF3mGyEYCl7XYWbV9V6O", 0.5, 0.7), // Elli
        "FEARFUL" | "fearful" => ("TxGEqnHWrfWFTfGW9XjX", 0.4, 0.6), // Josh
        "CONFUSED" | "confused" => ("VR6AewLTigWG4xSOukaG", 0.5, 0.6), // Sam
        "CONFIDENT" | "confident" => ("pNInz6obpgDQGcFmaJgB", 0.6, 0.8), // Adam
        _ => ("21m00Tcm4TlvDq8ikWAM", 0.75, 0.75),               // Rachel (default)
    }
}

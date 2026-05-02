//! ─── voice_pattern.rs — Voice Pattern Detection for Call Commands ───
//!
//! Detects voice patterns associated with call answer/reject keywords
//! using layered audio analysis (similar strategy to wake_word module).
//!
//! Detection strategy (layered):
//!   Layer 1: Energy-based pre-filter (skip silence)
//!   Layer 2: Spectral feature matching (speech-like frequency)
//!   Layer 3: Zero-crossing rate validation
//!   Layer 4: Duration gating (command-length speech)
//!   Layer 5: Burst pattern analysis (single vs multi-syllable)
//!   Layer 6: Confidence scoring
//!
//! Returns JSON like {"id": "call_answer", "confidence": 0.85}
//! or None if no voice pattern detected.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::wake_word;

/// Cooldown between voice pattern detections (2 seconds)
static LAST_PATTERN_TRIGGER_MS: AtomicU64 = AtomicU64::new(0);

/// Minimum confidence threshold to report a detection
const MIN_CONFIDENCE: f64 = 0.5;

/// Get current time in milliseconds since epoch
fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Voice pattern detection result
#[derive(Debug, Clone)]
pub struct VoicePattern {
    /// Pattern identifier: "call_answer" or "call_reject"
    pub id: String,
    /// Detection confidence (0.0 - 1.0)
    pub confidence: f64,
}

/// Burst analysis result
struct BurstAnalysis {
    /// Number of detected speech bursts
    burst_count: usize,
    /// Total number of speech frames across all bursts
    total_speech_frames: usize,
    /// Energy of the dominant (loudest) burst
    dominant_burst_energy: f64,
    /// Average energy across all bursts
    avg_burst_energy: f64,
}

/// Detect voice patterns in audio data for call management commands.
///
/// Uses layered analysis (energy, spectral, ZCR, duration, burst pattern)
/// similar to the wake_word module, but tuned for short command-like
/// utterances like "answer" or "reject".
///
/// Returns a VoicePattern if detected, or None if no pattern found.
pub fn detect_voice_pattern(audio_data: &[u8], sample_rate: u32) -> Option<VoicePattern> {
    let samples = wake_word::bytes_to_f64_samples(audio_data);

    if samples.is_empty() {
        return None;
    }

    // Layer 1: Energy pre-filter — must have sufficient volume
    let energy: f64 = samples.iter().map(|s| s * s).sum::<f64>() / samples.len() as f64;
    let rms = energy.sqrt();

    // Same threshold as wake_word for consistency
    if rms < 0.03 {
        return None;
    }

    // Layer 2: Spectral analysis — speech-like frequency distribution
    let spectral_centroid = wake_word::compute_spectral_centroid(&samples, sample_rate as f64);

    // Voice commands typically have energy in 200-4500Hz range
    // (wider than wake_word to accommodate different voice types)
    if spectral_centroid < 200.0 || spectral_centroid > 4500.0 {
        return None;
    }

    // Layer 3: Zero-crossing rate — speech has moderate ZCR
    let zcr = wake_word::compute_zero_crossing_rate(&samples);
    // Slightly wider range than wake_word for command diversity
    if zcr < 0.08 || zcr > 0.35 {
        return None;
    }

    // Layer 4: Duration gating — short commands like "answer" or "reject"
    // are typically 300-1500ms
    let duration_ms = (samples.len() as f64 / sample_rate as f64) * 1000.0;
    if duration_ms < 200.0 || duration_ms > 2000.0 {
        return None;
    }

    // Layer 5: Burst pattern analysis
    let burst_analysis = analyze_burst_pattern(&samples, sample_rate);

    // Need at least one speech burst to be a voice command
    if burst_analysis.burst_count == 0 {
        return None;
    }

    // Layer 6: Cooldown — 2 second cooldown between pattern detections
    let now = now_ms();
    let last = LAST_PATTERN_TRIGGER_MS.load(Ordering::Relaxed);
    if now >= last && now - last < 2000 {
        return None;
    }

    // Determine pattern based on burst characteristics:
    // - Single burst or high spectral centroid → likely "answer" (short, sharp command)
    // - Multiple bursts or lower spectral centroid → likely "reject" (longer, multi-syllable)
    let confidence = compute_confidence(rms, spectral_centroid, zcr, duration_ms, &burst_analysis);

    if confidence < MIN_CONFIDENCE {
        return None;
    }

    let pattern_id = if burst_analysis.burst_count <= 1 || spectral_centroid > 1500.0 {
        "call_answer"
    } else {
        "call_reject"
    };

    LAST_PATTERN_TRIGGER_MS.store(now, Ordering::Relaxed);

    log::info!(
        "Voice pattern detected: id={}, confidence={:.2}, bursts={}, spectral={:.0}Hz, duration={:.0}ms",
        pattern_id,
        confidence,
        burst_analysis.burst_count,
        spectral_centroid,
        duration_ms
    );

    Some(VoicePattern {
        id: pattern_id.to_string(),
        confidence,
    })
}

/// Analyze the burst pattern in audio samples.
///
/// Splits the audio into frames and tracks energy bursts (speech segments)
/// separated by silence. This helps distinguish single-syllable commands
/// ("answer") from multi-syllable ones ("reject").
fn analyze_burst_pattern(samples: &[f64], sample_rate: u32) -> BurstAnalysis {
    // Frame size: ~46ms at sample rate (same as wake_word)
    let frame_size = (sample_rate as usize * 46 / 1000).max(256);
    let energy_threshold: f64 = 0.03;
    let burst_end_frames: usize = 3;
    let min_burst_frames: usize = 3;

    let mut burst_count: usize = 0;
    let mut in_burst = false;
    let mut burst_frame_count: usize = 0;
    let mut burst_energy: f64 = 0.0;
    let mut total_speech_frames: usize = 0;
    let mut dominant_burst_energy: f64 = 0.0;
    let mut total_burst_energy: f64 = 0.0;
    let mut silence_frames: usize = 0;

    for chunk in samples.chunks(frame_size) {
        let frame_energy: f64 = chunk.iter().map(|s| s * s).sum::<f64>() / chunk.len() as f64;
        let frame_rms = frame_energy.sqrt();

        if frame_rms > energy_threshold {
            silence_frames = 0;
            if !in_burst {
                in_burst = true;
                burst_frame_count = 0;
                burst_energy = 0.0;
            }
            burst_frame_count += 1;
            burst_energy += frame_rms;
        } else if in_burst {
            silence_frames += 1;
            if silence_frames >= burst_end_frames {
                if burst_frame_count >= min_burst_frames {
                    burst_count += 1;
                    total_speech_frames += burst_frame_count;
                    total_burst_energy += burst_energy;
                    if burst_energy > dominant_burst_energy {
                        dominant_burst_energy = burst_energy;
                    }
                }
                in_burst = false;
                burst_frame_count = 0;
                burst_energy = 0.0;
            }
        }
    }

    // Finalize any in-progress burst
    if in_burst && burst_frame_count >= min_burst_frames {
        burst_count += 1;
        total_speech_frames += burst_frame_count;
        total_burst_energy += burst_energy;
        if burst_energy > dominant_burst_energy {
            dominant_burst_energy = burst_energy;
        }
    }

    let avg_burst_energy = if burst_count > 0 {
        total_burst_energy / burst_count as f64
    } else {
        0.0
    };

    BurstAnalysis {
        burst_count,
        total_speech_frames,
        dominant_burst_energy,
        avg_burst_energy,
    }
}

/// Compute overall confidence based on how well the audio matches
/// voice command characteristics.
///
/// Weighted scoring across multiple signal features:
/// - Energy (0-0.25): Higher RMS = more likely intentional speech
/// - Spectral (0-0.25): Closer to speech centroid = better match
/// - ZCR (0-0.25): Closer to typical speech ZCR = better match
/// - Duration (0-0.25): Commands are typically 400-800ms
/// - Burst bonus (+0.1): 1-3 bursts typical for voice commands
fn compute_confidence(
    rms: f64,
    spectral_centroid: f64,
    zcr: f64,
    duration_ms: f64,
    burst: &BurstAnalysis,
) -> f64 {
    let mut confidence = 0.0;

    // Energy confidence (0-0.25): higher RMS = more likely speech
    let energy_score = (rms / 0.15).min(1.0) * 0.25;
    confidence += energy_score;

    // Spectral confidence (0-0.25): closer to speech centroid = better
    let spectral_distance = (spectral_centroid - 1200.0).abs() / 2000.0;
    let spectral_score = (1.0 - spectral_distance.min(1.0)) * 0.25;
    confidence += spectral_score;

    // ZCR confidence (0-0.25): closer to typical speech ZCR (0.15-0.25) = better
    let zcr_distance = (zcr - 0.20).abs() / 0.20;
    let zcr_score = (1.0 - zcr_distance.min(1.0)) * 0.25;
    confidence += zcr_score;

    // Duration confidence (0-0.25): commands are typically 400-800ms
    let duration_distance = (duration_ms - 600.0).abs() / 600.0;
    let duration_score = (1.0 - duration_distance.min(1.0)) * 0.25;
    confidence += duration_score;

    // Bonus for burst patterns matching typical commands (1-3 syllables)
    if burst.burst_count >= 1 && burst.burst_count <= 3 {
        confidence += 0.1;
    }

    confidence.min(1.0)
}

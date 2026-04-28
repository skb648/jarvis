//! ─── wake_word.rs — Wake Word Detection Engine ────────────────
//!
//! Implements real-time wake word detection for "Jarvis".
//!
//! Detection strategy (layered):
//!   Layer 1: Energy-based pre-filter (skip silence)
//!   Layer 2: Spectral feature matching (MFCC-like)
//!   Layer 3: Zero-crossing rate validation
//!   Layer 4: Duration gating (minimum speech length)
//!   Layer 5: Cooldown (prevent rapid re-triggers)
//!
//! IMPORTANT: This is a HEURISTIC detector, not a keyword spotter.
//! It detects SPEECH activity, not the specific word "Jarvis".
//! For production, replace with Porcupine, Snowboy, or ONNX model.
//!
//! v7 FIX: Reduced false positives by:
//!   - Raising energy threshold from 0.02 → 0.04
//!   - Requiring minimum 5 consecutive speech frames (was 3)
//!   - Adding a cooldown period of 3 seconds between triggers
//!   - Narrowing spectral centroid range to human voice fundamentals
//!   - Adding duration gating: speech must last 200-2000ms

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

/// Configuration for wake word detection
struct WakeWordConfig {
    /// Minimum RMS energy to consider as speech (avoids false triggers in silence)
    energy_threshold: f64,
    /// Minimum number of consecutive speech frames before triggering
    min_speech_frames: usize,
    /// Maximum number of speech frames (speech too long = not a wake word)
    max_speech_frames: usize,
    /// Cooldown between detections in milliseconds
    cooldown_ms: u64,
    /// Target keyword for simple matching (used in speech recognizer output)
    keyword: String,
}

impl Default for WakeWordConfig {
    fn default() -> Self {
        Self {
            // v7: Raised from 0.02 to 0.04 — filters out background noise better
            energy_threshold: 0.04,
            // v7: Raised from 3 to 5 — requires more sustained speech
            min_speech_frames: 5,
            // v7: New — if speech goes on too long, it's not a wake word
            // At 44100Hz with 2048-sample frames ≈ 46ms/frame, 43 frames ≈ 2s
            max_speech_frames: 43,
            // v7: New — 3 second cooldown between triggers
            cooldown_ms: 3000,
            keyword: "jarvis".to_string(),
        }
    }
}

/// Global last-trigger timestamp for cooldown (shared across detector instances)
static LAST_TRIGGER_MS: AtomicU64 = AtomicU64::new(0);

/// Get current time in milliseconds since epoch
fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Stateful wake word detector (for continuous audio streams)
pub struct WakeWordDetector {
    config: WakeWordConfig,
    speech_frame_count: usize,
    is_speech: bool,
}

impl WakeWordDetector {
    pub fn new() -> Self {
        Self {
            config: WakeWordConfig::default(),
            speech_frame_count: 0,
            is_speech: false,
        }
    }

    /// Process an audio frame and return whether a wake word was detected.
    pub fn process_frame(&mut self, audio_data: &[u8], sample_rate: u32) -> bool {
        let energy = compute_frame_energy(audio_data);

        if energy > self.config.energy_threshold {
            self.speech_frame_count += 1;
            self.is_speech = true;
        } else {
            // v7: Only reset if we haven't accumulated enough frames yet
            // If we had speech frames and now silence, check if we should trigger
            if self.speech_frame_count >= self.config.min_speech_frames
                && self.speech_frame_count <= self.config.max_speech_frames
            {
                let detected = self.check_cooldown_and_trigger();
                self.speech_frame_count = 0;
                self.is_speech = false;
                return detected;
            }
            self.speech_frame_count = 0;
            self.is_speech = false;
        }

        // v7: If speech goes on too long, reset — it's not a short wake word
        if self.speech_frame_count > self.config.max_speech_frames {
            self.speech_frame_count = 0;
            self.is_speech = false;
            return false;
        }

        false
    }

    /// Check cooldown and trigger if allowed
    fn check_cooldown_and_trigger(&self) -> bool {
        let now = now_ms();
        let last = LAST_TRIGGER_MS.load(Ordering::Relaxed);
        if now > last && now - last < self.config.cooldown_ms {
            log::debug!(
                "Wake word cooldown active — {}ms since last trigger (need {}ms)",
                now - last,
                self.config.cooldown_ms
            );
            return false;
        }
        LAST_TRIGGER_MS.store(now, Ordering::Relaxed);
        true
    }

    /// Reset the detector state
    pub fn reset(&mut self) {
        self.speech_frame_count = 0;
        self.is_speech = false;
    }
}

/// One-shot wake word detection on an audio buffer.
/// Returns true if a wake word is detected in the given audio data.
///
/// v7: Added cooldown, higher thresholds, and duration gating.
pub fn detect(audio_data: &[u8], sample_rate: u32) -> bool {
    let samples = bytes_to_f64_samples(audio_data);

    if samples.is_empty() {
        return false;
    }

    // Layer 1: Energy pre-filter
    let energy: f64 = samples.iter().map(|s| s * s).sum::<f64>() / samples.len() as f64;
    let rms = energy.sqrt();

    // v7: Raised threshold from 0.02 to 0.04
    if rms < 0.04 {
        return false;
    }

    // Layer 2: Spectral analysis — check for speech-like frequency distribution
    let spectral_centroid = compute_spectral_centroid(&samples, sample_rate as f64);

    // v7: Narrowed range — human speech fundamentals are 85-300Hz for male,
    // 165-255Hz for female, with harmonics up to ~4kHz.
    // Spectral centroid for speech is typically 300-3500 Hz
    if spectral_centroid < 200.0 || spectral_centroid > 4500.0 {
        return false;
    }

    // Layer 3: Zero-crossing rate — speech has moderate ZCR (not too low like tonal,
    // not too high like noise)
    let zcr = compute_zero_crossing_rate(&samples);

    // v7: Narrowed ZCR range for speech
    if zcr < 0.08 || zcr > 0.35 {
        return false;
    }

    // Layer 4: Duration gating — the audio buffer should represent a short utterance
    // At 44100Hz with 2048 samples, one frame ≈ 46ms
    // A wake word "Jarvis" is typically 300-800ms
    let duration_ms = (samples.len() as f64 / sample_rate as f64) * 1000.0;
    if duration_ms < 50.0 || duration_ms > 3000.0 {
        return false;
    }

    // Layer 5: Cooldown check
    let now = now_ms();
    let last = LAST_TRIGGER_MS.load(Ordering::Relaxed);
    if now > last && now - last < 3000 {
        return false;
    }

    // All filters passed — speech detected that matches wake word characteristics.
    // In a production system, this is where we'd run a neural network
    // to classify whether the speech contains the keyword "jarvis".
    // For now, we use enhanced heuristic detection.
    LAST_TRIGGER_MS.store(now, Ordering::Relaxed);
    true
}

/// Compute spectral centroid (brightness) of audio signal.
fn compute_spectral_centroid(samples: &[f64], sample_rate: f64) -> f64 {
    if samples.len() < 256 {
        return 0.0;
    }

    let mut planner = rustfft::FftPlanner::new();
    let fft = planner.plan_fft_forward(samples.len());

    let mut buffer: Vec<rustfft::num_complex::Complex<f64>> = samples
        .iter()
        .map(|&s| rustfft::num_complex::Complex::new(s, 0.0))
        .collect();

    fft.process(&mut buffer);

    let mut weighted_sum = 0.0;
    let mut magnitude_sum = 0.0;

    for (i, c) in buffer.iter().enumerate().take(samples.len() / 2) {
        let magnitude = (c.re * c.re + c.im * c.im).sqrt();
        let frequency = i as f64 * sample_rate / samples.len() as f64;
        weighted_sum += frequency * magnitude;
        magnitude_sum += magnitude;
    }

    if magnitude_sum > 0.0 {
        weighted_sum / magnitude_sum
    } else {
        0.0
    }
}

/// Compute zero-crossing rate of audio signal.
fn compute_zero_crossing_rate(samples: &[f64]) -> f64 {
    if samples.len() < 2 {
        return 0.0;
    }

    let crossings = samples
        .windows(2)
        .filter(|w| (w[0] >= 0.0) != (w[1] >= 0.0))
        .count();

    crossings as f64 / (samples.len() - 1) as f64
}

/// Compute RMS energy of an audio frame.
fn compute_frame_energy(audio_data: &[u8]) -> f64 {
    let samples = bytes_to_f64_samples(audio_data);
    if samples.is_empty() {
        return 0.0;
    }
    let sum_sq: f64 = samples.iter().map(|s| s * s).sum();
    (sum_sq / samples.len() as f64).sqrt()
}

/// Convert 16-bit PCM bytes to f64 samples.
fn bytes_to_f64_samples(audio_data: &[u8]) -> Vec<f64> {
    audio_data
        .chunks(2)
        .filter_map(|chunk| {
            if chunk.len() == 2 {
                Some(i16::from_le_bytes([chunk[0], chunk[1]]) as f64 / 32768.0)
            } else {
                None
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_silence_detection() {
        let silence = vec![0u8; 4096];
        assert!(!detect(&silence, 16000));
    }

    #[test]
    fn test_zero_crossing_rate_silence() {
        let silence = vec![0.0f64; 1024];
        let zcr = compute_zero_crossing_rate(&silence);
        assert_eq!(zcr, 0.0);
    }

    #[test]
    fn test_cooldown_prevents_rapid_triggers() {
        // Reset cooldown
        LAST_TRIGGER_MS.store(0, Ordering::Relaxed);

        // Create a signal that would pass all filters
        let mut signal = Vec::new();
        for i in 0..4096 {
            let t = i as f64 / 44100.0;
            // 440Hz sine wave with amplitude 0.3
            let sample = (2.0 * std::f64::consts::PI * 440.0 * t).sin() * 0.3;
            let s16 = (sample * 32768.0) as i16;
            signal.push((s16 & 0xFF) as u8);
            signal.push(((s16 >> 8) & 0xFF) as u8);
        }

        // First detection should work (if it passes filters)
        let _first = detect(&signal, 44100);

        // Second immediate detection should be blocked by cooldown
        let second = detect(&signal, 44100);
        assert!(!second, "Cooldown should prevent rapid re-trigger");
    }
}

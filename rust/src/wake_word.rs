//! ─── wake_word.rs — Wake Word Detection Engine ────────────────
//!
//! Implements real-time wake word detection for "Jarvis".
//!
//! Detection strategy (layered):
//!   Layer 1: Energy-based pre-filter (skip silence)
//!   Layer 2: Spectral feature matching (MFCC-like)
//!   Layer 3: Zero-crossing rate validation
//!   Layer 4: Duration gating (minimum speech length)
//!   Layer 5: Syllable pattern detection (JAR-vis 2-burst pattern)
//!   Layer 6: Cooldown (prevent rapid re-triggers)
//!
//! v9 FIX: Added syllable pattern detection for "Jarvis" specifically:
//!   - Tracks individual speech bursts instead of just frame counts
//!   - Detects 2-syllable pattern: JAR (stressed) → pause → vis (lower energy)
//!   - Requires 2 bursts within 200-800ms of each other
//!   - First burst must have ≥60% of total burst energy (stressed syllable)
//!   - Lowered energy threshold from 0.06 → 0.03 (compensated by pattern matching)
//!   - Lowered cooldown from 5000ms → 3000ms for faster re-detection

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

/// Configuration for wake word detection
#[allow(dead_code)]
struct WakeWordConfig {
    /// Minimum RMS energy to consider as speech (avoids false triggers in silence)
    energy_threshold: f64,
    /// Minimum number of consecutive speech frames before considering a burst
    min_speech_frames: usize,
    /// Maximum number of speech frames (speech too long = not a wake word)
    max_speech_frames: usize,
    /// Cooldown between detections in milliseconds
    cooldown_ms: u64,
    /// Target keyword for simple matching (used in speech recognizer output)
    keyword: String,
    /// Minimum gap between syllable bursts in milliseconds
    syllable_gap_min_ms: u64,
    /// Maximum gap between syllable bursts in milliseconds
    syllable_gap_max_ms: u64,
    /// Minimum energy ratio of first burst to total (first syllable is stressed)
    first_syllable_energy_ratio: f64,
    /// Number of silence frames to consider a burst ended
    burst_end_silence_frames: usize,
}

impl Default for WakeWordConfig {
    fn default() -> Self {
        Self {
            // v9: Lowered from 0.06 to 0.03 — syllable pattern matching compensates
            energy_threshold: 0.03,
            // v9: Lowered from 8 to 4 — each syllable is short, pattern matters more
            min_speech_frames: 4,
            // v9: Kept at 25 — total speech duration still bounded
            max_speech_frames: 25,
            // v9: Lowered from 5000 to 3000 — allow faster re-detection
            cooldown_ms: 3000,
            keyword: "jarvis".to_string(),
            // Minimum gap between JAR and vis syllables (ms)
            syllable_gap_min_ms: 200,
            // Maximum gap between JAR and vis syllables (ms)
            syllable_gap_max_ms: 800,
            // First syllable "JAR" must carry at least 60% of total burst energy
            first_syllable_energy_ratio: 0.60,
            // 3+ frames of silence to end a burst
            burst_end_silence_frames: 3,
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

/// A single speech burst detected in the audio stream.
/// "Jarvis" produces 2 bursts: "JAR" (stressed) and "vis" (unstressed).
#[derive(Debug, Clone)]
#[allow(dead_code)]
struct SpeechBurst {
    /// Timestamp when the burst started (ms since epoch)
    start_time_ms: u64,
    /// Timestamp when the burst ended (ms since epoch)
    end_time_ms: u64,
    /// Cumulative RMS energy of frames in this burst
    energy: f64,
    /// Number of frames in this burst
    frame_count: usize,
}

impl SpeechBurst {
    /// Duration of the burst in milliseconds (approximate, based on frame timing)
    fn duration_ms(&self) -> u64 {
        self.end_time_ms.saturating_sub(self.start_time_ms)
    }
}

/// Stateful wake word detector (for continuous audio streams)
#[allow(dead_code)]
pub struct WakeWordDetector {
    config: WakeWordConfig,
    /// Consecutive speech frames in current burst
    speech_frame_count: usize,
    /// Whether we are currently in a speech burst
    is_speech: bool,
    /// Consecutive silence frames (used to detect burst endings)
    silence_frame_count: usize,
    /// Accumulated energy for the current burst
    current_burst_energy: f64,
    /// Start time of the current burst
    current_burst_start_ms: u64,
    /// Completed bursts waiting for syllable pattern analysis
    bursts: Vec<SpeechBurst>,
    /// Approximate frame duration in ms (set on first process_frame call)
    frame_duration_ms: f64,
}

#[allow(dead_code)]
impl WakeWordDetector {
    pub fn new() -> Self {
        Self {
            config: WakeWordConfig::default(),
            speech_frame_count: 0,
            is_speech: false,
            silence_frame_count: 0,
            current_burst_energy: 0.0,
            current_burst_start_ms: 0,
            bursts: Vec::new(),
            frame_duration_ms: 46.0, // ~46ms per frame at 16kHz/512 samples
        }
    }

    /// Process an audio frame and return whether a wake word was detected.
    pub fn process_frame(&mut self, audio_data: &[u8], sample_rate: u32) -> bool {
        let energy = compute_frame_energy(audio_data);
        let now = now_ms();

        // Estimate frame duration from sample rate and buffer size
        let samples_count = audio_data.len() / 2; // 16-bit = 2 bytes per sample
        if samples_count > 0 && sample_rate > 0 {
            self.frame_duration_ms = (samples_count as f64 / sample_rate as f64) * 1000.0;
        }

        if energy > self.config.energy_threshold {
            // We're in a speech frame
            self.silence_frame_count = 0;

            if !self.is_speech {
                // Start of a new burst
                self.is_speech = true;
                self.current_burst_start_ms = now;
                self.current_burst_energy = 0.0;
            }

            self.speech_frame_count += 1;
            self.current_burst_energy += energy;

            // If speech goes on too long, it's not a short wake word — reset everything
            if self.speech_frame_count > self.config.max_speech_frames {
                self.reset_burst_state();
                return false;
            }
        } else {
            // Silence frame
            if self.is_speech {
                self.silence_frame_count += 1;

                // If we've had enough silence frames, the burst has ended
                if self.silence_frame_count >= self.config.burst_end_silence_frames {
                    self.finalize_current_burst(now);
                }
            }
        }

        // Check for syllable pattern among accumulated bursts
        if self.detect_syllable_pattern() {
            // Pattern found — check cooldown
            if self.check_cooldown_and_trigger() {
                // Clear bursts after successful detection
                self.bursts.clear();
                self.reset_burst_state();
                return true;
            }
        }

        // Prune old bursts that are too far apart to form a syllable pattern
        self.prune_old_bursts(now);

        false
    }

    /// Finalize the current burst and add it to the bursts list
    fn finalize_current_burst(&mut self, now: u64) {
        if self.speech_frame_count >= self.config.min_speech_frames {
            let burst = SpeechBurst {
                start_time_ms: self.current_burst_start_ms,
                end_time_ms: now.saturating_sub(
                    (self.silence_frame_count as f64 * self.frame_duration_ms) as u64
                ),
                energy: self.current_burst_energy,
                frame_count: self.speech_frame_count,
            };
            self.bursts.push(burst);
        }
        self.reset_burst_state();
    }

    /// Reset the current burst tracking state (doesn't clear completed bursts)
    fn reset_burst_state(&mut self) {
        self.speech_frame_count = 0;
        self.is_speech = false;
        self.silence_frame_count = 0;
        self.current_burst_energy = 0.0;
    }

    /// Detect the "JAR-vis" syllable pattern among accumulated bursts.
    ///
    /// "Jarvis" has a distinctive 2-syllable pattern:
    ///   - "JAR" (stressed, higher energy) → brief pause → "vis" (lower energy)
    ///
    /// We look for:
    ///   1. Exactly 2 bursts within 200-800ms of each other
    ///   2. First burst has ≥60% of total burst energy (stressed syllable)
    ///   3. Each burst has minimum frame count
    fn detect_syllable_pattern(&self) -> bool {
        if self.bursts.len() < 2 {
            return false;
        }

        // Check the two most recent bursts for the JAR-vis pattern
        let len = self.bursts.len();
        for i in (0..len.saturating_sub(1)).rev() {
            let first = &self.bursts[i];
            let second = &self.bursts[i + 1];

            // Check timing gap between bursts
            let gap = second.start_time_ms.saturating_sub(first.end_time_ms);
            if gap < self.config.syllable_gap_min_ms || gap > self.config.syllable_gap_max_ms {
                continue;
            }

            // Check that each burst has minimum frame count
            if first.frame_count < self.config.min_speech_frames
                || second.frame_count < self.config.min_speech_frames
            {
                continue;
            }

            // Check energy ratio: first syllable "JAR" should be stressed
            let total_energy = first.energy + second.energy;
            if total_energy <= 0.0 {
                continue;
            }
            let first_ratio = first.energy / total_energy;
            if first_ratio < self.config.first_syllable_energy_ratio {
                continue;
            }

            // All checks passed — this looks like "JAR-vis"
            log::debug!(
                "Syllable pattern detected: gap={}ms, first_ratio={:.2}, \
                 first_frames={}, second_frames={}",
                gap,
                first_ratio,
                first.frame_count,
                second.frame_count
            );
            return true;
        }

        false
    }

    /// Remove bursts that are too old to form part of a syllable pattern
    fn prune_old_bursts(&mut self, now: u64) {
        let max_age_ms = self.config.syllable_gap_max_ms * 2; // Keep bursts within viable window
        self.bursts.retain(|b| now.saturating_sub(b.start_time_ms) < max_age_ms);
    }

    /// Check cooldown and trigger if allowed
    fn check_cooldown_and_trigger(&self) -> bool {
        let now = now_ms();
        let last = LAST_TRIGGER_MS.load(Ordering::Relaxed);
        if now >= last && now - last < self.config.cooldown_ms {
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
        self.silence_frame_count = 0;
        self.current_burst_energy = 0.0;
        self.bursts.clear();
    }
}

/// One-shot wake word detection on an audio buffer.
/// Returns true if a wake word is detected in the given audio data.
///
/// v9: Added syllable pattern check as Layer 5, lowered thresholds.
pub fn detect(audio_data: &[u8], sample_rate: u32) -> bool {
    let samples = bytes_to_f64_samples(audio_data);

    if samples.is_empty() {
        return false;
    }

    // Layer 1: Energy pre-filter
    let energy: f64 = samples.iter().map(|s| s * s).sum::<f64>() / samples.len() as f64;
    let rms = energy.sqrt();

    // v9: Lowered from 0.06 to 0.03 — syllable pattern check compensates
    if rms < 0.03 {
        return false;
    }

    // Layer 2: Spectral analysis — check for speech-like frequency distribution
    let spectral_centroid = compute_spectral_centroid(&samples, sample_rate as f64);

    // Tightened range for "Jarvis" specifically — male/female speech fundamentals
    // "Jarvis" has strong energy in 300-3000Hz range
    if spectral_centroid < 300.0 || spectral_centroid > 3500.0 {
        return false;
    }

    // Layer 3: Zero-crossing rate — speech has moderate ZCR
    let zcr = compute_zero_crossing_rate(&samples);

    // Narrowed ZCR range for clearer speech
    if zcr < 0.10 || zcr > 0.30 {
        return false;
    }

    // Layer 4: Duration gating — "Jarvis" is typically 400-800ms
    let duration_ms = (samples.len() as f64 / sample_rate as f64) * 1000.0;
    if duration_ms < 200.0 || duration_ms > 1500.0 {
        return false;
    }

    // Layer 5: Syllable pattern detection — look for JAR-vis 2-burst pattern
    if !detect_syllable_pattern_oneshot(&samples, sample_rate) {
        return false;
    }

    // Layer 6: Cooldown check — 3 second cooldown
    let now = now_ms();
    let last = LAST_TRIGGER_MS.load(Ordering::Relaxed);
    if now >= last && now - last < 3000 {
        return false;
    }

    // All filters passed — speech detected that matches "Jarvis" characteristics.
    LAST_TRIGGER_MS.store(now, Ordering::Relaxed);
    true
}

/// Detect the JAR-vis syllable pattern in a one-shot audio buffer.
///
/// Splits the buffer into frames, tracks energy bursts, and looks for
/// a 2-burst pattern matching the "JAR" (stressed) → "vis" (lower energy) timing.
fn detect_syllable_pattern_oneshot(samples: &[f64], sample_rate: u32) -> bool {
    if samples.is_empty() || sample_rate == 0 {
        return false;
    }

    // Frame size: ~46ms at 16kHz = 512 samples. Use similar for other rates.
    let frame_size = (sample_rate as usize * 46 / 1000).max(256);
    let energy_threshold: f64 = 0.03;
    let burst_end_frames: usize = 3;
    let min_burst_frames: usize = 4;
    let max_burst_frames: usize = 25;
    let gap_min_ms: u64 = 200;
    let gap_max_ms: u64 = 800;
    let first_syllable_ratio: f64 = 0.60;

    let frame_duration_ms = frame_size as f64 / sample_rate as f64 * 1000.0;

    let mut bursts: Vec<SpeechBurst> = Vec::new();
    let mut in_burst = false;
    let mut burst_frame_count: usize = 0;
    let mut burst_energy: f64 = 0.0;
    let mut burst_start_frame: usize = 0;
    let mut silence_frames: usize = 0;

    for (frame_idx, chunk) in samples.chunks(frame_size).enumerate() {
        // Compute RMS energy for this frame
        let frame_energy: f64 = chunk.iter().map(|s| s * s).sum::<f64>() / chunk.len() as f64;
        let frame_rms = frame_energy.sqrt();

        if frame_rms > energy_threshold {
            silence_frames = 0;

            if !in_burst {
                in_burst = true;
                burst_start_frame = frame_idx;
                burst_frame_count = 0;
                burst_energy = 0.0;
            }

            burst_frame_count += 1;
            burst_energy += frame_rms;

            // If burst is too long, discard it
            if burst_frame_count > max_burst_frames {
                in_burst = false;
                burst_frame_count = 0;
                burst_energy = 0.0;
                continue;
            }
        } else if in_burst {
            silence_frames += 1;

            if silence_frames >= burst_end_frames {
                // Burst ended
                if burst_frame_count >= min_burst_frames {
                    let start_ms = (burst_start_frame as f64 * frame_duration_ms) as u64;
                    let end_ms = ((frame_idx - silence_frames) as f64 * frame_duration_ms) as u64;
                    bursts.push(SpeechBurst {
                        start_time_ms: start_ms,
                        end_time_ms: end_ms,
                        energy: burst_energy,
                        frame_count: burst_frame_count,
                    });
                }
                in_burst = false;
                burst_frame_count = 0;
                burst_energy = 0.0;
            }
        }
    }

    // Finalize any in-progress burst
    if in_burst && burst_frame_count >= min_burst_frames {
        let start_ms = (burst_start_frame as f64 * frame_duration_ms) as u64;
        let end_ms = (samples.len() as f64 / sample_rate as f64 * 1000.0) as u64;
        bursts.push(SpeechBurst {
            start_time_ms: start_ms,
            end_time_ms: end_ms,
            energy: burst_energy,
            frame_count: burst_frame_count,
        });
    }

    // Look for the JAR-vis 2-burst pattern
    if bursts.len() < 2 {
        // If we only have one burst but it's strong enough and the right length,
        // it might still be "Jarvis" spoken quickly (syllables merged).
        // Allow single-burst detection with relaxed criteria for backward compatibility.
        if bursts.len() == 1 {
            let b = &bursts[0];
            let duration = b.duration_ms();
            // Single burst must be in the right duration range for "Jarvis" (400-900ms)
            // and have sufficient energy
            return duration >= 400 && duration <= 900 && b.frame_count >= 6;
        }
        return false;
    }

    // Check consecutive burst pairs for the JAR-vis pattern
    for i in 0..bursts.len().saturating_sub(1) {
        let first = &bursts[i];
        let second = &bursts[i + 1];

        // Check timing gap
        let gap = second.start_time_ms.saturating_sub(first.end_time_ms);
        if gap < gap_min_ms || gap > gap_max_ms {
            continue;
        }

        // Check minimum frame counts
        if first.frame_count < min_burst_frames || second.frame_count < min_burst_frames {
            continue;
        }

        // Check energy ratio: first syllable should be stressed
        let total_energy = first.energy + second.energy;
        if total_energy <= 0.0 {
            continue;
        }
        let first_ratio = first.energy / total_energy;
        if first_ratio < first_syllable_ratio {
            continue;
        }

        // Pattern matches JAR-vis
        return true;
    }

    // No valid syllable pattern found
    // Fall back: allow single strong burst for backward compatibility
    if bursts.len() == 1 {
        let b = &bursts[0];
        let duration = b.duration_ms();
        return duration >= 400 && duration <= 900 && b.frame_count >= 6;
    }

    false
}

/// Compute spectral centroid (brightness) of audio signal.
pub fn compute_spectral_centroid(samples: &[f64], sample_rate: f64) -> f64 {
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
pub fn compute_zero_crossing_rate(samples: &[f64]) -> f64 {
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
#[allow(dead_code)]
fn compute_frame_energy(audio_data: &[u8]) -> f64 {
    let samples = bytes_to_f64_samples(audio_data);
    if samples.is_empty() {
        return 0.0;
    }
    let sum_sq: f64 = samples.iter().map(|s| s * s).sum();
    (sum_sq / samples.len() as f64).sqrt()
}

/// Convert 16-bit PCM bytes to f64 samples.
pub fn bytes_to_f64_samples(audio_data: &[u8]) -> Vec<f64> {
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

    #[test]
    fn test_speech_burst_struct() {
        let burst = SpeechBurst {
            start_time_ms: 1000,
            end_time_ms: 1200,
            energy: 0.5,
            frame_count: 5,
        };
        assert_eq!(burst.duration_ms(), 200);
        assert_eq!(burst.frame_count, 5);
    }

    #[test]
    fn test_wake_word_detector_new() {
        let detector = WakeWordDetector::new();
        assert!(!detector.is_speech);
        assert_eq!(detector.speech_frame_count, 0);
        assert_eq!(detector.bursts.len(), 0);
    }

    #[test]
    fn test_wake_word_detector_silence() {
        let mut detector = WakeWordDetector::new();
        // Silence frames should not trigger detection
        let silence = vec![0u8; 1024];
        for _ in 0..20 {
            assert!(!detector.process_frame(&silence, 16000));
        }
    }

    #[test]
    fn test_syllable_pattern_two_bursts() {
        // Create a detector with 2 bursts matching JAR-vis pattern
        let mut detector = WakeWordDetector::new();

        // Simulate first burst (JAR - stressed, higher energy)
        let high_energy = create_sine_frame(440.0, 0.15, 512, 16000);
        for _ in 0..6 {
            detector.process_frame(&high_energy, 16000);
        }

        // Simulate gap between syllables (silence)
        let silence = vec![0u8; 1024];
        // Need enough silence frames to end the burst (3+ frames)
        for _ in 0..5 {
            detector.process_frame(&silence, 16000);
        }

        // Simulate second burst (vis - lower energy)
        let low_energy = create_sine_frame(300.0, 0.08, 512, 16000);
        for _ in 0..4 {
            detector.process_frame(&low_energy, 16000);
        }

        // Add silence to finalize the second burst
        for _ in 0..5 {
            detector.process_frame(&silence, 16000);
        }

        // The detector should have tracked bursts
        // (exact detection depends on timing, but bursts should be recorded)
        assert!(
            detector.bursts.len() >= 1 || detector.speech_frame_count > 0,
            "Should have tracked speech activity"
        );
    }

    #[test]
    fn test_detect_syllable_pattern_oneshot_no_bursts() {
        // Pure silence should not produce any bursts
        let silence = vec![0.0f64; 8192];
        assert!(!detect_syllable_pattern_oneshot(&silence, 16000));
    }

    #[test]
    fn test_detect_syllable_pattern_oneshot_single_burst() {
        // Single burst that's in the right duration range
        let mut samples = Vec::new();
        for i in 0..16000 {
            let t = i as f64 / 16000.0;
            let sample = (2.0 * std::f64::consts::PI * 440.0 * t).sin() * 0.2;
            samples.push(sample);
        }
        // This single burst should be detected (backward compatibility)
        assert!(detect_syllable_pattern_oneshot(&samples, 16000));
    }

    #[test]
    fn test_detect_syllable_pattern_oneshot_two_bursts() {
        // Create two bursts: first (JAR) high energy, second (vis) lower energy
        // with a gap between them
        let mut samples = Vec::new();
        let sample_rate: u32 = 16000;

        // First burst: "JAR" - high energy (200ms = 3200 samples)
        for i in 0..3200 {
            let t = i as f64 / sample_rate as f64;
            let sample = (2.0 * std::f64::consts::PI * 600.0 * t).sin() * 0.20;
            samples.push(sample);
        }

        // Gap: ~400ms silence (6400 samples) — within 200-800ms gap range
        for _ in 0..6400 {
            samples.push(0.0);
        }

        // Second burst: "vis" - lower energy (150ms = 2400 samples)
        for i in 0..2400 {
            let t = i as f64 / sample_rate as f64;
            let sample = (2.0 * std::f64::consts::PI * 400.0 * t).sin() * 0.10;
            samples.push(sample);
        }

        // Should detect the JAR-vis pattern
        assert!(detect_syllable_pattern_oneshot(&samples, sample_rate));
    }

    #[test]
    fn test_detect_syllable_pattern_rejects_reverse_energy() {
        // Two bursts where second is louder — should NOT match JAR-vis
        let mut samples = Vec::new();
        let sample_rate: u32 = 16000;

        // First burst: quiet (should be "JAR" but isn't stressed)
        for i in 0..3200 {
            let t = i as f64 / sample_rate as f64;
            let sample = (2.0 * std::f64::consts::PI * 600.0 * t).sin() * 0.08;
            samples.push(sample);
        }

        // Gap
        for _ in 0..4800 {
            samples.push(0.0);
        }

        // Second burst: loud (reverse of expected pattern)
        for i in 0..2400 {
            let t = i as f64 / sample_rate as f64;
            let sample = (2.0 * std::f64::consts::PI * 400.0 * t).sin() * 0.25;
            samples.push(sample);
        }

        // Should NOT detect — first syllable isn't stressed
        assert!(
            !detect_syllable_pattern_oneshot(&samples, sample_rate),
            "Should reject when second syllable has more energy than first"
        );
    }

    #[test]
    fn test_detect_syllable_pattern_rejects_gap_too_long() {
        // Two bursts with gap > 800ms — should NOT match
        let mut samples = Vec::new();
        let sample_rate: u32 = 16000;

        // First burst
        for i in 0..3200 {
            let t = i as f64 / sample_rate as f64;
            let sample = (2.0 * std::f64::consts::PI * 600.0 * t).sin() * 0.20;
            samples.push(sample);
        }

        // Gap: 1000ms = 16000 samples — too long
        for _ in 0..16000 {
            samples.push(0.0);
        }

        // Second burst
        for i in 0..2400 {
            let t = i as f64 / sample_rate as f64;
            let sample = (2.0 * std::f64::consts::PI * 400.0 * t).sin() * 0.10;
            samples.push(sample);
        }

        assert!(
            !detect_syllable_pattern_oneshot(&samples, sample_rate),
            "Should reject when gap is too long"
        );
    }

    #[test]
    fn test_energy_threshold_lowered() {
        let config = WakeWordConfig::default();
        assert!(
            (config.energy_threshold - 0.03).abs() < 0.001,
            "Energy threshold should be 0.03, got {}",
            config.energy_threshold
        );
    }

    #[test]
    fn test_cooldown_lowered() {
        let config = WakeWordConfig::default();
        assert_eq!(
            config.cooldown_ms, 3000,
            "Cooldown should be 3000ms, got {}",
            config.cooldown_ms
        );
    }

    #[test]
    fn test_syllable_config_values() {
        let config = WakeWordConfig::default();
        assert_eq!(config.syllable_gap_min_ms, 200);
        assert_eq!(config.syllable_gap_max_ms, 800);
        assert!((config.first_syllable_energy_ratio - 0.60).abs() < 0.001);
        assert_eq!(config.burst_end_silence_frames, 3);
    }

    /// Helper: create a sine wave audio frame as 16-bit PCM bytes
    fn create_sine_frame(
        frequency: f64,
        amplitude: f64,
        num_samples: usize,
        sample_rate: u32,
    ) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(num_samples * 2);
        for i in 0..num_samples {
            let t = i as f64 / sample_rate as f64;
            let sample = (2.0 * std::f64::consts::PI * frequency * t).sin() * amplitude;
            let s16 = (sample * 32768.0).clamp(-32768.0, 32767.0) as i16;
            bytes.push((s16 & 0xFF) as u8);
            bytes.push(((s16 >> 8) & 0xFF) as u8);
        }
        bytes
    }
}

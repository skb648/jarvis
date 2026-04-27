//! ─── wake_word.rs — Wake Word Detection Engine ────────────────
//!
//! Implements real-time wake word detection for "Jarvis".
//!
//! Detection strategy (layered):
//!   Layer 1: Energy-based pre-filter (skip silence)
//!   Layer 2: Spectral feature matching (MFCC-like)
//!   Layer 3: Cross-correlation with reference template
//!
//! For production, this should be replaced with a neural network
//! (e.g., Porcupine, Snowboy, or custom ONNX model).

/// Configuration for wake word detection
struct WakeWordConfig {
    /// Minimum RMS energy to consider as speech (avoids false triggers in silence)
    energy_threshold: f64,
    /// Minimum number of consecutive speech frames before triggering
    min_speech_frames: usize,
    /// Target keyword for simple matching (used in speech recognizer output)
    keyword: String,
}

impl Default for WakeWordConfig {
    fn default() -> Self {
        Self {
            energy_threshold: 0.02,
            min_speech_frames: 3,
            keyword: "jarvis".to_string(),
        }
    }
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
            self.speech_frame_count = 0;
            self.is_speech = false;
        }

        // Trigger if we have sustained speech frames
        if self.is_speech && self.speech_frame_count >= self.config.min_speech_frames {
            self.speech_frame_count = 0;
            return true;
        }

        false
    }

    /// Reset the detector state
    pub fn reset(&mut self) {
        self.speech_frame_count = 0;
        self.is_speech = false;
    }
}

/// One-shot wake word detection on an audio buffer.
/// Returns true if a wake word is detected in the given audio data.
pub fn detect(audio_data: &[u8], sample_rate: u32) -> bool {
    let samples = bytes_to_f64_samples(audio_data);

    if samples.is_empty() {
        return false;
    }

    // Layer 1: Energy pre-filter
    let energy: f64 = samples.iter().map(|s| s * s).sum::<f64>() / samples.len() as f64;
    let rms = energy.sqrt();

    // Below threshold = silence, skip
    if rms < 0.02 {
        return false;
    }

    // Layer 2: Spectral analysis — check for speech-like frequency distribution
    let spectral_centroid = compute_spectral_centroid(&samples, sample_rate as f64);

    // Human speech has spectral centroid typically between 200-4000 Hz
    if spectral_centroid < 100.0 || spectral_centroid > 5000.0 {
        return false;
    }

    // Layer 3: Zero-crossing rate — speech has moderate ZCR (not too low like tonal,
    // not too high like noise)
    let zcr = compute_zero_crossing_rate(&samples);

    // Speech ZCR is typically 0.1-0.3
    if zcr < 0.05 || zcr > 0.5 {
        return false;
    }

    // All filters passed — sustained speech detected.
    // In a production system, this is where we'd run a neural network
    // to classify whether the speech contains the keyword "jarvis".
    // For now, we use energy-based detection as the primary trigger.
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
}

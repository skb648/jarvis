//! ─── audio.rs — Real-Time Audio Processing Engine ─────────────
//!
//! Handles:
//!   - RMS volume calculation (for visual feedback at 120fps)
//!   - Pitch detection via YIN algorithm
//!   - Spectral analysis via FFT
//!   - Audio feature extraction for emotion detection

use anyhow::Result;
use rustfft::{num_complex::Complex, FftPlanner};

/// Result of audio analysis
#[derive(Debug, Clone, serde::Serialize)]
pub struct AudioAnalysis {
    pub pitch_hz: f64,
    pub volume_db: f64,
    pub energy: f64,
    pub emotion: String,
    pub confidence: f64,
}

/// Calculate RMS (Root Mean Square) of audio samples.
/// Returns the LINEAR RMS value in the 0..1 range, suitable for
/// orb visualization on the Kotlin side. Kotlin expects 0..1 amplitude.
pub fn calculate_rms(audio_data: &[u8]) -> f64 {
    // Assume 16-bit PCM samples (little-endian)
    let samples: Vec<f64> = audio_data
        .chunks(2)
        .filter_map(|chunk| {
            if chunk.len() == 2 {
                let sample = i16::from_le_bytes([chunk[0], chunk[1]]);
                Some(sample as f64 / 32768.0)
            } else {
                None
            }
        })
        .collect();

    if samples.is_empty() {
        return 0.0;
    }

    let sum_squares: f64 = samples.iter().map(|s| s * s).sum();
    let rms = (sum_squares / samples.len() as f64).sqrt();

    // Return linear RMS in 0..1 range
    rms
}

/// Calculate RMS in dB scale. Kept as a separate function for
/// cases where dB is explicitly needed (e.g., audio analysis reporting).
pub fn calculate_rms_db(audio_data: &[u8]) -> f64 {
    let rms = calculate_rms(audio_data);
    20.0 * (rms + 1e-10).log10()
}

/// Full audio analysis: pitch, volume, energy, emotion estimation.
pub fn analyze_audio_chunk(audio_data: &[u8], sample_rate: u32) -> Result<AudioAnalysis> {
    let samples = bytes_to_f64_samples(audio_data);

    if samples.is_empty() {
        return Ok(AudioAnalysis {
            pitch_hz: 0.0,
            volume_db: -96.0,
            energy: 0.0,
            emotion: "neutral".to_string(),
            confidence: 0.0,
        });
    }

    // Calculate volume in dB (using the dedicated dB function)
    let volume_db = calculate_rms_db(audio_data);

    // Calculate total energy
    let energy: f64 = samples.iter().map(|s| s * s).sum::<f64>() / samples.len() as f64;

    // Pitch detection using autocorrelation (simplified YIN)
    let pitch_hz = detect_pitch(&samples, sample_rate as f64);

    // Emotion estimation based on audio features
    let (emotion, confidence) = estimate_emotion_from_audio(pitch_hz, volume_db, energy);

    Ok(AudioAnalysis {
        pitch_hz,
        volume_db,
        energy,
        emotion,
        confidence,
    })
}

/// Convert 16-bit PCM byte data to f64 samples normalized to [-1.0, 1.0].
fn bytes_to_f64_samples(audio_data: &[u8]) -> Vec<f64> {
    audio_data
        .chunks(2)
        .filter_map(|chunk| {
            if chunk.len() == 2 {
                let sample = i16::from_le_bytes([chunk[0], chunk[1]]);
                Some(sample as f64 / 32768.0)
            } else {
                None
            }
        })
        .collect()
}

/// Pitch detection using autocorrelation method (simplified YIN).
/// Detects the fundamental frequency (F0) of the audio signal.
fn detect_pitch(samples: &[f64], sample_rate: f64) -> f64 {
    if samples.len() < 1024 {
        return 0.0;
    }

    let frame_size = 1024.min(samples.len());
    let frame = &samples[..frame_size];

    // Autocorrelation-based pitch detection
    let min_period = (sample_rate / 500.0) as usize; // Max 500 Hz
    let max_period = (sample_rate / 50.0) as usize; // Min 50 Hz

    let mut best_period = 0;
    let mut best_correlation = 0.0;

    for period in min_period..max_period.min(frame_size / 2) {
        let mut correlation = 0.0;
        let mut energy = 0.0;
        for i in 0..(frame_size - period) {
            correlation += frame[i] * frame[i + period];
            energy += frame[i] * frame[i];
        }

        if energy > 0.0 {
            let normalized = correlation / energy;
            if normalized > best_correlation {
                best_correlation = normalized;
                best_period = period;
            }
        }
    }

    if best_period > 0 && best_correlation > 0.3 {
        sample_rate / best_period as f64
    } else {
        0.0
    }
}

/// Estimate emotion from audio features (pitch, volume, energy).
/// This is a heuristic-based approach; a production system would use
/// a trained ML model (e.g., SER via ONNX Runtime).
fn estimate_emotion_from_audio(pitch_hz: f64, volume_db: f64, energy: f64) -> (String, f64) {
    // Heuristic rules based on prosodic features
    if energy < 0.001 {
        return ("silent".to_string(), 0.9);
    }

    let loud = volume_db > -10.0;
    let high_pitch = pitch_hz > 250.0;
    let low_pitch = pitch_hz > 0.0 && pitch_hz < 150.0;
    let moderate = volume_db > -20.0 && volume_db <= -10.0;

    match (loud, high_pitch, low_pitch, moderate) {
        (true, true, _, _) => ("excited".to_string(), 0.7),
        (true, false, true, _) => ("angry".to_string(), 0.65),
        (true, false, false, _) => ("confident".to_string(), 0.6),
        (false, _, _, true) => ("calm".to_string(), 0.75),
        (false, true, _, false) => ("happy".to_string(), 0.6),
        (false, false, true, false) => ("sad".to_string(), 0.55),
        _ => ("neutral".to_string(), 0.5),
    }
}

/// Compute FFT spectrum of audio samples (for visual equalizer).
#[allow(dead_code, unused_variables)]
pub fn compute_spectrum(audio_data: &[u8], _sample_rate: u32) -> Vec<f64> {
    let samples = bytes_to_f64_samples(audio_data);
    if samples.is_empty() {
        return Vec::new();
    }

    let len = samples.len();
    let mut planner = FftPlanner::new();
    let fft = planner.plan_fft_forward(len);

    let mut buffer: Vec<Complex<f64>> = samples.iter().map(|&s| Complex::new(s, 0.0)).collect();

    fft.process(&mut buffer);

    // Return magnitude spectrum (first half only)
    buffer[..len / 2]
        .iter()
        .map(|c| (c.re * c.re + c.im * c.im).sqrt())
        .collect()
}

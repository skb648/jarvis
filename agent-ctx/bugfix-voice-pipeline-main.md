# Voice Pipeline Bugfixes - Work Record

## Summary
Fixed 5 critical bugs in the Jarvis voice pipeline in `JarvisViewModel.kt` that prevented mic input from working, transcription from succeeding, and voice output from playing.

## Bugs Fixed

### BUG 1: processQuery() blocked when state was THINKING/SPEAKING
- **Root cause**: Early `return` on lines 926-929 completely dropped new queries when brain was already THINKING or SPEAKING. This meant the voice pipeline's own `handleCommandReady()` would get blocked since it sets THINKING before calling processQuery.
- **Fix**: Removed the early return. Instead, force-reset state by releasing MediaPlayer, canceling amplitude pulse, and stopping native TTS. The query then proceeds normally.

### BUG 2: Brain state got stuck in SPEAKING/THINKING
- **Root cause 1**: `fallbackToNativeTts()` used an unreliable `delay(text.length * 80L + 1000L)` instead of a proper completion callback.
- **Root cause 2**: All state-reset logic used `_isListening` to decide between LISTENING/IDLE, but `_isListening` could be false during the THINKING→SPEAKING phase (since recording stops), causing the state to go to IDLE even in voice mode.
- **Fix**: 
  - Replaced delay hack with `UtteranceProgressListener.onDone()` callback
  - Changed all `_isListening` checks in state-reset paths to `_isVoiceMode`
  - Added amplitude pulse simulation during native TTS playback

### BUG 3: stopListening() reset state too aggressively
- **Root cause**: When `handleCommandReady` was called, there was no explicit handling to stop the recording while keeping voice mode active. The `_isListening` and brain state could be reset prematurely.
- **Fix**: In `handleCommandReady()`, explicitly stop command recording and set `_isListening = false` (mic no longer active) while preserving `_isVoiceMode = true` (voice conversation continues). After TTS completes, `restartListeningAfterTts()` re-engages the mic.

### BUG 4: sendMessage() exists
- **Status**: Already existed at line 1459 as a simple delegation to `processQuery()`. No changes needed.

### BUG 5: toggleVoiceMode() toggled based on _isListening instead of _isVoiceMode
- **Root cause**: The toggle logic checked `_isListening` instead of `_isVoiceMode`. If the user was in voice mode but currently hearing a response (not listening), the toggle would START listening again instead of STOPPING voice mode.
- **Fix**: Changed toggle to check `_isVoiceMode.value` directly.

## New Method: restartListeningAfterTts()
Added a private helper method that restarts the audio listening pipeline after TTS playback completes in voice mode. Called from:
- `MediaPlayer.OnCompletionListener`
- `UtteranceProgressListener.onDone()`
- Error paths in `handleAIQuery` and `handleCommandReady` (when in voice mode)

## Key Design Decision: _isListening vs _isVoiceMode
- `_isListening` = "mic is currently active and capturing audio" (transient)
- `_isVoiceMode` = "voice conversation mode is on" (persistent until user toggles off)

This distinction is critical: during THINKING and SPEAKING phases, `_isListening` is false (mic is off), but `_isVoiceMode` stays true, ensuring the system returns to LISTENING after TTS completes.

## File Modified
- `app/src/main/java/com/jarvis/assistant/viewmodel/JarvisViewModel.kt`

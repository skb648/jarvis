# 🤖 J.A.R.V.I.S. — Personal AI Assistant (Emotional Voice) — v2.0

> **"Just A Rather Very Intelligent System"** — built in **Jetpack Compose** for Android.
> Aapki **awaaz ko text nahi bana ke samajhta** — raw audio ke features (pitch, energy, speed, strain) se **emotion FEEL karta hai**, aur **real-human jaisi emotional voice** me reply karta hai (robotic nahi).

---

## ⚡ What makes it special

| Feature | How |
|---|---|
| 🎙️ **Audio = Input (not just text)** | Microphone se raw waveform (16 kHz PCM) analyze hoti hai — `AudioAnalyzer` pitch (autocorrelation), loudness, zero-crossing rate, high-frequency strain nikaalta hai. Emotion engine yeh **vocal body-language** dekh kar 7 emotions feel karta hai — SAD / HAPPY / ANGRY / EXCITED / CALM / STRESSED / NEUTRAL. |
| 🗣️ **Human-like emotional voice output** | Google neural TTS + `PlaybackParams` se **emotion ke hisaab se pitch/speed/volume + pause** modulate + **audio ducking** (JARVIS bolte waqt gaana khud dheema). |
| 🧠 **Hybrid AI brain** | Local **IntentEngine** (offline, <5 ms) + optional **Gemini multimodal** (raw audio + emotion + text + camera photos). |
| 🎤 **On-device wake word** | Settings me "Train wake word" dabao → apni awaaz ka **MFCC+DTW template** banta hai → "Hey Jarvis" **200ms me, 100% offline, bina Google ke** detect. |
| 📱 **Full device control** | Media, volume, brightness, torch, WiFi, Bluetooth, hotspot, ringer, alarms, timers, reminders, **daily routines**, calls, SMS, WhatsApp, apps, **screen recording**, **clipboard/paste**, **find-my-phone** (beep+flash+vibrate), weather, battery. |
| 🏠 **Smart home + IR** | Home Assistant ("lights on", "AC 24 degree"), **IR blaster** ("TV on", "TV volume up"). |
| 🖱️ **"Kuch bhi kar de" — UI automation** | Accessibility se kisi bhi app me click/scroll/paste/screenshot/lock. |
| 🧠 **Memory** | "mera naam Rohan hai", "mujhe chai pasand hai" — kabhi nahi bhoolta. |
| 📰 **Proactive info** | Daily briefing (weather+news+battery), live **cricket score**, **gold/silver rates**, top **news** (RSS), **PNR** status. |
| 📍 **Geofence reminders** | "jab ghar pahunchu to paani lena" — GPS se pata chalega. |
| 📷 **Vision mode** | "photo le aur batao" — camera se dekhta hai (Gemini key ke saath describe bhi karta hai). |
| 🔔 **Always reachable** | Quick Settings tile, home screen widget, **floating bubble**, notification reader ("kya naya aaya?"). |
| ⚡ **Fast + battery smart** | TTS prewarm (pehla reply instant), emotion smoothing, **auto-sleep** (idle hone par service quietly so jaati hai). |
| 🫀 **Mood watch** | Stressed/angry ho to JARVIS notice karke poochhta hai. |
| 🎨 **Personal** | 5 accent themes, whisper mode, voice picker, **conversation history + JSON export**. |

---

## 📁 Project structure

```
JarvisAI/
├── app/src/main/java/com/jarvis/assistant/
│   ├── audio/          # AudioAnalyzer, EmotionAnalyzer, AudioCapturer, WavUtil
│   │                   # + Mfcc, WakeWordDetector (on-device wake word)
│   ├── ai/             # IntentEngine (60+ commands), JarvisBrain, GeminiClient, SpeechToText
│   ├── tts/            # EmotionalTtsEngine (emotion voice + ducking + ElevenLabs pro)
│   ├── control/        # Media/Timer/System/Comms/Weather/UI + SmartHome, IR, Geofence,
│   │                   #   NotificationReader, FindMyPhone, ScreenRecorder
│   ├── info/           # InfoHub — news, cricket, gold, PNR, briefing
│   ├── vision/         # VisionController (camera capture)
│   ├── service/        # JarvisService (state machine), FloatingBubbleService
│   ├── tile/           # Quick Settings tile
│   ├── widget/         # Home screen widget
│   ├── core/           # SettingsStore, MemoryStore, ConversationStore, NotificationHelper
│   └── ui/             # ArcReactor, MainScreen (mood trend), SettingsScreen
└── app/src/test/       # IntentEngineTest — 60+ command unit tests
```

---

## 🚀 Quick start

1. **Android Studio** me `JarvisAI/` kholo (JDK 17, AGP 8.7+).
2. Gradle sync (pehli baar 2–4 min) — **Google Play services (location)** auto-download hoga.
3. Phone (Android 8+) connect karo → **Run ▶**.
4. **ACTIVATE** → permissions do. Settings me Accessibility + Notification access + Battery saver off.
5. Tests chalane ke liye: `./gradlew testDebugUnitTest`

---

## 🛠️ Setup walkthrough

| Kya | Kyu | Kaise |
|---|---|---|
| Core permissions | Voice/torch/calls/SMS/location | Settings → Permissions → Enable |
| **Accessibility** | Click/scroll/paste/screenshot/lock | Settings → Accessibility → JARVIS UI Control |
| **Notification access** | Media control + "kya naya aaya" | Settings → Notification access |
| **Exact alarms** | Timer/alarm exact | Alarm & reminders → allow |
| **Battery optimization off** | Background sunna | Allow |
| **Overlay** | Floating bubble | Settings → Overlay |
| Write settings (optional) | Brightness | allow |

---

## 🎤 Commands (Hinglish me bhi!)

**Core:** "Hey Jarvis" • "gaana chalao/rok/agla" • "volume 60 percent" • "5 minute ka timer" • "7 baje alarm" • "remind me to call mummy at 5 pm" • "har subah 7 baje gaana chalao" • "torch on" • "wifi on" • "hotspot on" • "brightness 80 percent" • "call mummy" • "message Rohan say kal milte hain" • "whatsapp Rohan say party kab hai" • "open youtube" • "play coldplay" • "weather batao" • "screenshot" • "click settings" • "scroll down"

**v2.0 new:** "daily briefing" • "cricket score batao" • "gold ka rate" • "aaj ki news" • "pnr 1234567890" • "screen record karo" • "recording band karo" • "copy karo ye text" • "paste kar do" • "phone kahan hai" • "lights on" / "fan off" / "ac 24 degree" • "tv on" / "tv volume up" • "jab ghar pahunchu to paani lena" • "kya naya aaya" • "ye kaunsa gaana hai" • "photo le aur bata" • "mera naam Rohan hai" • "mujhe chai pasand hai" • "tumhe kya yaad hai" • "dobara bolo" • "jaldi bolo" / "dheere bolo"

---

## 🔑 Optional PRO MODE (free keys)

- **Gemini** ([aistudio.google.com](https://aistudio.google.com)) → open-ended chat + raw-audio understanding + vision.
- **ElevenLabs** ([elevenlabs.io](https://elevenlabs.io)) → cinematic human voice (auto-fallback to free engine).
- **Home Assistant** → URL + long-lived token for smart home.
- **PNR API key** (Indian Rail API) → train status.

---

## 🧬 Emotion engine

**Input:** har 100ms chunk → RMS (loudness), pitch (autocorrelation 80–400 Hz), zero-crossing (speed), high-freq energy (strain) → 7 emotion scores + confidence. Emotion smoothing (last-9 mode) se flicker nahi hota. UI me **48-dot mood trend strip** dikhti hai.

**Output:** emotion ke hisaab se pitch/speed/volume/pause — Happy +18%/+12%, Sad −18%/−16% soft, Angry −8%/+22% loud, Excited +30%/+30%, Calm −5%/−12% soft. **Whisper mode** aur **audio ducking** built-in.

---

## 🛠️ Troubleshooting

| Problem | Fix |
|---|---|
| "Jarvis sun nahi raha" | Battery optimization off; service active (notification); auto-sleep badha do |
| Wake word na chale | **Train karo** (Settings → Wake word) ya "Mic compatibility mode" ON |
| Voice robotic | Voice picker me offline neural voice; en-IN/en-GB; rate 1.0 |
| Timer exact na baje | Exact alarms permission |
| Smart home na chale | URL+token; "Test connection" button |
| Geofence na chale | Location permission + GPS on |
| Screen record na khule | MediaProjection consent auto-aaata hai — allow karo |

---

## 🗺️ Roadmap (agla step — baaki bacha hua)

- 🔓 Offline neural TTS (Piper) — bilkul human, 100% offline
- 🏠 Matter/Tuya smart home protocols
- 📱 Wear OS companion + watch wake word
- 🤖 On-device LLM (Gemma via MediaPipe) — poori AI bina internet
- 🧩 Macro recorder — "ye kaam karke dikhao, roz karna"
- 🎙️ Voice cloning (user permission ke saath)

---

## 🧑‍💻 Tech

Kotlin • Jetpack Compose (Material 3) • Coroutines • DataStore • AudioRecord • SpeechRecognizer • TextToSpeech • MediaPlayer PlaybackParams • AlarmManager • AccessibilityService • MediaProjection • Geofencing (play-services-location) • ConsumerIr • OkHttp • Open-Meteo • Google News RSS • ESPN Cricinfo • GoldPrice • optional: Gemini 2.5 Flash, ElevenLabs, Home Assistant

---
*Built with ❤️ — "Sir, main hamesha ready hoon."*

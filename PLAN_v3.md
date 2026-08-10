# 🦾 JARVIS v3.0 — MASTER PLAN
### (Problem Diagnosis + Solution + New Features — bina code change, sirf plan)

> Aapke feedback ke 5 core problems + unke root causes + exact fix plan.
> Saath me naye premium features ka roadmap. Approve karo → implement karta hoon.

---

## PART A — Aapki 5 Problems: Root Cause + Fix

### 🔴 Problem 1: Voice sunai hi nahi deti (sirf text dikhta hai)

**Root cause (main suspect):**
1. Aapke phone pe **Google TTS engine missing/disabled** ho sakta hai → `synthesizeToFile()` fail hota hai → fallback bhi silent fail ho jata hai.
2. Code me ek bug: TTS `ready` flag kabhi complete na ho → saara output drop ho jata hai.
3. Kuch phones pe **media volume 0** ya Do-Not-Disturb mode me stream block.

**Fix (4-tier guaranteed voice engine):**
| Tier | Engine | Quality | Cost |
|---|---|---|---|
| 1 | **ElevenLabs** (pro mode) | 🏆 Almost real human | Free 10k chars/month, phir ~₹11/1000 chars |
| 2 | **OpenAI TTS** (HD voices) | 🥈 Very natural | ~₹1.2/1000 chars (bahut sasta) |
| 3 | **Google Neural TTS** + emotion modulation | 🥉 Good (free) | Free |
| 4 | **Piper VITS** (offline neural voice, download ~60MB) | 🥉 Natural, 100% offline | Free |

- **Availability check at startup:** TTS engine hai ya nahi → nahi hai to Play Store link ke saath notification + Settings me "Voice Diagnostics" screen.
- **Guaranteed playback:** AudioFocus request + STREAM fixed + volume check (agar 0 hai to auto-raise + batao).
- **Triple fallback chain:** synthesizeToFile → direct speak → beep + on-screen text (kabhi silent nahi).

### 🔴 Problem 2: Voice robotic / AI jaisi — PREMIUM chahiye

**Sach:** 100% human-like voice **bina API key ke possible nahi** — Google TTS se koi bhi modulation karo, robotic hi lagegi. Premium ke 2 honest raaste:

1. **ElevenLabs (recommended)** — "Sarah"/"Adam" jaise voices, emotion control, Hindi+English dono.
2. **OpenAI TTS (sasta)** — gpt-4o-mini-tts, natural, dynamic emotions bhi bhar sakta hai.

**Plan:**
- **Voice Studio (Settings me naya section):** har engine/voice ka **live audition** — pehle suno, phir choose karo. Har emotion (happy/sad/angry) ka demo.
- **Emotion-to-voice mapping upgrade:** intensity levels (0-100%), punctuation-based natural pauses, sentence emphasis, volume normalization (loudness equalization), aur whisper mode improved.
- **Auto-fallback:** premium key fail ho to silently free engine pe shift.

### 🔴 Problem 3: Apps khul jati hain, par andar click/scroll/search nahi hota

**Root cause:** Current accessibility code sirf "exact text match + clickable node" dhundhta hai. Reality me:
- Button ka text uske **child node** me hota hai (parent clickable hota hai) → match fail
- Text match ho bhi jaye par node clickable na ho → action fail
- Target screen par **visible nahi** (scroll karna padta hai) → kuch nahi milta
- **Search boxes** me type karke submit karna supported hi nahi tha

**Fix — SmartAutomation Engine (complete rewrite of a11y layer):**
1. **SmartClick:** BFS tree search → text/desc match → best visible candidate → agar node clickable nahi to **nearest clickable ancestor** → agar wo bhi nahi to **screen coordinates par gesture tap** (`dispatchGesture`).
2. **Scroll-To-Find:** target nahi mila to **scrollable container dhundho → scroll → dobara search** (max 8 scrolls) — "click karo woh option jo neeche chhupa hai" ab kaam karega.
3. **TypeAndSubmit:** EditText dhundho → focus → text set (`ACTION_SET_TEXT`) → search button node click karo (ya keyboard ka search key).
4. **Verify-Retry loop:** har action ke baad 1.5s wait → check result → fail ho to alternate strategy → max 3 attempts.
5. **Multi-window support** + accessibility focus tracking.

### 🔴 Problem 4: Intelligent nahi — "install karo ye app" poora kaam khud kare

**Fix — JARVIS AutoPilot (Task Agent):** 3 layers:

**Layer 1: Skills Library (offline, deterministic, turant)**
| Command | Autonomous flow (bina user guidance) |
|---|---|
| "install karo [app]" | Play Store deep intent kholega → search → pehla result → Install button click → verify |
| "search karo [query]" | Chrome/Google search intent (ek hi step me done) |
| "YouTube pe [video] chalao" | YT search intent → pehla video click |
| "WhatsApp pe [name] ko [msg]" | Intent se chat kholo → type karo → send click |
| "settings me wifi kholo" | Deep settings intent |
| "flight mode on" | Quick Settings tile automation (system level!) |
| ... | 25+ skills list banayi jayegi |

**Layer 2: LLM Planner (Gemini key ho to)** — "koi bhi complicated task" → Gemini se **JSON step-plan** generate: `[{action:"open_app",target:"playstore"}, {action:"search",text:"spotify"}, {action:"click",target:"Install"}]` → AutoPilot execute karta hai.

**Layer 3: Visual Verification (Agent loop)** — JARVIS **khud screen dekhta hai**:
MediaProjection screenshot → Gemini Vision → "kya screen pe hai?" → decide next step → **observe → plan → act → verify** loop. Ye hi asli "smart" agent hai.

- **Honesty rule:** kisi step pe atak jaye to bolta hai "Boss, is step pe aapki help chahiye — ye button dikh raha hai?" — kabhi chupchaap fail nahi.

### 🔴 Problem 5: System-level control + 3D floating bubble

**System control (bina root, bina ADB):**
- **Universal Quick-Settings automation:** shade kholo → kisi bhi tile ko content-desc se tap (Flight mode, Hotspot, DND, Auto-rotate, NFC, GPS, Cast...) — ek generic skill sab toggles ke liye.
- Deep intents: App info, Uninstall, Battery saver, Dark mode, Wallpaper, Storage, Location mode, etc.

**3D Premium Floating Bubble (Siri/Bixby style):**
- Compose-based overlay (WindowManager + ComposeView) — **animated 3D orb**: specular gradient sphere + rotating rings + emotion glow + live waveform
- **Tap** → talk (expands to mini HUD: waveform + partial text + emotion label)
- **Drag** → move, **edge snap**, long-press → menu (close/mute/pin)
- Service ke saath live sync: listening/thinking/speaking states + emotion colors

---

## PART B — Extra Improvements (v3.0 me bhi aayenge)

1. **Memory upgrade:** LLM ke saath context summary — "kal kya baat hui thi" yaad rahega
2. **Diagnostics Screen:** mic level meter, TTS status, a11y status, last error log — "chalu kyun nahi hai" khud dikh jayega
3. **Command suggestions:** screen ke hisaab se ("Is app me kya karna hai?")
4. **Auto-verify voice fix:** settings me "Voice test" button har tier ka demo sunata hai
5. **App-specific Skills pack:** WhatsApp, YouTube, Play Store, Instagram, Chrome — pehle 5 apps ke full skill flows
6. **Wake word reliability v2:** custom trained template + ASR hybrid (dono ek saath, jo pehle mile)
7. **Battery intelligence:** agent background me kaam karte waqt screen-on control

---

## PART C — Implementation Phases (order)

| Phase | Kya | Time | Priority |
|---|---|---|---|
| **P1: Voice Fix + Premium Voice** | Engine chain bug-fix, Voice Studio, ElevenLabs/OpenAI integration, diagnostics | ⭐ Most urgent — bina awaaz ke sab bekaar |
| **P2: SmartAutomation** | a11y rewrite: smart click, scroll-find, type-submit, verify-retry | ⭐ Aapka #2 problem |
| **P3: AutoPilot Agent** | Skills library (25+), LLM planner, visual verify loop | ⭐ "Smart" wala hissa |
| **P4: 3D Bubble + System Toggles** | Premium bubble, quick-settings universal control | ⭐ Premium feel |
| **P5: Polish** | Memory summary, diagnostics UI, suggestions | Normal |

**P1+P2 milakar pehle** — kyunki voice + clicks ke bina baaki sab bekaar hai.

---

## PART D — Aapko Kya Dena Hoga (recommended)

| Cheez | Zaroori? | Kahan milega |
|---|---|---|
| **ElevenLabs API key** | Recommended (premium voice) | elevenlabs.io — free 10k chars/month |
| **Gemini API key** | Recommended (smart agent + vision) | aistudio.google.com — free tier |
| **OpenAI key** (optional) | Sasta voice alternative | platform.openai.com |
| Accessibility + Overlay + Notification permissions | Zaroori | App ke Settings me (pehle se hai) |

**Bina kisi key ke bhi:** Voice free tier pe theek hoga + SmartAutomation + skills sab kaam karenge — bas voice "premium human" nahi hogi (ye honest baat hai).

---

## ❓ Mere 3 sawal (plan finalize karne ke liye)

1. **Premium voice ke liye** — kya aapke paas ElevenLabs key hai ya OpenAI? (ya dono try karna hai free tier se?)
2. **Order:** P1 (voice) + P2 (clicks) pehle, ya P3 (agent) pehle?
3. **Aapka phone Android version** kya hai (12+ best hai automation ke liye)?

> ⏳ **Note:** Ye sirf plan hai — koi code change nahi hua. Aap bolenge tab implement karta hoon. 🦾

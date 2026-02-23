# AGENT.md — Android DSP Mic → Windows (C#) → Discord (USB Tethering)

## Goal
Build a two-part project that uses an Android phone as a high-quality microphone for a Windows PC by streaming **voice-processed** audio (Android DSP: echo cancel / noise suppression / auto gain) over **USB tethering**, then routing it into Discord on Windows via a virtual audio device.

## What “Done” Means (MVP)
- You plug in phone → enable USB tethering → press Start on phone
- Run Windows receiver app (C#)
- In Discord (Windows), select **VB-CABLE “CABLE Output”** as input
- Voice is clear with minimal echo/background noise when using headphones

---

## Default Tech Choices (No user decisions needed)
### Versions
- **Windows receiver:** .NET **8** (recommended)
- **Android:** `minSdk 24` (Android 7.0), `targetSdk 34` (Android 14)

### Transport
- **UDP** over USB tethering network

### Audio
- Capture: `VOICE_COMMUNICATION` + `MODE_IN_COMMUNICATION` (enables DSP on most phones)
- Format: PCM 16-bit, **48 kHz**, mono
- Frame size: **20 ms** (960 samples @ 48kHz)

### Codec
- **Opus**
  - Windows: `Concentus` decoder
  - Android: use an Opus encoder library (pick one and stick to it for MVP):
    - Preferred: **Concentus-Opus (C# Opus port usable in Xamarin/Android via binding)**
    - Practical Android-native: **libopus via a small JNI wrapper**
  - MVP recommendation: **libopus + JNI** (more reliable performance on Android)

### Windows “Mic Device” Integration (No driver work)
- Use **VB-Audio Virtual Cable (VB-CABLE)**
- Windows receiver plays decoded audio into “CABLE Input” (render device)
- Discord listens from “CABLE Output” (recording device)

---


---

## User Setup Requirements (Document Clearly)
1) Install **VB-CABLE** on Windows  
2) Plug phone into PC via USB  
3) Enable **USB tethering** on Android  
4) Run Windows receiver  
5) In Discord (Windows): Input device = **CABLE Output**

Important: user must use **headphones** on PC to prevent speaker-to-mic echo.

---

## Android App Requirements
### Permissions
- `RECORD_AUDIO`
- `INTERNET`
- `FOREGROUND_SERVICE`
- (Optional) `ACCESS_NETWORK_STATE`

### Audio Pipeline (Strict)
- Set:
  - `AudioManager.mode = MODE_IN_COMMUNICATION`
  - `AudioRecord` source = `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
- Best-effort enable effects on the AudioRecord session:
  - `AcousticEchoCanceler`
  - `NoiseSuppressor`
  - `AutomaticGainControl`
- If an effect is unavailable, continue and log it.

### Streaming
- Encode to Opus in 20ms frames
- Send each Opus frame as one UDP datagram to Windows IP:PORT

### UI (Minimal)
- Input fields:
  - PC IP (tethered adapter IP)
  - Port (default 5555)
- Buttons:
  - Start
  - Stop
- Status:
  - “sending packets/sec”
  - DSP effects enabled/disabled info

### Foreground Service
- Audio capture must run in foreground service to avoid being killed.

---

## Windows Receiver (C#) Requirements
### Dependencies
- NuGet:
  - `NAudio`
  - `Concentus`

### Behavior
- Listens on UDP port (default 5555)
- Decodes Opus frames to PCM 16-bit 48k mono
- Plays audio to the render device containing substring: **“CABLE Input”**
- Uses buffered playback (tunable latency)

### CLI Args (MVP)
- `PhoneMicReceiver.exe <port> <deviceSubstring>`
  - default: `5555`, `"CABLE Input"`

### Logs (Must)
- Selected output device name
- UDP packets received per second
- Decode errors count
- Buffer underrun/overflow events

---

## Core Implementation Steps (Order)
1) **Windows receiver MVP** (works with simulated packets)
2) **Android capture** (PCM only, no Opus) + verify DSP is active
3) Add **Opus encode** on Android
4) Connect end-to-end with Windows receiver
5) Tune buffer/latency
6) Write setup + troubleshooting docs

---

## Testing Checklist
### Functional
- [ ] Receiver starts and selects VB-CABLE render device
- [ ] Android can start/stop streaming without crash
- [ ] Discord receives voice via CABLE Output

### Audio Quality
- [ ] With headphones: no echo loop
- [ ] Background noise is reduced vs baseline
- [ ] No robotic sound from double processing

### Stability
- [ ] Run for 15 minutes without drift/stutter
- [ ] Unplug/replug USB and recover

---

## Discord / Windows Settings Guidance (Doc)
- Windows “Enhancements” for VB-CABLE: OFF (if present)
- Discord:
  - Echo cancellation: ON
  - Noise suppression: optional (start ON, turn OFF if robotic)
  - Automatic gain control: usually OFF (Android already does AGC)

---

## Codex Rules (Do Not Violate)
- Do not build a Windows kernel driver for MVP.
- Keep Android DSP capture pipeline exactly:
  - `MODE_IN_COMMUNICATION` + `VOICE_COMMUNICATION`
- Keep codec + sample rate fixed:
  - Opus, 48k, mono, 20ms frames
- If a device feature is missing (DSP effects), log and continue.

---

## Known Risks + Mitigations
- DSP differs by phone model:
  - Mitigation: best-effort effects + log; fallback to `VOICE_RECOGNITION`
- Latency/stutter:
  - Mitigation: jitter buffer, adjustable buffer size
- Wrong IP on tethering:
  - Mitigation: doc how to find PC tether adapter IP

---

## Deliverables
- `/android` Android Studio project (Kotlin)
- `/windows` C# receiver (Console app)
- `/docs` Setup and troubleshooting
- Root `README.md` with quickstart

---

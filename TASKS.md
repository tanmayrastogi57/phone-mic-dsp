# TASKS.md — Android DSP Mic → Windows (C#) → Discord (USB Tethering)

This file lists all tasks from project start to completion (MVP → quality upgrades). Follow the order unless blocked.

---

## Phase 0 — Project Setup (Repo + Standards)

### 0.1 Create repository structure
- [x] Create folders:
  - [x] `/android/`
  - [x] `/windows/`
  - [x] `/docs/`
- [x] Add baseline docs:
  - [x] `README.md`
  - [x] `AGENT.md`
  - [x] `ARCHITECTURE.md`
  - [x] `SPEC.md`
  - [x] `requirements.md`
  - [x] `TASKS.md`
- [x] Add license placeholder:
  - [x] `LICENSE` (MIT or Apache-2.0)

**Done when:** repo tree matches plan and markdown docs exist.

### 0.2 Tooling and coding standards
- [ ] Decide/lock:
  - [x] .NET version: **.NET 8**
  - [x] Android: minSdk **24**, targetSdk **34**
- [x] Add `.gitignore` for Android + Visual Studio
- [x] Add `CONTRIBUTING.md` (minimal: build/run steps + formatting)
- [x] Add `CHANGELOG.md` (empty but present)

**Done when:** clean first commit builds skeleton projects.

---

## Phase 1 — Windows Receiver (C#) MVP (No Android yet)

> Goal: Windows receiver can play any decoded PCM into VB-CABLE reliably.

### 1.1 Create Windows solution and console app
- [x] Create `windows/PhoneMicReceiver.sln`
- [x] Create console project `windows/PhoneMicReceiver/PhoneMicReceiver.csproj`
- [x] Add NuGet dependencies:
  - [x] `NAudio`
  - [x] `Concentus`

**Done when:** `dotnet build` succeeds.

### 1.2 Implement audio output device selection
- [x] Enumerate render devices (MMDeviceEnumerator)
- [x] Select device by substring (default `"CABLE Input"`)
- [x] If not found:
  - [x] Print all render devices
  - [x] Exit with non-zero code

**Done when:** running app prints selected device or lists devices.

### 1.3 Implement WASAPI output with buffering
- [x] Use `WasapiOut` (shared mode)
- [x] Create `BufferedWaveProvider`
- [x] Add PCM write helper to push bytes into buffer
- [x] Add config knobs (CLI or config file):
  - [x] output latency ms (default 50)
  - [x] buffer length ms (default 500)

**Done when:** app runs and plays a generated test tone (optional) or accepts injected PCM.

### 1.4 Implement UDP receiver loop (no Opus yet)
- [x] Listen UDP on port (default 5555)
- [x] Receive datagrams
- [x] For test mode, interpret payload as raw PCM and push to buffer (debug-only)

**Done when:** can send dummy PCM packets and hear audio through VB-CABLE.

### 1.5 Implement Opus decode path
- [x] Replace raw PCM mode with Opus mode (default)
- [x] Use Concentus `OpusDecoder`:
  - [x] Sample rate 48000
  - [x] Channels 1
  - [x] Decode each datagram as a single Opus packet
- [x] Convert decoded `short[]` PCM to bytes and push to buffer
- [x] Handle decode errors:
  - [x] increment counter
  - [x] drop packet and continue

**Done when:** receiver accepts Opus packets and plays them to VB-CABLE.

### 1.6 Add receiver diagnostics/logging
- [x] Log startup config: port, device, latency, buffer
- [x] Packets/sec counter (rolling every 1s)
- [x] Decode error counter
- [x] Buffer overflow/underrun logs (best-effort)

**Done when:** logs are useful for debugging.

---

## Phase 2 — Android Sender MVP (Capture + Stream)

> Goal: Android app captures via DSP pipeline and streams Opus over UDP to Windows receiver.

### 2.1 Create Android Studio project
- [x] Create project under `/android/`
- [x] Kotlin, single-activity (Compose optional; keep simple)
- [x] Set:
  - [x] minSdk 24
  - [x] targetSdk 34

**Done when:** app installs and launches on device.

### 2.2 Add permissions + foreground service scaffolding
- [x] Add permissions:
  - [x] `RECORD_AUDIO`
  - [x] `INTERNET`
  - [x] `FOREGROUND_SERVICE`
- [x] Implement Foreground Service:
  - [x] persistent notification channel
  - [x] start/stop actions

**Done when:** service runs without being killed quickly.

### 2.3 Implement UI (minimal)
- [x] Fields:
  - [x] PC IP address
  - [x] UDP port (default 5555)
- [x] Buttons:
  - [x] Start Streaming
  - [x] Stop Streaming
- [x] Status area:
  - [x] “Streaming: ON/OFF”
  - [x] packets/sec or frames/sec
  - [x] DSP effects availability summary

**Done when:** user can configure target and control streaming.

### 2.4 Implement Android voice DSP capture pipeline
- [ ] Set audio mode:
  - [ ] `AudioManager.mode = MODE_IN_COMMUNICATION`
- [ ] Create AudioRecord:
  - [ ] Source: `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
  - [ ] 48kHz, mono, PCM 16-bit
- [ ] Best-effort attach effects:
  - [ ] `AcousticEchoCanceler.create(sessionId)`
  - [ ] `NoiseSuppressor.create(sessionId)`
  - [ ] `AutomaticGainControl.create(sessionId)`
- [ ] Log enabled/available status

**Done when:** capture runs and logs DSP effect status.

### 2.5 Implement UDP sender (PCM debug mode first)
- [ ] Implement UDP socket to destination IP:port
- [ ] Read PCM frames from AudioRecord and send raw PCM packets (debug-only)

**Done when:** Windows receiver in raw mode can play Android PCM (debug milestone).

### 2.6 Add Opus encoder on Android (MVP)
- [ ] Choose Opus encoding approach for MVP:
  - [ ] **libopus via JNI wrapper** (recommended for Android)
- [ ] Implement encoder settings:
  - [ ] 48kHz, mono
  - [ ] 20ms frames (960 samples)
  - [ ] bitrate 48 kbps
  - [ ] complexity 8
  - [ ] FEC ON
- [ ] Encode each 20ms PCM frame into an Opus packet
- [ ] Send one Opus packet per UDP datagram

**Done when:** Windows receiver plays decoded voice from Android.

### 2.7 Ensure clean lifecycle + stop behavior
- [ ] Stop button stops AudioRecord and encoder
- [ ] Service stops networking threads cleanly
- [ ] Handle permissions denial gracefully
- [ ] Handle invalid IP/port with clear error

**Done when:** Start/Stop works repeatedly without crashes.

---

## Phase 3 — End-to-End Discord Integration (User-Real Scenario)

### 3.1 Document and validate Windows setup
- [ ] Install VB-CABLE
- [ ] Confirm device names present:
  - [ ] CABLE Input (Playback)
  - [ ] CABLE Output (Recording)
- [ ] Confirm receiver outputs audio to CABLE Input

**Done when:** Windows can route audio into Discord via cable.

### 3.2 Discord configuration validation
- [ ] Set Discord input device:
  - [ ] `CABLE Output`
- [ ] Confirm mic test works in Discord voice settings
- [ ] Confirm call quality in a test server

**Done when:** Discord receives voice reliably.

### 3.3 Headphone requirement validation
- [ ] With headphones: no echo loop
- [ ] With speakers (optional test): document echo risk

**Done when:** README clearly states requirement and it is verified.

---

## Phase 4 — Stability, Latency, and Quality Tuning (MVP Completion)

### 4.1 Add tunable buffer/latency controls (Windows)
- [ ] Expose config:
  - [ ] `latencyMs`
  - [ ] `bufferMs`
- [ ] Provide recommended presets:
  - [ ] Low-latency (higher stutter risk)
  - [ ] Balanced (default)
  - [ ] Stable (higher latency)

**Done when:** user can tune without code changes.

### 4.2 Add stats and health reporting
- [ ] Windows: buffer fill level estimate (if possible)
- [ ] Windows: packet loss estimate (simple: gaps if later header added)
- [ ] Android: frames encoded/sec + bytes/sec

**Done when:** debugging jitter is straightforward.

### 4.3 Run endurance tests
- [ ] 15-minute call test (no stutter/drift)
- [ ] Stop/start 10 times
- [ ] USB unplug/replug behavior documented

**Done when:** MVP acceptance criteria are met.

---

## Phase 5 — Documentation Finalization (MVP Ship)

### 5.1 Write docs
- [ ] `/docs/setup-usb-tethering.md`
  - [ ] Android steps
  - [ ] Windows IP discovery (`ipconfig`)
- [ ] `/docs/discord-settings.md`
  - [ ] input device selection
  - [ ] suggested toggles
- [ ] `/docs/troubleshooting.md`
  - [ ] silent mic
  - [ ] device not found
  - [ ] robotic voice
  - [ ] stutter/latency

**Done when:** a new user can follow docs end-to-end.

### 5.2 README polish
- [ ] Quickstart verified
- [ ] Clear prerequisites
- [ ] Known limitations

**Done when:** README matches actual behavior.

### 5.3 Tag MVP release
- [ ] Update CHANGELOG
- [ ] Create v0.1.0 tag (optional)

**Done when:** MVP is complete and reproducible.

---

## Phase 6 — “Better Audio Quality” Upgrades (Post-MVP)

> Only start these after MVP is stable.

### 6.1 Improve Opus quality (highest impact)
- [ ] Add encoder config UI/flags:
  - [ ] bitrate (32–128 kbps)
  - [ ] complexity (0–10)
  - [ ] frame duration (10/20ms)
  - [ ] FEC on/off
  - [ ] expected packet loss %
- [ ] Provide recommended presets:
  - [ ] Voice-clean (default)
  - [ ] High-quality voice
  - [ ] Low-latency

**Done when:** quality improves without instability.

### 6.2 Add packet header + jitter buffer
- [ ] Define header:
  - [ ] sequence (uint32)
  - [ ] timestamp (uint32 optional)
- [ ] Implement jitter buffer on Windows:
  - [ ] reorder packets
  - [ ] drop late packets
  - [ ] target playout delay configurable

**Done when:** stutters reduce under jitter.

### 6.3 Stereo support (only if necessary)
- [ ] Detect if Android capture supports stereo
- [ ] Add stereo toggle:
  - [ ] channels = 2
  - [ ] adjust Opus config (bitrate 96–128 kbps)
- [ ] Ensure Windows output device supports stereo
- [ ] Document Discord behavior (may downmix)

**Done when:** stereo runs end-to-end without breakage.

### 6.4 >16-bit output on Windows (format upgrades)
- [ ] Add support guidance for:
  - [ ] VB-CABLE Hi-Fi or VoiceMeeter
- [ ] Allow WASAPI format negotiation:
  - [ ] output float32 internally
  - [ ] render to device preferred format
- [ ] Document how to set device format in Windows

**Done when:** system can output 24-bit if device supports it.

### 6.5 Optional security
- [ ] Add AES-GCM encryption with pre-shared key
- [ ] Key entry UI on Android + config on Windows

**Done when:** local stream is encrypted without breaking latency.

---

## Final Definition of Done (Project Complete)
- [ ] MVP complete:
  - [ ] Android DSP capture + Opus stream over USB tethering
  - [ ] Windows receiver decode + output to VB-CABLE
  - [ ] Discord uses VB-CABLE output as mic input
  - [ ] Verified stable 15+ minutes
  - [ ] Full documentation
- [ ] Post-MVP quality upgrades implemented as desired:
  - [ ] higher Opus quality presets
  - [ ] jitter buffer
  - [ ] optional stereo / 24-bit path
- [ ] Release packaged binaries or build instructions for both sides

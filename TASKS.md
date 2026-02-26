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

## Phase 1.7 — Windows Receiver Desktop App ("Real Software")

> Goal: replace command-line usage with a Windows GUI app and refactor the receiver into a reusable Core library, while keeping the Android↔Windows streaming protocol unchanged.

### 1.7.1 Refactor Windows into Core + CLI + GUI projects
- [x] Create projects:
  - [x] `windows/PhoneMicReceiver.Core` (class library)
  - [x] `windows/PhoneMicReceiver.Cli` (console wrapper; recommended for debugging)
  - [x] `windows/PhoneMicReceiver.App` (WPF GUI)
- [x] Move current receiver logic out of `Program.cs` into Core classes (network + Opus decode + audio sink)
- [x] Keep CLI behavior working by calling Core (no duplicate logic)

**Done when:** `dotnet build` succeeds and CLI can still receive Opus and play to VB-CABLE.

### 1.7.2 Define Core public API (ReceiverEngine)
- [x] Add `ReceiverConfig`:
  - [x] listen port (default 5555)
  - [x] bind address (default 0.0.0.0)
  - [x] device selection (id or substring, default "CABLE Input")
  - [x] outputLatencyMs (default 50)
  - [x] bufferLengthMs (default 500)
  - [x] optional: lock to sender IP (ignore other sources)
- [x] Add `ReceiverStats`:
  - [x] packets/sec + packetsTotal
  - [x] decodeErrors
  - [x] bufferedMs
  - [x] overflows + underruns
- [x] Implement `ReceiverEngine`:
  - [x] `StartAsync(ReceiverConfig)`
  - [x] `StopAsync()`
  - [x] events/callbacks: `OnStats`, `OnLog`, `OnStateChanged`

**Done when:** UI and CLI can control the receiver with the same API.

_Status: Implemented `ReceiverEngine` API with shared config/stats/events in Core and switched CLI control flow to `StartAsync`/`StopAsync`; WPF wiring remains scaffolded for later UI tasks._

### 1.7.3 Audio device enumeration + selection (Core)
- [x] Expose render device list (id + friendly name)
- [x] Default selection strategy:
  - [x] prefer devices matching "CABLE Input"
  - [x] fallback to default render device
- [x] Support “refresh devices” (re-enumerate)
- [x] Handle device hot-unplug gracefully (stop with error, show message, allow reselect)

**Done when:** GUI dropdown works and selection persists across restarts.

_Status: Implemented Core render-device enumeration/fallback/hot-unplug handling and wired WPF dropdown refresh + persisted device selection to AppData._

### 1.7.4 Performance / CPU spike fixes (Core)
- [x] Eliminate per-packet allocations in the receive/decode loop:
  - [x] remove `pcmBytes.ToArray()` usage
  - [x] replace `new byte[...]` per packet with reusable buffer or `ArrayPool<byte>`
- [x] Keep decoded sample buffers reused (no per-frame `short[]` allocations)
- [x] Throttle stats publishing to a fixed cadence (e.g., 4–10 updates/sec) independent of packet rate
- [ ] Optional: record GC collection counts for diagnostics

**Done when:** allocations per packet are ~0 (verified by profiler) and CPU spikes reduce.

### 1.7.5 Build WPF GUI (MVVM)
- [x] Main controls:
  - [x] Start / Stop
  - [x] Listen port
  - [x] Output device dropdown + Refresh button
  - [x] outputLatencyMs + bufferLengthMs
  - [x] Presets: Low-latency / Balanced / Stable
  - [x] Test tone button
- [x] Status + diagnostics panel:
  - [x] state (Stopped / Starting / Running / Error)
  - [x] packets/sec, decodeErrors, bufferedMs, overflows, underruns
- [x] Log panel:
  - [x] filter by level (Info/Warn/Error)
  - [x] copy logs to clipboard
  - [x] open log folder

**Done when:** user can run the receiver end-to-end without opening a terminal.

### 1.7.6 Settings persistence
- [x] Save/load settings:
  - [x] `%AppData%\phone-mic-dsp\settings.json`
- [x] Persist: last port, selected device, latency/buffer, lockSenderIp, window state
- [x] Add “Reset to defaults” button

**Done when:** app reopens with previous settings and can start immediately.

### 1.7.7 Tray + Startup behavior
- [x] Minimize-to-tray option (receiver keeps running)
- [x] Tray menu: Show/Hide, Start/Stop, Exit
- [x] “Run at startup” toggle (CurrentUser Startup or registry)

**Done when:** receiver can run in background reliably.

### 1.7.8 Packaging (“double-click software”)
- [ ] Add publish profiles:
  - [ ] portable build
  - [ ] single-file build
  - [ ] self-contained build (optional)
- [ ] Optional installer:
  - [ ] MSIX or MSI (WiX)
- [ ] Versioning:
  - [ ] show version in UI
  - [ ] update CHANGELOG for releases

**Done when:** you can distribute a build that runs on a fresh Windows machine without `dotnet run`.


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
- [x] Set audio mode:
  - [x] `AudioManager.mode = MODE_IN_COMMUNICATION`
- [x] Create AudioRecord:
  - [x] Source: `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
  - [x] 48kHz, mono, PCM 16-bit
- [x] Best-effort attach effects:
  - [x] `AcousticEchoCanceler.create(sessionId)`
  - [x] `NoiseSuppressor.create(sessionId)`
  - [x] `AutomaticGainControl.create(sessionId)`
- [x] Log enabled/available status

**Done when:** capture runs and logs DSP effect status.

### 2.5 Implement UDP sender (PCM debug mode first)
- [x] Implement UDP socket to destination IP:port
- [x] Read PCM frames from AudioRecord and send raw PCM packets (debug-only)

**Done when:** Windows receiver in raw mode can play Android PCM (debug milestone).

### 2.6 Add Opus encoder on Android (MVP)
- [x] Choose Opus encoding approach for MVP:
  - [x] **Android MediaCodec Opus encoder** (MVP implementation in service)
- [x] Implement encoder settings:
  - [x] 48kHz, mono
  - [x] 20ms frames (960 samples)
  - [x] bitrate 48 kbps
  - [x] complexity 8
  - [x] FEC ON
- [x] Encode each 20ms PCM frame into an Opus packet
- [x] Send one Opus packet per UDP datagram

**Done when:** Windows receiver plays decoded voice from Android.

### 2.7 Ensure clean lifecycle + stop behavior
- [x] Stop button stops AudioRecord and encoder
- [x] Service stops networking threads cleanly
- [x] Handle permissions denial gracefully
- [x] Handle invalid IP/port with clear error

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
- [x] Provide recommended presets:
  - [x] Low-latency (higher stutter risk)
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
- [x] `/docs/windows-desktop-app.md`
  - [x] GUI usage + field meanings
  - [x] diagnostics + logs location
- [x] `/docs/windows-packaging.md`
  - [x] publish commands (framework-dependent + self-contained + single-file)
  - [x] optional installer notes (MSIX/MSI)
- [x] `/docs/windows-performance.md`
  - [x] CPU spike causes (allocations/GC) + verification steps
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
- [x] Add encoder config UI/flags:
  - [x] bitrate (32–128 kbps)
  - [x] complexity (0–10)
  - [x] frame duration (10/20ms)
  - [x] FEC on/off
  - [x] expected packet loss %
- [x] Provide recommended presets:
  - [x] Voice-clean (default)
  - [x] High-quality voice
  - [x] Low-latency

**Done when:** quality improves without instability.

_Status: Implemented UI/config plumbing and presets; runtime quality tuning still requires device validation._

### 6.2 Add packet header + jitter buffer
- [x] Define header:
  - [x] sequence (uint32)
  - [x] timestamp (uint32 optional)
- [x] Implement jitter buffer on Windows:
  - [x] reorder packets
  - [x] drop late packets
  - [x] target playout delay configurable

**Done when:** stutters reduce under jitter.

_Status: Added 8-byte UDP header (sequence + timestamp) on Android and sequence-based jitter buffer with reordering/late-drop logic on Windows; playout delay configurable via CLI._

### 6.3 Stereo support (only if necessary)
- [x] Detect if Android capture supports stereo
- [x] Add stereo toggle:
  - [x] channels = 2
  - [x] adjust Opus config (bitrate 96–128 kbps)
- [x] Ensure Windows output device supports stereo
- [x] Document Discord behavior (may downmix)

_Status: Added Android stereo capability detection + toggle with mono fallback, stereo channel config propagation into Opus capture/encoding, and Windows receiver channel argument with stereo mix-format validation. README includes stereo run guidance and Discord downmix caveat._

**Done when:** stereo runs end-to-end without breakage.

### 6.4 >16-bit output on Windows (format upgrades)
- [x] Add support guidance for:
  - [x] VB-CABLE Hi-Fi or VoiceMeeter
- [x] Allow WASAPI format negotiation:
  - [x] output float32 internally
  - [x] render to device preferred format
- [x] Document how to set device format in Windows

**Done when:** system can output 24-bit if device supports it.

_Status: Windows receiver now decodes to float32 internally, negotiates to the selected WASAPI mix format (with MediaFoundation resampler when needed), and docs now include VB-CABLE Hi-Fi / VoiceMeeter plus Windows device format setup steps._

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

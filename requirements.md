# requirements.md — Project Requirements

## 1) User Requirements
- UR-1: Use Android phone as microphone for Windows Discord.
- UR-2: Audio should be clean (low echo, low background noise).
- UR-3: Use **USB** connection (no Wi-Fi dependency for primary path).
- UR-4: Use **headphones** on PC (user commitment) to prevent echo loop.
- UR-5: Prefer higher “quality” than mono/16-bit when feasible, without breaking Discord compatibility.

## 2) Functional Requirements
### Android
- FR-1: Provide UI for PC IP + port; Start/Stop streaming.
- FR-2: Capture audio via Android voice communication pipeline:
  - `AudioManager.MODE_IN_COMMUNICATION`
  - `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
- FR-3: Best-effort enable AEC/NS/AGC effects and log results.
- FR-4: Encode to Opus and stream via UDP over USB tethering.
- FR-5: Run as Foreground Service for stability.

### Windows (C#)
- FR-6: Receive UDP stream on configurable port.
- FR-7: Decode Opus to PCM reliably (drop bad packets, continue).
- FR-8: Output decoded audio to VB-CABLE (“CABLE Input”) via WASAPI.
- FR-9: Provide logs and basic diagnostics (device list fallback).

### Discord
- FR-10: Work by selecting VB-CABLE “CABLE Output” as Discord input.

## 3) Non-Functional Requirements
- NFR-1: Stability: continuous operation for 15+ minutes without drift/stutter.
- NFR-2: Latency: 80–200ms typical end-to-end acceptable.
- NFR-3: Minimal dependencies:
  - Windows: NAudio + Concentus
  - Android: stable Opus encoder (libopus JNI recommended)
- NFR-4: Maintainability: clean module separation (capture/encode/network).
- NFR-5: Compatibility: Windows 10/11; Android 7+.

## 4) Constraints
- C-1: No Windows kernel driver for MVP.
- C-2: No Bluetooth voice profile support required (optional future).
- C-3: USB tethering must be the primary transport mechanism.
- C-4: Discord voice pipeline may downmix/normalize; do not assume end-to-end “studio” audio.

## 5) Audio/Codec Requirements
- AR-1: Sample rate default: 48kHz.
- AR-2: Frame duration: 20ms default.
- AR-3: Opus VOIP mode.
- AR-4: Use FEC ON for resilience.
- AR-5: Provide configuration for bitrate and (optionally) stereo mode, but ensure a working mono voice baseline.

## 6) Documentation Requirements
- DR-1: README quickstart.
- DR-2: USB tethering setup doc and IP discovery steps.
- DR-3: Discord settings doc.
- DR-4: Troubleshooting guide (echo, robotic voice, no audio, wrong device).

## 7) Acceptance Test Requirements
- AT-1: End-to-end: Android → Windows receiver → VB-CABLE → Discord input works.
- AT-2: Compare baseline: significantly improved echo/noise vs generic phone-as-mic apps.
- AT-3: Headphones on PC: no echo loop.
- AT-4: 15-minute stability test passes.

## 8) Future Enhancements (Not Required for MVP)
- FE-1: Datagram header with sequence numbers + jitter buffer
- FE-2: Auto-discovery of receiver over tethering subnet
- FE-3: Optional encryption (AES-GCM)
- FE-4: Windows GUI
- FE-5: Optional stereo / 24-bit support via higher-grade virtual devices (VoiceMeeter / VB-CABLE Hi-Fi)

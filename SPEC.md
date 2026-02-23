# SPEC.md — Android DSP Mic → Windows (C#) → Discord (USB Tethering)

## 1. Purpose
Provide a reliable “phone as microphone” solution for Discord on Windows by capturing **post-DSP** voice audio on Android (AEC/NS/AGC), streaming it over **USB tethering**, and routing it into Discord via a virtual audio device (VB-CABLE).

## 2. Scope
### In scope (MVP)
- Android app that captures audio using Android voice communication pipeline (DSP-enabled) and streams to Windows.
- Windows C# receiver that decodes the stream and outputs audio to VB-CABLE.
- Documentation for setup (USB tethering + Discord input selection).

### Out of scope (MVP)
- Windows kernel virtual microphone driver
- Bluetooth transport
- Cloud relay / internet streaming
- Recording to file (optional later)
- Encryption (optional later)

## 3. Definitions
- **DSP**: device/OS audio processing including echo cancellation (AEC), noise suppression (NS), automatic gain control (AGC).
- **USB tethering**: Android provides a network interface to the PC over USB.
- **VB-CABLE**: virtual audio cable used to route playback into a “microphone” device for Discord.

## 4. System Requirements (Functional)

### 4.1 Android Sender — Functional Requirements
**FR-A1**: Must provide Start/Stop streaming controls.  
**FR-A2**: Must allow user to configure:
- Destination IP (PC tether adapter IPv4)
- UDP port (default 5555)

**FR-A3**: Must capture microphone audio using:
- `AudioManager.MODE_IN_COMMUNICATION`
- `AudioRecord` with source `MediaRecorder.AudioSource.VOICE_COMMUNICATION`

**FR-A4**: Must attempt to enable (best-effort) the following effects on the AudioRecord session:
- `AcousticEchoCanceler`
- `NoiseSuppressor`
- `AutomaticGainControl`

If any effect is unavailable, continue without failing.

**FR-A5**: Must encode captured PCM to Opus and transmit using UDP.

**FR-A6**: Must run capture/streaming inside a Foreground Service to prevent OS killing the stream.

**FR-A7**: Must surface runtime status:
- streaming active/inactive
- packets per second or bytes/sec
- DSP effects available/enabled states (at least in logs)

### 4.2 Windows Receiver — Functional Requirements
**FR-W1**: Must listen on a UDP port (default 5555).  
**FR-W2**: Must decode Opus frames into PCM.  
**FR-W3**: Must play decoded PCM to a selected render device by substring match:
- default substring: `"CABLE Input"`

If device not found, print list of available render devices and exit.

**FR-W4**: Must buffer audio to tolerate jitter and avoid stutter using a configurable buffer.

**FR-W5**: Must provide visible logs:
- selected output device
- packets/sec
- decode errors
- buffer over/underrun events

### 4.3 Discord Integration — Functional Requirements
**FR-D1**: Discord input device must be configured to `CABLE Output (VB-Audio Virtual Cable)`.  
**FR-D2**: System must be usable with headphones to prevent speaker-to-mic echo loops.

## 5. Audio Specifications (MVP Defaults)
> Note: User requested “better than mono/16-bit”. MVP defaults stay voice-compatible; higher-quality options are defined in Section 6.

### 5.1 Default Stream Format (MVP)
- Sample rate: **48000 Hz**
- Channels: **1 (mono)**
- PCM bit depth: **16-bit signed**
- Frame duration: **20 ms**
- PCM samples per frame: **960** (48k * 0.02)
- Codec: **Opus**
- Opus application: **VOIP**
- Transport: **UDP**

### 5.2 Opus Encoder Defaults
- Bitrate: **48 kbps** (mono voice clean baseline)
- Complexity: **8**
- In-band FEC: **ON**
- Expected packet loss: **1–2%** (configurable)
- VBR: **ON** (preferred)

### 5.3 Windows Playback Format
- Output uses WASAPI Shared Mode to VB-CABLE render device.
- Device format is whatever VB-CABLE exposes; receiver outputs PCM accordingly.

## 6. Higher-Quality Options (Configurable Targets)
These are optional modes; implement after MVP stability.

### 6.1 Stereo Mode (if supported end-to-end)
- Channels: **2**
- Opus bitrate: **96–128 kbps**
- Note: Many Android devices still provide mono mic; stereo is not guaranteed.

### 6.2 24-bit Output (Windows)
- Use VB-CABLE Hi-Fi or VoiceMeeter if VB-CABLE doesn’t expose 24-bit.
- Windows receiver may process internally as float and render to device format.
- Note: This does not guarantee Discord uses 24-bit internally.

## 7. Network Specifications
### 7.1 Transport
- UDP datagrams over USB tethering network.

### 7.2 Datagram Payload (MVP)
- Each UDP datagram contains exactly **one Opus packet** (no header).
- No sequencing/reordering in MVP.

### 7.3 Packet Size Guidance
- Opus voice packets typically ~20–200 bytes depending on bitrate/VBR.

### 7.4 Future Datagram Header (not MVP)
If implementing jitter buffer + reordering:
- Add a small header:
  - uint32 sequence
  - uint32 timestamp (optional)
  - payload = Opus packet

## 8. Latency and Buffering Requirements
**LR-1**: End-to-end latency target: **80–200 ms** acceptable for Discord voice.  
**LR-2**: Receiver must allow tuning:
- WASAPI latency (e.g., 30–80ms)
- buffer size (e.g., 200–800ms)

**LR-3**: Must avoid unbounded buffer growth (cap buffer; discard oldest or overflow policy).

## 9. Error Handling Requirements
- Android:
  - If network send fails repeatedly, show status and keep trying until stopped.
  - If AudioRecord init fails, show actionable error.
- Windows:
  - On decode error: drop packet and continue.
  - On device missing: list devices and exit.
  - On buffer overflow: discard new samples (or oldest) and log.

## 10. Observability Requirements
### Android logs (minimum)
- Audio source used
- Mode set
- Effects enabled availability
- Destination IP/port
- packets/sec or frames/sec

### Windows logs (minimum)
- output device name
- listening port
- packets/sec
- decode error count
- buffer fill level events

## 11. Security (MVP)
- No encryption/authentication.
- Assumes local USB tethering network.

Future: optional AES-GCM with pre-shared key.

## 12. Acceptance Criteria (MVP)
MVP is accepted when:
1. With PC headphones, Discord on Windows receives clear voice with significantly reduced echo and background noise compared to generic phone-as-mic apps.
2. Android capture uses `MODE_IN_COMMUNICATION` + `VOICE_COMMUNICATION` and logs DSP availability.
3. Windows receiver decodes and outputs to VB-CABLE reliably for 15 minutes without stutter/drift.
4. Setup steps work for a new user in under 10 minutes.


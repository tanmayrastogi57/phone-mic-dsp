# ARCHITECTURE.md — Android DSP Mic → Windows (C#) → Discord (USB Tethering)

## Overview
This project turns an Android phone into a high-quality microphone for a Windows PC by:
1) Capturing audio through Android’s **voice communication** pipeline (DSP: AEC/NS/AGC),
2) Encoding and streaming it over **USB tethering** to Windows,
3) Decoding on Windows (C#) and routing into Discord using a **virtual audio device**.

The MVP avoids building a Windows kernel “virtual microphone” driver by using **VB-Audio Virtual Cable (VB-CABLE)** (or optionally VoiceMeeter / VB-CABLE Hi-Fi).

---

## System Components

### 1) Android Sender App
**Responsibilities**
- Configure Android audio stack for voice DSP:
  - `AudioManager.MODE_IN_COMMUNICATION`
  - `AudioRecord` source: `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
- Best-effort enable voice effects when supported:
  - `AcousticEchoCanceler` (AEC)
  - `NoiseSuppressor` (NS)
  - `AutomaticGainControl` (AGC)
- Capture PCM frames and encode to **Opus**
- Stream Opus frames via UDP over USB tethering network

**Key Modules**
- `AudioCapture`
  - AudioRecord initialization
  - Frame slicing (10ms/20ms)
  - Effect enablement + logging
- `OpusEncoder`
  - Encodes PCM → Opus packets
  - Configurable bitrate/complexity/FEC
- `UdpStreamer`
  - Sends one Opus packet per UDP datagram
  - Reconnect / target update handling
- `ForegroundService`
  - Keeps capture alive in background
- `UI`
  - PC IP + Port
  - Start/Stop
  - Status (packets/sec, DSP enabled)

---

### 2) Windows Receiver (C#)
**Responsibilities**
- Listen on UDP port
- Decode Opus packets → PCM
- Output decoded audio to a Windows render device:
  - **VB-CABLE “CABLE Input”** (recommended MVP)
- Provide stable playback via buffering and adjustable latency

**Key Modules**
- `UdpReceiver`
  - Datagram receive loop
  - Packet stats
- `OpusDecoder` (Concentus)
  - Opus → PCM conversion
- `AudioOut` (NAudio / WASAPI)
  - Playback device selection by substring
  - Buffered provider (jitter tolerance)
- `Config`
  - Port, device substring, latency, buffer size

---

### 3) Virtual Audio Device Layer (Windows)
**Purpose**
- Present a selectable audio endpoint that Discord can use as **microphone input** without driver development.

**MVP Device**
- VB-Audio Virtual Cable
  - Playback endpoint: **CABLE Input**
  - Recording endpoint: **CABLE Output**
- Windows receiver plays into “CABLE Input”
- Discord selects “CABLE Output” as input

**Optional Upgrade**
- VB-CABLE Hi-Fi / VoiceMeeter for higher format flexibility (24-bit etc.)

---

### 4) Discord Desktop App (Windows)
**Responsibilities**
- Consume microphone input from the virtual device
- Apply optional Discord-side voice processing (echo cancellation / suppression)
- Transmit voice to call participants

---

## Data Flow

### Audio + Network Pipeline (MVP)

[Android Mic]
↓
[Android Voice DSP]
(MODE_IN_COMMUNICATION + VOICE_COMMUNICATION)
↓ PCM (48kHz, mono, 16-bit in MVP)
[Opus Encoder]
↓ Opus packets (20ms frames)
[UDP over USB tethering]
↓
[Windows UDP Receiver]
↓
[Opus Decoder]
↓ PCM
[WASAPI Output → VB-CABLE “CABLE Input”】【playback endpoint】
↓ virtual cable internal routing
[VB-CABLE “CABLE Output”】【recording endpoint】
↓
[Discord Input Device]


---

## Why USB Tethering
- Provides a stable, low-interference network link compared to Wi-Fi
- Avoids Bluetooth voice profile limitations (bandwidth/codec quality)
- Works without special drivers on most Android devices

---

## Audio Format Strategy

### MVP Defaults (Optimized for Voice + Compatibility)
- Sample rate: **48,000 Hz**
- Channels: **1 (mono)**  
- PCM: **16-bit**
- Frame: **20ms** (960 samples at 48k mono)
- Codec: **Opus** (VoIP/voice)

**Rationale**
- Discord voice pipeline is optimized for speech; mono is typical.
- 48kHz matches Opus native and many Windows endpoints.
- 20ms is a standard balance of latency and overhead.

### Higher-Quality Options (When Needed)
If the goal is “better than mono/16-bit”, the architecture supports:
- Channels: **2 (stereo)** (only if both capture and device support it)
- Bit depth: **24-bit** (typically only meaningful on the Windows output device)
- Higher Opus bitrate + complexity + FEC

**Important Constraints**
- Many phones provide mono mic even if stereo requested.
- Many virtual cable endpoints are limited; use VB-CABLE Hi-Fi / VoiceMeeter for 24-bit formats.
- Discord may downmix/normalize voice input.

---

## Packet Format (MVP)
- One Opus frame per UDP datagram.
- No sequence numbers in MVP.

**Implications**
- Simple implementation
- Opus handles minor loss, but no reordering/advanced jitter control

**Planned Improvements**
- Add sequence number + timestamp to datagrams
- Jitter buffer keyed by sequence for smoother playback
- Optional packet loss estimation to tune Opus FEC

---

## Buffering and Latency
### Where latency comes from
1) Android capture buffering (AudioRecord internal)
2) Opus frame size (10ms/20ms)
3) Network transit (USB tethering)
4) Windows decode + audio buffering (BufferedWaveProvider + WASAPI)

### MVP approach
- Keep Opus frame at **20ms**
- Windows uses `BufferedWaveProvider` to avoid underruns
- Provide tunable:
  - WASAPI output latency (e.g., 30–80ms)
  - BufferedWaveProvider buffer length (e.g., 200–800ms)

---

## Device Selection and Routing (Windows)
### Output device selection
Windows receiver selects a render device by substring match:
- default: `"CABLE Input"`

If not found, receiver prints all render devices and exits.

### Discord input selection
Discord input device must be:
- `"CABLE Output (VB-Audio Virtual Cable)"`

---

## Processing/Enhancement Policy (Avoid Double Processing)
Recommended policy:
- **Android DSP ON** (AEC/NS/AGC via voice pipeline)
- **Windows enhancements OFF** (if available on cable device)
- **Discord processing minimal**:
  - Echo cancellation: ON (safe)
  - Noise suppression: optional (turn off if robotic)
  - Automatic gain control: often OFF if Android AGC is active

---

## Observability (Logging)
### Android should log
- Audio source in use (`VOICE_COMMUNICATION`)
- Mode (`MODE_IN_COMMUNICATION`)
- Effects availability and enabled status (AEC/NS/AGC)
- Frames/sec and bytes/sec sent
- Destination IP/port

### Windows should log
- Selected render device name
- Packets/sec received
- Decode errors count
- Buffer fill level / underrun events
- Output latency settings

---

## Failure Modes and Handling

### Wrong PC IP (tethering adapter mismatch)
- Symptom: receiver shows 0 packets
- Mitigation: docs include finding tether adapter IPv4 using `ipconfig`

### VB-CABLE not installed / wrong device name
- Symptom: receiver cannot find “CABLE Input”
- Mitigation: list all render devices and allow substring override

### Robotic voice / pumping
- Symptom: over-aggressive suppression/AGC
- Mitigation: reduce stacked processing; disable Discord NS/AGC first

### Stutter
- Symptom: periodic underruns due to jitter/CPU
- Mitigation: increase buffer slightly; reduce logging overhead; consider sequence+jitter buffer in next version

---

## Security Notes (MVP)
- Audio is streamed over a local USB tethered network.
- No encryption in MVP.
- Future option: add AES-GCM encryption + pre-shared key if required.

---

## Extensibility
This architecture supports:
- Better packet framing (sequence numbers, RTP)
- Stronger jitter buffering
- Automatic discovery (broadcast on tethered subnet)
- Optional Wi-Fi fallback transport
- Optional encryption/authentication
- Optional virtual mic driver (deferred; not MVP)

---

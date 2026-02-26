# Windows Desktop App (WPF) – Phone Mic DSP

## Objective

Convert the current console-based Windows receiver into a proper desktop application with:

- Start / Stop controls
- Output device selection
- Port configuration
- Latency & buffer controls
- Live diagnostics
- Logging
- Tray mode
- Persistent settings

Target framework: **.NET 8 (net8.0-windows)**

---

## Architecture

Split Windows side into 3 projects:
/windows
/PhoneMicReceiver.Core (Class Library)
/PhoneMicReceiver.Cli (Optional Console Wrapper)
/PhoneMicReceiver.App (WPF GUI)

---

## Core Library Responsibilities

### ReceiverEngine
- Start(config)
- Stop()
- Events:
  - OnStatsUpdated
  - OnLog
  - OnDeviceListChanged

### UdpOpusReceiver
- UDP receive loop
- Opus decode (Concentus)
- Frame validation

### WasapiAudioSink
- Output device enumeration
- BufferedWaveProvider
- WasapiOut playback
- Buffer health metrics

### SettingsStore
- Save/load JSON
- Path:%AppData%\phone-mic-dsp\settings.json

---

## GUI Features

### Main Controls
- Listen Port (default 5555)
- Output Device Dropdown
- Latency (ms)
- Buffer (ms)
- Start / Stop button

### Diagnostics Panel
- Packets/sec
- Decode Errors
- Buffered ms
- Overflows
- Underruns

### Presets
- Low Latency
- Balanced
- Stable

---

## Tray Mode

- Minimize to tray
- Show / Hide
- Exit
- Optional: Run at startup

---

## Technical Constraints

- Sample rate: 48000 Hz
- Mono
- 16-bit PCM
- 20ms frames
- Default output device substring: "CABLE Input"
- One UDP packet = One Opus frame
- No protocol changes

---

## Build
dotnet build
dotnet run --project windows/PhoneMicReceiver.App
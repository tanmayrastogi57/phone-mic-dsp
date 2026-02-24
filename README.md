# Android DSP Phone Mic → Windows (C#) → Discord (USB Tethering)

Use an Android phone as a high-quality microphone for a Windows PC by capturing **voice-processed** audio on Android (DSP: echo cancellation / noise suppression / auto gain), streaming it over **USB tethering**, and routing it into Discord on Windows via **VB-Audio Virtual Cable (VB-CABLE)**.

## Why this exists
Discord on Android sounds clean because Android uses a voice-communication audio pipeline with DSP. Most “phone as mic” apps bypass that pipeline. This project keeps the Android DSP benefits, then forwards the processed voice to Windows.

---

## How it works (MVP)
**Pipeline:**
1. Android captures mic audio using `VOICE_COMMUNICATION` + `MODE_IN_COMMUNICATION`
2. Android encodes audio to **Opus** (48kHz, mono, 20ms frames)
3. Android sends Opus frames via **UDP** over **USB tethering**
4. Windows receiver (C#) decodes Opus and plays it into **VB-CABLE** (“CABLE Input” render device)
5. Discord uses **“CABLE Output”** as the microphone input

**Important:** Use **headphones** on the PC. Using speakers can reintroduce echo because the phone mic will pick up speaker output.

---

## Requirements

### Windows
- Windows 10/11
- **.NET 8 SDK** (recommended)
- **VB-Audio Virtual Cable (VB-CABLE)** installed
- Discord Desktop app

### Android
- Android 7.0+ (minSdk 24, targetSdk 34)
- USB cable
- **USB tethering** support

---

## Quickstart (End-to-End)

### 1) Install VB-CABLE (Windows)
Install VB-Audio Virtual Cable (VB-CABLE).
After install, you should see:
- Playback device: **CABLE Input (VB-Audio Virtual Cable)**
- Recording device: **CABLE Output (VB-Audio Virtual Cable)**

### 2) Enable USB tethering (Android)
1. Plug your Android phone into the PC via USB
2. On phone: **Settings → Network & Internet → Hotspot & tethering → USB tethering (ON)**  
   (Path varies by device)

This creates a new network adapter on Windows.

### 3) Find the PC IP address (on the USB tether adapter)
On Windows PowerShell:
```powershell
ipconfig
```

## Selecting a microphone (Android)
- On the Android app main screen, use the **Microphone** dropdown to choose from available input devices discovered by Android (`AudioManager.GET_DEVICES_INPUTS`).
- Tap **Refresh Microphones** after plugging in or removing wired/Bluetooth/USB audio devices.
- You can switch microphones while streaming; Android will apply the preferred device to the active recorder without changing Opus, UDP, or Windows receiver behavior.
- If routing is not honored by the phone/OS, the app shows a non-blocking warning and continues with the system-default path.
- On older/limited Android builds where enumeration/routing support is unavailable, the app falls back to **Default microphone (system)**.

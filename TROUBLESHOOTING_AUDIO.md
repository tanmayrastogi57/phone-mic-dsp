# TROUBLESHOOTING_AUDIO.md
Android DSP Phone Mic → Windows Receiver → Discord

This guide provides a **structured debugging checklist** for diagnosing “no audio” issues when both Android and Windows applications are running but no sound is heard.

Follow steps in order. Do not skip layers.

---

# Layer 1 — Windows Receiver Verification

## 1.1 Confirm Receiver Startup Logs

When launching the Windows receiver, verify:

- Selected output device is printed.
- Listening port is correct (default: 5555).
- No immediate crash.
- Packets/sec counter appears.
- Decode error counter appears.

If output device is not found:
- Ensure substring `"CABLE Input"` matches exactly.
- Print and review all render devices.

---

## 1.2 Check Packet Reception

Observe:

- `Packets/sec` value.

### Case A: `Packets/sec = 0`
→ No UDP packets arriving.
Proceed to **Layer 3 — Network Check**.

### Case B: `Packets/sec > 0`
→ Packets arriving. Continue below.

---

## 1.3 Check Decode Errors

If:

- `DecodeErrors` increasing rapidly

Then:
- Confirm decoder initialized as:
  - Sample rate: 48000
  - Channels: 1
- Confirm Android is sending:
  - 20ms Opus frames
  - Mono
  - 48kHz

Mismatch here results in silence.

---

## 1.4 Verify Audio Buffer Operation

Check:

- Buffer overflow logs
- Buffer underrun logs

If constant underruns:
- Increase buffer size (e.g., 500ms → 800ms).
- Increase WASAPI latency slightly.

---

# Layer 2 — Windows Audio Routing Verification

## 2.1 Confirm Playback Device Activity

Open:

Control Panel → Sound → Playback
Select **CABLE Input**

Speak into phone.

Check:
- Does green level meter move?

### If NO:
Windows receiver is not pushing PCM correctly.
Return to Layer 1.

### If YES:
Audio is entering VB-CABLE.
Continue.

---

## 2.2 Confirm Recording Device Activity

Open:

Control Panel → Sound → Recording
Select **CABLE Output**

Check:
- Does green level meter move?

If NO:
- VB-CABLE installation issue.
- Restart Windows.
- Reinstall VB-CABLE if necessary.

---

## 2.3 Confirm Sample Format

Right-click **CABLE Input → Properties → Advanced**

Ensure format is:

- 1 channel
- 16 bit
- 48000 Hz

If not:
- Set to 48000 Hz.
- Disable enhancements.

---

# Layer 3 — Network (USB Tethering) Verification

## 3.1 Confirm USB Tethering Enabled

On Android:
- Settings → Network → Hotspot & Tethering → USB Tethering ON

---

## 3.2 Confirm Correct Windows IP

On Windows:

```powershell
ipconfig
```

Find the USB tether adapter (often `Ethernet`/`Remote NDIS`/`USB Ethernet` style name).
Use that IPv4 address in the Android app **PC IP** field.

If unsure, disconnect/reconnect USB and rerun `ipconfig` to see which adapter changes.

---

## 3.3 Validate Port Consistency

Verify both sides use the same UDP port:

- Android sender UI port (default `5555`)
- Windows receiver listen port (default `5555`)

Any mismatch means packets will never arrive.

---

## 3.4 Confirm Windows Firewall Rule

If packets remain at zero, temporarily test by allowing inbound UDP on the receiver port:

```powershell
netsh advfirewall firewall add rule name="PhoneMicReceiver UDP 5555" dir=in action=allow protocol=UDP localport=5555
```

Then retest streaming.

If this fixes the issue, keep a scoped inbound rule for your chosen port.

---

# Layer 4 — Android Capture & DSP Verification

## 4.1 Confirm App Permissions

In Android app settings, ensure:

- Microphone permission = Allowed
- Notifications enabled (foreground service visible)

If microphone permission was denied before, clear and re-grant it.

---

## 4.2 Confirm DSP Capture Mode

The app must run with:

- `AudioManager.MODE_IN_COMMUNICATION`
- `MediaRecorder.AudioSource.VOICE_COMMUNICATION`

If unavailable on a specific device model, fallback to `VOICE_RECOGNITION` is acceptable, but log it clearly.

---

## 4.3 Confirm Effects Status

Check app status/log output for:

- AcousticEchoCanceler enabled/unsupported
- NoiseSuppressor enabled/unsupported
- AutomaticGainControl enabled/unsupported

If unsupported, continue testing (this should not produce total silence).

---

# Layer 5 — Discord Verification

## 5.1 Correct Input Device

In Discord settings → Voice & Video:

- Input Device = **CABLE Output (VB-Audio Virtual Cable)**

Do **not** select your physical microphone.

---

## 5.2 Discord Processing Settings

Start with:

- Echo cancellation: ON
- Noise suppression: ON (if robotic, try OFF)
- Automatic gain control: OFF (Android already applies AGC)

---

# Quick Decision Tree

1. `Packets/sec = 0` on Windows receiver
   - Network/IP/port/firewall issue → complete Layer 3
2. `Packets/sec > 0`, `DecodeErrors` rising
   - Opus format mismatch → verify 48k/mono/20ms on both sides
3. `Packets/sec > 0`, decode ok, but no meter on CABLE Input
   - PCM push/output device issue in receiver
4. CABLE Input meter moves, CABLE Output does not
   - VB-CABLE routing/install problem
5. CABLE Output meter moves, Discord silent
   - Discord device/settings mismatch

---

# Useful Commands (Windows)

```powershell
# Show IP info
ipconfig

# See if receiver process is listening on UDP 5555
netstat -ano | findstr :5555

# Add firewall allow rule for UDP 5555 (if needed)
netsh advfirewall firewall add rule name="PhoneMicReceiver UDP 5555" dir=in action=allow protocol=UDP localport=5555
```

---

# Last Resort Logging Bundle

When asking for help, capture and share:

- Receiver startup logs (device selection, port, latency, buffer)
- 10–20s runtime stats (`Packets/sec`, `DecodeErrors`, over/underruns)
- Screenshot of Windows Sound meters for CABLE Input + CABLE Output
- Discord input device screenshot
- Android app status (DSP effects + packets/sec)

This set is usually enough to pinpoint the fault quickly.

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

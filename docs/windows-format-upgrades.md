# Windows Format Upgrades (>16-bit Output)

Task 6.4 adds a higher-precision Windows render path so decoded Opus audio is handled as **float32 internally** and then negotiated to the selected device's preferred WASAPI mix format.

## What changed in the receiver

- Internal decode/output buffer now uses **48 kHz IEEE float32**.
- Receiver now negotiates the playback endpoint format by reading `MMDevice.AudioClient.MixFormat`.
- If the device mix format differs from internal float32, the app inserts `MediaFoundationResampler` and renders in the device-preferred format.
- Startup logs now print:
  - selected output device
  - device preferred mix format
  - internal decode format
  - render path (direct vs resampler)

This enables output paths like 24-bit/32-bit render when the endpoint and Windows mixer support them.

## Recommended virtual-device options

### Option A: VB-CABLE Hi-Fi (for 24-bit / 32-bit float routes)
Use VB-Audio's Hi-Fi capable virtual endpoints when available.

### Option B: VoiceMeeter (Banana/Potato)
Use VoiceMeeter virtual inputs as the receiver output target, then route to Discord/monitoring outputs from VoiceMeeter.

## How to set preferred device format in Windows

1. Open **Control Panel → Sound** (or `mmsys.cpl`).
2. Go to **Playback** tab.
3. Select your target device (for example `CABLE Input` / VoiceMeeter input) and click **Properties**.
4. Open the **Advanced** tab.
5. Under **Default Format**, choose a higher format when available (for example `24 bit, 48000 Hz` or `32 bit, 48000 Hz`).
6. Disable additional signal processing/enhancements for cleaner testing where applicable.
7. Apply, close dialogs, and restart the receiver.

## Verify negotiated format

Start receiver and inspect startup logs. Example:

- `Device preferred mix format: IeeeFloat, 48000Hz, 2ch, 32-bit`
- `Internal decode format: IeeeFloat, 48000Hz, 2ch, 32-bit`
- `Render format path: direct`

or when conversion is required:

- `Render format path: MediaFoundationResampler -> device mix format`

If you only see 16-bit formats in the Windows dialog, that endpoint/driver likely does not expose higher precision in shared mode.

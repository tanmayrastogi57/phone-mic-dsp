# Contributing

Thanks for contributing to **phone-mic-dsp**.

## Prerequisites
- .NET SDK 8.x
- Android Studio (for Android module work)
- JDK 17+

## Build and run
### Windows receiver (when project files exist)
```bash
dotnet build windows/PhoneMicReceiver.sln
```

### Android app (when project files exist)
Build from Android Studio or:
```bash
cd android
./gradlew assembleDebug
```

## Formatting and style
- Keep changes small and focused.
- Prefer descriptive names and clear log messages.
- For Android audio capture, keep:
  - `MODE_IN_COMMUNICATION`
  - `VOICE_COMMUNICATION`
- For transport/codec MVP, keep:
  - Opus, 48 kHz, mono, 20 ms frames

## Pull requests
- Include a short summary and testing notes.
- Update docs when behavior/setup changes.

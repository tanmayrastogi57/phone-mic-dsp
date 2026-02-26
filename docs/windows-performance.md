# Windows Receiver Performance Optimization

## Observed Issue

CPU spikes every 5–6 seconds.

Likely cause:
- Frequent memory allocations
- GC pauses
- Per-packet array creation

---

# Critical Fix: Remove Per-Packet Allocations

## Problem Pattern

Bad:
new byte[]
ToArray()

This creates garbage every packet (50 packets/sec).

---

## Correct Approach

### 1. Reuse PCM buffer

Allocate once:
byte[] pcmBytesBuffer = new byte[MAX_FRAME_SIZE];

Reuse for every frame.

---

### 2. Remove ToArray()

Instead of:
bufferedWaveProvider.AddSamples(pcm.ToArray(), 0, length);

Use:
bufferedWaveProvider.AddSamples(pcmBytesBuffer, 0, bytesUsed);

---

### 3. Use ArrayPool (Optional Advanced)

var buffer = ArrayPool<byte>.Shared.Rent(size);

Return after use.

---

### 4. Throttle UI Updates

Do NOT update UI per packet.

Instead:
- Update every 250ms
- Use DispatcherTimer

---

# Expected Improvements

- Stable CPU usage
- No periodic GC spikes
- Lower latency jitter
- Smoother playback

---

# Recommended Buffer Settings

Low Latency:
- outputLatencyMs = 30
- bufferLengthMs = 200

Balanced:
- outputLatencyMs = 50
- bufferLengthMs = 400

Stable:
- outputLatencyMs = 80
- bufferLengthMs = 800

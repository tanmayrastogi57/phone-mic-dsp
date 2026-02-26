using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using Concentus.Structs;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace PhoneMicReceiver.Core;

public sealed record RenderDeviceInfo(
    string Id,
    string FriendlyName,
    bool IsDefault,
    bool MatchesPreferredSubstring);

public enum ReceiverState
{
    Stopped,
    Starting,
    Running,
    Stopping,
    Faulted
}

public sealed record ReceiverStats(
    long PacketsPerSecond,
    long PacketsTotal,
    long DecodeErrors,
    double BufferedMs,
    long Overflows,
    long Underruns,
    long MalformedPackets,
    long ReorderedPackets,
    long LatePacketsDropped,
    long MissingPacketsSkipped);

public sealed class ReceiverEngine : IAsyncDisposable
{
    private readonly SemaphoreSlim _stateGate = new(1, 1);
    private readonly object _statsLock = new();
    private CancellationTokenSource? _runCts;
    private Task? _runTask;
    private ReceiverStats _latestStats = new(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public ReceiverState State { get; private set; } = ReceiverState.Stopped;
    public ReceiverConfig? ActiveConfig { get; private set; }

    public event Action<ReceiverStats>? OnStats;
    public event Action<string>? OnLog;
    public event Action<ReceiverState>? OnStateChanged;

    public static IReadOnlyList<RenderDeviceInfo> GetRenderDevices(string preferredSubstring = ReceiverConfig.DefaultDeviceSubstring)
    {
        using var enumerator = new MMDeviceEnumerator();
        string? defaultDeviceId = TryGetDefaultRenderDeviceId(enumerator);
        var devices = enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active)
            .OrderBy(device => device.FriendlyName)
            .Select(device => new RenderDeviceInfo(
                Id: device.ID,
                FriendlyName: device.FriendlyName,
                IsDefault: string.Equals(device.ID, defaultDeviceId, StringComparison.OrdinalIgnoreCase),
                MatchesPreferredSubstring: device.FriendlyName.Contains(preferredSubstring, StringComparison.OrdinalIgnoreCase)))
            .ToList();

        return devices;
    }

    public async Task StartAsync(ReceiverConfig config, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(config);

        await _stateGate.WaitAsync(cancellationToken);
        try
        {
            if (_runTask is not null)
            {
                throw new InvalidOperationException("Receiver engine is already running.");
            }

            TransitionTo(ReceiverState.Starting);
            ActiveConfig = config;
            _latestStats = _latestStats with { PacketsPerSecond = 0 };

            _runCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            _runTask = RunAsync(config, _runCts.Token);
            TransitionTo(ReceiverState.Running);
        }
        finally
        {
            _stateGate.Release();
        }
    }

    public async Task StopAsync()
    {
        Task? runTask;

        await _stateGate.WaitAsync();
        try
        {
            if (_runTask is null)
            {
                return;
            }

            TransitionTo(ReceiverState.Stopping);
            _runCts?.Cancel();
            runTask = _runTask;
        }
        finally
        {
            _stateGate.Release();
        }

        try
        {
            await runTask!;
        }
        finally
        {
            await _stateGate.WaitAsync();
            try
            {
                _runTask = null;
                _runCts?.Dispose();
                _runCts = null;
                ActiveConfig = null;
                TransitionTo(ReceiverState.Stopped);
            }
            finally
            {
                _stateGate.Release();
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync();
        _stateGate.Dispose();
    }

    private async Task RunAsync(ReceiverConfig config, CancellationToken cancellationToken)
    {
        try
        {
            using var enumerator = new MMDeviceEnumerator();
            var renderDevices = enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active)
                .OrderBy(device => device.FriendlyName)
                .ToList();

            if (renderDevices.Count == 0)
            {
                throw new InvalidOperationException("No active render audio devices were found.");
            }

            var selectedDevice = SelectRenderDevice(config, enumerator, renderDevices, out bool selectedByFallback);

            if (config.Channels == 2 && selectedDevice.AudioClient.MixFormat.Channels < 2)
            {
                throw new InvalidOperationException($"Selected device does not report stereo mix support (mixChannels={selectedDevice.AudioClient.MixFormat.Channels}).");
            }

            var internalFloatFormat = WaveFormat.CreateIeeeFloatWaveFormat(ReceiverConfig.SampleRate, config.Channels);
            var bufferedWaveProvider = new BufferedWaveProvider(internalFloatFormat)
            {
                BufferDuration = TimeSpan.FromMilliseconds(config.BufferLengthMs),
                DiscardOnBufferOverflow = true
            };

            IWaveProvider playbackProvider = bufferedWaveProvider;
            MediaFoundationResampler? resampler = null;
            WaveFormat deviceMixFormat = selectedDevice.AudioClient.MixFormat;

            if (!WaveFormatEquals(bufferedWaveProvider.WaveFormat, deviceMixFormat))
            {
                resampler = new MediaFoundationResampler(bufferedWaveProvider, deviceMixFormat)
                {
                    ResamplerQuality = 60
                };
                playbackProvider = resampler;
            }

            using var output = new WasapiOut(selectedDevice, AudioClientShareMode.Shared, true, config.OutputLatencyMs);
            using (resampler)
            {
                output.Init(playbackProvider);
                output.Play();

                Log($"Selected output device: {selectedDevice.FriendlyName}");
                if (selectedByFallback)
                {
                    Log($"Preferred device substring \"{config.DeviceSubstring}\" was not found. Falling back to default render device.");
                }
                Log($"Device preferred mix format: {DescribeWaveFormat(deviceMixFormat)}");
                Log($"Internal decode format: {DescribeWaveFormat(internalFloatFormat)}");
                Log($"Render format path: {(resampler is null ? "direct" : "MediaFoundationResampler -> device mix format")}");
                Log($"Startup config: bindAddress={config.BindAddress}, port={config.ListenPort}, deviceId=\"{config.DeviceId}\", deviceSubstring=\"{config.DeviceSubstring}\", outputLatencyMs={config.OutputLatencyMs}, bufferLengthMs={config.BufferLengthMs}, jitterTargetDelayMs={config.JitterTargetDelayMs}, lockSenderIp={config.LockToSenderIp}, channels={config.Channels}");

                if (config.TestToneSeconds > 0)
                {
                    var toneBytes = GenerateSineFloat(440, config.TestToneSeconds, ReceiverConfig.SampleRate, config.Channels);
                    bufferedWaveProvider.AddSamples(toneBytes, 0, toneBytes.Length);
                    Log($"Queued {config.TestToneSeconds}s test tone at 440 Hz.");
                }

                await ReceiveUdpOpusAsync(config, enumerator, selectedDevice.ID, bufferedWaveProvider, cancellationToken);
            }
        }
        catch (OperationCanceledException)
        {
            // graceful stop
        }
        catch (Exception ex)
        {
            TransitionTo(ReceiverState.Faulted);
            Log($"Receiver faulted: {ex.Message}");
            throw;
        }
    }

    private async Task ReceiveUdpOpusAsync(
        ReceiverConfig config,
        MMDeviceEnumerator enumerator,
        string selectedDeviceId,
        BufferedWaveProvider bufferedWaveProvider,
        CancellationToken cancellationToken)
    {
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        bool deviceDisconnected = false;
        var monitorTask = MonitorSelectedDeviceAsync(enumerator, selectedDeviceId, () =>
        {
            deviceDisconnected = true;
            linkedCts.Cancel();
        }, linkedCts.Token);

        using var udpClient = new UdpClient(new IPEndPoint(IPAddress.Parse(config.BindAddress), config.ListenPort));
        var opusDecoder = OpusDecoder.Create(ReceiverConfig.SampleRate, config.Channels);
        var statsWindow = Stopwatch.StartNew();
        bool bufferPrimed = false;

        const int frameSize = 960;
        const int packetHeaderLengthBytes = 8;
        const int frameDurationMs = 20;
        var decodedSamples = new short[frameSize * config.Channels];
        var floatSamples = new float[frameSize * config.Channels];
        var pcmFloatBytes = new byte[frameSize * config.Channels * sizeof(float)];
        int jitterTargetPackets = Math.Max(1, (int)Math.Ceiling(config.JitterTargetDelayMs / (double)frameDurationMs));
        var jitterBuffer = new SequenceJitterBuffer(jitterTargetPackets, maxBufferedPackets: 200);

        Log($"Listening for UDP Opus packets on {config.BindAddress}:{config.ListenPort} with jitter target={jitterTargetPackets} packets...");

        long packetsThisWindow = 0;
        long packetsTotal = 0;
        long decodeErrors = 0;
        long overflows = 0;
        long underruns = 0;
        long malformedPackets = 0;
        long latePacketsDropped = 0;
        long missingPacketsSkipped = 0;
        long reorderedPackets = 0;
        IPAddress? lockedSenderIp = config.LockToSenderIp;

        while (!linkedCts.Token.IsCancellationRequested)
        {
            UdpReceiveResult packet;
            try
            {
                packet = await udpClient.ReceiveAsync(linkedCts.Token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (SocketException ex)
            {
                Log($"UDP receive error: {ex.Message}");
                continue;
            }

            if (lockedSenderIp is not null && !Equals(packet.RemoteEndPoint.Address, lockedSenderIp))
            {
                continue;
            }

            var packetBuffer = packet.Buffer;
            packetsThisWindow++;
            packetsTotal++;

            if (packetBuffer.Length <= packetHeaderLengthBytes)
            {
                malformedPackets++;
                continue;
            }

            uint sequence = BinaryPrimitives.ReadUInt32BigEndian(packetBuffer.AsSpan(0, 4));
            uint timestampMs = BinaryPrimitives.ReadUInt32BigEndian(packetBuffer.AsSpan(4, 4));
            var opusPayload = packetBuffer.AsSpan(packetHeaderLengthBytes).ToArray();

            var enqueueOutcome = jitterBuffer.Enqueue(sequence, timestampMs, opusPayload);
            if (enqueueOutcome == EnqueueOutcome.Reordered)
            {
                reorderedPackets++;
            }
            else if (enqueueOutcome == EnqueueOutcome.LateDropped)
            {
                latePacketsDropped++;
            }

            while (jitterBuffer.TryDequeue(out var nextPayload, out int skipped))
            {
                if (skipped > 0)
                {
                    missingPacketsSkipped += skipped;
                }

                try
                {
                    int decodedFrameCount = opusDecoder.Decode(nextPayload, 0, nextPayload.Length, decodedSamples, 0, frameSize, false);
                    int totalSamples = decodedFrameCount * config.Channels;
                    if (totalSamples <= 0)
                    {
                        continue;
                    }

                    for (int i = 0; i < totalSamples; i++)
                    {
                        floatSamples[i] = decodedSamples[i] / 32768f;
                    }

                    int floatBytesCount = totalSamples * sizeof(float);
                    Buffer.BlockCopy(floatSamples, 0, pcmFloatBytes, 0, floatBytesCount);

                    if (bufferedWaveProvider.BufferedBytes + floatBytesCount > bufferedWaveProvider.BufferLength)
                    {
                        overflows++;
                        Log($"[buffer] overflow predicted; dropping oldest samples (event #{overflows}).");
                    }

                    bufferedWaveProvider.AddSamples(pcmFloatBytes, 0, floatBytesCount);
                    bufferPrimed = true;
                }
                catch (Exception)
                {
                    decodeErrors++;
                }
            }

            if (statsWindow.ElapsedMilliseconds >= 1_000)
            {
                double bufferedMs = bufferedWaveProvider.BufferedDuration.TotalMilliseconds;
                if (bufferPrimed && bufferedMs < 10)
                {
                    underruns++;
                    Log($"[buffer] underrun risk; buffered={bufferedMs:F1}ms (event #{underruns}).");
                }

                PublishStats(new ReceiverStats(
                    PacketsPerSecond: packetsThisWindow,
                    PacketsTotal: packetsTotal,
                    DecodeErrors: decodeErrors,
                    BufferedMs: bufferedMs,
                    Overflows: overflows,
                    Underruns: underruns,
                    MalformedPackets: malformedPackets,
                    ReorderedPackets: reorderedPackets,
                    LatePacketsDropped: latePacketsDropped,
                    MissingPacketsSkipped: missingPacketsSkipped));

                packetsThisWindow = 0;
                statsWindow.Restart();
            }
        }

        PublishStats(new ReceiverStats(
            PacketsPerSecond: 0,
            PacketsTotal: packetsTotal,
            DecodeErrors: decodeErrors,
            BufferedMs: bufferedWaveProvider.BufferedDuration.TotalMilliseconds,
            Overflows: overflows,
            Underruns: underruns,
            MalformedPackets: malformedPackets,
            ReorderedPackets: reorderedPackets,
            LatePacketsDropped: latePacketsDropped,
            MissingPacketsSkipped: missingPacketsSkipped));
        Log($"Receiver stopped. packetsTotal={packetsTotal}, decodeErrors={decodeErrors}, malformed={malformedPackets}, reordered={reorderedPackets}, lateDrops={latePacketsDropped}, missingSkips={missingPacketsSkipped}, overflows={overflows}, underruns={underruns}");

        linkedCts.Cancel();
        try
        {
            await monitorTask;
        }
        catch (OperationCanceledException)
        {
            // expected during normal stop
        }

        if (deviceDisconnected)
        {
            throw new InvalidOperationException("Selected output device was disconnected. Re-select an active render device and start the receiver again.");
        }
    }

    private static MMDevice SelectRenderDevice(ReceiverConfig config, MMDeviceEnumerator enumerator, IReadOnlyList<MMDevice> renderDevices, out bool selectedByFallback)
    {
        selectedByFallback = false;

        if (!string.IsNullOrWhiteSpace(config.DeviceId))
        {
            var exact = renderDevices.FirstOrDefault(d => string.Equals(d.ID, config.DeviceId, StringComparison.OrdinalIgnoreCase));
            if (exact is not null)
            {
                return exact;
            }

            throw new InvalidOperationException($"No render device matched id \"{config.DeviceId}\".");
        }

        var bySubstring = renderDevices.FirstOrDefault(device =>
            device.FriendlyName.Contains(config.DeviceSubstring, StringComparison.OrdinalIgnoreCase));

        if (bySubstring is not null)
        {
            return bySubstring;
        }

        var fallbackDevice = TryGetDefaultRenderDevice(enumerator, renderDevices);
        if (fallbackDevice is not null)
        {
            selectedByFallback = true;
            return fallbackDevice;
        }

        var availableDevices = string.Join(Environment.NewLine, renderDevices.Select(device => $"- {device.FriendlyName}"));
        throw new InvalidOperationException($"No render device matched substring \"{config.DeviceSubstring}\".{Environment.NewLine}Available render devices:{Environment.NewLine}{availableDevices}");
    }

    private async Task MonitorSelectedDeviceAsync(MMDeviceEnumerator enumerator, string selectedDeviceId, Action onDisconnected, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(1), cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            if (!IsDeviceActive(enumerator, selectedDeviceId))
            {
                Log("Selected output device is no longer available. Stopping receiver.");
                onDisconnected();
                return;
            }
        }
    }

    private static bool IsDeviceActive(MMDeviceEnumerator enumerator, string deviceId)
    {
        try
        {
            var device = enumerator.GetDevice(deviceId);
            return device.State == DeviceState.Active;
        }
        catch
        {
            return false;
        }
    }

    private static MMDevice? TryGetDefaultRenderDevice(MMDeviceEnumerator enumerator, IReadOnlyList<MMDevice> activeDevices)
    {
        string? defaultDeviceId = TryGetDefaultRenderDeviceId(enumerator);
        if (defaultDeviceId is null)
        {
            return null;
        }

        return activeDevices.FirstOrDefault(device => string.Equals(device.ID, defaultDeviceId, StringComparison.OrdinalIgnoreCase));
    }

    private static string? TryGetDefaultRenderDeviceId(MMDeviceEnumerator enumerator)
    {
        try
        {
            return enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia).ID;
        }
        catch
        {
            return null;
        }
    }

    private void PublishStats(ReceiverStats stats)
    {
        lock (_statsLock)
        {
            _latestStats = stats;
        }

        OnStats?.Invoke(stats);
        Log($"[stats] packets/sec={stats.PacketsPerSecond}, packetsTotal={stats.PacketsTotal}, decodeErrors={stats.DecodeErrors}, malformed={stats.MalformedPackets}, reordered={stats.ReorderedPackets}, lateDrops={stats.LatePacketsDropped}, missingSkips={stats.MissingPacketsSkipped}, bufferedMs={stats.BufferedMs:F1}, overflows={stats.Overflows}, underruns={stats.Underruns}");
    }

    private void Log(string message) => OnLog?.Invoke(message);

    private void TransitionTo(ReceiverState newState)
    {
        State = newState;
        OnStateChanged?.Invoke(newState);
    }

    private static byte[] GenerateSineFloat(int frequencyHz, int durationSeconds, int waveSampleRate, int waveChannels)
    {
        int sampleCount = waveSampleRate * durationSeconds;
        var pcmBytes = new byte[sampleCount * waveChannels * sizeof(float)];
        var frame = new float[waveChannels];

        for (int i = 0; i < sampleCount; i++)
        {
            double sample = Math.Sin(2 * Math.PI * frequencyHz * i / waveSampleRate);
            float pcmSample = (float)(sample * 0.2);
            for (int ch = 0; ch < waveChannels; ch++)
            {
                frame[ch] = pcmSample;
            }

            Buffer.BlockCopy(frame, 0, pcmBytes, i * waveChannels * sizeof(float), waveChannels * sizeof(float));
        }

        return pcmBytes;
    }

    private static bool WaveFormatEquals(WaveFormat left, WaveFormat right)
    {
        return left.Encoding == right.Encoding
            && left.SampleRate == right.SampleRate
            && left.Channels == right.Channels
            && left.BitsPerSample == right.BitsPerSample;
    }

    private static string DescribeWaveFormat(WaveFormat format)
    {
        return $"{format.Encoding}, {format.SampleRate}Hz, {format.Channels}ch, {format.BitsPerSample}-bit";
    }
}

internal enum EnqueueOutcome
{
    Accepted,
    Reordered,
    LateDropped
}

internal sealed class SequenceJitterBuffer
{
    private readonly SortedDictionary<uint, byte[]> _buffer = new();
    private readonly int _targetDelayPackets;
    private readonly int _maxBufferedPackets;
    private uint? _expectedSequence;
    private uint? _lastEnqueuedSequence;

    public SequenceJitterBuffer(int targetDelayPackets, int maxBufferedPackets)
    {
        _targetDelayPackets = Math.Max(1, targetDelayPackets);
        _maxBufferedPackets = Math.Max(_targetDelayPackets + 1, maxBufferedPackets);
    }

    public int BufferedPackets => _buffer.Count;

    public EnqueueOutcome Enqueue(uint sequence, uint _timestampMs, byte[] payload)
    {
        _expectedSequence ??= sequence;

        if (_expectedSequence.HasValue && SequenceLessThan(sequence, _expectedSequence.Value))
        {
            return EnqueueOutcome.LateDropped;
        }

        bool reordered = _lastEnqueuedSequence.HasValue && SequenceLessThan(sequence, _lastEnqueuedSequence.Value);
        _lastEnqueuedSequence = sequence;
        _buffer[sequence] = payload;

        while (_buffer.Count > _maxBufferedPackets)
        {
            uint oldest = _buffer.Keys.First();
            _buffer.Remove(oldest);
            if (_expectedSequence.HasValue && oldest == _expectedSequence.Value)
            {
                _expectedSequence = oldest + 1;
            }
        }

        return reordered ? EnqueueOutcome.Reordered : EnqueueOutcome.Accepted;
    }

    public bool TryDequeue(out byte[] payload, out int skippedMissingPackets)
    {
        payload = Array.Empty<byte>();
        skippedMissingPackets = 0;

        if (!_expectedSequence.HasValue || _buffer.Count < _targetDelayPackets)
        {
            return false;
        }

        while (true)
        {
            uint expected = _expectedSequence.Value;
            if (_buffer.Remove(expected, out payload))
            {
                _expectedSequence = expected + 1;
                return true;
            }

            uint earliestAvailable = _buffer.Keys.First();
            if (!SequenceLessThan(expected, earliestAvailable))
            {
                _expectedSequence = expected + 1;
                continue;
            }

            skippedMissingPackets++;
            _expectedSequence = expected + 1;

            if (_buffer.Count < _targetDelayPackets)
            {
                return false;
            }
        }
    }

    private static bool SequenceLessThan(uint left, uint right) => unchecked((int)(left - right)) < 0;
}

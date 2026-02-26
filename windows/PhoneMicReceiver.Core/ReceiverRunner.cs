using System.Buffers.Binary;
using System.Diagnostics;
using System.Net.Sockets;
using Concentus.Structs;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace PhoneMicReceiver.Core;

public sealed class ReceiverRunner
{
    public async Task RunUntilCancelledAsync(ReceiverConfig config, CancellationToken cancellationToken)
    {
        using var enumerator = new MMDeviceEnumerator();
        var renderDevices = enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active)
            .OrderBy(device => device.FriendlyName)
            .ToList();

        if (renderDevices.Count == 0)
        {
            throw new InvalidOperationException("No active render audio devices were found.");
        }

        var selectedDevice = renderDevices.FirstOrDefault(device =>
            device.FriendlyName.Contains(config.DeviceSubstring, StringComparison.OrdinalIgnoreCase));

        if (selectedDevice is null)
        {
            var availableDevices = string.Join(Environment.NewLine, renderDevices.Select(device => $"- {device.FriendlyName}"));
            throw new InvalidOperationException($"No render device matched substring \"{config.DeviceSubstring}\".{Environment.NewLine}Available render devices:{Environment.NewLine}{availableDevices}");
        }

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

            Console.WriteLine($"Selected output device: {selectedDevice.FriendlyName}");
            Console.WriteLine($"Device preferred mix format: {DescribeWaveFormat(deviceMixFormat)}");
            Console.WriteLine($"Internal decode format: {DescribeWaveFormat(internalFloatFormat)}");
            Console.WriteLine($"Render format path: {(resampler is null ? "direct" : "MediaFoundationResampler -> device mix format")}");
            Console.WriteLine($"Startup config: port={config.ListenPort}, deviceSubstring=\"{config.DeviceSubstring}\", outputLatencyMs={config.OutputLatencyMs}, bufferLengthMs={config.BufferLengthMs}, jitterTargetDelayMs={config.JitterTargetDelayMs}, channels={config.Channels}");
            Console.WriteLine("WASAPI output is running.");
            Console.WriteLine("Receiver is in Opus UDP mode. Press Ctrl+C to stop.");

            if (config.TestToneSeconds > 0)
            {
                var toneBytes = GenerateSineFloat(440, config.TestToneSeconds, ReceiverConfig.SampleRate, config.Channels);
                bufferedWaveProvider.AddSamples(toneBytes, 0, toneBytes.Length);
                Console.WriteLine($"Queued {config.TestToneSeconds}s test tone at 440 Hz.");
            }

            await ReceiveUdpOpusAsync(config.ListenPort, config.JitterTargetDelayMs, config.Channels, bufferedWaveProvider, cancellationToken);
        }
    }

    private static async Task ReceiveUdpOpusAsync(int port, int jitterDelayMs, int decodeChannels, BufferedWaveProvider bufferedWaveProvider, CancellationToken cancellationToken)
    {
        using var udpClient = new UdpClient(port);
        var opusDecoder = OpusDecoder.Create(ReceiverConfig.SampleRate, decodeChannels);
        long packetsThisWindow = 0;
        long packetsTotal = 0;
        long decodeErrors = 0;
        long overflows = 0;
        long underruns = 0;
        long malformedPackets = 0;
        long latePacketsDropped = 0;
        long missingPacketsSkipped = 0;
        long reorderedPackets = 0;
        var statsWindow = Stopwatch.StartNew();
        bool bufferPrimed = false;

        const int frameSize = 960;
        const int packetHeaderLengthBytes = 8;
        const int frameDurationMs = 20;
        var decodedSamples = new short[frameSize * decodeChannels];
        var floatSamples = new float[frameSize * decodeChannels];
        var pcmFloatBytes = new byte[frameSize * decodeChannels * sizeof(float)];
        int jitterTargetPackets = Math.Max(1, (int)Math.Ceiling(jitterDelayMs / (double)frameDurationMs));
        var jitterBuffer = new SequenceJitterBuffer(jitterTargetPackets, maxBufferedPackets: 200);

        Console.WriteLine($"Listening for UDP Opus packets on 0.0.0.0:{port} with jitter target={jitterTargetPackets} packets...");

        while (!cancellationToken.IsCancellationRequested)
        {
            UdpReceiveResult packet;

            try
            {
                packet = await udpClient.ReceiveAsync(cancellationToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (SocketException ex)
            {
                Console.Error.WriteLine($"UDP receive error: {ex.Message}");
                continue;
            }

            if (packet.Buffer.Length <= packetHeaderLengthBytes)
            {
                malformedPackets++;
                continue;
            }

            packetsThisWindow++;
            packetsTotal++;

            var packetSpan = packet.Buffer.AsSpan();
            uint sequence = BinaryPrimitives.ReadUInt32BigEndian(packetSpan[..4]);
            uint timestampMs = BinaryPrimitives.ReadUInt32BigEndian(packetSpan.Slice(4, 4));
            var opusPayload = packetSpan[packetHeaderLengthBytes..].ToArray();

            var enqueueOutcome = jitterBuffer.Enqueue(sequence, timestampMs, opusPayload);
            if (enqueueOutcome == EnqueueOutcome.LateDropped)
            {
                latePacketsDropped++;
                continue;
            }

            if (enqueueOutcome == EnqueueOutcome.Reordered)
            {
                reorderedPackets++;
            }

            while (jitterBuffer.TryDequeue(out var orderedPayload, out int skippedPackets))
            {
                if (skippedPackets > 0)
                {
                    missingPacketsSkipped += skippedPackets;
                }

                try
                {
                    int decodedSamplesPerChannel = opusDecoder.Decode(orderedPayload, 0, orderedPayload.Length, decodedSamples, 0, frameSize, false);
                    int totalSamples = decodedSamplesPerChannel * decodeChannels;

                    if (totalSamples <= 0)
                    {
                        decodeErrors++;
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
                        Console.WriteLine($"[buffer] overflow predicted; dropping oldest samples (event #{overflows}).");
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
                    Console.WriteLine($"[buffer] underrun risk; buffered={bufferedMs:F1}ms (event #{underruns}).");
                }

                Console.WriteLine($"[stats] packets/sec={packetsThisWindow}, decodeErrors={decodeErrors}, malformed={malformedPackets}, reordered={reorderedPackets}, lateDrops={latePacketsDropped}, missingSkips={missingPacketsSkipped}, jitterBuffered={jitterBuffer.BufferedPackets}, bufferedMs={bufferedMs:F1}, overflows={overflows}, underruns={underruns}");
                packetsThisWindow = 0;
                statsWindow.Restart();
            }
        }

        Console.WriteLine($"Receiver stopped. packetsTotal={packetsTotal}, decodeErrors={decodeErrors}, malformed={malformedPackets}, reordered={reorderedPackets}, lateDrops={latePacketsDropped}, missingSkips={missingPacketsSkipped}, overflows={overflows}, underruns={underruns}");
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

enum EnqueueOutcome
{
    Accepted,
    Reordered,
    LateDropped
}

sealed class SequenceJitterBuffer
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

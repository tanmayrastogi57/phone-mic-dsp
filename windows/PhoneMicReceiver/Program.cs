using System.Net.Sockets;
using System.Diagnostics;
using Concentus.Structs;
using NAudio.CoreAudioApi;
using NAudio.Wave;

const string defaultDeviceSubstring = "CABLE Input";
const int defaultPort = 5555;
const int sampleRate = 48_000;
const int channels = 1;
const int bitsPerSample = 16;

int listenPort = ParsePositiveIntArg(args, 0, defaultPort, "port");
string deviceSubstring = args.Length > 1 && !string.IsNullOrWhiteSpace(args[1])
    ? args[1]
    : defaultDeviceSubstring;
int outputLatencyMs = ParsePositiveIntArg(args, 2, 50, "outputLatencyMs");
int bufferLengthMs = ParsePositiveIntArg(args, 3, 500, "bufferLengthMs");
int testToneSeconds = ParseNonNegativeIntArg(args, 4, 0, "testToneSeconds");

using var enumerator = new MMDeviceEnumerator();
var renderDevices = enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active)
    .OrderBy(device => device.FriendlyName)
    .ToList();

if (renderDevices.Count == 0)
{
    Console.Error.WriteLine("No active render audio devices were found.");
    Environment.Exit(1);
}

var selectedDevice = renderDevices.FirstOrDefault(device =>
    device.FriendlyName.Contains(deviceSubstring, StringComparison.OrdinalIgnoreCase));

if (selectedDevice is null)
{
    Console.Error.WriteLine($"No render device matched substring \"{deviceSubstring}\".");
    Console.Error.WriteLine("Available render devices:");

    foreach (var device in renderDevices)
    {
        Console.Error.WriteLine($"- {device.FriendlyName}");
    }

    Environment.Exit(1);
}

var waveFormat = new WaveFormat(sampleRate, bitsPerSample, channels);
var bufferedWaveProvider = new BufferedWaveProvider(waveFormat)
{
    BufferDuration = TimeSpan.FromMilliseconds(bufferLengthMs),
    DiscardOnBufferOverflow = true
};

using var output = new WasapiOut(selectedDevice, AudioClientShareMode.Shared, true, outputLatencyMs);
output.Init(bufferedWaveProvider);
output.Play();

Console.WriteLine($"Selected output device: {selectedDevice.FriendlyName}");
Console.WriteLine($"Startup config: port={listenPort}, deviceSubstring=\"{deviceSubstring}\", outputLatencyMs={outputLatencyMs}, bufferLengthMs={bufferLengthMs}");
Console.WriteLine("WASAPI output is running.");
Console.WriteLine("Receiver is in Opus UDP mode. Press Ctrl+C to stop.");

if (testToneSeconds > 0)
{
    var toneBytes = GenerateSinePcm(440, testToneSeconds, sampleRate);
    WritePcmToBuffer(toneBytes);
    Console.WriteLine($"Queued {testToneSeconds}s test tone at 440 Hz.");
}

var shutdown = new ManualResetEventSlim(false);
using var cancellationTokenSource = new CancellationTokenSource();

Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    cancellationTokenSource.Cancel();
    shutdown.Set();
};

var receiverTask = ReceiveUdpOpusAsync(listenPort, cancellationTokenSource.Token);

shutdown.Wait();

try
{
    await receiverTask;
}
catch (OperationCanceledException)
{
}

void WritePcmToBuffer(ReadOnlySpan<byte> pcmBytes)
{
    bufferedWaveProvider.AddSamples(pcmBytes.ToArray(), 0, pcmBytes.Length);
}

async Task ReceiveUdpOpusAsync(int port, CancellationToken cancellationToken)
{
    using var udpClient = new UdpClient(port);
    var opusDecoder = OpusDecoder.Create(sampleRate, channels);
    long packetsThisWindow = 0;
    long packetsTotal = 0;
    long decodeErrors = 0;
    long overflows = 0;
    long underruns = 0;
    var statsWindow = Stopwatch.StartNew();
    bool bufferPrimed = false;

    const int frameSize = 960;
    var decodedSamples = new short[frameSize * channels];

    Console.WriteLine($"Listening for UDP Opus packets on 0.0.0.0:{port}...");

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

        if (packet.Buffer.Length == 0)
        {
            continue;
        }

        packetsThisWindow++;
        packetsTotal++;

        try
        {
            int decodedSamplesPerChannel = opusDecoder.Decode(packet.Buffer, 0, packet.Buffer.Length, decodedSamples, 0, frameSize, false);
            int totalSamples = decodedSamplesPerChannel * channels;

            if (totalSamples <= 0)
            {
                decodeErrors++;
                continue;
            }

            var pcmBytes = new byte[totalSamples * sizeof(short)];
            Buffer.BlockCopy(decodedSamples, 0, pcmBytes, 0, pcmBytes.Length);

            if (bufferedWaveProvider.BufferedBytes + pcmBytes.Length > bufferedWaveProvider.BufferLength)
            {
                overflows++;
                Console.WriteLine($"[buffer] overflow predicted; dropping oldest samples (event #{overflows}).");
            }

            WritePcmToBuffer(pcmBytes);
            bufferPrimed = true;
        }
        catch (Exception)
        {
            decodeErrors++;
            continue;
        }

        if (statsWindow.ElapsedMilliseconds >= 1_000)
        {
            double bufferedMs = bufferedWaveProvider.BufferedDuration.TotalMilliseconds;
            if (bufferPrimed && bufferedMs < 10)
            {
                underruns++;
                Console.WriteLine($"[buffer] underrun risk; buffered={bufferedMs:F1}ms (event #{underruns}).");
            }

            Console.WriteLine($"[stats] packets/sec={packetsThisWindow}, decodeErrors={decodeErrors}, bufferedMs={bufferedMs:F1}, overflows={overflows}, underruns={underruns}");
            packetsThisWindow = 0;
            statsWindow.Restart();
        }
    }

    Console.WriteLine($"Receiver stopped. packetsTotal={packetsTotal}, decodeErrors={decodeErrors}, overflows={overflows}, underruns={underruns}");
}

static byte[] GenerateSinePcm(int frequencyHz, int durationSeconds, int waveSampleRate)
{
    int sampleCount = waveSampleRate * durationSeconds;
    var pcmBytes = new byte[sampleCount * 2];

    for (int i = 0; i < sampleCount; i++)
    {
        double sample = Math.Sin(2 * Math.PI * frequencyHz * i / waveSampleRate);
        short pcmSample = (short)(sample * short.MaxValue * 0.2);
        pcmBytes[i * 2] = (byte)(pcmSample & 0xFF);
        pcmBytes[i * 2 + 1] = (byte)((pcmSample >> 8) & 0xFF);
    }

    return pcmBytes;
}

static int ParsePositiveIntArg(string[] commandLineArgs, int index, int defaultValue, string argName)
{
    if (commandLineArgs.Length <= index || string.IsNullOrWhiteSpace(commandLineArgs[index]))
    {
        return defaultValue;
    }

    if (int.TryParse(commandLineArgs[index], out int parsed) && parsed > 0)
    {
        return parsed;
    }

    Console.Error.WriteLine($"Invalid {argName}: '{commandLineArgs[index]}'. Expected a positive integer.");
    Environment.Exit(1);
    return defaultValue;
}

static int ParseNonNegativeIntArg(string[] commandLineArgs, int index, int defaultValue, string argName)
{
    if (commandLineArgs.Length <= index || string.IsNullOrWhiteSpace(commandLineArgs[index]))
    {
        return defaultValue;
    }

    if (int.TryParse(commandLineArgs[index], out int parsed) && parsed >= 0)
    {
        return parsed;
    }

    Console.Error.WriteLine($"Invalid {argName}: '{commandLineArgs[index]}'. Expected a non-negative integer.");
    Environment.Exit(1);
    return defaultValue;
}

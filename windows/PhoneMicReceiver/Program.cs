using NAudio.CoreAudioApi;
using NAudio.Wave;

const string defaultDeviceSubstring = "CABLE Input";
const int sampleRate = 48_000;
const int channels = 1;
const int bitsPerSample = 16;

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
Console.WriteLine($"Output latency: {outputLatencyMs} ms");
Console.WriteLine($"Buffer length: {bufferLengthMs} ms");
Console.WriteLine("WASAPI output is running. Press Ctrl+C to stop.");

if (testToneSeconds > 0)
{
    var toneBytes = GenerateSinePcm(440, testToneSeconds, sampleRate);
    WritePcmToBuffer(toneBytes);
    Console.WriteLine($"Queued {testToneSeconds}s test tone at 440 Hz.");
}

var shutdown = new ManualResetEventSlim(false);
Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    shutdown.Set();
};

shutdown.Wait();

void WritePcmToBuffer(ReadOnlySpan<byte> pcmBytes)
{
    bufferedWaveProvider.AddSamples(pcmBytes.ToArray(), 0, pcmBytes.Length);
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

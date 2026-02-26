using System.Net;
using PhoneMicReceiver.Core;

var config = new ReceiverConfig
{
    ListenPort = ParsePositiveIntArg(args, 0, ReceiverConfig.DefaultPort, "port"),
    DeviceSubstring = args.Length > 1 && !string.IsNullOrWhiteSpace(args[1]) ? args[1] : ReceiverConfig.DefaultDeviceSubstring,
    OutputLatencyMs = ParsePositiveIntArg(args, 2, 50, "outputLatencyMs"),
    BufferLengthMs = ParsePositiveIntArg(args, 3, 500, "bufferLengthMs"),
    TestToneSeconds = ParseNonNegativeIntArg(args, 4, 0, "testToneSeconds"),
    JitterTargetDelayMs = ParseNonNegativeIntArg(args, 5, 60, "jitterTargetDelayMs"),
    Channels = ParseChannelCountArg(args, 6, ReceiverConfig.DefaultChannels),
    BindAddress = ParseBindAddressArg(args, 7),
    LockToSenderIp = ParseOptionalIpArg(args, 8, "lockToSenderIp")
};

using var cancellationTokenSource = new CancellationTokenSource();

Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    cancellationTokenSource.Cancel();
};

await using var engine = new ReceiverEngine();
engine.OnLog += Console.WriteLine;
engine.OnStateChanged += state => Console.WriteLine($"[state] {state}");

try
{
    await engine.StartAsync(config, cancellationTokenSource.Token);
    await Task.Delay(Timeout.InfiniteTimeSpan, cancellationTokenSource.Token);
}
catch (OperationCanceledException)
{
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    Environment.ExitCode = 1;
}
finally
{
    await engine.StopAsync();
}

static int ParseChannelCountArg(string[] commandLineArgs, int index, int defaultValue)
{
    if (commandLineArgs.Length <= index || string.IsNullOrWhiteSpace(commandLineArgs[index]))
    {
        return defaultValue;
    }

    if (int.TryParse(commandLineArgs[index], out int parsed) && (parsed == 1 || parsed == 2))
    {
        return parsed;
    }

    Console.Error.WriteLine($"Invalid channels: '{commandLineArgs[index]}'. Expected 1 or 2.");
    Environment.Exit(1);
    return defaultValue;
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

static string ParseBindAddressArg(string[] commandLineArgs, int index)
{
    if (commandLineArgs.Length <= index || string.IsNullOrWhiteSpace(commandLineArgs[index]))
    {
        return ReceiverConfig.DefaultBindAddress;
    }

    if (IPAddress.TryParse(commandLineArgs[index], out _))
    {
        return commandLineArgs[index];
    }

    Console.Error.WriteLine($"Invalid bindAddress: '{commandLineArgs[index]}'. Expected an IP address literal.");
    Environment.Exit(1);
    return ReceiverConfig.DefaultBindAddress;
}

static IPAddress? ParseOptionalIpArg(string[] commandLineArgs, int index, string argName)
{
    if (commandLineArgs.Length <= index || string.IsNullOrWhiteSpace(commandLineArgs[index]))
    {
        return null;
    }

    if (IPAddress.TryParse(commandLineArgs[index], out var parsed))
    {
        return parsed;
    }

    Console.Error.WriteLine($"Invalid {argName}: '{commandLineArgs[index]}'. Expected an IP address literal.");
    Environment.Exit(1);
    return null;
}

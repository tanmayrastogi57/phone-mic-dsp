using PhoneMicReceiver.Core;

var config = new ReceiverConfig
{
    ListenPort = ParsePositiveIntArg(args, 0, ReceiverConfig.DefaultPort, "port"),
    DeviceSubstring = args.Length > 1 && !string.IsNullOrWhiteSpace(args[1]) ? args[1] : ReceiverConfig.DefaultDeviceSubstring,
    OutputLatencyMs = ParsePositiveIntArg(args, 2, 50, "outputLatencyMs"),
    BufferLengthMs = ParsePositiveIntArg(args, 3, 500, "bufferLengthMs"),
    TestToneSeconds = ParseNonNegativeIntArg(args, 4, 0, "testToneSeconds"),
    JitterTargetDelayMs = ParseNonNegativeIntArg(args, 5, 60, "jitterTargetDelayMs"),
    Channels = ParseChannelCountArg(args, 6, ReceiverConfig.DefaultChannels)
};

using var cancellationTokenSource = new CancellationTokenSource();

Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    cancellationTokenSource.Cancel();
};

try
{
    var runner = new ReceiverRunner();
    await runner.RunUntilCancelledAsync(config, cancellationTokenSource.Token);
}
catch (OperationCanceledException)
{
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    Environment.ExitCode = 1;
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

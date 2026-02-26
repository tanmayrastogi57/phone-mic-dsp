using System.Net;

namespace PhoneMicReceiver.Core;

public sealed class ReceiverConfig
{
    public const int SampleRate = 48_000;
    public const string DefaultDeviceSubstring = "CABLE Input";
    public const int DefaultPort = 5555;
    public const int DefaultChannels = 1;
    public const string DefaultBindAddress = "0.0.0.0";

    public int ListenPort { get; init; } = DefaultPort;
    public string BindAddress { get; init; } = DefaultBindAddress;
    public string? DeviceId { get; init; }
    public string DeviceSubstring { get; init; } = DefaultDeviceSubstring;
    public int OutputLatencyMs { get; init; } = 50;
    public int BufferLengthMs { get; init; } = 500;
    public int TestToneSeconds { get; init; } = 0;
    public int JitterTargetDelayMs { get; init; } = 60;
    public int Channels { get; init; } = DefaultChannels;
    public IPAddress? LockToSenderIp { get; init; }
}

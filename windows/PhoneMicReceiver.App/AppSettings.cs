namespace PhoneMicReceiver.App;

public sealed class AppSettings
{
    public int ListenPort { get; set; } = ReceiverConfigDefaults.ListenPort;
    public string? SelectedDeviceId { get; set; }
    public int OutputLatencyMs { get; set; } = ReceiverConfigDefaults.OutputLatencyMs;
    public int BufferLengthMs { get; set; } = ReceiverConfigDefaults.BufferLengthMs;
    public string? LockSenderIp { get; set; }
    public string WindowState { get; set; } = nameof(System.Windows.WindowState.Normal);
    public bool MinimizeToTray { get; set; }
    public bool RunAtStartup { get; set; }
}

public static class ReceiverConfigDefaults
{
    public const int ListenPort = PhoneMicReceiver.Core.ReceiverConfig.DefaultPort;
    public const int OutputLatencyMs = 50;
    public const int BufferLengthMs = 500;
}

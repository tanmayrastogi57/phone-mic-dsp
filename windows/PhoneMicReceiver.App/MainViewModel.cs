using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;
using System.Diagnostics;
using System.Net;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Text;
using System.Windows;
using System.Windows.Data;
using System.Windows.Input;
using PhoneMicReceiver.Core;
using System.IO;
namespace PhoneMicReceiver.App;

public sealed class MainViewModel : INotifyPropertyChanged, IAsyncDisposable
{
    private readonly ReceiverEngine _engine = new();
    private readonly ObservableCollection<LogEntry> _logs = new();
    private readonly string _logDirectory;
    private readonly string _logPath;
    private readonly object _logFileLock = new();

    private string _listenPortText = ReceiverConfig.DefaultPort.ToString();
    private string _outputLatencyMsText = ReceiverConfigDefaults.OutputLatencyMs.ToString();
    private string _bufferLengthMsText = ReceiverConfigDefaults.BufferLengthMs.ToString();
    private string _lockSenderIpText = string.Empty;
    private string _statusMessage = "Ready.";
    private string _stateText = ReceiverState.Stopped.ToString();
    private string _presetSummary = "Balanced (latency 50ms, buffer 500ms).";
    private string _selectedLogFilter = "All";
    private string _packetsPerSecondText = "0";
    private string _decodeErrorsText = "0";
    private string _bufferedMsText = "0.0";
    private string _overflowsText = "0";
    private string _underrunsText = "0";
    private string _windowStateText = nameof(WindowState.Normal);
    private bool _minimizeToTray;
    private bool _runAtStartup;

    private RenderDeviceInfo? _selectedDevice;

    public MainViewModel()
    {
        Devices = new ObservableCollection<RenderDeviceInfo>();
        FilteredLogs = CollectionViewSource.GetDefaultView(_logs);
        FilteredLogs.Filter = FilterLogByLevel;
        _logs.CollectionChanged += LogsOnCollectionChanged;

        var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        _logDirectory = Path.Combine(appData, "phone-mic-dsp", "logs");
        Directory.CreateDirectory(_logDirectory);
        _logPath = Path.Combine(_logDirectory, $"receiver-{DateTime.Now:yyyyMMdd-HHmmss}.log");

        StartCommand = new AsyncRelayCommand(StartAsync, () => _engine.State is ReceiverState.Stopped or ReceiverState.Faulted);
        StopCommand = new AsyncRelayCommand(StopAsync, () => _engine.State is ReceiverState.Starting or ReceiverState.Running or ReceiverState.Faulted);
        TestToneCommand = new AsyncRelayCommand(PlayTestToneAsync, () => _engine.State == ReceiverState.Running);
        RefreshDevicesCommand = new RelayCommand(RefreshDevices);
        ApplyLowLatencyPresetCommand = new RelayCommand(() => ApplyPreset(30, 220, "Low-latency"));
        ApplyBalancedPresetCommand = new RelayCommand(() => ApplyPreset(50, 500, "Balanced"));
        ApplyStablePresetCommand = new RelayCommand(() => ApplyPreset(80, 900, "Stable"));
        CopyLogsCommand = new RelayCommand(CopyLogsToClipboard, () => _logs.Count > 0);
        OpenLogFolderCommand = new RelayCommand(OpenLogFolder);
        ResetSettingsCommand = new RelayCommand(ResetSettingsToDefaults);

        _engine.OnStats += HandleStats;
        _engine.OnStateChanged += HandleStateChanged;
        _engine.OnLog += HandleLog;

        RefreshDevices();
        LoadSettings();
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public async ValueTask DisposeAsync()
    {
        _engine.OnStats -= HandleStats;
        _engine.OnStateChanged -= HandleStateChanged;
        _engine.OnLog -= HandleLog;
        await _engine.DisposeAsync();
    }

    public ObservableCollection<RenderDeviceInfo> Devices { get; }

    public ICollectionView FilteredLogs { get; }

    public ICommand StartCommand { get; }

    public ICommand StopCommand { get; }

    public ICommand TestToneCommand { get; }

    public ICommand RefreshDevicesCommand { get; }

    public ICommand ApplyLowLatencyPresetCommand { get; }

    public ICommand ApplyBalancedPresetCommand { get; }

    public ICommand ApplyStablePresetCommand { get; }

    public ICommand CopyLogsCommand { get; }

    public ICommand OpenLogFolderCommand { get; }

    public ICommand ResetSettingsCommand { get; }

    public string ListenPortText
    {
        get => _listenPortText;
        set => SetProperty(ref _listenPortText, value);
    }

    public string OutputLatencyMsText
    {
        get => _outputLatencyMsText;
        set => SetProperty(ref _outputLatencyMsText, value);
    }

    public string BufferLengthMsText
    {
        get => _bufferLengthMsText;
        set => SetProperty(ref _bufferLengthMsText, value);
    }

    public string LockSenderIpText
    {
        get => _lockSenderIpText;
        set => SetProperty(ref _lockSenderIpText, value);
    }

    public string WindowStateText
    {
        get => _windowStateText;
        private set => SetProperty(ref _windowStateText, value);
    }

    public bool MinimizeToTray
    {
        get => _minimizeToTray;
        set
        {
            if (!SetProperty(ref _minimizeToTray, value))
            {
                return;
            }

            SaveSettings();
        }
    }

    public bool RunAtStartup
    {
        get => _runAtStartup;
        set
        {
            if (!SetProperty(ref _runAtStartup, value))
            {
                return;
            }

            SaveSettings();
        }
    }

    public RenderDeviceInfo? SelectedDevice
    {
        get => _selectedDevice;
        set => SetProperty(ref _selectedDevice, value);
    }

    public string StateText
    {
        get => _stateText;
        private set => SetProperty(ref _stateText, value);
    }

    public string PacketsPerSecondText
    {
        get => _packetsPerSecondText;
        private set => SetProperty(ref _packetsPerSecondText, value);
    }

    public string DecodeErrorsText
    {
        get => _decodeErrorsText;
        private set => SetProperty(ref _decodeErrorsText, value);
    }

    public string BufferedMsText
    {
        get => _bufferedMsText;
        private set => SetProperty(ref _bufferedMsText, value);
    }

    public string OverflowsText
    {
        get => _overflowsText;
        private set => SetProperty(ref _overflowsText, value);
    }

    public string UnderrunsText
    {
        get => _underrunsText;
        private set => SetProperty(ref _underrunsText, value);
    }

    public string AppVersionText { get; } = BuildAppVersionText();

    public string StatusMessage
    {
        get => _statusMessage;
        private set => SetProperty(ref _statusMessage, value);
    }

    public string PresetSummary
    {
        get => _presetSummary;
        private set => SetProperty(ref _presetSummary, value);
    }

    public string SelectedLogFilter
    {
        get => _selectedLogFilter;
        set
        {
            if (!SetProperty(ref _selectedLogFilter, value))
            {
                return;
            }

            FilteredLogs.Refresh();
        }
    }

    public WindowState GetInitialWindowState()
    {
        return Enum.TryParse(WindowStateText, out WindowState state) ? state : WindowState.Normal;
    }

    public void OnWindowStateChanged(WindowState state)
    {
        WindowStateText = state.ToString();
    }

    public bool ShouldMinimizeToTray() => MinimizeToTray;

    public async Task ToggleStartStopAsync()
    {
        if (_engine.State is ReceiverState.Starting or ReceiverState.Running)
        {
            await StopAsync();
            return;
        }

        await StartAsync();
    }

    public bool IsRunning() => _engine.State is ReceiverState.Starting or ReceiverState.Running;

    public void SaveSettings()
    {
        var settings = new AppSettings
        {
            ListenPort = ParseOrDefault(ListenPortText, ReceiverConfigDefaults.ListenPort),
            SelectedDeviceId = SelectedDevice?.Id,
            OutputLatencyMs = ParseOrDefault(OutputLatencyMsText, ReceiverConfigDefaults.OutputLatencyMs),
            BufferLengthMs = ParseOrDefault(BufferLengthMsText, ReceiverConfigDefaults.BufferLengthMs),
            LockSenderIp = string.IsNullOrWhiteSpace(LockSenderIpText) ? null : LockSenderIpText.Trim(),
            WindowState = WindowStateText,
            MinimizeToTray = MinimizeToTray,
            RunAtStartup = RunAtStartup
        };

        SettingsStore.Save(settings);
    }

    private void LoadSettings()
    {
        var settings = SettingsStore.LoadOrDefault();
        ListenPortText = settings.ListenPort.ToString();
        OutputLatencyMsText = settings.OutputLatencyMs.ToString();
        BufferLengthMsText = settings.BufferLengthMs.ToString();
        LockSenderIpText = settings.LockSenderIp ?? string.Empty;
        WindowStateText = settings.WindowState;
        MinimizeToTray = settings.MinimizeToTray;
        RunAtStartup = settings.RunAtStartup;

        if (!string.IsNullOrWhiteSpace(settings.SelectedDeviceId))
        {
            var selected = Devices.FirstOrDefault(d => string.Equals(d.Id, settings.SelectedDeviceId, StringComparison.OrdinalIgnoreCase));
            if (selected is not null)
            {
                SelectedDevice = selected;
            }
        }

        StatusMessage = $"Loaded settings from {SettingsStore.SettingsPath}.";
    }

    private void ResetSettingsToDefaults()
    {
        ListenPortText = ReceiverConfigDefaults.ListenPort.ToString();
        OutputLatencyMsText = ReceiverConfigDefaults.OutputLatencyMs.ToString();
        BufferLengthMsText = ReceiverConfigDefaults.BufferLengthMs.ToString();
        LockSenderIpText = string.Empty;
        WindowStateText = nameof(WindowState.Normal);
        MinimizeToTray = false;
        RunAtStartup = false;

        SelectedDevice = Devices.FirstOrDefault(d => d.MatchesPreferredSubstring)
            ?? Devices.FirstOrDefault(d => d.IsDefault)
            ?? Devices.FirstOrDefault();

        SaveSettings();
        StatusMessage = "Settings reset to defaults.";
    }

    private async Task StartAsync()
    {
        if (!TryParsePositiveInt(ListenPortText, "Listen port", out int listenPort)
            || !TryParsePositiveInt(OutputLatencyMsText, "Output latency", out int outputLatency)
            || !TryParsePositiveInt(BufferLengthMsText, "Buffer length", out int bufferLength))
        {
            return;
        }

        if (!TryParseOptionalIp(LockSenderIpText, out var lockSenderIp))
        {
            return;
        }

        var config = new ReceiverConfig
        {
            ListenPort = listenPort,
            DeviceId = SelectedDevice?.Id,
            OutputLatencyMs = outputLatency,
            BufferLengthMs = bufferLength,
            LockToSenderIp = lockSenderIp
        };

        try
        {
            await _engine.StartAsync(config);
            SaveSettings();
            StatusMessage = $"Receiver started on UDP port {listenPort}.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Start failed: {ex.Message}";
            AddLog(LogLevel.Error, ex.Message);
        }
    }

    private async Task StopAsync()
    {
        try
        {
            await _engine.StopAsync();
            SaveSettings();
            StatusMessage = "Receiver stopped.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Stop failed: {ex.Message}";
            AddLog(LogLevel.Error, ex.Message);
        }
    }

    private async Task PlayTestToneAsync()
    {
        try
        {
            await _engine.PlayTestToneAsync(1);
            StatusMessage = "Queued 1s 440Hz test tone.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Test tone failed: {ex.Message}";
            AddLog(LogLevel.Error, ex.Message);
        }
    }

    private void RefreshDevices()
    {
        var previousDeviceId = SelectedDevice?.Id;
        var devices = ReceiverEngine.GetRenderDevices();
        Devices.Clear();
        foreach (var device in devices)
        {
            Devices.Add(device);
        }

        SelectedDevice = devices.FirstOrDefault(d => d.Id == previousDeviceId)
            ?? devices.FirstOrDefault(d => d.MatchesPreferredSubstring)
            ?? devices.FirstOrDefault(d => d.IsDefault)
            ?? devices.FirstOrDefault();

        StatusMessage = devices.Count == 0
            ? "No active render devices found."
            : $"Loaded {devices.Count} render devices.";
    }

    private void HandleStats(ReceiverStats stats)
    {
        System.Windows.Application.Current.Dispatcher.Invoke(() =>
        {
            PacketsPerSecondText = stats.PacketsPerSecond.ToString();
            DecodeErrorsText = stats.DecodeErrors.ToString();
            BufferedMsText = stats.BufferedMs.ToString("F1");
            OverflowsText = stats.Overflows.ToString();
            UnderrunsText = stats.Underruns.ToString();
        });
    }

    private void HandleStateChanged(ReceiverState state)
    {
        System.Windows.Application.Current.Dispatcher.Invoke(() =>
        {
            StateText = state.ToString();
            RaiseCommandCanExecuteChanged();
        });
    }

    private void HandleLog(string message)
    {
        var level = InferLevel(message);
        AddLog(level, message);
    }

    private void AddLog(LogLevel level, string message)
    {
        System.Windows.Application.Current.Dispatcher.Invoke(() =>
        {
            var logEntry = new LogEntry(DateTimeOffset.Now, level, message);
            _logs.Add(logEntry);

            lock (_logFileLock)
            {
                File.AppendAllText(_logPath, $"{logEntry.Timestamp:O} [{logEntry.Level}] {logEntry.Message}{Environment.NewLine}");
            }
        });
    }

    private bool FilterLogByLevel(object item)
    {
        if (item is not LogEntry entry)
        {
            return false;
        }

        return SelectedLogFilter == "All"
            || entry.Level.ToString().Equals(SelectedLogFilter, StringComparison.OrdinalIgnoreCase);
    }

    private void LogsOnCollectionChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        if (_logs.Count > 1000)
        {
            _logs.RemoveAt(0);
        }

        (CopyLogsCommand as RelayCommand)?.RaiseCanExecuteChanged();
    }

    private void CopyLogsToClipboard()
    {
        var builder = new StringBuilder();
        foreach (var item in FilteredLogs.OfType<LogEntry>())
        {
            builder.AppendLine($"{item.TimestampDisplay} [{item.Level}] {item.Message}");
        }

        Clipboard.SetText(builder.ToString());
        StatusMessage = "Copied filtered logs to clipboard.";
    }

    private void OpenLogFolder()
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = _logDirectory,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            StatusMessage = $"Could not open log folder: {ex.Message}";
            AddLog(LogLevel.Warn, ex.Message);
        }
    }

    private void ApplyPreset(int latencyMs, int bufferMs, string presetName)
    {
        OutputLatencyMsText = latencyMs.ToString();
        BufferLengthMsText = bufferMs.ToString();
        PresetSummary = $"{presetName} preset applied (latency {latencyMs}ms, buffer {bufferMs}ms).";
    }

    private void RaiseCommandCanExecuteChanged()
    {
        (StartCommand as AsyncRelayCommand)?.RaiseCanExecuteChanged();
        (StopCommand as AsyncRelayCommand)?.RaiseCanExecuteChanged();
        (TestToneCommand as AsyncRelayCommand)?.RaiseCanExecuteChanged();
    }

    private static bool TryParsePositiveInt(string value, string fieldName, out int result)
    {
        if (int.TryParse(value, out result) && result > 0)
        {
            return true;
        }

        MessageBox.Show($"{fieldName} must be a positive integer.", "Validation", MessageBoxButton.OK, MessageBoxImage.Warning);
        result = 0;
        return false;
    }

    private static bool TryParseOptionalIp(string value, out IPAddress? result)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            result = null;
            return true;
        }

        if (IPAddress.TryParse(value.Trim(), out var parsed))
        {
            result = parsed;
            return true;
        }

        MessageBox.Show("Lock sender IP must be a valid IPv4 or IPv6 address.", "Validation", MessageBoxButton.OK, MessageBoxImage.Warning);
        result = null;
        return false;
    }

    private static int ParseOrDefault(string value, int fallback)
    {
        return int.TryParse(value, out var parsed) && parsed > 0 ? parsed : fallback;
    }

    private static LogLevel InferLevel(string message)
    {
        if (message.Contains("fault", StringComparison.OrdinalIgnoreCase)
            || message.Contains("error", StringComparison.OrdinalIgnoreCase)
            || message.Contains("failed", StringComparison.OrdinalIgnoreCase))
        {
            return LogLevel.Error;
        }

        if (message.Contains("underrun", StringComparison.OrdinalIgnoreCase)
            || message.Contains("overflow", StringComparison.OrdinalIgnoreCase)
            || message.Contains("warn", StringComparison.OrdinalIgnoreCase)
            || message.Contains("fallback", StringComparison.OrdinalIgnoreCase))
        {
            return LogLevel.Warn;
        }

        return LogLevel.Info;
    }

    private static string BuildAppVersionText()
    {
        var assembly = Assembly.GetExecutingAssembly();
        var informational = assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        if (!string.IsNullOrWhiteSpace(informational))
        {
            return $"Version {informational}";
        }

        var version = assembly.GetName().Version;
        return version is null ? "Version unknown" : $"Version {version}";
    }

    private bool SetProperty<T>(ref T storage, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(storage, value))
        {
            return false;
        }

        storage = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        return true;
    }
}

public enum LogLevel
{
    Info,
    Warn,
    Error
}

public sealed record LogEntry(DateTimeOffset Timestamp, LogLevel Level, string Message)
{
    public string TimestampDisplay => Timestamp.ToString("HH:mm:ss.fff");
}

public sealed class RelayCommand : ICommand
{
    private readonly Action _execute;
    private readonly Func<bool>? _canExecute;

    public RelayCommand(Action execute, Func<bool>? canExecute = null)
    {
        _execute = execute;
        _canExecute = canExecute;
    }

    public event EventHandler? CanExecuteChanged;

    public bool CanExecute(object? parameter) => _canExecute?.Invoke() ?? true;

    public void Execute(object? parameter) => _execute();

    public void RaiseCanExecuteChanged() => CanExecuteChanged?.Invoke(this, EventArgs.Empty);
}

public sealed class AsyncRelayCommand : ICommand
{
    private readonly Func<Task> _executeAsync;
    private readonly Func<bool>? _canExecute;
    private bool _isExecuting;

    public AsyncRelayCommand(Func<Task> executeAsync, Func<bool>? canExecute = null)
    {
        _executeAsync = executeAsync;
        _canExecute = canExecute;
    }

    public event EventHandler? CanExecuteChanged;

    public bool CanExecute(object? parameter)
    {
        return !_isExecuting && (_canExecute?.Invoke() ?? true);
    }

    public async void Execute(object? parameter)
    {
        if (!CanExecute(parameter))
        {
            return;
        }

        try
        {
            _isExecuting = true;
            RaiseCanExecuteChanged();
            await _executeAsync();
        }
        finally
        {
            _isExecuting = false;
            RaiseCanExecuteChanged();
        }
    }

    public void RaiseCanExecuteChanged() => CanExecuteChanged?.Invoke(this, EventArgs.Empty);
}

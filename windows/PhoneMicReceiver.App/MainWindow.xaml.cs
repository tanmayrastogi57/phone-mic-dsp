using System.Text.Json;
using System.Windows;
using PhoneMicReceiver.Core;

namespace PhoneMicReceiver.App;

public partial class MainWindow : Window
{
    private const string PreferredDeviceSubstring = ReceiverConfig.DefaultDeviceSubstring;
    private readonly string _settingsPath;

    public MainWindow()
    {
        InitializeComponent();

        var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        var settingsDirectory = Path.Combine(appData, "phone-mic-dsp");
        Directory.CreateDirectory(settingsDirectory);
        _settingsPath = Path.Combine(settingsDirectory, "app-device-selection.json");

        RefreshDevices();
    }

    private void RefreshDevices_OnClick(object sender, RoutedEventArgs e)
    {
        RefreshDevices();
    }

    private void DeviceComboBox_OnSelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (DeviceComboBox.SelectedValue is string deviceId)
        {
            SaveSettings(new AppSelectionSettings(deviceId));
            StatusTextBlock.Text = "Selected output device saved.";
        }
    }

    private void RefreshDevices()
    {
        var devices = ReceiverEngine.GetRenderDevices(PreferredDeviceSubstring);
        DeviceComboBox.ItemsSource = devices;

        if (devices.Count == 0)
        {
            StatusTextBlock.Text = "No active render devices found.";
            return;
        }

        var persistedDeviceId = LoadSettings()?.SelectedDeviceId;
        var selected = devices.FirstOrDefault(d => d.Id == persistedDeviceId)
            ?? devices.FirstOrDefault(d => d.MatchesPreferredSubstring)
            ?? devices.FirstOrDefault(d => d.IsDefault)
            ?? devices[0];

        DeviceComboBox.SelectedValue = selected.Id;
        var reason = selected.Id == persistedDeviceId
            ? "restored from previous selection"
            : selected.MatchesPreferredSubstring
                ? "matched preferred substring"
                : selected.IsDefault
                    ? "default render device fallback"
                    : "first available device";
        StatusTextBlock.Text = $"Loaded {devices.Count} devices. Active selection: {selected.FriendlyName} ({reason}).";
    }

    private AppSelectionSettings? LoadSettings()
    {
        if (!File.Exists(_settingsPath))
        {
            return null;
        }

        try
        {
            var json = File.ReadAllText(_settingsPath);
            return JsonSerializer.Deserialize<AppSelectionSettings>(json);
        }
        catch
        {
            return null;
        }
    }

    private void SaveSettings(AppSelectionSettings settings)
    {
        var json = JsonSerializer.Serialize(settings);
        File.WriteAllText(_settingsPath, json);
    }

    private sealed record AppSelectionSettings(string SelectedDeviceId);
}

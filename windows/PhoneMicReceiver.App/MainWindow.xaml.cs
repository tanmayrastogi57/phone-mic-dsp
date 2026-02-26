using System.Drawing;
using System.Windows;
using Forms = System.Windows.Forms;

namespace PhoneMicReceiver.App;

public partial class MainWindow : Window
{
    private readonly Forms.NotifyIcon _notifyIcon;
    private bool _allowClose;

    public MainWindow()
    {
        InitializeComponent();
        _notifyIcon = BuildNotifyIcon();

        if (DataContext is MainViewModel vm)
        {
            vm.PropertyChanged += ViewModelOnPropertyChanged;
            WindowState = vm.GetInitialWindowState();
            vm.OnWindowStateChanged(WindowState);
            SyncRunAtStartup(vm.RunAtStartup);
        }

        UpdateTrayState();
    }

    protected override void OnStateChanged(EventArgs e)
    {
        base.OnStateChanged(e);

        if (DataContext is MainViewModel vm)
        {
            vm.OnWindowStateChanged(WindowState);

            if (WindowState == WindowState.Minimized && vm.ShouldMinimizeToTray())
            {
                HideToTray();
                return;
            }
        }

        UpdateTrayState();
    }

    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        if (!_allowClose && DataContext is MainViewModel vm && vm.ShouldMinimizeToTray())
        {
            e.Cancel = true;
            HideToTray();
            return;
        }

        base.OnClosing(e);
    }

    protected override async void OnClosed(EventArgs e)
    {
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();

        if (DataContext is MainViewModel vm)
        {
            vm.PropertyChanged -= ViewModelOnPropertyChanged;
            vm.SaveSettings();
            await vm.DisposeAsync();
        }

        base.OnClosed(e);
    }

    private Forms.NotifyIcon BuildNotifyIcon()
    {
        var menu = new Forms.ContextMenuStrip();
        var showHideItem = new Forms.ToolStripMenuItem("Hide", null, (_, _) => ToggleWindowVisibility());
        var startStopItem = new Forms.ToolStripMenuItem("Start", null, async (_, _) => await ToggleStartStopAsync());
        var exitItem = new Forms.ToolStripMenuItem("Exit", null, (_, _) => ExitFromTray());
        menu.Items.Add(showHideItem);
        menu.Items.Add(startStopItem);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(exitItem);

        var notifyIcon = new Forms.NotifyIcon
        {
            Text = "Phone Mic Receiver",
            Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath ?? string.Empty) ?? SystemIcons.Application,
            ContextMenuStrip = menu,
            Visible = false
        };

        notifyIcon.DoubleClick += (_, _) => RestoreFromTray();
        return notifyIcon;
    }

    private void ViewModelOnPropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (sender is not MainViewModel vm)
        {
            return;
        }

        if (e.PropertyName == nameof(MainViewModel.RunAtStartup))
        {
            SyncRunAtStartup(vm.RunAtStartup);
        }

        if (e.PropertyName is nameof(MainViewModel.MinimizeToTray) or nameof(MainViewModel.StateText))
        {
            UpdateTrayState();
        }
    }

    private void SyncRunAtStartup(bool enabled)
    {
        StartupManager.SetRunAtStartup(enabled);
    }

    private void ToggleWindowVisibility()
    {
        if (IsVisible && WindowState != WindowState.Minimized)
        {
            HideToTray();
            return;
        }

        RestoreFromTray();
    }

    private async Task ToggleStartStopAsync()
    {
        if (DataContext is MainViewModel vm)
        {
            await vm.ToggleStartStopAsync();
            UpdateTrayState();
        }
    }

    private void ExitFromTray()
    {
        _allowClose = true;
        Close();
    }

    private void HideToTray()
    {
        Hide();
        UpdateTrayState();
    }

    private void RestoreFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
        UpdateTrayState();
    }

    private void UpdateTrayState()
    {
        if (DataContext is not MainViewModel vm)
        {
            return;
        }

        var trayEnabled = vm.ShouldMinimizeToTray() || !IsVisible || WindowState == WindowState.Minimized;
        _notifyIcon.Visible = trayEnabled;

        if (_notifyIcon.ContextMenuStrip?.Items[0] is Forms.ToolStripMenuItem showHideItem)
        {
            showHideItem.Text = IsVisible && WindowState != WindowState.Minimized ? "Hide" : "Show";
        }

        if (_notifyIcon.ContextMenuStrip?.Items[1] is Forms.ToolStripMenuItem startStopItem)
        {
            startStopItem.Text = vm.IsRunning() ? "Stop" : "Start";
        }
    }
}

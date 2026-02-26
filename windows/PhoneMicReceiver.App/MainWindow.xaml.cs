using System.Windows;

namespace PhoneMicReceiver.App;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();

        if (DataContext is MainViewModel vm)
        {
            WindowState = vm.GetInitialWindowState();
            vm.OnWindowStateChanged(WindowState);
        }
    }

    protected override void OnStateChanged(EventArgs e)
    {
        base.OnStateChanged(e);

        if (DataContext is MainViewModel vm)
        {
            vm.OnWindowStateChanged(WindowState);
        }
    }

    protected override async void OnClosed(EventArgs e)
    {
        if (DataContext is MainViewModel vm)
        {
            vm.SaveSettings();
            await vm.DisposeAsync();
        }

        base.OnClosed(e);
    }
}

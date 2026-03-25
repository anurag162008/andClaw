using System.Windows;
using AndClaw.Windows.ViewModels;

namespace AndClaw.Windows.Views;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        DataContext = new MainViewModel();
    }
}

namespace AndClaw.Windows.Models;

public sealed class SetupState
{
    public bool RootfsInstalled { get; set; }
    public bool NodeInstalled { get; set; }
    public bool OpenClawInstalled { get; set; }
    public bool ChromiumInstalled { get; set; }
    public int ProgressPercent { get; set; }
    public bool IsComplete => RootfsInstalled && NodeInstalled && OpenClawInstalled && ChromiumInstalled;
}

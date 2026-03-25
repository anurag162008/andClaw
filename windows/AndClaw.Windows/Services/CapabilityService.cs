namespace AndClaw.Windows.Services;

public sealed class CapabilityService
{
    public bool IsWindows => OperatingSystem.IsWindows();

    public bool SupportsWhatsAppNativePairing => false;
    public bool SupportsBootReceiver => false;

    public string WhatsAppReplacement => "Use WhatsApp Cloud API webhook bridge on Windows.";
    public string BootReplacement => "Use Startup Task / Task Scheduler for auto-start on boot.";
}

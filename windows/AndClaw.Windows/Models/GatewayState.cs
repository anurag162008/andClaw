namespace AndClaw.Windows.Models;

public enum GatewayStatus
{
    Stopped,
    Starting,
    Running,
    Error,
}

public sealed class GatewayState
{
    public GatewayStatus Status { get; set; } = GatewayStatus.Stopped;
    public string? ErrorMessage { get; set; }
    public bool DashboardReady => Status == GatewayStatus.Running;
}

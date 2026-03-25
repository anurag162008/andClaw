namespace AndClaw.Windows.Models;

public sealed class SessionLogEntry
{
    public DateTime TimestampUtc { get; set; } = DateTime.UtcNow;
    public string Category { get; set; } = "runtime";
    public string Message { get; set; } = string.Empty;
}

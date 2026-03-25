using System.IO.Compression;
using System.Text;
using System.Text.Json;
using AndClaw.Windows.Models;

namespace AndClaw.Windows.Services;

public sealed class BugReportService
{
    public string CreateBugReport(string destinationDirectory, AppConfig config, IEnumerable<SessionLogEntry> logs, string gatewayStatus)
    {
        Directory.CreateDirectory(destinationDirectory);
        var filePath = Path.Combine(destinationDirectory, $"andclaw-bugreport-{DateTime.UtcNow:yyyyMMdd-HHmmss}.zip");

        using var archive = ZipFile.Open(filePath, ZipArchiveMode.Create);

        var configEntry = archive.CreateEntry("config.json");
        using (var writer = new StreamWriter(configEntry.Open(), Encoding.UTF8))
        {
            writer.Write(JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true }));
        }

        var logsEntry = archive.CreateEntry("session_logs.json");
        using (var writer = new StreamWriter(logsEntry.Open(), Encoding.UTF8))
        {
            writer.Write(JsonSerializer.Serialize(logs, new JsonSerializerOptions { WriteIndented = true }));
        }

        var statusEntry = archive.CreateEntry("gateway_status.txt");
        using (var writer = new StreamWriter(statusEntry.Open(), Encoding.UTF8))
        {
            writer.WriteLine(gatewayStatus);
            writer.WriteLine($"GeneratedUTC: {DateTime.UtcNow:O}");
        }

        return filePath;
    }
}

using System.Text.Json;
using AndClaw.Windows.Models;

namespace AndClaw.Windows.Services;

public sealed class LocalStorageService
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly string _baseDir;

    public LocalStorageService()
    {
        _baseDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "andClaw");
        Directory.CreateDirectory(_baseDir);
    }

    public string BaseDirectory => _baseDir;

    public AppConfig LoadConfig()
    {
        var path = ConfigPath();
        if (!File.Exists(path))
        {
            return new AppConfig();
        }

        var text = File.ReadAllText(path);
        return JsonSerializer.Deserialize<AppConfig>(text) ?? new AppConfig();
    }

    public void SaveConfig(AppConfig config)
    {
        File.WriteAllText(ConfigPath(), JsonSerializer.Serialize(config, JsonOptions));
    }

    public void SaveSessionLogs(IEnumerable<SessionLogEntry> entries)
    {
        File.WriteAllText(SessionLogPath(), JsonSerializer.Serialize(entries, JsonOptions));
    }

    public List<SessionLogEntry> LoadSessionLogs()
    {
        var path = SessionLogPath();
        if (!File.Exists(path))
        {
            return [];
        }

        return JsonSerializer.Deserialize<List<SessionLogEntry>>(File.ReadAllText(path)) ?? [];
    }

    private string ConfigPath() => Path.Combine(_baseDir, "config.json");
    private string SessionLogPath() => Path.Combine(_baseDir, "session_logs.json");
}

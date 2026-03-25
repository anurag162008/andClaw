namespace AndClaw.Windows.Models;

public sealed class AppConfig
{
    public string ApiProvider { get; set; } = "openrouter";
    public string ApiKey { get; set; } = string.Empty;
    public string OpenAiCompatibleBaseUrl { get; set; } = "https://api.openai.com/v1";
    public string OpenAiCompatibleModelId { get; set; } = string.Empty;
    public string SelectedModel { get; set; } = "openai/gpt-4.1-mini";
    public string SelectedModelProvider { get; set; } = "openrouter";
    public bool AutoStartOnBoot { get; set; } = true;
    public bool ChargeOnlyMode { get; set; }
    public bool MemorySearchEnabled { get; set; } = true;
    public string MemorySearchProvider { get; set; } = "auto";
    public string MemorySearchApiKey { get; set; } = string.Empty;
    public ChannelConfig ChannelConfig { get; set; } = new();
}

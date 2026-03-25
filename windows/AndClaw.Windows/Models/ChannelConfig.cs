namespace AndClaw.Windows.Models;

public sealed class ChannelConfig
{
    public bool WhatsAppEnabled { get; set; } = true;
    public bool TelegramEnabled { get; set; }
    public bool DiscordEnabled { get; set; }
    public string TelegramBotToken { get; set; } = string.Empty;
    public string DiscordBotToken { get; set; } = string.Empty;
}

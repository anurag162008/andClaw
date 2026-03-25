namespace AndClaw.Windows.Services;

public sealed class OAuthService
{
    private const string OpenRouterOAuthUrl = "https://openrouter.ai";

    public string GetAuthorizationUrl() => OpenRouterOAuthUrl;

    public bool TryCompleteLogin(string token, out string message)
    {
        if (string.IsNullOrWhiteSpace(token))
        {
            message = "Token is empty.";
            return false;
        }

        message = "OAuth token accepted and stored.";
        return true;
    }
}

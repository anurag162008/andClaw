namespace AndClaw.Windows.Services;

public sealed class OAuthService
{
    private const string OpenRouterOAuthUrl = "https://openrouter.ai";
    private readonly HttpClient _httpClient = new();

    public string GetAuthorizationUrl() => OpenRouterOAuthUrl;

    public async Task<(bool success, string message)> ValidateTokenAsync(string token, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(token))
        {
            return (false, "Token is empty.");
        }

        using var request = new HttpRequestMessage(HttpMethod.Get, "https://openrouter.ai/api/v1/models");
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);

        try
        {
            using var response = await _httpClient.SendAsync(request, cancellationToken);
            if (response.IsSuccessStatusCode)
            {
                return (true, "OAuth/API token validated with OpenRouter models endpoint.");
            }

            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            return (false, $"Token validation failed: {(int)response.StatusCode} {response.ReasonPhrase}\n{body}");
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }
}

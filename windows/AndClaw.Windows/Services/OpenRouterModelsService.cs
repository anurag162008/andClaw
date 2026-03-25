namespace AndClaw.Windows.Services;

public sealed class OpenRouterModelsService
{
    private readonly HttpClient _httpClient = new();

    public async Task<(bool success, List<string> models, string message)> FetchModelsAsync(string apiKey, CancellationToken cancellationToken = default)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, "https://openrouter.ai/api/v1/models");
            if (!string.IsNullOrWhiteSpace(apiKey))
            {
                request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", apiKey);
            }

            using var response = await _httpClient.SendAsync(request, cancellationToken);
            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                return (false, [], $"OpenRouter API error: {(int)response.StatusCode} {response.ReasonPhrase}\n{body}");
            }

            using var doc = JsonDocument.Parse(body);
            var list = new List<string>();
            if (doc.RootElement.TryGetProperty("data", out var data) && data.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in data.EnumerateArray())
                {
                    if (item.TryGetProperty("id", out var id))
                    {
                        var modelId = id.GetString();
                        if (!string.IsNullOrWhiteSpace(modelId))
                        {
                            list.Add(modelId);
                        }
                    }
                }
            }

            list.Sort(StringComparer.OrdinalIgnoreCase);
            return (true, list, $"Loaded {list.Count} models from OpenRouter.");
        }
        catch (Exception ex)
        {
            return (false, [], ex.Message);
        }
    }
}

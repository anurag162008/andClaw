using System.Diagnostics;
using AndClaw.Windows.Models;

namespace AndClaw.Windows.Services;

public sealed class WslGatewayService
{
    private Process? _process;

    public GatewayState State { get; } = new();

    public bool IsRunning => _process is { HasExited: false };

    public async Task<string> StartAsync(AppConfig config, CancellationToken cancellationToken = default)
    {
        if (IsRunning)
        {
            return "Gateway already running.";
        }

        State.Status = GatewayStatus.Starting;
        State.ErrorMessage = null;

        try
        {
            var providerArg = string.IsNullOrWhiteSpace(config.ApiProvider) ? "openrouter" : config.ApiProvider;
            var modelArg = string.IsNullOrWhiteSpace(config.SelectedModel) ? "openai/gpt-4.1-mini" : config.SelectedModel;

            var startInfo = new ProcessStartInfo
            {
                FileName = "wsl.exe",
                Arguments = $"bash -lc \"OPENCLAW_PROVIDER={providerArg} OPENCLAW_MODEL={modelArg} openclaw\"",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };

            _process = Process.Start(startInfo);

            if (_process is null)
            {
                State.Status = GatewayStatus.Error;
                State.ErrorMessage = "Failed to start OpenClaw process.";
                return State.ErrorMessage;
            }

            await Task.Delay(400, cancellationToken);
            State.Status = GatewayStatus.Running;
            return "Gateway started in WSL.";
        }
        catch (Exception ex)
        {
            State.Status = GatewayStatus.Error;
            State.ErrorMessage = ex.Message;
            return $"Failed to start gateway: {ex.Message}";
        }
    }

    public string Stop()
    {
        if (!IsRunning)
        {
            State.Status = GatewayStatus.Stopped;
            return "Gateway is not running.";
        }

        _process!.Kill(entireProcessTree: true);
        _process = null;
        State.Status = GatewayStatus.Stopped;
        return "Gateway stopped.";
    }

    public async Task<string> RestartAsync(AppConfig config, CancellationToken cancellationToken = default)
    {
        Stop();
        return await StartAsync(config, cancellationToken);
    }

    public string DashboardUrl() => "http://127.0.0.1:3000";
}

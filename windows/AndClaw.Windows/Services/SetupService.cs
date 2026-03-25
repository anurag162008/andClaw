using System.Diagnostics;
using AndClaw.Windows.Models;

namespace AndClaw.Windows.Services;

public sealed class SetupService
{
    public async Task<SetupState> RunFullSetupAsync(Action<string>? notify = null, CancellationToken cancellationToken = default)
    {
        var state = new SetupState();

        notify?.Invoke("Checking WSL Ubuntu rootfs...");
        state.RootfsInstalled = await CheckCommandAsync("wsl.exe", "-l -q", cancellationToken);
        state.ProgressPercent = 25;

        notify?.Invoke("Checking Node.js in WSL...");
        state.NodeInstalled = await CheckCommandAsync("wsl.exe", "bash -lc \"command -v node\"", cancellationToken);
        state.ProgressPercent = 50;

        notify?.Invoke("Checking OpenClaw in WSL...");
        state.OpenClawInstalled = await CheckCommandAsync("wsl.exe", "bash -lc \"command -v openclaw\"", cancellationToken);
        state.ProgressPercent = 75;

        notify?.Invoke("Checking headless Chromium in WSL...");
        state.ChromiumInstalled = await CheckCommandAsync("wsl.exe", "bash -lc \"command -v chromium || command -v chromium-browser || test -d ~/.cache/ms-playwright\"", cancellationToken);
        state.ProgressPercent = 100;

        notify?.Invoke(state.IsComplete ? "Setup verification complete." : "Setup incomplete: use Install Runtime to auto-provision missing dependencies.");
        return state;
    }

    public async Task<(bool success, string output)> InstallRuntimeDependenciesAsync(Action<string>? notify = null, CancellationToken cancellationToken = default)
    {
        notify?.Invoke("Provisioning WSL runtime (Node, OpenClaw, Chromium dependencies)...");

        var script = @"
set -e
if command -v sudo >/dev/null 2>&1; then SUDO='sudo'; else SUDO=''; fi
$SUDO apt-get update
$SUDO apt-get install -y curl ca-certificates git jq build-essential python3
if ! command -v node >/dev/null 2>&1; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | $SUDO bash -
  $SUDO apt-get install -y nodejs
fi
npm install -g openclaw@latest
npx --yes playwright install --with-deps chromium
command -v openclaw
";

        return await RunWslScriptAsync(script, cancellationToken);
    }

    private static async Task<(bool success, string output)> RunWslScriptAsync(string script, CancellationToken cancellationToken)
    {
        var escaped = script.Replace("\"", "\\\"").Replace("\r", "").Replace("\n", "; ");
        var args = $"bash -lc \"{escaped}\"";

        var startInfo = new ProcessStartInfo
        {
            FileName = "wsl.exe",
            Arguments = args,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };

        try
        {
            using var process = Process.Start(startInfo);
            if (process is null)
            {
                return (false, "Failed to start WSL process.");
            }

            var stdout = await process.StandardOutput.ReadToEndAsync(cancellationToken);
            var stderr = await process.StandardError.ReadToEndAsync(cancellationToken);
            await process.WaitForExitAsync(cancellationToken);

            var output = string.IsNullOrWhiteSpace(stderr) ? stdout : $"{stdout}\n{stderr}";
            return (process.ExitCode == 0, output);
        }
        catch (Exception ex)
        {
            return (false, ex.ToString());
        }
    }

    private static async Task<bool> CheckCommandAsync(string fileName, string arguments, CancellationToken cancellationToken)
    {
        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = arguments,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };

            using var process = Process.Start(startInfo);
            if (process is null)
            {
                return false;
            }

            await process.WaitForExitAsync(cancellationToken);
            return process.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }
}

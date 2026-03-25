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

        notify?.Invoke("Checking Playwright Chromium in WSL...");
        state.ChromiumInstalled = await CheckCommandAsync("wsl.exe", "bash -lc \"test -x ~/.cache/ms-playwright/chromium-*/chrome-linux/chrome || command -v chromium || command -v chromium-browser\"", cancellationToken);
        state.ProgressPercent = 100;

        notify?.Invoke(state.IsComplete ? "Setup verification complete." : "Setup incomplete: some runtime dependencies are missing.");
        return state;
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

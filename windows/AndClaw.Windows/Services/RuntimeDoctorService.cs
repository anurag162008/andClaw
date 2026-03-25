using System.Diagnostics;
using System.Text;

namespace AndClaw.Windows.Services;

public sealed class RuntimeDoctorService
{
    public async Task<(bool Success, string Report)> RunChecksAsync(CancellationToken cancellationToken = default)
    {
        var report = new StringBuilder();
        var ok = true;

        if (!OperatingSystem.IsWindows())
        {
            ok = false;
            report.AppendLine("Host OS is not Windows. Windows app should be run on Windows host.");
            return (ok, report.ToString());
        }

        var wslCheck = await RunCommand("wsl.exe", "--status", cancellationToken);
        ok &= wslCheck.exitCode == 0;
        report.AppendLine($"WSL status check: {(wslCheck.exitCode == 0 ? "OK" : "FAIL")}");
        report.AppendLine(wslCheck.output);

        var openclawCheck = await RunCommand("wsl.exe", "bash -lc \"command -v openclaw\"", cancellationToken);
        ok &= openclawCheck.exitCode == 0;
        report.AppendLine($"OpenClaw check: {(openclawCheck.exitCode == 0 ? "OK" : "FAIL")}");
        report.AppendLine(openclawCheck.output);

        return (ok, report.ToString());
    }

    private static async Task<(int exitCode, string output)> RunCommand(string fileName, string arguments, CancellationToken cancellationToken)
    {
        var psi = new ProcessStartInfo
        {
            FileName = fileName,
            Arguments = arguments,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };

        using var process = Process.Start(psi);
        if (process is null)
        {
            return (-1, "Failed to start process.");
        }

        var output = await process.StandardOutput.ReadToEndAsync(cancellationToken);
        var error = await process.StandardError.ReadToEndAsync(cancellationToken);
        await process.WaitForExitAsync(cancellationToken);

        return (process.ExitCode, string.IsNullOrWhiteSpace(error) ? output : $"{output}\n{error}");
    }
}

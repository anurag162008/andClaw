namespace AndClaw.Windows.Services;

public sealed class OsSupportService
{
    public string GetSupportNote()
    {
        var v = Environment.OSVersion.Version;
        if (v.Major >= 10)
        {
            return "OS support: Windows 10/11 (full target).";
        }

        if (v.Major == 6 && (v.Minor == 2 || v.Minor == 3 || v.Minor == 1))
        {
            return "OS support: Windows 7/8/8.1 detected. Use legacy .NET Desktop Runtime + WSL alternatives may be limited.";
        }

        return "OS support: Unverified Windows version. Run Runtime Doctor and validate dependencies manually.";
    }
}

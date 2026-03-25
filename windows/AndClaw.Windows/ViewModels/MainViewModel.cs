using System.Collections.ObjectModel;
using AndClaw.Windows.Models;
using AndClaw.Windows.Services;

namespace AndClaw.Windows.ViewModels;

public sealed class MainViewModel : ObservableObject
{
    private readonly WslGatewayService _gatewayService = new();
    private readonly SetupService _setupService = new();
    private readonly OAuthService _oauthService = new();
    private readonly BugReportService _bugReportService = new();
    private readonly TransferService _transferService = new();
    private readonly LocalStorageService _storage = new();
    private readonly RuntimeDoctorService _runtimeDoctorService = new();
    private readonly CapabilityService _capabilityService = new();
    private readonly OsSupportService _osSupportService = new();
    private readonly OpenRouterModelsService _modelsService = new();
    private readonly WindowsStartupService _startupService = new();

    private string _status = "Ready";
    private bool _setupInProgress;
    private int _setupProgress;
    private bool _rootfsInstalled;
    private bool _nodeInstalled;
    private bool _openClawInstalled;
    private bool _chromiumInstalled;

    private string _apiProvider = "openrouter";
    private string _selectedModel = "openai/gpt-4.1-mini";
    private string _apiKey = string.Empty;
    private string _oauthTokenInput = string.Empty;

    private bool _autoStartOnBoot = true;
    private bool _chargeOnlyMode;
    private bool _memorySearchEnabled = true;
    private string _memorySearchProvider = "auto";
    private string _memorySearchApiKey = string.Empty;

    private bool _whatsAppEnabled = true;
    private bool _telegramEnabled;
    private bool _discordEnabled;
    private string _telegramBotToken = string.Empty;
    private string _discordBotToken = string.Empty;

    private string _bugReportPath = string.Empty;
    private string _transferArtifactPath = string.Empty;
    private string _transferImportPath = string.Empty;
    private string _runtimeDoctorReport = string.Empty;
    private string _runtimeInstallOutput = string.Empty;
    private string _availableModelsText = string.Empty;

    public MainViewModel()
    {
        LoadPersistedState();

        SessionLogs = new ObservableCollection<SessionLogEntry>(_storage.LoadSessionLogs());

        RunSetupCommand = new AsyncRelayCommand(RunSetupAsync, () => !SetupInProgress);
        InstallRuntimeCommand = new AsyncRelayCommand(InstallRuntimeAsync);
        RunRuntimeDoctorCommand = new AsyncRelayCommand(RunRuntimeDoctorAsync);
        RefreshModelsCommand = new AsyncRelayCommand(RefreshModelsAsync);

        SaveOnboardingCommand = new RelayCommand(SaveOnboarding);
        OpenOAuthCommand = new RelayCommand(OpenOAuth);
        CompleteOAuthCommand = new AsyncRelayCommand(CompleteOAuthAsync);

        StartGatewayCommand = new AsyncRelayCommand(StartGatewayAsync);
        StopGatewayCommand = new RelayCommand(StopGateway);
        RestartGatewayCommand = new AsyncRelayCommand(RestartGatewayAsync);
        OpenDashboardCommand = new RelayCommand(OpenDashboard);

        SaveSettingsCommand = new RelayCommand(SaveSettings);
        GenerateBugReportCommand = new RelayCommand(GenerateBugReport);
        ExportTransferCommand = new RelayCommand(ExportTransfer);
        ImportTransferCommand = new RelayCommand(ImportTransfer);
    }

    public ObservableCollection<SessionLogEntry> SessionLogs { get; }

    public AsyncRelayCommand RunSetupCommand { get; }
    public AsyncRelayCommand InstallRuntimeCommand { get; }
    public AsyncRelayCommand RunRuntimeDoctorCommand { get; }
    public AsyncRelayCommand RefreshModelsCommand { get; }
    public RelayCommand SaveOnboardingCommand { get; }
    public RelayCommand OpenOAuthCommand { get; }
    public AsyncRelayCommand CompleteOAuthCommand { get; }
    public AsyncRelayCommand StartGatewayCommand { get; }
    public RelayCommand StopGatewayCommand { get; }
    public AsyncRelayCommand RestartGatewayCommand { get; }
    public RelayCommand OpenDashboardCommand { get; }
    public RelayCommand SaveSettingsCommand { get; }
    public RelayCommand GenerateBugReportCommand { get; }
    public RelayCommand ExportTransferCommand { get; }
    public RelayCommand ImportTransferCommand { get; }

    public string Status
    {
        get => _status;
        set => SetProperty(ref _status, value);
    }

    public bool SetupInProgress
    {
        get => _setupInProgress;
        set
        {
            if (SetProperty(ref _setupInProgress, value))
            {
                RunSetupCommand.RaiseCanExecuteChanged();
            }
        }
    }

    public int SetupProgress { get => _setupProgress; set => SetProperty(ref _setupProgress, value); }
    public bool RootfsInstalled { get => _rootfsInstalled; set => SetProperty(ref _rootfsInstalled, value); }
    public bool NodeInstalled { get => _nodeInstalled; set => SetProperty(ref _nodeInstalled, value); }
    public bool OpenClawInstalled { get => _openClawInstalled; set => SetProperty(ref _openClawInstalled, value); }
    public bool ChromiumInstalled { get => _chromiumInstalled; set => SetProperty(ref _chromiumInstalled, value); }

    public string ApiProvider { get => _apiProvider; set => SetProperty(ref _apiProvider, value); }
    public string SelectedModel { get => _selectedModel; set => SetProperty(ref _selectedModel, value); }
    public string ApiKey { get => _apiKey; set => SetProperty(ref _apiKey, value); }
    public string OauthTokenInput { get => _oauthTokenInput; set => SetProperty(ref _oauthTokenInput, value); }

    public bool AutoStartOnBoot { get => _autoStartOnBoot; set => SetProperty(ref _autoStartOnBoot, value); }
    public bool ChargeOnlyMode { get => _chargeOnlyMode; set => SetProperty(ref _chargeOnlyMode, value); }
    public bool MemorySearchEnabled { get => _memorySearchEnabled; set => SetProperty(ref _memorySearchEnabled, value); }
    public string MemorySearchProvider { get => _memorySearchProvider; set => SetProperty(ref _memorySearchProvider, value); }
    public string MemorySearchApiKey { get => _memorySearchApiKey; set => SetProperty(ref _memorySearchApiKey, value); }

    public bool WhatsAppEnabled { get => _whatsAppEnabled; set => SetProperty(ref _whatsAppEnabled, value); }
    public bool TelegramEnabled { get => _telegramEnabled; set => SetProperty(ref _telegramEnabled, value); }
    public bool DiscordEnabled { get => _discordEnabled; set => SetProperty(ref _discordEnabled, value); }
    public string TelegramBotToken { get => _telegramBotToken; set => SetProperty(ref _telegramBotToken, value); }
    public string DiscordBotToken { get => _discordBotToken; set => SetProperty(ref _discordBotToken, value); }

    public string BugReportPath { get => _bugReportPath; set => SetProperty(ref _bugReportPath, value); }
    public string TransferArtifactPath { get => _transferArtifactPath; set => SetProperty(ref _transferArtifactPath, value); }
    public string TransferImportPath { get => _transferImportPath; set => SetProperty(ref _transferImportPath, value); }
    public string RuntimeDoctorReport { get => _runtimeDoctorReport; set => SetProperty(ref _runtimeDoctorReport, value); }
    public string RuntimeInstallOutput { get => _runtimeInstallOutput; set => SetProperty(ref _runtimeInstallOutput, value); }
    public string AvailableModelsText { get => _availableModelsText; set => SetProperty(ref _availableModelsText, value); }

    public string GatewayStatus => _gatewayService.State.Status.ToString();
    public string OsSupportNote => _osSupportService.GetSupportNote();
    public string WhatsAppCapabilityNote => _capabilityService.SupportsWhatsAppNativePairing
        ? "Native WhatsApp pairing is supported."
        : _capabilityService.WhatsAppReplacement;
    public string BootCapabilityNote => _capabilityService.SupportsBootReceiver
        ? "Native boot receiver supported."
        : _capabilityService.BootReplacement;

    private async Task RunSetupAsync()
    {
        SetupInProgress = true;
        try
        {
            var result = await _setupService.RunFullSetupAsync(msg => Status = msg);
            RootfsInstalled = result.RootfsInstalled;
            NodeInstalled = result.NodeInstalled;
            OpenClawInstalled = result.OpenClawInstalled;
            ChromiumInstalled = result.ChromiumInstalled;
            SetupProgress = result.ProgressPercent;

            Status = result.IsComplete
                ? "Setup verification complete."
                : "Setup incomplete. Install missing runtime dependencies in WSL.";

            AppendLog("setup", Status);
        }
        finally
        {
            SetupInProgress = false;
        }
    }


    private async Task InstallRuntimeAsync()
    {
        var result = await _setupService.InstallRuntimeDependenciesAsync(msg => Status = msg);
        RuntimeInstallOutput = result.output;
        Status = result.success ? "Runtime install completed." : "Runtime install failed. Check output.";
        AppendLog("setup", Status);

        var verification = await _setupService.RunFullSetupAsync();
        RootfsInstalled = verification.RootfsInstalled;
        NodeInstalled = verification.NodeInstalled;
        OpenClawInstalled = verification.OpenClawInstalled;
        ChromiumInstalled = verification.ChromiumInstalled;
        SetupProgress = verification.ProgressPercent;
    }

    private async Task RefreshModelsAsync()
    {
        var result = await _modelsService.FetchModelsAsync(ApiKey);
        if (result.success)
        {
            AvailableModelsText = string.Join(Environment.NewLine, result.models);
            Status = result.message;
            AppendLog("models", result.message);
        }
        else
        {
            AvailableModelsText = string.Empty;
            Status = result.message;
            AppendLog("models", "Model refresh failed.");
        }
    }
    private async Task RunRuntimeDoctorAsync()
    {
        var result = await _runtimeDoctorService.RunChecksAsync();
        RuntimeDoctorReport = result.Report;
        Status = result.Success ? "Runtime doctor: all checks passed." : "Runtime doctor found issues.";
        AppendLog("doctor", Status);
    }

    private void SaveOnboarding()
    {
        SaveSettings();
        AppendLog("onboarding", $"Saved provider={ApiProvider}, model={SelectedModel}");
        Status = "Onboarding saved.";
    }

    private void OpenOAuth()
    {
        var url = _oauthService.GetAuthorizationUrl();
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true });
        Status = "Opened OAuth page in browser.";
    }

    private async Task CompleteOAuthAsync()
    {
        var result = await _oauthService.ValidateTokenAsync(OauthTokenInput);
        if (result.success)
        {
            ApiKey = OauthTokenInput;
            SaveSettings();
            AppendLog("oauth", "OAuth/API token validated and saved.");
        }

        Status = result.message;
    }

    private async Task StartGatewayAsync()
    {
        if (!RootfsInstalled || !OpenClawInstalled)
        {
            Status = "Setup not complete. Run setup verification first.";
            AppendLog("gateway", Status);
            return;
        }

        Status = await _gatewayService.StartAsync(BuildConfig());
        AppendLog("gateway", Status);
        OnPropertyChanged(nameof(GatewayStatus));
    }

    private void StopGateway()
    {
        Status = _gatewayService.Stop();
        AppendLog("gateway", Status);
        OnPropertyChanged(nameof(GatewayStatus));
    }

    private async Task RestartGatewayAsync()
    {
        Status = await _gatewayService.RestartAsync(BuildConfig());
        AppendLog("gateway", Status);
        OnPropertyChanged(nameof(GatewayStatus));
    }

    private void OpenDashboard()
    {
        var url = _gatewayService.DashboardUrl();
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true });
        Status = $"Opened dashboard: {url}";
    }

    private void SaveSettings()
    {
        _storage.SaveConfig(BuildConfig());
        _storage.SaveSessionLogs(SessionLogs);

        if (AutoStartOnBoot)
        {
            var exe = Environment.ProcessPath ?? string.Empty;
            if (!string.IsNullOrWhiteSpace(exe))
            {
                _startupService.Enable(exe);
            }
        }
        else
        {
            _startupService.Disable();
        }

        Status = "Settings saved to local profile.";
    }

    private void GenerateBugReport()
    {
        BugReportPath = _bugReportService.CreateBugReport(_storage.BaseDirectory, BuildConfig(), SessionLogs, GatewayStatus);
        Status = $"Bug report created: {BugReportPath}";
        AppendLog("diagnostics", "Bug report generated.");
    }

    private void ExportTransfer()
    {
        TransferArtifactPath = _transferService.ExportSettings(_storage.BaseDirectory, _storage.BaseDirectory);
        Status = $"Transfer export created: {TransferArtifactPath}";
        AppendLog("transfer", "Export completed.");
    }

    private void ImportTransfer()
    {
        if (string.IsNullOrWhiteSpace(TransferImportPath) || !File.Exists(TransferImportPath))
        {
            Status = "Transfer import path does not exist.";
            return;
        }

        _transferService.ImportSettings(TransferImportPath, _storage.BaseDirectory);
        LoadPersistedState();
        Status = "Transfer import completed.";
        AppendLog("transfer", "Import completed.");
    }

    private void AppendLog(string category, string message)
    {
        SessionLogs.Insert(0, new SessionLogEntry { Category = category, Message = message });
        while (SessionLogs.Count > 300)
        {
            SessionLogs.RemoveAt(SessionLogs.Count - 1);
        }

        _storage.SaveSessionLogs(SessionLogs);
    }

    private void LoadPersistedState()
    {
        var config = _storage.LoadConfig();
        ApiProvider = config.ApiProvider;
        ApiKey = config.ApiKey;
        SelectedModel = config.SelectedModel;
        AutoStartOnBoot = _startupService.IsEnabled() || config.AutoStartOnBoot;
        ChargeOnlyMode = config.ChargeOnlyMode;
        MemorySearchEnabled = config.MemorySearchEnabled;
        MemorySearchProvider = config.MemorySearchProvider;
        MemorySearchApiKey = config.MemorySearchApiKey;

        WhatsAppEnabled = config.ChannelConfig.WhatsAppEnabled;
        TelegramEnabled = config.ChannelConfig.TelegramEnabled;
        DiscordEnabled = config.ChannelConfig.DiscordEnabled;
        TelegramBotToken = config.ChannelConfig.TelegramBotToken;
        DiscordBotToken = config.ChannelConfig.DiscordBotToken;
    }

    private AppConfig BuildConfig() => new()
    {
        ApiProvider = ApiProvider,
        ApiKey = ApiKey,
        SelectedModel = SelectedModel,
        SelectedModelProvider = ApiProvider,
        AutoStartOnBoot = AutoStartOnBoot,
        ChargeOnlyMode = ChargeOnlyMode,
        MemorySearchEnabled = MemorySearchEnabled,
        MemorySearchProvider = MemorySearchProvider,
        MemorySearchApiKey = MemorySearchApiKey,
        ChannelConfig = new ChannelConfig
        {
            WhatsAppEnabled = WhatsAppEnabled,
            TelegramEnabled = TelegramEnabled,
            DiscordEnabled = DiscordEnabled,
            TelegramBotToken = TelegramBotToken,
            DiscordBotToken = DiscordBotToken,
        },
    };
}

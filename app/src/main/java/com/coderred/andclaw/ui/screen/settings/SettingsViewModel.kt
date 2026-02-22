@file:Suppress("PackageDirectoryMismatch")

package com.coderred.andclaw.ui.screen.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coderred.andclaw.R
import com.coderred.andclaw.AndClawApp
import com.coderred.andclaw.data.BugReportBundleBuilder
import com.coderred.andclaw.data.BugReportZipArtifact
import com.coderred.andclaw.data.BugReportZipWriter
import com.coderred.andclaw.data.OpenRouterModel
import com.coderred.andclaw.data.SessionLogEntry
import com.coderred.andclaw.data.parseOpenRouterModels
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.isSessionError
import com.coderred.andclaw.proot.GatewayWsClient
import com.coderred.andclaw.service.GatewayService
import com.coderred.andclaw.ui.screen.dashboard.WhatsAppQrState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    data class DoctorFixResult(
        val success: Boolean,
        val output: String,
    )

    data class BugReportPreview(
        val sessionErrorCount: Int = 0,
        val hasGatewayError: Boolean = false,
        val hasProcessError: Boolean = false,
        val gatewayLogCount: Int = 0,
    )

    data class BugReportArtifactInfo(
        val fileName: String,
        val sizeBytes: Long,
    )

    data class BugReportUiState(
        val isVisible: Boolean = false,
        val hasConsent: Boolean = false,
        val isGenerating: Boolean = false,
        val preview: BugReportPreview = BugReportPreview(),
        val artifact: BugReportZipArtifact? = null,
        val artifactInfo: BugReportArtifactInfo? = null,
        val generationErrorMessage: String? = null,
    )

    companion object {
        private const val TAG = "AndClawCodexAuth"
        private const val OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val OAUTH_AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        private const val OAUTH_TOKEN_URL_PRIMARY = "https://auth.openai.com/oauth/token"
        private const val OAUTH_REDIRECT_URI = "http://localhost:1455/auth/callback"
        private const val OAUTH_SCOPE = "openid profile email offline_access"
        private const val OAUTH_JWT_CLAIM_PATH = "https://api.openai.com/auth"
        private const val LOG_UNLOCK_TAP_COUNT = 7
        private const val LOG_UNLOCK_TAP_WINDOW_MS = 2000L
        private const val OPENCLAW_BUILTIN_MODELS_PATH =
            "usr/local/lib/node_modules/openclaw/node_modules/@mariozechner/pi-ai/dist/models.generated.js"
    }
    private val prefs = (application as AndClawApp).preferencesManager
    private val prootManager = (application as AndClawApp).prootManager
    private val processManager = (application as AndClawApp).processManager

    val autoStartOnBoot: StateFlow<Boolean> = prefs.autoStartOnBoot
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val chargeOnlyMode: StateFlow<Boolean> = prefs.chargeOnlyMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val apiProvider: StateFlow<String> = prefs.apiProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, "openrouter")

    val apiKey: StateFlow<String> = prefs.apiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val openClawVersion: StateFlow<String> = prefs.openClawVersion
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val selectedModel: StateFlow<String> = prefs.selectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val braveSearchApiKey: StateFlow<String> = prefs.braveSearchApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val isLogSectionUnlocked: StateFlow<Boolean> = prefs.logSectionUnlocked
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val whatsappEnabled: StateFlow<Boolean> = prefs.whatsappEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val telegramEnabled: StateFlow<Boolean> = prefs.telegramEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val telegramBotToken: StateFlow<String> = prefs.telegramBotToken
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val discordEnabled: StateFlow<Boolean> = prefs.discordEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val discordBotToken: StateFlow<String> = prefs.discordBotToken
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val discordGuildAllowlist: StateFlow<String> = prefs.discordGuildAllowlist
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val discordRequireMention: StateFlow<Boolean> = prefs.discordRequireMention
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _availableModels = MutableStateFlow<List<OpenRouterModel>>(emptyList())
    val availableModels: StateFlow<List<OpenRouterModel>> = _availableModels.asStateFlow()

    private val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels: StateFlow<Boolean> = _isLoadingModels.asStateFlow()

    private val _modelLoadError = MutableStateFlow<String?>(null)
    val modelLoadError: StateFlow<String?> = _modelLoadError.asStateFlow()

    private val _whatsappQrState = MutableStateFlow<WhatsAppQrState>(WhatsAppQrState.Idle)
    val whatsappQrState: StateFlow<WhatsAppQrState> = _whatsappQrState.asStateFlow()

    private val _isCodexAuthInProgress = MutableStateFlow(false)
    val isCodexAuthInProgress: StateFlow<Boolean> = _isCodexAuthInProgress.asStateFlow()

    private val _isCodexAuthenticated = MutableStateFlow(false)
    val isCodexAuthenticated: StateFlow<Boolean> = _isCodexAuthenticated.asStateFlow()

    private val _codexAuthUrl = MutableStateFlow<String?>(null)
    val codexAuthUrl: StateFlow<String?> = _codexAuthUrl.asStateFlow()

    private val _codexAuthDebugLine = MutableStateFlow<String?>(null)
    val codexAuthDebugLine: StateFlow<String?> = _codexAuthDebugLine.asStateFlow()
    private val _isDoctorFixRunning = MutableStateFlow(false)
    val isDoctorFixRunning: StateFlow<Boolean> = _isDoctorFixRunning.asStateFlow()
    private val _doctorFixResult = MutableStateFlow<DoctorFixResult?>(null)
    val doctorFixResult: StateFlow<DoctorFixResult?> = _doctorFixResult.asStateFlow()
    private val _bugReportUiState = MutableStateFlow(BugReportUiState())
    val bugReportUiState: StateFlow<BugReportUiState> = _bugReportUiState.asStateFlow()
    private val codexAuthRunning = AtomicBoolean(false)
    private val oauthServerLock = Any()
    private var oauthServerSocket: ServerSocket? = null

    private var wsClient: GatewayWsClient? = null
    private var whatsappQrJob: Job? = null
    private var shouldRestartAfterWhatsAppPairing: Boolean = false
    private var logUnlockTapCount: Int = 0
    private var lastLogUnlockTapAtMs: Long = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val provider = prefs.apiProvider.first()
            if (provider == "openai-codex") {
                _isCodexAuthenticated.value = detectCodexAuth()
            }
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoStartOnBoot(enabled) }
    }

    fun setChargeOnlyMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setChargeOnlyMode(enabled) }
    }

    fun onVersionInfoTapped() {
        if (isLogSectionUnlocked.value) return

        val now = System.currentTimeMillis()
        if (now - lastLogUnlockTapAtMs > LOG_UNLOCK_TAP_WINDOW_MS) {
            logUnlockTapCount = 0
        }
        lastLogUnlockTapAtMs = now
        logUnlockTapCount += 1

        if (logUnlockTapCount >= LOG_UNLOCK_TAP_COUNT) {
            logUnlockTapCount = 0
            viewModelScope.launch {
                prefs.setLogSectionUnlocked(true)
            }
        }
    }

    fun setApiProvider(provider: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setApiProvider(provider)
            val defaultModelId = when (provider) {
                "openrouter" -> "openrouter/free"
                "anthropic" -> "claude-sonnet-4-5"
                "openai" -> "gpt-5-mini"
                "openai-codex" -> "gpt-5.3-codex"
                "google" -> "gemini-2.5-flash"
                else -> ""
            }
            if (defaultModelId.isNotBlank()) {
                prefs.setSelectedModelId(defaultModelId)
                processManager.ensureOpenClawConfig(
                    apiProvider = provider,
                    selectedModel = defaultModelId,
                )
            }
            if (provider == "openai-codex") {
                refreshCodexAuthStatus()
            }
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setApiKey(key)
            val provider = prefs.apiProvider.first()
            syncApiKeyAuthProfile(provider = provider, apiKey = key)
        }
    }

    fun setSelectedModel(model: com.coderred.andclaw.data.OpenRouterModel) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setSelectedModel(model)
            // config 파일도 업데이트
            val provider = prefs.apiProvider.first()
            processManager.ensureOpenClawConfig(
                apiProvider = provider,
                selectedModel = model.id,
                modelReasoning = model.supportsReasoning,
                modelImages = model.supportsImages,
                modelContext = model.contextLength,
                modelMaxOutput = model.maxOutputTokens,
            )
        }
    }

    fun setGptSubscription(useCodexOAuth: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val provider = if (useCodexOAuth) "openai-codex" else "openai"
            val modelId = if (useCodexOAuth) "gpt-5.3-codex" else "gpt-5-mini"

            prefs.setApiProvider(provider)
            prefs.setSelectedModelId(modelId)

            processManager.ensureOpenClawConfig(
                apiProvider = provider,
                selectedModel = modelId,
            )

            if (useCodexOAuth) {
                _isCodexAuthenticated.value = detectCodexAuth()
            } else {
                val openAiApiKey = prefs.apiKey.first()
                syncApiKeyAuthProfile(provider = "openai", apiKey = openAiApiKey)
            }
        }
    }

    fun shouldShowRestartPromptForProvider(provider: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val shouldShow = when (provider) {
                "openai-codex" -> detectCodexAuth()
                else -> prefs.hasApiKeyForProvider(provider)
            }
            withContext(Dispatchers.Main) {
                onResult(shouldShow)
            }
        }
    }

    fun setBraveSearchApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setBraveSearchApiKey(key)
            restartGatewayIfRunning()
        }
    }

    fun runOpenClawDoctorFix() {
        if (_isDoctorFixRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isDoctorFixRunning.value = true
            val command = "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                "openclaw doctor --fix 2>&1"
            val result = prootManager.executeWithResult(command, timeoutMs = 300_000)
            _doctorFixResult.value = when {
                result == null -> DoctorFixResult(
                    success = false,
                    output = "Failed to execute command.",
                )
                result.timedOut -> DoctorFixResult(
                    success = false,
                    output = "Command timed out after 300 seconds.\n\n${result.output}",
                )
                else -> DoctorFixResult(
                    success = result.exitCode == 0,
                    output = result.output.ifBlank { "No output." },
                )
            }
            _isDoctorFixRunning.value = false
        }
    }

    fun consumeDoctorFixResult() {
        _doctorFixResult.value = null
    }

    fun openBugReportDialog() {
        viewModelScope.launch(Dispatchers.IO) {
            val previewResult = runCatching {
                val sessionEntries = processManager.getSessionLogEntries()
                val gatewayErrorMessage = processManager.gatewayState.value.errorMessage
                val gatewayLogLines = processManager.logLines.value
                buildBugReportPreview(sessionEntries, gatewayErrorMessage, gatewayLogLines)
            }

            val preview = previewResult.getOrElse { BugReportPreview() }
            val openErrorMessage = previewResult.exceptionOrNull()?.message
                ?.takeIf { it.isNotBlank() }
                ?: previewResult.exceptionOrNull()?.let { "Failed to load preview data." }

            withContext(Dispatchers.Main) {
                _bugReportUiState.value = _bugReportUiState.value.copy(
                    isVisible = true,
                    hasConsent = false,
                    isGenerating = false,
                    preview = preview,
                    generationErrorMessage = openErrorMessage,
                )
            }
        }
    }

    fun dismissBugReportDialog() {
        _bugReportUiState.value = _bugReportUiState.value.copy(
            isVisible = false,
            hasConsent = false,
            isGenerating = false,
            generationErrorMessage = null,
        )
    }

    fun setBugReportConsent(consented: Boolean) {
        _bugReportUiState.value = _bugReportUiState.value.copy(hasConsent = consented)
    }

    fun setBugReportGenerationErrorMessage(message: String?) {
        _bugReportUiState.value = _bugReportUiState.value.copy(generationErrorMessage = message)
    }

    fun generateBugReportZip() {
        val currentState = _bugReportUiState.value
        if (!currentState.isVisible || !currentState.hasConsent || currentState.isGenerating) return

        _bugReportUiState.value = currentState.copy(
            isGenerating = true,
            generationErrorMessage = null,
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val sessionEntries = processManager.getSessionLogEntries()
                val gatewayErrorMessage = processManager.gatewayState.value.errorMessage
                val gatewayLogLines = processManager.logLines.value
                val metadata = BugReportBundleBuilder.collectMetadata(context)
                val bundle = BugReportBundleBuilder.build(
                    sessionEntries = sessionEntries,
                    gatewayLogLines = gatewayLogLines,
                    metadata = metadata,
                    gatewayErrorMessage = gatewayErrorMessage,
                    processErrorMessage = gatewayErrorMessage,
                )
                val artifact = BugReportZipWriter.write(context, bundle)
                val artifactInfo = BugReportArtifactInfo(
                    fileName = artifact.file.name,
                    sizeBytes = artifact.file.length(),
                )
                val preview = buildBugReportPreview(sessionEntries, gatewayErrorMessage, gatewayLogLines)

                withContext(Dispatchers.Main) {
                    _bugReportUiState.value = _bugReportUiState.value.copy(
                        isGenerating = false,
                        preview = preview,
                        artifact = artifact,
                        artifactInfo = artifactInfo,
                        generationErrorMessage = null,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) {
                    _bugReportUiState.value = _bugReportUiState.value.copy(
                        isGenerating = false,
                        generationErrorMessage = e.message ?: "Failed to generate bug report ZIP.",
                    )
                }
            }
        }
    }

    private fun buildBugReportPreview(
        sessionEntries: List<SessionLogEntry>,
        gatewayErrorMessage: String?,
        gatewayLogLines: List<String>,
    ): BugReportPreview {
        val includedGatewayLogCount = BugReportBundleBuilder.sanitizeGatewayLogLines(gatewayLogLines).size
        return BugReportPreview(
            sessionErrorCount = sessionEntries.count { it.isSessionError() },
            hasGatewayError = !gatewayErrorMessage.isNullOrBlank(),
            hasProcessError = !gatewayErrorMessage.isNullOrBlank(),
            gatewayLogCount = includedGatewayLogCount,
        )
    }

    fun setTelegramEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setTelegramEnabled(enabled)
            applyChannelConfigAndRestart()
        }
    }

    fun setTelegramBotToken(token: String, restartGateway: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setTelegramBotToken(token)
            if (restartGateway) {
                applyChannelConfigAndRestart()
            }
        }
    }

    fun setDiscordEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setDiscordEnabled(enabled)
            applyChannelConfigAndRestart()
        }
    }

    fun setDiscordBotToken(token: String, restartGateway: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setDiscordBotToken(token)
            if (restartGateway) {
                applyChannelConfigAndRestart()
            }
        }
    }

    fun setDiscordGuildAllowlist(raw: String, restartGateway: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setDiscordGuildAllowlist(raw)
            if (restartGateway) {
                applyChannelConfigAndRestart()
            }
        }
    }

    fun setDiscordRequireMention(enabled: Boolean, restartGateway: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setDiscordRequireMention(enabled)
            if (restartGateway) {
                applyChannelConfigAndRestart()
            }
        }
    }

    private var restartJob: kotlinx.coroutines.Job? = null

    private suspend fun applyChannelConfigAndRestart() {
        val channelConfig = prefs.channelConfig.first()
        processManager.ensureChannelConfig(channelConfig)
        restartGatewayIfRunning()
    }

    private fun restartGatewayIfRunning(delayMs: Long = 1000L) {
        val status = processManager.gatewayState.value.status
        if (
            status != GatewayStatus.RUNNING &&
            status != GatewayStatus.STARTING &&
            status != GatewayStatus.ERROR
        ) return
        restartJob?.cancel()
        restartJob = viewModelScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(delayMs)
            val context = getApplication<Application>()
            val intent = Intent(context, GatewayService::class.java).apply {
                action = GatewayService.ACTION_RESTART
            }
            context.startForegroundService(intent)
        }
    }

    fun restartGatewayNow() {
        val context = getApplication<Application>()
        val intent = Intent(context, GatewayService::class.java).apply {
            action = GatewayService.ACTION_RESTART
        }
        context.startForegroundService(intent)
    }

    fun restartGatewayIfRunningNow() {
        restartGatewayIfRunning(delayMs = 0L)
    }

    fun fetchModels() {
        if (_isLoadingModels.value) return
        _isLoadingModels.value = true
        _modelLoadError.value = null

        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    val url = URL("https://openrouter.ai/api/v1/models")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("Accept", "application/json")

                    try {
                        val responseCode = conn.responseCode
                        if (responseCode != 200) {
                            throw Exception("HTTP $responseCode")
                        }

                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        parseOpenRouterModels(body)
                    } finally {
                        conn.disconnect()
                    }
                }
                _availableModels.value = models
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _modelLoadError.value = e.message
            } finally {
                _isLoadingModels.value = false
            }
        }
    }

    fun fetchModelsForCurrentProvider() {
        val provider = apiProvider.value
        if (provider == "openrouter") {
            fetchModels()
            return
        }

        if (_isLoadingModels.value) return
        _isLoadingModels.value = true
        _modelLoadError.value = null

        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    loadBuiltInModels(provider)
                }
                _availableModels.value = models
                if (models.isEmpty()) {
                    _modelLoadError.value = "No built-in models found."
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _availableModels.value = emptyList()
                _modelLoadError.value = e.message ?: "Failed to load built-in models."
            } finally {
                _isLoadingModels.value = false
            }
        }
    }

    private fun loadBuiltInModels(provider: String): List<OpenRouterModel> {
        val fallbackModels = defaultBuiltInModels(provider)
        val rootfsDir = prootManager.rootfsDir ?: return fallbackModels

        val modelsFile = File(rootfsDir, OPENCLAW_BUILTIN_MODELS_PATH)
        if (!modelsFile.exists()) {
            return fallbackModels
        }

        val content = modelsFile.readText()
        val sectionName = when (provider) {
            "openai-codex" -> "openai-codex"
            else -> provider
        }

        val entries = extractProviderModelEntries(content, sectionName)
        if (entries.isEmpty() && provider == "openai-codex") {
            val fallbackOpenAiEntries = extractProviderModelEntries(content, "openai")
                .filter { it.id.contains("codex", ignoreCase = true) }
            if (fallbackOpenAiEntries.isNotEmpty()) {
                return fallbackOpenAiEntries
                    .map { it.toOpenRouterModel() }
                    .distinctBy { it.id }
            }
        }

        return if (entries.isNotEmpty()) {
            entries
                .map { it.toOpenRouterModel() }
                .distinctBy { it.id }
        } else {
            fallbackModels
        }
    }

    private data class BuiltInModelEntry(
        val id: String,
        val name: String,
        val contextWindow: Int,
        val maxTokens: Int,
        val supportsReasoning: Boolean,
        val supportsImages: Boolean,
    ) {
        fun toOpenRouterModel(): OpenRouterModel {
            return OpenRouterModel(
                id = id,
                name = name,
                contextLength = contextWindow,
                maxOutputTokens = maxTokens,
                isFree = false,
                pricing = "Built-in",
                supportsReasoning = supportsReasoning,
                supportsImages = supportsImages,
            )
        }
    }

    private fun builtInModel(
        id: String,
        contextWindow: Int,
        maxTokens: Int,
        supportsReasoning: Boolean = false,
        supportsImages: Boolean = false,
    ): OpenRouterModel {
        return BuiltInModelEntry(
            id = id,
            name = id,
            contextWindow = contextWindow,
            maxTokens = maxTokens,
            supportsReasoning = supportsReasoning,
            supportsImages = supportsImages,
        ).toOpenRouterModel()
    }

    private fun defaultBuiltInModels(provider: String): List<OpenRouterModel> {
        return when (provider) {
            "anthropic" -> listOf(
                builtInModel("claude-sonnet-4-5", contextWindow = 200_000, maxTokens = 8_192),
                builtInModel("claude-opus-4.1", contextWindow = 200_000, maxTokens = 8_192),
            )
            "openai" -> listOf(
                builtInModel("gpt-5-mini", contextWindow = 128_000, maxTokens = 16_384),
                builtInModel("gpt-5", contextWindow = 128_000, maxTokens = 16_384),
            )
            "openai-codex" -> listOf(
                builtInModel("gpt-5.3-codex", contextWindow = 128_000, maxTokens = 16_384),
            )
            "google" -> listOf(
                builtInModel("gemini-2.5-flash", contextWindow = 1_000_000, maxTokens = 8_192),
                builtInModel("gemini-2.5-pro", contextWindow = 1_000_000, maxTokens = 8_192),
            )
            else -> emptyList()
        }
    }

    private fun extractProviderModelEntries(content: String, sectionName: String): List<BuiltInModelEntry> {
        val marker = "\"$sectionName\": {"
        val markerIndex = content.indexOf(marker)
        if (markerIndex < 0) return emptyList()

        val sectionStart = content.indexOf('{', markerIndex + marker.length - 1)
        if (sectionStart < 0) return emptyList()

        val sectionEnd = findMatchingBraceIndex(content, sectionStart)
        if (sectionEnd <= sectionStart) return emptyList()

        val result = mutableListOf<BuiltInModelEntry>()
        var index = sectionStart + 1
        var depth = 1

        while (index < sectionEnd) {
            when (content[index]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    val (key, endQuoteIndex) = readJsonLikeString(content, index)
                    index = endQuoteIndex
                    if (depth == 1) {
                        var cursor = skipWhitespace(content, endQuoteIndex + 1, sectionEnd)
                        if (cursor < sectionEnd && content[cursor] == ':') {
                            cursor = skipWhitespace(content, cursor + 1, sectionEnd)
                            if (cursor < sectionEnd && content[cursor] == '{') {
                                val objectEnd = findMatchingBraceIndex(content, cursor)
                                if (objectEnd in (cursor + 1)..sectionEnd) {
                                    val body = content.substring(cursor, objectEnd + 1)
                                    parseBuiltInModelEntry(key, body)?.let { result.add(it) }
                                }
                            }
                        }
                    }
                }
            }
            index++
        }

        return result
    }

    private fun parseBuiltInModelEntry(id: String, body: String): BuiltInModelEntry? {
        val contextWindow = parseJsNumberField(body, "contextWindow") ?: return null
        val maxTokens = parseJsNumberField(body, "maxTokens") ?: 4096
        val name = parseJsStringField(body, "name") ?: id
        val supportsReasoning = parseJsBooleanField(body, "reasoning") ?: false
        val supportsImages = parseInputSupportsImages(body)

        return BuiltInModelEntry(
            id = id,
            name = name,
            contextWindow = contextWindow,
            maxTokens = maxTokens,
            supportsReasoning = supportsReasoning,
            supportsImages = supportsImages,
        )
    }

    private fun parseJsStringField(body: String, field: String): String? {
        val escapedField = Regex.escape(field)
        val regex = Regex("\\b$escapedField\\s*:\\s*\"((?:\\\\.|[^\\\"])*)\"")
        val raw = regex.find(body)?.groupValues?.get(1) ?: return null
        return raw.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun parseJsBooleanField(body: String, field: String): Boolean? {
        val escapedField = Regex.escape(field)
        val regex = Regex("\\b$escapedField\\s*:\\s*(true|false)")
        return when (regex.find(body)?.groupValues?.get(1)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun parseJsNumberField(body: String, field: String): Int? {
        val escapedField = Regex.escape(field)
        val regex = Regex("\\b$escapedField\\s*:\\s*([0-9][0-9_]*(?:\\.[0-9_]+)?(?:[eE][+-]?[0-9]+)?)")
        val raw = regex.find(body)?.groupValues?.get(1)?.replace("_", "") ?: return null
        val value = raw.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val longValue = value.toLong()
        if (longValue <= 0L) return null
        return longValue.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun parseInputSupportsImages(body: String): Boolean {
        val regex = Regex("\\binput\\s*:\\s*\\[([^]]*)]", RegexOption.DOT_MATCHES_ALL)
        val inputBody = regex.find(body)?.groupValues?.get(1) ?: return false
        return Regex("\"(image|images|vision|video)\"").containsMatchIn(inputBody)
    }

    private fun skipWhitespace(text: String, start: Int, maxExclusive: Int): Int {
        var index = start
        while (index < maxExclusive && text[index].isWhitespace()) {
            index++
        }
        return index
    }

    private fun findMatchingBraceIndex(text: String, openingBraceIndex: Int): Int {
        if (openingBraceIndex !in text.indices || text[openingBraceIndex] != '{') return -1

        var depth = 0
        var inString = false
        var escaped = false
        var index = openingBraceIndex

        while (index < text.length) {
            val ch = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (ch) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }

        return -1
    }

    private fun readJsonLikeString(text: String, quoteIndex: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var index = quoteIndex + 1
        var escaped = false

        while (index < text.length) {
            val ch = text[index]
            if (escaped) {
                sb.append(ch)
                escaped = false
            } else {
                when (ch) {
                    '\\' -> escaped = true
                    '"' -> return sb.toString() to index
                    else -> sb.append(ch)
                }
            }
            index++
        }

        return sb.toString() to text.lastIndex
    }

    fun refreshCodexAuthStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _isCodexAuthenticated.value = detectCodexAuth()
        }
    }

    fun loginOpenAiCodexOAuth() {
        if (!codexAuthRunning.compareAndSet(false, true)) return

        viewModelScope.launch(Dispatchers.IO) {
            _isCodexAuthInProgress.value = true
            try {
                _codexAuthUrl.value = null
                _codexAuthDebugLine.value = null

                runCodexDirectPkceLogin()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Codex OAuth failed: ${e.message}", e)
                _codexAuthDebugLine.value = e.message
            } finally {
                _isCodexAuthenticated.value = detectCodexAuth()
                Log.i(TAG, "Auth flow finished. authenticated=${_isCodexAuthenticated.value}")
                _isCodexAuthInProgress.value = false
                codexAuthRunning.set(false)
            }
        }
    }

    private data class AuthorizationFlow(
        val verifier: String,
        val state: String,
        val url: String,
    )

    private data class OAuthTokenResult(
        val access: String,
        val refresh: String,
        val expires: Long,
    )

    private suspend fun runCodexDirectPkceLogin() {
        val flow = createAuthorizationFlow()
        _codexAuthUrl.value = flow.url
        Log.i(TAG, "Detected auth URL: ${flow.url}")

        val code = waitForOAuthCallbackCode(flow.state)
            ?: throw IllegalStateException("OAuth callback timeout")

        val token = exchangeAuthorizationCode(code, flow.verifier)
        val accountId = extractAccountId(token.access)
            ?: throw IllegalStateException("Failed to extract accountId from token")

        writeCodexOAuthCredentials(token, accountId)
        ensureCodexPrimaryModel()
    }

    private fun createAuthorizationFlow(originator: String = "pi"): AuthorizationFlow {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()
        val authUrl = Uri.parse(OAUTH_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("client_id", OAUTH_CLIENT_ID)
            .appendQueryParameter("redirect_uri", OAUTH_REDIRECT_URI)
            .appendQueryParameter("scope", OAUTH_SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("originator", originator)
            .build()
            .toString()

        return AuthorizationFlow(
            verifier = verifier,
            state = state,
            url = authUrl,
        )
    }

    private fun waitForOAuthCallbackCode(expectedState: String, timeoutMs: Long = 300_000): String? {
        val startedAt = System.currentTimeMillis()
        var serverSocket: ServerSocket? = null
        return try {
            synchronized(oauthServerLock) {
                runCatching { oauthServerSocket?.close() }
                oauthServerSocket = null

                serverSocket = ServerSocket(1455, 50, InetAddress.getByName("127.0.0.1"))
                oauthServerSocket = serverSocket
            }
            val socket = serverSocket ?: return null
            socket.soTimeout = 1_000

            while (System.currentTimeMillis() - startedAt < timeoutMs) {
                try {
                    val client = socket.accept()
                    client.use {
                        client.soTimeout = 3_000
                        val requestLine = BufferedReader(InputStreamReader(client.getInputStream()))
                            .readLine()
                            .orEmpty()
                        Log.d(TAG, "OAuth callback requestLine=$requestLine")
                        val target = requestLine.split(" ").getOrNull(1).orEmpty()
                        val parsedUri = if (target.startsWith("http://") || target.startsWith("https://")) {
                            runCatching { Uri.parse(target) }.getOrNull()
                        } else {
                            null
                        }
                        val path = parsedUri?.path ?: target.substringBefore("?")
                        val query = parsedUri?.encodedQuery ?: target.substringAfter("?", "")
                        val params = parseQueryString(query)
                        val code = params["code"]
                        val state = params["state"]
                        val err = params["error"]
                        val errDesc = params["error_description"]
                        val body: String

                        val isCallbackPath = path == "/auth/callback" || path == "/auth/callback/"
                        if (!isCallbackPath) {
                            Log.w(TAG, "OAuth callback path mismatch: path=$path target=$target")
                            body = "Not found"
                            client.getOutputStream().write(
                                "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
                                    .toByteArray()
                            )
                        } else if (code.isNullOrBlank()) {
                            Log.w(TAG, "OAuth callback missing code. error=$err desc=$errDesc params=$params")
                            body = "Invalid callback"
                            client.getOutputStream().write(
                                "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
                                    .toByteArray()
                            )
                        } else {
                            if (state != expectedState) {
                                Log.w(TAG, "OAuth state mismatch. expected=$expectedState actual=$state")
                            }
                            val title = getApplication<Application>().getString(R.string.settings_api_key_configured)
                            val appName = getApplication<Application>().getString(R.string.app_name)
                            body = """
                                <!doctype html>
                                <html lang="en">
                                <head>
                                  <meta charset="utf-8" />
                                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                                  <title>$appName</title>
                                  <style>
                                    body {
                                      margin: 0;
                                      min-height: 100vh;
                                      display: grid;
                                      place-items: center;
                                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                                      background: #f6f8fb;
                                      color: #0f172a;
                                    }
                                    .card {
                                      width: min(92vw, 560px);
                                      background: #ffffff;
                                      border-radius: 18px;
                                      padding: 28px 24px;
                                      box-shadow: 0 10px 30px rgba(15, 23, 42, 0.10);
                                      text-align: center;
                                    }
                                    .mark {
                                      font-size: 64px;
                                      line-height: 1;
                                      margin-bottom: 10px;
                                    }
                                    h1 {
                                      margin: 0;
                                      font-size: 32px;
                                      font-weight: 800;
                                    }
                                    p {
                                      margin: 12px 0 0 0;
                                      font-size: 20px;
                                      color: #334155;
                                    }
                                  </style>
                                </head>
                                <body>
                                  <div class="card">
                                    <div class="mark">✓</div>
                                    <h1>$title</h1>
                                    <p>$appName</p>
                                  </div>
                                </body>
                                </html>
                            """.trimIndent()
                            client.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
                                    .toByteArray()
                            )
                            return code
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // keep waiting
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind callback server: ${e.message}", e)
            null
        } finally {
            synchronized(oauthServerLock) {
                runCatching { serverSocket?.close() }
                if (oauthServerSocket === serverSocket) {
                    oauthServerSocket = null
                }
            }
        }
    }

    fun consumeCodexAuthUrl() {
        _codexAuthUrl.value = null
    }

    private fun detectCodexAuth(): Boolean {
        return try {
            // OpenClaw auth profiles
            val openClawAuthFile = File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/auth-profiles.json")
            if (openClawAuthFile.exists()) {
                runCatching {
                    val root = JSONObject(openClawAuthFile.readText())
                    val profiles = root.optJSONObject("profiles")
                    val profile = profiles?.optJSONObject("openai-codex:default")
                        ?: profiles?.optJSONObject("openai:default")
                    if (profile != null) {
                        val access = profile.optString("access", "")
                        val expires = profile.optLong("expires", 0L)
                        val hasValidExpiry = expires <= 0L || expires > System.currentTimeMillis()
                        if (access.isNotBlank() && hasValidExpiry) {
                            return true
                        }
                    }
                }
            }

            // Codex CLI auth file fallback
            val codexAuthFile = File(prootManager.rootfsDir, "root/.codex/auth.json")
            if (codexAuthFile.exists() && codexAuthFile.readText().contains("chatgpt.com")) {
                return true
            }

            // CLI 상태 조회 (느린 편이라 마지막 fallback)
            val authListCommand = "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                "openclaw models auth list --provider openai-codex 2>&1"
            val authListOutput = prootManager.executeAndCapture(authListCommand)
            if (!authListOutput.isNullOrBlank()) {
                val normalized = authListOutput.lowercase()
                if (normalized.contains("openai-codex:")) return true
                if (normalized.contains("codex-cli")) return true
                if (normalized.contains("chatgpt")) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun exchangeAuthorizationCode(code: String, verifier: String): OAuthTokenResult {
        var lastError: Exception? = null

        for (attempt in 0..7) {
            try {
                return exchangeAuthorizationCodeOnce(OAUTH_TOKEN_URL_PRIMARY, code, verifier)
            } catch (e: Exception) {
                lastError = e
                val retryable = e is UnknownHostException || e is SocketTimeoutException
                if (!retryable || attempt >= 7) break
                val backoffMs = 500L * (attempt + 1)
                Log.w(TAG, "Token exchange retry after ${backoffMs}ms (${e.javaClass.simpleName})")
                delay(backoffMs)
            }
        }

        throw lastError ?: IllegalStateException("Token exchange failed")
    }

    private fun exchangeAuthorizationCodeOnce(
        tokenUrl: String,
        code: String,
        verifier: String,
    ): OAuthTokenResult {
        val conn = URL(tokenUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = buildString {
                append("grant_type=authorization_code")
                append("&client_id=").append(urlEncode(OAUTH_CLIENT_ID))
                append("&code=").append(urlEncode(code))
                append("&code_verifier=").append(urlEncode(verifier))
                append("&redirect_uri=").append(urlEncode(OAUTH_REDIRECT_URI))
            }
            conn.outputStream.use { it.write(body.toByteArray()) }

            val status = conn.responseCode
            val raw = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (status !in 200..299) {
                val reason = raw.take(240)
                throw IllegalStateException("Token exchange failed: HTTP $status ${if (reason.isNotBlank()) "- $reason" else ""}".trim())
            }

            val json = JSONObject(raw)
            val access = json.optString("access_token", "")
            val refresh = json.optString("refresh_token", "")
            val expiresInSec = json.optLong("expires_in", -1)
            if (access.isBlank() || refresh.isBlank() || expiresInSec <= 0) {
                throw IllegalStateException("Token response missing fields")
            }

            OAuthTokenResult(
                access = access,
                refresh = refresh,
                expires = System.currentTimeMillis() + (expiresInSec * 1000L),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun extractAccountId(accessToken: String): String? {
        val payload = decodeJwt(accessToken) ?: return null
        val auth = payload.optJSONObject(OAUTH_JWT_CLAIM_PATH) ?: return null
        return auth.optString("chatgpt_account_id", "").ifBlank { null }
    }

    private fun decodeJwt(token: String): JSONObject? {
        return runCatching {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
            JSONObject(payload)
        }.getOrNull()
    }

    private fun writeCodexOAuthCredentials(token: OAuthTokenResult, accountId: String) {
        val authFile = File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/auth-profiles.json")
        authFile.parentFile?.mkdirs()

        val root = if (authFile.exists()) {
            runCatching { JSONObject(authFile.readText()) }.getOrElse { JSONObject() }
        } else JSONObject()

        if (!root.has("version")) {
            root.put("version", 1)
        }
        val profiles = root.optJSONObject("profiles") ?: JSONObject().also { root.put("profiles", it) }

        val codexCredential = JSONObject().apply {
            put("type", "oauth")
            put("provider", "openai-codex")
            put("access", token.access)
            put("refresh", token.refresh)
            put("expires", token.expires)
            put("accountId", accountId)
        }
        val openAiCredential = JSONObject(codexCredential.toString()).apply {
            put("provider", "openai")
        }

        profiles.put("openai-codex:default", codexCredential)
        profiles.put("openai:default", openAiCredential)

        val lastGood = root.optJSONObject("lastGood") ?: JSONObject().also { root.put("lastGood", it) }
        lastGood.put("openai-codex", "openai-codex:default")
        lastGood.put("openai", "openai:default")

        authFile.writeText(root.toString(2))
    }

    private fun syncApiKeyAuthProfile(provider: String, apiKey: String) {
        val normalizedProvider = when (provider.lowercase()) {
            "openai-codex" -> "openai"
            else -> provider.lowercase()
        }
        if (normalizedProvider !in setOf("google", "openai", "anthropic", "openrouter")) return

        runCatching {
            val profileId = "$normalizedProvider:default"
            val authFile = File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/auth-profiles.json")
            authFile.parentFile?.mkdirs()

            val root = if (authFile.exists()) {
                runCatching { JSONObject(authFile.readText()) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }

            if (!root.has("version")) {
                root.put("version", 1)
            }

            val profiles = root.optJSONObject("profiles") ?: JSONObject().also { root.put("profiles", it) }

            if (apiKey.isBlank()) {
                profiles.remove(profileId)
                root.optJSONObject("lastGood")?.let { lastGood ->
                    if (lastGood.optString(normalizedProvider) == profileId) {
                        lastGood.remove(normalizedProvider)
                    }
                    if (lastGood.length() == 0) {
                        root.remove("lastGood")
                    }
                }
            } else {
                val apiKeyCredential = JSONObject().apply {
                    put("type", "api_key")
                    put("provider", normalizedProvider)
                    put("key", apiKey)
                }
                profiles.put(profileId, apiKeyCredential)

                val lastGood = root.optJSONObject("lastGood") ?: JSONObject().also { root.put("lastGood", it) }
                lastGood.put(normalizedProvider, profileId)
            }

            authFile.writeText(root.toString(2))
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to sync auth profile for provider $normalizedProvider", throwable)
        }
    }

    private fun ensureCodexPrimaryModel() {
        val configFile = File(prootManager.rootfsDir, "root/.openclaw/openclaw.json")
        if (!configFile.exists()) return

        runCatching {
            val json = JSONObject(configFile.readText())
            val agents = json.optJSONObject("agents") ?: JSONObject().also { json.put("agents", it) }
            val defaults = agents.optJSONObject("defaults") ?: JSONObject().also { agents.put("defaults", it) }
            val model = defaults.optJSONObject("model") ?: JSONObject().also { defaults.put("model", it) }
            model.put("primary", "openai-codex/gpt-5.3-codex")

            val models = defaults.optJSONObject("models") ?: JSONObject().also { defaults.put("models", it) }
            if (!models.has("openai-codex/gpt-5.3-codex")) {
                models.put("openai-codex/gpt-5.3-codex", JSONObject())
            }
            configFile.writeText(json.toString(2))
        }
    }

    private fun generateCodeVerifier(): String {
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        return Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateState(): String {
        val random = ByteArray(16)
        SecureRandom().nextBytes(random)
        return random.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun parseQueryString(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("&")
            .mapNotNull { part ->
                val key = part.substringBefore("=", missingDelimiterValue = "").trim()
                if (key.isBlank()) return@mapNotNull null
                val value = part.substringAfter("=", missingDelimiterValue = "")
                key to URLDecoder.decode(value, "UTF-8")
            }
            .toMap()
    }

    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }

    // ── WhatsApp QR ──

    fun startWhatsAppQr() {
        whatsappQrJob?.cancel()
        shouldRestartAfterWhatsAppPairing = false
        _whatsappQrState.value = WhatsAppQrState.Loading
        whatsappQrJob = viewModelScope.launch {
            try {
                val client = GatewayWsClient(prootManager)
                wsClient = client

                val qrData = withContext(Dispatchers.IO) { client.startWhatsAppLogin() }
                if (qrData == null) {
                    val reason = client.getLastCallErrorMessage()
                    val message = if (reason.isNullOrBlank()) {
                        "Failed to get QR code"
                    } else {
                        "Failed to get QR code: $reason"
                    }
                    _whatsappQrState.value = WhatsAppQrState.Error(message)
                    client.close()
                    wsClient = null
                    return@launch
                }

                val isDataUrl = qrData.startsWith("data:image/")
                _whatsappQrState.value = WhatsAppQrState.QrReady(qrData, isDataUrl)

                val success = withContext(Dispatchers.IO) { client.waitWhatsAppLogin() }
                if (success) {
                    shouldRestartAfterWhatsAppPairing = true
                    _whatsappQrState.value = WhatsAppQrState.Connected
                    delay(3000)
                    _whatsappQrState.value = WhatsAppQrState.Idle
                    // 페어링 완료 후 채널 설정 반영
                    restartGatewayIfRunning()
                    shouldRestartAfterWhatsAppPairing = false
                } else {
                    val reason = client.getLastCallErrorMessage()
                    val message = if (reason.isNullOrBlank()) {
                        "Login timed out"
                    } else {
                        "Login failed: $reason"
                    }
                    _whatsappQrState.value = WhatsAppQrState.Error(message)
                }

                client.close()
                wsClient = null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _whatsappQrState.value = WhatsAppQrState.Error(e.message ?: "Unknown error")
                wsClient?.close()
                wsClient = null
            }
        }
    }

    fun cancelWhatsAppQr() {
        whatsappQrJob?.cancel()
        whatsappQrJob = null
        wsClient?.close()
        wsClient = null
        _whatsappQrState.value = WhatsAppQrState.Idle
        // 페어링 완료가 확인된 경우에만 재시작한다.
        if (shouldRestartAfterWhatsAppPairing) {
            restartGatewayIfRunning()
            shouldRestartAfterWhatsAppPairing = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelWhatsAppQr()
    }

    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    fun requestBatteryOptimizationExemption(): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${getApplication<Application>().packageName}")
        }
    }
}

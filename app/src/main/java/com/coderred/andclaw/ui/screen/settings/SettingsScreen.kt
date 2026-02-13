package com.coderred.andclaw.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coderred.andclaw.BuildConfig
import com.coderred.andclaw.R
import com.coderred.andclaw.ui.component.ModelSelectionDialog
import com.coderred.andclaw.ui.component.WhatsAppQrDialog
import com.coderred.andclaw.ui.screen.dashboard.WhatsAppQrState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val autoStartOnBoot by viewModel.autoStartOnBoot.collectAsState()
    val chargeOnlyMode by viewModel.chargeOnlyMode.collectAsState()
    val apiProvider by viewModel.apiProvider.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val isLoadingModels by viewModel.isLoadingModels.collectAsState()
    val modelLoadError by viewModel.modelLoadError.collectAsState()
    val telegramEnabled by viewModel.telegramEnabled.collectAsState()
    val telegramBotToken by viewModel.telegramBotToken.collectAsState()
    val discordEnabled by viewModel.discordEnabled.collectAsState()
    val discordBotToken by viewModel.discordBotToken.collectAsState()
    val braveSearchApiKey by viewModel.braveSearchApiKey.collectAsState()
    val isDoctorFixRunning by viewModel.isDoctorFixRunning.collectAsState()
    val doctorFixResult by viewModel.doctorFixResult.collectAsState()
    val whatsappQrState by viewModel.whatsappQrState.collectAsState()
    val isCodexAuthInProgress by viewModel.isCodexAuthInProgress.collectAsState()
    val isCodexAuthenticated by viewModel.isCodexAuthenticated.collectAsState()
    val codexAuthUrl by viewModel.codexAuthUrl.collectAsState()
    val codexAuthDebugLine by viewModel.codexAuthDebugLine.collectAsState()
    val context = LocalContext.current

    var showModelDialog by remember { mutableStateOf(false) }
    var showProviderDialog by remember { mutableStateOf(false) }
    var showGptSubscriptionDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showRestartHint by remember { mutableStateOf(false) }
    var showBotRestartNotice by remember { mutableStateOf(false) }
    var showTelegramTokenDialog by remember { mutableStateOf(false) }
    var showDiscordTokenDialog by remember { mutableStateOf(false) }
    var showBraveKeyDialog by remember { mutableStateOf(false) }
    var showOssLicensesDialog by remember { mutableStateOf(false) }
    val ossLicensesText = remember {
        runCatching {
            context.resources.openRawResource(R.raw.third_party_licenses)
                .bufferedReader()
                .use { it.readText() }
        }.getOrElse { "Failed to load OSS license notices." }
    }

    LaunchedEffect(codexAuthUrl) {
        val url = codexAuthUrl
        if (!url.isNullOrBlank()) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.settings_cannot_open), Toast.LENGTH_SHORT).show()
            } finally {
                viewModel.consumeCodexAuthUrl()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_cd_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ══════════════════════════════════════
            // Gateway 섹션
            // ══════════════════════════════════════
            SectionHeader(
                title = stringResource(R.string.settings_section_gateway),
                icon = Icons.Default.PowerSettingsNew,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column {
                    SettingToggle(
                        title = stringResource(R.string.settings_auto_start),
                        description = stringResource(R.string.settings_auto_start_desc),
                        checked = autoStartOnBoot,
                        onCheckedChange = viewModel::setAutoStartOnBoot,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    SettingToggle(
                        title = stringResource(R.string.settings_charge_only),
                        description = stringResource(R.string.settings_charge_only_desc),
                        checked = chargeOnlyMode,
                        onCheckedChange = viewModel::setChargeOnlyMode,
                    )
                }
            }

            // ══════════════════════════════════════
            // Battery 섹션
            // ══════════════════════════════════════
            SectionHeader(
                title = stringResource(R.string.settings_section_battery),
                icon = Icons.Default.BatteryAlert,
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        try {
                            context.startActivity(viewModel.requestBatteryOptimizationExemption())
                        } catch (e: Exception) {
                            Toast
                                .makeText(context, context.getString(R.string.settings_cannot_open), Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.BatteryAlert,
                        contentDescription = null,
                        tint = if (viewModel.isBatteryOptimizationIgnored())
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_battery_optimization),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (viewModel.isBatteryOptimizationIgnored())
                                stringResource(R.string.settings_battery_exempted)
                            else
                                stringResource(R.string.settings_battery_not_exempted),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // ══════════════════════════════════════
            // AI 섹션
            // ══════════════════════════════════════
            SectionHeader(
                title = stringResource(R.string.settings_section_ai),
                icon = Icons.Default.Psychology,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column {
                    // Provider
                    SettingClickableRow(
                        title = stringResource(R.string.settings_current_provider),
                        value = when (apiProvider) {
                            "openrouter" -> stringResource(R.string.onboarding_provider_openrouter)
                            "anthropic" -> stringResource(R.string.onboarding_provider_anthropic)
                            "openai", "openai-codex" -> stringResource(R.string.onboarding_provider_openai)
                            "google" -> stringResource(R.string.onboarding_provider_google)
                            else -> apiProvider.replaceFirstChar { it.uppercase() }
                        },
                        onClick = { showProviderDialog = true },
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    // Model
                    if (apiProvider == "openrouter") {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )

                        SettingClickableRow(
                            title = stringResource(R.string.settings_select_model),
                            value = if (selectedModel.isNotBlank()) {
                                selectedModel.removePrefix("openrouter/")
                            } else {
                                stringResource(R.string.settings_model_default)
                            },
                            onClick = {
                                showModelDialog = true
                                viewModel.fetchModels()
                            },
                        )
                    } else if (apiProvider == "openai" || apiProvider == "openai-codex") {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        SettingClickableRow(
                            title = stringResource(R.string.settings_select_model),
                            value = if (apiProvider == "openai-codex") {
                                stringResource(
                                    R.string.settings_provider_openai_codex,
                                    stringResource(R.string.onboarding_provider_openai)
                                )
                            } else {
                                stringResource(
                                    R.string.settings_provider_openai_api,
                                    stringResource(R.string.onboarding_provider_openai)
                                )
                            },
                            onClick = { showGptSubscriptionDialog = true },
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    // API
                    if (apiProvider != "openai-codex") {
                        SettingClickableRow(
                            title = stringResource(R.string.settings_api_key),
                            value = if (apiKey.isNotBlank()) {
                                stringResource(R.string.settings_api_key_configured) + " (${apiKey.take(8)}...)"
                            } else {
                                stringResource(R.string.settings_api_key_not_configured)
                            },
                            valueColor = if (apiKey.isBlank()) MaterialTheme.colorScheme.error else null,
                            onClick = { showApiKeyDialog = true },
                        )
                    } else {
                        SettingClickableRow(
                            title = stringResource(R.string.settings_codex_oauth_title),
                            value = when {
                                isCodexAuthInProgress -> stringResource(R.string.settings_codex_oauth_signing_in)
                                isCodexAuthenticated -> stringResource(R.string.settings_api_key_configured)
                                else -> stringResource(R.string.settings_api_key_not_configured)
                            },
                            valueColor = if (!isCodexAuthenticated && !isCodexAuthInProgress) MaterialTheme.colorScheme.error else null,
                            onClick = { viewModel.loginOpenAiCodexOAuth() },
                        )
                        if (codexAuthDebugLine != null) {
                            Text(
                                text = codexAuthDebugLine ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // ══════════════════════════════════════
            // Tools 섹션
            // ══════════════════════════════════════
            SectionHeader(
                title = stringResource(R.string.settings_section_tools),
                icon = Icons.Default.Search,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column {
                    SettingClickableRow(
                        title = stringResource(R.string.settings_brave_search_key),
                        value = if (braveSearchApiKey.isNotBlank()) {
                            stringResource(R.string.settings_api_key_configured) + " (${braveSearchApiKey.take(8)}...)"
                        } else {
                            stringResource(R.string.settings_brave_search_optional)
                        },
                        onClick = { showBraveKeyDialog = true },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    SettingClickableRow(
                        title = stringResource(R.string.settings_openclaw_doctor_fix),
                        value = if (isDoctorFixRunning) {
                            stringResource(R.string.settings_openclaw_doctor_fix_running)
                        } else {
                            stringResource(R.string.settings_openclaw_doctor_fix_run)
                        },
                        onClick = { viewModel.runOpenClawDoctorFix() },
                    )
                }
            }

            // ══════════════════════════════════════
            // Channels 섹션
            // ══════════════════════════════════════
            SectionHeader(
                title = stringResource(R.string.settings_section_channels),
                icon = Icons.AutoMirrored.Filled.Chat,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column {
                    // WhatsApp (always-on): 토글 없이 QR 연결만 제공
                    SettingClickableRow(
                        title = stringResource(R.string.settings_channel_whatsapp),
                        value = stringResource(R.string.whatsapp_connect_btn),
                        onClick = { viewModel.startWhatsAppQr() },
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    // Telegram
                    SettingToggle(
                        title = stringResource(R.string.settings_channel_telegram),
                        description = stringResource(R.string.settings_telegram_desc),
                        checked = telegramEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setTelegramEnabled(enabled)
                            if (enabled && telegramBotToken.isBlank()) {
                                showTelegramTokenDialog = true
                            }

                        },
                    )

                    if (telegramEnabled) {
                        SettingClickableRow(
                            title = stringResource(R.string.settings_bot_token_title),
                            value = if (telegramBotToken.isNotBlank()) {
                                stringResource(R.string.settings_token_configured) + " (${telegramBotToken.take(8)}...)"
                            } else {
                                stringResource(R.string.settings_token_not_configured)
                            },
                            valueColor = if (telegramBotToken.isBlank()) MaterialTheme.colorScheme.error else null,
                            onClick = { showTelegramTokenDialog = true },
                            indent = true,
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    // Discord
                    SettingToggle(
                        title = stringResource(R.string.settings_channel_discord),
                        description = stringResource(R.string.settings_discord_desc),
                        checked = discordEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setDiscordEnabled(enabled)
                            if (enabled && discordBotToken.isBlank()) {
                                showDiscordTokenDialog = true
                            }

                        },
                    )

                    if (discordEnabled) {
                        SettingClickableRow(
                            title = stringResource(R.string.settings_bot_token_title),
                            value = if (discordBotToken.isNotBlank()) {
                                stringResource(R.string.settings_token_configured) + " (${discordBotToken.take(8)}...)"
                            } else {
                                stringResource(R.string.settings_token_not_configured)
                            },
                            valueColor = if (discordBotToken.isBlank()) MaterialTheme.colorScheme.error else null,
                            onClick = { showDiscordTokenDialog = true },
                            indent = true,
                        )
                    }
                }
            }

            // ══════════════════════════════════════
            // About 섹션
            // ══════════════════════════════════════
            SectionHeader(
                title = stringResource(R.string.settings_section_about),
                icon = Icons.Default.Info,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.settings_about_powered_by),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    SettingClickableRow(
                        title = "Open Source Licenses",
                        value = "View",
                        onClick = { showOssLicensesDialog = true },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // ── Model Selection Dialog ──
    if (showModelDialog) {
        ModelSelectionDialog(
            models = availableModels,
            selectedModelId = selectedModel,
            isLoading = isLoadingModels,
            errorMessage = modelLoadError,
            onSelectModel = { model ->
                viewModel.setSelectedModel(model)
                showModelDialog = false
                viewModel.shouldShowRestartPromptForProvider(apiProvider) { shouldShow ->
                    showRestartHint = shouldShow
                }
            },
            onDismiss = { showModelDialog = false },
            onRetry = { viewModel.fetchModels() },
        )
    }

    // ── Provider Selection Dialog ──
    if (showProviderDialog) {
        ProviderSelectionDialog(
            currentProvider = apiProvider,
            onSelectProvider = { provider ->
                viewModel.setApiProvider(provider)
                showProviderDialog = false
                viewModel.shouldShowRestartPromptForProvider(provider) { shouldShow ->
                    showRestartHint = shouldShow
                }
            },
            onDismiss = { showProviderDialog = false },
        )
    }

    if (showGptSubscriptionDialog) {
        GptSubscriptionDialog(
            currentProvider = apiProvider,
            onSelect = { useCodexOAuth ->
                viewModel.setGptSubscription(useCodexOAuth)
                showGptSubscriptionDialog = false
                val provider = if (useCodexOAuth) "openai-codex" else "openai"
                viewModel.shouldShowRestartPromptForProvider(provider) { shouldShow ->
                    showRestartHint = shouldShow
                }
            },
            onDismiss = { showGptSubscriptionDialog = false },
        )
    }

    // ── API Key Input Dialog ──
    if (showApiKeyDialog) {
        ApiKeyInputDialog(
            currentKey = apiKey,
            provider = apiProvider,
            onSave = { key ->
                viewModel.setApiKey(key)
                showApiKeyDialog = false
                showRestartHint = true
            },
            onDismiss = { showApiKeyDialog = false },
        )
    }

    // ── Telegram Bot Token Dialog ──
    if (showTelegramTokenDialog) {
        BotTokenInputDialog(
            currentToken = telegramBotToken,
            channelName = stringResource(R.string.settings_channel_telegram),
            tokenHint = stringResource(R.string.settings_bot_token_hint),
            helpUrl = "https://core.telegram.org/bots#how-do-i-create-a-bot",
            helpText = stringResource(R.string.settings_bot_token_help, stringResource(R.string.settings_channel_telegram)),
            onSave = { token ->
                viewModel.setTelegramBotToken(token, restartGateway = false)
                showTelegramTokenDialog = false
                showBotRestartNotice = true
            },
            onDismiss = { showTelegramTokenDialog = false },
        )
    }

    // ── Discord Bot Token Dialog ──
    if (showDiscordTokenDialog) {
        BotTokenInputDialog(
            currentToken = discordBotToken,
            channelName = stringResource(R.string.settings_channel_discord),
            tokenHint = stringResource(R.string.settings_bot_token_hint),
            helpUrl = "https://discord.com/developers/applications",
            helpText = stringResource(R.string.settings_bot_token_help, stringResource(R.string.settings_channel_discord)),
            onSave = { token ->
                viewModel.setDiscordBotToken(token, restartGateway = false)
                showDiscordTokenDialog = false
                showBotRestartNotice = true
            },
            onDismiss = { showDiscordTokenDialog = false },
        )
    }

    // ── Brave Search API Key Dialog ──
    if (showBraveKeyDialog) {
        BraveSearchKeyDialog(
            currentKey = braveSearchApiKey,
            onSave = { key ->
                viewModel.setBraveSearchApiKey(key)
                showBraveKeyDialog = false
                showRestartHint = true
            },
            onDismiss = { showBraveKeyDialog = false },
        )
    }

    if (showRestartHint) {
        AlertDialog(
            onDismissRequest = { showRestartHint = false },
            shape = RoundedCornerShape(24.dp),
            text = { Text(stringResource(R.string.settings_restart_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartHint = false
                        viewModel.restartGatewayNow()
                    },
                ) {
                    Text(stringResource(R.string.settings_restart_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartHint = false }) {
                    Text(stringResource(R.string.settings_restart_confirm_no))
                }
            },
        )
    }

    if (showBotRestartNotice) {
        AlertDialog(
            onDismissRequest = { },
            shape = RoundedCornerShape(24.dp),
            text = { Text(stringResource(R.string.settings_bot_restart_notice_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBotRestartNotice = false
                        viewModel.restartGatewayNow()
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (showOssLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showOssLicensesDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Open Source Licenses") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = ossLicensesText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showOssLicensesDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (doctorFixResult != null) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeDoctorFixResult() },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    if (doctorFixResult?.success == true) {
                        stringResource(R.string.settings_openclaw_doctor_fix_success)
                    } else {
                        stringResource(R.string.settings_openclaw_doctor_fix_failed)
                    },
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = doctorFixResult?.output.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeDoctorFixResult() }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    // ── WhatsApp QR Dialog ──
    if (whatsappQrState !is WhatsAppQrState.Idle) {
        WhatsAppQrDialog(
            state = whatsappQrState,
            onDismiss = { viewModel.cancelWhatsAppQr() },
        )
    }
}

// ── Reusable Components ──

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingClickableRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    indent: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = if (indent) 36.dp else 20.dp,
                end = 20.dp,
                top = 14.dp,
                bottom = 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (indent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

// ── Dialogs ──

@Composable
private fun ProviderSelectionDialog(
    currentProvider: String,
    onSelectProvider: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val providers = listOf(
        "openrouter" to stringResource(R.string.onboarding_provider_openrouter),
        "anthropic" to stringResource(R.string.onboarding_provider_anthropic),
        "openai" to stringResource(R.string.onboarding_provider_openai),
        "google" to stringResource(R.string.onboarding_provider_google),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_change_provider)) },
        text = {
            Column {
                providers.forEach { (id, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectProvider(id) }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = if (id == "openai") {
                                currentProvider == "openai" || currentProvider == "openai-codex"
                            } else {
                                currentProvider == id
                            },
                            onClick = { onSelectProvider(id) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@Composable
private fun GptSubscriptionDialog(
    currentProvider: String,
    onSelect: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val openAiLabel = stringResource(R.string.onboarding_provider_openai)
    val apiLabel = stringResource(R.string.settings_provider_openai_api, openAiLabel)
    val codexLabel = stringResource(R.string.settings_provider_openai_codex, openAiLabel)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_select_model)) },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(false) }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentProvider == "openai",
                        onClick = { onSelect(false) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = apiLabel, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(true) }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentProvider == "openai-codex",
                        onClick = { onSelect(true) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = codexLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@Composable
private fun ApiKeyInputDialog(
    currentKey: String,
    provider: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyText by remember { mutableStateOf(currentKey) }
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val settingsUrl = when (provider) {
        "openrouter" -> "https://openrouter.ai/keys"
        "anthropic" -> "https://console.anthropic.com/settings/keys"
        "openai" -> "https://platform.openai.com/api-keys"
        "google" -> "https://aistudio.google.com/apikey"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_api_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(stringResource(R.string.settings_api_key_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
                if (settingsUrl != null) {
                    Text(
                        text = stringResource(R.string.settings_api_key_get_link, provider.replaceFirstChar { it.uppercase() }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(settingsUrl)))
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(keyText.trim()) },
                enabled = keyText.isNotBlank(),
            ) {
                Text(stringResource(R.string.settings_api_key_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@Composable
private fun BotTokenInputDialog(
    currentToken: String,
    channelName: String,
    tokenHint: String,
    helpUrl: String,
    helpText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tokenText by remember { mutableStateOf(currentToken) }
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_bot_token_dialog_title, channelName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text(tokenHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
                Text(
                    text = helpText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl)))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(tokenText.trim()) },
                enabled = tokenText.isNotBlank(),
            ) {
                Text(stringResource(R.string.settings_api_key_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@Composable
private fun BraveSearchKeyDialog(
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyText by remember { mutableStateOf(currentKey) }
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_brave_search_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_brave_search_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(stringResource(R.string.settings_api_key_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.settings_brave_search_get_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://brave.com/search/api/"))
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(keyText.trim()) },
            ) {
                Text(stringResource(R.string.settings_api_key_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

package com.coderred.andclaw.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "andclaw_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private val KEY_API_PROVIDER = stringPreferencesKey("api_provider")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_API_KEY_OPENROUTER = stringPreferencesKey("api_key_openrouter")
        private val KEY_API_KEY_ANTHROPIC = stringPreferencesKey("api_key_anthropic")
        private val KEY_API_KEY_OPENAI = stringPreferencesKey("api_key_openai")
        private val KEY_API_KEY_GOOGLE = stringPreferencesKey("api_key_google")
        private val KEY_AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        private val KEY_CHARGE_ONLY_MODE = booleanPreferencesKey("charge_only_mode")
        private val KEY_OPENCLAW_VERSION = stringPreferencesKey("openclaw_version")
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model")
        private val KEY_SELECTED_MODEL_REASONING = booleanPreferencesKey("selected_model_reasoning")
        private val KEY_SELECTED_MODEL_IMAGES = booleanPreferencesKey("selected_model_images")
        private val KEY_SELECTED_MODEL_CONTEXT = stringPreferencesKey("selected_model_context")
        private val KEY_SELECTED_MODEL_MAX_OUTPUT = stringPreferencesKey("selected_model_max_output")
        private val KEY_WHATSAPP_ENABLED = booleanPreferencesKey("whatsapp_enabled")
        private val KEY_TELEGRAM_ENABLED = booleanPreferencesKey("telegram_enabled")
        private val KEY_TELEGRAM_BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
        private val KEY_DISCORD_ENABLED = booleanPreferencesKey("discord_enabled")
        private val KEY_DISCORD_BOT_TOKEN = stringPreferencesKey("discord_bot_token")
        private val KEY_DISCORD_GUILD_ALLOWLIST = stringPreferencesKey("discord_guild_allowlist")
        private val KEY_DISCORD_REQUIRE_MENTION = booleanPreferencesKey("discord_require_mention")
        private val KEY_BRAVE_SEARCH_API_KEY = stringPreferencesKey("brave_search_api_key")
        private val KEY_GATEWAY_WAS_RUNNING = booleanPreferencesKey("gateway_was_running")
        private val KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION = stringPreferencesKey("bundle_update_fail_count_by_version")
        private val KEY_BUNDLE_UPDATE_LAST_FAIL_AT = longPreferencesKey("bundle_update_last_fail_at")
        private val KEY_BUNDLE_UPDATE_LAST_FAIL_ELAPSED = longPreferencesKey("bundle_update_last_fail_elapsed")
        private val KEY_BUNDLE_UPDATE_LAST_FAIL_VERSION = intPreferencesKey("bundle_update_last_fail_version")
        private val KEY_BUNDLE_UPDATE_LAST_ERROR = stringPreferencesKey("bundle_update_last_error")
        private val KEY_BUNDLE_UPDATE_LAST_FAILURE_TYPE = stringPreferencesKey("bundle_update_last_failure_type")
        private val KEY_BUNDLE_UPDATE_MANUAL_RETRY_USED = booleanPreferencesKey("bundle_update_manual_retry_used")
        private val KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION = intPreferencesKey("bundle_update_manual_retry_version")
        private val KEY_LOG_SECTION_UNLOCKED = booleanPreferencesKey("log_section_unlocked")
    }

    val isSetupComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SETUP_COMPLETE] ?: false
    }.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences()[KEY_SETUP_COMPLETE] ?: false)
        } else {
            throw error
        }
    }

    val isOnboardingComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETE] ?: false
    }.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences()[KEY_ONBOARDING_COMPLETE] ?: false)
        } else {
            throw error
        }
    }

    val apiProvider: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_PROVIDER] ?: "openrouter"
    }

    val apiKey: Flow<String> = combine(
        apiProvider,
        context.dataStore.data,
    ) { provider, prefs ->
        val legacy = prefs[KEY_API_KEY] ?: ""
        when (provider) {
            "openrouter" -> prefs[KEY_API_KEY_OPENROUTER] ?: legacy
            "anthropic" -> prefs[KEY_API_KEY_ANTHROPIC] ?: ""
            "openai" -> prefs[KEY_API_KEY_OPENAI] ?: ""
            "google" -> prefs[KEY_API_KEY_GOOGLE] ?: ""
            else -> legacy
        }
    }

    val autoStartOnBoot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_START_ON_BOOT] ?: false
    }

    val chargeOnlyMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CHARGE_ONLY_MODE] ?: false
    }

    val openClawVersion: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_OPENCLAW_VERSION] ?: ""
    }

    val selectedModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL] ?: ""
    }

    val selectedModelReasoning: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL_REASONING] ?: false
    }

    val selectedModelImages: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL_IMAGES] ?: false
    }

    val selectedModelContext: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL_CONTEXT]?.toIntOrNull() ?: 200000
    }

    val selectedModelMaxOutput: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL_MAX_OUTPUT]?.toIntOrNull() ?: 4096
    }

    suspend fun setSetupComplete(complete: Boolean) {
        context.dataStore.edit { it[KEY_SETUP_COMPLETE] = complete }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING_COMPLETE] = complete }
    }

    suspend fun setApiProvider(provider: String) {
        context.dataStore.edit { it[KEY_API_PROVIDER] = provider }
    }

    suspend fun setApiKey(key: String) {
        val provider = apiProvider.first()
        context.dataStore.edit {
            // 레거시 키 유지 (하위 호환)
            it[KEY_API_KEY] = key
            when (provider) {
                "openrouter" -> it[KEY_API_KEY_OPENROUTER] = key
                "anthropic" -> it[KEY_API_KEY_ANTHROPIC] = key
                "openai", "openai-codex" -> it[KEY_API_KEY_OPENAI] = key
                "google" -> it[KEY_API_KEY_GOOGLE] = key
                else -> { /* no-op */ }
            }
        }
    }

    suspend fun hasApiKeyForProvider(provider: String): Boolean {
        val snapshot = context.dataStore.data.first()
        val legacy = snapshot[KEY_API_KEY].orEmpty()
        val key = when (provider) {
            "openrouter" -> snapshot[KEY_API_KEY_OPENROUTER] ?: legacy
            "anthropic" -> snapshot[KEY_API_KEY_ANTHROPIC].orEmpty()
            "openai" -> snapshot[KEY_API_KEY_OPENAI].orEmpty()
            "google" -> snapshot[KEY_API_KEY_GOOGLE].orEmpty()
            else -> legacy
        }
        return key.isNotBlank()
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_START_ON_BOOT] = enabled }
    }

    suspend fun setChargeOnlyMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CHARGE_ONLY_MODE] = enabled }
    }

    suspend fun setOpenClawVersion(version: String) {
        context.dataStore.edit { it[KEY_OPENCLAW_VERSION] = version }
    }

    suspend fun setSelectedModel(model: OpenRouterModel) {
        context.dataStore.edit {
            it[KEY_SELECTED_MODEL] = model.id
            it[KEY_SELECTED_MODEL_REASONING] = model.supportsReasoning
            it[KEY_SELECTED_MODEL_IMAGES] = model.supportsImages
            it[KEY_SELECTED_MODEL_CONTEXT] = model.contextLength.toString()
            it[KEY_SELECTED_MODEL_MAX_OUTPUT] = model.maxOutputTokens.toString()
        }
    }

    suspend fun setSelectedModelId(modelId: String) {
        context.dataStore.edit { it[KEY_SELECTED_MODEL] = modelId }
    }

    // ── Brave Search ──

    val braveSearchApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_BRAVE_SEARCH_API_KEY] ?: ""
    }

    suspend fun setBraveSearchApiKey(key: String) {
        context.dataStore.edit { it[KEY_BRAVE_SEARCH_API_KEY] = key }
    }

    // ── Channel settings ──

    // WhatsApp는 항상 활성화 상태로 고정한다.
    val whatsappEnabled: Flow<Boolean> = context.dataStore.data.map { true }

    val telegramEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_TELEGRAM_ENABLED] ?: false
    }

    val telegramBotToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TELEGRAM_BOT_TOKEN] ?: ""
    }

    val discordEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DISCORD_ENABLED] ?: false
    }

    val discordBotToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DISCORD_BOT_TOKEN] ?: ""
    }

    val discordGuildAllowlist: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DISCORD_GUILD_ALLOWLIST] ?: ""
    }

    val discordRequireMention: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DISCORD_REQUIRE_MENTION] ?: true
    }

    val channelConfig: Flow<ChannelConfig> = combine(
        whatsappEnabled,
        telegramEnabled,
        telegramBotToken,
        discordEnabled,
        discordBotToken,
    ) { wa, tgEnabled, tgToken, dcEnabled, dcToken ->
        ChannelConfig(
            whatsappEnabled = wa,
            telegramEnabled = tgEnabled,
            telegramBotToken = tgToken,
            discordEnabled = dcEnabled,
            discordBotToken = dcToken,
        )
    }
        .combine(discordGuildAllowlist) { config, dcGuildAllowlist ->
            config.copy(discordGuildAllowlist = dcGuildAllowlist)
        }
        .combine(discordRequireMention) { config, dcRequireMention ->
            config.copy(discordRequireMention = dcRequireMention)
        }

    suspend fun setWhatsappEnabled(enabled: Boolean) {
        // no-op: WhatsApp는 always-on 정책
    }

    suspend fun setTelegramEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TELEGRAM_ENABLED] = enabled }
    }

    suspend fun setTelegramBotToken(token: String) {
        context.dataStore.edit { it[KEY_TELEGRAM_BOT_TOKEN] = token }
    }

    suspend fun setDiscordEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DISCORD_ENABLED] = enabled }
    }

    suspend fun setDiscordBotToken(token: String) {
        context.dataStore.edit { it[KEY_DISCORD_BOT_TOKEN] = token }
    }

    suspend fun setDiscordGuildAllowlist(raw: String) {
        context.dataStore.edit { it[KEY_DISCORD_GUILD_ALLOWLIST] = raw }
    }

    suspend fun setDiscordRequireMention(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DISCORD_REQUIRE_MENTION] = enabled }
    }

    // ── Gateway running state (앱 업데이트 후 자동 재시작용) ──

    val gatewayWasRunning: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_GATEWAY_WAS_RUNNING] ?: false
    }

    suspend fun setGatewayWasRunning(running: Boolean) {
        context.dataStore.edit { it[KEY_GATEWAY_WAS_RUNNING] = running }
    }

    val logSectionUnlocked: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_LOG_SECTION_UNLOCKED] ?: false
    }

    suspend fun setLogSectionUnlocked(unlocked: Boolean) {
        context.dataStore.edit { it[KEY_LOG_SECTION_UNLOCKED] = unlocked }
    }

    suspend fun getBundleUpdateFailure(currentVersion: Int): BundleUpdateFailureRecord {
        val snapshot = context.dataStore.data.first()
        val failCountByVersion = decodeFailCountByVersion(snapshot[KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION])
        val lastFailVersion = snapshot[KEY_BUNDLE_UPDATE_LAST_FAIL_VERSION]
        val manualRetryVersion = snapshot[KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION]
        return BundleUpdateFailureRecord(
            failCountForCurrentVersion = failCountByVersion[currentVersion] ?: 0,
            lastFailAtEpochMs = snapshot[KEY_BUNDLE_UPDATE_LAST_FAIL_AT],
            lastFailElapsedMs = snapshot[KEY_BUNDLE_UPDATE_LAST_FAIL_ELAPSED],
            lastFailVersion = lastFailVersion,
            lastError = snapshot[KEY_BUNDLE_UPDATE_LAST_ERROR],
            lastFailureType = snapshot[KEY_BUNDLE_UPDATE_LAST_FAILURE_TYPE],
            manualRetryUsed = (snapshot[KEY_BUNDLE_UPDATE_MANUAL_RETRY_USED] ?: false) &&
                manualRetryVersion == currentVersion &&
                lastFailVersion == currentVersion,
        )
    }

    suspend fun recordBundleUpdateFailure(
        currentVersion: Int,
        failureType: String,
        errorMessage: String,
        nowEpochMs: Long,
        nowElapsedMs: Long,
    ) {
        context.dataStore.edit { prefs ->
            val counts = decodeFailCountByVersion(prefs[KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION]).toMutableMap()
            val nextCount = (counts[currentVersion] ?: 0) + 1
            val previousFailVersion = prefs[KEY_BUNDLE_UPDATE_LAST_FAIL_VERSION]
            counts[currentVersion] = nextCount
            prefs[KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION] = encodeFailCountByVersion(counts)
            prefs[KEY_BUNDLE_UPDATE_LAST_FAIL_AT] = nowEpochMs
            prefs[KEY_BUNDLE_UPDATE_LAST_FAIL_ELAPSED] = nowElapsedMs
            prefs[KEY_BUNDLE_UPDATE_LAST_FAIL_VERSION] = currentVersion
            prefs[KEY_BUNDLE_UPDATE_LAST_ERROR] = errorMessage
            prefs[KEY_BUNDLE_UPDATE_LAST_FAILURE_TYPE] = failureType
            if (previousFailVersion != currentVersion) {
                prefs[KEY_BUNDLE_UPDATE_MANUAL_RETRY_USED] = false
                prefs.remove(KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION)
            }
        }
    }

    suspend fun setBundleUpdateManualRetryUsed(currentVersion: Int, used: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BUNDLE_UPDATE_MANUAL_RETRY_USED] = used
            if (used) {
                prefs[KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION] = currentVersion
            } else {
                prefs.remove(KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION)
            }
        }
    }

    suspend fun clearBundleUpdateFailure(currentVersion: Int) {
        context.dataStore.edit { prefs ->
            val counts = decodeFailCountByVersion(prefs[KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION]).toMutableMap()
            counts.remove(currentVersion)
            prefs[KEY_BUNDLE_UPDATE_FAIL_COUNT_BY_VERSION] = encodeFailCountByVersion(counts)
            prefs.remove(KEY_BUNDLE_UPDATE_LAST_FAIL_AT)
            prefs.remove(KEY_BUNDLE_UPDATE_LAST_FAIL_ELAPSED)
            prefs.remove(KEY_BUNDLE_UPDATE_LAST_FAIL_VERSION)
            prefs.remove(KEY_BUNDLE_UPDATE_LAST_ERROR)
            prefs.remove(KEY_BUNDLE_UPDATE_LAST_FAILURE_TYPE)
            prefs[KEY_BUNDLE_UPDATE_MANUAL_RETRY_USED] = false
            prefs.remove(KEY_BUNDLE_UPDATE_MANUAL_RETRY_VERSION)
        }
    }

    private fun decodeFailCountByVersion(raw: String?): Map<Int, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val version = key.toIntOrNull() ?: continue
                    val count = json.optInt(key, 0)
                    if (count > 0) put(version, count)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeFailCountByVersion(map: Map<Int, Int>): String {
        val out = org.json.JSONObject()
        map.forEach { (version, count) ->
            if (count > 0) {
                out.put(version.toString(), count)
            }
        }
        return out.toString()
    }
}

data class BundleUpdateFailureRecord(
    val failCountForCurrentVersion: Int,
    val lastFailAtEpochMs: Long?,
    val lastFailElapsedMs: Long?,
    val lastFailVersion: Int?,
    val lastError: String?,
    val lastFailureType: String?,
    val manualRetryUsed: Boolean,
)

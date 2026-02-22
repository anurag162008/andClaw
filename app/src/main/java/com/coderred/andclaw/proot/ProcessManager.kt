package com.coderred.andclaw.proot

import android.os.Build
import android.os.FileObserver
import android.util.Log
import com.coderred.andclaw.data.ChannelConfig
import com.coderred.andclaw.data.GatewayState
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.PairingRequest
import com.coderred.andclaw.data.SessionLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * proot 환경에서 OpenClaw 게이트웨이 프로세스를 관리한다.
 *
 * - start(): proot 내에서 openclaw 프로세스 시작
 * - stop(): 프로세스 종료
 * - restart(): 재시작
 * - stdout/stderr 스트림을 로그로 수집
 * - 프로세스 상태를 StateFlow 로 실시간 노출
 */
class ProcessManager(
    private val prootManager: ProotManager,
) {
    companion object {
        private const val TAG = "ProcessManager"
    }

    private val _gatewayState = MutableStateFlow(GatewayState())
    val gatewayState: StateFlow<GatewayState> = _gatewayState.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private var process: Process? = null
    private var outputJob: Job? = null
    private var uptimeJob: Job? = null
    private var startTime: Long = 0L
    private var scope: CoroutineScope? = null

    private val _dashboardUrl = MutableStateFlow<String?>(null)
    val dashboardUrl: StateFlow<String?> = _dashboardUrl.asStateFlow()

    private val _pairingRequests = MutableStateFlow<List<PairingRequest>>(emptyList())
    val pairingRequests: StateFlow<List<PairingRequest>> = _pairingRequests.asStateFlow()

    private var pairingFileObserver: FileObserver? = null
    private var lastChannelConfig: ChannelConfig = ChannelConfig()

    val isRunning: Boolean
        get() = _gatewayState.value.status == GatewayStatus.RUNNING

    /**
     * OpenClaw 게이트웨이를 시작한다.
     *
     * @param apiProvider AI 모델 공급자 (anthropic, openai, openrouter)
     * @param apiKey API 키
     * @param selectedModel 사용자가 선택한 모델 ID (빈 문자열이면 기본값 사용)
     */
    fun start(
        apiProvider: String = "",
        apiKey: String = "",
        selectedModel: String = "",
        channelConfig: ChannelConfig = ChannelConfig(),
        modelReasoning: Boolean = false,
        modelImages: Boolean = false,
        modelContext: Int = 200000,
        modelMaxOutput: Int = 4096,
        braveSearchApiKey: String = "",
    ) {
        if (isRunning) return

        lastChannelConfig = channelConfig

        _gatewayState.value = _gatewayState.value.copy(
            status = GatewayStatus.STARTING,
            errorMessage = null,
        )
        addLog("[andClaw] Starting gateway...")

        // 새 scope 생성
        scope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        newScope.launch {
            try {
                // 이전 게이트웨이 프로세스 정리 (앱 재설치 등으로 좀비 프로세스 남아있을 수 있음)
                killOrphanGatewayProcesses()

                // 패치 파일이 없으면 생성
                ensurePatchFile()

                // config 파일 생성/갱신 (모델, 게이트웨이, 브라우저 설정)
                ensureOpenClawConfig(apiProvider, apiKey, selectedModel, modelReasoning, modelImages, modelContext, modelMaxOutput)

                // 채널 설정 기록
                ensureChannelConfig(channelConfig)

                // proot 명령어 구성
                val cmd = prootManager.buildGatewayCommand()

                // 환경변수 구성 (API 키 포함)
                val extraEnv = buildMap {
                    put("HOME", "/root")
                    put("PATH", "/usr/local/bin:/usr/bin:/bin")
                    put("LANG", "C.UTF-8")
                    put("UV_USE_IO_URING", "0")
                    put("PLAYWRIGHT_BROWSERS_PATH", "/root/.cache/ms-playwright")

                    // API 키를 환경변수로 전달
                    if (apiKey.isNotBlank()) {
                        when (apiProvider) {
                            "anthropic" -> put("ANTHROPIC_API_KEY", apiKey)
                            "openai" -> put("OPENAI_API_KEY", apiKey)
                            "openai-codex" -> { /* OAuth provider: no API key env needed */ }
                            "openrouter" -> put("OPENROUTER_API_KEY", apiKey)
                            "google" -> {
                                put("GEMINI_API_KEY", apiKey)
                                put("GOOGLE_API_KEY", apiKey)
                            }
                            else -> put("OPENROUTER_API_KEY", apiKey) // 기본
                        }
                    }

                    // Brave Search API 키
                    if (braveSearchApiKey.isNotBlank()) {
                        put("BRAVE_API_KEY", braveSearchApiKey)
                    }

                    // 채널 봇 토큰
                    if (channelConfig.telegramEnabled && channelConfig.telegramBotToken.isNotBlank()) {
                        put("TELEGRAM_BOT_TOKEN", channelConfig.telegramBotToken)
                    }
                    if (channelConfig.discordEnabled && channelConfig.discordBotToken.isNotBlank()) {
                        put("DISCORD_BOT_TOKEN", channelConfig.discordBotToken)
                    }
                }
                val env = prootManager.buildEnvironment(extraEnv)

                // 프로세스 시작
                val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                pb.environment().putAll(env)

                addLog("[andClaw] Command: ${cmd.joinToString(" ")}")

                process = pb.start()
                startTime = System.currentTimeMillis()

                _gatewayState.value = _gatewayState.value.copy(
                    status = GatewayStatus.STARTING,
                    pid = getProcessId(process),
                    dashboardReady = false,
                )
                addLog("[andClaw] Gateway process started (PID: ${_gatewayState.value.pid ?: "?"})")

                // stdout/stderr 스트림 읽기
                outputJob = newScope.launch {
                    readProcessOutput(process!!)
                }

                // uptime 카운터
                uptimeJob = newScope.launch {
                    while (isActive) {
                        delay(1000)
                        val uptime = (System.currentTimeMillis() - startTime) / 1000
                        _gatewayState.value = _gatewayState.value.copy(uptime = uptime)
                    }
                }

                // 프로세스 종료 대기
                val exitCode = withContext(Dispatchers.IO) {
                    process!!.waitFor()
                }

                uptimeJob?.cancel()
                addLog("[andClaw] Gateway process exited (exit code: $exitCode)")

                // 예기치 않은 종료 (사용자가 stop()을 호출하지 않았는데 종료된 경우)
                if (_gatewayState.value.status == GatewayStatus.RUNNING ||
                    _gatewayState.value.status == GatewayStatus.STARTING) {
                    _gatewayState.value = _gatewayState.value.copy(
                        status = GatewayStatus.ERROR,
                        errorMessage = "Process terminated unexpectedly (exit: $exitCode)",
                        pid = null,
                    )
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // 정상적인 취소 (재시작 등) — 에러로 표시하지 않음
            } catch (e: Exception) {
                addLog("[andClaw] Error: ${e.message}")
                _gatewayState.value = _gatewayState.value.copy(
                    status = GatewayStatus.ERROR,
                    errorMessage = e.message,
                    pid = null,
                )
            }
        }
    }

    /**
     * 게이트웨이 프로세스를 중지한다.
     */
    fun stop() {
        val currentStatus = _gatewayState.value.status
        if (currentStatus != GatewayStatus.RUNNING && currentStatus != GatewayStatus.STARTING) return

        _gatewayState.value = _gatewayState.value.copy(status = GatewayStatus.STOPPING)
        addLog("[andClaw] Stopping gateway...")

        outputJob?.cancel()
        uptimeJob?.cancel()
        stopPairingObserver()

        // 프로세스 트리 전체 종료
        process?.let { proc ->
            proc.destroyForcibly()
        }
        process = null

        _gatewayState.value = GatewayState(status = GatewayStatus.STOPPED)
        addLog("[andClaw] Gateway stopped")
    }

    /**
     * 게이트웨이를 재시작한다.
     */
    fun restart(
        apiProvider: String = "",
        apiKey: String = "",
        selectedModel: String = "",
        channelConfig: ChannelConfig = ChannelConfig(),
        modelReasoning: Boolean = false,
        modelImages: Boolean = false,
        modelContext: Int = 200000,
        modelMaxOutput: Int = 4096,
        braveSearchApiKey: String = "",
    ) {
        stop()
        start(apiProvider, apiKey, selectedModel, channelConfig, modelReasoning, modelImages, modelContext, modelMaxOutput, braveSearchApiKey)
    }

    /**
     * 리소스 정리
     */
    fun destroy() {
        stop()
        stopPairingObserver()
        scope?.cancel()
        scope = null
    }

    /**
     * 세션 로그에서 최근 메시지 엔트리를 읽어 반환한다.
     * sessions/ 디렉토리에서 가장 최신 JSONL 파일을 파싱한다.
     */
    fun getSessionLogEntries(): List<SessionLogEntry> {
        val sessionsDir = File(prootManager.rootfsDir, "root/.openclaw/agents/main/sessions")
        if (!sessionsDir.exists()) return emptyList()

        try {
            // 가장 최근 수정된 .jsonl 파일 찾기
            val jsonlFiles = sessionsDir.listFiles { f -> f.extension == "jsonl" }
                ?.sortedByDescending { it.lastModified() }
                ?: return emptyList()

            val entries = mutableListOf<SessionLogEntry>()

            for (file in jsonlFiles) {
                if (entries.size >= 50) break

                val lines = file.readLines().filter { it.isNotBlank() }
                for (line in lines.reversed()) {
                    if (entries.size >= 50) break
                    try {
                        val json = JSONObject(line)
                        val type = json.optString("type", "")
                        if (type != "message") continue

                        val msg = json.optJSONObject("message") ?: continue
                        val role = msg.optString("role", "")
                        if (role.isBlank()) continue

                        val model = msg.optString("model", "").ifBlank { null }
                        val stopReason = msg.optString("stopReason", "").ifBlank { null }
                        val errorMessage = msg.optString("errorMessage", "").ifBlank { null }
                        val timestamp = json.optString("timestamp", "").ifBlank {
                            json.optString("ts", "")
                        }

                        // content 미리보기 추출
                        val contentPreview = extractContentPreview(msg)

                        // 토큰 사용량
                        val usage = msg.optJSONObject("usage")
                        val tokenUsage = if (usage != null) {
                            usage.optInt("totalTokens", 0).let {
                                if (it > 0) it else usage.optInt("outputTokens", 0) + usage.optInt("inputTokens", 0)
                            }
                        } else 0

                        entries.add(
                            SessionLogEntry(
                                timestamp = timestamp,
                                role = role,
                                model = model,
                                contentPreview = contentPreview,
                                errorMessage = errorMessage,
                                stopReason = stopReason,
                                tokenUsage = tokenUsage,
                            )
                        )
                    } catch (_: Exception) {
                        // 파싱 실패한 줄은 무시
                    }
                }
            }

            return entries
        } catch (e: Exception) {
            addLog("[andClaw] Failed to read session logs: ${e.message}")
            return emptyList()
        }
    }

    /**
     * 메시지 JSON에서 content 미리보기를 추출한다.
     * content가 문자열이면 직접, 배열이면 첫 text 블록을 사용.
     */
    private fun extractContentPreview(msg: JSONObject): String? {
        // content가 문자열인 경우
        val contentStr = msg.optString("content", "")
        if (contentStr.isNotBlank()) {
            return contentStr.take(100)
        }

        // content가 배열인 경우
        val contentArr = msg.optJSONArray("content")
        if (contentArr != null) {
            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i) ?: continue
                if (block.optString("type", "") == "text") {
                    val text = block.optString("text", "")
                    if (text.isNotBlank()) return text.take(100)
                }
            }
        }

        return null
    }

    // ── 내부 헬퍼 ──

    /**
     * OpenClaw config에서 모델을 사용자의 provider에 맞게 설정한다.
     *
     * @param apiProvider AI 모델 공급자 (anthropic, openai, openrouter)
     * @param selectedModel 사용자가 선택한 모델 ID (빈 문자열이면 기본값 사용)
     * @param modelReasoning 모델 reasoning 지원 여부
     * @param modelImages 모델 이미지 입력 지원 여부
     * @param modelContext 모델 컨텍스트 윈도우 크기
     * @param modelMaxOutput 모델 최대 출력 토큰 수
     */
    fun ensureOpenClawConfig(
        apiProvider: String,
        apiKey: String = "",
        selectedModel: String = "",
        modelReasoning: Boolean = false,
        modelImages: Boolean = false,
        modelContext: Int = 200000,
        modelMaxOutput: Int = 4096,
    ) {
        val configFile = File(prootManager.rootfsDir, "root/.openclaw/openclaw.json")

        try {
            val json = if (configFile.exists()) {
                JSONObject(configFile.readText())
            } else {
                configFile.parentFile?.mkdirs()
                addLog("[andClaw] Creating minimal openclaw config")
                val token = java.security.SecureRandom().let { sr ->
                    ByteArray(24).also { sr.nextBytes(it) }
                        .joinToString("") { "%02x".format(it) }
                }
                JSONObject().apply {
                    put("agents", JSONObject().put("defaults", JSONObject()))
                    put("gateway", JSONObject().apply {
                        put("auth", JSONObject().apply {
                            put("mode", "token")
                            put("token", token)
                        })
                    })
                }
            }

            // agents.defaults가 없으면 생성
            val agents = json.optJSONObject("agents") ?: JSONObject().also { json.put("agents", it) }
            val defaults = agents.optJSONObject("defaults") ?: JSONObject().also { agents.put("defaults", it) }

            // 현재 모델 확인
            val modelObj = defaults.optJSONObject("model")
            val currentModel = modelObj?.optString("primary", "") ?: ""

            // provider에 맞는 기본 모델 결정
            // dialog의 model ID는 API 원본 (e.g. "openrouter/free", "qwen/qwen3-coder:free")
            // OpenClaw은 "provider/model_id" 형식 (e.g. "openrouter/openrouter/free")
            val targetModel = if (selectedModel.isNotBlank()) {
                when (apiProvider) {
                    "openrouter" -> "openrouter/$selectedModel"
                    "anthropic" -> {
                        val id = when {
                            selectedModel.startsWith("anthropic/") -> selectedModel.removePrefix("anthropic/")
                            selectedModel.contains("/") -> "claude-sonnet-4-5"
                            else -> selectedModel
                        }
                        "anthropic/$id"
                    }
                    "openai" -> {
                        val id = when {
                            selectedModel.startsWith("openai/") -> selectedModel.removePrefix("openai/")
                            selectedModel.contains("/") -> "gpt-5-mini"
                            else -> selectedModel
                        }
                        "openai/$id"
                    }
                    "openai-codex" -> {
                        val id = when {
                            selectedModel.startsWith("openai/") -> selectedModel.removePrefix("openai/")
                            selectedModel.startsWith("openai-codex/") -> selectedModel.removePrefix("openai-codex/")
                            else -> "gpt-5.3-codex"
                        }
                        "openai-codex/$id"
                    }
                    "google" -> {
                        val id = when {
                            selectedModel.startsWith("google/") -> selectedModel.removePrefix("google/")
                            selectedModel.contains("/") -> "gemini-2.5-flash"
                            else -> selectedModel
                        }
                        "google/$id"
                    }
                    else -> "openrouter/$selectedModel"
                }
            } else {
                when (apiProvider) {
                    "openrouter" -> "openrouter/openrouter/free"
                    "anthropic" -> "anthropic/claude-sonnet-4-5"
                    "openai" -> "openai/gpt-5-mini"
                    "openai-codex" -> "openai-codex/gpt-5.3-codex"
                    "google" -> "google/gemini-2.5-flash"
                    else -> "openrouter/openrouter/free"
                }
            }

            // Playwright Chromium 바이너리 경로 탐색 + proot wrapper 생성
            var changed = false
            val playwrightDir = File(prootManager.rootfsDir, "root/.cache/ms-playwright")
            var chromePath: String? = null
            if (playwrightDir.exists()) {
                // 1순위: headless_shell, 2순위: chrome
                val binary = playwrightDir.walkTopDown()
                    .firstOrNull { it.name == "headless_shell" && it.parentFile?.name == "chrome-linux" && it.canExecute() }
                    ?: playwrightDir.walkTopDown()
                        .firstOrNull { it.name == "chrome" && it.parentFile?.name == "chrome-linux" && it.canExecute() }

                binary?.let { bin ->
                    val realPath = "/" + bin.toRelativeString(prootManager.rootfsDir).replace('\\', '/')
                    // proot에서 필수 Chromium 플래그를 주입하는 wrapper 스크립트 생성
                    val wrapperPath = realPath.substringBeforeLast('/') + "/chromium-proot-wrapper.sh"
                    chromePath = wrapperPath
                    ensureBrowserWrapper(bin.parentFile!!, realPath)
                }
            }

            // 브라우저 설정
            val browserObj = json.optJSONObject("browser")
            if (browserObj == null || !browserObj.has("defaultProfile")) {
                json.put("browser", JSONObject().apply {
                    put("headless", true)
                    put("noSandbox", true)
                    put("defaultProfile", "openclaw")
                    if (chromePath != null) {
                        put("executablePath", chromePath)
                    }
                })
                addLog("[andClaw] Browser config added (executablePath=$chromePath)")
                changed = true
            } else if (chromePath != null) {
                val currentExe = browserObj.optString("executablePath", "")
                if (currentExe != chromePath) {
                    browserObj.put("executablePath", chromePath)
                    addLog("[andClaw] Browser executablePath updated: $chromePath")
                    changed = true
                }
            }

            // 모델이 설정 안 됐거나 다른 모델이면 업데이트
            if (currentModel.isBlank() || currentModel != targetModel) {
                addLog("[andClaw] Model config updated: $targetModel")
                val newModelObj = JSONObject().apply {
                    put("primary", targetModel)
                }
                defaults.put("model", newModelObj)

                // models 목록도 추가
                val modelsObj = JSONObject().apply {
                    put(targetModel, JSONObject())
                }
                defaults.put("models", modelsObj)
                changed = true
            }

            // OpenRouter 모델 등록:
            // 내장 모델(models.generated.js에 정의됨)은 compat 설정 포함 정확한 정의를 갖고 있으므로
            // 커스텀 등록으로 덮어쓰면 안 된다. 비내장 모델만 models.json에 등록.
            if (apiProvider == "openrouter") {
                val modelId = if (selectedModel.isNotBlank()) selectedModel else "openrouter/free"
                val builtInIds = getBuiltInOpenRouterModelIds()
                val modelsJsonFile = File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/models.json")

                if (builtInIds.contains(modelId)) {
                    // 내장 모델: models.json 제거 (이전 커스텀 등록이 내장 정의를 덮어쓰지 않도록)
                    if (modelsJsonFile.exists()) modelsJsonFile.delete()
                    addLog("[andClaw] Model '$modelId' is built-in - using native definition")
                } else {
                    // 비내장 모델: models.json에 커스텀 등록 (ModelRegistry가 직접 읽음)
                    writeModelsJson(modelId, modelReasoning, modelImages, modelContext, modelMaxOutput)
                    addLog("[andClaw] Model '$modelId' not built-in - registered in models.json")
                }
                // openclaw.json에서 models.providers 섹션 제거 (models.json으로 이관)
                json.optJSONObject("models")?.remove("providers")
                changed = true
            }

            // gateway 설정 (mode 및 controlUi.allowedOrigins)
            val gateway = json.optJSONObject("gateway") ?: JSONObject().also { json.put("gateway", it) }

            // gateway.mode 설정 (필수 - 미설정 시 게이트웨이 시작 불가)
            if (!gateway.has("mode")) {
                gateway.put("mode", "local")
                changed = true
            }

            // gateway.controlUi.allowedOrigins 설정 (앱 내 WebSocket 연결 허용)
            val controlUi = gateway.optJSONObject("controlUi") ?: JSONObject().also { gateway.put("controlUi", it) }
            if (!controlUi.has("allowedOrigins")) {
                controlUi.put("allowedOrigins", org.json.JSONArray().apply { put("*") })
                changed = true
            }

            // 현재 번들에 없는 플러그인 엔트리 정리 (게이트웨이 부팅 실패 방지)
            val plugins = json.optJSONObject("plugins")
            val entries = plugins?.optJSONObject("entries")
            if (entries != null) {
                if (entries.has("codex-cli")) {
                    entries.remove("codex-cli")
                    changed = true
                }
                if (entries.has("openai-codex")) {
                    entries.remove("openai-codex")
                    changed = true
                }
            }

            if (changed) {
                configFile.writeText(json.toString(2))
            }
        } catch (e: Exception) {
            addLog("[andClaw] Config update failed: ${e.message}")
        }
    }

    /**
     * openclaw.json에 채널 설정 블록을 기록한다.
     */
    fun ensureChannelConfig(channelConfig: ChannelConfig) {
        val configFile = File(prootManager.rootfsDir, "root/.openclaw/openclaw.json")

        try {
            val json = if (configFile.exists()) {
                JSONObject(configFile.readText())
            } else {
                configFile.parentFile?.mkdirs()
                addLog("[andClaw] Creating minimal openclaw config for channel setup")
                JSONObject().apply {
                    put("agents", JSONObject().put("defaults", JSONObject()))
                    put("gateway", JSONObject())
                }
            }
            val channels = JSONObject()

            if (channelConfig.whatsappEnabled) {
                channels.put("whatsapp", JSONObject().apply {
                    put("dmPolicy", "pairing")
                    put("accounts", JSONObject().apply {
                        put("default", JSONObject())
                    })
                })
            }

            if (channelConfig.telegramEnabled && channelConfig.telegramBotToken.isNotBlank()) {
                channels.put("telegram", JSONObject().apply {
                    put("botToken", "\${TELEGRAM_BOT_TOKEN}")
                    put("dmPolicy", "pairing")
                })
            }

            if (channelConfig.discordEnabled && channelConfig.discordBotToken.isNotBlank()) {
                val guildAllowlist = parseDiscordGuildAllowlist(channelConfig.discordGuildAllowlist)
                channels.put("discord", JSONObject().apply {
                    put("token", "\${DISCORD_BOT_TOKEN}")
                    put("dmPolicy", "pairing")
                    // Explicitly disable guild handling when allowlist is empty (no implicit open fallback).
                    put("groupPolicy", if (guildAllowlist.isNotEmpty()) "allowlist" else "disabled")
                    if (guildAllowlist.isNotEmpty()) {
                        put("guilds", JSONObject().apply {
                            guildAllowlist.forEach { guildId ->
                                put(guildId, JSONObject().apply {
                                    put("requireMention", channelConfig.discordRequireMention)
                                })
                            }
                        })
                    }
                })
                if (guildAllowlist.isEmpty()) {
                    addLog("[andClaw] Discord guild allowlist is empty: guild messages are blocked")
                } else {
                    addLog("[andClaw] Discord guild allowlist applied: ${guildAllowlist.size} guild(s)")
                }
            }

            if (channels.length() > 0) {
                json.put("channels", channels)
                addLog("[andClaw] Channel config: ${channels.keys().asSequence().joinToString(", ")}")
            } else {
                json.remove("channels")
            }

            // 채널에 맞는 플러그인 활성화/비활성화
            val plugins = json.optJSONObject("plugins") ?: JSONObject().also { json.put("plugins", it) }
            val entries = plugins.optJSONObject("entries") ?: JSONObject().also { plugins.put("entries", it) }

            // Telegram
            val tgPlugin = entries.optJSONObject("telegram") ?: JSONObject()
            tgPlugin.put("enabled", channelConfig.telegramEnabled && channelConfig.telegramBotToken.isNotBlank())
            entries.put("telegram", tgPlugin)

            // Discord
            val dcPlugin = entries.optJSONObject("discord") ?: JSONObject()
            dcPlugin.put("enabled", channelConfig.discordEnabled && channelConfig.discordBotToken.isNotBlank())
            entries.put("discord", dcPlugin)

            // WhatsApp
            val waPlugin = entries.optJSONObject("whatsapp") ?: JSONObject()
            waPlugin.put("enabled", channelConfig.whatsappEnabled)
            entries.put("whatsapp", waPlugin)

            configFile.writeText(json.toString(2))
        } catch (e: Exception) {
            addLog("[andClaw] Channel config failed: ${e.message}")
        }
    }

    /**
     * 이전 세션에서 남은 좀비 게이트웨이 프로세스를 찾아서 죽인다.
     * 앱 재설치/강제종료 시 proot 프로세스가 남아 포트를 점유할 수 있다.
     */
    private fun killOrphanGatewayProcesses() {
        try {
            val ps = ProcessBuilder("ps", "-ef").start()
            val lines = ps.inputStream.bufferedReader().readLines()
            ps.waitFor()

            val myUid = android.os.Process.myUid()
            for (line in lines) {
                if (line.contains("openclaw") || line.contains("libproot.so")) {
                    // PID 추출 (ps -ef 형식: UID PID PPID ...)
                    val parts = line.trim().split("\\s+".toRegex())
                    val pid = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    try {
                        android.os.Process.killProcess(pid)
                        addLog("[andClaw] Killing orphan process: PID $pid")
                    } catch (_: Exception) {
                        // 권한 없는 프로세스는 무시
                    }
                }
            }
            // 포트 해제 대기
            Thread.sleep(500)
        } catch (e: Exception) {
            addLog("[andClaw] Process cleanup error (ignored): ${e.message}")
        }
    }

    /**
     * proot에서 Chromium 실행 시 필수 플래그(--no-zygote 등)를 주입하는 wrapper 스크립트를 생성한다.
     * OpenClaw config에 커스텀 launch args를 넘길 방법이 없으므로 wrapper로 우회.
     *
     * @param chromeLinuxDir rootfs 내 chrome-linux 디렉토리 (Android 호스트 경로)
     * @param realBinaryPath proot 내부 경로 (e.g. /root/.cache/ms-playwright/.../headless_shell)
     */
    private fun ensureBrowserWrapper(chromeLinuxDir: File, realBinaryPath: String) {
        val wrapper = File(chromeLinuxDir, "chromium-proot-wrapper.sh")
        // proot 내부 절대 경로로 원본 바이너리를 직접 exec (dirname 명령어가 없을 수 있으므로)
        val script = "#!/bin/sh\nexec $realBinaryPath --no-zygote --single-process \"\$@\"\n"
        if (!wrapper.exists() || wrapper.readText() != script) {
            wrapper.writeText(script)
            wrapper.setExecutable(true, false)
            addLog("[andClaw] Browser wrapper script created")
        }
    }

    /**
     * OpenClaw의 내장 OpenRouter 모델 ID 목록을 models.generated.js에서 추출한다.
     * 내장 모델은 ModelRegistry에서 compat 설정까지 포함된 정확한 정의를 갖고 있으므로
     * 커스텀 등록으로 덮어쓰면 안 된다.
     */
    private fun getBuiltInOpenRouterModelIds(): Set<String> {
        try {
            val modelsFile = File(
                prootManager.rootfsDir,
                "usr/local/lib/node_modules/openclaw/node_modules/@mariozechner/pi-ai/dist/models.generated.js"
            )
            if (!modelsFile.exists()) return emptySet()

            val content = modelsFile.readText()
            // "openrouter" 섹션에서 모델 ID 추출
            val startMarker = "\"openrouter\": {"
            val startIdx = content.indexOf(startMarker)
            if (startIdx == -1) return emptySet()

            // 섹션 끝 찾기: 같은 들여쓰기 레벨의 닫는 중괄호
            val sectionStart = startIdx + startMarker.length
            var braceDepth = 1
            var endIdx = sectionStart
            while (endIdx < content.length && braceDepth > 0) {
                when (content[endIdx]) {
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                }
                endIdx++
            }
            val section = content.substring(sectionStart, endIdx)

            // 모델 ID 추출: "model-id": { 패턴
            val regex = Regex(""""([a-zA-Z0-9/.:_-]+)":\s*\{""")
            return regex.findAll(section)
                .map { it.groupValues[1] }
                .filter { it.contains("/") || it == "auto" } // provider/model 형식 또는 특수 ID
                .toSet()
        } catch (e: Exception) {
            addLog("[andClaw] Failed to read built-in models: ${e.message}")
            return emptySet()
        }
    }

    /**
     * 비내장 모델을 models.json에 등록한다.
     * ModelRegistry가 이 파일을 직접 읽어서 모델을 로드한다.
     * 경로: ~/.openclaw/agents/main/agent/models.json
     */
    private fun writeModelsJson(
        modelId: String,
        modelReasoning: Boolean,
        modelImages: Boolean,
        modelContext: Int,
        modelMaxOutput: Int,
    ) {
        try {
            val modelsJsonFile = File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/models.json")
            modelsJsonFile.parentFile?.mkdirs()

            val inputTypes = org.json.JSONArray().apply {
                put("text")
                if (modelImages) put("image")
            }

            val json = JSONObject().apply {
                put("providers", JSONObject().apply {
                    put("openrouter", JSONObject().apply {
                        put("baseUrl", "https://openrouter.ai/api/v1")
                        put("apiKey", "\${OPENROUTER_API_KEY}")
                        put("models", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", modelId)
                                put("name", modelId)
                                put("api", "openai-completions")
                                put("reasoning", modelReasoning)
                                put("input", inputTypes)
                                put("cost", JSONObject().apply {
                                    put("input", 0); put("output", 0); put("cacheRead", 0); put("cacheWrite", 0)
                                })
                                put("contextWindow", modelContext)
                                put("maxTokens", modelMaxOutput)
                            })
                        })
                    })
                })
            }
            modelsJsonFile.writeText(json.toString(2))
        } catch (e: Exception) {
            addLog("[andClaw] Failed to write models.json: ${e.message}")
        }
    }

    private fun ensurePatchFile() {
        val patchFile = File(prootManager.rootfsDir, "root/.openclaw-patch.js")
        if (!patchFile.exists()) {
            addLog("[andClaw] Creating Node.js compatibility patch...")
            patchFile.writeText(buildString {
                appendLine("const os = require('os');")
                appendLine("const _ni = os.networkInterfaces;")
                appendLine("os.networkInterfaces = function() {")
                appendLine("  try { return _ni.call(this); } catch(e) {")
                appendLine("    return {")
                appendLine("      lo: [{")
                appendLine("        address: '127.0.0.1',")
                appendLine("        netmask: '255.0.0.0',")
                appendLine("        family: 'IPv4',")
                appendLine("        mac: '00:00:00:00:00:00',")
                appendLine("        internal: true,")
                appendLine("        cidr: '127.0.0.1/8'")
                appendLine("      }]")
                appendLine("    };")
                appendLine("  }")
                appendLine("};")
            })
        }
    }

    private fun addLog(line: String) {
        Log.i(TAG, line)
        val current = _logLines.value.toMutableList()
        current.add(line)
        // 최대 1000줄 유지
        _logLines.value = if (current.size > 1000) current.takeLast(1000) else current
    }

    private suspend fun readProcessOutput(process: Process) = withContext(Dispatchers.IO) {
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let {
                        addLog(it)
                        parseLogLine(it)
                    }
                }
            }
        } catch (_: Exception) {
            // 프로세스 종료 시 자연스럽게 발생
        }
    }

    /**
     * OpenClaw 로그에서 상태 정보를 파싱한다.
     */
    private fun parseLogLine(line: String) {
        val lineLower = line.lowercase()

        // 게이트웨이 ready 상태 감지 (HTTP 서버 리슨 시작)
        if (lineLower.contains("listening on") && lineLower.contains("18789")) {
            _gatewayState.value = _gatewayState.value.copy(
                status = GatewayStatus.RUNNING,
                dashboardReady = true,
            )
            addLog("[andClaw] Gateway is ready!")
            startPairingObserver()
        }
    }

    private fun parseDiscordGuildAllowlist(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw
            .split(',', '\n', ';', '\t')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { normalizeDiscordGuildAllowlistEntry(it) }
            .distinct()
            .toList()
    }

    private fun normalizeDiscordGuildAllowlistEntry(value: String): String? {
        val slug = value
            .trim()
            .lowercase()
            .replace("^#".toRegex(), "")
            .replace("[^a-z0-9]+".toRegex(), "-")
            .replace("^-+|-+$".toRegex(), "")

        return slug.ifBlank { null }
    }

    // ── Pairing 관리 (파일 직접 읽기/쓰기 방식) ──

    private val credentialsDir: File
        get() = File(prootManager.rootfsDir, "root/.openclaw/credentials")

    /**
     * 활성화된 채널의 대기 중인 pairing 요청 목록을 조회한다.
     * credentials/<channel>-pairing.json 파일을 직접 읽는다.
     * CLI 실행 시 새 Node.js 프로세스가 메모리를 초과해 OOM kill이 발생하므로 파일 방식 사용.
     */
    suspend fun listPairingRequests(channelConfig: ChannelConfig): List<PairingRequest> {
        if (!isRunning) return emptyList()
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<PairingRequest>()
            val channels = buildList {
                if (channelConfig.telegramEnabled && channelConfig.telegramBotToken.isNotBlank()) add("telegram")
                if (channelConfig.discordEnabled && channelConfig.discordBotToken.isNotBlank()) add("discord")
                if (channelConfig.whatsappEnabled) add("whatsapp")
            }
            for (channel in channels) {
                try {
                    val file = File(credentialsDir, "$channel-pairing.json")
                    if (!file.exists()) continue
                    val json = JSONObject(file.readText())
                    val requests = json.optJSONArray("requests") ?: continue
                    for (i in 0 until requests.length()) {
                        val req = requests.getJSONObject(i)
                        val code = req.optString("code", "")
                        val meta = req.optJSONObject("meta")
                        val username = meta?.optString("name", "") ?: meta?.optString("tag", "") ?: ""
                        if (code.isNotBlank()) {
                            results.add(PairingRequest(channel = channel, code = code, username = username))
                        }
                    }
                } catch (_: Exception) { }
            }
            results
        }
    }

    /**
     * pairing 요청을 승인한다.
     * pairing.json에서 해당 요청을 제거하고 allowFrom.json에 ID를 추가한다.
     */
    suspend fun approvePairing(channel: String, code: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val pairingFile = File(credentialsDir, "$channel-pairing.json")
                if (!pairingFile.exists()) return@withContext false

                val json = JSONObject(pairingFile.readText())
                val requests = json.optJSONArray("requests") ?: return@withContext false

                // 코드에 해당하는 요청 찾기
                var targetId: String? = null
                var targetIndex = -1
                for (i in 0 until requests.length()) {
                    val req = requests.getJSONObject(i)
                    if (req.optString("code") == code) {
                        targetId = req.optString("id")
                        targetIndex = i
                        break
                    }
                }
                if (targetId == null || targetIndex < 0) return@withContext false

                // pairing.json에서 제거
                requests.remove(targetIndex)
                pairingFile.writeText(json.toString(2))

                // allowFrom.json에 ID 추가
                val allowFile = File(credentialsDir, "$channel-allowFrom.json")
                val allowJson = if (allowFile.exists()) {
                    JSONObject(allowFile.readText())
                } else {
                    JSONObject().put("version", 1).put("allowFrom", org.json.JSONArray())
                }
                val allowFrom = allowJson.optJSONArray("allowFrom") ?: org.json.JSONArray()
                // 중복 방지
                var alreadyExists = false
                for (i in 0 until allowFrom.length()) {
                    if (allowFrom.optString(i) == targetId) {
                        alreadyExists = true
                        break
                    }
                }
                if (!alreadyExists) {
                    allowFrom.put(targetId)
                    allowJson.put("allowFrom", allowFrom)
                    allowFile.writeText(allowJson.toString(2))
                }

                addLog("[andClaw] Pairing approved: $channel $code (id: $targetId)")
                true
            } catch (e: Exception) {
                addLog("[andClaw] Pairing approve failed: ${e.message}")
                false
            }
        }
    }

    /**
     * pairing 요청을 거부한다.
     * pairing.json에서 해당 요청만 제거한다.
     */
    suspend fun denyPairing(channel: String, code: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val pairingFile = File(credentialsDir, "$channel-pairing.json")
                if (!pairingFile.exists()) return@withContext false

                val json = JSONObject(pairingFile.readText())
                val requests = json.optJSONArray("requests") ?: return@withContext false

                var removed = false
                for (i in 0 until requests.length()) {
                    if (requests.getJSONObject(i).optString("code") == code) {
                        requests.remove(i)
                        removed = true
                        break
                    }
                }
                if (removed) {
                    pairingFile.writeText(json.toString(2))
                    addLog("[andClaw] Pairing denied: $channel $code")
                }
                removed
            } catch (e: Exception) {
                addLog("[andClaw] Pairing deny failed: ${e.message}")
                false
            }
        }
    }

    private fun startPairingObserver() {
        if (pairingFileObserver != null) return
        val dir = credentialsDir
        dir.mkdirs()

        val eventMask = FileObserver.MODIFY or FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO
        pairingFileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, eventMask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path.contains("pairing")) {
                        refreshPairingRequests()
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, eventMask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path.contains("pairing")) {
                        refreshPairingRequests()
                    }
                }
            }
        }.also { it.startWatching() }

        // 시작 시 한 번 조회
        refreshPairingRequests()
    }

    private fun stopPairingObserver() {
        pairingFileObserver?.stopWatching()
        pairingFileObserver = null
        _pairingRequests.value = emptyList()
    }

    fun refreshPairingRequests() {
        scope?.launch {
            _pairingRequests.value = listPairingRequests(lastChannelConfig)
        }
    }

    @Suppress("deprecation")
    private fun getProcessId(process: Process?): Int? {
        return try {
            val field = process?.javaClass?.getDeclaredField("pid")
            field?.isAccessible = true
            field?.getInt(process)
        } catch (_: Exception) {
            null
        }
    }
}

package com.coderred.andclaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.coderred.andclaw.AndClawApp
import com.coderred.andclaw.MainActivity
import com.coderred.andclaw.R
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.PairingRequest
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.proot.BundleUpdateOutcome
import com.coderred.andclaw.proot.ProcessManager
import com.coderred.andclaw.receiver.GatewayWatchdogReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GatewayService : Service() {

    private enum class GatewayActionType {
        START,
        RESTART,
        STOP,
    }

    companion object {
        const val CHANNEL_ID = "andclaw_gateway"
        const val PAIRING_CHANNEL_ID = "andclaw_pairing"
        const val NOTIFICATION_ID = 1
        const val PAIRING_NOTIFICATION_ID = 2
        private const val START_WAKE_LOCK_TIMEOUT_MS = 120_000L
        // Bundle update policy 기본 최대치(20분) + startup final state 대기(2분) + 여유(1분)
        private const val START_WAKE_LOCK_TIMEOUT_BACKGROUND_MS = 23L * 60L * 1000L
        private const val START_TERMINAL_WAIT_TIMEOUT_MS = 120_000L
        private const val RESTART_WAKE_LOCK_TIMEOUT_MS = 120_000L
        const val ACTION_START = "com.coderred.andclaw.action.START"
        const val ACTION_STOP = "com.coderred.andclaw.action.STOP"
        const val ACTION_RESTART = "com.coderred.andclaw.action.RESTART"
        private const val EXTRA_FROM_WATCHDOG = "from_watchdog"
        private const val EXTRA_USER_INITIATED = "user_initiated"

        private var _instance: GatewayService? = null

        internal fun shouldHoldChargingWakeLock(batteryStatus: Int): Boolean {
            return batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        }

        /** 현재 서비스의 ProcessManager. 서비스가 살아있을 때만 non-null. */
        val processManager: ProcessManager?
            get() = _instance?.pm

        fun start(context: Context, fromWatchdog: Boolean = false, userInitiated: Boolean = true) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FROM_WATCHDOG, fromWatchdog)
                putExtra(EXTRA_USER_INITIATED, userInitiated)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun restart(context: Context, userInitiated: Boolean = true) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_RESTART
                putExtra(EXTRA_USER_INITIATED, userInitiated)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var pm: ProcessManager
    private lateinit var prefs: PreferencesManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var actionJob: Job? = null
    private var actionJobType: GatewayActionType? = null
    private val actionSequence = AtomicLong(0L)
    private val desiredRunningMutex = Mutex()
    private val wakeLockGuard = Any()
    private var activeWakeLock: PowerManager.WakeLock? = null
    private val chargingWakeLockGuard = Any()
    private var chargingWakeLock: PowerManager.WakeLock? = null
    private var isBatteryReceiverRegistered = false
    private val batteryStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            updateChargingWakeLock(shouldHoldChargingWakeLock(status))
        }
    }

    override fun onCreate() {
        super.onCreate()
        _instance = this

        val app = application as AndClawApp
        pm = app.processManager
        prefs = app.preferencesManager

        createNotificationChannel()
        createPairingNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_waiting)))
        startChargingWakeLockController()

        // 상태 변화 → 알림 업데이트
        serviceScope.launch {
            pm.gatewayState.collectLatest { state ->
                val text = when (state.status) {
                    GatewayStatus.RUNNING -> getString(R.string.notification_running, formatUptime(state.uptime))
                    GatewayStatus.STARTING -> getString(R.string.notification_starting)
                    GatewayStatus.STOPPING -> getString(R.string.notification_stopping)
                    GatewayStatus.ERROR -> getString(R.string.notification_error, state.errorMessage?.take(50) ?: getString(R.string.notification_unknown))
                    GatewayStatus.STOPPED -> getString(R.string.notification_stopped)
                }
                updateNotification(text)
            }
        }

        // 페어링 요청 → 푸시 알림
        serviceScope.launch {
            pm.pairingRequests.collect { requests ->
                if (requests.isNotEmpty()) {
                    postPairingNotification(requests)
                } else {
                    cancelPairingNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            // START_STICKY 재생성(null intent) 시 이전 사용자 의도가 running이면 복구를 재시도한다.
            runAction(GatewayActionType.START, startId) { actionToken, actionStartId ->
                val shouldRecover = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
                if (!shouldRecover) {
                    GatewayWatchdogReceiver.cancel(applicationContext)
                    stopServiceForeground(actionStartId)
                    return@runAction
                }
                handleStart(
                    fromWatchdog = true,
                    userInitiated = false,
                    actionToken = actionToken,
                    startId = actionStartId,
                )
            }
            return START_STICKY
        }

        val fromWatchdog = intent.getBooleanExtra(EXTRA_FROM_WATCHDOG, false)
        val userInitiated = intent.getBooleanExtra(EXTRA_USER_INITIATED, true)
        when (action) {
            ACTION_START -> {
                runAction(GatewayActionType.START, startId) { actionToken, actionStartId ->
                    handleStart(
                        fromWatchdog = fromWatchdog,
                        userInitiated = userInitiated,
                        actionToken = actionToken,
                        startId = actionStartId,
                    )
                }
            }
            ACTION_RESTART -> {
                runAction(GatewayActionType.RESTART, startId) { actionToken, actionStartId ->
                    handleRestart(
                        userInitiated = userInitiated,
                        actionToken = actionToken,
                        startId = actionStartId,
                    )
                }
            }
            ACTION_STOP -> {
                runAction(GatewayActionType.STOP, startId) { actionToken, actionStartId ->
                    handleStop(actionToken = actionToken, startId = actionStartId)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopChargingWakeLockController()
        releaseActiveWakeLock()
        pm.stop()
        serviceScope.cancel()
        _instance = null
        super.onDestroy()
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GatewayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("andClaw")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_action_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    // ── Pairing Notification ──

    private fun createPairingNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PAIRING_CHANNEL_ID,
                getString(R.string.pairing_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications for new pairing requests"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun postPairingNotification(requests: List<PairingRequest>) {
        val first = requests.first()
        val channelName = first.channel.replaceFirstChar { it.uppercase() }
        val displayName = if (first.username.isNotBlank()) first.username else first.code
        val text = getString(R.string.pairing_notification_text, channelName, displayName)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, PAIRING_CHANNEL_ID)
            .setContentTitle(getString(R.string.pairing_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(PAIRING_NOTIFICATION_ID, notification)
    }

    private fun cancelPairingNotification() {
        getSystemService(NotificationManager::class.java)
            .cancel(PAIRING_NOTIFICATION_ID)
    }

    // ── WakeLock ──

    private suspend fun handleStart(
        fromWatchdog: Boolean,
        userInitiated: Boolean,
        actionToken: Long,
        startId: Int,
    ) {
        if (!isActionCurrent(actionToken)) return

        if (fromWatchdog) {
            val shouldKeepRunning = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
            if (!shouldKeepRunning) {
                GatewayWatchdogReceiver.cancel(applicationContext)
                stopServiceForeground(startId)
                return
            }
        }

        if (!isActionCurrent(actionToken)) return
        val app = application as AndClawApp
        val bundleUpdateRequired = runCatching { app.setupManager.isBundleUpdateRequired() }
            .getOrDefault(true)

        val startWakeLockTimeoutMs = resolveStartWakeLockTimeoutMs(
            fromWatchdog = fromWatchdog,
            userInitiated = userInitiated,
            bundleUpdateRequired = bundleUpdateRequired,
        )

        // 사용자가 START를 요청한 순간부터 desired-running 상태를 유지한다.
        // 단, bundle update가 끝나기 전에는 watchdog를 예약하지 않아
        // 장시간 업데이트 중 watchdog가 STOPPED 상태를 오인해 START를 선점하는 루프를 막는다.
        if (
            !setDesiredRunningAndWatchdog(
                shouldRun = true,
                actionToken = actionToken,
                updateWatchdog = false,
            )
        ) {
            return
        }

        withTimedWakeLock(startWakeLockTimeoutMs) {
            if (!isActionCurrent(actionToken)) return@withTimedWakeLock

            try {
                val result = app.setupManager.updateBundleIfNeededWithPolicy(
                    includeOpenClawAssetUpdate = false,
                )
                if (result.outcome == BundleUpdateOutcome.FAILED) {
                    android.util.Log.e("GatewayService", "Bundle update policy run failed: ${result.errorMessage}")
                    if (isActionCurrent(actionToken)) {
                        // 번들 업데이트 실패 시 부분 설치 상태로 런타임을 시작하면 ENOENT로 연쇄 실패할 수 있다.
                        // 사용자 의도는 유지하고 watchdog 복구를 위해 desired-running 상태만 유지한다.
                        markDesiredRunningAndScheduleWatchdog(actionToken)
                    }
                    return@withTimedWakeLock
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("GatewayService", "Bundle update failed during ACTION_START", error)
                if (isActionCurrent(actionToken)) {
                    markDesiredRunningAndScheduleWatchdog(actionToken)
                }
                return@withTimedWakeLock
            }

            if (!isActionCurrent(actionToken)) return@withTimedWakeLock

            val apiProvider = prefs.apiProvider.first()
            val apiKey = prefs.apiKey.first()
            val selectedModel = prefs.selectedModel.first()
            val channelConfig = prefs.channelConfig.first()
            val modelReasoning = prefs.selectedModelReasoning.first()
            val modelImages = prefs.selectedModelImages.first()
            val modelContext = prefs.selectedModelContext.first()
            val modelMaxOutput = prefs.selectedModelMaxOutput.first()
            val openAiCompatibleBaseUrl = prefs.openAiCompatibleBaseUrl.first()
            val braveSearchApiKey = prefs.braveSearchApiKey.first()

            if (!setDesiredRunningAndWatchdog(shouldRun = true, actionToken = actionToken)) {
                return@withTimedWakeLock
            }

            if (!isActionCurrent(actionToken)) return@withTimedWakeLock
            pm.start(
                apiProvider,
                apiKey,
                selectedModel,
                openAiCompatibleBaseUrl,
                channelConfig,
                modelReasoning,
                modelImages,
                modelContext,
                modelMaxOutput,
                braveSearchApiKey,
            )

            val finalStatus = awaitGatewayStartupTerminalState(START_TERMINAL_WAIT_TIMEOUT_MS)
            if (!isActionCurrent(actionToken)) return@withTimedWakeLock
            handleStartupOutcome(finalStatus, actionToken)
        }
    }

    private suspend fun handleRestart(userInitiated: Boolean, actionToken: Long, startId: Int) {
        if (!isActionCurrent(actionToken)) return

        if (!userInitiated) {
            val shouldKeepRunning = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
            val status = pm.gatewayState.value.status
            val alreadyActive =
                status == GatewayStatus.RUNNING || status == GatewayStatus.STARTING
            if (!shouldKeepRunning && !alreadyActive) {
                GatewayWatchdogReceiver.cancel(applicationContext)
                stopServiceForeground(startId)
                return
            }
        }

        // RESTART는 사용자 의도상 running 유지이므로 즉시 desired-running=true를 반영한다.
        if (!setDesiredRunningAndWatchdog(shouldRun = true, actionToken = actionToken)) return

        withTimedWakeLock(RESTART_WAKE_LOCK_TIMEOUT_MS) {
            if (!isActionCurrent(actionToken)) return@withTimedWakeLock

            val apiProvider = prefs.apiProvider.first()
            val apiKey = prefs.apiKey.first()
            val selectedModel = prefs.selectedModel.first()
            val channelConfig = prefs.channelConfig.first()
            val modelReasoning = prefs.selectedModelReasoning.first()
            val modelImages = prefs.selectedModelImages.first()
            val modelContext = prefs.selectedModelContext.first()
            val modelMaxOutput = prefs.selectedModelMaxOutput.first()
            val openAiCompatibleBaseUrl = prefs.openAiCompatibleBaseUrl.first()
            val braveSearchApiKey = prefs.braveSearchApiKey.first()

            if (!isActionCurrent(actionToken)) return@withTimedWakeLock
            pm.restart(
                apiProvider,
                apiKey,
                selectedModel,
                openAiCompatibleBaseUrl,
                channelConfig,
                modelReasoning,
                modelImages,
                modelContext,
                modelMaxOutput,
                braveSearchApiKey,
            )
            val finalStatus = awaitGatewayStartupTerminalState(RESTART_WAKE_LOCK_TIMEOUT_MS)
            if (!isActionCurrent(actionToken)) return@withTimedWakeLock
            handleStartupOutcome(finalStatus, actionToken)
        }
    }

    private suspend fun handleStop(actionToken: Long, startId: Int) {
        // STOP 선점 시 in-flight START/RESTART가 늦게 unwind 되더라도 즉시 wake lock을 해제한다.
        releaseActiveWakeLock()

        // STOP 요청은 최신 토큰 여부와 무관하게 항상 desired-running=false를 강제 반영한다.
        forceDesiredStopped()
        pm.stop()
        stopServiceForeground(startId)
    }

    private suspend fun handleStartupOutcome(finalStatus: GatewayStatus?, actionToken: Long) {
        if (!isActionCurrent(actionToken)) return

        if (finalStatus == GatewayStatus.RUNNING) {
            markDesiredRunningAndScheduleWatchdog(actionToken)
            return
        }

        if (finalStatus == null) {
            // STARTING 상태 고착 방지
            pm.stop()
        }
        // START/RESTART 실패는 사용자 의도를 꺾지 않는다.
        // transient failure 후에도 watchdog/boot/update 경로가 복구를 계속 시도해야 한다.
        markDesiredRunningAndScheduleWatchdog(actionToken)
    }

    private suspend fun markDesiredRunningAndScheduleWatchdog(actionToken: Long) {
        setDesiredRunningAndWatchdog(shouldRun = true, actionToken = actionToken)
    }

    private suspend fun forceDesiredStopped() {
        desiredRunningMutex.withLock {
            prefs.setGatewayWasRunning(false)
            GatewayWatchdogReceiver.cancel(applicationContext)
        }
    }

    private suspend fun setDesiredRunningAndWatchdog(
        shouldRun: Boolean,
        actionToken: Long,
        updateWatchdog: Boolean = true,
    ): Boolean {
        return desiredRunningMutex.withLock {
            if (!isActionCurrent(actionToken)) {
                return@withLock false
            }

            prefs.setGatewayWasRunning(shouldRun)

            if (!isActionCurrent(actionToken)) {
                return@withLock false
            }

            if (updateWatchdog) {
                if (shouldRun) {
                    GatewayWatchdogReceiver.schedule(applicationContext)
                } else {
                    GatewayWatchdogReceiver.cancel(applicationContext)
                }
            }

            isActionCurrent(actionToken)
        }
    }

    private suspend fun <T> withTimedWakeLock(timeoutMs: Long, block: suspend () -> T): T {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("deprecation")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "andClaw::GatewayWakeLock",
        ).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
        registerActiveWakeLock(wakeLock)
        return try {
            block()
        } finally {
            releaseWakeLockInstance(wakeLock)
        }
    }

    private suspend fun awaitGatewayStartupTerminalState(timeoutMs: Long): GatewayStatus? {
        val finalState = withTimeoutOrNull(timeoutMs) {
            pm.gatewayState.first { state ->
                state.status == GatewayStatus.RUNNING ||
                    state.status == GatewayStatus.ERROR ||
                    state.status == GatewayStatus.STOPPED
            }
        }
        if (finalState == null) {
            android.util.Log.w(
                "GatewayService",
                "Timed out waiting for gateway startup terminal state after ${timeoutMs}ms",
            )
        }
        return finalState?.status
    }

    private fun formatUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun runAction(
        actionType: GatewayActionType,
        startId: Int,
        block: suspend (Long, Int) -> Unit,
    ) {
        val actionToken = actionSequence.incrementAndGet()
        val previousAction = actionJob
        val previousActionType = actionJobType
        actionJobType = actionType
        actionJob = serviceScope.launch {
            try {
                if (actionType == GatewayActionType.STOP) {
                    // STOP은 지연 없이 선점한다. 오래 걸리는 START/RESTART 종료를 기다리지 않는다.
                    previousAction?.cancel()
                    if (previousAction != null) {
                        val stopToken = actionToken
                        serviceScope.launch {
                            try {
                                previousAction.join()
                            } catch (_: CancellationException) {
                                // ignore
                            }
                            withContext(Dispatchers.IO) {
                                if (!isActionCurrent(stopToken)) return@withContext
                                // STOP 선점 이후 이전 START/RESTART가 늦게 반영한 상태를 한 번 더 정리한다.
                                setDesiredRunningAndWatchdog(shouldRun = false, actionToken = stopToken)
                                pm.stop()
                            }
                        }
                    }
                } else {
                    if (previousActionType == GatewayActionType.STOP) {
                        // STOP 이후 START/RESTART가 들어오면 STOP 완료를 보장한 뒤 진행한다.
                        previousAction?.join()
                    } else {
                        previousAction?.cancelAndJoin()
                    }
                }
                withContext(Dispatchers.IO) {
                    if (!isActionCurrent(actionToken)) return@withContext
                    block(actionToken, startId)
                }
            } finally {
                if (isActionCurrent(actionToken)) {
                    actionJobType = null
                }
            }
        }
    }

    private fun isActionCurrent(actionToken: Long): Boolean = actionSequence.get() == actionToken

    private fun resolveStartWakeLockTimeoutMs(
        fromWatchdog: Boolean,
        userInitiated: Boolean,
        bundleUpdateRequired: Boolean,
    ): Long {
        if (fromWatchdog || !userInitiated || bundleUpdateRequired) return START_WAKE_LOCK_TIMEOUT_BACKGROUND_MS
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (powerManager.isInteractive) {
            START_WAKE_LOCK_TIMEOUT_MS
        } else {
            START_WAKE_LOCK_TIMEOUT_BACKGROUND_MS
        }
    }

    private suspend fun stopServiceForeground(startId: Int) {
        withContext(Dispatchers.Main) {
            if (stopSelfResult(startId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun registerActiveWakeLock(wakeLock: PowerManager.WakeLock) {
        synchronized(wakeLockGuard) {
            activeWakeLock = wakeLock
        }
    }

    private fun releaseWakeLockInstance(wakeLock: PowerManager.WakeLock) {
        synchronized(wakeLockGuard) {
            if (activeWakeLock === wakeLock) {
                activeWakeLock = null
            }
        }
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun releaseActiveWakeLock() {
        val wakeLock = synchronized(wakeLockGuard) {
            val current = activeWakeLock
            activeWakeLock = null
            current
        }
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
    }

    private fun startChargingWakeLockController() {
        if (isBatteryReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryStateReceiver, filter)
        }
        isBatteryReceiverRegistered = true
        val status = stickyIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        updateChargingWakeLock(shouldHoldChargingWakeLock(status))
    }

    private fun stopChargingWakeLockController() {
        if (isBatteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryStateReceiver)
            } catch (_: IllegalArgumentException) {
                // ignore
            } finally {
                isBatteryReceiverRegistered = false
            }
        }
        releaseChargingWakeLock()
    }

    private fun updateChargingWakeLock(shouldHold: Boolean) {
        if (shouldHold) {
            acquireChargingWakeLock()
        } else {
            releaseChargingWakeLock()
        }
    }

    private fun acquireChargingWakeLock() {
        synchronized(chargingWakeLockGuard) {
            if (chargingWakeLock?.isHeld == true) return
            val lock = chargingWakeLock ?: run {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("deprecation")
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "andClaw::ChargingWakeLock",
                ).apply { setReferenceCounted(false) }
            }
            if (!lock.isHeld) {
                lock.acquire()
            }
            chargingWakeLock = lock
        }
    }

    private fun releaseChargingWakeLock() {
        val lock = synchronized(chargingWakeLockGuard) {
            val current = chargingWakeLock
            chargingWakeLock = null
            current
        }
        if (lock?.isHeld == true) {
            lock.release()
        }
    }
}

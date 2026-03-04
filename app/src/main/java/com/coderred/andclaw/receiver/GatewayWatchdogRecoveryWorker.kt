package com.coderred.andclaw.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.coderred.andclaw.MainActivity
import com.coderred.andclaw.R
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.service.GatewayService
import kotlinx.coroutines.flow.first

class GatewayWatchdogRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "gateway_watchdog_recovery"
        private const val CHANNEL_ID = "andclaw_watchdog_recovery"
        private const val NOTIFICATION_ID = 30101
        private const val HEALTH_PROBE_TIMEOUT_MS = 8_000L

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<GatewayWatchdogRecoveryWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)
        val shouldKeepRunning = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
        if (!shouldKeepRunning) {
            GatewayWatchdogReceiver.cancel(applicationContext)
            return Result.success()
        }

        val processManager = GatewayService.processManager
        var status = processManager?.gatewayState?.value?.status
        val needsRecovery = when (status) {
            null,
            GatewayStatus.STOPPED,
            GatewayStatus.ERROR -> true
            GatewayStatus.RUNNING -> {
                val healthy = processManager?.probeGatewayHealth(timeoutMs = HEALTH_PROBE_TIMEOUT_MS) == true
                // Probe 중 상태 전이가 일어난 경우 stale RUNNING 판정을 폐기한다.
                status = processManager?.gatewayState?.value?.status ?: status
                when (status) {
                    GatewayStatus.STOPPED,
                    GatewayStatus.ERROR -> true
                    GatewayStatus.RUNNING -> {
                        if (!healthy) {
                            Log.w("GatewayWatchdogWorker", "Gateway RUNNING but unhealthy, restarting")
                        }
                        !healthy
                    }
                    GatewayStatus.STARTING,
                    GatewayStatus.STOPPING -> false
                }
            }
            GatewayStatus.STARTING,
            GatewayStatus.STOPPING -> false
        }
        if (!needsRecovery) {
            return Result.success()
        }

        return try {
            setForeground(createForegroundInfo())
            if (status == GatewayStatus.RUNNING) {
                GatewayService.restart(
                    applicationContext,
                    userInitiated = false,
                    fromWatchdog = true,
                )
            } else {
                GatewayService.start(
                    applicationContext,
                    fromWatchdog = true,
                    userInitiated = false,
                )
            }
            Result.success()
        } catch (error: Exception) {
            Log.e("GatewayWatchdogWorker", "Failed to start gateway from watchdog worker", error)
            Result.retry()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val openIntent = Intent(applicationContext, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.notification_starting))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}

package com.coderred.andclaw.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.service.GatewayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 앱 업데이트 후 게이트웨이 자동 재시작.
 * 업데이트 전에 게이트웨이가 실행 중이었으면 자동으로 다시 시작한다.
 * 번들 업데이트는 GatewayService.onStartCommand에서 처리.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferencesManager(context)
                val shouldRestart = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
                if (shouldRestart) {
                    GatewayService.start(context, userInitiated = false, source = "update:auto_restart")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

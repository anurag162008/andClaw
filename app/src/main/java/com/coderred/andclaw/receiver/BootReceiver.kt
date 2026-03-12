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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferencesManager(context)
                val shouldAutoStart = prefs.autoStartOnBoot.first() && prefs.isSetupComplete.first()
                if (shouldAutoStart) {
                    GatewayService.start(context, userInitiated = false, source = "boot:auto_start")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

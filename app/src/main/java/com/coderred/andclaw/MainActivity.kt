package com.coderred.andclaw

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.coderred.andclaw.auth.OpenRouterAuth
import com.coderred.andclaw.proot.BundleUpdateOutcome
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.SetupStep
import com.coderred.andclaw.ui.navigation.AndClawNavGraph
import com.coderred.andclaw.service.GatewayService
import com.coderred.andclaw.ui.theme.AndClawTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private enum class StartupBundleUpdateStatus {
    CHECKING,
    UPDATING,
    DONE,
}

private data class StartupOpenClawUpdatePrompt(
    val installedVersion: String?,
    val bundledVersion: String?,
)

private data class StartupOpenClawUpdateResult(
    val success: Boolean,
    val message: String,
)

class MainActivity : ComponentActivity() {

    private var authCallbackUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        handleDeepLink(intent)

        val app = application as AndClawApp

        setContent {
            AndClawTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val isSetupCompleteRaw by app.preferencesManager.isSetupComplete
                        .collectAsState(initial = null)
                    val isOnboardingCompleteRaw by app.preferencesManager.isOnboardingComplete
                        .collectAsState(initial = null)

                    if (isSetupCompleteRaw == null || isOnboardingCompleteRaw == null) {
                        startupBundleUpdateScreen(step = SetupStep.CHECKING_PROOT)
                        return@Surface
                    }

                    val isSetupComplete = isSetupCompleteRaw == true
                    val isOnboardingComplete = isOnboardingCompleteRaw == true

                    var startupUpdateStatus by remember(isSetupComplete) {
                        mutableStateOf(
                            if (isSetupComplete) {
                                StartupBundleUpdateStatus.CHECKING
                            } else {
                                StartupBundleUpdateStatus.DONE
                            },
                        )
                    }
                    var startupUpdateStep by remember(isSetupComplete) {
                        mutableStateOf(SetupStep.CHECKING_PROOT)
                    }
                    var hasCheckedOpenClawUpdatePrompt by remember(isSetupComplete, isOnboardingComplete) {
                        mutableStateOf(false)
                    }
                    var startupOpenClawUpdatePrompt by remember {
                        mutableStateOf<StartupOpenClawUpdatePrompt?>(null)
                    }
                    var startupOpenClawSkipUntilNextVersion by remember {
                        mutableStateOf(false)
                    }
                    var startupOpenClawUpdateRunning by remember {
                        mutableStateOf(false)
                    }
                    var startupOpenClawUpdateResult by remember {
                        mutableStateOf<StartupOpenClawUpdateResult?>(null)
                    }
                    val setupState by app.setupManager.state.collectAsState()
                    val scope = rememberCoroutineScope()

                    LaunchedEffect(isSetupComplete) {
                        if (!isSetupComplete) {
                            startupUpdateStatus = StartupBundleUpdateStatus.DONE
                            return@LaunchedEffect
                        }

                        startupUpdateStatus = StartupBundleUpdateStatus.CHECKING
                        val needsBundleUpdate = withContext(Dispatchers.IO) {
                            withTimeoutOrNull(30_000L) {
                                app.setupManager.isBundleUpdateRequired()
                            } ?: false
                        }

                        if (!needsBundleUpdate) {
                            startupUpdateStatus = StartupBundleUpdateStatus.DONE
                            return@LaunchedEffect
                        }

                        startupUpdateStatus = StartupBundleUpdateStatus.UPDATING
                        startupUpdateStep = SetupStep.INSTALLING_TOOLS

                        try {
                            val result = withContext(Dispatchers.IO) {
                                app.setupManager.updateBundleIfNeededWithPolicy(onStepChanged = { step ->
                                    runOnUiThread { startupUpdateStep = step }
                                }, includeOpenClawAssetUpdate = false)
                            }
                            if (result.outcome == BundleUpdateOutcome.FAILED) {
                                Log.e("MainActivity", "Bundle update policy run failed: ${result.errorMessage}")
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.e("MainActivity", "Failed to update bundled assets on startup", error)
                        }

                        startupUpdateStatus = StartupBundleUpdateStatus.DONE
                    }

                    LaunchedEffect(
                        isSetupComplete,
                        isOnboardingComplete,
                        startupUpdateStatus,
                        startupOpenClawUpdateRunning,
                    ) {
                        if (!isSetupComplete || !isOnboardingComplete) return@LaunchedEffect
                        if (startupUpdateStatus != StartupBundleUpdateStatus.DONE) return@LaunchedEffect
                        if (startupOpenClawUpdateRunning) return@LaunchedEffect
                        if (hasCheckedOpenClawUpdatePrompt) return@LaunchedEffect
                        hasCheckedOpenClawUpdatePrompt = true

                        val info = withContext(Dispatchers.IO) {
                            runCatching { app.setupManager.getOpenClawUpdateInfo() }.getOrNull()
                        } ?: return@LaunchedEffect

                        if (!info.updateAvailable) return@LaunchedEffect

                        val bundledVersion = info.bundledVersion?.trim().orEmpty()
                        if (bundledVersion.isNotEmpty()) {
                            val suppressedVersion = withContext(Dispatchers.IO) {
                                app.preferencesManager.getOpenClawUpdatePromptSuppressedBundledVersion()
                            }?.trim().orEmpty()
                            if (suppressedVersion == bundledVersion) {
                                return@LaunchedEffect
                            }
                        }

                        startupOpenClawUpdatePrompt = StartupOpenClawUpdatePrompt(
                            installedVersion = info.installedVersion,
                            bundledVersion = info.bundledVersion,
                        )
                    }

                    if (isSetupComplete && startupUpdateStatus != StartupBundleUpdateStatus.DONE) {
                        val screenStep = when (startupUpdateStatus) {
                            StartupBundleUpdateStatus.CHECKING -> SetupStep.CHECKING_PROOT
                            StartupBundleUpdateStatus.UPDATING -> startupUpdateStep
                            StartupBundleUpdateStatus.DONE -> SetupStep.CHECKING_PROOT
                        }
                        val screenProgress = if (startupUpdateStatus == StartupBundleUpdateStatus.UPDATING) {
                            setupState.progress
                        } else {
                            0f
                        }
                        val screenDownloadedBytes = if (startupUpdateStatus == StartupBundleUpdateStatus.UPDATING) {
                            setupState.downloadedBytes
                        } else {
                            0L
                        }
                        val screenTotalBytes = if (startupUpdateStatus == StartupBundleUpdateStatus.UPDATING) {
                            setupState.totalBytes
                        } else {
                            0L
                        }
                        startupBundleUpdateScreen(
                            step = screenStep,
                            progress = screenProgress,
                            downloadedBytes = screenDownloadedBytes,
                            totalBytes = screenTotalBytes,
                        )
                    } else {
                        AndClawNavGraph(
                            navController = navController,
                            isSetupComplete = isSetupComplete,
                            isOnboardingComplete = isOnboardingComplete,
                            authCallbackUri = authCallbackUri,
                        )
                    }

                    val openClawPrompt = startupOpenClawUpdatePrompt
                    if (openClawPrompt != null) {
                        AlertDialog(
                            onDismissRequest = {
                                startupOpenClawUpdatePrompt = null
                                startupOpenClawSkipUntilNextVersion = false
                            },
                            title = { Text(stringResource(R.string.settings_openclaw_update_action)) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val versionLabel = if (
                                        !openClawPrompt.installedVersion.isNullOrBlank() &&
                                        !openClawPrompt.bundledVersion.isNullOrBlank()
                                    ) {
                                        stringResource(
                                            R.string.settings_openclaw_update_available_version,
                                            openClawPrompt.installedVersion!!,
                                            openClawPrompt.bundledVersion!!,
                                        )
                                    } else {
                                        stringResource(R.string.settings_openclaw_update_action)
                                    }
                                    Text(text = versionLabel)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = startupOpenClawSkipUntilNextVersion,
                                            onCheckedChange = { startupOpenClawSkipUntilNextVersion = it },
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.settings_openclaw_update_skip_until_next_version))
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val prompt = startupOpenClawUpdatePrompt
                                        startupOpenClawUpdatePrompt = null
                                        val skipChecked = startupOpenClawSkipUntilNextVersion
                                        startupOpenClawSkipUntilNextVersion = false
                                        scope.launch {
                                            val bundledVersion = prompt?.bundledVersion
                                            if (skipChecked) {
                                                withContext(Dispatchers.IO) {
                                                    app.preferencesManager.setOpenClawUpdatePromptSuppressedBundledVersion(
                                                        bundledVersion,
                                                    )
                                                }
                                            }
                                            startupOpenClawUpdateRunning = true
                                            startupOpenClawUpdateResult = withContext(Dispatchers.IO) {
                                                runStartupOpenClawUpdate(app)
                                            }
                                            startupOpenClawUpdateRunning = false
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.settings_restart_confirm_yes))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        val prompt = startupOpenClawUpdatePrompt
                                        startupOpenClawUpdatePrompt = null
                                        val skipChecked = startupOpenClawSkipUntilNextVersion
                                        startupOpenClawSkipUntilNextVersion = false
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                if (skipChecked) {
                                                    app.preferencesManager.setOpenClawUpdatePromptSuppressedBundledVersion(
                                                        prompt?.bundledVersion,
                                                    )
                                                } else {
                                                    app.preferencesManager.setOpenClawUpdatePromptSuppressedBundledVersion(null)
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.settings_restart_confirm_no))
                                }
                            },
                        )
                    }

                    if (startupOpenClawUpdateRunning) {
                        val safeProgress = setupState.progress.coerceIn(0f, 1f)
                        val isFileCountMode = setupState.currentStep == SetupStep.INSTALLING_OPENCLAW
                        AlertDialog(
                            onDismissRequest = {},
                            confirmButton = {},
                            title = { Text(stringResource(R.string.settings_openclaw_update_running)) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        CircularProgressIndicator()
                                        Text(stringResource(R.string.settings_openclaw_update_running))
                                    }
                                    LinearProgressIndicator(
                                        progress = { safeProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        text = if (isFileCountMode) {
                                            val safeDownloaded = setupState.downloadedBytes.coerceAtLeast(0L)
                                            "${(safeProgress * 100).toInt()}% · " +
                                                "${stringResource(setupState.currentStep.displayNameRes)} · " +
                                                if (setupState.totalBytes > 0L) {
                                                    "($safeDownloaded/${setupState.totalBytes})"
                                                } else {
                                                    "($safeDownloaded/?)"
                                                }
                                        } else {
                                            "${(safeProgress * 100).toInt()}% · " +
                                                stringResource(setupState.currentStep.displayNameRes)
                                        },
                                    )
                                    if (!isFileCountMode && setupState.downloadedBytes > 0L) {
                                        Text(
                                            text = if (setupState.totalBytes > 0L) {
                                                "${formatBytesForProgress(setupState.downloadedBytes)} / " +
                                                    formatBytesForProgress(setupState.totalBytes)
                                            } else {
                                                formatBytesForProgress(setupState.downloadedBytes)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            },
                        )
                    }

                    val openClawUpdateResult = startupOpenClawUpdateResult
                    if (openClawUpdateResult != null) {
                        AlertDialog(
                            onDismissRequest = { startupOpenClawUpdateResult = null },
                            title = {
                                Text(
                                    if (openClawUpdateResult.success) {
                                        stringResource(R.string.dashboard_update_action_done)
                                    } else {
                                        stringResource(R.string.dashboard_update_action_failed)
                                    },
                                )
                            },
                            text = {
                                Text(openClawUpdateResult.message)
                            },
                            confirmButton = {
                                TextButton(onClick = { startupOpenClawUpdateResult = null }) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == OpenRouterAuth.CALLBACK_SCHEME &&
            uri.host == OpenRouterAuth.CALLBACK_HOST &&
            uri.path == OpenRouterAuth.CALLBACK_PATH
        ) {
            authCallbackUri = uri
        }
    }

    private suspend fun runStartupOpenClawUpdate(app: AndClawApp): StartupOpenClawUpdateResult {
        val wasGatewayActive = app.processManager.gatewayState.value.status.let { status ->
            status == GatewayStatus.RUNNING || status == GatewayStatus.STARTING
        }

        return try {
            if (wasGatewayActive) {
                GatewayService.stop(this)
                val stopped = waitForGatewayStopped(app)
                if (!stopped) {
                    return StartupOpenClawUpdateResult(
                        success = false,
                        message = getString(R.string.dashboard_update_action_failed),
                    )
                }
            }

            val result = app.setupManager.runOpenClawManualSync()
            if (result.fullReinstall) {
                StartupOpenClawUpdateResult(
                    success = true,
                    message = getString(R.string.dashboard_update_action_done),
                )
            } else {
                val message = getString(
                    R.string.settings_openclaw_update_result_incremental,
                    result.copiedCount,
                    result.deletedCount,
                    result.skippedCount,
                )
                StartupOpenClawUpdateResult(success = true, message = message)
            }
        } catch (error: Exception) {
            StartupOpenClawUpdateResult(
                success = false,
                message = error.message ?: getString(R.string.dashboard_update_action_failed),
            )
        } finally {
            restoreGatewayIfNeeded(
                app = app,
                shouldRestore = wasGatewayActive,
            )
        }
    }

    private suspend fun waitForGatewayStopped(app: AndClawApp, timeoutMs: Long = 10_000L): Boolean {
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            val status = app.processManager.gatewayState.value.status
            if (status == GatewayStatus.STOPPED || status == GatewayStatus.ERROR) {
                return true
            }
            delay(250)
        }
        return false
    }

    private suspend fun restoreGatewayIfNeeded(
        app: AndClawApp,
        shouldRestore: Boolean,
        timeoutMs: Long = 10_000L,
    ) {
        if (!shouldRestore) return

        var startRequested = false
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            when (app.processManager.gatewayState.value.status) {
                GatewayStatus.RUNNING,
                GatewayStatus.STARTING,
                -> return
                GatewayStatus.STOPPING -> delay(250)
                GatewayStatus.STOPPED,
                GatewayStatus.ERROR,
                -> {
                    if (!startRequested) {
                        GatewayService.start(this)
                        startRequested = true
                    }
                    delay(250)
                }
            }
        }

        val status = app.processManager.gatewayState.value.status
        if (status != GatewayStatus.RUNNING && status != GatewayStatus.STARTING) {
            GatewayService.start(this)
        }
    }
}

private fun formatBytesForProgress(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (value >= 100 || unitIndex == 0) {
        String.format(Locale.US, "%.0f %s", value, units[unitIndex])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

@androidx.compose.runtime.Composable
private fun startupBundleUpdateScreen(
    step: SetupStep,
    progress: Float = 0f,
    downloadedBytes: Long = 0L,
    totalBytes: Long = 0L,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    val isFileCountMode = step == SetupStep.INSTALLING_OPENCLAW
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(step.displayNameRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            LinearProgressIndicator(
                progress = { safeProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            )
            Text(
                text = if (isFileCountMode) {
                    val safeDownloaded = downloadedBytes.coerceAtLeast(0L)
                    "${(safeProgress * 100).toInt()}% · " +
                        if (totalBytes > 0L) {
                            "($safeDownloaded/$totalBytes)"
                        } else {
                            "($safeDownloaded/?)"
                        }
                } else {
                    "${(safeProgress * 100).toInt()}%"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (!isFileCountMode && downloadedBytes > 0L) {
                Text(
                    text = if (totalBytes > 0L) {
                        "${formatBytesForProgress(downloadedBytes)} / ${formatBytesForProgress(totalBytes)}"
                    } else {
                        formatBytesForProgress(downloadedBytes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

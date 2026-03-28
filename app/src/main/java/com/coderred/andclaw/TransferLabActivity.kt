package com.coderred.andclaw

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.coderred.andclaw.data.transfer.TransferDecrypter
import com.coderred.andclaw.data.transfer.TransferDecrypter.OutputFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hacker-style interactive transfer utility screen for Android.
 * Path-based operations are intentional so power users can run all flows without leaving the app.
 */
class TransferLabActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val bg = Color(0xFF050505)
            val cardBg = Color(0xFF101A10)
            val neon = Color(0xFF33FF88)
            val neonSoft = Color(0xFF9BFFCC)
            val accent = Color(0xFF00E5FF)

            var sourcePath by remember { mutableStateOf("") }
            var outputPath by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            var format by remember { mutableStateOf(OutputFormat.ZIP) }
            var logs by remember { mutableStateOf(listOf("[ready] Transfer Lab online")) }
            var autoHeal by remember { mutableStateOf(true) }
            var retryCount by remember { mutableStateOf("2") }

            fun appendLog(line: String) {
                logs = logs + line
            }

            fun runIo(actionName: String, block: () -> Unit) {
                if (sourcePath.isBlank()) {
                    Toast.makeText(this, "Source path required", Toast.LENGTH_SHORT).show()
                    return
                }
                if (password.isBlank()) {
                    Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show()
                    return
                }
                if (outputPath.isBlank() && actionName != "analyze") {
                    if (autoHeal) {
                        outputPath = File(filesDir, "transfer-lab-out").absolutePath
                        appendLog("[heal] output auto-set: $outputPath")
                    } else {
                        Toast.makeText(this, "Output path required", Toast.LENGTH_SHORT).show()
                        return
                    }
                }

                appendLog("[run] $actionName")
                lifecycleScope.launch(Dispatchers.IO) {
                    val maxAttempts = retryCount.toIntOrNull()?.coerceIn(1, 5) ?: 2
                    var lastError: Throwable? = null
                    for (attempt in 1..maxAttempts) {
                        val result = runCatching(block)
                        if (result.isSuccess) {
                            runOnUiThread {
                                appendLog("[ok] $actionName done (attempt=$attempt)")
                            }
                            return@launch
                        }
                        lastError = result.exceptionOrNull()
                        if (!autoHeal) break
                        runOnUiThread { appendLog("[retry] $actionName attempt=$attempt failed; retrying...") }
                    }
                    runOnUiThread {
                        appendLog("[err] $actionName: ${lastError?.message}")
                        Toast.makeText(this@TransferLabActivity, lastError?.message ?: "Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            fun withAutoAnalyze(tag: String, action: () -> Unit) {
                runIo(tag) {
                    if (autoHeal) {
                        runCatching {
                            val info = TransferDecrypter.analyzeTransfer(
                                sourceTransferFile = File(sourcePath),
                                password = password.toCharArray(),
                            )
                            runOnUiThread {
                                appendLog(
                                    "[precheck] chunk=${info.chunkSize} count=${info.chunkCount} zip=${info.likelyZipPayload}",
                                )
                            }
                        }
                    }
                    action()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    text = "ANDCLAW // TRANSFER LAB",
                    color = neon,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "Interactive .transfer toolkit • decrypt/open/create/analyze • ready-to-launch",
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                )

                Spacer(Modifier.height(12.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sourcePath,
                            onValueChange = { sourcePath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Source path", color = neonSoft) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = outputPath,
                            onValueChange = { outputPath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Output path", color = neonSoft) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Password", color = neonSoft) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = autoHeal, onCheckedChange = { autoHeal = it })
                            Text("Auto-heal (retry + auto defaults)", color = neonSoft, fontFamily = FontFamily.Monospace)
                        }
                        OutlinedTextField(
                            value = retryCount,
                            onValueChange = { retryCount = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Retry attempts (1-5)", color = neonSoft) },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = {
                                outputPath = File(filesDir, "transfer-lab-out").absolutePath
                                appendLog("[preset] output -> app files")
                            }) {
                                Text("Use App Files", color = accent, fontFamily = FontFamily.Monospace)
                            }
                            TextButton(onClick = {
                                sourcePath = File(filesDir, "transfers").absolutePath
                                appendLog("[preset] source -> app transfers")
                            }) {
                                Text("Use Transfers Dir", color = accent, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Output format", color = neon, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(OutputFormat.ZIP, OutputFormat.EXTRACT, OutputFormat.RAW).forEach { candidate ->
                                TextButton(onClick = { format = candidate }) {
                                    Text(
                                        text = if (format == candidate) "[${candidate.name}]" else candidate.name,
                                        color = if (format == candidate) neon else neonSoft,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    withAutoAnalyze("decrypt-one") {
                                        val out = TransferDecrypter.decryptTransfer(
                                            sourceTransferFile = File(sourcePath),
                                            password = password.toCharArray(),
                                            outputDir = File(outputPath),
                                            outputFormat = format,
                                        )
                                        runOnUiThread { appendLog("[out] ${out.absolutePath}") }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B2E19)),
                            ) { Text("Decrypt", color = Color.White) }

                            Button(
                                onClick = {
                                    withAutoAnalyze("batch-decrypt") {
                                        val (ok, fails) = TransferDecrypter.decryptFolder(
                                            inputDir = File(sourcePath),
                                            outputDir = File(outputPath),
                                            password = password.toCharArray(),
                                            outputFormat = format,
                                        )
                                        runOnUiThread {
                                            appendLog("[batch] success=$ok failed=${fails.size}")
                                            fails.take(10).forEach { appendLog("[fail] $it") }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B2E19)),
                            ) { Text("Batch", color = Color.White) }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    withAutoAnalyze("open-edit") {
                                        val mode = TransferDecrypter.openTransferForEdit(
                                            inputTransfer = File(sourcePath),
                                            outputFolder = File(outputPath),
                                            password = password.toCharArray(),
                                        )
                                        runOnUiThread { appendLog("[mode] $mode") }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B2E19)),
                            ) { Text("Open", color = Color.White) }

                            Button(
                                onClick = {
                                    withAutoAnalyze("create-transfer") {
                                        TransferDecrypter.createTransfer(
                                            sourcePath = File(sourcePath),
                                            outputTransfer = File(outputPath),
                                            password = password.toCharArray(),
                                        )
                                        runOnUiThread { appendLog("[out] ${File(outputPath).absolutePath}") }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B2E19)),
                            ) { Text("Create", color = Color.White) }
                        }

                        Button(
                            onClick = {
                                runIo("analyze") {
                                    val info = TransferDecrypter.analyzeTransfer(
                                        sourceTransferFile = File(sourcePath),
                                        password = password.toCharArray(),
                                    )
                                    runOnUiThread {
                                        appendLog("[analysis] iter=${info.iterations} salt=${info.saltLength} iv=${info.ivLength}")
                                        appendLog("[analysis] chunk=${info.chunkSize} count=${info.chunkCount} zip=${info.likelyZipPayload}")
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B2E19)),
                        ) { Text("Analyze", color = Color.White) }

                        TextButton(onClick = { logs = listOf("[ready] log reset") }) {
                            Text("Clear Console", color = neonSoft, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Console", color = neon, fontFamily = FontFamily.Monospace)
                        logs.takeLast(200).forEach { line ->
                            Text(line, color = neonSoft, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

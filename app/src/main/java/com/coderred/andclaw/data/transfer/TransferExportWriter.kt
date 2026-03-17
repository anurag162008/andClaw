package com.coderred.andclaw.data.transfer

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TransferExportRequest(
    val outputDir: File,
    val rootfsDir: File,
    val approvedPreferencesSnapshot: Map<String, String>,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val createdAtEpochMs: Long,
    val password: CharArray,
) {
    override fun toString(): String = "TransferExportRequest(applicationId=$applicationId, password=***)"
}

data class TransferExportArtifact(
    val file: File,
    val manifest: TransferManifest,
)

object TransferExportWriter {
    private const val FILE_PREFIX = "andclaw-transfer-"
    private const val FILE_SUFFIX = ".transfer"

    fun write(request: TransferExportRequest): TransferExportArtifact {
        val outputDir = ensureOutputDir(request.outputDir)
        val timestamp = formatTimestamp(request.createdAtEpochMs)
        val finalFile = createUniqueFinalFile(outputDir, timestamp)
        val archiveTempFile = File.createTempFile("$FILE_PREFIX$timestamp-", ".archive.tmp", outputDir)
        val encryptedTempFile = File.createTempFile("$FILE_PREFIX$timestamp-", ".enc.tmp", outputDir)
        val includedSections = TransferManifestContract.detectIncludedSections(request.rootfsDir)

        val manifest = TransferManifestContract.newManifest(
            applicationId = request.applicationId,
            versionCode = request.versionCode,
            versionName = request.versionName,
            createdAtEpochMs = request.createdAtEpochMs,
            includedSections = includedSections,
        )

        try {
            TransferArchive.writeDeterministicArchive(
                targetFile = archiveTempFile,
                manifest = manifest,
                approvedPreferencesSnapshot = request.approvedPreferencesSnapshot,
                rootfsDir = request.rootfsDir,
            )
            TransferCrypto.encryptFile(
                inputFile = archiveTempFile,
                outputFile = encryptedTempFile,
                password = request.password,
            )
            val writtenFile = moveAtomicallyBestEffort(encryptedTempFile, finalFile)
            return TransferExportArtifact(
                file = writtenFile,
                manifest = manifest,
            )
        } finally {
            request.password.fill('\u0000')
            if (archiveTempFile.exists()) {
                archiveTempFile.delete()
            }
            if (encryptedTempFile.exists() && encryptedTempFile != finalFile) {
                encryptedTempFile.delete()
            }
        }
    }

    private fun ensureOutputDir(outputDir: File): File {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("Failed to create transfer output dir: ${outputDir.absolutePath}")
        }
        if (!outputDir.isDirectory) {
            throw IOException("Transfer output path is not a directory: ${outputDir.absolutePath}")
        }
        return outputDir
    }

    private fun moveAtomicallyBestEffort(tempFile: File, finalFile: File): File {
        if (tempFile.renameTo(finalFile)) {
            return finalFile
        }

        tempFile.copyTo(finalFile, overwrite = true)
        if (!tempFile.delete()) {
            tempFile.deleteOnExit()
        }
        return finalFile
    }

    private fun formatTimestamp(epochMs: Long): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
        formatter.timeZone = TimeZone.getDefault()
        return formatter.format(Date(epochMs))
    }

    private fun createUniqueFinalFile(outputDir: File, timestamp: String): File {
        var attempt = 0
        while (attempt < 100) {
            val suffix = if (attempt == 0) "" else "-$attempt"
            val candidate = File(outputDir, "$FILE_PREFIX$timestamp$suffix$FILE_SUFFIX")
            if (!candidate.exists()) return candidate
            attempt += 1
        }
        throw IOException("Failed to create unique transfer file after 100 attempts")
    }
}

package com.coderred.andclaw.data.transfer

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Android-friendly helper for decrypting/analyzing/packing .transfer artifacts.
 *
 * Feature parity target with tools/windows-transfer-decrypter/transfer_decrypter.py:
 * - decrypt folder (zip/extract/raw)
 * - open one transfer for edit
 * - create transfer from folder/zip/raw
 * - transfer metadata analysis
 */
object TransferDecrypter {
    enum class OutputFormat {
        ZIP,
        EXTRACT,
        RAW,
    }

    enum class OpenMode {
        EXTRACT,
        RAW,
    }

    data class TransferAnalysis(
        val iterations: Int,
        val saltLength: Int,
        val ivLength: Int,
        val chunkSize: Int,
        val chunkCount: Int,
        val likelyZipPayload: Boolean,
    )

    fun analyzeTransfer(sourceTransferFile: File, password: CharArray): TransferAnalysis {
        if (!sourceTransferFile.exists() || !sourceTransferFile.isFile) {
            throw IOException("Transfer file not found: ${sourceTransferFile.absolutePath}")
        }

        val (iterations, saltLength, ivLength, chunkSize, chunkCount) = readTransferHeader(sourceTransferFile)
        val decrypted = TransferCrypto.decryptToBytes(sourceTransferFile, password)
        return TransferAnalysis(
            iterations = iterations,
            saltLength = saltLength,
            ivLength = ivLength,
            chunkSize = chunkSize,
            chunkCount = chunkCount,
            likelyZipPayload = looksLikeZip(decrypted),
        )
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun decryptTransfer(
        sourceTransferFile: File,
        password: CharArray,
        outputDir: File,
        outputFormat: OutputFormat,
    ): File {
        if (!sourceTransferFile.exists() || !sourceTransferFile.isFile) {
            throw IOException("Transfer file not found: ${sourceTransferFile.absolutePath}")
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("Failed to create output directory: ${outputDir.absolutePath}")
        }

        val decryptedBytes = TransferCrypto.decryptToBytes(sourceTransferFile, password)
        val baseName = sourceTransferFile.nameWithoutExtension

        return when (outputFormat) {
            OutputFormat.ZIP -> {
                val zipFile = File(outputDir, "${safeOutputName(baseName)}.zip")
                zipFile.writeBytes(decryptedBytes)
                zipFile
            }

            OutputFormat.RAW -> {
                val rawFile = File(outputDir, safeOutputName(baseName))
                rawFile.writeBytes(decryptedBytes)
                rawFile
            }

            OutputFormat.EXTRACT -> {
                val extractDir = File(outputDir, safeOutputName(baseName))
                extractZipBytesSafely(decryptedBytes, extractDir)
                extractDir
            }
        }
    }

    fun decryptFolder(
        inputDir: File,
        outputDir: File,
        password: CharArray,
        outputFormat: OutputFormat,
    ): Pair<Int, List<String>> {
        if (!inputDir.exists() || !inputDir.isDirectory) {
            throw IOException("Input directory not found: ${inputDir.absolutePath}")
        }

        val transferFiles = inputDir.listFiles { file ->
            file.isFile && file.name.lowercase(Locale.US).endsWith(".transfer")
        }?.sortedBy { it.name } ?: emptyList()

        if (transferFiles.isEmpty()) {
            throw IllegalArgumentException("Input folder me koi .transfer file nahi mili")
        }

        val failures = mutableListOf<String>()
        var success = 0

        transferFiles.forEach { source ->
            try {
                decryptTransfer(source, password, outputDir, outputFormat)
                success += 1
            } catch (cause: Exception) {
                failures += "${source.name}: ${cause.message ?: cause::class.java.simpleName}"
            }
        }

        return success to failures
    }

    fun openTransferForEdit(inputTransfer: File, outputFolder: File, password: CharArray): OpenMode {
        if (!inputTransfer.exists() || !inputTransfer.isFile) {
            throw IOException("Transfer file not found: ${inputTransfer.absolutePath}")
        }
        if (!outputFolder.exists() && !outputFolder.mkdirs()) {
            throw IOException("Failed to create output directory: ${outputFolder.absolutePath}")
        }

        val decrypted = TransferCrypto.decryptToBytes(inputTransfer, password)
        return if (looksLikeZip(decrypted)) {
            runCatching {
                extractZipBytesSafely(decrypted, outputFolder)
                OpenMode.EXTRACT
            }.getOrElse {
                // Self-heal fallback: if zip extraction fails unexpectedly, still preserve recoverable bytes.
                File(outputFolder, "payload-recovered.bin").writeBytes(decrypted)
                OpenMode.RAW
            }
        } else {
            File(outputFolder, "payload.bin").writeBytes(decrypted)
            OpenMode.RAW
        }
    }

    fun createTransfer(sourcePath: File, outputTransfer: File, password: CharArray) {
        val payloadFile = preparePayloadFile(sourcePath)
        try {
            outputTransfer.parentFile?.mkdirs()
            TransferCrypto.encryptFile(payloadFile, outputTransfer, password)
        } catch (cause: Exception) {
            outputTransfer.delete()
            throw cause
        } finally {
            if (payloadFile.name.startsWith("andclaw-transfer-payload-")) {
                payloadFile.delete()
            }
        }
    }

    @Throws(IOException::class)
    private fun extractZipBytesSafely(zipBytes: ByteArray, targetDir: File) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create extract directory: ${targetDir.absolutePath}")
        }

        val targetCanonicalPath = targetDir.canonicalFile
        ZipInputStream(zipBytes.inputStream().buffered()).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                try {
                    val outFile = File(targetDir, entry.name)
                    val outCanonical = outFile.canonicalFile

                    if (!outCanonical.path.startsWith(targetCanonicalPath.path + File.separator)) {
                        throw IOException("Unsafe ZIP path detected: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        if (!outCanonical.exists() && !outCanonical.mkdirs()) {
                            throw IOException("Failed to create directory: ${outCanonical.absolutePath}")
                        }
                        continue
                    }

                    val parent = outCanonical.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw IOException("Failed to create parent directory: ${parent.absolutePath}")
                    }

                    FileOutputStream(outCanonical).use { fos ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = zipInput.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            fos.write(buffer, 0, read)
                        }
                    }
                } finally {
                    zipInput.closeEntry()
                }
            }
        }
    }

    private fun preparePayloadFile(sourcePath: File): File {
        if (!sourcePath.exists()) {
            throw IllegalArgumentException("Source path not found")
        }

        return when {
            sourcePath.isDirectory -> zipFolderToTempFile(sourcePath)
            sourcePath.isFile && sourcePath.extension.equals("zip", ignoreCase = true) -> sourcePath
            sourcePath.isFile -> sourcePath
            else -> throw IllegalArgumentException("Source path not found")
        }
    }

    private fun zipFolderToTempFile(folder: File): File {
        val files = folder.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(folder).invariantSeparatorsPath }.toList()
        val tempZip = kotlin.io.path.createTempFile(prefix = "andclaw-transfer-payload-", suffix = ".zip").toFile()

        ZipOutputStream(tempZip.outputStream().buffered()).use { zipOut ->
            files.forEach { file ->
                val entryName = file.relativeTo(folder).invariantSeparatorsPath
                zipOut.putNextEntry(ZipEntry(entryName))
                file.inputStream().buffered().use { input ->
                    input.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }

        return tempZip
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean {
        return bytes.size >= 4 && (
            (bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
                (bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte())
            )
    }

    private fun safeOutputName(sourceName: String): String {
        val sanitized = sourceName.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_', ' ')
        return if (sanitized.isBlank()) "transfer_output" else sanitized
    }

    private fun readTransferHeader(sourceTransferFile: File): HeaderData {
        DataInputStream(sourceTransferFile.inputStream().buffered()).use { input ->
            val magic = ByteArray(4)
            input.readFully(magic)
            if (!magic.contentEquals(byteArrayOf('A'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte()))) {
                throw IllegalArgumentException("Invalid transfer artifact format")
            }

            val iterations = input.readInt()
            if (iterations <= 0) throw IllegalArgumentException("Invalid transfer artifact format")

            val saltLength = input.readUnsignedByte()
            val ivLength = input.readUnsignedByte()
            if (saltLength <= 0 || ivLength <= 0) throw IllegalArgumentException("Invalid transfer artifact format")

            skipFully(input, saltLength)
            skipFully(input, ivLength)

            val chunkSize = input.readInt()
            if (chunkSize <= 0 || chunkSize > 16 * 1024 * 1024) {
                throw IllegalArgumentException("Invalid transfer artifact format")
            }

            var chunkCount = 0
            while (true) {
                val encLen = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                if (encLen <= 0 || encLen > chunkSize + 16) {
                    throw IllegalArgumentException("Invalid transfer artifact format")
                }
                skipFully(input, encLen)
                chunkCount += 1
            }

            return HeaderData(iterations, saltLength, ivLength, chunkSize, chunkCount)
        }
    }

    private fun skipFully(input: DataInputStream, total: Int) {
        var remaining = total
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            if (skipped <= 0) {
                throw EOFException("Unexpected EOF while reading transfer artifact")
            }
            remaining -= skipped
        }
    }

    private data class HeaderData(
        val iterations: Int,
        val saltLength: Int,
        val ivLength: Int,
        val chunkSize: Int,
        val chunkCount: Int,
    )
}

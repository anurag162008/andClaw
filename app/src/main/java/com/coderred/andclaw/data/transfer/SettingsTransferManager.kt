package com.coderred.andclaw.data.transfer

data class SettingsTransferExportRequest(
    val request: TransferExportRequest,
)

data class SettingsTransferImportRequest(
    val request: TransferRestoreRequest,
)

enum class SettingsTransferFailureReason {
    WRONG_PASSWORD,
    VERSION_MISMATCH,
    TRANSIENT_RUNTIME,
    UNKNOWN,
}

sealed interface SettingsTransferExportResult {
    data class Success(
        val artifact: TransferExportArtifact,
    ) : SettingsTransferExportResult

    data class Error(
        val reason: SettingsTransferFailureReason,
        val message: String,
        val cause: Throwable? = null,
    ) : SettingsTransferExportResult
}

sealed interface SettingsTransferImportResult {
    data class Success(
        val result: TransferRestoreResult,
        val warningMessage: String? = null,
    ) : SettingsTransferImportResult

    data class Error(
        val reason: SettingsTransferFailureReason,
        val message: String,
        val cause: Throwable? = null,
    ) : SettingsTransferImportResult
}

interface SettingsTransferManager {
    suspend fun export(request: SettingsTransferExportRequest): SettingsTransferExportResult

    suspend fun import(request: SettingsTransferImportRequest): SettingsTransferImportResult
}

class DefaultSettingsTransferManager : SettingsTransferManager {
    override suspend fun export(request: SettingsTransferExportRequest): SettingsTransferExportResult {
        return runCatching {
            TransferExportWriter.write(request.request)
        }.fold(
            onSuccess = { artifact -> SettingsTransferExportResult.Success(artifact) },
            onFailure = ::toExportError,
        )
    }

    override suspend fun import(request: SettingsTransferImportRequest): SettingsTransferImportResult {
        return runCatching {
            TransferRestoreManager.restore(request.request)
        }.fold(
            onSuccess = { restored ->
                SettingsTransferImportResult.Success(
                    result = restored,
                    warningMessage = restored.warningMessage,
                )
            },
            onFailure = { cause ->
                if (cause is TransferRestoreRuntimeStartException && cause.partialResult != null) {
                    SettingsTransferImportResult.Success(
                        result = cause.partialResult,
                        warningMessage = cause.partialResult.warningMessage ?: cause.message,
                    )
                } else {
                    toImportError(cause)
                }
            },
        )
    }

    internal fun toExportError(cause: Throwable): SettingsTransferExportResult.Error {
        return SettingsTransferExportResult.Error(
            reason = classifyFailureReason(cause),
            message = cause.message ?: "Failed to export transfer artifact.",
            cause = cause,
        )
    }

    internal fun toImportError(cause: Throwable): SettingsTransferImportResult.Error {
        return SettingsTransferImportResult.Error(
            reason = if (cause is TransferRestoreRuntimeStartException) {
                SettingsTransferFailureReason.TRANSIENT_RUNTIME
            } else {
                classifyFailureReason(cause)
            },
            message = when (cause) {
                is TransferRestoreRuntimeStartException -> cause.message
                    ?: "Gateway startup verification failed after restore."
                else -> cause.message ?: "Failed to import transfer artifact."
            },
            cause = cause,
        )
    }

    private fun classifyFailureReason(cause: Throwable): SettingsTransferFailureReason {
        val message = cause.message.orEmpty().lowercase()
        if (
            message.contains("invalid password") ||
            message.contains("corrupted transfer artifact") ||
            message.contains("invalid transfer artifact format")
        ) {
            return SettingsTransferFailureReason.WRONG_PASSWORD
        }
        if (
            message.contains("versioncode mismatch") ||
            message.contains("versionname mismatch") ||
            message.contains("applicationid mismatch") ||
            message.contains("version mismatch")
        ) {
            return SettingsTransferFailureReason.VERSION_MISMATCH
        }
        return SettingsTransferFailureReason.UNKNOWN
    }
}

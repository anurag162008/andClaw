package com.coderred.andclaw.proot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.coroutines.coroutineContext

object ArchiveUtils {

    /**
     * tar.gz 파일을 지정 디렉토리에 압축 해제한다.
     * symlink, hardlink, 파일 퍼미션을 보존한다.
     * zip-slip 공격을 방지한다.
     *
     * @param tarGzFile 압축 파일
     * @param destDir 추출 대상 디렉토리
     * @param stripComponents 경로에서 제거할 선행 컴포넌트 수 (tar --strip-components와 동일)
     * @param onProgress 진행률 콜백 (추출된 엔트리 수)
     */
    suspend fun extractTarGz(
        tarGzFile: File,
        destDir: File,
        stripComponents: Int = 0,
        onProgress: (extractedEntries: Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val destCanonical = destDir.canonicalPath
        var entryCount = 0

        GzipCompressorInputStream(
            BufferedInputStream(FileInputStream(tarGzFile), 65536)
        ).use { gzIn ->
            TarArchiveInputStream(gzIn).use { tarIn ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry: TarArchiveEntry = tarIn.nextEntry ?: break

                    // 경로 정규화
                    var name = entry.name
                        .removePrefix("./")
                        .removePrefix("/")

                    // strip-components 적용
                    if (stripComponents > 0) {
                        val parts = name.split("/")
                        if (parts.size <= stripComponents) continue
                        name = parts.drop(stripComponents).joinToString("/")
                    }

                    if (name.isEmpty() || name == ".") continue

                    val outFile = File(destDir, name)

                    // Zip-slip 방지
                    if (!outFile.canonicalPath.startsWith(destCanonical)) {
                        continue
                    }

                    when {
                        entry.isDirectory -> {
                            outFile.mkdirs()
                        }

                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            // 기존 파일 삭제 후 심볼릭 링크 생성
                            outFile.delete()
                            try {
                                Files.createSymbolicLink(
                                    outFile.toPath(),
                                    Paths.get(entry.linkName),
                                )
                            } catch (_: Exception) {
                                // 심볼릭 링크 생성 실패 시 무시 (일부 Android 파일시스템 제한)
                            }
                        }

                        entry.isLink -> {
                            outFile.parentFile?.mkdirs()
                            val normalizedLinkName = entry.linkName
                                .removePrefix("./")
                                .removePrefix("/")
                            val linkTarget = File(destDir, normalizedLinkName)

                            // tar 입력 중복/자기참조 hardlink가 들어온 경우(예: path -> same path),
                            // 기존 파일을 지우면 원본까지 사라지므로 no-op 처리한다.
                            val isSelfHardLink = outFile.absoluteFile.toPath().normalize() ==
                                linkTarget.absoluteFile.toPath().normalize()
                            if (!isSelfHardLink) {
                                outFile.delete()
                                try {
                                    Files.createLink(outFile.toPath(), linkTarget.toPath())
                                } catch (_: Exception) {
                                    // 하드 링크 실패 시 파일 복사로 폴백
                                    if (linkTarget.exists()) {
                                        linkTarget.copyTo(outFile, overwrite = true)
                                    }
                                }
                            }
                        }

                        else -> {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { output ->
                                val buffer = ByteArray(32768)
                                var len: Int
                                while (tarIn.read(buffer).also { len = it } != -1) {
                                    output.write(buffer, 0, len)
                                }
                            }
                        }
                    }

                    // 퍼미션 설정 (심볼릭 링크 제외)
                    if (!entry.isSymbolicLink && outFile.exists()) {
                        setFilePermissions(outFile, entry.mode)
                    }

                    entryCount++
                    if (entryCount % 200 == 0) {
                        onProgress(entryCount)
                    }
                }
            }
        }

        onProgress(entryCount)
        entryCount
    }

    private fun setFilePermissions(file: File, mode: Int) {
        // owner/group/other execute bit: 0111 = 0x49
        val isExecutable = (mode and 73) != 0 // 0111 in octal = 73 in decimal
        file.setReadable(true, false)
        file.setExecutable(isExecutable, false)
        file.setWritable(true, true)
    }
}

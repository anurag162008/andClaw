package com.coderred.andclaw.proot

import android.content.Context
import android.content.pm.PackageManager
import com.coderred.andclaw.data.SetupState
import com.coderred.andclaw.data.SetupStep
import com.coderred.andclaw.proot.installer.DirectoryInstallSpec
import com.coderred.andclaw.proot.installer.DirectoryInstaller
import com.coderred.andclaw.proot.installer.ExecutableManifest
import com.coderred.andclaw.proot.installer.TarInstallSpec
import com.coderred.andclaw.proot.installer.TarInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 원클릭 환경 세팅을 관리하는 매니저.
 *
 * rootfs, Node.js, 시스템 도구, OpenClaw, Playwright Chromium 은
 * APK assets 에 번들되어 네트워크 없이 로컬에서 추출/설치가 가능하다.
 *
 * assets 구조:
 *   assets/
 *     rootfs.tar.gz.bin                     (~30MB)  Ubuntu 24.04 arm64 base
 *     node-arm64.tar.gz.bin                 (~25MB)  Node.js 22 arm64 linux
 *     system-tools-arm64.tar.gz.bin         (~80-100MB) git, curl, python3, 시스템 libs
 *     openclaw/                             OpenClaw 파일 트리(증분 업데이트 대상)
 *     playwright-chromium-arm64.tar.gz.bin  (~150-180MB) Chromium headless_shell
 *
 * 전체 흐름:
 * 1. proot 바이너리 확인 (jniLibs 에서 추출됨)
 * 2. rootfs 추출 (assets -> filesDir/rootfs)
 * 3. rootfs 설정 (DNS, hosts, profile)
 * 4. Node.js 추출 (assets -> rootfs/usr/local)
 * 5. 시스템 도구 설치 (assets -> rootfs)
 * 6. OpenClaw 설치 (assets -> rootfs)
 * 7. Playwright Chromium 설치 (assets -> rootfs)
 * 8. Bionic libc 호환성 패치
 * 9. 설치 검증
 * 10. OpenClaw 온보딩
 */
class SetupManager(
    private val context: Context,
    private val prootManager: ProotManager,
) {
    private val executableManifest = ExecutableManifest(context)
    private val tarInstaller = TarInstaller(context, executableManifest)
    private val directoryInstaller = DirectoryInstaller(context, executableManifest)

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    // ── 로그 / 상태 헬퍼 ──

    private fun log(message: String) {
        _state.value = _state.value.copy(
            logLines = _state.value.logLines + message,
        )
    }

    private fun updateStep(step: SetupStep, progress: Float = -1f) {
        _state.value = _state.value.copy(
            currentStep = step,
            progress = if (progress >= 0) progress else _state.value.progress,
        )
    }

    private fun updateBytes(downloaded: Long, total: Long) {
        _state.value = _state.value.copy(
            downloadedBytes = downloaded,
            totalBytes = total,
        )
    }

    // ── 메인 설치 흐름 ──

    suspend fun runFullSetup(): Boolean = withContext(Dispatchers.IO) {
        _state.value = SetupState(isInProgress = true)

        try {
            // ─── Step 1: proot 바이너리 확인 ───
            updateStep(SetupStep.CHECKING_PROOT, 0.02f)
            log(">> Checking proot binary...")
            if (!prootManager.isProotAvailable) {
                throw SetupException(
                    "Cannot find proot binary (libproot.so).\n" +
                        "Run scripts/setup-assets.sh and build again."
                )
            }
            log("   proot: ${prootManager.prootBinaryPath}")
            prootManager.setupNativeLibLinks()
            val tallocLink = java.io.File(prootManager.libLinksDir, "libtalloc.so.2")
            if (tallocLink.exists()) {
                log("   libtalloc.so.2: ${tallocLink.absolutePath} (${tallocLink.length()} bytes)")
            } else {
                log("   WARNING: Failed to create libtalloc.so.2")
                log("   nativeLibDir: ${context.applicationInfo.nativeLibraryDir}")
                val nativeDir = java.io.File(context.applicationInfo.nativeLibraryDir)
                nativeDir.listFiles()?.forEach { f ->
                    log("     ${f.name} (${f.length()} bytes)")
                }
            }
            log("   LD_LIBRARY_PATH: ${prootManager.ldLibraryPath()}")
            log("   Library links ready")

            // ─── Step 2: rootfs 추출 (assets -> filesDir) ───
            if (!prootManager.isRootfsInstalled) {
                extractRootfsFromAssets()
            } else {
                log(">> rootfs already installed, skipping")
                updateStep(SetupStep.CONFIGURING_ROOTFS, 0.22f)
            }

            // ─── Step 3: rootfs 기본 설정 ───
            updateStep(SetupStep.CONFIGURING_ROOTFS, 0.24f)
            configureRootfs()

            // ─── Step 4: Node.js 추출 (assets -> rootfs/usr/local) ───
            if (!prootManager.isNodeInstalled) {
                extractNodeJsFromAssets()
            } else {
                log(">> Node.js already installed, skipping")
                updateStep(SetupStep.INSTALLING_TOOLS, 0.40f)
            }

            // ─── Step 5: 시스템 도구 설치 (git, curl, python3, libs) ───
            if (!prootManager.isSystemToolsInstalled || isToolsOutdated()) {
                installSystemTools()
            } else {
                log(">> System tools already installed (v${getInstalledVersion(toolsVersionFile)}), skipping")
                updateStep(SetupStep.INSTALLING_OPENCLAW, 0.57f)
            }

            // ─── Step 6: OpenClaw 설치 ───
            if (!prootManager.isOpenClawInstalled || isOpenClawOutdated()) {
                installOpenClaw()
            } else {
                log(">> OpenClaw already installed (v${getInstalledVersion(openclawVersionFile)}), skipping")
                updateStep(SetupStep.INSTALLING_CHROMIUM, 0.67f)
            }

            // ─── Step 7: Playwright Chromium 설치 ───
            if (!prootManager.isChromiumInstalled || isPlaywrightOutdated()) {
                installPlaywright()
            } else {
                log(">> Chromium already installed (v${getInstalledVersion(playwrightVersionFile)}), skipping")
                updateStep(SetupStep.APPLYING_PATCHES, 0.90f)
            }

            // ─── Step 8: 호환성 패치 ───
            updateStep(SetupStep.APPLYING_PATCHES, 0.90f)
            applyPatches()

            // ─── Step 9: 검증 ───
            updateStep(SetupStep.VERIFYING, 0.92f)
            verify()

            // ─── Step 10: OpenClaw 초기 설정 (ensureOpenClawConfig에서 처리) ───
            log(">> OpenClaw config will be created on first gateway start")

            // ─── 완료 ───
            updateStep(SetupStep.COMPLETE, 1.0f)
            log(">> Setup completed successfully!")
            _state.value = _state.value.copy(isInProgress = false)
            true
        } catch (e: Exception) {
            log("!! Error: ${e.message}")
            _state.value = _state.value.copy(
                isInProgress = false,
                currentStep = SetupStep.FAILED,
                error = e.message,
            )
            false
        }
    }

    // ── Step 2: assets 에서 rootfs 추출 ──

    private suspend fun extractRootfsFromAssets() {
        updateStep(SetupStep.EXTRACTING_ROOTFS, 0.05f)
        log(">> Extracting rootfs (bundled assets)...")

        // tar.gz 추출
        updateStep(SetupStep.EXTRACTING_ROOTFS, 0.10f)
        log("   Extracting... (this may take a few minutes)")
        updateBytes(0, 0)

        tarInstaller.install(
            TarInstallSpec(
                assetName = ProotManager.ROOTFS_ASSET,
                cacheDir = prootManager.cacheDir,
                destinationDir = prootManager.rootfsDir,
                permissionRootDir = prootManager.rootfsDir,
            ),
        ) { entries ->
            log("   Extracting... ($entries entries)")
            val pct = (entries.toFloat() / 20000).coerceAtMost(1f)
            _state.value = _state.value.copy(progress = 0.10f + pct * 0.12f)
        }

        log("   rootfs extraction complete")
        updateStep(SetupStep.EXTRACTING_ROOTFS, 0.22f)
    }

    // ── Step 3: rootfs 설정 ──

    private fun configureRootfs() {
        val rootfsDir = prootManager.rootfsDir
        log(">> Applying rootfs configuration...")

        // DNS
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        }
        log("   DNS configured (8.8.8.8)")

        // /etc/hosts
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n::1 localhost\n")
        }

        // /etc/hostname
        File(rootfsDir, "etc/hostname").apply {
            parentFile?.mkdirs()
            writeText("andclaw\n")
        }

        // /root
        File(rootfsDir, "root").mkdirs()

        // /root/.profile
        val deviceTz = java.util.TimeZone.getDefault().id  // e.g. "Asia/Seoul"
        File(rootfsDir, "root/.profile").writeText(
            buildString {
                appendLine("export HOME=/root")
                appendLine("export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin")
                appendLine("export LANG=C.UTF-8")
                appendLine("export TZ=$deviceTz")
                appendLine("export UV_USE_IO_URING=0")
                appendLine("export npm_config_cache=/tmp/.npm")
                appendLine("export PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright")
            }
        )
        log("   Timezone: $deviceTz")

        // /tmp, /var/tmp
        File(rootfsDir, "tmp").apply { mkdirs(); setWritable(true, false) }
        File(rootfsDir, "var/tmp").mkdirs()

        // /etc/passwd, /etc/group (Node.js 모듈이 참조)
        File(rootfsDir, "etc/passwd").apply {
            if (!exists() || !readText().contains("root:")) {
                writeText("root:x:0:0:root:/root:/bin/sh\nnobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n")
            }
        }
        File(rootfsDir, "etc/group").apply {
            if (!exists()) {
                writeText("root:x:0:\nnogroup:x:65534:\n")
            }
        }

        // .gitconfig — SSH->HTTPS 리다이렉트 + SSL 검증 비활성화 (ca-certificates 없이 동작)
        File(rootfsDir, "root/.gitconfig").writeText(buildString {
            appendLine("[url \"https://github.com/\"]")
            appendLine("    insteadOf = ssh://git@github.com/")
            appendLine("    insteadOf = git@github.com:")
            appendLine("[http]")
            appendLine("    sslVerify = false")
        })

        log("   Configuration complete")
    }

    // ── Step 4: assets 에서 Node.js 추출 ──

    private suspend fun extractNodeJsFromAssets() {
        updateStep(SetupStep.EXTRACTING_NODEJS, 0.26f)
        log(">> Installing Node.js ${ProotManager.NODEJS_VERSION} (bundled assets)...")

        // 추출 (strip-components=1 로 node-v22.x/ 프리픽스 제거)
        updateStep(SetupStep.EXTRACTING_NODEJS, 0.30f)
        log("   Installing...")

        val usrLocal = File(prootManager.rootfsDir, "usr/local")
        usrLocal.mkdirs()

        tarInstaller.install(
            TarInstallSpec(
                assetName = ProotManager.NODEJS_ASSET,
                cacheDir = prootManager.cacheDir,
                destinationDir = usrLocal,
                permissionRootDir = prootManager.rootfsDir,
                stripComponents = 1,
            ),
        ) { entries ->
            if (entries % 500 == 0) log("   Installing... ($entries entries)")
            val pct = (entries.toFloat() / 5000).coerceAtMost(1f)
            _state.value = _state.value.copy(progress = 0.30f + pct * 0.08f)
        }

        log("   Node.js installation complete")
        updateStep(SetupStep.EXTRACTING_NODEJS, 0.38f)
    }

    // ── Step 5: 시스템 도구 설치 (git, curl, python3, 시스템 libs) ──

    private suspend fun installSystemTools() = withContext(Dispatchers.IO) {
        updateStep(SetupStep.INSTALLING_TOOLS, 0.40f)
        log(">> Installing system tools (git, curl, python3, libs)...")

        updateStep(SetupStep.INSTALLING_TOOLS, 0.44f)
        log("   Extracting bundle...")

        tarInstaller.install(
            TarInstallSpec(
                assetName = ProotManager.SYSTEM_TOOLS_ASSET,
                cacheDir = prootManager.cacheDir,
                destinationDir = prootManager.rootfsDir,
                permissionRootDir = prootManager.rootfsDir,
            ),
        ) { entries ->
            if (entries % 1000 == 0) log("   Extracting... ($entries entries)")
            val pct = (entries.toFloat() / 15000).coerceAtMost(1f)
            _state.value = _state.value.copy(progress = 0.44f + pct * 0.11f)
        }

        saveVersion(toolsVersionFile)
        updateStep(SetupStep.INSTALLING_TOOLS, 0.55f)
        log(">> System tools installation complete")
    }

    // ── Step 6: OpenClaw 설치 ──

    private suspend fun installOpenClaw() = withContext(Dispatchers.IO) {
        updateStep(SetupStep.INSTALLING_OPENCLAW, 0.57f)
        log(">> Installing OpenClaw...")

        updateStep(SetupStep.INSTALLING_OPENCLAW, 0.60f)
        log("   Copying files...")

        val hasOpenClawDir = context.assets.list(ProotManager.OPENCLAW_ASSET_DIR)?.isNotEmpty() == true
        if (!hasOpenClawDir) {
            throw SetupException("OpenClaw assets directory not found (${ProotManager.OPENCLAW_ASSET_DIR})")
        }

        directoryInstaller.install(
            DirectoryInstallSpec(
                assetPath = ProotManager.OPENCLAW_ASSET_DIR,
                destinationDir = prootManager.rootfsDir,
                permissionRootDir = prootManager.rootfsDir,
                permissionKey = ProotManager.OPENCLAW_ASSET_DIR,
                cleanRelativePaths = listOf(
                    "usr/local/lib/node_modules/openclaw",
                    "usr/local/bin/openclaw",
                ),
            ),
        ) { entries ->
            if (entries % 1000 == 0) log("   Extracting... ($entries entries)")
            val pct = (entries.toFloat() / 10000).coerceAtMost(1f)
            _state.value = _state.value.copy(progress = 0.60f + pct * 0.05f)
        }
        restoreOpenClawUnderscorePaths()
        ensureOpenClawExecutable()

        log("   OpenClaw installation complete")

        if (!prootManager.isOpenClawInstalled) {
            throw SetupException("Cannot find OpenClaw module after installation")
        }

        saveVersion(openclawVersionFile)
        updateStep(SetupStep.INSTALLING_OPENCLAW, 0.65f)
        log(">> OpenClaw installation complete")
    }

    private fun restoreOpenClawUnderscorePaths() {
        val openclawRoot = File(prootManager.rootfsDir, "usr/local/lib/node_modules/openclaw")
        if (!openclawRoot.exists()) return

        val encodedPrefix = "andclaw_us__"
        openclawRoot.walkTopDown()
            .filter { it.name.startsWith(encodedPrefix) }
            .toList()
            .sortedByDescending { it.absolutePath.length }
            .forEach { entry ->
                val restoredName = "_" + entry.name.removePrefix(encodedPrefix)
                val restored = File(entry.parentFile, restoredName)
                if (restored.exists()) {
                    if (restored.isDirectory) restored.deleteRecursively() else restored.delete()
                }
                entry.renameTo(restored)
            }
    }

    private fun ensureOpenClawExecutable() {
        val openClawBin = File(prootManager.rootfsDir, "usr/local/bin/openclaw")
        if (!openClawBin.exists()) {
            throw SetupException("OpenClaw executable not found: ${openClawBin.path}")
        }
        openClawBin.setReadable(true, false)
        val executableOk = openClawBin.setExecutable(true, false)
        if (!executableOk || !openClawBin.canExecute()) {
            throw SetupException("Failed to set execute permission for OpenClaw: ${openClawBin.path}")
        }
        log("   OpenClaw execute permission applied")
    }

    // ── Step 7: Playwright Chromium 설치 ──

    private suspend fun installPlaywright() = withContext(Dispatchers.IO) {
        updateStep(SetupStep.INSTALLING_CHROMIUM, 0.67f)
        log(">> Installing Playwright Chromium...")

        updateStep(SetupStep.INSTALLING_CHROMIUM, 0.72f)
        log("   Extracting bundle...")

        tarInstaller.install(
            TarInstallSpec(
                assetName = ProotManager.PLAYWRIGHT_ASSET,
                cacheDir = prootManager.cacheDir,
                destinationDir = prootManager.rootfsDir,
                permissionRootDir = prootManager.rootfsDir,
            ),
        ) { entries ->
            if (entries % 1000 == 0) log("   Extracting... ($entries entries)")
            val pct = (entries.toFloat() / 5000).coerceAtMost(1f)
            _state.value = _state.value.copy(progress = 0.72f + pct * 0.16f)
        }

        if (!prootManager.refreshChromiumExecutableMarker()) {
            throw SetupException("Cannot find Chromium executable after installation")
        }
        log("   Chromium executable marker updated")

        saveVersion(playwrightVersionFile)
        updateStep(SetupStep.INSTALLING_CHROMIUM, 0.88f)
        log(">> Playwright Chromium installation complete")
    }

    // ── 번들 업데이트 (앱 업데이트 후 게이트웨이 시작 전 호출) ──

    /**
     * 3개 번들을 각각 독립적으로 체크하고, 아웃데이트된 것만 재추출한다.
     * SetupScreen 없이 GatewayService에서 직접 호출 가능.
     */
    fun isBundleUpdateRequired(): Boolean {
        return !prootManager.isSystemToolsInstalled ||
            isToolsOutdated() ||
            !prootManager.isOpenClawInstalled ||
            isOpenClawOutdated() ||
            !prootManager.isChromiumInstalled ||
            isPlaywrightOutdated()
    }

    fun updateBundleIfNeeded(onStepChanged: ((SetupStep) -> Unit)? = null) {
        val appVersion = getAppVersionCode()

        if (!prootManager.isSystemToolsInstalled || isToolsOutdated()) {
            android.util.Log.i("SetupManager", "System tools update required (installed=${getInstalledVersion(toolsVersionFile)}, app=$appVersion)")
            onStepChanged?.invoke(SetupStep.INSTALLING_TOOLS)
            kotlinx.coroutines.runBlocking { installSystemTools() }
            android.util.Log.i("SetupManager", "System tools update complete")
        }

        if (!prootManager.isOpenClawInstalled || isOpenClawOutdated()) {
            android.util.Log.i("SetupManager", "OpenClaw update required (installed=${getInstalledVersion(openclawVersionFile)}, app=$appVersion)")
            onStepChanged?.invoke(SetupStep.INSTALLING_OPENCLAW)
            kotlinx.coroutines.runBlocking { installOpenClaw() }
            android.util.Log.i("SetupManager", "OpenClaw update complete")
        }

        if (!prootManager.isChromiumInstalled || isPlaywrightOutdated()) {
            android.util.Log.i("SetupManager", "Playwright update required (installed=${getInstalledVersion(playwrightVersionFile)}, app=$appVersion)")
            onStepChanged?.invoke(SetupStep.INSTALLING_CHROMIUM)
            kotlinx.coroutines.runBlocking { installPlaywright() }
            android.util.Log.i("SetupManager", "Playwright update complete")
        }
    }

    // ── 번들 버전 관리 (3분할) ──

    private val toolsVersionFile: File
        get() = File(prootManager.rootfsDir, ".tools_version")

    private val openclawVersionFile: File
        get() = File(prootManager.rootfsDir, ".bundle_version")

    private val playwrightVersionFile: File
        get() = File(prootManager.rootfsDir, ".playwright_version")

    private fun getAppVersionCode(): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            info.versionCode
        } catch (_: PackageManager.NameNotFoundException) {
            0
        }
    }

    private fun getInstalledVersion(file: File): Int {
        return try {
            file.readText().trim().toInt()
        } catch (_: Exception) {
            0
        }
    }

    private fun saveVersion(file: File) {
        file.writeText(getAppVersionCode().toString())
    }

    private fun isToolsOutdated(): Boolean {
        return getInstalledVersion(toolsVersionFile) < getAppVersionCode()
    }

    private fun isOpenClawOutdated(): Boolean {
        return getInstalledVersion(openclawVersionFile) < getAppVersionCode()
    }

    private fun isPlaywrightOutdated(): Boolean {
        return getInstalledVersion(playwrightVersionFile) < getAppVersionCode()
    }

    // ── Step 8: 패치 ──

    private fun applyPatches() {
        log(">> Applying Bionic libc compatibility patches...")

        // UV_USE_IO_URING=0 은 이미 .profile 에 포함

        // Node.js os.networkInterfaces() 패치
        // proot에서 getifaddrs() syscall이 EACCES를 반환하므로
        // 가짜 loopback 인터페이스를 반환하는 preload 스크립트
        val patchFile = File(prootManager.rootfsDir, "root/.openclaw-patch.js")
        patchFile.writeText(buildString {
            appendLine("const os = require('os');")
            appendLine("const _ni = os.networkInterfaces;")
            appendLine("os.networkInterfaces = function() {")
            appendLine("  try { return _ni.call(this); } catch(e) {")
            appendLine("    return {")
            appendLine("      lo: [{")
            appendLine("        address: '127.0.0.1',")
            appendLine("        netmask: '255.0.0.0',")
            appendLine("        family: 'IPv4',")
            appendLine("        mac: '00:00:00:00:00:00',")
            appendLine("        internal: true,")
            appendLine("        cidr: '127.0.0.1/8'")
            appendLine("      }]")
            appendLine("    };")
            appendLine("  }")
            appendLine("};")
        })
        log("   Created os.networkInterfaces() patch")

        log("   Patches applied")
    }

    // ── Step 9: 검증 ──

    private suspend fun verify() = withContext(Dispatchers.IO) {
        log(">> Verifying installation...")

        log("   Checking proot...")
        if (executeInProot("echo 'proot OK'") != 0) {
            throw SetupException("proot check failed")
        }

        log("   Checking Node.js...")
        if (executeInProot(". /root/.profile && node --version") != 0) {
            throw SetupException("Node.js check failed")
        }

        log("   Checking OpenClaw...")
        executeInProot(". /root/.profile && openclaw --version 2>/dev/null || openclaw --help 2>&1 | head -1")
        if (!prootManager.isOpenClawInstalled) {
            throw SetupException("OpenClaw validation failed")
        }

        if (prootManager.isChromiumInstalled) {
            log("   Chromium: installed")
        } else {
            log("   WARNING: Chromium not installed (browser tools disabled)")
        }

        log("   All checks passed!")
    }



    // ── 유틸 ──

    private fun executeInProot(command: String): Int {
        val cmd = prootManager.buildProotCommand(command)
        val env = prootManager.buildEnvironment()

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(env)

        val process = pb.start()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { log("   $it") }
            }
        }
        return process.waitFor()
    }
}

class SetupException(message: String) : Exception(message)

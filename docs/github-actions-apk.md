# GitHub Actions: Full APK build output

This repository includes a workflow at:

- `.github/workflows/build-full-apk.yml`

## What it builds

The workflow assembles a **prod debug APK** after preparing all runtime bundles via `scripts/setup-assets.sh`.
It enables arm64 Docker emulation (QEMU) in CI so Docker `--platform linux/arm64` steps used by asset preparation run correctly on GitHub-hosted runners.
It installs Android SDK components for API 35 (`platforms;android-35`, `build-tools;35.0.0`, `platform-tools`) before Gradle build.
It creates `local.properties` from `ANDROID_SDK_ROOT` and auto-generates `debug.keystore` (if missing) to avoid signing/config failures in clean CI environments.
After build, it verifies the generated APK actually contains core runtime payload files (rootfs, node, tools, openclaw, playwright, terminal backend/frontend).

Included runtime content comes from this project and setup script:

- `proot` + `talloc` + proot loaders in `app/src/main/jniLibs/arm64-v8a`
- Ubuntu arm64 rootfs
- Node.js arm64 runtime
- system-tools bundle (git/curl/python and related libs)
- OpenClaw tree
- Playwright Chromium bundle
- Terminal stack sources from `terminal/backend` and `terminal/frontend` copied into packaged assets under `terminal/root/andclaw-terminal`

Because debug builds in this project directly include install-time assets from
`install_time_assets/src/main/assets`, the artifact APK is a full runnable app package for testing.


If Gradle build fails, the workflow uploads `andclaw-gradle-failure-diagnostics` with build reports and daemon logs for debugging.

## Downloadable artifacts

After each workflow run, download from the Actions run page:

1. `andclaw-full-app-package`
   - `andclaw-full-app-package.tar.gz`
   - Includes APK, checksum, JNI libs snapshot, install-time assets snapshot,
     terminal stack source snapshot, executable manifest, and bundle fingerprint metadata
2. `andclaw-prod-debug-apk`
   - `andclaw-prod-debug.apk`
   - `andclaw-prod-debug.apk.sha256`
3. `andclaw-install-time-assets`
   - Snapshot of prepared asset payloads used for the build (including terminal sources)
4. `andclaw-jni-libs-arm64`
   - Snapshot of bundled arm64 JNI native libraries
5. `andclaw-repo-snapshot`
   - Source snapshot tarball for the exact commit that was built

## Workflow inputs (manual run)

- `force_asset_rebuild` (boolean): clear prepared runtime assets before building.
- `include_repo_snapshot` (boolean): include or skip source snapshot upload.

## Trigger

- Manual (`workflow_dispatch`)
- Pushes to `main` / `master` when Android/build related files change, including `terminal/**`

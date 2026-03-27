# GitHub Actions: Full APK build output

This repository includes a workflow at:

- `.github/workflows/build-full-apk.yml`

## What it builds

The workflow prepares runtime bundles via `scripts/setup-assets.sh`, then builds a **prod debug APK**.

The APK is validated to include bundled runtime payloads:

- `rootfs.tar.gz.bin`
- `node-arm64.tar.gz.bin`
- `system-tools-arm64.tar.gz.bin`
- OpenClaw launcher files
- Playwright Chromium bundle
- terminal backend/frontend under `assets/terminal/root/andclaw-terminal`

## Downloadable artifact

After each run, only one artifact is uploaded:

1. `andclaw-prod-debug-apk`
   - `app-prod-debug.apk`

## Workflow input (manual run)

- `force_asset_rebuild` (boolean): clear prepared runtime assets before build.

## Trigger

- Manual (`workflow_dispatch`)
- Pushes to `main` / `master` when Android/build related files change, including `terminal/**`

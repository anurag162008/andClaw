# GitHub Actions: Full APK build output

This repository includes a workflow at:

- `.github/workflows/build-full-apk.yml`

## What it builds

The workflow prepares runtime bundles via `scripts/setup-assets.sh`, then builds a **prod debug APK**.
To reduce Docker Hub auth/rate-limit failures, Docker arm64 build steps use `public.ecr.aws/ubuntu/ubuntu:24.04` by default via `DOCKER_BASE_IMAGE`.

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

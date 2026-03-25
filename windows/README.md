# andClaw for Windows (Parity Track)

This directory contains the Windows desktop parity implementation for andClaw.

## OS support

- Primary target: Windows 10 / Windows 11
- Legacy path: Windows 7 / 8 / 8.1 can run only with reduced/legacy runtime assumptions and manual dependency validation.
- Use Setup tab + Runtime Doctor for environment verification.

## Android → Windows parity mapping (implemented)

| Android capability | Windows equivalent in this repo |
|---|---|
| Setup/install progress flow | `SetupService` verification pipeline + setup UI progress/checklist |
| OAuth/API-key onboarding | Onboarding panel + `OAuthService` |
| Gateway start/stop/restart and dashboard open | `WslGatewayService` + dashboard controls |
| Provider/model/channel settings | `AppConfig`, `ChannelConfig`, settings bindings, JSON persistence |
| Session logs | In-app log list + persisted `session_logs.json` |
| Bug report export | `BugReportService` zip artifact |
| Settings transfer import/export | `TransferService` zip import/export |
| Recovery/health checks | `RuntimeDoctorService` preflight checks |

## Android-only behaviors and Windows replacements (implemented)

Some Android behaviors do not exist natively on Windows. They are replaced with explicit working equivalents:

1. Android boot receiver → Windows Startup Task / Task Scheduler guidance in UI.
2. Android native WhatsApp QR pairing path → explicit Windows replacement guidance for webhook/cloud bridge usage.

These replacement notes are surfaced directly in the Settings UI through `CapabilityService` so users are not blocked by unsupported Android APIs.

## Validation tools

- `windows/PARITY_MATRIX.md` provides full feature mapping against Android README feature list.
- `windows/scripts/verify_parity.py` fails if any Android README feature is missing from the parity matrix.

## Run

```bash
dotnet run --project windows/AndClaw.Windows/AndClaw.Windows.csproj
```

## Recommended Windows validation

1. Run **Runtime Doctor** from Setup tab.
2. Confirm WSL installed and `openclaw` available in WSL.
3. Save onboarding/provider/model.
4. Start gateway from Dashboard.
5. Generate bug report and export transfer from Settings.


## GitHub Actions: EXE build + downloadable artifact

Workflow file: `.github/workflows/windows-app-build.yml`

What it does:
1. Runs on `windows-latest`.
2. Restores and publishes the WPF app as **self-contained single-file EXE** (`win-x64`).
3. Produces `andclaw-windows-win-x64.zip` + SHA256 checksum.
4. Uploads both as downloadable Actions artifact.
5. On tag push like `v1.0.0`, also creates a GitHub Release and attaches the same files.

How to download:
1. Open **Actions** tab in GitHub.
2. Run **Build Windows EXE (andClaw)** using *Run workflow*.
3. Open workflow run → **Artifacts** → download `andclaw-windows-win-x64`.

Release download:
- Push a tag `vX.Y.Z` and open **Releases** page.
- Download `andclaw-windows-win-x64.zip` from release assets.


## If workflow ran but no downloadable asset appears

Check repository settings:
1. **Actions permissions**: allow read/write for `GITHUB_TOKEN`.
2. **Workflow permissions**: enable artifact and release upload.
3. Ensure run is on default branch or trusted context.
4. For release assets, push tag format `vX.Y.Z`.

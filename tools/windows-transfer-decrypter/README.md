# Windows .transfer Tool (AndClaw format)

Ye tool AndClaw `.transfer` files ko:
- decrypt,
- open/edit,
- aur dubara `.transfer` me pack
kar sakta hai.

## Features
- AndClaw crypto format support: `ATF2` + PBKDF2-HMAC-SHA256 + AES-256-GCM.
- Batch decrypt with formats:
  - `zip` → `.zip` file output.
  - `extract` → folder extract output.
  - `raw` → decrypted binary output.
- Single file open-for-edit: `.transfer` ko editable folder me nikaalna.
- Create transfer: folder/zip/raw file se nayi `.transfer` banana.
- Analyze transfer: header/chunk metadata + payload type hint (zip/raw).
- Hacker-style interactive console UI (neon dark theme + live status/logs).
- Auto-heal guards: retry wrapper for crypto ops + fallback raw recovery on zip-extract failure.
- GUI + CLI dono modes.

## Requirements
- Python 3.10+
- `cryptography`

```bash
pip install cryptography
```

## GUI Run

```bash
python transfer_decrypter.py
```

GUI buttons:
- **Decrypt Folder**
- **Open One .transfer (edit)**
- **Create .transfer**
- **Analyze .transfer**

## CLI Usage

### 1) Batch decrypt

```bash
python transfer_decrypter.py --decrypt-batch "C:\\input" "C:\\output" "your-password" zip
```

`zip` optional hai; valid formats: `zip`, `extract`, `raw`.

### 2) Open one transfer for editing

```bash
python transfer_decrypter.py --open-transfer "C:\\data\\backup.transfer" "C:\\editable" "your-password"
```

### 3) Create transfer from edited folder

```bash
python transfer_decrypter.py --create-transfer "C:\\editable" "C:\\new-backup.transfer" "your-password"
```

> `--create-transfer` me source folder, `.zip`, ya koi raw file de sakte ho.

### 4) Analyze one transfer

```bash
python transfer_decrypter.py --analyze-transfer "C:\\data\\backup.transfer" "your-password"
```

## Build Windows EXE (local)

```bash
pip install pyinstaller
build_windows_exe.bat
```

Output:
- `dist\\AndClawTransferDecrypter.exe`

## GitHub Actions EXE build

Repo me workflow add hai jo manual trigger pe Windows EXE build karta hai aur artifact upload karta hai.
- Workflow file: `.github/workflows/windows-transfer-decrypter.yml`
- Trigger: **Actions → Build Windows Transfer Decrypter EXE → Run workflow**

## Important
- Galat password ya corrupt file par decrypt fail hoga.
- `extract` mode me unsafe zip paths block ki jati hain.
- Sirf legal/authorized data pe use karein.

## Current Limitations
- Yeh tool `.transfer` payload ko zip/raw ke basis pe handle karta hai; payload content schema validation nahi karta.
- Bohot bade payloads par analysis/decrypt me memory usage high ho sakta hai (especially single-file operations).
- Windows GUI basic hai: advanced batch filters/progress resume abhi included nahi hain.

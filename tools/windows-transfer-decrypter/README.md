# Windows .transfer Decrypter (AndClaw format)

Ye tool AndClaw app ki `.transfer` files ko password se decrypt karta hai.

## Features
- Input folder se saari `.transfer` files read karta hai.
- Same crypto format use karta hai jo app me hai: `ATF2` + PBKDF2-HMAC-SHA256 + AES-256-GCM.
- Output options:
  - `zip` → decrypted data ko `.zip` file me save karta hai.
  - `extract` → zip content ko folder me extract karta hai.
  - `raw` → decrypted bytes ko raw file me save karta hai.
- GUI mode (double click / normal run) aur CLI mode dono available.

## Requirements
- Python 3.10+
- `cryptography`

Install dependency:

```bash
pip install cryptography
```

## Run (GUI)

```bash
python transfer_decrypter.py
```

## Run (CLI)

Default output format `zip` hai:

```bash
python transfer_decrypter.py --cli "C:\\input" "C:\\output" "your-password"
```

Custom output format:

```bash
python transfer_decrypter.py --cli "C:\\input" "C:\\output" "your-password" extract
```

Valid formats: `zip`, `extract`, `raw`.

## Build Windows EXE (PyInstaller)

1. PyInstaller install karo:

```bash
pip install pyinstaller
```

2. EXE build karo:

```bash
build_windows_exe.bat
```

3. Built app milegi:

- `dist\\AndClawTransferDecrypter.exe`

## Important
- Agar password galat hai ya file corrupt hai to decrypt fail hoga.
- `extract` mode secure extraction use karta hai (unsafe paths block hoti hain).
- Ye tool sirf un files ke liye use karo jin ka access aapko legally allowed ho.

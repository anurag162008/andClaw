@echo off
setlocal

where pyinstaller >nul 2>nul
if errorlevel 1 (
  echo PyInstaller not found. Install with: pip install pyinstaller
  exit /b 1
)

pyinstaller --noconfirm --onefile --windowed --name AndClawTransferDecrypter transfer_decrypter.py
if errorlevel 1 exit /b 1

echo Build completed: dist\AndClawTransferDecrypter.exe

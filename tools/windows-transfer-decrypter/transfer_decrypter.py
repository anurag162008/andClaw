#!/usr/bin/env python3
"""Windows-friendly AndClaw .transfer decrypter/editor/packer.

Supports:
- Decrypting .transfer files to zip/extracted/raw output.
- Opening a single .transfer into an editable folder.
- Creating a new .transfer from a folder, zip, or raw file.
"""

from __future__ import annotations

import dataclasses
import os
import pathlib
import secrets
import struct
import sys
import traceback
import zipfile
from io import BytesIO
from tkinter import Tk, StringVar, filedialog, messagebox, ttk

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

MAGIC = b"ATF2"
PBKDF2_ITERATIONS = 120_000
KEY_LENGTH_BITS = 256
GCM_TAG_LENGTH_BITS = 128
SALT_LENGTH_BYTES = 16
IV_LENGTH_BYTES = 12
CHUNK_SIZE = 1 * 1024 * 1024
MAX_CHUNK_SIZE = 16 * 1024 * 1024

OUTPUT_FORMAT_ZIP = "zip"
OUTPUT_FORMAT_EXTRACT = "extract"
OUTPUT_FORMAT_RAW = "raw"
OUTPUT_FORMATS = (OUTPUT_FORMAT_ZIP, OUTPUT_FORMAT_EXTRACT, OUTPUT_FORMAT_RAW)


@dataclasses.dataclass
class TransferHeader:
    iterations: int
    salt: bytes
    base_iv: bytes
    chunk_size: int


def _derive_key(password: str, salt: bytes, iterations: int) -> bytes:
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=KEY_LENGTH_BITS // 8,
        salt=salt,
        iterations=iterations,
    )
    return kdf.derive(password.encode("utf-8"))


def _derive_chunk_iv(base_iv: bytes, chunk_index: int) -> bytes:
    iv = bytearray(base_iv)
    for i in range(8):
        iv[len(iv) - 1 - i] ^= (chunk_index >> (i * 8)) & 0xFF
    return bytes(iv)


def _read_header(raw: bytes, offset: int = 0) -> tuple[TransferHeader, int]:
    if raw[offset : offset + 4] != MAGIC:
        raise ValueError("Invalid file format: missing ATF2 header")
    offset += 4

    (iterations,) = struct.unpack_from(">I", raw, offset)
    offset += 4
    if iterations <= 0:
        raise ValueError("Invalid file format: iterations must be > 0")

    salt_length = raw[offset]
    iv_length = raw[offset + 1]
    offset += 2
    if salt_length <= 0 or iv_length <= 0:
        raise ValueError("Invalid file format: salt/iv length must be > 0")

    salt = raw[offset : offset + salt_length]
    offset += salt_length
    base_iv = raw[offset : offset + iv_length]
    offset += iv_length

    (chunk_size,) = struct.unpack_from(">I", raw, offset)
    offset += 4
    if chunk_size <= 0 or chunk_size > MAX_CHUNK_SIZE:
        raise ValueError("Invalid file format: chunk size out of range")

    return TransferHeader(iterations=iterations, salt=salt, base_iv=base_iv, chunk_size=chunk_size), offset


def decrypt_transfer_bytes(input_path: pathlib.Path, password: str) -> bytes:
    raw = input_path.read_bytes()
    header, offset = _read_header(raw)
    key = _derive_key(password, header.salt, header.iterations)
    aesgcm = AESGCM(key)

    max_enc_chunk_size = header.chunk_size + (GCM_TAG_LENGTH_BITS // 8)
    plain_parts: list[bytes] = []
    chunk_index = 0

    while offset < len(raw):
        if offset + 4 > len(raw):
            raise ValueError("Corrupted transfer file: incomplete chunk length")

        (enc_len,) = struct.unpack_from(">I", raw, offset)
        offset += 4
        if enc_len <= 0 or enc_len > max_enc_chunk_size:
            raise ValueError("Corrupted transfer file: invalid encrypted chunk length")

        enc_chunk = raw[offset : offset + enc_len]
        if len(enc_chunk) != enc_len:
            raise ValueError("Corrupted transfer file: truncated encrypted chunk")
        offset += enc_len

        chunk_iv = _derive_chunk_iv(header.base_iv, chunk_index)
        try:
            plain_parts.append(aesgcm.decrypt(chunk_iv, enc_chunk, None))
        except Exception as exc:  # noqa: BLE001
            raise ValueError("Invalid password or corrupted file") from exc
        chunk_index += 1

    return b"".join(plain_parts)


def encrypt_transfer_bytes(plain: bytes, password: str) -> bytes:
    salt = secrets.token_bytes(SALT_LENGTH_BYTES)
    base_iv = secrets.token_bytes(IV_LENGTH_BYTES)
    key = _derive_key(password, salt, PBKDF2_ITERATIONS)
    aesgcm = AESGCM(key)

    out = bytearray()
    out.extend(MAGIC)
    out.extend(struct.pack(">I", PBKDF2_ITERATIONS))
    out.extend(bytes([SALT_LENGTH_BYTES, IV_LENGTH_BYTES]))
    out.extend(salt)
    out.extend(base_iv)
    out.extend(struct.pack(">I", CHUNK_SIZE))

    chunk_index = 0
    for start in range(0, len(plain), CHUNK_SIZE):
        chunk = plain[start : start + CHUNK_SIZE]
        chunk_iv = _derive_chunk_iv(base_iv, chunk_index)
        encrypted = aesgcm.encrypt(chunk_iv, chunk, None)
        out.extend(struct.pack(">I", len(encrypted)))
        out.extend(encrypted)
        chunk_index += 1

    return bytes(out)


def _safe_extract_zip_bytes(zip_bytes: bytes, target_dir: pathlib.Path) -> None:
    target_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(BytesIO(zip_bytes), mode="r") as zf:
        for info in zf.infolist():
            rel = pathlib.Path(info.filename)
            if rel.is_absolute() or ".." in rel.parts:
                raise ValueError(f"Unsafe ZIP path detected: {info.filename}")

            destination = target_dir / rel
            destination.parent.mkdir(parents=True, exist_ok=True)
            if info.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
                continue

            with zf.open(info, "r") as src, destination.open("wb") as dst:
                dst.write(src.read())


def _zip_folder_to_bytes(folder: pathlib.Path) -> bytes:
    if not folder.is_dir():
        raise ValueError("Source folder does not exist")

    mem = BytesIO()
    with zipfile.ZipFile(mem, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        files = sorted(p for p in folder.rglob("*") if p.is_file())
        for file in files:
            arcname = file.relative_to(folder).as_posix()
            zf.write(file, arcname)
    return mem.getvalue()


def _write_output(
    decrypted_bytes: bytes,
    source_transfer: pathlib.Path,
    output_dir: pathlib.Path,
    output_format: str,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)

    if output_format == OUTPUT_FORMAT_ZIP:
        (output_dir / f"{source_transfer.stem}.zip").write_bytes(decrypted_bytes)
        return

    if output_format == OUTPUT_FORMAT_RAW:
        (output_dir / source_transfer.stem).write_bytes(decrypted_bytes)
        return

    if output_format == OUTPUT_FORMAT_EXTRACT:
        _safe_extract_zip_bytes(decrypted_bytes, output_dir / source_transfer.stem)
        return

    raise ValueError(f"Unsupported output format: {output_format}")


def decrypt_folder(
    input_dir: pathlib.Path,
    output_dir: pathlib.Path,
    password: str,
    output_format: str,
) -> tuple[int, list[str]]:
    if output_format not in OUTPUT_FORMATS:
        raise ValueError(f"Invalid output format. Use one of: {', '.join(OUTPUT_FORMATS)}")

    transfer_files = sorted(input_dir.glob("*.transfer"))
    if not transfer_files:
        raise ValueError("Input folder me koi .transfer file nahi mili")

    failures: list[str] = []
    success = 0
    for source in transfer_files:
        try:
            decrypted = decrypt_transfer_bytes(source, password)
            _write_output(decrypted, source, output_dir, output_format)
            success += 1
        except Exception as exc:  # noqa: BLE001
            failures.append(f"{source.name}: {exc}")

    return success, failures


def open_transfer_for_edit(input_transfer: pathlib.Path, output_folder: pathlib.Path, password: str) -> str:
    decrypted = decrypt_transfer_bytes(input_transfer, password)
    output_folder.mkdir(parents=True, exist_ok=True)

    is_zip = decrypted.startswith(b"PK\x03\x04") or decrypted.startswith(b"PK\x05\x06")
    if is_zip:
        _safe_extract_zip_bytes(decrypted, output_folder)
        return "extract"

    (output_folder / "payload.bin").write_bytes(decrypted)
    return "raw"


def create_transfer(source_path: pathlib.Path, output_transfer: pathlib.Path, password: str) -> None:
    if source_path.is_dir():
        payload = _zip_folder_to_bytes(source_path)
    elif source_path.is_file() and source_path.suffix.lower() == ".zip":
        payload = source_path.read_bytes()
    elif source_path.is_file():
        payload = source_path.read_bytes()
    else:
        raise ValueError("Source path not found")

    encrypted = encrypt_transfer_bytes(payload, password)
    output_transfer.parent.mkdir(parents=True, exist_ok=True)
    output_transfer.write_bytes(encrypted)


class DecrypterApp:
    def __init__(self, root: Tk) -> None:
        self.root = root
        self.root.title("AndClaw .transfer Tool")
        self.root.geometry("760x380")

        self.input_dir = StringVar()
        self.output_dir = StringVar()
        self.password = StringVar()
        self.output_format = StringVar(value=OUTPUT_FORMAT_ZIP)

        frame = ttk.Frame(root, padding=16)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="Input folder (.transfer files)").grid(row=0, column=0, sticky="w")
        ttk.Entry(frame, textvariable=self.input_dir, width=72).grid(row=1, column=0, sticky="ew", padx=(0, 8))
        ttk.Button(frame, text="Browse", command=self.browse_input).grid(row=1, column=1, sticky="ew")

        ttk.Label(frame, text="Output folder").grid(row=2, column=0, sticky="w", pady=(10, 0))
        ttk.Entry(frame, textvariable=self.output_dir, width=72).grid(row=3, column=0, sticky="ew", padx=(0, 8))
        ttk.Button(frame, text="Browse", command=self.browse_output).grid(row=3, column=1, sticky="ew")

        ttk.Label(frame, text="Password").grid(row=4, column=0, sticky="w", pady=(10, 0))
        ttk.Entry(frame, textvariable=self.password, show="*", width=72).grid(row=5, column=0, sticky="ew", padx=(0, 8))

        ttk.Label(frame, text="Batch decrypt output").grid(row=6, column=0, sticky="w", pady=(10, 0))
        ttk.Combobox(
            frame,
            textvariable=self.output_format,
            values=OUTPUT_FORMATS,
            state="readonly",
            width=22,
        ).grid(row=7, column=0, sticky="w")

        ttk.Button(frame, text="Decrypt Folder", command=self.run_decrypt).grid(row=8, column=0, sticky="ew", pady=(14, 0))
        ttk.Button(frame, text="Open One .transfer (edit)", command=self.run_open_for_edit).grid(row=9, column=0, sticky="ew", pady=(8, 0))
        ttk.Button(frame, text="Create .transfer", command=self.run_create_transfer).grid(row=10, column=0, sticky="ew", pady=(8, 0))

        ttk.Label(
            frame,
            text="Tip: Open for edit extracts zip payload; after changes use Create .transfer.",
        ).grid(row=11, column=0, sticky="w", pady=(10, 0))

        frame.columnconfigure(0, weight=1)

    def browse_input(self) -> None:
        path = filedialog.askdirectory(title="Select input folder")
        if path:
            self.input_dir.set(path)
            if not self.output_dir.get():
                self.output_dir.set(os.path.join(path, "decrypted"))

    def browse_output(self) -> None:
        path = filedialog.askdirectory(title="Select output folder")
        if path:
            self.output_dir.set(path)

    def _require_password(self) -> str:
        password = self.password.get()
        if not password:
            raise ValueError("Password enter karo")
        return password

    def run_decrypt(self) -> None:
        try:
            input_dir = pathlib.Path(self.input_dir.get().strip())
            output_dir = pathlib.Path(self.output_dir.get().strip())
            success, failures = decrypt_folder(
                input_dir,
                output_dir,
                self._require_password(),
                self.output_format.get(),
            )
            if failures:
                messagebox.showwarning("Completed with errors", f"{success} success, {len(failures)} failed\n\n" + "\n".join(failures[:10]))
            else:
                messagebox.showinfo("Success", f"Sab files process ho gayi: {success}")
        except Exception as exc:  # noqa: BLE001
            messagebox.showerror("Error", str(exc))

    def run_open_for_edit(self) -> None:
        try:
            src = filedialog.askopenfilename(title="Select .transfer file", filetypes=[("Transfer Files", "*.transfer")])
            if not src:
                return
            target = filedialog.askdirectory(title="Select output folder for extracted/editable data")
            if not target:
                return
            mode = open_transfer_for_edit(pathlib.Path(src), pathlib.Path(target), self._require_password())
            messagebox.showinfo("Success", f"Opened in {mode} mode: {target}")
        except Exception as exc:  # noqa: BLE001
            messagebox.showerror("Error", str(exc))

    def run_create_transfer(self) -> None:
        try:
            src = filedialog.askdirectory(title="Select source folder to pack (editable content)")
            if not src:
                return
            out = filedialog.asksaveasfilename(
                title="Save .transfer as",
                defaultextension=".transfer",
                filetypes=[("Transfer Files", "*.transfer")],
            )
            if not out:
                return
            create_transfer(pathlib.Path(src), pathlib.Path(out), self._require_password())
            messagebox.showinfo("Success", f"Transfer file created: {out}")
        except Exception as exc:  # noqa: BLE001
            messagebox.showerror("Error", str(exc))


def _cli(argv: list[str]) -> int:
    if len(argv) in {5, 6} and argv[1] == "--decrypt-batch":
        output_format = argv[5] if len(argv) == 6 else OUTPUT_FORMAT_ZIP
        success, failures = decrypt_folder(pathlib.Path(argv[2]), pathlib.Path(argv[3]), argv[4], output_format)
        for fail in failures:
            print(f"FAIL: {fail}", file=sys.stderr)
        print(f"OK: {success} processed (format={output_format})")
        return 1 if failures else 0

    if len(argv) == 5 and argv[1] == "--open-transfer":
        mode = open_transfer_for_edit(pathlib.Path(argv[2]), pathlib.Path(argv[3]), argv[4])
        print(f"OK: opened in {mode} mode")
        return 0

    if len(argv) == 5 and argv[1] == "--create-transfer":
        create_transfer(pathlib.Path(argv[2]), pathlib.Path(argv[3]), argv[4])
        print("OK: transfer created")
        return 0

    # Backward compatibility with earlier version
    if len(argv) in {5, 6} and argv[1] == "--cli":
        output_format = argv[5] if len(argv) == 6 else OUTPUT_FORMAT_ZIP
        success, failures = decrypt_folder(pathlib.Path(argv[2]), pathlib.Path(argv[3]), argv[4], output_format)
        for fail in failures:
            print(f"FAIL: {fail}", file=sys.stderr)
        print(f"OK: {success} processed (format={output_format})")
        return 1 if failures else 0

    return -1


def main() -> int:
    cli_code = _cli(sys.argv)
    if cli_code >= 0:
        return cli_code

    try:
        root = Tk()
        DecrypterApp(root)
        root.mainloop()
        return 0
    except Exception:  # noqa: BLE001
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

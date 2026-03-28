import pathlib
import tempfile
import unittest
import zipfile

try:
    import importlib.util

    MODULE_PATH = pathlib.Path(__file__).with_name("transfer_decrypter.py")
    SPEC = importlib.util.spec_from_file_location("transfer_decrypter", MODULE_PATH)
    transfer_decrypter = importlib.util.module_from_spec(SPEC)
    assert SPEC and SPEC.loader
    SPEC.loader.exec_module(transfer_decrypter)
    IMPORT_ERROR = None
except ModuleNotFoundError as exc:  # cryptography missing in limited env
    transfer_decrypter = None
    IMPORT_ERROR = exc


class TransferDecrypterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if IMPORT_ERROR is not None:
            raise unittest.SkipTest(f"Dependency missing: {IMPORT_ERROR}")

    def test_create_and_analyze_zip_payload(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            source_dir = root / "editable"
            source_dir.mkdir()
            (source_dir / "hello.txt").write_text("hello-andclaw", encoding="utf-8")

            transfer_file = root / "backup.transfer"
            transfer_decrypter.create_transfer(source_dir, transfer_file, "pass123")

            info = transfer_decrypter.analyze_transfer_file(transfer_file, "pass123")
            self.assertEqual(info.iterations, 120_000)
            self.assertEqual(info.salt_length, 16)
            self.assertEqual(info.iv_length, 12)
            self.assertGreaterEqual(info.chunk_count, 1)
            self.assertTrue(info.likely_zip_payload)

    def test_open_transfer_for_edit_raw_payload(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            raw_file = root / "payload.bin"
            payload = b"not-a-zip-payload"
            raw_file.write_bytes(payload)

            transfer_file = root / "payload.transfer"
            transfer_decrypter.create_transfer(raw_file, transfer_file, "pass123")

            out_dir = root / "opened"
            mode = transfer_decrypter.open_transfer_for_edit(transfer_file, out_dir, "pass123")
            self.assertEqual(mode, "raw")
            self.assertEqual((out_dir / "payload.bin").read_bytes(), payload)

    def test_decrypt_folder_extracts_zip_payload(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            zip_file = root / "payload.zip"
            with zipfile.ZipFile(zip_file, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
                zf.writestr("dir/data.txt", "abc")

            transfer_file = root / "payload.transfer"
            transfer_decrypter.create_transfer(zip_file, transfer_file, "pass123")

            output_dir = root / "out"
            success, failures = transfer_decrypter.decrypt_folder(root, output_dir, "pass123", "extract")
            self.assertEqual(success, 1)
            self.assertEqual(failures, [])
            self.assertEqual((output_dir / "payload" / "dir" / "data.txt").read_text(encoding="utf-8"), "abc")


if __name__ == "__main__":
    unittest.main()

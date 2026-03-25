from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
readme = (root / "README.md").read_text(encoding="utf-8")
matrix = (root / "windows" / "PARITY_MATRIX.md").read_text(encoding="utf-8")

match = re.search(r"## Features\n(.*?)(\n## |\Z)", readme, flags=re.S)
if not match:
    raise SystemExit("Could not find Features section in README.md")

features_block = match.group(1)
feature_lines = re.findall(r"^-\s+(.+)$", features_block, flags=re.MULTILINE)
missing = [f for f in feature_lines if f not in matrix]

if missing:
    print("Missing matrix coverage for features:")
    for m in missing:
        print("-", m)
    raise SystemExit(1)

print("Parity matrix covers all README Android features.")

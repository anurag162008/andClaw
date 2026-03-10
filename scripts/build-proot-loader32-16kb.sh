#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEFAULT_OUTPUT="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libproot-loader32.so"

OUTPUT_PATH="${1:-$DEFAULT_OUTPUT}"
PROOT_COMMIT="${2:-4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f}"

if ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: Docker가 필요합니다"
    exit 1
fi

mkdir -p "$(dirname "$OUTPUT_PATH")"

TMP_DIR="$(mktemp -d)"
trap 'docker rm -f "andclaw-loader32-$$" 2>/dev/null || true; chmod -R u+w "$TMP_DIR" 2>/dev/null || true; rm -rf "$TMP_DIR" 2>/dev/null || true' EXIT
mkdir -p "$TMP_DIR/out"

echo "[loader32] Building 16KB-compatible loader32 from termux/proot commit: $PROOT_COMMIT"

docker run --platform linux/arm64 --name "andclaw-loader32-$$" \
    -e "PROOT_COMMIT=$PROOT_COMMIT" \
    ubuntu:24.04 \
    bash -c '
        set -euo pipefail
        export DEBIAN_FRONTEND=noninteractive

        apt-get update -qq
        apt-get install -y -qq --no-install-recommends \
            ca-certificates curl unzip \
            gcc-arm-linux-gnueabihf \
            binutils-arm-linux-gnueabihf \
            binutils

        curl -fsSL "https://github.com/termux/proot/archive/${PROOT_COMMIT}.zip" -o /tmp/proot.zip
        unzip -q /tmp/proot.zip -d /tmp

        SRC_DIR=$(find /tmp -maxdepth 1 -type d -name "proot-*" | head -1)
        if [ -z "$SRC_DIR" ]; then
            echo "ERROR: termux/proot source directory not found"
            exit 1
        fi

        cd "$SRC_DIR/src"
        mkdir -p /tmp/build-loader32

        arm-linux-gnueabihf-gcc \
            -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE \
            -I. -fPIC -ffreestanding -O2 -Wall -Wextra \
            -c loader/loader.c -o /tmp/build-loader32/loader.o

        arm-linux-gnueabihf-gcc \
            -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE \
            -I. -fPIC -ffreestanding -O2 -Wall -Wextra \
            -c loader/assembly.S -o /tmp/build-loader32/assembly.o

        BASE_FLAGS="-static -nostdlib -Wl,-Ttext=0x20000000,-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384"
        if ! arm-linux-gnueabihf-gcc \
            -o /tmp/build-loader32/loader32 \
            /tmp/build-loader32/loader.o /tmp/build-loader32/assembly.o \
            ${BASE_FLAGS},--rosegment; then
            echo "[loader32] linker does not support --rosegment, retry without it"
            arm-linux-gnueabihf-gcc \
                -o /tmp/build-loader32/loader32 \
                /tmp/build-loader32/loader.o /tmp/build-loader32/assembly.o \
                ${BASE_FLAGS}
        fi

        arm-linux-gnueabihf-strip /tmp/build-loader32/loader32

        readelf -W -l /tmp/build-loader32/loader32 | awk "/^[[:space:]]*LOAD[[:space:]]/ { print \$NF }" > /tmp/build-loader32/alignments.txt
        if [ ! -s /tmp/build-loader32/alignments.txt ]; then
            echo "ERROR: LOAD segment alignment 정보를 읽지 못했습니다"
            exit 1
        fi

        if ! awk "\$1 != \"0x4000\" { bad = 1 } END { exit bad }" /tmp/build-loader32/alignments.txt; then
            echo "ERROR: loader32가 16KB 정렬이 아닙니다"
            cat /tmp/build-loader32/alignments.txt
            exit 1
        fi

        cp /tmp/build-loader32/loader32 /tmp/build-loader32/libproot-loader32.so
    '

docker cp "andclaw-loader32-$$":/tmp/build-loader32/libproot-loader32.so "$TMP_DIR/out/libproot-loader32.so"
docker rm -f "andclaw-loader32-$$" 2>/dev/null || true

if [ ! -f "$TMP_DIR/out/libproot-loader32.so" ]; then
    echo "ERROR: loader32 output was not created"
    exit 1
fi

cp "$TMP_DIR/out/libproot-loader32.so" "$OUTPUT_PATH"
chmod +x "$OUTPUT_PATH"

echo "[loader32] Built: $OUTPUT_PATH"
readelf -W -l "$OUTPUT_PATH" | awk '/^[[:space:]]*LOAD[[:space:]]/ { print "  ALIGN=" $NF }'

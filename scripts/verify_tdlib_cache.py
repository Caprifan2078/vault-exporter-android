#!/usr/bin/env python3
"""Integrity and build-recipe guard for reusing an exact pinned TDLib QA binary.

A cache is an optimization, NOT an upstream signature, reproducible-build proof,
or independent supply-chain attestation. First cache miss must build the pinned
official source and pass verify_tdlib_elf.py; every hit is re-hashed and ELF-gated.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import sys

UPSTREAM = "ea3f69ce11aef458bfbb09869b809ede4447486f"
SPEC = "tdlib-json-android-arm64-v8a-api26"
RECIPE = ("scripts/prepare_tdlib_android.py", "scripts/verify_tdlib_elf.py")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def record(library: Path, root: Path) -> dict[str, object]:
    if library.is_symlink() or not library.is_file() or library.stat().st_size < 512_000:
        raise ValueError("Cache binary missing, symlinked or too small")
    return {
        "format": 1,
        "official_tdlib_commit": UPSTREAM,
        "target": SPEC,
        "sha256": sha256(library),
        "bytes": library.stat().st_size,
        "recipe_sha256": {rel: sha256(root / rel) for rel in RECIPE},
    }


def check(library: Path, manifest: Path, root: Path) -> None:
    if manifest.is_symlink() or not manifest.is_file():
        raise ValueError("TDLib provenance metadata missing or symlinked")
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data != record(library, root):
        raise ValueError("TDLib cache hash, pinned commit, target or recipe mismatch")
    print("PASS: pinned TDLib cache manifest and binary bytes match")


def create(library: Path, manifest: Path, root: Path) -> None:
    if manifest.is_symlink():
        raise ValueError("Refuse symlinked metadata destination")
    data = record(library, root)
    manifest.parent.mkdir(parents=True, exist_ok=True)
    # A new immutable GitHub Actions cache key is used on recipe changes.
    manifest.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    check(library, manifest, root)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("create", "check"))
    parser.add_argument("binary", type=Path)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    try:
        if args.action == "create":
            create(args.binary, args.manifest, args.root)
        else:
            check(args.binary, args.manifest, args.root)
    except (ValueError, OSError, UnicodeError, json.JSONDecodeError) as ex:
        raise SystemExit("ABORT: TDLib binary cache rejected: " + str(ex))


if __name__ == "__main__":
    main()

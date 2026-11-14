#!/usr/bin/env python3
"""Offline cache-guard negative fixtures; does NOT assert TDLib source authenticity."""
import importlib.util
import json
import shutil
from pathlib import Path
from tempfile import TemporaryDirectory

HERE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("tdlib_cache_guard", HERE / "scripts/verify_tdlib_cache.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


def rejected(fn, reason):
    try:
        fn()
    except (OSError, ValueError, json.JSONDecodeError):
        return
    raise AssertionError(reason)


with TemporaryDirectory(prefix="vault-tdlib-cache-") as work:
    root = Path(work)
    (root / "scripts").mkdir()
    for name in guard.RECIPE:
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(HERE / name, target)
    cache = root / "native-cache"
    cache.mkdir()
    binary = cache / "libtdjson.so"
    binary.write_bytes(b"SYNTHETIC-BINARY-NEVER-PACKAGE" + b"Z" * 512_000)
    manifest = cache / "manifest.json"

    guard.create(binary, manifest, root)
    guard.check(binary, manifest, root)

    original = binary.read_bytes()
    binary.write_bytes(original[:-1] + b"X")
    rejected(lambda: guard.check(binary, manifest, root),
             "Tampered cached TDLib binary was accepted")
    binary.write_bytes(original)

    meta = manifest.read_text()
    tampered = json.loads(meta)
    tampered["official_tdlib_commit"] = "0" * 40
    manifest.write_text(json.dumps(tampered))
    rejected(lambda: guard.check(binary, manifest, root),
             "Incorrect upstream revision was accepted")
    manifest.write_text(meta)

    tampered = json.loads(meta)
    tampered["target"] = "tdlib-json-android-armeabi-v7a-api26"
    manifest.write_text(json.dumps(tampered))
    rejected(lambda: guard.check(binary, manifest, root),
             "Incorrect ABI target was accepted")
    manifest.write_text(meta)

    script = root / guard.RECIPE[0]
    script.write_bytes(script.read_bytes() + b"# changed build recipe\n")
    rejected(lambda: guard.check(binary, manifest, root),
             "Changed TDLib build recipe reused previous binary")
    shutil.copyfile(HERE / guard.RECIPE[0], script)

    malformed = manifest.read_text()
    manifest.write_text("{broken")
    rejected(lambda: guard.check(binary, manifest, root),
             "Malformed cache metadata accepted")
    manifest.write_text(malformed)

    manifest.unlink()
    manifest.symlink_to(root / "scripts")
    rejected(lambda: guard.check(binary, manifest, root),
             "Symlinked cache provenance accepted")
    manifest.unlink()
    manifest.write_text(malformed)

    binary.unlink()
    binary.symlink_to(root / "scripts")
    rejected(lambda: guard.check(binary, manifest, root),
             "Symlinked native binary accepted")

print("PASS: pinned TDLib cache guards reject binary tampering, wrong source/ABI, recipe drift and symlinks")

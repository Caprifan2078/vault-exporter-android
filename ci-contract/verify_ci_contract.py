#!/usr/bin/env python3
"""Sanity-check offline release invariants. This does NOT verify a real binary build."""
from pathlib import Path
import re
root=Path(__file__).resolve().parent.parent
build=(root/'.github/workflows/build-debug-apk.yml').read_text()
sha=re.search(r'^\s*TDLIB_SHA=([0-9a-f]{40})$',build,re.M)
assert sha, 'Missing explicit 40-digit upstream TDLib SHA'
assert 'git -C "$RUNNER_TEMP/td" rev-parse HEAD' in build, 'No source pin check'
assert '--build-arg "COMMIT_HASH=$TDLIB_SHA"' in build, 'Docker still builds moving master'
assert not re.search(r'^    env:',build,re.M), 'Telegram API credentials must not be job-wide secrets'
assert build.count('secrets.TELEGRAM_API_ID') == 2 and build.count('secrets.TELEGRAM_API_HASH') == 2, 'Credentials must be scoped only to validation/build steps'
assert '--build-arg TDLIB_INTERFACE=JSON' in build, 'Must build JSON client'
assert 'scripts/verify_tdlib_elf.py' in build, 'Missing strict ELF JSON ABI audit'
assert 'scripts/prepare_tdlib_android.py' in build, 'Missing arm64-only pinned Docker build recipe'
assert 'tdlib-abi.json' in build, 'Missing structured TDLib ABI attestation'
assert 'tdlib-sha256.txt' in build and 'build-provenance.txt' in build, 'Missing reproducible native build evidence'
assert 'unzip -Z1 "$APK" | grep -Fxq' in build, 'Missing exact APK entry validation'
assert 'bash RUN_ALL_OFFLINE.sh' in build, 'Build workflow missing complete offline suite'
assert 'bash native-host-test/RUN_NATIVE_HOST_TEST.sh' in (root/'RUN_ALL_OFFLINE.sh').read_text(), 'Native JNI smoke not part of CI'
assert 'verifyExporterBuild' in (root/'app/build.gradle').read_text(), 'Guard missing'
assert 'test' in (root/'app/build.gradle').read_text(), 'Test DC flag missing'
print('PASS CI build contract: pinned TDLib',sha.group(1),'and complete offline suite')

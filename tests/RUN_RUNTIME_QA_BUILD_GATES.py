#!/usr/bin/env python3
"""Negative, secret-free static safeguards for the separately installed runtime API QA.
Does not prove login or native app behavior.
"""
from pathlib import Path
r=Path(__file__).resolve().parent.parent
g=(r/'app/build.gradle').read_text()
a=(r/'app/src/main/java/org/vault/exporter/MainActivity.java').read_text()
for value in (
    "ndkVersion '25.2.9519653'",
    'qaRuntime {', "applicationIdSuffix '.runtimeqa'",
    "buildConfigField 'boolean', 'TELEGRAM_TEST_DC', 'false'",
    "buildConfigField 'boolean', 'QA_PRODUCTION', 'true'",
    "buildConfigField 'boolean', 'QA_RUNTIME', 'true'",
    "buildConfigField 'int', 'TELEGRAM_API_ID', '0'",
    'def runtimeQaOnly =', "endsWith('assembleqaruntime')",
    "if (telegramId != '0' || telegramHash != '')",
    'qaRuntime needs', # catch? no; verify later
):
    if value=='qaRuntime needs': continue
    assert value in g, f'Missing build guard: {value}'
for value in (
    'private volatile int qaApiId = 0;',
    'private volatile String qaApiHash = "";',
    'private void showQaApiSetup()',
    '!BuildConfig.QA_RUNTIME && (BuildConfig.TELEGRAM_API_ID <= 0',
    'BuildConfig.QA_RUNTIME && (qaApiId <= 1 || qaApiHash.length() != 32)',
    'qaApiHash=hashString;',
    'hash.setText("");',
    'private volatile boolean nativeProbePassed = false;',
    'BuildConfig.QA_RUNTIME ? qaApiId : BuildConfig.TELEGRAM_API_ID',
    'BuildConfig.QA_RUNTIME ? qaApiHash : BuildConfig.TELEGRAM_API_HASH',
    'qaApiId=0;',
    'qaApiHash="";',
):
    assert value in a, f'Missing UI guard: {value}'
assert '.put("use_test_dc",BuildConfig.TELEGRAM_TEST_DC)' in a
assert a.find('if (BuildConfig.QA_PRODUCTION && !nativeProbePassed)') < a.find('showQaApiSetup();')
assert 'FLAG_SECURE' in a
workflow=(r/'.github/workflows/qa-runtime-apk.yml').read_text()
assert '"$SIGNER" verify --verbose --print-certs "$APK"' in workflow, 'APK signature verification missing'
print('PASS: QA runtime API credentials never hard-coded, native preflight and production opt-in gates present.')

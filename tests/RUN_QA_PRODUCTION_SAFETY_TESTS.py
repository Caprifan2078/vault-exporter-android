#!/usr/bin/env python3
"""Static/negative QA build gates only. Not Android compilation or live Telegram test."""
from pathlib import Path
root=Path(__file__).resolve().parent.parent
gradle=(root/'app/build.gradle').read_text()
activity=(root/'app/src/main/java/org/vault/exporter/MainActivity.java').read_text()
workflow=(root/'.github/workflows/disposable-real-account-qa.yml').read_text()
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
for fragment in (
    "isQaProductionBuild && (telegramEnvironment != 'production' || !productionApproved)",
    "qaProduction {", "signingConfig signingConfigs.debug", "debuggable false",
    "applicationIdSuffix '.qa'", "versionNameSuffix '-qa-not-release'",
    "buildConfigField 'boolean', 'TELEGRAM_TEST_DC', 'false'",
    "buildConfigField 'boolean', 'ALLOW_PRODUCTION', 'true'",
    "buildConfigField 'boolean', 'QA_PRODUCTION', 'true'",
    "telegramId == '1'", "telegramHash == '00000000000000000000000000000000'",
):
    assert fragment in gradle, f'Missing build guard: {fragment}'
assert 'if (BuildConfig.QA_PRODUCTION)' in activity
assert 'ТЕСТОВАЯ СБОРКА' in activity
assert 'Сначала проверить TDLib без аккаунта' in activity
assert 'BuildConfig.QA_PRODUCTION || (BuildConfig.DEBUG && BuildConfig.TELEGRAM_TEST_DC)' in activity
assert 'if (!BuildConfig.TELEGRAM_TEST_DC && !BuildConfig.ALLOW_PRODUCTION)' in activity
assert 'if (BuildConfig.DEBUG)' in activity
assert 'if(BuildConfig.TELEGRAM_TEST_DC)' in activity
assert '.put("use_test_dc",BuildConfig.TELEGRAM_TEST_DC)' in activity
assert 'authorizationStateWaitPhoneNumber' in activity
assert 'authorizationStateWaitCode' in activity
assert 'authorizationStateWaitPassword' in activity
assert 'authorizationStateWaitEmailCode' in activity
assert 'android.permission.INTERNET' in manifest
assert 'workflow_dispatch:' in workflow
assert '  push:' not in workflow
assert 'approve_disposable_test_account == true' in workflow
assert 'secrets.TELEGRAM_API_ID' in workflow
assert 'secrets.TELEGRAM_API_HASH' in workflow
assert 'python3 scripts/prepare_tdlib_android.py' in workflow
assert ':app:assembleQaProduction' in workflow
assert "name='org.vault.exporter.qa'" in workflow
assert 'retention-days: 1' in workflow
print('PASS: manual-only disposable-account production QA gates (STATIC, no APK or live login).')

#!/usr/bin/env bash
# First-runtime smoke test for a zero-network-permission SYNTHETIC demo.
# Never use a Telegram account, API key, chat export or human-entered password.
set -euo pipefail
SDK="$ANDROID_HOME"
APK="srcapp/app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
"$SDKMANAGER" --sdk_root="$SDK" 'platform-tools' 'emulator' 'system-images;android-33;google_apis;x86_64'
export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
printf 'no\n' | "$AVDMANAGER" create avd -n vault_ci_android33 -k 'system-images;android-33;google_apis;x86_64' --force
# A clean isolated emulator session; do not attach any real device or accounts.
"$SDK/emulator/emulator" -avd vault_ci_android33 -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -camera-back none -camera-front none > /tmp/vault-emulator.log 2>&1 &
EMULATOR_PID=$!
cleanup(){
  adb emu kill >/dev/null 2>&1 || true
  kill "$EMULATOR_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT
adb wait-for-device
READY=0
for i in $(seq 1 150); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then READY=1; break; fi
  sleep 2
done
if [ "$READY" != 1 ]; then
  tail -100 /tmp/vault-emulator.log
  echo "Emulator boot timeout"; exit 1
fi
adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb install -r "$APK"
adb shell pm list packages | grep -F 'package:org.vault.exporter.offlinedemo'
adb logcat -c
for iteration in 1 2; do
  adb shell am force-stop org.vault.exporter.offlinedemo || true
  adb shell am start -W -n org.vault.exporter.offlinedemo/org.vault.exporter.MainActivity
  sleep 3
  adb shell uiautomator dump /sdcard/vault_ui.xml >/tmp/vault-uiautomator.txt
  adb shell cat /sdcard/vault_ui.xml | grep -F 'Vault Exporter'
  adb shell dumpsys activity activities | grep -F 'org.vault.exporter.offlinedemo'
done
if adb logcat -d -s AndroidRuntime:E | grep -F 'FATAL EXCEPTION'; then
  echo 'Fatal Android exception during startup'; exit 1
fi
# App install must not request INTERNET or privileged data permissions.
"$SDK/build-tools/35.0.0/aapt" dump permissions "$APK" | tee /tmp/vault-emulator-permissions.txt
if grep -E 'android.permission.(INTERNET|READ_CONTACTS|READ_SMS|READ_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE)' /tmp/vault-emulator-permissions.txt; then
  echo 'Forbidden Android permission'; exit 1
fi
echo 'PASS: Android 13 emulator installed APK; launched twice; visible UI, no fatal crash, no sensitive permissions.'

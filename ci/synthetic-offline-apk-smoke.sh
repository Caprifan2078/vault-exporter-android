#!/usr/bin/env bash
set -euo pipefail
APP_ID=org.vault.exporter.sandbox.testnet
APK="$APK_UNDER_TEST"
test -s "$APK"
mkdir -p /tmp/vault-mock
mkdir -p "$GITHUB_WORKSPACE/mock-debug"
debug_on_exit() {
  local code=$?
  if test "$code" -ne 0; then
    echo "Capturing emulator UI and app-only log errors after failed synthetic export"
    adb shell uiautomator dump /sdcard/mock-fail.xml >/dev/null 2>&1 || true
    adb exec-out cat /sdcard/mock-fail.xml > "$GITHUB_WORKSPACE/mock-debug/window.xml" 2>/dev/null || true
    adb exec-out screencap -p > "$GITHUB_WORKSPACE/mock-debug/screen.png" 2>/dev/null || true
    adb logcat -d -s AndroidRuntime:E '*:S' > "$GITHUB_WORKSPACE/mock-debug/android-fatal.txt" 2>/dev/null || true
    adb shell ls -la /sdcard/Download > "$GITHUB_WORKSPACE/mock-debug/downloads.txt" 2>/dev/null || true
  fi
}
trap debug_on_exit EXIT
adb shell svc wifi disable || true
adb shell svc data disable || true
adb install -r "$APK"
adb shell am force-stop "$APP_ID" || true
adb shell am start -W -n "$APP_ID/org.vault.exporter.MainActivity"
sleep 5
test -n "$(adb shell pidof "$APP_ID" | tr -d '\r')" || { echo "App crashed"; exit 40; }
capture() {
  for attempt in 1 2 3 4; do
    if adb shell uiautomator dump /sdcard/mock-ui.xml >/dev/null 2>&1 &&
      adb exec-out cat /sdcard/mock-ui.xml > /tmp/vault-mock/window.xml; then return 0; fi
    sleep 2
  done
  return 1
}
find_button() {
  local phrase="$1"
  python3 - "$phrase" /tmp/vault-mock/window.xml <<'PY'
import sys, re, xml.etree.ElementTree as E
needle=sys.argv[1].lower(); root=E.parse(sys.argv[2]).getroot()
for node in root.iter('node'):
    text=((node.get('text') or '')+' '+(node.get('content-desc') or '')).lower()
    if needle not in text:continue
    m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
    if m:
        a,b,c,d=map(int,m.groups())
        if c>a and d>b:
            print((a+c)//2,(b+d)//2)
            sys.exit(0)
sys.exit(1)
PY
}
dismiss_launcher_anr() {
  local xy
  xy="$(python3 - /tmp/vault-mock/window.xml <<'PY'
import sys,re,xml.etree.ElementTree as E
root=E.parse(sys.argv[1]).getroot()
nodes=list(root.iter("node"))
if not any("Pixel Launcher" in n.get("text","") and "respond" in n.get("text","").lower() for n in nodes):
    sys.exit(1)
for node in nodes:
    if node.get("resource-id")!="android:id/aerr_close":continue
    m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get("bounds",""))
    if m:
        a,b,c,d=map(int,m.groups());print((a+c)//2,(b+d)//2);sys.exit(0)
sys.exit(1)
PY
)" || return 1
  read -r x y <<<"$xy"
  echo "Dismissing Pixel Launcher ANR, not the app under test"
  adb shell input tap "$x" "$y"
  sleep 2
}
tap_found() {
  local label="$1" xy
  for attempt in 1 2 3 4 5 6 7 8; do
    capture || true
    if dismiss_launcher_anr; then continue; fi
    if xy="$(find_button "$label")"; then
      read -r x y <<<"$xy"
      adb shell input tap "$x" "$y"
      sleep 2
      return 0
    fi
    adb shell input swipe 450 1450 450 520 250
    sleep 1
  done
  echo "FAIL: UI button '$label' absent";cat /tmp/vault-mock/window.xml
  return 1
}
capture
tap_found "Попробовать без аккаунта"
tap_found "Скачать учебный чат"
capture
if ! find_button "save" >/dev/null 2>&1 && ! find_button "сохранить" >/dev/null 2>&1; then
  if find_button "Downloads" >/dev/null 2>&1; then tap_found "Downloads"
  elif find_button "Загрузки" >/dev/null 2>&1; then tap_found "Загрузки"
  else
    adb shell input tap 46 93; sleep 2; capture
    if find_button "Downloads" >/dev/null 2>&1; then tap_found "Downloads"
    elif find_button "Загрузки" >/dev/null 2>&1; then tap_found "Загрузки"
    fi
  fi
fi
if find_button "save" >/dev/null 2>&1; then tap_found "save"
elif find_button "сохранить" >/dev/null 2>&1; then tap_found "сохранить"
else echo "No system document save action";cat /tmp/vault-mock/window.xml;exit 43;fi
# On Android /sdcard may be a symlink; 'find' does not follow it by default.
# Read the file straight from the Downloads path chosen by DocumentsUI.
FILE="/sdcard/Download/Vault_УЧЕБНАЯ_ПЕРЕПИСКА.zip"
for attempt in $(seq 1 30);do
  if adb shell test -s "$FILE";then
    if adb exec-out cat "$FILE" > /tmp/vault-mock/out.zip &&
      unzip -t /tmp/vault-mock/out.zip >/dev/null 2>&1;then break;fi
  fi
  sleep 2
done
test -s /tmp/vault-mock/out.zip || { echo "No completed exported ZIP";exit 44; }
python3 - <<'PY'
import zipfile,json
p='/tmp/vault-mock/out.zip'
with zipfile.ZipFile(p) as z:
 names=z.namelist()
 assert 'messages.html' in names
 assert 'README_TEST_ONLY.txt' in names
 manifest=json.loads(z.read('manifest.json'))
 assert manifest['source']=='FICTIONAL LOCAL TEST (NOT TELEGRAM)'
 messages=[]
 for name in names:
  if name.startswith('messages/') and name.endswith('.jsonl'):
   messages += [json.loads(line) for line in z.read(name).decode().splitlines() if line]
 media=[x for x in names if x.startswith('media/')]
 assert len(messages)==60, len(messages)
 assert len(media)==7, len(media)
 print('PASS: emulator created fictional ZIP with 60 messages and 7 media, no Telegram network')
PY
adb shell am force-stop "$APP_ID"
echo "PASS: synthetic Android export worked offline"

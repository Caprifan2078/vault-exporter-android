#!/usr/bin/env python3
"""Offline Android UI probe. Never enters credentials or connects to Telegram."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=35).decode("utf-8", "replace")

def screen():
    text = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    start, end = text.find("<?xml"), text.rfind("</hierarchy>")
    if start < 0 or end < 0:
        raise RuntimeError("UI hierarchy unavailable")
    return ET.fromstring(text[start:end + len("</hierarchy>")])

def dismiss_launcher_anr(root):
    nodes = list(root.iter("node"))
    if not any("Pixel Launcher" in n.get("text","") and "respond" in n.get("text","").lower() for n in nodes):
        return False
    close = next((n for n in nodes if n.get("resource-id") == "android:id/aerr_close"), None)
    if close is None:
        raise RuntimeError("Pixel Launcher ANR cannot be dismissed")
    coords = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", close.get("bounds", ""))
    if not coords:
        raise RuntimeError("Pixel Launcher ANR has invalid bounds")
    x1, y1, x2, y2 = map(int, coords.groups())
    print("Dismissing Pixel Launcher ANR, not the app under test")
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1)
    return True

def probe():
    node = None
    for attempt in range(12):
        root = screen()
        if dismiss_launcher_anr(root):
            continue
        node = next((n for n in root.iter("node") if "Сначала проверить TDLib без аккаунта" in n.get("text", "")), None)
        if node is not None:
            break
        time.sleep(1)
        adb("shell", "input", "swipe", "500", "1550", "500", "400", "400")
    if node is None:
        print("Visible text:", [n.get("text", "") for n in root.iter("node") if n.get("text", "")])
        raise RuntimeError("Offline TDLib diagnostic button not found")
    coords = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not coords:
        raise RuntimeError("Diagnostic button has invalid bounds")
    x1, y1, x2, y2 = map(int, coords.groups())
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        time.sleep(1)
        root = screen()
        if dismiss_launcher_anr(root):
            continue
        texts = [n.get("text", "") for n in root.iter("node")]
        # The background status can still contain PASS from the first run.
        # Require the current diagnostic dialog's success text instead.
        if any("Готово! Движок TDLib отвечает." in value and "TDLIB_OFFLINE_PROBE_PASS" in value for value in texts):
            return
        if any("TDLIB_OFFLINE_PROBE_FAIL" in value for value in texts):
            raise RuntimeError("TDLib offline diagnostic reported failure")
    raise RuntimeError("TDLib offline diagnostic timed out")

probe()
adb("shell", "input", "keyevent", "KEYCODE_BACK")
time.sleep(0.5)
probe()
print("PASS: two real TDLib offline roundtrips, close and reopen, no account")

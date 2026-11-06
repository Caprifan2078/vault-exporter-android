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

def probe():
    node = None
    for attempt in range(12):
        root = screen()
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
        texts = [n.get("text", "") for n in screen().iter("node")]
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

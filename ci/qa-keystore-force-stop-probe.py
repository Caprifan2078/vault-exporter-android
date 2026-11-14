#!/usr/bin/env python3
"""Real Android Keystore + encrypted resume crash recovery; fictional data only.

Requires the isolated QA runtime APK and its matching androidTest APK already
installed on a clean emulator. No Telegram credentials or network requests.
"""
import subprocess
import time

PACKAGE = "org.vault.exporter.runtimeqa"
COMPONENT = "org.vault.exporter.runtimeqa.test/org.vault.exporter.QaRecoveryInstrumentation"


def adb(*args, timeout=90):
    return subprocess.check_output(
        ["adb", *args], timeout=timeout, stderr=subprocess.STDOUT
    ).decode("utf-8", "replace")


def process_pid():
    # pidof exits with code 1 when the stopped app has no process.
    try:
        return adb("shell", "pidof", PACKAGE).strip()
    except subprocess.CalledProcessError:
        return ""


def phase(name):
    result = adb("shell", "am", "instrument", "-w", "-r",
                 "-e", "phase", name, COMPONENT, timeout=110)
    if ("vault_qa_status=PASS" not in result
            or f"vault_qa_phase={name}" not in result
            or "INSTRUMENTATION_CODE: -1" not in result):
        raise RuntimeError(f"Android Keystore {name} instrumentation FAILED:\n{result}")
    print(f"PASS: actual Android Keystore instrumentation {name} phase")


# Never run with network access or a personal Telegram session.
adb("shell", "svc", "wifi", "disable")
adb("shell", "svc", "data", "disable")
adb("shell", "am", "force-stop", PACKAGE)
phase("prepare")

# A running app process is killed after encrypted pages/media and a synthetic
# plaintext TDLib cache file were written. Neither Java finally nor onDestroy
# participates in the test; the verification uses a fresh instrumentation run.
launch = adb("shell", "am", "start", "-W", "-n",
             PACKAGE + "/org.vault.exporter.MainActivity")
if "Error" in launch or "Exception" in launch:
    raise RuntimeError(f"Android QA app relaunch failed:\n{launch}")
pid = process_pid()
if not pid:
    raise RuntimeError("QA app was not alive before force-stop")
adb("shell", "am", "force-stop", PACKAGE)
time.sleep(1)
if process_pid():
    raise RuntimeError("Android process remained alive after force-stop")
phase("verify")
print("PASS: hardware-backed-or-software-emulated AndroidKeyStore recovery, "
      "encrypted checkpoint and media, orphan cleanup and TDLib native restart; "
      "real emulator, zero Telegram accounts")

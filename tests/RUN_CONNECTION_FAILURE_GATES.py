#!/usr/bin/env python3
"""Source-level checks for clean TDLib init failure and one-time QA credentials.

Static contracts supplement JVM transport and Android compile checks;
they are not an end-to-end Telegram login test.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
activity = (root / "app/src/main/java/org/vault/exporter/MainActivity.java").read_text()
gateway = (root / "app/src/main/java/org/vault/exporter/TdGateway.java").read_text()


def between(text, first, last):
    start = text.index(first)
    return text[start:text.index(last, start + len(first))]


failure = between(activity, "private void abandonFailedConnection(", "private int dp(")
startup = between(activity, "private void beginConnect()", "private void abandonFailedConnection(")
td_params = between(activity, 'if ("authorizationStateWaitTdlibParameters".equals(name))', 'runOnUiThread(() -> {')

assert "TdCacheStartupCleaner.clean(getFilesDir());" in startup, "Missing pre-TDLib crash-cache scrub"
assert startup.index("TdCacheStartupCleaner.clean(getFilesDir());") < startup.index("td = new TdGateway("), "Cleanup must precede any native TDLib client"
assert "abandonFailedConnection(" in startup, "Failed initial getAuthorizationState must release client"
assert "abandonFailedConnection(" in td_params, "Failed setTdlibParameters must release client"
for token in (
    "TdGateway abandoned=td;",
    "td=null;",
    "initializing.set(false);",
    "qaApiId=0;",
    'qaApiHash="";',
    "abandoned.stop();",
    "button(\"Вернуться к безопасному началу\",this::showWelcome)",
):
    assert token in failure, f"Missing clean-init rollback: {token}"
assert "if(BuildConfig.QA_RUNTIME) {qaApiId=0;qaApiHash=\"\";}" in td_params
assert '"Не удалось настроить TDLib: " + e.getMessage()' not in td_params, "Don't display raw auth errors"

call = between(gateway, "public CompletableFuture<JSONObject> call(", "public JSONObject await(")
stop = between(gateway, "public void stop()", "\n}")
assert "synchronized(sendLock)" in call and "synchronized(sendLock)" in stop
assert "closing=true;" in stop
assert stop.index("closing=true;") < stop.index('TdNative.send(client,')
assert "if (closeConfirmed) TdNative.releaseReceiver();" in gateway
assert 'if(ex instanceof InterruptedException) Thread.currentThread().interrupt();' in gateway

print("PASS: clean TDLib startup rollback, one-time API credentials, serialized shutdown, interrupt propagation")

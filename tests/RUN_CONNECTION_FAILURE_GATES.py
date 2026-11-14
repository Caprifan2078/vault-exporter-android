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

assert "td = new TdGateway(this::onTdEvent,getFilesDir());" in startup, "Cache-cleaning TDLib gateway must be used"
assert gateway.index("TdNative.acquireReceiver()") < gateway.index("TdCacheStartupCleaner.clean(appPrivateFilesDir);") < gateway.index("client = TdNative.createClient();"), "Cache scrub must run only under receiver reservation before native client creation"
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

# An acknowledgement of logOut is not the terminal authorization-state update.
logout = between(activity, 'private void confirmLogout()', '@Override protected void onDestroy()')
assert 'JSONObject logoutAck=td.await(TdGateway.cmd("logOut"),120);' in logout
assert 'td.awaitLogoutClosed(120);' in logout
assert 'TdCacheStartupCleaner.clean(privateStorage);' in logout, 'Plaintext cache must be scrubbed after logout'
assert 'PrivateDataEraser.eraseProgress(privateStorage);' in logout, 'Fail-closed progress deletion required'
assert logout.index('telegramLogoutConfirmed=true;') < logout.index('TdCacheStartupCleaner.clean(privateStorage);') < logout.index('PrivateDataEraser.eraseProgress(privateStorage);')
assert logout.index('td.awaitLogoutClosed(120);') < logout.index('telegramLogoutConfirmed=true;')
assert logout.index('telegramLogoutConfirmed=true;') < logout.index('new SessionSecrets(this).discard();')
assert 'if (logoutObserved) logoutClosed.complete(null);' in gateway
assert '"authorizationStateLoggingOut".equals(state)' in gateway
assert 'logoutClosed.completeExceptionally' in gateway, "A plain close must not count as logout"

print("PASS: clean TDLib startup rollback, credentials, serialized shutdown, interrupt and confirmed logout lifecycle")

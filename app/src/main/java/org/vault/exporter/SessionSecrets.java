package org.vault.exporter;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Wraps TDLib's database secret with a nonexportable Android Keystore key.
 * The production adapter uses the same serialized, fail-closed lifecycle
 * exercised by pure JVM tests, without persisting plaintext secrets.
 */
public final class SessionSecrets {
  private static final String ALIAS = "tg_vault_database_wrap_v1";
  private static final String PREF = "tdlib_session";
  private final Context ctx;

  public SessionSecrets(Context context) { ctx = context.getApplicationContext(); }

  private static final class AndroidStore implements SessionKeyLifecycle.Storage {
    private final SharedPreferences prefs;
    AndroidStore(Context context) {
      prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    @Override public String wrapped() { return prefs.getString("wrapped", null); }
    @Override public String iv() { return prefs.getString("iv", null); }

    @Override public SecretKey key(boolean allowCreate) throws Exception {
      KeyStore store = KeyStore.getInstance("AndroidKeyStore");
      store.load(null);
      if (!store.containsAlias(ALIAS)) {
        if (!allowCreate) return null; // No silent replacement of a lost wrapping key.
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        gen.generateKey();
      }
      return (SecretKey) store.getKey(ALIAS, null);
    }
    @Override public boolean save(String iv, String wrapped) {
      // SharedPreferences commits both fields atomically, never one at a time.
      return prefs.edit().putString("iv", iv).putString("wrapped", wrapped).commit();
    }
    @Override public boolean clear() {
      return prefs.edit().clear().commit();
    }
    @Override public void deleteKey() throws Exception {
      KeyStore store = KeyStore.getInstance("AndroidKeyStore");
      store.load(null);
      if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
    }
  }

  public byte[] getOrCreate() throws Exception {
    return SessionKeyLifecycle.getOrCreate(new AndroidStore(ctx));
  }

  public void discard() {
    try { SessionKeyLifecycle.discard(new AndroidStore(ctx)); }
    catch (Exception e) {
      throw new IllegalStateException("Не удалось удалить ключ сеанса", e);
    }
  }
}

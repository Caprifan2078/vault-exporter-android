package org.vault.exporter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The in-process transaction for the TDLib database encryption secret.
 * Single-process Android app: every SessionSecrets instance uses this SAME monitor.
 * Platform persistence and non-exportable Keystore operations are injected for
 * credential-free, concurrent JVM regression tests.
 */
final class SessionKeyLifecycle {
  private static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final Object PROCESS_LOCK = new Object();

  interface Storage {
    String wrapped();
    String iv();
    SecretKey key(boolean allowCreate) throws Exception;
    boolean save(String iv, String wrapped);
    boolean clear();
    void deleteKey() throws Exception;
  }

  private SessionKeyLifecycle() {}

  static byte[] getOrCreate(Storage store) throws Exception {
    synchronized (PROCESS_LOCK) {
      String wrapped = store.wrapped();
      String savedIv = store.iv();
      if (wrapped != null || savedIv != null) {
        // A partial or invalid record must never be mistaken for "first use".
        if (wrapped == null || savedIv == null)
          throw new IOException("Incomplete encrypted session secret");
        byte[] encrypted, iv;
        try {
          encrypted = Base64.getDecoder().decode(wrapped);
          iv = Base64.getDecoder().decode(savedIv);
        } catch (IllegalArgumentException malformed) {
          throw new IOException("Invalid encoded session secret", malformed);
        }
        if (iv.length != IV_BYTES || encrypted.length != KEY_BYTES + 16)
          throw new IOException("Invalid encrypted session secret length");
        // Never silently create a new Keystore key when an old wrapped key exists.
        SecretKey key = store.key(false);
        if (key == null) throw new IOException("Session Keystore key missing");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] secret = cipher.doFinal(encrypted);
        if (secret.length != KEY_BYTES) {
          Arrays.fill(secret, (byte) 0);
          throw new IOException("Invalid decrypted session secret length");
        }
        return secret;
      }

      byte[] secret = new byte[KEY_BYTES];
      new SecureRandom().nextBytes(secret);
      try {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, store.key(true));
        byte[] encrypted = cipher.doFinal(secret);
        String iv = Base64.getEncoder().encodeToString(cipher.getIV());
        String wrappedValue = Base64.getEncoder().encodeToString(encrypted);
        if (!store.save(iv, wrappedValue))
          throw new IOException("Unable to persist wrapped session secret");
        return secret;
      } catch (Exception | Error failure) {
        // Never return usable ephemeral credentials if durable persistence failed.
        Arrays.fill(secret, (byte) 0);
        throw failure;
      }
    }
  }

  static void discard(Storage store) throws Exception {
    synchronized (PROCESS_LOCK) {
      if (!store.clear()) throw new IOException("Unable to clear wrapped session secret");
      store.deleteKey();
    }
  }
}

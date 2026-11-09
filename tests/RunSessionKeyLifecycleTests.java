package org.vault.exporter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Credential-free tests of the SAME key lifecycle used by Android SessionSecrets. */
public final class RunSessionKeyLifecycleTests {
  private static int checks;
  private static void ok(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }
  private interface Action { void run() throws Exception; }
  private static void reject(Action action, String message) throws Exception {
    boolean failed = false;
    try { action.run(); } catch (Exception expected) { failed = true; }
    ok(failed, message);
  }

  private static final class Data {
    String wrapped, iv;
    SecretKey key;
    int saves, generatedKeys, clears, deletedKeys;
    boolean rejectSave, rejectClear;
  }
  private static final class Store implements SessionKeyLifecycle.Storage {
    final Data data;
    Store(Data data) { this.data = data; }
    @Override public String wrapped() { return data.wrapped; }
    @Override public String iv() { return data.iv; }
    @Override public SecretKey key(boolean create) {
      if (data.key == null && create) {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        data.key = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);
        data.generatedKeys++;
      }
      return data.key;
    }
    @Override public boolean save(String iv, String wrapped) {
      if (data.rejectSave) return false;
      data.iv = iv; data.wrapped = wrapped; data.saves++;
      return true;
    }
    @Override public boolean clear() {
      if (data.rejectClear) return false;
      data.wrapped = null; data.iv = null; data.clears++;
      return true;
    }
    @Override public void deleteKey() {
      if (data.key != null) data.deletedKeys++;
      data.key = null;
    }
  }

  public static void main(String[] args) throws Exception {
    Data data = new Data();
    byte[] first = SessionKeyLifecycle.getOrCreate(new Store(data));
    ok(first.length == 32, "Fresh session key is not 256-bit");
    ok(data.saves == 1 && data.generatedKeys == 1, "Fresh state not persisted once");
    byte[] restored = SessionKeyLifecycle.getOrCreate(new Store(data));
    ok(Arrays.equals(first, restored), "Restart silently rotated session key");
    ok(data.saves == 1, "Existing session key was overwritten");

    // Two Activity instances or separate workers race through different adapter instances.
    ExecutorService executor = Executors.newFixedThreadPool(12);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<byte[]>> futures = new ArrayList<>();
    for (int i = 0; i < 48; i++) {
      futures.add(executor.submit((Callable<byte[]>) () -> {
        start.await();
        return SessionKeyLifecycle.getOrCreate(new Store(data));
      }));
    }
    start.countDown();
    for (Future<byte[]> future : futures) {
      byte[] key = future.get();
      ok(Arrays.equals(first, key), "Concurrent callers received different session keys");
      Arrays.fill(key, (byte) 0);
    }
    executor.shutdownNow();
    ok(data.saves == 1 && data.generatedKeys == 1, "Concurrent calls regenerated key");

    String validIv = data.iv, validWrapped = data.wrapped;
    data.iv = null;
    reject(() -> SessionKeyLifecycle.getOrCreate(new Store(data)),
        "Partial encrypted key record silently recreated");
    data.iv = "not valid base64!";
    reject(() -> SessionKeyLifecycle.getOrCreate(new Store(data)),
        "Invalid session key IV silently recreated");
    data.iv = validIv;
    data.wrapped = "oops";
    reject(() -> SessionKeyLifecycle.getOrCreate(new Store(data)),
        "Invalid wrapped ciphertext silently recreated");
    data.wrapped = validWrapped;
    SecretKey oldKey = data.key;
    data.key = null;
    int originalGeneration = data.generatedKeys;
    reject(() -> SessionKeyLifecycle.getOrCreate(new Store(data)),
        "Missing Android Keystore key silently replaced");
    ok(data.generatedKeys == originalGeneration,
        "Existing wrapped key caused unauthorized Keystore generation");
    data.key = oldKey;
    ok(Arrays.equals(first, SessionKeyLifecycle.getOrCreate(new Store(data))),
        "Valid key not recoverable after rejecting bad storage");

    data.rejectClear = true;
    reject(() -> SessionKeyLifecycle.discard(new Store(data)),
        "Failed preference cleanup accepted as successful logout");
    ok(data.key != null && data.wrapped != null,
        "Failed preference cleanup erased the only remaining key");
    data.rejectClear = false;
    SessionKeyLifecycle.discard(new Store(data));
    ok(data.wrapped == null && data.iv == null && data.key == null,
        "Logout retained encrypted key or Keystore alias");
    ok(data.clears == 1 && data.deletedKeys == 1,
        "Logout cleanup not committed exactly once");
    byte[] next = SessionKeyLifecycle.getOrCreate(new Store(data));
    ok(!Arrays.equals(first, next), "New session reused old encryption secret");

    Data failed = new Data();
    failed.rejectSave = true;
    reject(() -> SessionKeyLifecycle.getOrCreate(new Store(failed)),
        "Unpersisted ephemeral session secret was returned");
    ok(failed.wrapped == null && failed.iv == null,
        "Failed write produced a partially saved secret");
    failed.rejectSave = false;
    byte[] afterFailure = SessionKeyLifecycle.getOrCreate(new Store(failed));
    ok(afterFailure.length == 32 && failed.saves == 1,
        "Retry could not recover after failed initial preference commit");
    Arrays.fill(afterFailure, (byte) 0);
    Arrays.fill(restored, (byte) 0);
    Arrays.fill(first, (byte) 0);
    Arrays.fill(next, (byte) 0);
    System.out.println("PASS " + checks + " encrypted session-key lifecycle checks: concurrent callers, corruption, lost alias, logout, persistence");
  }
}

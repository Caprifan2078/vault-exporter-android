package org.vault.exporter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Credential-free crash-leftover tests; no TDLib client or Telegram traffic. */
public final class RunTdCacheStartupCleanerTests {
  private static int checks;
  private static void ok(boolean actual, String msg) {
    checks++;
    if (!actual) throw new AssertionError(msg);
  }
  private interface Action { void run() throws IOException; }
  private static void reject(Action action, String msg) {
    boolean failed=false;
    try { action.run(); } catch(IOException expected) { failed=true; }
    ok(failed,msg);
  }
  private static void put(Path file, String data) throws Exception {
    Files.createDirectories(file.getParent());
    Files.write(file,data.getBytes(StandardCharsets.UTF_8));
  }
  public static void main(String[] args) throws Exception {
    Path app=Files.createTempDirectory("vault-tdlib-cache-test-");
    Path database=app.resolve("tdlib/database/account.bin");
    Path checkpoint=app.resolve("export-progress/chat-777/checkpoint.enc");
    Path nested=app.resolve("tdlib/files/a/b/private-photo.jpg");
    Path voice=app.resolve("tdlib/files/voice.ogg");
    put(database,"encrypted TDLib database remains untouched");
    put(checkpoint,"encrypted staged checkpoint remains untouched");
    put(nested,"synthetic plaintext photo");
    put(voice,"synthetic plaintext voice note");
    byte[] db=Files.readAllBytes(database);
    byte[] saved=Files.readAllBytes(checkpoint);
    TdCacheStartupCleaner.clean(app.toFile());
    ok(!Files.exists(app.resolve("tdlib/files")),"Stale TDLib files remain after startup cleanup");
    ok(Arrays.equals(db,Files.readAllBytes(database)),"TDLib database was destroyed");
    ok(Arrays.equals(saved,Files.readAllBytes(checkpoint)),"Encrypted resume checkpoint was destroyed");
    TdCacheStartupCleaner.clean(app.toFile());
    ok(Arrays.equals(db,Files.readAllBytes(database)),"Idempotent cleanup damaged database");

    // Reject links rather than following any path outside our dedicated cache.
    Path outside=Files.createTempDirectory("vault-do-not-follow-");
    Path victim=outside.resolve("owner.txt");put(victim,"do not delete");
    Path fileCache=app.resolve("tdlib/files");
    Files.createDirectories(fileCache);
    Files.createSymbolicLink(fileCache.resolve("escape"),outside);
    reject(()->TdCacheStartupCleaner.clean(app.toFile()),"Symlinked cache entry accepted");
    ok(Files.isRegularFile(victim),"Symlink cleanup touched external file");
    Files.delete(fileCache.resolve("escape"));
    TdCacheStartupCleaner.clean(app.toFile());
    Files.createSymbolicLink(fileCache,outside);
    reject(()->TdCacheStartupCleaner.clean(app.toFile()),"Symlinked cache root accepted");
    ok(Files.isRegularFile(victim),"Symlinked cache root escaped");
    Files.delete(fileCache);
    ok(Files.isRegularFile(database),"Database damaged by link rejection");

    Path otherApp=Files.createTempDirectory("vault-fresh-app-");
    TdCacheStartupCleaner.clean(otherApp.toFile());
    ok(!Files.exists(otherApp.resolve("tdlib")),"Cleaner created unrequested directory");
    System.out.println("PASS "+checks+" TDLib crash-cache checks: recursive removal, checkpoint/database retention, symlink rejection, idempotence");
  }
}

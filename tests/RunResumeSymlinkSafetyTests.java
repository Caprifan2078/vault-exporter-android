package org.vault.exporter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Standalone, credential-free adversarial staging-directory escape tests. */
public final class RunResumeSymlinkSafetyTests {
  private static int checks;
  private static void ok(boolean value,String message) {
    checks++;
    if(!value)throw new AssertionError(message);
  }
  private interface Action {void run() throws Exception;}
  private static void rejects(Action action,String message)throws Exception {
    try {action.run();}
    catch(java.io.IOException | IllegalStateException expected){checks++;return;}
    throw new AssertionError(message);
  }
  private static Path put(Path dest,String value)throws Exception {
    Files.createDirectories(dest.getParent());
    Files.write(dest,value.getBytes(StandardCharsets.UTF_8));
    return dest;
  }
  public static void main(String[] args)throws Exception {
    byte[] key=new byte[32];
    Arrays.fill(key,(byte)7);
    Path outside=Files.createTempDirectory("vault-stage-outside-");
    Path victim=put(outside.resolve("keep.txt"),"never change");
    Path workspace=Files.createTempDirectory("vault-stage-symlinks-");
    Path root=workspace.resolve("export-progress");
    Files.createDirectories(root);

    // A redirected export-progress leaf must never be canonicalized into
    // somebody else's directory before validating its target.
    Path redirected=workspace.resolve("redirected-export-progress");
    Files.createSymbolicLink(redirected,outside);
    rejects(()->{try(ResumeStore stage=new ResumeStore(redirected.toFile(),902,key)){}},
        "Symlinked entire export-progress directory was accepted");
    ok(Files.isRegularFile(victim),"Export-progress root symlink escaped sandbox");
    Files.delete(redirected);

    // Do not resolve a preexisting chat directory symlink before checking it.
    Files.createSymbolicLink(root.resolve("chat-901"),outside);
    rejects(()->{try(ResumeStore stage=new ResumeStore(root.toFile(),901,key)){}},
        "Symlinked chat-root was accepted");
    ok(Files.isRegularFile(victim),"Chat-root symlink touched an outside file");
    Files.delete(root.resolve("chat-901"));
    // The rejected construction may have already created its SAFE lock file.
    // Reset only this fictional lock fixture before testing the next symlink.
    Files.deleteIfExists(root.resolve(".locks/chat-901.lock"));
    Files.deleteIfExists(root.resolve(".locks"));

    // The lock path is outside the erasable chat subtree and must be checked too.
    Files.createSymbolicLink(root.resolve(".locks"),outside);
    rejects(()->{try(ResumeStore stage=new ResumeStore(root.toFile(),901,key)){}},
        "Symlinked locks folder was accepted");
    Files.delete(root.resolve(".locks"));

    Path locks=root.resolve(".locks");
    Files.createDirectories(locks);
    Files.createSymbolicLink(locks.resolve("chat-901.lock"),victim);
    rejects(()->{try(ResumeStore stage=new ResumeStore(root.toFile(),901,key)){}},
        "Symlinked per-chat lockfile was accepted");
    Files.delete(locks.resolve("chat-901.lock"));

    try(ResumeStore stage=new ResumeStore(root.toFile(),901,key)) {
      // Valid checkpoint and encrypted page still work without any symlink.
      stage.checkpoint(new ResumeStore.State(901,0,0,0,0,false));
      ok(stage.state().chatId==901,"Ordinary encrypted staging corrupted");

      // A symlinked child directory must not redirect the encrypted page.
      Path pages=root.resolve("chat-901/pages");
      Files.createSymbolicLink(pages,outside);
      rejects(()->stage.putPage(1,"message".getBytes(StandardCharsets.UTF_8),new byte[0]),
          "Writing through symlinked pages directory was accepted");
      ok(!Files.exists(outside.resolve("000001.jsonl.enc")),
          "Encrypted page leaked outside app-private staging");
      rejects(stage::erase,"Recursive erase followed or silently ignored symlink");
      ok(Files.isRegularFile(victim),"Erase followed a symlink outside app");
      Files.delete(pages);

      // No-follow erasure can stop on the symlink before or after visiting
      // ordinary files; do not rely on filesystem enumeration order.
      Files.deleteIfExists(root.resolve("chat-901/checkpoint.enc"));
      // Even a file symlink inside a normal directory must not be read.
      Files.createSymbolicLink(root.resolve("chat-901/checkpoint.enc"),victim);
      rejects(stage::state,"Staging checkpoint file symlink was accepted");
      Files.delete(root.resolve("chat-901/checkpoint.enc"));

      // Removing a clean staging tree is still supported with the lock held.
      stage.checkpoint(new ResumeStore.State(901,0,0,0,0,false));
      stage.erase();
      ok(!Files.exists(root.resolve("chat-901")),"Safe erase left chat staging behind");
      ok(Files.isRegularFile(victim),"Safe erase damaged outside content");
    }
    // Old lock survives erase and kernel lock is correctly released on close.
    try(ResumeStore reopened=new ResumeStore(root.toFile(),901,key)){
      ok(reopened.state().chatId==901,"Cannot reopen after safe erase");
    }
    ok(Arrays.equals("never change".getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(victim)),"Outside file changed during symlink tests");
    System.out.println("PASS "+checks+" staging symlink safety checks: root, locks, read/write, nofollow erase, reopen");
  }
}

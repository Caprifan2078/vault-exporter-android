package org.vault.exporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** No Telegram credentials or account: destructive filesystem safety regressions. */
public final class RunPrivateDataEraserTests {
  private static int checks;
  private static void yes(boolean value,String explanation) {
    checks++;
    if (!value) throw new AssertionError(explanation);
  }
  private interface Action { void call() throws Exception; }
  private static void rejects(Action action,String explanation) throws Exception {
    try {action.call();}
    catch(IOException expected) {checks++;return;}
    throw new AssertionError(explanation);
  }
  private static Path write(Path path,String text) throws Exception {
    Files.createDirectories(path.getParent());
    Files.write(path,text.getBytes(StandardCharsets.UTF_8));
    return path;
  }

  public static void main(String[] args) throws Exception {
    Path root=Files.createTempDirectory("vault-progress-safe-delete-");
    Path tdlib=write(root.resolve("tdlib/database/still-here.dat"),"TDLib database");
    Path other=write(root.resolve("dont-delete.txt"),"persistent app file");
    Path work=write(root.resolve("export-progress/chat-100/pages/000001.jsonl.enc"),"encrypted data");
    write(root.resolve("export-progress/.locks/chat-100.lock"),"lock released");
    yes(Files.exists(work),"Sanity: progress fixture missing");
    PrivateDataEraser.eraseProgress(root.toFile());
    yes(!Files.exists(root.resolve("export-progress")),"Completed logout retained progress");
    yes(Files.exists(tdlib)&&Files.exists(other),"Cleanup deleted unrelated private files");
    PrivateDataEraser.eraseProgress(root.toFile());
    yes(Files.exists(tdlib),"Idempotent logout touched TDLib database");

    Path external=Files.createTempDirectory("vault-outside-logout-");
    Path victim=write(external.resolve("personal-file.txt"),"untouched");
    Path progress=root.resolve("export-progress");
    Files.createDirectory(progress);
    Files.createSymbolicLink(progress.resolve("escape"), external);
    rejects(()->PrivateDataEraser.eraseProgress(root.toFile()),
        "Unsafe nested symlink accepted");
    yes(Files.exists(victim),"Nested symlink escaped app progress root");
    Files.delete(progress.resolve("escape"));
    PrivateDataEraser.eraseProgress(root.toFile());

    Files.createSymbolicLink(progress,external);
    rejects(()->PrivateDataEraser.eraseProgress(root.toFile()),
        "Unsafe progress root symlink accepted");
    yes(Arrays.equals("untouched".getBytes(StandardCharsets.UTF_8),
          Files.readAllBytes(victim)),"Root symlink modified outside victim");
    Files.delete(progress);
    yes(Files.exists(tdlib),"Invalid cleanup removed database");
    System.out.println("PASS "+checks+" private logout deletion checks: nofollow symlinks, preserves database, idempotent");
  }
}

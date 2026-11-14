package org.vault.exporter;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Delete only the explicitly named app-private export-progress tree.
 * Never traverse symlinks: a malformed local entry must not cause cleanup
 * to follow a target outside of the app sandbox. A failure is reported rather
 * than being mistaken for a successful logout cleanup.
 */
final class PrivateDataEraser {
  private PrivateDataEraser() {}

  static void eraseProgress(File appFilesDir) throws IOException {
    Path appRoot = appFilesDir.getCanonicalFile().toPath();
    if (!Files.isDirectory(appRoot, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Private app storage is unavailable");
    Path progress = appRoot.resolve("export-progress");
    if (!Files.exists(progress, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isDirectory(progress, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Unsafe export progress root");
    Files.walkFileTree(progress, new SimpleFileVisitor<Path>() {
      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (!attrs.isRegularFile())
          throw new IOException("Unsafe link or file type in export progress");
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
        if (error != null) throw error;
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }
}

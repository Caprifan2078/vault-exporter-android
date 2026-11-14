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
 * App-private TDLib downloads are not TVA-encrypted. A killed process cannot run
 * export's finally cleanup. Before the NEXT TDLib client is created, remove
 * leftovers from TDLib's dedicated files_directory only; never touch its
 * database_directory, encrypted resume checkpoints, or the output document.
 *
 * Must be invoked when no TDLib client is active. Fail closed on unknown file
 * types or symlinks, and do not follow paths outside the app-private directory.
 */
final class TdCacheStartupCleaner {
  private TdCacheStartupCleaner() {}

  static void clean(File appFilesDir) throws IOException {
    Path tdlib = appFilesDir.toPath().resolve("tdlib");
    if (!Files.exists(tdlib, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isDirectory(tdlib, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Unsafe TDLib directory; cache cleanup refused");
    Path cache = tdlib.resolve("files");
    if (!Files.exists(cache, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Unsafe TDLib downloads directory; cache cleanup refused");
    Files.walkFileTree(cache, new SimpleFileVisitor<Path>() {
      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (!attrs.isRegularFile())
          throw new IOException("Unexpected entry in TDLib cache; cleanup refused");
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

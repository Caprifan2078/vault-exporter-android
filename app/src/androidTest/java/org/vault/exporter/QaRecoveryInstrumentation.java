package org.vault.exporter;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * APK instrumentation ONLY. Two separate invocations deliberately execute
 * in different processes with an Android am force-stop between them.
 * No credentials, Telegram account, online requests or production messages.
 */
public final class QaRecoveryInstrumentation extends Instrumentation {
  private static final long CHAT = -994011223344L;
  private static final long MESSAGE = 893210L;
  private static final int FILE_ID = 9786;
  private static final byte[] PAGE = "{\"id\":893210,\"chat_id\":-994011223344}\n"
      .getBytes(StandardCharsets.UTF_8);
  private static final byte[] MEDIA = "QA fake audio data, not a Telegram file"
      .getBytes(StandardCharsets.UTF_8);
  private static final String ORPHAN = "qa-crash-leftover.txt";
  private static final String MARKER = "qa-checkpoint-sentinel.txt";
  private String phase;

  private static void require(boolean ok, String why) {
    if (!ok) throw new AssertionError(why);
  }

  private static byte[] all(InputStream stream) throws Exception {
    try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[4096]; int n;
      while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
      return out.toByteArray();
    }
  }

  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    phase = args == null ? "" : args.getString("phase", "");
    start();
  }

  @Override public void onStart() {
    super.onStart();
    Bundle result = new Bundle();
    int code = Activity.RESULT_CANCELED;
    try {
      Context target = getTargetContext();
      require(BuildConfig.QA_RUNTIME
              && "org.vault.exporter.runtimeqa".equals(target.getPackageName()),
          "Only the isolated QA runtime application may run this instrumentation");
      if ("prepare".equals(phase)) prepare(target);
      else if ("verify".equals(phase)) verify(target);
      else throw new IllegalArgumentException("Unknown recovery test phase");
      result.putString("vault_qa_status", "PASS");
      result.putString("vault_qa_phase", phase);
      code = Activity.RESULT_OK;
    } catch (Throwable error) {
      // No personal data or secret key bytes are ever printed.
      result.putString("vault_qa_status", "FAIL");
      result.putString("vault_qa_error", error.getClass().getSimpleName()
          + ": " + String.valueOf(error.getMessage()));
    } finally {
      finish(code, result);
    }
  }

  private static void prepare(Context context) throws Exception {
    File files = context.getFilesDir();
    File progress = new File(files, "export-progress");
    File privateChat = new File(progress, "chat-" + CHAT);
    require(!privateChat.exists(), "Synthetic checkpoint already exists: not a fresh QA device");
    byte[] key = new SessionSecrets(context).getOrCreate();
    File sample = new File(context.getCacheDir(), "qa-media-fixture");
    try {
      Files.write(sample.toPath(), MEDIA);
      try (ResumeStore stage = new ResumeStore(progress, CHAT, key)) {
        stage.putPage(1, PAGE, new byte[0]);
        ResumeStore.Media stored = stage.saveMedia(MESSAGE, FILE_ID, sample);
        require(stored.bytes == MEDIA.length, "Synthetic encrypted media length mismatch");
        stage.recordMediaIdentity(MESSAGE, FILE_ID, "qa-offline-identity");
        stage.checkpoint(new ResumeStore.State(CHAT, MESSAGE, 1, 1, 1, false));
      }
    } finally {
      Arrays.fill(key, (byte) 0);
      Files.deleteIfExists(sample.toPath());
    }
    File pending = new File(privateChat, "pages/.pending-crashed-write.tmp");
    Files.write(pending.toPath(), new byte[]{9, 9, 9});

    // A synthetic plain file models TDLib's leftover cache after process death.
    File abandoned = new File(files, "tdlib/files/" + ORPHAN);
    require(abandoned.getParentFile().isDirectory()
        || abandoned.getParentFile().mkdirs(), "Unable to create synthetic TDLib cache");
    Files.write(abandoned.toPath(), "synthetic plaintext".getBytes(StandardCharsets.UTF_8));
    File database = new File(files, "tdlib/database/" + MARKER);
    require(database.getParentFile().isDirectory()
        || database.getParentFile().mkdirs(), "Unable to create synthetic database directory");
    Files.write(database.toPath(), "must survive".getBytes(StandardCharsets.UTF_8));
    require(abandoned.isFile() && pending.isFile(), "Crash fixtures not persisted");
  }

  private static void verify(Context context) throws Exception {
    File files = context.getFilesDir();
    File progress = new File(files, "export-progress");
    File privateChat = new File(progress, "chat-" + CHAT);
    File pending = new File(privateChat, "pages/.pending-crashed-write.tmp");
    File abandoned = new File(files, "tdlib/files/" + ORPHAN);
    File database = new File(files, "tdlib/database/" + MARKER);
    require(abandoned.isFile() && pending.isFile(), "Crash files disappeared before recovery");
    require(database.isFile(), "TDLib database test marker lost before recovery");
    byte[] key = new SessionSecrets(context).getOrCreate();
    try (ResumeStore stage = new ResumeStore(progress, CHAT, key)) {
      ResumeStore.State state = stage.state();
      require(state.chatId == CHAT && state.cursor == MESSAGE && state.pages == 1
          && state.messages == 1 && state.media == 1 && !state.historyFinished,
          "Encrypted Android Keystore checkpoint did not survive process death");
      require(Arrays.equals(PAGE, all(stage.openPage(1))), "Encrypted message page lost");
      require(Arrays.equals(MEDIA, all(stage.openMedia(MESSAGE, FILE_ID))),
          "Encrypted media could not be decrypted with restored Keystore secret");
      require(stage.hasMediaIdentity(MESSAGE, FILE_ID, "qa-offline-identity"),
          "Persistent media identity lost");
      require(!pending.exists(), "Abandoned encrypted temporary file not reclaimed");
      require(abandoned.isFile(), "Plaintext cache erased before the native receiver was reserved");
    } finally {
      Arrays.fill(key, (byte) 0);
    }
    TdGateway gateway = new TdGateway(event -> {}, files);
    try {
      require(!abandoned.exists(), "Plaintext TDLib crash cache not erased during native start");
      require(database.isFile(), "TDLib database deleted during cache recovery");
      require(Arrays.equals("must survive".getBytes(StandardCharsets.UTF_8),
          Files.readAllBytes(database.toPath())), "TDLib database marker changed");
    } finally {
      gateway.stop();
    }
    // The fixture is deliberately left intact in the ephemeral CI emulator.
    // Do not call SessionSecrets.discard(): that could delete a user's key if
    // this test was accidentally run on a non-ephemeral QA installation.
  }
}

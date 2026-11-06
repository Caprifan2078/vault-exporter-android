package org.vault.exporter;
import java.io.IOException;
public final class TestExportFailureMessages {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args) {
    check(ExportFailureMessages.describe(new ExportEngine.CachedMessageStateException("private")).contains("изменилось"),"Missing cached-message guidance");
    check(ExportFailureMessages.describe(new SecurityException("private")).contains("ограничено"),"Missing protected-content guidance");
    check(ExportFailureMessages.describe(new IOException("private",new TelegramRateLimit.Delay(121))).contains("121 сек."),"Missing requested wait");
    check(ExportFailureMessages.describe(new ExportEngine.CachePrivacyException("private")).contains("незашифрованного"),"Missing cache privacy warning");
    check(ExportFailureMessages.describe(new ExportEngine.StorageCapacityException(67108864,33554432)).contains("свободной памяти"),"Missing storage guidance");
    check(!ExportFailureMessages.describe(new IOException("SECRET_PHONE_TOKEN")).contains("SECRET_PHONE_TOKEN"),"Leaked error contents");
    System.out.println("PASS: safe error guidance for cached changes, restrictions, rate limits, storage and privacy");
  }
}

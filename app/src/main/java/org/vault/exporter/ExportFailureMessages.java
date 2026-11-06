package org.vault.exporter;

import java.io.IOException;

/** Safe user-facing explanations; never display private TDLib errors or requests. */
public final class ExportFailureMessages {
  private ExportFailureMessages() {}
  public static String describe(Exception failure) {
    if(failure instanceof ExportEngine.CachePrivacyException)
      return "ВНИМАНИЕ: не удалось подтвердить удаление незашифрованного временного файла. Экспорт остановлен ради приватности. Не повторяй его до очистки данных Vault Exporter в настройках Android. Это удалит сеанс и незавершённый прогресс.";
    if(failure instanceof ExportEngine.StorageCapacityException)
      return ((ExportEngine.StorageCapacityException)failure).userMessage();
    if(failure instanceof ExportEngine.CachedMessageStateException)
      return "Сохранённое сообщение изменилось, недоступно локально или его данные некорректны. Экспорт остановлен, прогресс сохранён. Попробуй позже; если ошибка повторяется, можно удалить прогресс выбранного чата и начать загрузку заново.";
    if(failure instanceof SecurityException)
      return "Экспорт остановлен: сохранение чата или сообщения ограничено либо его защиту не удалось подтвердить. Прогресс сохранён.";
    if(failure instanceof IOException && failure.getCause() instanceof TelegramRateLimit.Delay) {
      long wait=((TelegramRateLimit.Delay)failure.getCause()).waitSeconds;
      return "Telegram ограничил частоту запросов. Подожди не менее "+wait+" сек. и попробуй продолжить позже. Прогресс сохранён.";
    }
    return "Экспорт прерван. Зашифрованный прогресс сохранён локально, можно попробовать продолжить позже.";
  }
}

# Единственный внешний блокер настоящей Telegram QA APK

Проверено 2 ноября 2026:

- [QA Android compile](https://github.com/Caprifan2078/vault-exporter-android/actions/runs/41440264498) — SUCCESS: обе Java-версии, static/JVM/JNI; **APK не создавалась**.
- [QA production APK](https://github.com/Caprifan2078/vault-exporter-android/actions/runs/41440404533) — STOP на проверке GitHub Actions Secrets `TELEGRAM_API_ID` и `TELEGRAM_API_HASH`. **Ни звонков, ни входов, ни Telegram данных нет.**
- [Source-built offline APK](https://github.com/Caprifan2078/vault-exporter-android/actions/runs/41440702889) — отдельная попытка сборки TDLib из официального фиксированного исходника; состояние смотреть в GitHub Actions. Её итог нельзя выдавать за настоящий Telegram-экспорт.

## Что должен сделать владелец репозитория (один раз, бесплатно)

1. На **официальном** https://my.telegram.org/apps войти в свой существующий Telegram через браузер, выбрать API development tools и создать приложение, например «Vault Exporter», Android. Это регистрация *приложения разработчика*, не вход основного Telegram в экспериментальную APK.
2. Значения `App api_id` (целое число) и `App api_hash` (32 hex-символа) **НЕ отправлять в ChatGPT, issue, PR, публичную переписку, скриншоты или исходники**.
3. Добавить их на приватной странице https://github.com/Caprifan2078/vault-exporter-android/settings/secrets/actions как два **Repository secrets** с точными именами `TELEGRAM_API_ID` и `TELEGRAM_API_HASH`.
4. После этого в QA-ветке `qa/disposable-real-telegram-20261102` можно повторно сделать явный запрос на сборку (изменить `ci/APPROVED_QA_BUILD_REQUEST.txt`, без содержимого ключей). GitHub Actions делает приватный QA артефакт с хранением 1 день и **не запускает Telegram** без ввода тестового аккаунта в APK.

Не покупать аккаунт Djekxa до получения установочной QA APK и прохождения офлайн самодиагностики. Не подключать основной номер к QA-экспортеру. Реквизиты зарегистрированного приложения могут быть извлечены из скомпилированного APK; не распространять QA APK публично.

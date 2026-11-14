# Vault Exporter — паспорт восстановления (9 ноября 2026)

## Каноническая основа
- Актуальный приватный репозиторий: `Caprifan2078/vault-exporter-android`, default branch `main`.
- Исходная проверенная база восстановления: ветка `qa/full-audit-20261106`, коммит `87e0a3d910d9f7aad0e7692c963bec4d79d067a7`.
- Рабочая ветка восстановления: `qa/recovery-audit-20261109`. Только она изменена в этой итерации. Не сливать в main.
- PR #3 (`qa/full-audit-20261106` → `qa/security-recovery-20261106`) открыт как draft, не слит.
- Версия из `app/build.gradle`: `0.9-test-gated-alpha`, versionCode 9. Ниже речь об экспериментальном исходном дереве, не о публичном релизе.
- Исторические Markdown и исходные ZIP из октября имеют более старые владельцев/ветки/оценки и не являются текущим HEAD.

## Проверенные следы существования
- GitHub tree последнего QA коммита содержит MainActivity, ExportEngine, TdGateway, TdNative, ArchiveWriter, ArchiveVerifier, ChunkedVault, ResumeStore, SessionSecrets, StagingCipher, SyntheticZipWriter, Gradle/CMake, CI workflows и автономные тесты.
- `app/src/main/jniLibs/arm64-v8a/` в Git хранит только README. Реальная `libtdjson.so` поступает при сборке из официального TDLib (не считать source checkout полноценным APK).
- Доказательства старого HEAD `87e0a3d`:
  - https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42303616149 — завершён `success`: Java compile и статические/JVM регрессии.
  - https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42303616172 — завершён `success`: сборка APK/подпись, офлайн TDLib/JNI в эмуляторе, синтетическая выгрузка.
  - https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42303323317 — `success` 100 000 синтетических сообщений с восстановлением на предыдущем коммите.
- QA артефакты последнего успешного прогона уже не перечисляются API списка artifacts; наличие старых SHA-256 в PR #3 не означает доступность APK для скачивания.
- Ранее использовавшийся артефакт pinned source-built APK из run 41440702889 был доступен при проверке и имел `expires_at=2026-11-09T12:34:24Z`. Поэтому прежний `qa-runtime-apk.yml` скоро стал бы несобираемым даже при неизменном коде.

## Что есть в коде
- Прямой клиент TDLib JSON C API через JNI; экраны входа/списка чатов/пароля/сохранения/выхода.
- `getChatHistory` и `downloadFile`, категории медиа, медиасвязи с сообщениями, зашифрованный TVA1 (AES-GCM), шифрованное промежуточное состояние и resume.
- Синтетические сценарии, тесты обрывов и ошибок, архивные проверки и ограничение памяти.
- Защита от части неизвестных/защищённых/самоудаляющихся сообщений, ошибки TDLib не всегда превращаются в мнимый конец списка, защита повторного использования неверных media ID, безопасная повторная проверка контента.
- Тестовая сборка отделена от production; runtime QA не содержит API credentials внутри APK. Конечная цель — не требовать от обычного пользователя ручного API ID/hash.
- Учебный HTML ZIP создаётся только из вымышленных данных кодом `SyntheticZipWriter`. Реальный пользовательский экспорт — парольный `.tva`, который текущий Telegram Archive Viewer пока не умеет импортировать.

## Что **не** проверено / не готово
- Реальный вход Telegram (код, email/2FA), получение списка в реальном аккаунте, выгрузка реального чата и облачных медиа, logout, обрыв на реальном устройстве — **не подтверждены**.
- Не проводился независимый сетевой, криптографический, privacy и API-terms аудит; не доказано отсутствие расшифрованных копий TDLib в кэше после аварий.
- Совместимость `.tva` с Telegram Archive Viewer — **отсутствует**; не подменять её учебным ZIP.
- Не доказаны многогигабайтный экспорт на телефоне, foreground service, doze/background, ограничение свободного места, стабильная производительная работа с большими чатами, резервирование и стабильная релизная подпись.
- Пропуски недоступных сообщений и Telegram-серверные изменения требуют реального сквозного тестирования.
- Нулевая вероятность Telegram-ограничений или потерь данных не гарантируется. Не использовать основной аккаунт для QA.

## Исправления этой итерации
1. В `.github/workflows/qa-runtime-apk.yml` заменено скачивание скоро истекающего GitHub Actions APK-артефакта на прямую сборку официальной TDLib из закреплённого SHA `ea3f69ce11aef458bfbb09869b809ede4447486f`, используя уже имевшийся протестированный рецепт `qa-offline-apk.yml`; проверяются pin, ABI и ELF. Ограничение времени сборки увеличено до 120 минут. Эта правка устраняет зависимость от *данного* истекающего артефакта, но её собственный полный CI ещё должен завершиться успешно.
2. Включены статические/JVM/Android compile checks для отдельной рабочей ветки.
3. В `MOCK_TRIAL_RU.md` вместо несуществующего `HtmlArchiveWriter.verify` указан реальный `SyntheticZipWriter.verify`.

Новые CI на момент составления паспорта: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42535049674 и https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42535045773 . **Запущены, результат не подтверждён.** Не считать текущую правку прошедшей CI без статуса success и проверки шагов.

## Приоритеты для следующей сессии
P0: дождаться _результата CI, а не предполагать успех_; при ошибке поправить только отдельную QA ветку. P0: контролируемая безопасная проверка session/cache/lifecycle и прав на сохранение. P1: один разрешённый тестовый Telegram-аккаунт и фиктивный чат, реальная авторизация/экспорт, byte-level медиа и logout (не вводить секреты в ChatGPT и не использовать основной аккаунт). P1: полноценный TVA1 импорт в Archive Viewer и тест перекрёстной совместимости. P1: foreground service, большие объёмы/обрывы. P2: стабильная подпись и приёмка реального APK. Не тратить ресурс на новые декоративные функции до закрытия P0/P1.

## Степень готовности
Нельзя вывести объективный единый процент из только офлайн-CI. Ориентировочно: значительный объём инфраструктуры/кода уже существует, офлайн QA покрывает много сценариев; главный пользовательский сценарий остаётся **0 реально подтверждённых end-to-end Telegram-сессий**. Release-ready = **нет**. Проценты тестов и продуктовой готовности не смешивать.

## Итерация: исправление многовложенных сообщений и проверка CI (9 ноября 2026)
- В `ArchiveVerifier.verify` найден подтверждённый дефект: `usedIds.add(message_id)` запрещал **любые** два файла одного сообщения. `ExportEngine.attachments` уже поддерживал несколько файлов (`messagePaidMedia`, анимированные фото чата, link preview). Исправлено на уникальную пару `message_id:file_id`: разные файлы одного сообщения разрешены, повтор одного `file_id` — отказ. Изменение: `08989d4a9965dcac71b46d3867119bb0aac99c33`.
- `RunSemanticArchiveVerifierTests`: положительный защищённый TVA с двумя файлами одного сообщения; отрицательный TVA с повтором того же `file_id` под другим расширением. Тест: `a166c3816b242860dcd3dc279a77baae983cfd36`.
- Проверено: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42537787833 — оба jobs завершены **success**: static/JVM, криптоархив, интеграция/восстановление, TDLib/JNI, компиляция двух Android-конфигураций. Это НЕ проверка реального экспорта.
- Оптимизирована нагрузка CI: в `.github/workflows/qa-runtime-apk.yml` введена `concurrency` по `github.ref` с `cancel-in-progress: true`; более новый push отменяет устаревшую задачу той же ветки. Правка `275448db8d44c3304415d0b353ec29caeeaa5d18`. Ранее запущенные workflow без группы отменяться автоматически не обязаны.
- На предыдущем коммите `ef496b0` в run https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42535045773 job `runtime-apk` завершился **success**: TDLib rebuilt from source + assemble APK + подпись. Оба offline emulator jobs на момент этой записи были **queued**, поэтому полная Android runtime проверка текущего кода **не подтверждена**. Наличие успешного APK на старом коммите не доказывает, что он включает фикс ArchiveVerifier.
- Новейший runtime QA на коммите `275448db`: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42537900091 — в процессе сборки TDLib, результат ещё не подтверждён. Workflow артефакты — краткоживущие приватные QA-материалы, не production release.

### RESUME STATE
- Проект: Vault Exporter (Android Telegram exporter).
- Репозиторий: `Caprifan2078/vault-exporter-android` (private).
- Ветка: `qa/recovery-audit-20261109`, PR #4 draft → `qa/full-audit-20261106`; `main` не менять.
- Последняя проверенная базовая версия: `0.9-test-gated-alpha`. Ветка на коммите `275448db8d44c3304415d0b353ec29caeeaa5d18` до данного документационного коммита.
- Последняя задача: исправление неверного запрета нескольких медиа одного сообщения; JVM + Android compile **PASS**.
- Осталось: полный runtime QA build/emulator на текущем коде, реальная разрешённая тестовая авторизация/выгрузка, безопасность временного кэша и жизненный цикл, поддержка TVA в Viewer, стабильная release-подпись.
- Следующее действие: проверить завершение run `42537900091` и его job/artifacts; при failed — точечная диагностика лога на этой ветке. Не повышать версию и не делать release.
- Для тестов в Linux GitHub runner: `bash RUN_ALL_OFFLINE.sh`; Android QA: `qa-android-compile.yml`; полный pinned TDLib + эмулятор: `qa-runtime-apk.yml`.
- Разрешения: ничего дополнительного для офлайн CI; для реального Telegram QA требуются отдельное согласие, изолированный тестовый аккаунт и личные developer credentials, которые не должны передаваться в чат.
- Контрольная точка: этот файл и PR #4.

### Дополнительная интеграционная регрессия
- Коммит `3ba4e0c25d994df1d28d17f33f6f4e460329c956`: `integration-jvm/TestHardenedExportEngine.java` создаёт `messagePaidMedia` с оригинальным фото и видео (разные `file_id`) и прогоняет **реальный ExportEngine**, fake TDLib, шифрование TVA1, `ArchiveVerifier` и проверку байтов/числа 3 медиа (два на одном сообщении плюс голосовое в другом). Код приложения не менялся, только тест.
- CI https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42538315340 — **success обоих jobs** (`static-and-jvm` и `android-compile`), включая экспорт двух медиа одним сообщением, криптотесты и компиляцию обеих Android-конфигураций. Это офлайн-проверка, не вход в Telegram.

- Исторически более ранний запуск https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42535045773 теперь подтверждён полностью: **success всех трёх jobs** — сборка pinned TDLib и подписанных QA APK, установка/нативный офлайн self-test в эмуляторе и учебный экспорт вымышленных сообщений/медиа. Этот коммит `ef496b0` предшествовал правке многовложенных сообщений и не считается проверкой текущего HEAD.

## Итерация: восстановление после прерванной записи TVS2 (9 ноября 2026)
- **Обнаружено по коду:** `ResumeStore.put` сохраняет encrypted staging во временный `.pending-*.tmp`, затем атомарно переименовывает. При обычном исключении файл удаляется, но при аварийном завершении процесса `catch` не срабатывает. Следовательно, крупные незавершённые зашифрованные медиа могли оставаться в закрытом хранилище и постепенно занимать место. Наличие таких файлов на реальном устройстве не проверялось.
- **Изменение:** `a6d886ca831ee8c2e2ff43a63433a8e478b7fb75` — после получения исключительной lock-блокировки нужного чата `ResumeStore` удаляет только созданные этим механизмом незавершённые `.pending-*.tmp` в штатных каталогах; не следует по symlink, не читает файлы в память, отказывает при нештатном типе элемента. Подтверждённые страницы, медиа и checkpoint не трогаются.
- **Регрессия:** `cbe31f406301eeb96311096bb4a4054654ecd700` — создаёт искусственные аварийные остатки в корне и четырёх подкаталогах; проверяет их удаление, сохранение других файлов, побайтовое сохранение ранее записанных checkpoint/media и возможность их прочитать.
- **CI:** https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42539160563 — **SUCCESS обоих jobs** (`static-and-jvm`, `android-compile`). Это JVM-моделирование последствий crash, а не реальный SIGKILL на Android. Отдельный CI исходного code change без теста https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42539133351 тоже SUCCESS.
- **Текущее ограничение:** полная сборка APK с изменённым `ResumeStore` запущена: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42539133393 . До окончательного успеха её jobs не заявлять APK проверенным. Реальный Telegram аккаунт и персональные данные не использовались.

### Обновлённый RESUME STATE
- Проект: Telegram Vault Exporter, версия исходного Android-кода `0.9-test-gated-alpha`.
- Рабочая ветка: `qa/recovery-audit-20261109`, draft PR #4 в `qa/full-audit-20261106`. **main не менять и не сливать.**
- Последнее проверенное изменение кода: `a6d886ca831ee8c2e2ff43a63433a8e478b7fb75`, тест `cbe31f406301eeb96311096bb4a4054654ecd700`.
- Проверки: CI `42539160563` SUCCESS (оба jobs). Не считать Android real-device и настоящий Telegram экспорт подтверждёнными.
- Блокер: официальная TDLib полная повторная сборка на этом коде, жизненный цикл/cleanup Android и Test DC E2E, интеграция TVA1 с Viewer, стабильная подпись.
- Следующая конкретная задача: узнать статус `42539133393`; затем выполнить Android emulator smoke с аварийным перезапуском или отдельное безопасное E2E-тестирование, без личного аккаунта.
- Команды для разработчика: `bash RUN_ALL_OFFLINE.sh`; CI `qa-android-compile.yml`, `qa-runtime-apk.yml` (отдельная ветка, без входа).
- Сохранённая контрольная точка: этот файл в GitHub и черновой PR #4.

## Итерация: контроль принадлежности сообщений чату в TVA1 (9 ноября 2026)
- **Обнаружено по исходникам:** `ArchiveVerifier.verify` до этого проверял количество/порядок ID сообщений, медиа SHA-256 и манифест, но не сверял `message.chat_id` с `manifest.chat_id`. Внутренне аутентифицированный архив с ошибочно смешанными сообщениями разных чатов мог пройти самостоятельную проверку формата, хотя сам `ExportEngine` ранее проверял чат во время загрузки. Это независимая гарантия внутренней непротиворечивости, не доказательство чужого доступа или уязвимости Telegram.
- **Изменение:** `407580b4a7aa51eceae31d0dbbeef593db357512`, корректировка пустого чата `056e44fa925e6c59c7112014cc56e9a2221f9955`. При наличии `chat_id` у сообщений обязательно совпадение всех ID (включая отрицательные), манифест обязан совпасть; смешанное наличие поля запрещено. Пустой чат с `messages=0` остаётся корректным. Исторические минимальные тестовые архивы, полностью опускающие `chat_id`, остаются читаемыми.
- **Регрессии:** `4c7c3054325b39f8cfa1508f541987402d3d3b14`, `76d30e78197c2cd603102f4e15b53f13333c06d8`: отрицательные сценарии подмены `chat_id` в сообщении/манифесте, частичного отсутствия поля, неверного JSON-типа, `chat_id=0` у пустого архива; положительные сценарии отрицательного Telegram chat ID, полностью согласованного манифеста, допустимого старого тестового архива без поля и пустого чата.
- **Проверка:** https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42546226975 — **оба jobs SUCCESS**: `static-and-jvm` (включая новые сценарии) и `android-compile`. Подтверждена офлайн-регрессия и Android-компиляция; это ещё не реальная выгрузка Telegram. Предыдущий compile/JVM https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42546129245 — оба jobs success (промежуточный коммит).
- **APK:** https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42546205243 — полный pinned TDLib и эмулятор для исправленной версии в процессе; прошлый APK run `42539133393` success **только для более раннего кода**.
- **Последняя проверенная версия:** `0.9-test-gated-alpha` по Gradle, публичного релиза нет.

### RESUME STATE, обновление
- Проект: `Caprifan2078/vault-exporter-android` (private).
- Ветка: `qa/recovery-audit-20261109` (draft PR #4 → `qa/full-audit-20261106`), `main` не менять.
- Последний проверенный код: `056e44fa925e6c59c7112014cc56e9a2221f9955`; тест `76d30e78197c2cd603102f4e15b53f13333c06d8`.
- Результат: полный Android compile+JVM `42546226975` SUCCESS; полный APK `42546205243` пока проверить отдельно.
- Не проверено: настоящий Telegram login/2FA, медиа в реальном чате, перезапуск на телефоне, интеграция TVA с Viewer.
- Следующее: проверить оба jobs `42546226975`, полный APK `42546205243`; затем Android kill/restart smoke test на синтетической переписке. Не тратить CI на несвязанные изменения.
- Команды для восстановления: `bash tests/RUN_TESTS_OFFLINE.sh`, `bash integration-jvm/RUN_INTEGRATION_JVM.sh`, workflow `qa-android-compile.yml` и `qa-runtime-apk.yml`.
- Контрольная точка: этот файл и PR #4. Данные/секреты не выгружать, merge/release не делать.

## Итерация: однозначность вложенного JSON в архиве (9 ноября 2026)
- **Пробел по коду**: `ArchiveVerifier.Json.fields` отклонял повторные JSON-ключи верхнего уровня, но вложенные объекты в `Json.value` могли содержать повтор одного поля. Разные JSON-парсеры по-разному интерпретируют такой архив; это риск некорректной интерпретации **внутренних данных**, а не доказательство компрометации Telegram.
- **Код**: коммит `724c6221aa283c4a52665097e72a496ecf7e1aca`. Для каждого вложенного JSON-объекта добавлен локальный `HashSet<String>`, повторный ключ вызывает `IOException`. Потребление памяти ограничено размером одной JSONL-строки и глубиной вложенности; формат TVA1 не изменён.
- **Тесты**: `1832531d7dc42a3bf9056f88a1f99659eacaa199` — намеренно повторяющиеся вложенные ключи в сообщении, списке участников, манифесте и индексе медиа; все должны отклоняться. Обычные архивы, включая многовложенные и пустые, проходят прежние регрессии.
- **Проверено**: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42616187693 — **оба jobs SUCCESS** (`static-and-jvm` и `android-compile`). Реальная авторизация/экспорт не запускались.
- **Полная Android QA сборка** для кода `724c6221` запущена: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42616101096 . При последней проверке `runtime-apk` собирает pinned TDLib, результат ещё **не известен**. Последняя полностью успешная более ранняя сборка: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42546205243 (все 3 jobs SUCCESS).
- **Следующий P0/P1 кандидат**: проверить гонку первого создания `SessionSecrets.getOrCreate` между несколькими экземплярами Activity и `discard` при выходе. Требуется воспроизводимый тест, затем минимальная сериализация операций; не создавать рисковых изменений без проверки.

### RESUME STATE
- Проект: Telegram Vault Exporter, `0.9-test-gated-alpha`, приватный `Caprifan2078/vault-exporter-android`.
- Ветка: `qa/recovery-audit-20261109`, PR #4 draft → `qa/full-audit-20261106`; main не трогать.
- Последняя функциональная правка: `724c6221aa283c4a52665097e72a496ecf7e1aca`; регрессионные тесты `1832531d7dc42a3bf9056f88a1f99659eacaa199`.
- Успешно: JVM/Android compile `42616187693`; предыдущая полная APK/эмулятор `42546205243`.
- Не подтверждено: полный APK/эмулятор на новой правке `42616101096`, реальная TDLib с тестовым аккаунтом и долговременная фоновая выгрузка, TVA1-import в Viewer.
- Следующее действие: проверить `42616101096`, затем выполнить точечный воспроизводимый тест race `SessionSecrets` либо Android kill/restart без аккаунта.
- Команды: `bash integration-jvm/RUN_INTEGRATION_JVM.sh`, `bash tests/RUN_TESTS_OFFLINE.sh`; QA GitHub workflows `qa-android-compile.yml`, `qa-runtime-apk.yml`.
- Постоянная контрольная точка: этот файл и PR #4.

## Итерация: безопасность ключей, logout и остаточного plaintext TDLib-кэша (10 ноября 2026)

**Причина:** Прежние оценки готовности по процентам не имели независимой методики и не должны использоваться как доказательство безопасности. Продолжили работу по конкретным кодовым рискам в QA без аккаунта и без изменения `main`.

### 1. Ключи сессии Android Keystore / race
- До: `SessionSecrets.getOrCreate` и `discard` не сериализовали работу разных экземпляров Activity. Теоретически одновременное первое создание могло перезаписать wrapped secret, сделав TDLib database и зашифрованные checkpoints нечитаемыми.
- Исправление: общий `SessionKeyLifecycle`, атомарная работа с wrapped-парой под process-global monitor; отдельно fail-closed на частичную/corrupt запись и отсутствие существующего Keystore alias; запрет возврата ключа, который не был надёжно сохранён; нуление сгенерированного ключа при отказе. Production adapter в `SessionSecrets` по-прежнему использует неэкспортируемый AndroidKeyStore AES/GCM.
- Коммиты: `080549dfc83b71b3e741e922b02841a6015f6482`, `7eb71e2ebf66baade60728e93569b5718bedb392`. Регрессионные тесты: `c007236de54ed3a6e74888350e77a6faeec31388` (включая 48 параллельных обращений), включены в офлайн CI через `dbf5e3e99910129ffedfc75a27e38046316f1d63`.
- Доказательства: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42723946050 — оба jobs **success**, ключевые JVM-тесты и Android compile; это не проверка Keystore на физическом устройстве.
- Ограничение: монитор защищает один процесс (по текущему AndroidManifest приложение однопроцессное), не препятствует внешней потере Android Keystore или компрометации ОС.

### 2. Гонки logout / picker / export
- До: кнопка завершения сеанса не блокировала повторное подтверждение и выбор файла экспорта в период асинхронного выхода. После подтверждения logout оставались видимыми старые кнопки чата.
- Исправление: запрет reset/export/picker при `logoutRequested`, повторная проверка внутри обработчика `Завершить сеанс`, сброс выбранного чата и скрытие старых кнопок после подтверждённого запроса logout.
- Коммиты `486078cee448358e1ae671bb4030ca6495b3485f`, `2f2a55841282a3515ecdb215d68a754a57f80f15`, регрессии `3a219591f16975e13b1b2efb910245ddfa748d46`, `37cb5b1b6c9d9b1fc42240af1284756916c7103a`; `f05247298f9ffe5fbeda8e1191fdfb5a50683641` обновил старый статический контракт при более строгой защите.
- CI https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42724285283 — оба jobs **success** для первой logout guard правки; финальная проверка полного текущего набора https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42725281739 — оба jobs **success**.
- Не подтверждено: реальный серверный ответ Telegram и подтверждение полной серверной аннуляции сеанса.

### 3. Остаточный незащищённый TDLib файловый кэш после SIGKILL
- До: `ExportEngine` удалял plaintext после обработки, но обработчики `finally` не выполняются при убийстве процесса; файлы `getFilesDir()/tdlib/files` могли оставаться до следующего запуска. Наличие реальных остаточных файлов на физическом устройстве **не подтверждено**.
- Исправление: `TdCacheStartupCleaner` рекурсивно удаляет только отдельный каталог TDLib `files_directory` перед созданием следующего native-клиента. Не трогает `tdlib/database`, `export-progress`, готовые `.tva`. Отказывает при symlink/нештатных объектах, без перехода за пределы приватного дерева. Итоговая версия `TdGateway` сначала резервирует глобальный receiver, затем выполняет scrub и только затем создаёт native-клиент — второй Activity не может стирать кэш живого клиента.
- Коммиты: `365dedd93f69e935d885956b0cea844a743ba4f5`, `9c18ad17ad17ec799ed117de8fa88773be6a1115`, `2f2b4ff294f25cf6cfc107c488a8439f8cf04a17`, `c95a5a8849e0b2e9ae0faaf84cadf653f0a7cddb`.
- Тесты: `a7e99a00d4d8ba2b8a4bc76e57b55f81319f35ef`, `e25ae53f8e29c2c776c3dc85369f383b0ff978b3`, `2e38afca72be42ac638273ee4a952b6a6d76dd8f`, `1f989068883ec7d04b0ac56f0ee829677ff179ad`, `1ec67353baf0bfaabbbd6a722e890ca1f27ff03f`; тестируются выжившие файлы, отсутствие удаления БД и checkpoint, защита от symlink и резервирование native receiver.
- Доказательства: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42725036044 — **оба jobs success** (включая transport JVM и Android compile); финальное объединённое покрытие https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42725281739 — **оба jobs success**.
- ОГРАНИЧЕНИЕ: после SIGKILL до нового запуска файл ещё может существовать в закрытом каталоге приложения. Риск полностью не устранён. На устройстве с root или скомпрометированной ОС локальное хранение нельзя считать защищённым только этим механизмом. Нужен Android kill/restart test и при необходимости более строгая политика кеширования.

### 4. Текущее состояние
- Рабочая ветка `qa/recovery-audit-20261109` и draft PR #4 в `qa/full-audit-20261106`; **не менять main и не публиковать релиз**.
- Самый свежий функциональный код `2f2a55841282a3515ecdb215d68a754a57f80f15`, регрессии до `37cb5b1b6c9d9b1fc42240af1284756916c7103a`.
- Полный Android QA runtime APK + offline emulator на новом коде: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42725253062 — при последней проверке задача `runtime-apk` ещё строит pinned TDLib, результат **не заявлять успешным** без окончательного статуса всех jobs.
- НЕ ГОТОВО: E2E вход в реальный Telegram (только изолированный тестовый аккаунт), стабильная подпись release, foreground/background lifecycle, независимый security/privacy review, аппаратный Keystore test, проверка кэша после убийства Android процесса, импорт `.tva` в Viewer, долговременная большая выгрузка на телефоне. **Ноль подтверждённых live-account end-to-end прогонов**.
- Проценты функциональной и release/security готовности не подменяют реальную приёмку, общий измеренный процент отсутствует.
- Следующий безопасный шаг: проверить исход `42725253062`; добавить эмуляторный kill/restart тест plaintext cache cleanup/keystore и только затем планировать ограниченный live Telegram test без личной переписки.
- Чтобы продолжить: этот паспорт + PR #4 + последние CI. Пользователю не нужны повторные вводы prompt, аккаунтов и секретов.

## Контрольная матрица прогресса — 10 ноября 2026

**Ограничение метрики:** Это фиксированные **20 контрольных задач QA**, одинаково учитываемых лишь для видимого прогресса, **НЕ** процент доказанной безопасности, полноты реализации или готовности к релизу. Отметка [x] означает только конкретный названный уровень проверки; офлайн-тест ≠ реальный Telegram.
**База на начало этой итерации:** 10/20, реконструирована по ранее успешным CI.
**После фикса потери remote unique ID:** 11/20 = **55% контрольного списка**, +1 пункт / **+5 процентных пунктов**. Общая продуктовая готовность по-прежнему не измерена; критические блокеры остаются. Если регрессия обнаружена, статус возвращается в [ ].
- [x] 01 Безопасные разрешения, отсутствие облачного Android backup, gating production по статическим проверкам (CI `42726725734`)
- [x] 02 Компиляция обеих Android-конфигураций (CI `42726725734`)
- [ ] 03 Полная исходная сборка official pinned TDLib и QA APK с подписью **на последнем коде** (выполняется `42727099985`; ранее успешно на старой ревизии)
- [ ] 04 Android emulator synthetic full export **на последнем коде** (ожидается `42727099985`; ранее успешно на старой ревизии)
- [x] 05 TVA1 AES-GCM, структура, независимые проверки целостности и несовпадений (JVM CI `42726725734`)
- [x] 06 Медиа одного сообщения, SHA-256 и вложения с байтовой проверкой через fake TDLib (JVM CI `42726725734`)
- [x] 07 Ограничения protected/secret/self-destruct/auto-delete fail-closed в симуляторе TDLib (JVM CI `42726725734`)
- [x] 08 History cursor, 404/rate limits, simulated interruption and retry (JVM CI `42726725734`)
- [x] 09 Зашифрованный resume/staging и process-crash/lock тесты на JVM (CI `42726725734`)
- [x] 10 Безопасная сериализация создания/удаления wrapped Keystore secret, 48 concurrent JVM calls (CI `42726725734`)
- [x] 11 Стартовая очистка TDLib plaintext кэша + глобальная блокировка receiver (JVM/transport CI `42726725734`)
- [x] 12 UI гонки picker/export/logout и удаление устаревших контролов после выхода (статический/Android compile CI `42726725734`)
- [x] 13 **НОВОЕ:** исчезновение ранее существовавшего `remote.unique_id` при локальной финальной проверке вызывает отказ и сохраняет возможность возобновления (JVM integration CI `42726725734`; коммиты `160df003`, `f852b8ef`, `e2347e87`)
- [ ] 14 Android emulator принудительное завершение/холодный перезапуск native TDLib без аккаунта: добавлена проверка `0e783913`, ожидается реальный результат `42727099985`
- [ ] 15 Android Keystore восстановление с сохранённой сессией после реального убийства процесса и очисткой orphan files
- [ ] 16 Изолированный Telegram test-account: сетевой вход, 2FA/email и подтверждённый logout
- [ ] 17 Настоящий Telegram test-account: end-to-end сообщения+медиа, байты, полнота и повтор после обрыва
- [ ] 18 Длительный многогигабайтный экспорт с Android Doze/background, memory/storage pressure на физическом устройстве
- [ ] 19 Импорт/чтение `.tva` в Telegram Archive Viewer и проверка сквозного чтения
- [ ] 20 Независимый security/privacy review, стабильная release-подпись и release acceptance

**Следующее действие:** проверить два jobs CI `42726725734` (последний код ExportEngine + регрессионные тесты) и три jobs source-built APK `42727099985`. Не засчитывать 03,04,14 до успеха полного Android-emulator CI. Эти проценты строго привязаны к неизменному перечню 01–20 и не могут заменять реальный итоговый E2E.

## Проверка результатов прерванной итерации — 11 ноября 2026

Подтверждено повторным чтением GitHub Actions, а не предположениями:
- CI по последнему функциональному коду: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42726725734 — **success**, Android compile и полный набор JVM/статических регрессий, включая потерю `remote.unique_id` и безопасное продолжение.
- Полная native QA APK проверка: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/42727099985, SHA исходников `c0752b8e110887526b393a74b0337e405dbecf0f` — **все три jobs success**: `runtime-apk`, `offline-emulator-smoke`, `offline-synthetic-export`.
- В логе `offline-emulator-smoke` присутствует: `PASS: three offline TDLib diagnostics including real Android process force-stop/relaunch, no account`. Это **реальный Android emulator force-stop/relaunch** без аккаунта, но НЕ тест сохранения сессии/Keystore и НЕ SIGKILL во время активного экспорта.
- В логе `offline-synthetic-export` присутствует: `PASS: emulator created fictional ZIP with 60 messages and 7 media, no Telegram network`; файл был создан, прочитан и проверен в эмуляторе.
- Сравнение `c0752b8e1108..qa/recovery-audit-20261109` на момент проверки: 1 дополнительный коммит, изменён только **этот Markdown-паспорт**. Следовательно APK CI охватывает весь текущий функциональный код; обновления паспорта не требуют пересборки TDLib.

### Обновлённая постоянная матрица — ровно те же 20 пунктов
- **Было:** 11/20 = 55% QA-контрольного списка.
- **Теперь:** 14/20 = **70% QA-контрольного списка**, изменение **+3 закрытых пункта / +15 процентных пунктов**.
- Переведены в [x] пункты **03** (сборка official TDLib и подписанного QA APK), **04** (Android offline synthetic export), **14** (force-stop/relaunch native TDLib без аккаунта). Все — зафиксированные результаты CI `42727099985`.
- Всё ещё [ ]: **15** Android Keystore/session recovery при убийстве процесса + очистка orphan файлов на эмуляторе/телефоне, **16** живой тестовый Telegram login/2FA/logout, **17** реальный end-to-end сообщений и медиа, **18** долгий Android background/doze/storage тест, **19** TVA в Viewer, **20** независимый security review, постоянная подпись и release acceptance.
- **Важно:** 70% — НЕ объективная продуктовая готовность, не доказанная безопасность и не повод использовать основную переписку. При прохождении новых пунктов прибавлять по 5 процентных пунктов, не изменяя задним числом список. Текущая версия остается `0.9-test-gated-alpha`, `main` неизменён, PR #4 остаётся draft.
- Продолжать с пункта **15**: в APK/эмуляторе протестировать именно Android Keystore + восстановление зашифрованной checkpoint-пары и очистку остаточного TDLib cache после внезапной остановки; не подменять его уже проверенным cold restart без авторизации.


## Итерация 11 ноября 2026 — Android Keystore + SIGKILL recovery, пункт 15 (в проверке)
- Начальный статус без переоценки: **14/20 = 70%** фиксированных контрольных задач. Пункт 15 на момент этой записи **[ ]**, пока Android emulator job не завершился SUCCESS.
- Создан отдельный `app/src/androidTest/java/org/vault/exporter/QaRecoveryInstrumentation.java`, который **не входит в пользовательский APK**. Два независимых этапа (prepare/verify) через Android Instrumentation проверяют реальный Android Keystore и повторное чтение `ResumeStore` после смены процесса. Подготовка записывает зашифрованный checkpoint, страницу, медиа, remote identity; размещает тестовые аварийные `.pending-*.tmp` и plaintext в отдельном TDLib download-каталоге. Проверка сравнивает расшифрованные байты, проверяет удаление abandoned staging, запускает официальный native `TdGateway` и убеждается, что удалён только TDLib downloads, а файл-свидетель в TDLib database сохранён.
- `ci/qa-keystore-force-stop-probe.py` через ADB сначала завершает процесс, запускает prepare, затем реальную Activity, выполняет `am force-stop` и отдельный verify (строгий разбор результата instrumentation). Отключает мобильную сеть и Wi-Fi до проверок. Проверка без аккаунта.
- В `app/build.gradle` инструментальный тест ограничен `testBuildType 'qaRuntime'` и кастомным `testInstrumentationRunner`; не допускается в релизный APK. `.github/workflows/qa-runtime-apk.yml` собирает подписанный отдельный test APK и скачивает его в emulator smoke; оба APK подписаны QA debug-key, но это НЕ постоянная релизная подпись. Новая компиляция вынесена в быстрый `qa-android-compile.yml`.
- Коммиты: `ef37aee0` Android instrumentation; `b38e9695` test build type; `22a8d36c` ADB orchestration; `c9882057` real APK emulator CI; `08cc7043` CI compile; `297187ab` корректная обработка `pidof` после force-stop.
- **Подтверждённо SUCCESS:** https://github.com/Caprifan2078/vault-exporter-android/actions/runs/43012041444 (оба jobs, включая отдельный compile `QaRuntimeAndroidTestJavaWithJavac`). Полный APK+instrumentation+emulator: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/43012321809 — результат ещё не подтверждён.
- Остальные пробелы: реальный Telegram E2E и пользовательская сохранность данных не проверены; `am force-stop` в эмуляторе не тождественен Android kill во время активной многогигабайтной выгрузки.
- Следующее: получить полный список jobs `43012321809`. При красном результате точно локализовать шаг в логах; при success отметить 15 = [x] и **15/20 = 75% QA**, но это не 75% гарантированной безопасности/готовности продукта. `main` не менять, PR #4 держать draft.

## Продолжение аудита, 11 ноября 2026 — подтверждение logout по auth state

**Подтверждённый дефект логики до исправления:** `MainActivity.confirmLogout()` считал успешный ответ на TDLib `logOut` достаточным доказательством закрытия авторизованного клиента, после чего удалял локальный Keystore wrapping-key и зашифрованный resume. Асинхронное состояние TDLib при этом могло ещё оставаться `authorizationStateLoggingOut`/Closing. Это не подтверждённая утечка, а незащищённый порядок действий.

- `TdGateway` теперь отдельно отслеживает переход `authorizationStateLoggingOut` → `authorizationStateClosed` на том же native client. Метод `awaitLogoutClosed(timeout)` завершается только после указанной пары. Простое `close()` или преждевременный выход event pump не считаются завершённым лог-аутом; ожидающий запрос получает ошибку/тайм-аут, а секреты остаются на месте.
- `MainActivity`: проверяется `@type=ok` на запрос `logOut`, затем `awaitLogoutClosed(120)`, **лишь после этого** `telegramLogoutConfirmed=true`, удаление прогресса и ключа. При ненадёжном статусе удаления нет.
- Код: `afe52e26d151edd77913a0e2c0bb4ce0cce9f663`, `f05684ff4c69a4c13e667bba5575cc2fa63f3a07`.
- Тест: `e9a87f2828cd42453042f7f271b62faa83d195e7` — fake TDLib проверяет, что native close и один LoggingOut без Closed не подтверждают logout, а полный переход принимается. Статический fail-closed CI gate: `15d0ad4198aa84e63d83104d73d270af1799205c`.
- **Подтверждено:** https://github.com/Caprifan2078/vault-exporter-android/actions/runs/43013643964 — JVM/transport fake TDLib, статические проверки и Android compile (включая Android instrumentation) — оба jobs **SUCCESS**. Полный native APK+Android Keystore emulator: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/43013529602 — в момент последней проверки ещё собирает pinned TDLib, итог не подтверждён. Предыдущий APK `43012321809` отменён по concurrency при изменении функционального кода, это не ошибка теста.
- **Пункт 15 не закрыт** до реального успеха Android Keystore crash-probe в emulator; текущая фиксированная метрика **14/20 = 70%**, +0 официально закрытых пунктов. Проверка logout на настоящем аккаунте (#16) также не сделана.
- Особое ограничение: TDLib может отдавать события с непредусмотренной последовательностью; в этом случае приложение теперь останавливает удаление ключей, а не обещает корректный logout. Нужен controlled disposable-account test.

## Проверка 15 закрыта — Android Keystore после force-stop (15 ноября 2026)

**Подтверждено логами завершённой GitHub Actions #43013529602 (SHA исходников `f05684ff4c69a4c13e667bba5575cc2fa63f3a07`):**
- `runtime-apk`: **SUCCESS** — official pinned TDLib из исходников, подписанная QA APK и изолированный androidTest APK.
- `offline-emulator-smoke`: **SUCCESS**, в реальном эмуляторе Android выполнены отдельно instrumentation `prepare` и `verify` после принудительной остановки процесса (`am force-stop`). Содержит точные сообщения `PASS: actual Android Keystore instrumentation prepare phase` и `PASS: actual Android Keystore instrumentation verify phase`.
- Подтверждено: прежний wrapping key из Android Keystore позволяет прочитать encrypted checkpoint/page/media, encrypted orphan pending удаляется при восстановлении, plaintext download кэш TDLib очищается при безопасном новом старте native, тестовый файл в TDLib database сохраняется. Конкретный emulator может иметь программную реализацию Keystore — **не гарантия аппаратно защищённого ключа**.
- `offline-synthetic-export`: **SUCCESS** — fictional 60 сообщений + 7 медиа без Telegram сети.

**Постоянная матрица из 20 пунктов:**
- Пункт **15 [x]**: *Android Keystore восстановление с сохранённой сессией/зашифрованным staging после реального force-stop и очисткой orphan files* — засчитано **на уровне отдельного emulator QA-сценария без реальной авторизации**, не включает kill во время настоящей загрузки и не является реальным Telegram-session recovery.
- Предыдущие 14/20 (70%) → **15/20 (75%)**, +1 задача, **+5 процентных пунктов**. Это ТОЛЬКО измерение выполнения QA матрицы, не 75% безопасность приложения.
- Открыты **16** live test-account login/2FA/logout, **17** live end-to-end export/resume/multimedia, **18** foreground/background/doze и multi-GB на физическом телефоне, **19** интеграция TVA в Viewer, **20** независимый security-review + permanent release-signing/acceptance.
- Следующее: продолжить независимый аудит и подготовить безопасный воспроизводимый live-account тест (без чужих личных данных); не закрывать пункт 16 до настоящих результатов. Полный источник: https://github.com/Caprifan2078/vault-exporter-android/actions/runs/43013529602 .
- На дату этого checkpoint функциональный SHA `f05684ff`; после него только тесты/документация — перед релизом проверить сравнение SHA и CI. `main` не затрагивать, PR #4 держать draft.

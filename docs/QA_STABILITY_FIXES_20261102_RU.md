# Vault Exporter — QA стабильность, 02.11.2026

Рабочая ветка: `qa/disposable-real-telegram-20261102`. `main` не изменён.

## Исправления
- QA-вход в настоящий Telegram требует успешной локальной JNI/TDLib самопроверки до запроса номера. Функция сама не устанавливает параметры TDLib и не подключается к Telegram.
- Исправлен fake TDLib: `downloadFile` теперь возвращает file_id и длину; `cancelDownloadFile`, `deleteFile`, `getFile` реально моделируют очистку приватного кэша. Ранее устаревший fake ломал тесты.
- Уточнены ошибки скрытых платных медиа и фото чатов.
- Исправлен случай, когда неизвестный/некорректный `link_preview` без доступного оригинала мог пропасть без ошибки. Обрабатывается `type.@type`, `@type`, известные немедийные статьи; остальные неизвестные варианты без медиа прекращают экспорт.
- Добавлены негативные проверки nested/missing type и положительная проверка `linkPreviewTypeArticle`.

## Фактически прошедшие проверки (без Telegram)
- `bash integration-jvm/RUN_INTEGRATION_JVM.sh`: EXIT 0; 250 и 2 000 сообщений, три обрыва, восстановление, 8 типов вложений, 48 МиБ при 32 МиБ heap, SHA-256/архив/пароль, неизвестные медиа.
- `python3 tests/RUN_QA_PRODUCTION_SAFETY_TESTS.py`: PASS
- `python3 scripts/security_gate.py`: PASS, статический контракт
- `python3 tests/RUN_BUILD_SAFETY_TESTS.py`: PASS
- `python3 tests/RUN_ACTIVITY_SELECTION_SAFETY_TESTS.py`: PASS
- `python3 ci-contract/verify_ci_contract.py`: PASS
- `bash transport-jvm/RUN_TRANSPORT_JVM.sh`: PASS
- `bash native-host-test/RUN_NATIVE_HOST_TEST.sh`: PASS
- Общий `RUN_ALL_OFFLINE.sh` вывел PASS по всем подгруппам, но единый exit-код оболочки не зафиксирован.

## Не готово
Реальная Android APK не собрана и не запущена: в рабочей среде нет Android SDK/NDK/Gradle и не настроены собственные Telegram API ID/hash для приватной CI сборки. Невозможно подтвердить фактический вход, работу облачного Telegram и совместимость `.tva` с отдельной читалкой. До проверки настоящей APK не приобретать одноразовый аккаунт и не пополнять площадку на 100 ₽.
# TG WS Proxy Android

Android-приложение для обхода блокировок Telegram через локальный MTProto-прокси с WebSocket-туннелированием.

## Как это работает

```
Telegram → MTProto Proxy (127.0.0.1:1443) → WebSocket (TLS) → Telegram DC
```

1. Приложение поднимает локальный MTProto-прокси на `127.0.0.1:1443`
2. Telegram подключается к этому прокси
3. Прокси определяет DC ID из MTProto handshake
4. Устанавливает WebSocket-соединение (TLS) к соответствующему Telegram DC
5. Передаёт трафик в зашифрованном виде

## Архитектура

- **Frontend**: Jetpack Compose с тёмной темой
- **Service**: Foreground Service для работы прокси в фоне
- **Proxy Engine**: Kotlin-реализация MTProto-WS bridge

## Сборка

### Требования
- Android Studio Hedgehog (2023.1.1) или новее
- JDK 17
- Android SDK 34

### Сборка через Android Studio
1. Откройте проект в Android Studio
2. Дождитесь синхронизации Gradle
3. Нажмите `Run` (▶) или соберите APK через `Build → Build Bundle(s) / APK(s)`

### Сборка через командную строку
```bash
./gradlew assembleDebug
```

## Использование

1. Запустите приложение
2. Нажмите **"Запустить прокси"**
3. Нажмите **"Подключить Telegram"** — откроется Telegram с автоматической настройкой прокси
4. Или скопируйте ссылку и отправьте себе в Telegram, затем нажмите на неё

### Ручная настройка Telegram
1. Telegram → **Настройки** → **Данные и память** → **Настройки прокси**
2. Добавьте прокси:
   - **Тип:** MTProto
   - **Сервер:** `127.0.0.1`
   - **Порт:** `1443`
   - **Секрет:** из приложения

## Возможности

- [x] Локальный MTProto-прокси
- [x] WebSocket-туннелирование к Telegram DC
- [x] TCP fallback если WS недоступен
- [x] Генерация случайного секрета
- [x] Foreground service (прокси работает в фоне)
- [x] Jetpack Compose UI
- [x] Deeplink в Telegram (`tg://proxy?...`)
- [x] Логи подключений

## Технологии

- Kotlin
- Jetpack Compose
- Coroutines
- OkHttp (WebSocket)
- AES-CTR шифрование

## Вдохновение

- [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — оригинальная Python-реализация
- [MTProxy](https://github.com/TelegramMessenger/MTProxy) — официальный MTProto-прокси

## Лицензия

MIT

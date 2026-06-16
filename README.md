# TG WS Proxy Android

Android-приложение для обхода блокировок. Состоит из двух независимых модулей:

1. **Telegram-прокси** — локальный MTProto-прокси с WebSocket-туннелированием к Telegram DC.
2. **Разблокировка сайтов** — DPI-desync VPN в стиле ByeDPI для обхода блокировок YouTube, Discord, Instagram и др. без внешнего сервера.

## 1. Telegram-прокси

### Как это работает

```
Telegram → MTProto Proxy (127.0.0.1:1443) → WebSocket (TLS) → Telegram DC
```

1. Приложение поднимает локальный MTProto-прокси на `127.0.0.1:1443`
2. Telegram подключается к этому прокси
3. Прокси определяет DC ID из MTProto handshake
4. Устанавливает WebSocket-соединение (TLS) к соответствующему Telegram DC
5. Передаёт трафик в зашифрованном виде (AES-CTR)

### Использование

1. Откройте вкладку **Telegram**
2. Нажмите **«Запустить прокси»**
3. Нажмите **«Подключить Telegram»** — откроется Telegram с автоматической настройкой прокси
4. Или скопируйте ссылку и отправьте себе в Telegram, затем нажмите на неё

### Ручная настройка Telegram

1. Telegram → **Настройки** → **Данные и память** → **Настройки прокси**
2. Добавьте прокси:
   - **Тип:** MTProto
   - **Сервер:** `127.0.0.1`
   - **Порт:** `1443`
   - **Секрет:** из приложения

## 2. Разблокировка сайтов (DPI-desync VPN)

### Как это работает

Локальный `VpnService` перехватывает исходящий TCP-трафик и манипулирует первыми байтами TLS-рукопожатия (ClientHello), чтобы DPI (например, ТСПУ) не мог прочитать SNI и заблокировать/замедлить соединение. Внешний сервер при этом получает корректное TLS-рукопожатие — меняется только то, как байты фрагментируются и упорядочиваются на проводе. Трафик **никуда не туннелируется** — обработка идёт локально на устройстве.

Доступные методы десинхронизации:
- **SPLIT** — разрезание TCP-полезной нагрузки на два сегмента внутри SNI
- **TLSREC** — рефрагментация TLS-записи на две валидные записи по границе SNI
- **DISORDER** — как SPLIT, но второй сегмент отправляется первым

### Возможности

- Автоподбор стратегии обхода (параллельные TLS-пробы по хостам, выбор рабочей)
- Список исключений — «Не использовать обход для…» выбранных приложений
- Автозапуск VPN после перезагрузки (если был включён)
- Плитка в шторке (Quick Settings) для быстрого включения/выключения

### Использование

1. Откройте вкладку **Разблокировка**
2. Нажмите включить — система запросит разрешение на VPN
3. Дождитесь автоподбора стратегии; активная стратегия отображается в статусе

## Архитектура

- **Frontend**: Jetpack Compose, тёмная тема, две вкладки (Telegram / Разблокировка)
- **Services**:
  - `ProxyService` — foreground service Telegram-прокси
  - `DesyncVpnService` — foreground VpnService для DPI-desync
  - `ProxyTileService` / `DesyncTileService` — плитки Quick Settings
- **Proxy Engine**: Kotlin-реализация MTProto-WS bridge (`proxy/`)
- **Desync Engine**: dependency-free DPI-desync ядро (`desync/`, `vpn/`, `net/`)

## Сборка

### Требования
- Android Studio Hedgehog (2023.1.1) или новее
- JDK 17
- Android SDK 35 (minSdk 26, targetSdk 35)

### Через Android Studio
1. Откройте проект, дождитесь синхронизации Gradle
2. Нажмите `Run` (▶) или соберите APK через `Build → Build Bundle(s) / APK(s)`

### Через командную строку
```bash
./gradlew assembleDebug
```

## Технологии

- Kotlin + Coroutines
- Jetpack Compose
- OkHttp (WebSocket)
- VpnService (TUN-перехват TCP/UDP)
- AES-CTR шифрование (MTProto)

## Вдохновение

- [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — оригинальная Python-реализация WS-прокси
- [MTProxy](https://github.com/TelegramMessenger/MTProxy) — официальный MTProto-прокси
- [ByeDPI](https://github.com/hufrea/byedpi) — техники DPI-десинхронизации

## Лицензия

MIT

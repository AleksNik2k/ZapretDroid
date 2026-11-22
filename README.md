# ZapretDroid

Android-порт идей и стратегий
[Flowseal/zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube)
и [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
Приложение помогает обходить DPI-блокировки для YouTube, Discord, Telegram и других сервисов
через локальный Android `VpnService`. Root не требуется.

## Возможности

- Запуск и остановка VPN-перехода через системный диалог разрешения Android
- IPv4/IPv6 TCP и UDP проксирование через локальный TUN-интерфейс
- Набор DPI-стратегий для YouTube, Discord, Google, Telegram и игр
- Доменные списки для Google и General-категорий
- Исключения для доменов
- Пользовательские доменные и IP/CIDR списки
- IPSet-фильтрация и отдельный режим фильтрации игр/UDP
- Обновление общих списков из репозитория Flowseal
- Повторная сборка фрагментированных IP-пакетов и проверка транспортных контрольных сумм
- Telegram WebSocket-прокси на локальном порту `1443`
- Автозапуск VPN после загрузки устройства
- Журнал работы с возможностью очистки
- Постоянное уведомление о состоянии VPN
- Поддержка системных разрешений Android 23–36

## Требования

- Android 6.0 и новее (`minSdk 23`)
- Android SDK 36 для сборки
- JDK 21 для совместимости с проектной toolchain
- Android Gradle Plugin 8.13.0
- Доступ к интернету для VPN-соединения и обновления списков

## Установка

1. Скачайте APK со [страницы релизов](https://github.com/AleksNik2k/ZapretDroid/releases).
2. Разрешите установку приложения из неизвестного источника, если Android запросит подтверждение.
3. Откройте приложение.
4. При первом запуске разрешите создание VPN-соединения.
5. На Android 13 и новее разрешите уведомления — это необязательное разрешение для системного уведомления VPN.
6. Выберите стратегию и нажмите **Запустить**.

Не все стратегии работают одинаково с разными провайдерами, регионами и узлами сети.
Если выбранная стратегия не работает, попробуйте другой пресет.

## Разрешения

Приложение объявляет и запрашивает только необходимые для работы разрешения:

| Разрешение | Назначение | Версии Android |
|---|---|---|
| `INTERNET` | Установка VPN-соединений и загрузка локального прокси-трафика | Все |
| `POST_NOTIFICATIONS` | Показ системного уведомления состояния VPN | Android 13+ |
| `FOREGROUND_SERVICE` | Запуск foreground-службы VPN | Android 9+ |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Тип foreground-службы для специального VPN-сервиса | Android 14+ |
| `RECEIVE_BOOT_COMPLETED` | Автозапуск, если пользователь включил соответствующую настройку | Android 6+ |

Разрешение VPN запрашивается интерактивно через стандартный Android `VpnService.prepare`.
При включенном автозапуске служба не запускается, если разрешение VPN ещё не выдано.

## Настройки приложения

### Списки

Кнопка **Списки** открывает редактор пользовательских значений:

- дополнительные домены General
- дополнительные домены Google
- исключённые домены
- дополнительные IP/CIDR
- исключённые IP/CIDR

Доменные записи поддерживают поддомены. Например, запись `discord.com`
также охватывает `*.discord.com`.

Кнопка **Обновить** загружает актуальные общие списки
[оригинального проекта](https://github.com/Flowseal/zapret-discord-youtube).
Пользовательские записи сохраняются отдельно и не перезаписываются обновлением.

### IPSet-фильтр

IPSet-фильтр принимает IPv4 и IPv6 CIDR, игнорируя тестовые IPv4-сети из документации.
В поставляемом `ipset-all.txt` намеренно нет фиктивных адресов; загрузите список
из репозитория Flowseal или добавьте свои диапазоны.

### Фильтр игр/UDP

Режим распознаёт UDP-портовые диапазоны Discord/Gaming:

- `19294–19344`
- `50000–50100`

Сейчас UDP-пакеты передаются без изменения; распознавание не включает raw UDP desync.

## Стратегии

Таблица отражает названия и параметры исходных пресетов Flowseal, а не гарантирует
реализацию каждой исходной техники в Android-порту. Работоспособность зависит от
провайдера и региона.

Текущий TCP relay использует обычные Android sockets и может только разделять первые
данные соединения на несколько stream-записей. ОС может объединить их в TCP-сегменты.
Поддельные raw-пакеты с контролируемыми TCP sequence numbers и UDP fake-пакеты не
отправляются: Android `Socket` не предоставляет такой возможности. Поэтому техники
Fake, BadSeq, Disorder и другие raw packet desync из таблицы здесь не эквивалентны
реализации `nfqws`.

| Стратегия | Основная техника |
|---|---|
| General | Исходный пресет: Fake QUIC, 6 повторов |
| ALT 1 | Multisplit seqovl=681 |
| ALT 2 | Fake + Multisplit seqovl=652 |
| ALT 3 | Hostfakesplit + BadSeq |
| ALT 4 | Syndata + Multidisorder |
| ALT 5 | BadSeq increment=1000 |
| ALT 6 | Fake + Fakedsplit seqovl=664 |
| ALT 7 | Multisplit seqovl=568 + TS fooling |
| ALT 8 | Fake + Discord L7 |
| ALT 9 | Any-protocol + cutoff=n2 |
| ALT 10 | Multisplit midsld |
| ALT 11 | Fake TLS MAX.RU |
| ALT 12 | Fake + Multisplit sniext+1 |
| ALT 13 | Fake + Hostfakesplit |
| EXP | Experimental QUIC/STUN/UDP режим |
| Fake TLS AUTO | Рандомный TLS ClientHello |
| Fake TLS AUTO DupSID | Рандомный TLS с дублированным Session ID |
| Fake TLS AUTO ALT / ALT 2 / ALT 3 | Альтернативные Random/DupSID пресеты |
| Simple Fake | Только fake-пакет |
| Simple Fake ALT / ALT 2 | Альтернативные упрощённые fake-пресеты |
| Simple Fake UDP | UDP/QUIC fake-пакет |
| Google IP-ID Zero | Multisplit с IP-ID=0 |

## Telegram

Для Telegram обхода включите переключатель **Telegram обход**, запустите VPN и нажмите
**Подключить**, когда кнопка станет доступной. Приложение откроет Telegram-ссылку
на локальный MTProto-прокси `127.0.0.1:1443`.

## Сборка

### Локальная сборка

```bash
bash gradlew :app:assembleDebug
```

### Unit-тесты

```bash
bash gradlew :app:testDebugUnitTest
```

### Android lint

```bash
bash gradlew :app:lintDebug
```

Результаты сборки:

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Отчёт lint: `app/build/reports/lint-results-debug.html`
- Отчёт unit-тестов: `app/build/reports/tests/testDebugUnitTest/`

## Структура проекта

- `app/src/main/java/com/zapret/droid/proxy` — TUN-пакеты, TCP/UDP прокси и DPI-техники
- `app/src/main/java/com/zapret/droid/service` — VPN-служба и автозапуск
- `app/src/main/java/com/zapret/droid/strategies` — пресеты и модели стратегий
- `app/src/main/java/com/zapret/droid/ui` — адаптеры списков и журналов
- `app/src/main/assets/lists` — встроенные доменные и IP списки
- `app/src/main/assets/fake_packets` — исходные fake-пакеты (не отправляются текущим relay)
- `app/src/test` — unit-тесты сетевых пакетов, фрагментации и классификации UDP-трафика

## Ограничения

- TCP relay реализует упрощённое состояние соединения и не заменяет полноценный TCP/IP stack
- Android не предоставляет WinDivert и не позволяет полностью эмулировать все raw-пакетные
  функции nfqws; raw TCP/UDP desync требует отдельного native/backend решения
- Для точного определения домена используется SNI или HTTP `Host`
- Работоспособность стратегий зависит от настроек DPI конкретного провайдера

## Распространённые проблемы

### Не работает YouTube

- Проверьте Secure DNS в браузере или системных настройках Android
- Переберите стратегии по порядку
- Проверьте пользовательские Google-списки
- Попробуйте пресеты с пометкой `Fake TLS AUTO`

### Не работает Discord

- Сначала найдите рабочую стратегию для YouTube
- Для голосовых соединений попробуйте `ALT 8`
- Включите режим `Фильтр игр/UDP`
- Проверьте Discord в мобильном браузере

### Не работает Telegram

- Включите переключатель `Telegram обход`
- Запустите VPN
- Нажмите `Подключить`
- Если Telegram не установлен, приложение предложит открыть прокси-ссылку через браузер

### Перестал работать обход

- Попробуйте другую стратегию
- Перезапустите приложение
- Обновите общие списки
- Проверьте Secure DNS
- Проверьте пользовательские исключения

### Не работают игры

- Включите режим `Фильтр игр/UDP`
- Попробуйте `ALT 9`
- Добавьте IP/CIDR или домены нужного сервиса через раздел `Списки`

## Добавление доменов в исходные файлы

Базовые списки находятся в `app/src/main/assets/lists/`:

- `list-general.txt` — Discord, CDN, Telegram и игровые сервисы
- `list-google.txt` — YouTube, Google и связанные домены
- `list-exclude.txt` — исключённые домены
- `ipset-all.txt` — начальный список IP/CIDR (из коробки пустой)
- `ipset-exclude.txt` — исключённые IP/CIDR

Поддомены учитываются автоматически.

Постоянные пользовательские изменения удобнее сохранять через кнопку **Списки**:
они не перезаписываются при сборке или обновлении общих списков.

## Поддержка проекта

Поставьте ⭐ репозиторию, если приложение вам помогло.

Поддержите оригинального разработчика zapret:
[bol-van/zapret](https://github.com/bol-van/zapret?tab=readme-ov-file#поддержать-разработчика).

## Лицензирование

Проект распространяется на условиях лицензии MIT.

## Благодарность

- [Flowseal](https://github.com/Flowseal) — исходные стратегии и fake-пакеты
- [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — идея Telegram WebSocket-прокси
- [bol-van/zapret](https://github.com/bol-van/zapret) — оригинальная реализация DPI-desync

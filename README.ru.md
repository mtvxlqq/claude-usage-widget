<div align="center">

[English](README.md) · **Русский**

# ● Claude Usage Widget

**Android-виджет в стиле Nothing, который показывает лимиты Claude прямо на домашнем экране**

5-часовая сессия · недельный лимит · время до сброса · токены Claude Code

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Python](https://img.shields.io/badge/Python-3.8%2B-3776AB?logo=python&logoColor=white)
![Dependencies](https://img.shields.io/badge/зависимости-0-black)
![APK](https://img.shields.io/badge/APK-~26_КБ-black)

</div>

---

<div align="center">

<img src="docs/screenshot.png" alt="Claude Usage Widget на домашнем экране Android" width="340">

<sub>Виджет на домашнем экране: слева 5-часовая сессия, справа недельный лимит.</sub>

</div>

## ✦ Возможности

- **Оба лимита сразу** — процент использования 5-часовой сессии и недели в виде точечных шкал.
- **Живой обратный отсчёт** до сброса сессии. Таймер тикает силами лаунчера — приложение при этом спит и не тратит батарею.
- **Токены Claude Code** за текущее окно (всего и output), посчитанные по локальным логам.
- **Работает вне дома** — телефон ходит на ноутбук через [Tailscale Funnel](https://tailscale.com/kb/1223/funnel) по HTTPS, порты в локальной сети открывать не нужно.
- **Офлайн-устойчивость** — без связи виджет показывает последние данные и понятный статус ошибки.
- **Минимализм** — ни одной сторонней зависимости ни на сервере (чистая stdlib Python), ни в приложении (чистый Android SDK). Release-APK весит около 26 КБ.

## ⚙ Как это устроено

```
 ┌─────────────┐   HTTPS + Bearer   ┌──────────────────┐        ┌──────────────────────────────┐
 │   Телефон   │ ─────────────────▶ │ Tailscale Funnel │ ─────▶ │  127.0.0.1:8765              │
 │   (виджет)  │                    │  *.ts.net        │        │  claude_usage_server.py      │
 └─────────────┘                    └──────────────────┘        └──────────────┬───────────────┘
                                                                               │
                                ┌──────────────────────────────────────────────┤
                                ▼                                              ▼
                 api.anthropic.com/api/oauth/usage              ~/.claude/projects/**/*.jsonl
                 лимиты в %, время сброса                       токены Claude Code
                 (кэш 60 с, OAuth-токен Claude Code)            (читаются инкрементально)
```

**Сервер** (`server/claude_usage_server.py`) работает на компьютере, где установлен Claude Code:

- берёт OAuth-токен из `~/.claude/.credentials.json` и запрашивает у Anthropic текущие лимиты — не чаще раза в минуту;
- считает токены по транскриптам Claude Code. Файлы `*.jsonl` только дописываются, поэтому после первого прохода сервер дочитывает лишь новые строки с запомненной позиции; дубли реплик от стриминга отбрасываются;
- если Anthropic недоступен или токен истёк — отдаёт последние известные лимиты вместе с описанием ошибки;
- слушает только `127.0.0.1` и не запускается без ключа доступа. Ключ сравнивается за постоянное время и не попадает в логи.

**Приложение** (`android/`) — виджет и экран настроек:

- обновляется раз в 30 минут через `JobScheduler`, по нажатию на виджет и точно в момент сброса лимита;
- переживает перезагрузку телефона;
- хранит последний ответ сервера и показывает его, пока нет связи.

## 🚀 Установка

> [!TIP]
> **Подробное пошаговое руководство с нуля — в [INSTALL.ru.md](INSTALL.ru.md).** Там установка Claude Code, Tailscale и Android SDK, установка APK на телефон и решение типичных проблем. Ниже — краткая версия для тех, у кого всё уже стоит.

### Что понадобится

| Где | Что |
|---|---|
| Компьютер | Linux с `systemd`, Python 3.8+, [Claude Code](https://claude.com/claude-code) с входом по подписке, [Tailscale](https://tailscale.com/download) |
| Телефон | Android 8.0+ |
| Сборка APK | Java (JRE) и Android SDK — JDK 21 Gradle скачает сам |

### 1. Сервер

Склонируй репозиторий в `~/development/claude-usage-widget` — этот путь указан в unit-файле. Если кладёшь проект в другое место, поправь `ExecStart` в `server/claude-usage-server.service`.

```bash
git clone https://github.com/mtvxlqq/claude-usage-widget.git ~/development/claude-usage-widget

# ключ доступа (создаётся один раз, файл доступен только владельцу)
[ -f ~/.config/claude-usage-server.env ] || \
  (umask 077; echo "CLAUDE_WIDGET_KEY=$(openssl rand -hex 16)" > ~/.config/claude-usage-server.env)

# автозапуск через systemd (пользовательский сервис)
mkdir -p ~/.config/systemd/user
cp ~/development/claude-usage-widget/server/claude-usage-server.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now claude-usage-server

# публикация наружу по HTTPS
sudo tailscale funnel --bg 8765
```

Сервер, Tailscale и Funnel после этого стартуют сами при включении компьютера.

> [!TIP]
> Чтобы сервис запускался сразу после загрузки, без входа в систему, включи lingering:
> `loginctl enable-linger $USER`

Проверка:

```bash
curl -H "Authorization: Bearer $(cut -d= -f2 ~/.config/claude-usage-server.env)" \
     https://<имя-машины>.<tailnet>.ts.net/usage
```

Адрес Funnel покажет `tailscale funnel status`.

### 2. Приложение

```bash
cd android
./gradlew assembleRelease
```

Готовый APK: `android/app/build/outputs/apk/release/app-release.apk`. Установи его на телефон (например, `adb install -r app-release.apk`).

> [!NOTE]
> Release-сборка подписана debug-ключом — это удобно для личного использования, но не подходит для публикации в магазинах.
> Путь к Android SDK задаётся в `android/local.properties` (`sdk.dir=...`) или переменной `ANDROID_HOME`.

### 3. Настройка

1. Добавь виджет **Claude Usage** на домашний экран — откроются настройки.
2. Укажи адрес Funnel (`https://<имя-машины>.<tailnet>.ts.net`) и ключ из `~/.config/claude-usage-server.env`.
3. Нажми **«Сохранить и проверить»**.
4. Нажми **«Разрешить работу в фоне»**, чтобы система не усыпляла обновления.

## 🔎 Статусы виджета

В правом нижнем углу виджета выводится состояние последнего обновления:

| Статус | Что значит | Что делать |
|---|---|---|
| `14:05` | время получения лимитов | всё в порядке |
| `···` | идёт обновление | подождать |
| `НЕТ СВЯЗИ · 14:05` | компьютер выключен или нет интернета | показаны данные на указанное время |
| `ТОКЕН ИСТЁК · 14:05` | Claude Code давно не запускался, OAuth-токен устарел | запустить `claude` на компьютере |
| `НЕВЕРНЫЙ КЛЮЧ` | ключ в приложении не совпадает с серверным | сверить с `~/.config/claude-usage-server.env` |
| `НУЖНА НАСТРОЙКА` | не указан адрес сервера | открыть приложение и заполнить настройки |
| `ОШИБКА СЕРВЕРА` | прочие ошибки | подробности — в приложении, «Последняя ошибка виджета» |

Логи сервера:

```bash
journalctl --user -u claude-usage-server -f
```

## 📡 API сервера

`GET /usage` с заголовком `Authorization: Bearer <ключ>` (или параметром `?key=<ключ>`):

```json
{
  "session": { "percent": 42, "resets_at": 1761832800, "tokens": 1200000, "output_tokens": 38400 },
  "weekly":  { "percent": 17, "resets_at": 1762149600, "tokens": 8700000, "output_tokens": 251000 },
  "updated_at": 1761821100,
  "error": { "code": "auth", "message": "токен Claude Code истёк — запусти claude на ноутбуке" }
}
```

| Поле | Описание |
|---|---|
| `session`, `weekly` | окно лимита; `null`, если окно ещё не началось |
| `percent` | использование лимита в процентах |
| `resets_at` | время сброса, Unix-секунды |
| `tokens`, `output_tokens` | токены Claude Code за окно (все / только output) |
| `updated_at` | когда лимиты получены от Anthropic |
| `error` | есть, только если лимиты не обновились и отданы последние известные |

Коды ответа: `403` — неверный ключ, `503` — лимиты ещё ни разу не удалось получить, `500` — внутренняя ошибка.

Переменные окружения сервера: `CLAUDE_WIDGET_KEY` (обязательна), `HOST` (по умолчанию `127.0.0.1`), `PORT` (по умолчанию `8765`).

## 📁 Структура проекта

```
claude-usage-widget/
├── server/
│   ├── claude_usage_server.py        # HTTP-сервер: лимиты + подсчёт токенов
│   └── claude-usage-server.service   # unit для systemd --user
└── android/
    ├── app/src/main/
    │   ├── java/dev/mtvxlqq/claudewidget/
    │   │   ├── UsageWidget.java      # отрисовка виджета, статусы
    │   │   ├── UsageClient.java      # запрос к серверу, хранение настроек
    │   │   ├── RefreshJob.java       # фоновые обновления через JobScheduler
    │   │   └── SettingsActivity.java # экран настроек
    │   └── res/                      # разметка, точечные шкалы, шрифт Doto
    └── OFL-Doto.txt                  # лицензия шрифта
```

## ⚠️ Дисклеймер

Это неофициальный проект, он никак не связан с Anthropic. Сервер обращается к недокументированному эндпоинту `api/oauth/usage`, который может измениться или перестать работать без предупреждения.

## 📜 Лицензии

Код проекта распространяется по лицензии [MIT](LICENSE).

Шрифт **Doto** © The Doto Project Authors, распространяется по [SIL Open Font License 1.1](android/OFL-Doto.txt). В APK включено только подмножество глифов.

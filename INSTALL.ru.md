[English](INSTALL.md) · **Русский**

# Установка Claude Usage Widget

Пошаговое руководство с нуля: от чистого компьютера до работающего виджета на телефоне.
В среднем занимает 20–30 минут, большая часть уходит на скачивание Android SDK.

**Содержание**

1. [Что получится в итоге](#1-что-получится-в-итоге)
2. [Сервер на компьютере](#2-сервер-на-компьютере)
3. [Сборка приложения](#3-сборка-приложения)
4. [Установка на телефон и настройка](#4-установка-на-телефон-и-настройка)
5. [Если что-то не работает](#5-если-что-то-не-работает)
6. [Обновление и удаление](#6-обновление-и-удаление)

---

## 1. Что получится в итоге

```
 Телефон ──HTTPS──▶ https://<машина>.<tailnet>.ts.net ──▶ сервер на компьютере ──▶ Anthropic
```

- На компьютере, где вы пользуетесь Claude Code, работает маленький сервер. Он узнаёт ваши лимиты у Anthropic и считает токены по логам Claude Code.
- Tailscale Funnel даёт серверу постоянный HTTPS-адрес в интернете. Телефону **не нужен** Tailscale, и он может быть в любой сети.
- Виджет раз в 30 минут (и по нажатию) забирает данные по этому адресу. Доступ закрыт секретным ключом.

### Требования

| | |
|---|---|
| **Компьютер** | Linux с `systemd` (проверено на Fedora; подойдут Ubuntu, Debian, Arch и др.) |
| **Claude** | подписка Pro или Max и Claude Code, в который выполнен вход через аккаунт claude.ai (а не по API-ключу) |
| **Телефон** | Android 8.0 или новее |
| **Аккаунт Tailscale** | бесплатный, вход через Google / GitHub / Microsoft и т. д. |

> [!IMPORTANT]
> Пока компьютер выключен или спит, виджет показывает последние полученные данные со статусом `НЕТ СВЯЗИ`.

---

## 2. Сервер на компьютере

### 2.1. Python

Сервер использует только стандартную библиотеку, ставить пакеты не нужно. Проверьте версию — подойдёт 3.8 и новее:

```bash
python3 --version
```

Если Python не установлен: `sudo dnf install python3` (Fedora) или `sudo apt install python3` (Ubuntu/Debian).

### 2.2. Claude Code

Если Claude Code уже установлен и вы в нём работаете — переходите к проверке ниже.

```bash
curl -fsSL https://claude.ai/install.sh | bash
claude
```

При первом запуске выберите вход через аккаунт Claude (подписку). Убедитесь, что появился файл с токеном:

```bash
ls -l ~/.claude/.credentials.json
```

> [!NOTE]
> Claude Code сам обновляет токен, пока вы им пользуетесь. Если не запускать его долго, токен истечёт и виджет покажет `ТОКЕН ИСТЁК` — достаточно один раз запустить `claude`.

### 2.3. Tailscale

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo systemctl enable --now tailscaled
sudo tailscale up
```

`tailscale up` выведет ссылку — откройте её в браузере и войдите в аккаунт. Проверьте, что машина в сети:

```bash
tailscale status
```

### 2.4. Скачивание проекта

Unit-файл сервиса рассчитан на путь `~/development/claude-usage-widget`, поэтому проще всего склонировать именно туда:

```bash
mkdir -p ~/development
git clone https://github.com/mtvxlqq/claude-usage-widget.git ~/development/claude-usage-widget
```

<details>
<summary>Хочу положить проект в другое место</summary>

Откройте `server/claude-usage-server.service` и поправьте путь в строке `ExecStart`:

```ini
ExecStart=/usr/bin/python3 -I %h/путь/к/проекту/server/claude_usage_server.py
```

`%h` — это ваша домашняя папка.
</details>

### 2.5. Секретный ключ

Ключ защищает ваши данные: без него сервер отвечает `403`. Создаём его один раз:

```bash
[ -f ~/.config/claude-usage-server.env ] || \
  (umask 077; echo "CLAUDE_WIDGET_KEY=$(openssl rand -hex 16)" > ~/.config/claude-usage-server.env)
```

Посмотреть ключ (он понадобится на телефоне):

```bash
cut -d= -f2 ~/.config/claude-usage-server.env
```

### 2.6. Запуск сервера как сервиса

```bash
mkdir -p ~/.config/systemd/user
cp ~/development/claude-usage-widget/server/claude-usage-server.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now claude-usage-server
```

Чтобы сервис запускался сразу при включении компьютера, ещё до входа в систему:

```bash
loginctl enable-linger $USER
```

Проверьте, что сервер работает и отвечает локально:

```bash
systemctl --user status claude-usage-server
curl -s -H "Authorization: Bearer $(cut -d= -f2 ~/.config/claude-usage-server.env)" \
     http://127.0.0.1:8765/usage
```

Должен прийти JSON с полями `session` и `weekly`.

### 2.7. Публикация через Tailscale Funnel

```bash
sudo tailscale funnel --bg 8765
```

Если Funnel в вашем tailnet ещё не включён, команда выведет ссылку на панель администратора — откройте её, разрешите HTTPS-сертификаты и Funnel, затем повторите команду.

Узнайте свой публичный адрес:

```bash
tailscale funnel status
```

Он выглядит как `https://<машина>.<tailnet>.ts.net`. Проверьте его извне:

```bash
curl -s -H "Authorization: Bearer $(cut -d= -f2 ~/.config/claude-usage-server.env)" \
     https://<машина>.<tailnet>.ts.net/usage
```

> [!TIP]
> Сразу после включения Funnel адрес может начать открываться не сразу — DNS и сертификату нужно до нескольких минут.

Флаг `--bg` сохраняет настройку: после перезагрузки Funnel поднимется сам. Сервер готов ✅

---

## 3. Сборка приложения

Сборка выполняется на компьютере, телефон для неё не нужен.

> [!TIP]
> Если в разделе **Releases** репозитория есть готовый `app-release.apk`, этот раздел можно пропустить и сразу перейти к [установке на телефон](#4-установка-на-телефон-и-настройка).

### 3.1. Java

Для запуска Gradle нужна Java 17 или новее. Полноценный JDK 21 для компиляции Gradle скачает сам.

```bash
java -version
```

Если Java нет:

```bash
sudo dnf install java-21-openjdk        # Fedora
sudo apt install openjdk-21-jre         # Ubuntu / Debian
```

### 3.2. Android SDK

Есть два пути — выберите один.

<details open>
<summary><b>Вариант А: только командная строка (≈ 1 ГБ)</b></summary>

1. Скачайте **Command line tools only** для Linux со страницы <https://developer.android.com/studio#command-line-tools-only>.
2. Распакуйте в нужную структуру папок:

   ```bash
   mkdir -p ~/Android/Sdk/cmdline-tools
   unzip ~/Downloads/commandlinetools-linux-*_latest.zip -d ~/Android/Sdk/cmdline-tools
   mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
   ```

3. Примите лицензии и поставьте нужные компоненты:

   ```bash
   export ANDROID_HOME=~/Android/Sdk
   $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
   $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
   ```
</details>

<details>
<summary><b>Вариант Б: через Android Studio</b></summary>

Установите [Android Studio](https://developer.android.com/studio), запустите и пройдите мастер первого запуска — он скачает SDK в `~/Android/Sdk`. Затем в **Settings → Languages & Frameworks → Android SDK** отметьте **Android 16 (API 36)**.

Проект можно открыть прямо в Studio (папка `android/`) и собрать оттуда — тогда шаги 3.3–3.4 не нужны.
</details>

### 3.3. Путь к SDK

Укажите Gradle, где лежит SDK. Файл `local.properties` не хранится в репозитории, его нужно создать:

```bash
cd ~/development/claude-usage-widget/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

### 3.4. Сборка

```bash
./gradlew assembleRelease
```

Первая сборка скачивает Gradle, JDK и плагины, поэтому занимает несколько минут. В конце должно появиться `BUILD SUCCESSFUL`, а файл приложения — здесь:

```
android/app/build/outputs/apk/release/app-release.apk
```

> [!NOTE]
> APK подписан стандартным debug-ключом вашего компьютера. Для личного использования этого достаточно. Если позже собрать APK на другом компьютере, поверх старого он не установится — сначала удалите приложение.

---

## 4. Установка на телефон и настройка

### 4.1. Установка APK

<details open>
<summary><b>Через USB-кабель (adb)</b></summary>

1. На телефоне включите режим разработчика: **Настройки → О телефоне** → 7 раз нажмите на **Номер сборки**.
2. В **Настройки → Для разработчиков** включите **Отладку по USB**.
3. Подключите телефон кабелем, подтвердите запрос на телефоне и выполните:

   ```bash
   ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
   ```
</details>

<details>
<summary><b>Без кабеля</b></summary>

Перешлите `app-release.apk` на телефон любым способом (мессенджер, облако, почта), откройте файл и разрешите установку из этого источника, когда Android спросит.
</details>

### 4.2. Добавление виджета

1. Долгое нажатие на пустом месте домашнего экрана → **Виджеты**.
2. Найдите **Claude Usage** и перетащите на экран. Размер по умолчанию — 4×2, его можно менять.
3. Сразу откроется экран настроек.

### 4.3. Подключение к серверу

| Поле | Что ввести |
|---|---|
| **Адрес сервера** | адрес Funnel из шага 2.7, например `https://fedora.tail1234.ts.net` |
| **Ключ** | значение из `~/.config/claude-usage-server.env` (без `CLAUDE_WIDGET_KEY=`) |

Нажмите **«Сохранить и проверить»**. При успехе экран закроется, и на виджете появятся проценты и таймер. При ошибке под кнопкой будет её текст — см. [раздел 5](#5-если-что-то-не-работает).

> [!TIP]
> Ключ длинный — удобнее не перепечатывать его, а отправить себе на телефон (например, в «Избранное» мессенджера) и вставить.

### 4.4. Работа в фоне

Откройте приложение **Claude Usage** из списка приложений и нажмите **«Разрешить работу в фоне»** → **Разрешить**. Без этого Android может надолго откладывать обновления. Когда разрешение выдано, кнопка пропадает.

На телефонах Xiaomi, Huawei, Samsung и некоторых других есть собственные «оптимизаторы», которые всё равно усыпляют приложения. Как их отключить для вашей модели — на <https://dontkillmyapp.com>.

**Готово** 🎉 Виджет обновляется раз в 30 минут, в момент сброса лимита и при нажатии на него.

---

## 5. Если что-то не работает

### Статус на виджете

| Статус | Причина | Решение |
|---|---|---|
| `НУЖНА НАСТРОЙКА` | не указан адрес сервера | откройте приложение и заполните поля |
| `НЕТ СВЯЗИ` | компьютер выключен/спит или на телефоне нет интернета | включите компьютер; проверьте `tailscale funnel status` |
| `НЕВЕРНЫЙ КЛЮЧ` | ключ в приложении не совпадает с серверным | скопируйте ключ заново, без пробелов и `CLAUDE_WIDGET_KEY=` |
| `ТОКЕН ИСТЁК` | Claude Code давно не запускался | запустите `claude` на компьютере |
| `ОШИБКА СЕРВЕРА` | прочие ошибки | текст ошибки — в приложении, в блоке «Последняя ошибка виджета» |

### Проверки на компьютере

```bash
# сервис запущен?
systemctl --user status claude-usage-server

# логи сервера в реальном времени
journalctl --user -u claude-usage-server -f

# Funnel включён и смотрит на порт 8765?
tailscale funnel status
```

### Частые проблемы

<details>
<summary><b>Сервис не стартует: «CLAUDE_WIDGET_KEY не задан»</b></summary>

Не создан файл с ключом — выполните шаг [2.5](#25-секретный-ключ), затем `systemctl --user restart claude-usage-server`.
</details>

<details>
<summary><b>Сервис не стартует: «can't open file … claude_usage_server.py»</b></summary>

Проект лежит не в `~/development/claude-usage-widget`. Поправьте `ExecStart` в `~/.config/systemd/user/claude-usage-server.service`, затем:

```bash
systemctl --user daemon-reload && systemctl --user restart claude-usage-server
```
</details>

<details>
<summary><b>curl через Funnel возвращает 502</b></summary>

Funnel работает, но сервер за ним не запущен. Проверьте `systemctl --user status claude-usage-server`.
</details>

<details>
<summary><b>Ответ 503 с кодом <code>auth</code></b></summary>

Нет файла `~/.claude/.credentials.json` или токен устарел. Запустите `claude` и убедитесь, что вход выполнен через аккаунт Claude, а не через API-ключ.
</details>

<details>
<summary><b>Сервер работает, пока я залогинен, и останавливается после выхода</b></summary>

Не включён lingering: `loginctl enable-linger $USER`.
</details>

<details>
<summary><b>Сборка: «SDK location not found»</b></summary>

Нет файла `android/local.properties` — выполните шаг [3.3](#33-путь-к-sdk).
</details>

<details>
<summary><b>Сборка: «Failed to install the following SDK components» / лицензии не приняты</b></summary>

```bash
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses
```
</details>

<details>
<summary><b>Виджет обновляется редко или только по нажатию</b></summary>

Выдайте разрешение на работу в фоне (шаг [4.4](#44-работа-в-фоне)) и отключите фирменный оптимизатор батареи по инструкции с dontkillmyapp.com.
</details>

---

## 6. Обновление и удаление

### Обновление

```bash
cd ~/development/claude-usage-widget
git pull
systemctl --user restart claude-usage-server

cd android
./gradlew assembleRelease
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```

Настройки приложения при обновлении сохраняются.

### Полное удаление

```bash
# сервер
systemctl --user disable --now claude-usage-server
rm ~/.config/systemd/user/claude-usage-server.service ~/.config/claude-usage-server.env
systemctl --user daemon-reload

# публичный адрес
sudo tailscale funnel --https=443 off
```

На телефоне удалите виджет с экрана и само приложение **Claude Usage**.

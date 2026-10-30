**English** · [Русский](INSTALL.ru.md)

# Installing Claude Usage Widget

A step-by-step guide from scratch: from a clean computer to a working widget on your phone.
It usually takes 20–30 minutes, most of which is spent downloading the Android SDK.

**Contents**

1. [What you'll end up with](#1-what-youll-end-up-with)
2. [Server on the computer](#2-server-on-the-computer)
3. [Building the app](#3-building-the-app)
4. [Installing on the phone and setup](#4-installing-on-the-phone-and-setup)
5. [Troubleshooting](#5-troubleshooting)
6. [Updating and uninstalling](#6-updating-and-uninstalling)

> [!NOTE]
> The app interface is in Russian. This guide gives the original labels with translations in parentheses.

---

## 1. What you'll end up with

```
 Phone ──HTTPS──▶ https://<machine>.<tailnet>.ts.net ──▶ server on the computer ──▶ Anthropic
```

- A small server runs on the computer where you use Claude Code. It fetches your limits from Anthropic and counts tokens from Claude Code logs.
- Tailscale Funnel gives the server a permanent public HTTPS address. The phone does **not** need Tailscale and can be on any network.
- Every 30 minutes (and on tap) the widget fetches data from that address. Access is protected by a secret key.

### Requirements

| | |
|---|---|
| **Computer** | Linux with `systemd` (tested on Fedora; Ubuntu, Debian, Arch etc. will work) |
| **Claude** | a Pro or Max subscription and Claude Code signed in with your claude.ai account (not with an API key) |
| **Phone** | Android 8.0 or newer |
| **Tailscale account** | free; sign in with Google / GitHub / Microsoft etc. |

> [!IMPORTANT]
> While the computer is off or asleep, the widget shows the last received data with the `НЕТ СВЯЗИ` (no connection) status.

---

## 2. Server on the computer

### 2.1. Python

The server uses only the standard library, so there's nothing to install with pip. Check the version — 3.8 or newer is fine:

```bash
python3 --version
```

If Python is missing: `sudo dnf install python3` (Fedora) or `sudo apt install python3` (Ubuntu/Debian).

### 2.2. Claude Code

If you already have Claude Code installed and use it, skip to the check below.

```bash
curl -fsSL https://claude.ai/install.sh | bash
claude
```

On first launch, choose to sign in with your Claude account (subscription). Make sure the token file exists:

```bash
ls -l ~/.claude/.credentials.json
```

> [!NOTE]
> Claude Code refreshes the token on its own while you use it. If you don't run it for a long time, the token expires and the widget shows `ТОКЕН ИСТЁК` (token expired) — just run `claude` once.

### 2.3. Tailscale

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo systemctl enable --now tailscaled
sudo tailscale up
```

`tailscale up` prints a link — open it in a browser and sign in. Check that the machine is online:

```bash
tailscale status
```

### 2.4. Getting the project

The service unit file expects the path `~/development/claude-usage-widget`, so the easiest option is to clone there:

```bash
mkdir -p ~/development
git clone https://github.com/mtvxlqq/claude-usage-widget.git ~/development/claude-usage-widget
```

<details>
<summary>I want to put the project somewhere else</summary>

Open `server/claude-usage-server.service` and change the path in the `ExecStart` line:

```ini
ExecStart=/usr/bin/python3 -I %h/path/to/project/server/claude_usage_server.py
```

`%h` is your home directory.
</details>

### 2.5. Secret key

The key protects your data: without it the server answers `403`. Create it once:

```bash
[ -f ~/.config/claude-usage-server.env ] || \
  (umask 077; echo "CLAUDE_WIDGET_KEY=$(openssl rand -hex 16)" > ~/.config/claude-usage-server.env)
```

Show the key (you'll need it on the phone):

```bash
cut -d= -f2 ~/.config/claude-usage-server.env
```

### 2.6. Running the server as a service

```bash
mkdir -p ~/.config/systemd/user
cp ~/development/claude-usage-widget/server/claude-usage-server.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now claude-usage-server
```

To have the service start as soon as the computer boots, even before you log in:

```bash
loginctl enable-linger $USER
```

Check that the server is running and responds locally:

```bash
systemctl --user status claude-usage-server
curl -s -H "Authorization: Bearer $(cut -d= -f2 ~/.config/claude-usage-server.env)" \
     http://127.0.0.1:8765/usage
```

You should get JSON with `session` and `weekly` fields.

### 2.7. Publishing via Tailscale Funnel

```bash
sudo tailscale funnel --bg 8765
```

If Funnel isn't enabled in your tailnet yet, the command prints a link to the admin console — open it, allow HTTPS certificates and Funnel, then run the command again.

Find your public address:

```bash
tailscale funnel status
```

It looks like `https://<machine>.<tailnet>.ts.net`. Test it from outside:

```bash
curl -s -H "Authorization: Bearer $(cut -d= -f2 ~/.config/claude-usage-server.env)" \
     https://<machine>.<tailnet>.ts.net/usage
```

> [!TIP]
> Right after enabling Funnel, the address may take up to a few minutes to start working while DNS and the certificate propagate.

The `--bg` flag persists the setting: after a reboot Funnel comes back on its own. The server is ready ✅

---

## 3. Building the app

The build runs on the computer; the phone isn't needed for it.

> [!TIP]
> If the repository's **Releases** section has a prebuilt `app-release.apk`, you can skip this section and go straight to [installing on the phone](#4-installing-on-the-phone-and-setup).

### 3.1. Java

Gradle needs Java 17 or newer to run. Gradle downloads a full JDK 21 for compilation by itself.

```bash
java -version
```

If Java is missing:

```bash
sudo dnf install java-21-openjdk        # Fedora
sudo apt install openjdk-21-jre         # Ubuntu / Debian
```

### 3.2. Android SDK

There are two ways — pick one.

<details open>
<summary><b>Option A: command line only (≈ 1 GB)</b></summary>

1. Download **Command line tools only** for Linux from <https://developer.android.com/studio#command-line-tools-only>.
2. Unpack it into the expected folder layout:

   ```bash
   mkdir -p ~/Android/Sdk/cmdline-tools
   unzip ~/Downloads/commandlinetools-linux-*_latest.zip -d ~/Android/Sdk/cmdline-tools
   mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
   ```

3. Accept the licenses and install the required components:

   ```bash
   export ANDROID_HOME=~/Android/Sdk
   $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
   $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
   ```
</details>

<details>
<summary><b>Option B: Android Studio</b></summary>

Install [Android Studio](https://developer.android.com/studio), launch it and go through the first-run wizard — it downloads the SDK into `~/Android/Sdk`. Then in **Settings → Languages & Frameworks → Android SDK** check **Android 16 (API 36)**.

You can open the project (the `android/` folder) in Studio and build from there — then steps 3.3–3.4 aren't needed.
</details>

### 3.3. SDK path

Tell Gradle where the SDK is. `local.properties` isn't stored in the repository, so you need to create it:

```bash
cd ~/development/claude-usage-widget/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

### 3.4. Build

```bash
./gradlew assembleRelease
```

The first build downloads Gradle, the JDK and plugins, so it takes a few minutes. At the end you should see `BUILD SUCCESSFUL`, and the app file will be here:

```
android/app/build/outputs/apk/release/app-release.apk
```

> [!NOTE]
> The APK is signed with your computer's standard debug key. That's enough for personal use. If you later build the APK on a different computer, it won't install over the old one — uninstall the app first.

---

## 4. Installing on the phone and setup

### 4.1. Installing the APK

<details open>
<summary><b>Over a USB cable (adb)</b></summary>

1. Enable developer mode on the phone: **Settings → About phone** → tap **Build number** 7 times.
2. In **Settings → Developer options**, turn on **USB debugging**.
3. Connect the phone with a cable, confirm the prompt on the phone and run:

   ```bash
   ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
   ```
</details>

<details>
<summary><b>Without a cable</b></summary>

Send `app-release.apk` to the phone any way you like (messenger, cloud storage, email), open the file and allow installs from that source when Android asks.
</details>

### 4.2. Adding the widget

1. Long-press an empty spot on the home screen → **Widgets**.
2. Find **Claude Usage** and drag it onto the screen. The default size is 4×2 and can be resized.
3. The settings screen opens right away.

### 4.3. Connecting to the server

| Field | What to enter |
|---|---|
| **АДРЕС СЕРВЕРА** (server address) | the Funnel address from step 2.7, e.g. `https://fedora.tail1234.ts.net` |
| **КЛЮЧ** (key) | the value from `~/.config/claude-usage-server.env` (without `CLAUDE_WIDGET_KEY=`) |

Tap **«Сохранить и проверить»** (Save and check). On success the screen closes and the widget shows percentages and the timer. On error its text appears under the button — see [section 5](#5-troubleshooting).

> [!TIP]
> The key is long — rather than retyping it, send it to your phone (e.g. to your own chat in a messenger) and paste it.

### 4.4. Background work

Open the **Claude Usage** app from the app list and tap **«Разрешить работу в фоне»** (Allow background work) → **Allow**. Without this, Android may delay refreshes for a long time. Once the permission is granted, the button disappears.

Xiaomi, Huawei, Samsung and some other phones have their own "optimizers" that put apps to sleep anyway. How to disable them for your model: <https://dontkillmyapp.com>.

**Done** 🎉 The widget refreshes every 30 minutes, when a limit resets, and when you tap it.

---

## 5. Troubleshooting

### Widget status

| Status | Cause | Fix |
|---|---|---|
| `НУЖНА НАСТРОЙКА` (setup needed) | no server address set | open the app and fill in the fields |
| `НЕТ СВЯЗИ` (no connection) | the computer is off/asleep or the phone has no internet | turn the computer on; check `tailscale funnel status` |
| `НЕВЕРНЫЙ КЛЮЧ` (wrong key) | the app key doesn't match the server's | copy the key again, without spaces or `CLAUDE_WIDGET_KEY=` |
| `ТОКЕН ИСТЁК` (token expired) | Claude Code hasn't been run for a while | run `claude` on the computer |
| `ОШИБКА СЕРВЕРА` (server error) | other errors | see the error text in the app under «Последняя ошибка виджета» (last widget error) |

### Checks on the computer

```bash
# is the service running?
systemctl --user status claude-usage-server

# live server logs
journalctl --user -u claude-usage-server -f

# is Funnel on and pointing to port 8765?
tailscale funnel status
```

### Common problems

<details>
<summary><b>The service won't start: «CLAUDE_WIDGET_KEY не задан» (key not set)</b></summary>

The key file wasn't created — do step [2.5](#25-secret-key), then `systemctl --user restart claude-usage-server`.
</details>

<details>
<summary><b>The service won't start: «can't open file … claude_usage_server.py»</b></summary>

The project isn't in `~/development/claude-usage-widget`. Fix `ExecStart` in `~/.config/systemd/user/claude-usage-server.service`, then:

```bash
systemctl --user daemon-reload && systemctl --user restart claude-usage-server
```
</details>

<details>
<summary><b>curl through Funnel returns 502</b></summary>

Funnel works, but the server behind it isn't running. Check `systemctl --user status claude-usage-server`.
</details>

<details>
<summary><b>Response 503 with code <code>auth</code></b></summary>

`~/.claude/.credentials.json` is missing or the token is stale. Run `claude` and make sure you're signed in with your Claude account, not an API key.
</details>

<details>
<summary><b>The server runs while I'm logged in and stops after I log out</b></summary>

Lingering isn't enabled: `loginctl enable-linger $USER`.
</details>

<details>
<summary><b>Build: «SDK location not found»</b></summary>

`android/local.properties` is missing — do step [3.3](#33-sdk-path).
</details>

<details>
<summary><b>Build: «Failed to install the following SDK components» / licenses not accepted</b></summary>

```bash
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses
```
</details>

<details>
<summary><b>The widget refreshes rarely or only on tap</b></summary>

Grant the background permission (step [4.4](#44-background-work)) and disable the vendor battery optimizer following dontkillmyapp.com.
</details>

---

## 6. Updating and uninstalling

### Updating

```bash
cd ~/development/claude-usage-widget
git pull
systemctl --user restart claude-usage-server

cd android
./gradlew assembleRelease
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```

App settings are kept across updates.

### Full removal

```bash
# server
systemctl --user disable --now claude-usage-server
rm ~/.config/systemd/user/claude-usage-server.service ~/.config/claude-usage-server.env
systemctl --user daemon-reload

# public address
sudo tailscale funnel --https=443 off
```

On the phone, remove the widget from the home screen and uninstall the **Claude Usage** app.

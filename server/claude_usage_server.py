#!/usr/bin/env python3
"""Отдаёт статистику использования Claude для Android-виджета.

GET /usage, ключ в заголовке "Authorization: Bearer <KEY>" (или ?key=<KEY>) -> JSON:
  session/weekly: percent, resets_at (unix sec), tokens, output_tokens (из локальных логов)
  updated_at: когда лимиты получены от Anthropic
  error: {code, message} — лимиты не обновились, отданы последние известные
"""
import glob
import hmac
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HOME = os.path.expanduser("~")
CREDS = os.path.join(HOME, ".claude", ".credentials.json")
PROJECTS = os.path.join(HOME, ".claude", "projects")
USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
# Funnel проксирует на localhost, в локальную сеть порт открывать не нужно
HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8765"))
SECRET = os.environ.get("CLAUDE_WIDGET_KEY", "").encode()
LIMITS_TTL = 60
WEEK = 7 * 24 * 3600


def parse_ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


class ApiError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def fetch_limits():
    try:
        with open(CREDS) as f:
            token = json.load(f)["claudeAiOauth"]["accessToken"]
    except (OSError, ValueError, KeyError) as ex:
        raise ApiError("auth", f"нет токена Claude Code: {ex}")
    req = urllib.request.Request(USAGE_URL, headers={
        "Authorization": f"Bearer {token}",
        "anthropic-beta": "oauth-2025-04-20",
    })
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.load(r)
    except urllib.error.HTTPError as ex:
        if ex.code in (401, 403):
            # Claude Code обновляет токен, только когда им пользуются
            raise ApiError("auth", "токен Claude Code истёк — запусти claude на ноутбуке")
        raise ApiError("upstream", f"Anthropic API: HTTP {ex.code}")
    except (OSError, ValueError) as ex:
        raise ApiError("upstream", f"Anthropic API недоступен: {ex}")


class TokenIndex:
    """Токены из транскриптов Claude Code с инкрементальным чтением.

    Файлы *.jsonl только дописываются, поэтому после первого прохода читаются
    лишь новые строки с запомненной позиции, а не десятки мегабайт заново.
    """

    def __init__(self):
        self.offsets = {}   # path -> (inode, позиция после последней целой строки)
        self.messages = {}  # (id сообщения, id запроса) -> (ts, всего токенов, output)

    def refresh(self, horizon):
        alive = set()
        for path in glob.glob(os.path.join(PROJECTS, "**", "*.jsonl"), recursive=True):
            try:
                st = os.stat(path)
            except OSError:
                continue
            if st.st_mtime < horizon:
                continue
            alive.add(path)
            ino, pos = self.offsets.get(path, (st.st_ino, 0))
            if ino != st.st_ino or st.st_size < pos:
                pos = 0  # файл заменён или обрезан
            if st.st_size > pos:
                pos = self._read(path, pos, horizon)
            self.offsets[path] = (st.st_ino, pos)
        for path in self.offsets.keys() - alive:
            del self.offsets[path]
        self.messages = {k: v for k, v in self.messages.items() if v[0] >= horizon}

    def _read(self, path, pos, horizon):
        with open(path, "rb") as f:
            f.seek(pos)
            for line in f:
                if not line.endswith(b"\n"):
                    break  # строка ещё дописывается — дочитаем в следующий раз
                pos += len(line)
                if b'"usage"' not in line:
                    continue
                try:
                    e = json.loads(line)
                    msg = e["message"]
                    u = msg["usage"]
                    ts = parse_ts(e["timestamp"])
                    # одна и та же реплика пишется в лог несколько раз при стриминге
                    key = (msg.get("id") or e.get("uuid"), e.get("requestId"))
                    if ts < horizon or key in self.messages:
                        continue
                    out = u.get("output_tokens") or 0
                    total = (out + (u.get("input_tokens") or 0)
                             + (u.get("cache_creation_input_tokens") or 0)
                             + (u.get("cache_read_input_tokens") or 0))
                except (ValueError, KeyError, TypeError, AttributeError):
                    continue
                self.messages[key] = (ts, total, out)
        return pos

    def total(self, since):
        total = output = 0
        for ts, t, o in self.messages.values():
            if ts >= since:
                total += t
                output += o
        return total, output


_index = TokenIndex()
_lock = threading.Lock()
_state = {"limits": None, "at": 0.0, "error": None, "checked": 0.0}


def refresh_limits(now):
    """Лимиты не чаще раза в LIMITS_TTL; при ошибке остаются последние известные."""
    if now - _state["checked"] < LIMITS_TTL:
        return
    _state["checked"] = now
    try:
        _state.update(limits=fetch_limits(), at=now, error=None)
    except ApiError as ex:
        _state["error"] = ex


def window(limit, length):
    if not limit or not limit.get("resets_at"):
        return None
    resets = parse_ts(limit["resets_at"])
    total, output = _index.total(resets - length)
    return {
        "percent": limit.get("utilization") or 0,
        "resets_at": int(resets),
        "tokens": total,
        "output_tokens": output,
    }


def build():
    now = time.time()
    refresh_limits(now)
    limits, error = _state["limits"], _state["error"]
    if limits is None:
        raise error
    _index.refresh(now - WEEK - 3600)
    data = {
        "session": window(limits.get("five_hour"), 5 * 3600),
        "weekly": window(limits.get("seven_day"), WEEK),
        "updated_at": int(_state["at"]),
    }
    if error:
        data["error"] = {"code": error.code, "message": str(error)}
    return data


class Handler(BaseHTTPRequestHandler):
    server_version = "claude-usage"
    sys_version = ""

    def do_GET(self):
        url = urlparse(self.path)
        if url.path != "/usage":
            return self.reply(404, {"error": "not found", "code": "path"})
        if not self.authorized(url):
            return self.reply(403, {"error": "bad key", "code": "key"})
        try:
            with _lock:
                data = build()
        except ApiError as ex:
            return self.reply(503, {"error": str(ex), "code": ex.code})
        except Exception as ex:
            return self.reply(500, {"error": str(ex), "code": "internal"})
        self.reply(200, data)

    def authorized(self, url):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            given = auth[7:]
        else:
            given = parse_qs(url.query).get("key", [""])[0]
        return hmac.compare_digest(given.encode(), SECRET)

    def reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_request(self, code="-", size="-"):
        # стандартный лог пишет полный URL, а в нём может быть ключ
        print(f"{self.command} {urlparse(self.path).path} {code}", file=sys.stderr, flush=True)


if __name__ == "__main__":
    if not SECRET:
        sys.exit("CLAUDE_WIDGET_KEY не задан: без ключа данные увидит любой, кто знает адрес")
    with _lock:
        _index.refresh(time.time() - WEEK - 3600)  # первый полный проход — до первого запроса
    print(f"listening on {HOST}:{PORT}", file=sys.stderr, flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()

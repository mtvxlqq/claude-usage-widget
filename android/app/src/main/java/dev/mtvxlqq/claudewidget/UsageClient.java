package dev.mtvxlqq.claudewidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Ходит на claude_usage_server.py и хранит настройки подключения. */
final class UsageClient {
    static final String PREFS = "settings";
    static final String KEY_URL = "url";
    static final String KEY_SECRET = "secret";
    static final String KEY_LAST_JSON = "last_json";
    static final String KEY_LAST_ERROR = "last_error";

    /** Ошибка с кодом для строки статуса: setup, key, auth, upstream, http… */
    static final class UsageException extends IOException {
        final String code;

        UsageException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private UsageClient() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONObject fetch(Context ctx) throws IOException, JSONException {
        SharedPreferences p = prefs(ctx);
        String base = p.getString(KEY_URL, "").replaceAll("/+$", "");
        if (base.isEmpty()) throw new UsageException("setup", "введи адрес сервера");
        if (!base.contains("://")) base = "https://" + base; // сервер доступен только через Funnel

        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/usage").openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);
        conn.setUseCaches(false);
        // ключ в заголовке, а не в URL: адреса попадают в логи прокси
        conn.setRequestProperty("Authorization", "Bearer " + p.getString(KEY_SECRET, ""));
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                JSONObject err;
                try {
                    err = new JSONObject(read(conn.getErrorStream()));
                } catch (IOException | JSONException e) {
                    err = new JSONObject(); // например, 502 от Funnel, когда сервер не запущен
                }
                throw new UsageException(err.optString("code", "http"),
                        "HTTP " + code + " " + err.optString("error"));
            }
            String body = read(conn.getInputStream());
            JSONObject json = new JSONObject(body);
            p.edit().putString(KEY_LAST_JSON, body).apply();
            return json;
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) throw new IOException("пустой ответ");
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String formatTokens(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return Long.toString(n);
    }

    static String formatRemaining(long seconds) {
        if (seconds <= 0) return "СЕЙЧАС";
        long d = seconds / 86400, h = seconds % 86400 / 3600, m = seconds % 3600 / 60;
        if (d > 0) return d + "Д " + h + "Ч";
        if (h > 0) return h + "Ч " + m + "М";
        return m + "М";
    }
}

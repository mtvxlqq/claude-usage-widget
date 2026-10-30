package dev.mtvxlqq.claudewidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UsageWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "dev.mtvxlqq.claudewidget.REFRESH";

    /** Точек в полоске — столько же кругов в drawable/dots_*.xml. */
    private static final int DOTS = 16;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        // приходит при добавлении виджета, загрузке телефона и обновлении приложения;
        // остальные обновления идут через RefreshJob, а не через updatePeriodMillis
        RefreshJob.schedulePeriodic(ctx);
        refresh(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        RefreshJob.cancelAll(ctx);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            refresh(ctx);
            return;
        }
        super.onReceive(ctx, intent);
    }

    /** Сразу показать «···» поверх последних данных и поставить загрузку. */
    private static void refresh(Context ctx) {
        render(ctx, loadLast(ctx), "···");
        RefreshJob.runNow(ctx);
    }

    static boolean hasWidgets(Context ctx) {
        return AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(new ComponentName(ctx, UsageWidget.class)).length > 0;
    }

    /** Загружает данные и перерисовывает виджет. Вызывается из RefreshJob в фоне. */
    static void update(Context ctx) {
        try {
            show(ctx, UsageClient.fetch(ctx));
        } catch (Exception e) {
            // ноутбук выключен или сервер недоступен — остаются последние данные
            saveError(ctx, e.toString());
            String code = e instanceof UsageClient.UsageException
                    ? ((UsageClient.UsageException) e).code
                    : e instanceof IOException ? null : "bad";
            JSONObject last = loadLast(ctx);
            render(ctx, last, last == null ? labelFor(code)
                    : labelFor(code) + " · " + formatTime(last.optLong("updated_at")));
        }
    }

    /** Показать свежий ответ сервера (из RefreshJob или после проверки в настройках). */
    static void show(Context ctx, JSONObject data) {
        JSONObject err = data.optJSONObject("error");
        if (err != null) {
            saveError(ctx, err.optString("message"));
        } else {
            UsageClient.prefs(ctx).edit().remove(UsageClient.KEY_LAST_ERROR).apply();
        }
        render(ctx, data, null);
    }

    private static void saveError(Context ctx, String message) {
        UsageClient.prefs(ctx).edit()
                .putString(UsageClient.KEY_LAST_ERROR, formatTime(now()) + " " + message)
                .apply();
    }

    /** Короткая подпись для строки статуса; null — сервер не ответил вовсе. */
    private static String labelFor(String code) {
        if (code == null) return "НЕТ СВЯЗИ";
        switch (code) {
            case "setup": return "НУЖНА НАСТРОЙКА";
            case "key": return "НЕВЕРНЫЙ КЛЮЧ";
            case "auth": return "ТОКЕН ИСТЁК";
            default: return "ОШИБКА СЕРВЕРА";
        }
    }

    private static JSONObject loadLast(Context ctx) {
        try {
            String s = UsageClient.prefs(ctx).getString(UsageClient.KEY_LAST_JSON, null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String formatTime(long unixSec) {
        SimpleDateFormat fmt = new SimpleDateFormat(
                now() - unixSec > 86400 ? "dd.MM HH:mm" : "HH:mm", Locale.ROOT);
        return fmt.format(new Date(unixSec * 1000));
    }

    /** Полоска из точек: ClipDrawable в dot_bar.xml открывает целое число точек. */
    private static void setBar(RemoteViews v, int id, double percent) {
        int on = (int) Math.min(DOTS, Math.round(percent / 100 * DOTS));
        if (percent > 0 && on == 0) on = 1;
        v.setInt(id, "setImageLevel", on * 10000 / DOTS);
    }

    private static void render(Context ctx, JSONObject data, String status) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_usage);
        long now = now();
        long nextReset = 0;

        if (data != null) {
            JSONObject s = data.optJSONObject("session");
            if (s == null || s.optLong("resets_at") <= now) {
                // окна нет или оно истекло, пока не было связи: лимит обнулился
                v.setTextViewText(R.id.session_pct, "0%");
                v.setTextViewText(R.id.session_info, s == null ? "НЕ НАЧАТА" : "СБРОШЕНО");
                v.setChronometer(R.id.session_timer, SystemClock.elapsedRealtime(), null, false);
            } else {
                double pct = s.optDouble("percent");
                long resetsAt = s.optLong("resets_at");
                nextReset = resetsAt;
                v.setTextViewText(R.id.session_pct, Math.round(pct) + "%");
                setBar(v, R.id.session_bar, pct);
                v.setTextViewText(R.id.session_info,
                        UsageClient.formatTokens(s.optLong("tokens")) + " ТОК · "
                                + UsageClient.formatTokens(s.optLong("output_tokens")) + " OUT");
                // Chronometer тикает сам на стороне лаунчера, приложение при этом спит
                v.setChronometer(R.id.session_timer,
                        SystemClock.elapsedRealtime() + (resetsAt - now) * 1000, null, true);
                v.setChronometerCountDown(R.id.session_timer, true);
            }

            JSONObject w = data.optJSONObject("weekly");
            if (w == null || w.optLong("resets_at") <= now) {
                v.setTextViewText(R.id.weekly_pct, "0%");
                v.setTextViewText(R.id.weekly_info, w == null ? "НЕ НАЧАТА" : "СБРОШЕНО");
            } else {
                double pct = w.optDouble("percent");
                long resetsAt = w.optLong("resets_at");
                v.setTextViewText(R.id.weekly_pct, Math.round(pct) + "%");
                setBar(v, R.id.weekly_bar, pct);
                v.setTextViewText(R.id.weekly_reset, new SimpleDateFormat("dd.MM HH:mm", Locale.ROOT)
                        .format(new Date(resetsAt * 1000)));
                v.setTextViewText(R.id.weekly_info,
                        UsageClient.formatTokens(w.optLong("tokens")) + " ТОК · "
                                + UsageClient.formatRemaining(resetsAt - now));
                if (nextReset == 0 || resetsAt < nextReset) nextReset = resetsAt;
            }

            if (status == null) {
                // лимиты могли не обновиться (например, истёк токен) — сервер прислал старые
                JSONObject err = data.optJSONObject("error");
                String time = formatTime(data.optLong("updated_at"));
                status = err == null ? time : labelFor(err.optString("code")) + " · " + time;
            }
        }
        if (status != null) v.setTextViewText(R.id.status, status);

        v.setOnClickPendingIntent(R.id.root, PendingIntent.getBroadcast(ctx, 0,
                new Intent(ACTION_REFRESH).setClass(ctx, UsageWidget.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        AppWidgetManager.getInstance(ctx)
                .updateAppWidget(new ComponentName(ctx, UsageWidget.class), v);
        if (hasWidgets(ctx)) RefreshJob.scheduleReset(ctx, nextReset);
    }
}

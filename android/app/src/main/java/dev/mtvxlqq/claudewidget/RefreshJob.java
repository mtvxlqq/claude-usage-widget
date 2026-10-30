package dev.mtvxlqq.claudewidget;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Все фоновые обновления виджета. JobScheduler, в отличие от BroadcastReceiver
 * и неточных будильников, гарантированно даёт сеть в фоне и не откладывает
 * запуск на часы.
 */
public class RefreshJob extends JobService {
    private static final int ID_NOW = 1;
    private static final int ID_PERIODIC = 2;
    private static final int ID_RESET = 3;
    private static final long PERIOD_MS = 30 * 60 * 1000L;
    private static final String EXTRA_AT = "at";

    /** Один поток на все обновления: параллельные задачи не дублируют запросы. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    /** Обновить как можно скорее (нажатие на виджет, добавление, загрузка телефона). */
    static void runNow(Context ctx) {
        try {
            JobScheduler js = scheduler(ctx);
            // запрос уже ждёт сеть или выполняется — повторное нажатие его не перезапускает
            if (js.getPendingJob(ID_NOW) != null) return;
            JobInfo.Builder b = builder(ctx, ID_NOW);
            if (Build.VERSION.SDK_INT >= 31) {
                b.setExpedited(true);
                if (js.schedule(b.build()) == JobScheduler.RESULT_SUCCESS) return;
                b = builder(ctx, ID_NOW); // квота срочных задач исчерпана — ставим обычную
            }
            js.schedule(b.build());
        } catch (RuntimeException e) {
            // не роняем приложение из-за планировщика, а грузим напрямую
            saveError(ctx, e);
            Context app = ctx.getApplicationContext();
            WORKER.execute(() -> UsageWidget.update(app));
        }
    }

    static void schedulePeriodic(Context ctx) {
        try {
            JobScheduler js = scheduler(ctx);
            // пересоздаём задачу, если она была запланирована с другим интервалом
            JobInfo pending = js.getPendingJob(ID_PERIODIC);
            if (pending != null && pending.getIntervalMillis() == PERIOD_MS) return;
            js.schedule(builder(ctx, ID_PERIODIC)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException e) {
            saveError(ctx, e);
        }
    }

    /** Перерисовать виджет, когда лимит сбросится и таймер дойдёт до нуля. */
    static void scheduleReset(Context ctx, long unixSec) {
        try {
            JobScheduler js = scheduler(ctx);
            JobInfo pending = js.getPendingJob(ID_RESET);
            if (unixSec <= 0) {
                if (pending != null) js.cancel(ID_RESET);
                return;
            }
            if (pending != null && pending.getExtras().getLong(EXTRA_AT) == unixSec) return;
            long delay = Math.max(0, unixSec * 1000 - System.currentTimeMillis()) + 2000;
            PersistableBundle extras = new PersistableBundle();
            extras.putLong(EXTRA_AT, unixSec);
            js.schedule(builder(ctx, ID_RESET)
                    .setMinimumLatency(delay)
                    // дедлайн снимает требование сети: без связи виджет просто обнулится
                    .setOverrideDeadline(delay + 60_000)
                    .setExtras(extras)
                    .build());
        } catch (RuntimeException e) {
            saveError(ctx, e);
        }
    }

    static void cancelAll(Context ctx) {
        scheduler(ctx).cancelAll();
    }

    private static JobScheduler scheduler(Context ctx) {
        return ctx.getSystemService(JobScheduler.class);
    }

    private static JobInfo.Builder builder(Context ctx, int id) {
        return new JobInfo.Builder(id, new ComponentName(ctx, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
    }

    private static void saveError(Context ctx, Exception e) {
        UsageClient.prefs(ctx).edit()
                .putString(UsageClient.KEY_LAST_ERROR, "JobScheduler: " + e)
                .apply();
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        WORKER.execute(() -> {
            UsageWidget.update(getApplicationContext());
            jobFinished(params, false);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}

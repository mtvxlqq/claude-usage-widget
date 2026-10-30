package dev.mtvxlqq.claudewidget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

public class SettingsActivity extends Activity {
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private Button battery;
    private TextView result;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);

        // открыто как конфигурация при добавлении виджета
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        }
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) setResult(RESULT_CANCELED);

        SharedPreferences p = UsageClient.prefs(this);
        EditText url = findViewById(R.id.server_url);
        EditText key = findViewById(R.id.server_key);
        Button save = findViewById(R.id.save);
        battery = findViewById(R.id.battery);
        result = findViewById(R.id.result);
        url.setText(p.getString(UsageClient.KEY_URL, ""));
        key.setText(p.getString(UsageClient.KEY_SECRET, ""));

        battery.setOnClickListener(b -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()))));

        save.setOnClickListener(b -> {
            p.edit()
                    .putString(UsageClient.KEY_URL, url.getText().toString().trim())
                    .putString(UsageClient.KEY_SECRET, key.getText().toString().trim())
                    .apply();
            save.setEnabled(false);
            result.setText("ПРОВЕРЯЮ…");
            Context app = getApplicationContext();
            new Thread(() -> {
                String text;
                boolean ok = false;
                try {
                    JSONObject data = UsageClient.fetch(app);
                    // виджет рисуется из этого же ответа, без второго запроса
                    UsageWidget.show(app, data);
                    if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID || UsageWidget.hasWidgets(app)) {
                        RefreshJob.schedulePeriodic(app);
                    }
                    text = "OK\n" + data.toString(2);
                    ok = true;
                } catch (Exception e) {
                    text = "ОШИБКА: " + e.getMessage();
                }
                String msg = text;
                boolean success = ok;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    save.setEnabled(true);
                    result.setText(msg);
                    if (success && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        setResult(RESULT_OK, new Intent()
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
                        finish();
                    }
                });
            }).start();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // кнопка не нужна, если ограничение уже снято
        boolean exempt = getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName());
        battery.setVisibility(exempt ? View.GONE : View.VISIBLE);

        String lastError = UsageClient.prefs(this).getString(UsageClient.KEY_LAST_ERROR, null);
        if (lastError != null && result.length() == 0) {
            result.setText("ПОСЛЕДНЯЯ ОШИБКА ВИДЖЕТА:\n" + lastError);
        }
    }
}

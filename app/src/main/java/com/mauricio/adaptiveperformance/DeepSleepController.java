package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;

public final class DeepSleepController {
    private final Context context;
    private final SharedPreferences prefs;
    private long lastElapsed = -1L;
    private long lastUptime = -1L;
    private boolean lastInteractive = true;
    private long sessionObservedMs = 0L;
    private long sessionDeepMs = 0L;

    public DeepSleepController(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    public void update(boolean interactive) {
        long elapsed = SystemClock.elapsedRealtime();
        long uptime = SystemClock.uptimeMillis();

        if (lastElapsed < 0L || lastUptime < 0L) {
            lastElapsed = elapsed;
            lastUptime = uptime;
            lastInteractive = interactive;
            if (!interactive) {
                sessionObservedMs = 0L;
                sessionDeepMs = 0L;
            }
            persist(interactive);
            return;
        }

        long dElapsed = Math.max(0L, elapsed - lastElapsed);
        long dUptime = Math.max(0L, uptime - lastUptime);
        long deep = Math.max(0L, dElapsed - dUptime);

        if (!interactive && lastInteractive) {
            sessionObservedMs = 0L;
            sessionDeepMs = 0L;
        } else if (!interactive && !lastInteractive) {
            sessionObservedMs += dElapsed;
            sessionDeepMs += Math.min(deep, dElapsed);
        } else if (interactive && !lastInteractive) {
            persistSessionSummary();
        }

        lastElapsed = elapsed;
        lastUptime = uptime;
        lastInteractive = interactive;
        persist(interactive);
    }

    private void persist(boolean interactive) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean idle = false;
        boolean lightIdle = false;
        try {
            idle = pm != null && pm.isDeviceIdleMode();
        } catch (Throwable ignored) {}

        float pct = sessionObservedMs > 0
                ? 100f * sessionDeepMs / sessionObservedMs : -1f;
        boolean poor = sessionObservedMs >= 20L * 60L * 1000L && pct >= 0f && pct < 30f;

        prefs.edit()
                .putFloat("deep_sleep_pct", pct)
                .putLong("deep_sleep_observed_ms", sessionObservedMs)
                .putLong("deep_sleep_deep_ms", sessionDeepMs)
                .putBoolean("deep_sleep_poor", poor)
                .putBoolean("device_idle_mode", idle)
                .putBoolean("light_device_idle_mode", lightIdle)
                .putBoolean("deep_sleep_screen_off", !interactive)
                .apply();
    }

    private void persistSessionSummary() {
        float pct = sessionObservedMs > 0
                ? 100f * sessionDeepMs / sessionObservedMs : -1f;
        prefs.edit()
                .putFloat("deep_sleep_last_session_pct", pct)
                .putLong("deep_sleep_last_session_observed_ms", sessionObservedMs)
                .putLong("deep_sleep_last_session_deep_ms", sessionDeepMs)
                .putLong("deep_sleep_last_session_at", System.currentTimeMillis())
                .apply();
    }
}

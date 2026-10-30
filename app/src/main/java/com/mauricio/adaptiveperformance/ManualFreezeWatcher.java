package com.mauricio.adaptiveperformance;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.util.Collections;
import java.util.function.Consumer;

/** Lightweight activity-transition watcher. No periodic shell snapshots. */
public final class ManualFreezeWatcher {
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Consumer<String> onForegroundChanged;
    private long lastEventAt;
    private String lastPackage = "";
    private boolean started;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!started) return;
            boolean enabled = prefs.getBoolean("manual_freeze_enabled", false)
                    && !prefs.getStringSet("manual_freeze_selected", Collections.emptySet()).isEmpty();
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean interactive = power != null && power.isInteractive();
            if (enabled && interactive) {
                try {
                    UsageStatsManager usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
                    long now = System.currentTimeMillis();
                    if (usage != null) {
                        long from = lastEventAt <= 0 ? now - 180_000L : Math.max(now - 180_000L, lastEventAt + 1);
                        UsageEvents events = usage.queryEvents(from, now);
                        UsageEvents.Event e = new UsageEvents.Event();
                        String latest = "";
                        while (events != null && events.hasNextEvent()) {
                            events.getNextEvent(e);
                            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED
                                    && e.getPackageName() != null
                                    && e.getPackageName().matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
                                latest = e.getPackageName();
                                lastEventAt = Math.max(lastEventAt, e.getTimeStamp());
                            }
                        }
                        if (!latest.isEmpty() && !latest.equals(lastPackage)) {
                            lastPackage = latest;
                            onForegroundChanged.accept(latest);
                        }
                    }
                } catch (Throwable ignored) { /* Normal service sample remains fallback. */ }
            }
            handler.postDelayed(this, enabled && interactive ? 2000L : 15000L);
        }
    };

    public ManualFreezeWatcher(Context context, SharedPreferences prefs,
                               Consumer<String> onForegroundChanged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.onForegroundChanged = onForegroundChanged;
    }
    public void start() {
        if (started) return;
        started = true;
        handler.post(poll);
    }
    public void stop() {
        started = false;
        handler.removeCallbacks(poll);
    }
}

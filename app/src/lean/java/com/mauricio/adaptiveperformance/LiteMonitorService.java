package com.mauricio.adaptiveperformance;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;

/** Passive readings only. Never invokes Shizuku, shell, or privileged system APIs. */
public final class LiteMonitorService extends Service {
    static final String PREFS = "lite_monitor";
    static final String CHANNEL = "lite_monitor";
    static final String ACTION_STOP = "lite.STOP";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean active;
    private final Runnable reading = new Runnable() {
        @Override public void run() {
            if (!active) return;
            sample();
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            boolean screenOn = pm != null && pm.isInteractive();
            handler.postDelayed(this, screenOn ? 60000L : 300000L);
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Monitoramento Lite", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitor(); return START_NOT_STICKY;
        }
        if (!active) {
            active = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("active", true).apply();
            startForeground(7101, notification("Monitorando bateria e RAM"));
            reading.run();
        }
        return START_STICKY;
    }
    private Notification notification(String status) {
        Intent launch = new Intent(this, LiteMainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setContentTitle("Adaptive Performance Lite")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(tap)
            .setOngoing(true)
            .build();
    }
    private void sample() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = scale > 0 ? Math.round(100f * level / scale) : -1;
        float temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f;
        boolean charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        float ram = mi.totalMem > 0 ? mi.availMem * 100f / mi.totalMem : -1f;
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        int thermalStatus = Build.VERSION.SDK_INT >= 29 && pm != null ? pm.getCurrentThermalStatus() : -1;
        long now = System.currentTimeMillis();
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        android.content.SharedPreferences.Editor e = p.edit().putInt("pct", pct).putFloat("temp", temp)
            .putFloat("ram", ram).putInt("thermal", thermalStatus).putBoolean("charging", charging)
            .putLong("last", now);
        // One sample every five minutes, keep up to 24 hours.
        long lastSaved = p.getLong("last_history", 0);
        if (now - lastSaved >= 300000L || lastSaved == 0) {
            StringBuilder hist = new StringBuilder();
            for (String line : p.getString("history", "").split("\\n")) {
                if (line.trim().isEmpty()) continue;
                try {
                    if (Long.parseLong(line.split(",")[0]) >= now - 86400000L) hist.append(line).append('\n');
                } catch (Exception ignored) {}
            }
            hist.append(now).append(',').append(pct).append(',').append(temp).append(',')
                .append(ram).append(',').append(charging ? 1 : 0).append('\n');
            e.putString("history", hist.toString()).putLong("last_history", now);
        }
        e.apply();
    }
    private void stopMonitor() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("active", false).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onDestroy() { active = false; handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}

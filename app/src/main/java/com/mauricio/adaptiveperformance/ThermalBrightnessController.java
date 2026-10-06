package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.Locale;

public final class ThermalBrightnessController {
    private static final long MIN_HOLD_MS = 120_000L;

    private final Context context;
    private final SharedPreferences prefs;
    private final IPrivilegedService privileged;

    private volatile boolean applied;
    private long appliedAt;
    private String originalMode;
    private String originalBrightness;
    private String originalAdj;

    public ThermalBrightnessController(Context context, SharedPreferences prefs,
                                       IPrivilegedService privileged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.privileged = privileged;
        this.applied = prefs.getBoolean("thermal_brightness_applied", false);
        this.appliedAt = prefs.getLong("thermal_brightness_applied_elapsed", 0L);
        this.originalMode = prefs.getString("thermal_brightness_original_mode", "");
        this.originalBrightness = prefs.getString("thermal_brightness_original_value", "");
        this.originalAdj = prefs.getString("thermal_brightness_original_adj", "");
    }

    public synchronized void update(float socTemp, float batteryTemp, int thermalLevel,
                                    int predictedLevel, boolean interactive) {
        if (!prefs.getBoolean("thermal_brightness_control", true)) {
            if (applied) restore();
            return;
        }

        int severity = Math.max(thermalLevel, predictedLevel);
        if (socTemp >= 66f || batteryTemp >= 42f) severity = Math.max(severity, 5);
        else if (socTemp >= 63f || batteryTemp >= 40f) severity = Math.max(severity, 4);

        boolean shouldApply = interactive && severity >= 4;
        boolean coolEnough = (socTemp <= 0f || socTemp <= 57f)
                && (batteryTemp <= 0f || batteryTemp <= 37.5f)
                && thermalLevel <= 1 && predictedLevel <= 1;

        if (shouldApply && !applied) {
            apply(severity);
        } else if (applied && coolEnough &&
                SystemClock.elapsedRealtime() - appliedAt >= MIN_HOLD_MS) {
            restore();
        }

        prefs.edit()
                .putInt("thermal_brightness_severity", severity)
                .putBoolean("thermal_brightness_applied", applied)
                .apply();
    }

    private void apply(int severity) {
        try {
            String raw = privileged.exec(
                    "settings get system screen_brightness_mode; " +
                    "settings get system screen_brightness; " +
                    "settings get system screen_auto_brightness_adj");
            String[] lines = raw == null ? new String[0] : raw.trim().split("\n");
            String mode = lines.length > 0 ? lines[0].trim() : "";
            String brightness = lines.length > 1 ? lines[1].trim() : "";
            String adj = lines.length > 2 ? lines[2].trim() : "";

            originalMode = mode;
            originalBrightness = brightness;
            originalAdj = adj;

            if ("1".equals(mode)) {
                float currentAdj = parseFloat(adj, 0f);
                float cap = severity >= 5 ? -0.45f : -0.30f;
                float target = Math.min(currentAdj, cap);
                privileged.exec(String.format(Locale.US,
                        "settings put system screen_auto_brightness_adj %.3f", target));
                prefs.edit().putString("thermal_brightness_action",
                        String.format(Locale.US, "Auto brilho: ajuste %.2f", target)).apply();
            } else {
                int current = parseInt(brightness, -1);
                if (current <= 0) return;
                int reductionPct = severity >= 5 ? 40 : 25;
                int target = Math.max(8, Math.round(current * (100 - reductionPct) / 100f));
                if (target >= current) return;
                privileged.exec("settings put system screen_brightness " + target);
                prefs.edit().putString("thermal_brightness_action",
                        "Brilho manual: " + current + " → " + target).apply();
            }

            applied = true;
            appliedAt = SystemClock.elapsedRealtime();
            prefs.edit()
                    .putBoolean("thermal_brightness_applied", true)
                    .putLong("thermal_brightness_applied_elapsed", appliedAt)
                    .putString("thermal_brightness_original_mode", originalMode)
                    .putString("thermal_brightness_original_value", originalBrightness)
                    .putString("thermal_brightness_original_adj", originalAdj)
                    .apply();
        } catch (Throwable t) {
            prefs.edit().putString("thermal_brightness_error",
                    t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
        }
    }

    public synchronized void restore() {
        if (!applied) return;
        try {
            if ("1".equals(originalMode)) {
                if (isSettingValue(originalAdj)) {
                    privileged.exec("settings put system screen_auto_brightness_adj " + originalAdj);
                } else {
                    privileged.exec("settings delete system screen_auto_brightness_adj");
                }
            } else if (isSettingValue(originalBrightness)) {
                privileged.exec("settings put system screen_brightness " + originalBrightness);
            }
        } catch (Throwable t) {
            prefs.edit().putString("thermal_brightness_restore_error",
                    t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
            return;
        }

        applied = false;
        appliedAt = 0L;
        prefs.edit()
                .putBoolean("thermal_brightness_applied", false)
                .remove("thermal_brightness_applied_elapsed")
                .putString("thermal_brightness_action", "Brilho restaurado")
                .apply();
    }

    private boolean isSettingValue(String s) {
        return s != null && !s.isEmpty() && !"null".equalsIgnoreCase(s);
    }

    private float parseFloat(String s, float fallback) {
        try { return Float.parseFloat(s); } catch (Throwable t) { return fallback; }
    }

    private int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return fallback; }
    }
}

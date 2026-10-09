package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.Locale;
import java.util.Map;

/** Native, conservative prediction with an OEM-safe unavailable fallback. */
final class NativeThermalForecast {
    private final SharedPreferences prefs;
    private PowerManager power;
    private PowerManager.OnThermalStatusChangedListener listener;
    private long lastRead;
    private int consecutiveHigh;
    private boolean predictedRisk;

    NativeThermalForecast(SharedPreferences prefs) { this.prefs = prefs; }

    void attach(PowerManager pm, Runnable onChange) {
        power = pm;
        if (pm == null || Build.VERSION.SDK_INT < 29) return;
        try {
            listener = status -> {
                prefs.edit().putInt("native_thermal_status", status)
                        .putLong("native_thermal_event_at", System.currentTimeMillis()).apply();
                if (status >= PowerManager.THERMAL_STATUS_MODERATE) onChange.run();
            };
            pm.addThermalStatusListener(listener);
            prefs.edit().putString("native_thermal_api", "listener ativo").apply();
        } catch (Throwable t) {
            listener = null;
            prefs.edit().putString("native_thermal_api", "status por leitura periódica").apply();
        }
    }

    boolean sample(boolean interactive) {
        if (power == null || Build.VERSION.SDK_INT < 30) {
            prefs.edit().putString("native_thermal_api", "indisponível neste Android").apply();
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastRead != 0L && now - lastRead < 20000L) return predictedRisk;
        lastRead = now;
        try {
            float forecast = power.getThermalHeadroom(30);
            int status = power.getCurrentThermalStatus();
            // This app can itself override thermalservice status; never treat its own
            // synthetic status as independent evidence of real overheating.
            boolean ownOverride = prefs.getInt("thermal_level", 0) > 0;
            if (Float.isNaN(forecast) || Float.isInfinite(forecast) || forecast < 0) {
                consecutiveHigh = 0;
                predictedRisk = !ownOverride && status >= PowerManager.THERMAL_STATUS_MODERATE;
                prefs.edit().putString("native_thermal_api",
                        "headroom indisponível pela ROM; usando estado térmico").apply();
                return predictedRisk;
            }
            float warning = 0.85f;
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    Map<Integer, Float> thresholds = power.getThermalHeadroomThresholds();
                    Float moderate = thresholds.get(PowerManager.THERMAL_STATUS_MODERATE);
                    if (moderate != null && Float.isFinite(moderate) && moderate > 0.3f)
                        warning = Math.min(0.95f, moderate);
                } catch (Throwable ignored) { }
            }
            consecutiveHigh = forecast >= warning ? Math.min(3, consecutiveHigh+1) : 0;
            predictedRisk = (!ownOverride && status >= PowerManager.THERMAL_STATUS_MODERATE)
                    || (interactive && consecutiveHigh >= 2);
            prefs.edit().putFloat("native_thermal_headroom_30s", forecast)
                    .putInt("native_thermal_status", status)
                    .putFloat("native_thermal_warning_threshold", warning)
                    .putBoolean("native_thermal_prediction_risk", predictedRisk)
                    .putString("native_thermal_api", "disponível")
                    .putString("native_thermal_forecast",
                            String.format(Locale.US,
                                    "30s: %.2f • limite %.2f • estado %d • %s",
                                    forecast, warning, status,
                                    predictedRisk ? "risco previsto" :
                                        (ownOverride ? "status simulado; verificando headroom" : "normal")))
                    .apply();
        } catch (Throwable t) {
            consecutiveHigh = 0;
            predictedRisk = false;
            prefs.edit().putString("native_thermal_api", "limitada pela ROM").apply();
        }
        return predictedRisk;
    }

    void close() {
        if (listener != null && power != null && Build.VERSION.SDK_INT >= 29) {
            try { power.removeThermalStatusListener(listener); }
            catch (Throwable ignored) {}
        }
        listener = null;
    }
}

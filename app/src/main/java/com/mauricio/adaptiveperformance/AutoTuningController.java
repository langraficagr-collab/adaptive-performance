package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;

/** Tests reversible options sequentially using bounded 30-minute A/B evidence windows. */
final class AutoTuningController {
    private final SharedPreferences prefs;
    AutoTuningController(SharedPreferences prefs) { this.prefs = prefs; }

    void evaluate(int batteryPct, float tempC, double cpuLoad, double freeRamPct,
                  boolean interactive, boolean charging, float powerW) {
        if (!"auto".equals(prefs.getString("user_mode", "auto"))) {
            ConservativeTuningController.stopIfNeeded(prefs);
            return;
        }
        ConservativeTuningController.evaluate(prefs, batteryPct, tempC, cpuLoad,
                freeRamPct, interactive, charging, powerW);
    }

    static void resetSession(SharedPreferences prefs) {
        ConservativeTuningController.resetSession(prefs);
    }
}

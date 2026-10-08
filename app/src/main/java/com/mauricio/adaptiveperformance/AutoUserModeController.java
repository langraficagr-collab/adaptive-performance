package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;

/** Battery-first policy layer for users who prefer automatic configuration. */
public final class AutoUserModeController {
    private static final long APPLY_INTERVAL_MS = 15L * 60L * 1000L;
    private static final long TRIAL_WINDOW_MS = 4L * 60L * 60L * 1000L;

    private AutoUserModeController() {}

    public static boolean enabled(SharedPreferences prefs) {
        return "auto".equals(prefs.getString("user_mode", "auto"));
    }

    public static void evaluate(SharedPreferences prefs, int batteryPct, float tempC,
                                double cpuLoad, double freeRamPct, boolean interactive,
                                boolean charging, float powerW) {
        if (!enabled(prefs)) return;
        long now = System.currentTimeMillis();
        long last = prefs.getLong("auto_user_last_apply", 0L);
        if (last > 0L && now - last < APPLY_INTERVAL_MS) return;

        updateLearning(prefs, now, batteryPct, tempC, cpuLoad, powerW, charging);

        int variant = prefs.getInt("auto_user_best_variant", 1);
        int trial = prefs.getInt("auto_user_trial_variant", variant);
        long trialStart = prefs.getLong("auto_user_trial_start", 0L);
        if (trialStart <= 0L) {
            trialStart = now;
            trial = variant;
        } else if (!charging && now - trialStart >= TRIAL_WINDOW_MS) {
            float score = scoreWindow(prefs, batteryPct, tempC, cpuLoad, powerW);
            int best = prefs.getInt("auto_user_best_variant", 1);
            float bestScore = prefs.getFloat("auto_user_best_score", Float.MAX_VALUE);
            if (score < bestScore) {
                best = trial;
                bestScore = score;
            }
            trial = (trial + 1) % 3;
            trialStart = now;
            prefs.edit()
                    .putInt("auto_user_best_variant", best)
                    .putFloat("auto_user_best_score", bestScore)
                    .putInt("auto_user_trial_variant", trial)
                    .putLong("auto_user_trial_start", trialStart)
                    .putInt("auto_user_trial_start_battery", batteryPct)
                    .apply();
        }

        int effective = charging ? variant : trial;
        int goalPressure = batteryGoalPressure(prefs, batteryPct, charging, now);
        effective = Math.max(effective, goalPressure);
        if (!charging && batteryPct >= 0 && batteryPct <= 50) effective = Math.max(effective, 1);
        if (batteryPct >= 0 && batteryPct <= 20) effective = 2;
        if (tempC >= 39f) effective = Math.max(effective, 2);
        if (!interactive) effective = Math.max(effective, 1);

        applyVariant(prefs, effective, freeRamPct, batteryPct, interactive);
        prefs.edit()
                .putLong("auto_user_last_apply", now)
                .putString("auto_user_status", describe(effective, trial, variant, batteryPct, tempC)
                        + batteryGoalStatus(prefs, batteryPct, charging, now))
                .apply();
    }

    private static int batteryGoalPressure(SharedPreferences prefs, int batteryPct, boolean charging, long now) {
        if (charging || batteryPct < 0) return 0;
        int target = Math.max(10, Math.min(50, prefs.getInt("battery_goal_pct", 20)));
        int hour = Math.max(0, Math.min(23, prefs.getInt("battery_goal_hour", 22)));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        java.util.Calendar end = (java.util.Calendar) cal.clone();
        end.set(java.util.Calendar.HOUR_OF_DAY, hour);
        end.set(java.util.Calendar.MINUTE, 0);
        end.set(java.util.Calendar.SECOND, 0);
        end.set(java.util.Calendar.MILLISECOND, 0);
        if (!end.after(cal)) end.add(java.util.Calendar.DAY_OF_YEAR, 1);
        double hours = Math.max(0.25, (end.getTimeInMillis() - now) / 3600000.0);
        double budgetPerHour = Math.max(0.0, batteryPct - target) / hours;
        prefs.edit().putFloat("battery_goal_budget_per_hour", (float) budgetPerHour).apply();
        if (batteryPct <= target + 3) return 2;
        if (budgetPerHour < 1.0) return 2;
        if (budgetPerHour < 2.0) return 1;
        return 0;
    }

    private static String batteryGoalStatus(SharedPreferences prefs, int batteryPct, boolean charging, long now) {
        if (charging || batteryPct < 0) return "";
        int target = prefs.getInt("battery_goal_pct", 20);
        int hour = prefs.getInt("battery_goal_hour", 22);
        float budget = prefs.getFloat("battery_goal_budget_per_hour", -1f);
        return budget >= 0 ? String.format(java.util.Locale.US, " • meta %d%% às %02d:00 • limite %.1f%%/h", target, hour, budget) : "";
    }

    private static void applyVariant(SharedPreferences prefs, int variant, double freeRamPct,
                                     int batteryPct, boolean interactive) {
        boolean stronger = variant >= 1;
        boolean maximum = variant >= 2;
        int compactAt = freeRamPct < 25 ? 60 : (stronger ? 50 : 45);
        int ramLimit = maximum ? 350 : (stronger ? 450 : 600);

        SharedPreferences.Editor e = prefs.edit();
        // Core intelligence and safety features.
        e.putBoolean("smart_suite_enabled", true)
                .putBoolean("advanced_adaptive", true)
                .putBoolean("auto_calibration", true)
                .putBoolean("app_learning", true)
                .putBoolean("app_profiles", true)
                .putBoolean("thermal_prediction", true)
                .putBoolean("health_guard", true)
                .putBoolean("rollback_guard", true)
                .putBoolean("over_optimization_guard", true)
                .putBoolean("auto_repair", true)
                .putBoolean("action_effectiveness", true)
                .putBoolean("time_usage_learning", true)
                .putBoolean("history_24h", true)
                .putBoolean("deep_sleep_monitor", true)
                .putBoolean("screen_off_optimization", true)
                .putBoolean("system_battery_guard", true)
                .putBoolean("system_deep_idle", true)
                .putBoolean("system_radio_savings", true)
                .putBoolean("dynamic_doze_whitelist", true)
                .putBoolean("adaptive_screen_timeout", true)
                .putBoolean("adaptive_refresh", true)
                .putBoolean("low_battery_adaptive", true)
                .putBoolean("zram_thrash_guard", true)
                .putBoolean("memory_leak_detector", true)
                .putBoolean("thermal_brightness_control", true)
                .putBoolean("critical_cleanup", true)
                .putBoolean("auto_bug_cleanup", true)
                .putBoolean("auto_unused_restrict", stronger)
                .putBoolean("wakeup_network_guard", true)
                .putBoolean("cpu_pressure_control", true)
                .putBoolean("adaptive_aggressiveness", true)
                .putBoolean("diagnostic_only", false)
                .putBoolean("extended_safe_mode_manual", false)
                .putString("thermal_mode", "auto")
                .putString("zram_profile", "auto")
                .putBoolean("memory_compaction_enabled", true)
                .putInt("memory_compaction_threshold_pct", compactAt)
                .putBoolean("aggressive_memory_cleanup", maximum && freeRamPct < 20)
                .putBoolean("app_ram_limiter_enabled", maximum)
                .putInt("app_ram_limit_mb", ramLimit)
                .putBoolean("maximum_battery_mode", maximum || (batteryPct >= 0 && batteryPct <= 20))
                .putBoolean("smart_auto_cleanup", stronger && !interactive)
                .putBoolean("adaptive_freeze_enabled", maximum && !interactive)
                .apply();
    }

    private static void updateLearning(SharedPreferences prefs, long now, int batteryPct,
                                       float tempC, double cpuLoad, float powerW, boolean charging) {
        float oldTemp = prefs.getFloat("auto_user_avg_temp", tempC > 0 ? tempC : 0f);
        float oldCpu = prefs.getFloat("auto_user_avg_cpu", cpuLoad >= 0 ? (float) cpuLoad : 0f);
        float oldPower = prefs.getFloat("auto_user_avg_power", powerW > 0 ? powerW : 0f);
        float alpha = 0.12f;
        float avgTemp = tempC > 0 ? oldTemp * (1f-alpha) + tempC * alpha : oldTemp;
        float avgCpu = cpuLoad >= 0 ? oldCpu * (1f-alpha) + (float)cpuLoad * alpha : oldCpu;
        float avgPower = powerW > 0 ? oldPower * (1f-alpha) + powerW * alpha : oldPower;
        SharedPreferences.Editor e = prefs.edit()
                .putFloat("auto_user_avg_temp", avgTemp)
                .putFloat("auto_user_avg_cpu", avgCpu)
                .putFloat("auto_user_avg_power", avgPower)
                .putLong("auto_user_learning_updated", now);
        if (!charging && prefs.getInt("auto_user_trial_start_battery", -1) < 0 && batteryPct >= 0)
            e.putInt("auto_user_trial_start_battery", batteryPct);
        e.apply();
    }

    private static float scoreWindow(SharedPreferences prefs, int batteryPct, float tempC,
                                     double cpuLoad, float powerW) {
        int startBattery = prefs.getInt("auto_user_trial_start_battery", batteryPct);
        float drain = (batteryPct >= 0 && startBattery >= batteryPct) ? startBattery - batteryPct : 0f;
        float power = powerW > 0 ? powerW : prefs.getFloat("auto_user_avg_power", 0f);
        float cpu = cpuLoad >= 0 ? (float)cpuLoad : prefs.getFloat("auto_user_avg_cpu", 0f);
        float heatPenalty = Math.max(0f, tempC - 36f) * 0.8f;
        // Lower is better; battery drain dominates, then power/heat, then CPU overhead.
        return drain * 10f + power * 1.5f + heatPenalty + cpu * 0.03f;
    }

    private static String describe(int effective, int trial, int best, int batteryPct, float tempC) {
        String[] names = {"Equilibrado econômico", "Economia adaptativa", "Máxima economia"};
        String n = names[Math.max(0, Math.min(2, effective))];
        return n + " • aprendendo pelo uso • teste " + (trial + 1) + "/3 • melhor " + (best + 1)
                + (batteryPct >= 0 ? " • bateria " + batteryPct + "%" : "");
    }
}

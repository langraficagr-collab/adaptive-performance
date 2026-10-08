package com.mauricio.adaptiveperformance;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pré-carrega os apps usados recentemente sem transformá-los em exceções de congelamento. */
final class AdaptivePreloadController {
    private static final long CYCLE_INTERVAL_MS = 15L * 60L * 1000L;
    private static final long USAGE_WINDOW_MS = 15L * 60L * 1000L;
    private static final int MAX_APPS = 4;
    private static final int CONSERVATIVE_MAX_APPS = 2;
    private static final int CONSERVATIVE_BUDGET_MB = 32;

    private final Context context;
    private final SharedPreferences prefs;

    AdaptivePreloadController(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    void evaluate(IPrivilegedService privileged, double availablePct, float temperatureC,
                  float batteryTemperatureC, boolean interactive, boolean charging,
                  int batteryPct, boolean automaticMode, float powerW) {
        long now = System.currentTimeMillis();
        if (BuildConfig.CONSERVATIVE_MODE
                && prefs.getBoolean("auto_preload_monitor_pending", false)) {
            boolean sameScreenState = prefs.getBoolean("auto_preload_monitor_interactive", interactive)
                    == interactive;
            float priorControlTemp = prefs.getFloat("auto_preload_monitor_control_temp", -1f);
            float priorBatteryTemp = prefs.getFloat("auto_preload_monitor_battery_temp", -1f);
            float priorPower = prefs.getFloat("auto_preload_monitor_power_w", -1f);
            boolean hotter = (sameScreenState && priorControlTemp > 0f && temperatureC > 0f
                    && temperatureC >= priorControlTemp + 1.5f)
                    || (sameScreenState && priorBatteryTemp > 0f && batteryTemperatureC > 0f
                    && batteryTemperatureC >= priorBatteryTemp + 1.5f);
            boolean higherDraw = sameScreenState && priorPower > 0f && powerW > 0f
                    && powerW >= priorPower * 1.5f;
            prefs.edit().putBoolean("auto_preload_monitor_pending", false).apply();
            if (hotter || higherDraw) {
                String rollback = hotter
                        ? "Pré-carga desativada: temperatura subiu após o ciclo"
                        : "Pré-carga desativada: consumo subiu após o ciclo";
                prefs.edit().putBoolean("auto_preload_enabled", false)
                        .putStringSet("auto_preload_apps", new LinkedHashSet<>())
                        .putInt("auto_preload_count", 0)
                        .putInt("auto_preload_budget_mb", 0)
                        .putLong("auto_preload_cycle_at", now)
                        .putString("auto_preload_status", rollback)
                        .putString("auto_preload_last_result", rollback)
                        .apply();
                return;
            }
        }
        if (!prefs.getBoolean("auto_preload_enabled", true)) {
            if (!prefs.getString("auto_preload_status", "").startsWith("Pré-carga desativada")) {
                setStatus("Pré-carga desativada pelo usuário", 0, now);
            }
            return;
        }
        if (BuildConfig.CONSERVATIVE_MODE && !automaticMode) {
            setStatus("Pré-carga disponível somente no modo Automático", 0, now);
            return;
        }
        long last = prefs.getLong("auto_preload_cycle_at", 0L);
        if (last > 0L && now - last < CYCLE_INTERVAL_MS) return;
        if (!interactive) {
            setStatus("Aguardando a tela ficar ativa para pré-carregar", 0, now);
            return;
        }
        if (BuildConfig.CONSERVATIVE_MODE) {
            String pauseReason = conservativePauseReason(
                    availablePct, temperatureC, batteryTemperatureC, charging, batteryPct);
            if (!pauseReason.isEmpty()) {
                setStatus("Pré-carga pausada: " + pauseReason, 0, now);
                return;
            }
        }
        if (privileged == null) {
            setStatus("Shizuku indisponível para pré-carregamento", 0, now);
            return;
        }

        List<String> apps = mostUsedApps(now);
        if (apps.isEmpty()) {
            setStatus("Nenhum app elegível usado nos últimos 15 minutos", 0, now);
            return;
        }

        float thermalBudgetTemperature = temperatureC > 0f ? temperatureC : batteryTemperatureC;
        if (batteryTemperatureC > thermalBudgetTemperature) thermalBudgetTemperature = batteryTemperatureC;
        int budgetMb = chooseBudgetMb(availablePct, BuildConfig.CONSERVATIVE_MODE
                ? batteryTemperatureC : thermalBudgetTemperature);
        if (automaticMode && !charging && batteryPct >= 0) {
            if (batteryPct <= 15) budgetMb = 0;
            else if (batteryPct <= 30) budgetMb = Math.min(budgetMb, 32);
            else if (batteryPct <= 50) budgetMb = Math.min(budgetMb, 64);
        }
        if (batteryTemperatureC >= 40f || temperatureC >= 60f) budgetMb = Math.min(budgetMb, 32);
        if (BuildConfig.CONSERVATIVE_MODE) {
            budgetMb = Math.min(budgetMb, CONSERVATIVE_BUDGET_MB);
        }
        if (budgetMb <= 0) {
            setStatus(String.format(java.util.Locale.US,
                    "Pré-carregamento pausado: RAM %.0f%% • %.1f°C", availablePct, temperatureC),
                    0, now);
            prefs.edit().putLong("auto_preload_cycle_at", now).apply();
            return;
        }

        int perAppMb = Math.max(16, budgetMb / Math.max(1, apps.size()));
        String command = BuildConfig.CONSERVATIVE_MODE
                ? buildConservativeCommand(apps, perAppMb)
                : buildCommand(apps, perAppMb);
        String result;
        try {
            result = privileged.exec(command);
        } catch (Throwable error) {
            result = "erro: " + error.getClass().getSimpleName();
        }
        String normalizedResult = result == null ? "" : result.trim();
        boolean preloadFailed = normalizedResult.toLowerCase(java.util.Locale.US).matches(
                "(?s).*(^erro:|permission denied|not found|exception|failed|error).*");
        if (BuildConfig.CONSERVATIVE_MODE
                && !normalizedResult.matches("(?s).*WARMED_BYTES=[1-9][0-9]*.*")) {
            preloadFailed = true;
        }
        int warmedAppCount = preloadFailed ? 0 : apps.size();
        String preloadStatus = preloadFailed
                ? "Falha/nenhum APK aquecido • " + normalizedResult
                : (BuildConfig.CONSERVATIVE_MODE ? "APKs aquecidos " : "Pré-carregados ")
                        + warmedAppCount + " app(s) • " + budgetMb
                        + " MB máx. total • " + perAppMb + " MB/app";
        SharedPreferences.Editor resultEditor = prefs.edit()
                .putStringSet("auto_preload_apps", new LinkedHashSet<>(
                        preloadFailed ? new ArrayList<String>() : apps))
                .putInt("auto_preload_count", warmedAppCount)
                .putInt("auto_preload_budget_mb", preloadFailed ? 0 : budgetMb)
                .putInt("auto_preload_per_app_mb", perAppMb)
                .putFloat("auto_preload_ram_pct", (float) availablePct)
                .putFloat("auto_preload_temperature_c", temperatureC)
                .putLong("auto_preload_cycle_at", now)
                .putString("auto_preload_status", preloadStatus)
                .putString("auto_preload_last_result", normalizedResult);
        if (BuildConfig.CONSERVATIVE_MODE) {
            resultEditor.putBoolean("auto_preload_monitor_pending", !preloadFailed)
                    .putBoolean("auto_preload_monitor_interactive", interactive)
                    .putFloat("auto_preload_monitor_control_temp", temperatureC)
                    .putFloat("auto_preload_monitor_battery_temp", batteryTemperatureC)
                    .putFloat("auto_preload_monitor_power_w", powerW);
        }
        resultEditor.apply();
    }

    private String conservativePauseReason(double availablePct, float temperatureC,
                                            float batteryTemperatureC, boolean charging,
                                            int batteryPct) {
        if (charging) return "aguardando sair do carregador";
        if (batteryPct >= 0 && batteryPct <= 30) return "bateria em 30% ou menos";
        if (availablePct < 22.0) return "RAM disponível abaixo de 22%";
        if (batteryTemperatureC <= 0f) return "temperatura da bateria indisponível";
        if (batteryTemperatureC >= 38.0f) return String.format(java.util.Locale.US,
                "bateria quente (%.1f°C)", batteryTemperatureC);
        if (temperatureC >= 56.0f) return String.format(java.util.Locale.US,
                "processador quente (%.1f°C)", temperatureC);
        return "";
    }

    private List<String> mostUsedApps(long now) {
        List<String> result = new ArrayList<>();
        try {
            UsageStatsManager manager = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (manager == null) return result;
            Map<String, UsageStats> stats = manager.queryAndAggregateUsageStats(
                    now - USAGE_WINDOW_MS, now);
            List<UsageStats> ranked = new ArrayList<>(stats.values());
            ranked.sort((a, b) -> {
                int byTime = Long.compare(recentTime(b), recentTime(a));
                return byTime != 0 ? byTime : Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed());
            });
            for (UsageStats stat : ranked) {
                String pkg = stat.getPackageName();
                if (pkg.equals(context.getPackageName())
                        || !pkg.matches("[A-Za-z0-9_.]+")
                        || recentTime(stat) <= 0L
                        || now - stat.getLastTimeUsed() > USAGE_WINDOW_MS
                        || !AppSafety.isEligibleForAdaptiveOptimization(context, pkg)) continue;
                result.add(pkg);
                int appLimit = BuildConfig.CONSERVATIVE_MODE
                        ? CONSERVATIVE_MAX_APPS : MAX_APPS;
                if (result.size() == appLimit) break;
            }
        } catch (Throwable ignored) { }
        return result;
    }

    private long recentTime(UsageStats stat) {
        long value = stat.getTotalTimeInForeground();
        if (Build.VERSION.SDK_INT >= 29) value = Math.max(value, stat.getTotalTimeVisible());
        return value;
    }

    private int chooseBudgetMb(double availablePct, float temperatureC) {
        if (availablePct < 12.0 || temperatureC >= 45.0f) return 0;
        int budget;
        if (availablePct >= 55.0) budget = 256;
        else if (availablePct >= 40.0) budget = 192;
        else if (availablePct >= 30.0) budget = 128;
        else if (availablePct >= 22.0) budget = 96;
        else if (availablePct >= 16.0) budget = 64;
        else budget = 32;

        if (temperatureC >= 42.0f) budget = Math.min(budget, 32);
        else if (temperatureC >= 40.0f) budget = Math.min(budget, 64);
        else if (temperatureC >= 38.0f) budget = Math.min(budget, 96);
        return Math.max(16, budget);
    }

    private String buildConservativeCommand(List<String> apps, int perAppMb) {
        int perAppBytes = perAppMb * 1048576;
        int totalBytes = perAppBytes * Math.max(1, apps.size());
        StringBuilder command = new StringBuilder();
        command.append("MAX_BYTES=").append(totalBytes)
                .append("; APP_MAX_BYTES=").append(perAppBytes)
                .append("; used=0; warmed=0; app_used=0; ");
        command.append("read_one(){ f=\"$1\"; s=$(stat -c %s \"$f\" 2>/dev/null || echo 0); ");
        command.append("[ \"$s\" -gt 0 ] || return; total_left=$((MAX_BYTES-used)); ");
        command.append("app_left=$((APP_MAX_BYTES-app_used)); ");
        command.append("[ \"$total_left\" -gt 0 ] && [ \"$app_left\" -gt 0 ] || return; ");
        command.append("remain=$total_left; [ \"$app_left\" -lt \"$remain\" ] && remain=$app_left; ");
        command.append("if [ \"$s\" -gt \"$remain\" ]; then n=$(( (remain+1048575)/1048576 )); ");
        command.append("dd if=\"$f\" of=/dev/null bs=1M count=\"$n\" 2>/dev/null; ");
        command.append("used=$((used+remain)); app_used=$((app_used+remain)); warmed=$used; ");
        command.append("else cat \"$f\" >/dev/null 2>/dev/null; used=$((used+s)); ");
        command.append("app_used=$((app_used+s)); warmed=$used; fi; }; ");
        for (String pkg : apps) {
            command.append("app_used=0; for f in $(cmd package path ").append(pkg)
                    .append(" | sed 's/^package://'); do read_one \"$f\"; done; ");
        }
        command.append("echo WARMED_BYTES=$warmed; echo LIMIT_BYTES=$MAX_BYTES");
        return command.toString();
    }

    private String buildCommand(List<String> apps, int perAppMb) {
        int bytes = perAppMb * 1048576;
        StringBuilder command = new StringBuilder();
        command.append("MAX_BYTES=").append(bytes).append("; used=0; warmed=0; ");
        command.append("read_one(){ f=\"$1\"; s=$(stat -c %s \"$f\" 2>/dev/null || echo 0); ");
        command.append("[ \"$s\" -gt 0 ] || return; remain=$((MAX_BYTES-used)); ");
        command.append("[ \"$remain\" -gt 0 ] || return; ");
        command.append("if [ \"$s\" -gt \"$remain\" ]; then n=$(( (remain+1048575)/1048576 )); ");
        command.append("dd if=\"$f\" of=/dev/null bs=1M count=\"$n\" 2>/dev/null; used=$MAX_BYTES; warmed=$MAX_BYTES; ");
        command.append("else cat \"$f\" >/dev/null 2>/dev/null; used=$((used+s)); warmed=$used; fi; }; ");
        for (String pkg : apps) {
            command.append("for f in $(cmd package path ").append(pkg)
                    .append(" | sed 's/^package://'); do read_one \"$f\"; done; ");
            command.append("for root in /sdcard/Android/data/").append(pkg)
                    .append("/files /sdcard/Android/obb/").append(pkg)
                    .append(" /sdcard/Android/media/").append(pkg).append("; do ");
            command.append("[ -d \"$root\" ] || continue; ");
            command.append("for f in $(find \"$root\" -type f -size -512M -print 2>/dev/null); do read_one \"$f\"; done; done; ");
        }
        command.append("echo WARMED_BYTES=$warmed; echo LIMIT_BYTES=$MAX_BYTES");
        return command.toString();
    }

    private void setStatus(String status, int count, long now) {
        prefs.edit().putStringSet("auto_preload_apps", new LinkedHashSet<>())
                .putInt("auto_preload_count", count)
                .putInt("auto_preload_budget_mb", 0)
                .putLong("auto_preload_cycle_at", now)
                .putString("auto_preload_status", status)
                .putString("auto_preload_last_result", status)
                .apply();
    }
}

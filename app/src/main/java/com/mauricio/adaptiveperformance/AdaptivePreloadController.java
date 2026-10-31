package com.mauricio.adaptiveperformance;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
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
        // Full edition also must not warm APKs during fast charging.
        boolean unsafeTemperature = batteryTemperatureC >= 38.0f
                || (temperatureC > 0f && temperatureC >= 55f);
        if (charging || unsafeTemperature) {
            String status = charging ? "Pré-carga pausada durante a carga"
                    : "Pré-carga pausada: aparelho aquecido";
            if (!prefs.getString("auto_preload_status", "").equals(status))
                prefs.edit().putString("auto_preload_status", status)
                        .putBoolean("auto_preload_monitor_pending", false).apply();
            return;
        }
        if (prefs.getBoolean("auto_preload_monitor_pending", false)) {
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
        if (AdaptiveBrainV2.avoidOptionalPreload(prefs)) {
            String reason=prefs.getString("v2_preload_skip_reason","");
            String status="Pré-carga adiada pelo Adaptive Brain 2"
                    + (reason.isEmpty()?"":": "+reason);
            if(!status.equals(prefs.getString("auto_preload_status","")))
                prefs.edit().putString("auto_preload_status",status).apply();
            return;
        }
        if (PersonalUsageML.avoidPreload(prefs)
                && System.currentTimeMillis() - prefs.getLong("ml_last_training_at",0L) <= 20L * 60L * 1000L) {
            if (!"Pré-carga pausada: previsão de uso/temperatura".equals(
                    prefs.getString("auto_preload_status", "")))
                prefs.edit().putString("auto_preload_status",
                        "Pré-carga pausada: previsão de uso/temperatura").apply();
            return;
        }
        if (!prefs.getBoolean("auto_preload_enabled", false)) {
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

        boolean manualMode = prefs.getBoolean("auto_preload_manual_mode", false);
        List<String> apps = manualMode ? selectedApps() : mostUsedApps(now);
        // Do not warm a new or unknown package: merely prioritize a transition
        // prediction IF the candidate is already in the 15-minute safe shortlist.
        if (!manualMode && automaticMode && prefs.getBoolean("v2_next_app_enabled",true)
                && !RoutineProfileClassifier.neverWarm(prefs.getString("v2_routine",""))
                && prefs.getInt("ml_training_samples",0)>=48) {
            String predicted=prefs.getString("v2_next_app","");
            if(!predicted.isEmpty() && apps.remove(predicted))
                apps.add(0,predicted);
        }
        if (apps.isEmpty()) {
            setStatus(manualMode ? "Nenhum aplicativo selecionado elegível"
                    : "Nenhum app elegível usado nos últimos 15 minutos", 0, now);
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

        Map<String, Integer> requested = new LinkedHashMap<>();
        int appCount = manualMode
                ? prefs.getInt("auto_preload_manual_count", 2)
                : (BuildConfig.CONSERVATIVE_MODE ? CONSERVATIVE_MAX_APPS : MAX_APPS);
        for (String pkg : apps) {
            requested.put(pkg, manualMode
                    ? prefs.getInt("auto_preload_quota_mb_" + pkg, 32) : budgetMb);
        }
        LinkedHashMap<String, Integer> allocation = PreloadAllocationPolicy.allocate(
                apps, requested, appCount, budgetMb);
        if (allocation.isEmpty()) {
            setStatus("Pré-carga pausada: orçamento de memória insuficiente", 0, now);
            return;
        }
        int allocatedMb = 0;
        for (int mb : allocation.values()) allocatedMb += mb;
        String command = buildPreloadCommand(allocation, allocatedMb,
                BuildConfig.CONSERVATIVE_MODE);
        String result;
        try {
            result = privileged.exec(command);
        } catch (Throwable error) {
            result = "erro: " + error.getClass().getSimpleName();
        }
        String normalizedResult = result == null ? "" : result.trim();
        boolean preloadFailed = !normalizedResult.matches("(?s).*WARMED_BYTES=[1-9][0-9]*.*")
                || normalizedResult.toLowerCase(java.util.Locale.US).matches(
                        "(?s).*(^erro:|permission denied|not found|exception|failed|error).*");
        LinkedHashSet<String> warmedPackages = new LinkedHashSet<>();
        if (!preloadFailed) {
            for (String line : normalizedResult.split("\\R")) {
                if (line.startsWith("WARMED_PKG=")) {
                    String packageName = line.substring("WARMED_PKG=".length()).trim();
                    if (allocation.containsKey(packageName)) warmedPackages.add(packageName);
                }
            }
        }
        int warmedAppCount = warmedPackages.size();
        String preloadStatus = preloadFailed
                ? "Falha/nenhum APK aquecido • " + normalizedResult
                : (BuildConfig.CONSERVATIVE_MODE ? "APKs aquecidos " : "Cache aquecido ")
                        + warmedAppCount + "/" + allocation.size()
                        + " app(s) • até " + allocatedMb + " MB de leitura"
                        + (manualMode ? " • seleção manual" : " • recentes");
        SharedPreferences.Editor resultEditor = prefs.edit()
                .putStringSet("auto_preload_apps", warmedPackages)
                .putInt("auto_preload_count", warmedAppCount)
                .putInt("auto_preload_budget_mb", preloadFailed ? 0 : allocatedMb)
                .putInt("auto_preload_per_app_mb", allocatedMb / Math.max(1, allocation.size()))
                .putFloat("auto_preload_ram_pct", (float) availablePct)
                .putFloat("auto_preload_temperature_c", temperatureC)
                .putLong("auto_preload_cycle_at", now)
                .putString("auto_preload_status", preloadStatus)
                .putString("auto_preload_last_result", normalizedResult);
        resultEditor.putBoolean("auto_preload_monitor_pending", !preloadFailed)
                    .putBoolean("auto_preload_monitor_interactive", interactive)
                    .putFloat("auto_preload_monitor_control_temp", temperatureC)
                    .putFloat("auto_preload_monitor_battery_temp", batteryTemperatureC)
                    .putFloat("auto_preload_monitor_power_w", powerW);
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

    private List<String> selectedApps() {
        Set<String> saved = prefs.getStringSet("auto_preload_manual_apps", Collections.emptySet());
        if (saved == null || saved.isEmpty()) return new ArrayList<>();
        List<String> apps = new ArrayList<>();
        for (String pkg : saved) {
            if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
                    || !AppSafety.isEligibleForAdaptiveOptimization(context, pkg)
                    || !AppSafety.isUserFacing(context, pkg)
                    || AppSafety.isSystemApp(context, pkg)) continue;
            apps.add(pkg);
        }
        apps.sort((a, b) -> {
            long ageA = prefs.getLong("app_last_foreground_at_" +
                    Integer.toHexString(a.hashCode()), 0L);
            long ageB = prefs.getLong("app_last_foreground_at_" +
                    Integer.toHexString(b.hashCode()), 0L);
            int recency = Long.compare(ageB, ageA);
            return recency != 0 ? recency : a.compareTo(b);
        });
        if (apps.size() > PreloadAllocationPolicy.MAX_APPS)
            return new ArrayList<>(apps.subList(0, PreloadAllocationPolicy.MAX_APPS));
        return apps;
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

    /**
     * Read at most the per-app budget and the global budget. This only warms APK
     * and optional file page cache, never pins the application process in RAM.
     */
    private String buildPreloadCommand(Map<String, Integer> quotas, int totalMb,
                                       boolean conservative) {
        StringBuilder command = new StringBuilder();
        command.append("MAX_BYTES=").append(totalMb * 1048576L)
                .append("; used=0; warmed=0; app_used=0; APP_MAX_BYTES=0; ");
        command.append("read_one(){ f=\\\"$1\\\"; s=$(stat -c %s \\\"$f\\\" 2>/dev/null || echo 0); ");
        command.append("[ \\\"$s\\\" -gt 0 ] || return; total_left=$((MAX_BYTES-used)); ");
        command.append("app_left=$((APP_MAX_BYTES-app_used)); ");
        command.append("[ \\\"$total_left\\\" -gt 0 ] && [ \\\"$app_left\\\" -gt 0 ] || return; ");
        command.append("take=$s; [ \\\"$total_left\\\" -lt \\\"$take\\\" ] && take=$total_left; ");
        command.append("[ \\\"$app_left\\\" -lt \\\"$take\\\" ] && take=$app_left; ");
        command.append("head -c \\\"$take\\\" \\\"$f\\\" >/dev/null 2>&1 && { ");
        command.append("used=$((used+take)); app_used=$((app_used+take)); warmed=$used; }; }; ");
        for (Map.Entry<String, Integer> entry : quotas.entrySet()) {
            String pkg = entry.getKey();
            if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) continue;
            command.append("APP_MAX_BYTES=").append(entry.getValue() * 1048576L)
                    .append("; app_used=0; before=$used; ");
            command.append("for f in $(cmd package path ").append(pkg)
                    .append(" | sed 's/^package://'); do read_one \\\"$f\\\"; done; ");
            if (!conservative) {
                command.append("for root in /sdcard/Android/data/").append(pkg)
                        .append("/files /sdcard/Android/obb/").append(pkg)
                        .append(" /sdcard/Android/media/").append(pkg).append("; do ");
                command.append("[ \\\"$used\\\" -ge \\\"$MAX_BYTES\\\" ] && break; ");
                command.append("[ -d \\\"$root\\\" ] || continue; ");
                command.append("for f in $(find \\\"$root\\\" -maxdepth 2 -type f -size -64M ");
                command.append("-print 2>/dev/null | head -n 4); do ");
                command.append("[ \\\"$used\\\" -ge \\\"$MAX_BYTES\\\" ] && break; ");
                command.append("read_one \\\"$f\\\"; done; done; ");
            }
            command.append("[ \\\"$used\\\" -gt \\\"$before\\\" ] && echo WARMED_PKG=")
                    .append(pkg).append("; ");
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

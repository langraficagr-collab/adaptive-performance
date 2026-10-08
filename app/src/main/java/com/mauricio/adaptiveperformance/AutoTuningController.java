package com.mauricio.adaptiveperformance;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.*;

final class AutoTuningController {
    private static final long EVAL_INTERVAL_MS = 30L * 60L * 1000L;
    private static final long TRIAL_MS = 2L * 60L * 60L * 1000L;
    private static final long USAGE_REFRESH_MS = 6L * 60L * 60L * 1000L;

    private final Context context;
    private final SharedPreferences prefs;

    AutoTuningController(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    void evaluate(int batteryPct, float tempC, double cpuLoad, double freeRamPct,
                  boolean interactive, boolean charging, float powerW) {
        if (!"auto".equals(prefs.getString("user_mode", "auto"))) return;
        long now = System.currentTimeMillis();
        refreshFrequentApps(now);

        long last = prefs.getLong("auto_tune_last_eval", 0L);
        if (last > 0L && now - last < EVAL_INTERVAL_MS) {
            applyCurrent();
            return;
        }

        int phase = prefs.getInt("auto_tune_phase", 0);
        int candidate = prefs.getInt("auto_tune_candidate", 0);
        long started = prefs.getLong("auto_tune_trial_started", 0L);
        if (started <= 0L) {
            started = now;
            prefs.edit().putLong("auto_tune_trial_started", now).apply();
            snapshotTrialStart(batteryPct, tempC, cpuLoad, powerW);
        }

        accumulate(tempC, cpuLoad, freeRamPct, powerW);
        if (!charging && now - started >= TRIAL_MS && prefs.getInt("auto_tune_samples", 0) >= 3) {
            float score = score(batteryPct);
            String key = "auto_tune_best_score_" + phase;
            float best = prefs.getFloat(key, Float.MAX_VALUE);
            if (score < best) {
                prefs.edit().putFloat(key, score).putInt("auto_tune_best_candidate_" + phase, candidate).apply();
            }
            candidate++;
            if (candidate >= candidateCount(phase)) {
                phase++;
                candidate = 0;
            }
            if (phase >= 9) {
                phase = 0;
                candidate = 0;
                prefs.edit().putLong("auto_tune_completed_at", now).apply();
            }
            resetTrial(now, batteryPct, tempC, cpuLoad, powerW);
        }

        prefs.edit()
                .putInt("auto_tune_phase", phase)
                .putInt("auto_tune_candidate", candidate)
                .putLong("auto_tune_last_eval", now)
                .putString("auto_tune_status", "Testando " + phaseName(phase) + " • nível " + (candidate + 1) + "/" + candidateCount(phase))
                .apply();
        applyCurrent();
    }

    private void refreshFrequentApps(long now) {
        long last = prefs.getLong("auto_frequent_refresh_at", 0L);
        if (last > 0L && now - last < USAGE_REFRESH_MS) return;
        Set<String> frequent = new HashSet<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - 7L*24L*60L*60L*1000L, now);
                for (UsageStats u : stats.values()) {
                    String pkg = u.getPackageName();
                    long visible = Build.VERSION.SDK_INT >= 29 ? u.getTotalTimeVisible() : u.getTotalTimeInForeground();
                    long lastUse = u.getLastTimeUsed();
                    if ((visible >= 30L*60L*1000L || now - lastUse <= 2L*60L*60L*1000L)
                            && AppSafety.isEligibleForAdaptiveOptimization(context, pkg)) {
                        frequent.add(pkg);
                    }
                }
            }
        } catch (Throwable ignored) {}
        prefs.edit().putStringSet("auto_frequent_apps", frequent)
                .putInt("auto_frequent_count", frequent.size())
                .putLong("auto_frequent_refresh_at", now).apply();
    }

    private void applyCurrent() {
        int phase = prefs.getInt("auto_tune_phase", 0);
        int candidate = prefs.getInt("auto_tune_candidate", 0);
        SharedPreferences.Editor e = prefs.edit();
        for (int p = 0; p < 9; p++) {
            if (p == phase) continue;
            String key = "auto_tune_best_candidate_" + p;
            if (prefs.contains(key)) applyParameter(e, p, prefs.getInt(key, 0));
        }
        applyParameter(e, phase, candidate);
        e.apply();
    }

    private void applyParameter(SharedPreferences.Editor e, int phase, int chosen) {
        switch (phase) {
            case 0: e.putInt("memory_compaction_threshold_pct", new int[]{45,50,60}[clamp(chosen,3)]); break;
            case 1: e.putBoolean("app_ram_limiter_enabled", true).putInt("app_ram_limit_mb", new int[]{350,450,600}[clamp(chosen,3)]); break;
            case 2: e.putBoolean("adaptive_freeze_enabled", true).putInt("adaptive_freeze_hours", new int[]{24,48,72}[clamp(chosen,3)]); break;
            case 3: e.putLong("freeze_delay_minutes", new int[]{15,30,60}[clamp(chosen,3)]); break;
            case 4: e.putBoolean("smart_auto_cleanup", true).putInt("smart_cleanup_free_pct", new int[]{10,15,20}[clamp(chosen,3)]); break;
            case 5: e.putBoolean("screen_off_optimization", chosen > 0); break;
            case 6: e.putBoolean("wakeup_network_guard", chosen > 0); break;
            case 7: e.putBoolean("system_radio_savings", chosen > 0); break;
            case 8: e.putBoolean("adaptive_refresh", chosen > 0); break;
        }
    }

    private void accumulate(float tempC, double cpuLoad, double freeRamPct, float powerW) {
        int n = prefs.getInt("auto_tune_samples", 0);
        prefs.edit()
                .putInt("auto_tune_samples", n + 1)
                .putFloat("auto_tune_sum_temp", prefs.getFloat("auto_tune_sum_temp",0f) + Math.max(0f,tempC))
                .putFloat("auto_tune_sum_cpu", prefs.getFloat("auto_tune_sum_cpu",0f) + (float)Math.max(0d,cpuLoad))
                .putFloat("auto_tune_sum_ram_free", prefs.getFloat("auto_tune_sum_ram_free",0f) + (float)Math.max(0d,freeRamPct))
                .putFloat("auto_tune_sum_power", prefs.getFloat("auto_tune_sum_power",0f) + Math.max(0f,powerW))
                .apply();
    }

    private float score(int batteryPct) {
        int n = Math.max(1, prefs.getInt("auto_tune_samples", 1));
        int startBat = prefs.getInt("auto_tune_start_battery", batteryPct);
        float drain = batteryPct >= 0 && startBat >= batteryPct ? startBat - batteryPct : 0f;
        float temp = prefs.getFloat("auto_tune_sum_temp",0f)/n;
        float cpu = prefs.getFloat("auto_tune_sum_cpu",0f)/n;
        float ramFree = prefs.getFloat("auto_tune_sum_ram_free",0f)/n;
        float power = prefs.getFloat("auto_tune_sum_power",0f)/n;
        return drain*12f + power*1.8f + Math.max(0f,temp-36f)*1.5f + cpu*0.03f + Math.max(0f,12f-ramFree)*0.8f;
    }

    private void snapshotTrialStart(int batteryPct,float tempC,double cpuLoad,float powerW) {
        prefs.edit().putInt("auto_tune_start_battery", batteryPct)
                .putFloat("auto_tune_start_temp", tempC)
                .putFloat("auto_tune_start_cpu",(float)cpuLoad)
                .putFloat("auto_tune_start_power",powerW).apply();
    }

    private void resetTrial(long now,int batteryPct,float tempC,double cpuLoad,float powerW) {
        prefs.edit().putLong("auto_tune_trial_started", now)
                .putInt("auto_tune_samples",0)
                .putFloat("auto_tune_sum_temp",0f)
                .putFloat("auto_tune_sum_cpu",0f)
                .putFloat("auto_tune_sum_ram_free",0f)
                .putFloat("auto_tune_sum_power",0f).apply();
        snapshotTrialStart(batteryPct,tempC,cpuLoad,powerW);
    }

    static void resetSession(SharedPreferences prefs) {
        SharedPreferences.Editor e = prefs.edit()
                .putInt("auto_tune_phase",0).putInt("auto_tune_candidate",0)
                .putLong("auto_tune_trial_started",0L).putLong("auto_tune_last_eval",0L)
                .putInt("auto_tune_samples",0).putString("auto_tune_status","Novo ciclo de testes iniciado");
        for(int i=0;i<9;i++){ e.remove("auto_tune_best_score_"+i); e.remove("auto_tune_best_candidate_"+i); }
        e.apply();
    }

    private int candidateCount(int phase){ return phase <= 4 ? 3 : 2; }
    private int clamp(int v,int n){ return Math.max(0,Math.min(n-1,v)); }
    private String phaseName(int p){
        String[] n={"compactação de RAM","limite de RAM por app","tempo para congelamento","atraso após uso","limpeza automática","economia com tela apagada","controle de wakeups","economia de rádio","taxa de atualização"};
        return n[Math.max(0,Math.min(n.length-1,p))];
    }
}


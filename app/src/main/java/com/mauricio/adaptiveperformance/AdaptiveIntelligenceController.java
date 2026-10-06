package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import java.util.*;

public final class AdaptiveIntelligenceController {
    private AdaptiveIntelligenceController() {}
    private static String k(String pkg) { return Integer.toHexString(pkg == null ? 0 : pkg.hashCode()); }
    private static float ema(float old, float now, float a) { return old < 0 ? now : old * (1f-a) + now * a; }
    public static boolean mutationAllowed(SharedPreferences p) {
        if (p.getBoolean("diagnostic_only", false)) return false;
        if (!p.getBoolean("post_correction_guard", true)) return true;
        return System.currentTimeMillis() >= p.getLong("post_correction_observation_until", 0L);
    }
    public static synchronized void noteCorrection(SharedPreferences p, String cause) {
        if (!p.getBoolean("post_correction_guard", true)) return;
        long now = System.currentTimeMillis();
        long window = Math.max(30_000L, Math.min(120_000L, p.getLong("observation_window_ms", 60_000L)));
        p.edit().putLong("post_correction_observation_until", now + window)
                .putString("post_correction_cause", cause == null ? "" : cause).apply();
    }
    public static synchronized void update(Context c, SharedPreferences p, String fg,
                                           float temp, float trend, double cpu, double ram,
                                           float psi, String cause) {
        long now = System.currentTimeMillis();
        if (fg == null) fg = "";
        if (cause == null || cause.isEmpty()) cause = "Sem causa anormal detectada";
        boolean observation = now < p.getLong("post_correction_observation_until", 0L);
        SharedPreferences.Editor e = p.edit().putBoolean("post_correction_observation", observation)
                .putLong("observation_remaining_ms", observation ? p.getLong("post_correction_observation_until",0L)-now : 0L);

        // Cause confidence: require current supporting signals, not just historic counters.
        int confidence = 20;
        String lc = cause.toLowerCase(Locale.US);
        if (lc.contains("sem causa")) confidence = 0;
        else {
            if (lc.contains("ram") || lc.contains("lmk") || lc.contains("zram")) {
                if (ram > 0 && ram < 20) confidence += 35;
                if (psi >= 8) confidence += 25;
                if (p.getInt("lmk_delta",0) >= 5) confidence += 20;
            } else if (lc.contains("aquec") || lc.contains("térm") || lc.contains("temper")) {
                if (temp >= 40) confidence += 35;
                else if (temp >= 38) confidence += 20;
                if (trend >= 0.8f) confidence += 25;
            } else if (lc.contains("cpu")) {
                if (cpu >= 80) confidence += 50;
                else if (cpu >= 60) confidence += 25;
            } else confidence += 30;
        }
        confidence = Math.max(0, Math.min(100, confidence));
        e.putInt("cause_confidence", confidence);

        // Recurrence in a rolling six-hour window.
        boolean recurrenceEnabled = p.getBoolean("recurrence_guard", true);
        String lastCause = p.getString("recurrence_cause", "");
        long recStart = p.getLong("recurrence_window_start", 0L);
        int rec = p.getInt("recurrence_count", 0);
        if (recurrenceEnabled && !lc.contains("sem causa")) {
            if (!cause.equals(lastCause) || recStart <= 0 || now-recStart > 6L*60L*60L*1000L) {
                rec = 1; recStart = now;
            } else if (now - p.getLong("recurrence_last_seen",0L) >= 5L*60L*1000L) rec = Math.min(20, rec+1);
            e.putString("recurrence_cause", cause).putLong("recurrence_window_start", recStart)
                    .putLong("recurrence_last_seen", now).putInt("recurrence_count", rec);
        } else if (recurrenceEnabled && recStart > 0 && now-recStart > 6L*60L*60L*1000L) {
            e.putInt("recurrence_count",0).remove("recurrence_cause").remove("recurrence_window_start"); rec = 0;
        }
        String strategy = rec >= 4 ? "Recidiva alta: evitar repetir a mesma ação; priorizar rollback/causa raiz"
                : rec >= 2 ? "Recidiva detectada: exigir confiança maior antes de nova ação"
                : "Estratégia normal";
        e.putString("recurrence_strategy", strategy);

        // Learn app + time-of-day patterns at low frequency.
        long lastLearn = p.getLong("time_learning_last",0L);
        if (p.getBoolean("time_usage_learning", true) && !fg.isEmpty() && now-lastLearn >= 5L*60L*1000L) {
            Calendar cal = Calendar.getInstance(); int hour = cal.get(Calendar.HOUR_OF_DAY); String h = k(fg);
            int count = p.getInt("hour_use_"+hour+"_"+h,0)+1;
            int top = p.getInt("hour_top_count_"+hour,0);
            e.putInt("hour_use_"+hour+"_"+h,count).putLong("time_learning_last",now);
            if (count >= top) e.putString("hour_top_pkg_"+hour,fg).putInt("hour_top_count_"+hour,count);
        }

        if (!fg.isEmpty()) {
            String h = k(fg); int n = Math.min(5000, p.getInt("learn_n_"+h,0)+1);
            float oldCpu=p.getFloat("learn_cpu_"+h,-1), oldRam=p.getFloat("learn_ram_"+h,-1), oldTemp=p.getFloat("learn_temp_"+h,-1);
            if (cpu >= 0) e.putFloat("learn_cpu_"+h, ema(oldCpu,(float)cpu,0.08f));
            if (ram >= 0) e.putFloat("learn_ram_"+h, ema(oldRam,(float)ram,0.08f));
            if (temp > 0) e.putFloat("learn_temp_"+h, ema(oldTemp,temp,0.08f));
            e.putInt("learn_n_"+h,n).putString("learn_pkg_"+h,fg);
            try {
                PackageInfo pi=c.getPackageManager().getPackageInfo(fg,0); long v=pi.getLongVersionCode();
                long oldV=p.getLong("learn_ver_"+h,-1L);
                if (oldV>=0 && oldV!=v) {
                    e.putLong("update_seen_at_"+h,now).putLong("update_prev_ver_"+h,oldV)
                            .putFloat("update_base_cpu_"+h,oldCpu).putFloat("update_base_ram_"+h,oldRam)
                            .putFloat("update_base_temp_"+h,oldTemp).putInt("update_samples_"+h,0);
                }
                e.putLong("learn_ver_"+h,v);
                long seen=p.getLong("update_seen_at_"+h,0L);
                if (p.getBoolean("update_regression_detector", true) && seen>0 && now-seen<7L*24L*60L*60L*1000L) {
                    int us=p.getInt("update_samples_"+h,0)+1; e.putInt("update_samples_"+h,us);
                    if (us>=8) {
                        float bc=p.getFloat("update_base_cpu_"+h,-1), bt=p.getFloat("update_base_temp_"+h,-1);
                        float cc=cpu>=0?(float)cpu:-1f;
                        boolean reg=(bc>=0&&cc>=bc+20f)||(bt>0&&temp>=bt+2.5f);
                        e.putBoolean("app_regression_detected",reg).putString("app_regression_pkg",reg?fg:"")
                                .putString("app_regression_report",reg?(AppSafety.label(c,fg)+": consumo aumentou após atualização"):"");
                    }
                }
            } catch(Throwable ignored) {}
        }

        // 24h rolling hourly aggregate, compact fixed 24 slots.
        long hourEpoch=now/3600000L; int slot=(int)(hourEpoch%24); long stored=p.getLong("cmp_hour_"+slot,-1L);
        if (stored!=hourEpoch) {
            e.putLong("cmp_hour_"+slot,hourEpoch).putInt("cmp_n_"+slot,1)
                    .putFloat("cmp_ram_"+slot,(float)ram).putFloat("cmp_cpu_"+slot,(float)cpu)
                    .putFloat("cmp_temp_"+slot,temp).putInt("cmp_lmk_"+slot,p.getInt("lmk_delta",0));
        } else {
            int n=Math.max(1,p.getInt("cmp_n_"+slot,1)); int nn=Math.min(10000,n+1);
            e.putInt("cmp_n_"+slot,nn)
                    .putFloat("cmp_ram_"+slot,(p.getFloat("cmp_ram_"+slot,(float)ram)*n+(float)ram)/nn)
                    .putFloat("cmp_cpu_"+slot,(p.getFloat("cmp_cpu_"+slot,(float)cpu)*n+(float)cpu)/nn)
                    .putFloat("cmp_temp_"+slot,(p.getFloat("cmp_temp_"+slot,temp)*n+temp)/nn)
                    .putInt("cmp_lmk_"+slot,Math.max(p.getInt("cmp_lmk_"+slot,0),p.getInt("lmk_delta",0)));
        }

        int limited=p.getInt("cpu_limited_apps",0), frozen=p.getInt("manual_frozen_active_count",0), auto=p.getStringSet("auto_restricted",Collections.emptySet()).size();
        String dashboard="CPU/RAM limitados: "+limited+" • congelados: "+frozen+" • manutenção: "+auto+
                " • causa: "+cause+" ("+confidence+"%)"+(observation?" • observando pós-correção":"")+
                (p.getBoolean("diagnostic_only",false)?" • SOMENTE DIAGNÓSTICO":"");
        e.putString("limiting_dashboard",dashboard).putString("recurrence_strategy",strategy).apply();
    }

    public static String comparative24h(SharedPreferences p) {
        long nowH=System.currentTimeMillis()/3600000L; float ram=0,cpu=0,temp=0; int lmk=0,n=0;
        for(int s=0;s<24;s++) { long h=p.getLong("cmp_hour_"+s,-1L); if(h<nowH-23||h>nowH) continue;
            ram+=p.getFloat("cmp_ram_"+s,0); cpu+=p.getFloat("cmp_cpu_"+s,0); temp+=p.getFloat("cmp_temp_"+s,0); lmk+=p.getInt("cmp_lmk_"+s,0); n++; }
        if(n==0) return "Sem amostras comparativas de 24h";
        return String.format(Locale.US,"24h: RAM livre média %.1f%% • CPU média %.1f%% • temp média %.1f°C • LMKD acumulado aprox. %d",ram/n,cpu/n,temp/n,lmk);
    }
}

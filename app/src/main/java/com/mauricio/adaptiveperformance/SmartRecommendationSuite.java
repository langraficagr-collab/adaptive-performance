package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import java.util.regex.*;

public final class SmartRecommendationSuite {
    private static long lastRun=0, lastSwapIn=-1, lastSwapOut=-1, lastSwapAt=0;
    private static final Map<String,Integer> restartCounts=new HashMap<>();
    private SmartRecommendationSuite(){}

    public static void evaluate(Context c, SharedPreferences p, IPrivilegedService s,
                                String fg, boolean interactive, int batteryPct,
                                float tempC, int pressureScore) {
        if (!p.getBoolean("smart_suite_enabled", true) || s==null) return;
        long now=System.currentTimeMillis();
        long interval = pressureScore >= 4 || tempC >= 40f ? 60_000L : 300_000L;
        if(now-lastRun<interval) return;
        lastRun=now;
        try { detectSwapThrashing(p,s,now); } catch(Throwable ignored){}
        try { lowBatteryMode(p,batteryPct); } catch(Throwable ignored){}
        try { recurrence(p,s,fg); } catch(Throwable ignored){}
        try { adaptiveFreeze(c,p,s,fg); } catch(Throwable ignored){}
        try { autoCleanup(p); } catch(Throwable ignored){}
        try { economyDashboard(p,batteryPct,tempC,pressureScore); } catch(Throwable ignored){}
    }

    public static int ramLimitFor(SharedPreferences p,String pkg,int fallback){
        if(pkg==null) return fallback;
        String h=Integer.toHexString(pkg.hashCode());
        return Math.max(100,Math.min(2000,p.getInt("app_ram_limit_"+h,fallback)));
    }

    private static void detectSwapThrashing(SharedPreferences p,IPrivilegedService s,long now)throws Exception{
        if(!p.getBoolean("zram_thrash_guard",true)) return;
        String out=s.exec("grep -E '^(pswpin|pswpout) ' /proc/vmstat 2>/dev/null");
        if(out==null)return;
        long in=val(out,"pswpin"), ot=val(out,"pswpout");
        if(lastSwapAt>0 && now>lastSwapAt){
            double sec=(now-lastSwapAt)/1000.0;
            double rate=((Math.max(0,in-lastSwapIn)+Math.max(0,ot-lastSwapOut))/sec);
            boolean thrash=rate>6000;
            p.edit().putFloat("zram_swap_pages_sec",(float)rate).putBoolean("zram_thrashing",thrash)
                    .putLong("zram_thrash_last_at",thrash?now:p.getLong("zram_thrash_last_at",0)).apply();
            if(thrash) p.edit().putString("smart_auto_level","Normal — proteção contra thrashing").apply();
        }
        lastSwapIn=in;lastSwapOut=ot;lastSwapAt=now;
    }
    private static long val(String raw,String key){
        Matcher m=Pattern.compile("(?m)^"+key+"\\s+(\\d+)").matcher(raw);
        if (!m.find()) return 0;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException ignored) {
            // A malformed or out-of-range /proc/vmstat value must not stop monitoring.
            return 0;
        }
    }
    private static void lowBatteryMode(SharedPreferences p,int pct){
        if(!p.getBoolean("low_battery_adaptive",true))return;
        int threshold=Math.max(10,Math.min(50,p.getInt("low_battery_threshold",50)));
        boolean on=pct>=0 && pct<=threshold;
        p.edit().putBoolean("low_battery_active",on).apply();
    }
    private static void recurrence(SharedPreferences p,IPrivilegedService s,String fg)throws Exception{
        if(!p.getBoolean("restart_recurrence_guard",true))return;
        String raw=s.exec("ps -A -o ARGS 2>/dev/null | grep -E '^[a-zA-Z][a-zA-Z0-9_.]+(:[a-zA-Z0-9_.]+)?$' | head -120");
        if(raw==null)return;
        Set<String> now=new HashSet<>();
        for (String x : raw.split("\n")) {
            x = x.trim();
            if (x.contains(":")) x = x.substring(0, x.indexOf(':'));
            if (x.contains(".")) now.add(x);
        }
        String last=p.getString("recurrence_last_packages","");
        Set<String> prev=new HashSet<>(Arrays.asList(last.split(",")));
        for(String pkg:now) if(!pkg.equals(fg) && !prev.contains(pkg)) restartCounts.put(pkg,restartCounts.getOrDefault(pkg,0)+1);
        String worst="";int max=0;
        for(Map.Entry<String,Integer>e:restartCounts.entrySet())if(e.getValue()>max){max=e.getValue();worst=e.getKey();}
        p.edit().putString("recurrence_last_packages",String.join(",",now)).putString("restart_recurrence_pkg",worst)
                .putInt("restart_recurrence_count",max).apply();
    }
    private static void adaptiveFreeze(Context c,SharedPreferences p,IPrivilegedService s,String fg)throws Exception{
        if(!p.getBoolean("adaptive_freeze_enabled",false))return;
        long hours=Math.max(6,Math.min(168,p.getInt("adaptive_freeze_hours",48)));
        long cutoff=System.currentTimeMillis()-hours*3600000L;
        String raw=s.exec("cmd usagestats query-events --user 0 2>/dev/null | tail -250");
        if(raw==null)return;
        // Conservative: only packages already auto-restricted are eligible.
        Set<String> restricted=p.getStringSet("auto_restricted",Collections.emptySet());
        int n=0;
        for(String pkg:new HashSet<>(restricted)){
            if(n>=3||pkg.equals(fg)||AppSafety.isNeverFreeze(c,pkg)
                    ||!AppSafety.isEligibleForAdaptiveOptimization(c,pkg)
                    ||!AppSafety.hasLeftForegroundLongEnough(p,pkg))continue;
            long seen=p.getLong("app_last_seen_"+Integer.toHexString(pkg.hashCode()),0);
            if(seen>0&&seen<cutoff&&RestrictionGuard.command(s,"am force-stop --user 0 "+pkg)){n++;}
        }
        if(n>0)p.edit().putInt("adaptive_freeze_last_count",n).putLong("adaptive_freeze_last_at",System.currentTimeMillis()).apply();
    }
    private static void autoCleanup(SharedPreferences p) {
        // Automatic trim-caches with 256G caused repeated cache regeneration and I/O.
        // Keep cache under Android's ownership; manual capped cleanup only.
        if (p.getBoolean("smart_auto_cleanup", false))
            p.edit().putBoolean("smart_auto_cleanup", false)
                .putString("smart_cleanup_status", "Limpeza automática de cache removida: custo energético").apply();
    }
    private static void economyDashboard(SharedPreferences p,int battery,float temp,int pressure){
        String level=p.getBoolean("zram_thrashing",false)?"Proteção ZRAM":
                p.getBoolean("low_battery_active",false)?"Economia de bateria":
                pressure>=4?"Estabilidade":temp>=40?"Resfriamento":"Balanceado";
        p.edit().putString("smart_auto_level",level)
                .putString("economy_dashboard","Modo: "+level+"\nBateria: "+battery+"%\nTemperatura: "+
                        String.format(Locale.US,"%.1f °C",temp)+"\nPressão: "+pressure+"/5\n"+
                        "Apps congelados: "+p.getInt("manual_frozen_active_count",0)+"\n"+
                        "Compactações: "+p.getInt("memory_compaction_count",0)+"\n"+
                        "Última limpeza: "+p.getLong("storage_last_freed_kb",0)/1024+" MB liberados").apply();
    }
}

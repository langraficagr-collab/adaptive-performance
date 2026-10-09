package com.mauricio.adaptiveperformance;
import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.Map;
import rikka.shizuku.Shizuku;

/** Read-only capability audit. Presence is not interpreted as write permission. */
final class CompatibilityAudit {
    static String report(Context c, SharedPreferences p) {
        boolean en = UiLanguage.english(p);
        String works = en ? "works" : "funciona";
        String limited = en ? "limited by ROM / permissions" : "limitada pela ROM/permissões";
        String unavailable = en ? "unavailable" : "indisponível";
        boolean shizuku = false;
        try { shizuku = Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED; }
        catch (Throwable ignored) {}
        boolean psi = new File("/proc/pressure/memory").canRead();
        boolean zram = new File("/sys/block/zram0").exists();
        boolean freq = new File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").canRead();
        boolean stats = false;
        try {
            UsageStatsManager manager = (UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            Map<String,?> rows = manager == null ? null :
                    manager.queryAndAggregateUsageStats(now - 86400000L, now);
            stats = rows != null && !rows.isEmpty();
        } catch (Throwable ignored) {}
        return (en ? "Compatibility audit (read-only)" : "Auditoria de compatibilidade (somente leitura)")
                + "\n" + "RAM: " + works
                + "\nPSI: " + (psi ? works : unavailable)
                + "\nShizuku shell: " + (shizuku ? works : unavailable)
                + "\nCPU frequency / controle: " + (freq ? limited : unavailable)
                + "\nzRAM: " + (zram ? limited : unavailable)
                + "\n" + (en ? "Radio/network control: " : "Controle de rede/rádio: ") + (shizuku ? limited : unavailable)
                + "\n" + (en ? "App usage history: " : "Histórico de uso dos apps: ")
                    + (stats ? works : unavailable)
                + "\n" + (en ? "Feature presence does not guarantee kernel/ROM write access."
                    : "Detectar recurso não garante permissão de escrita do kernel/ROM.");
    }
}

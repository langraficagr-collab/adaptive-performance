package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StorageHealthController {
    private static final long CHECK_INTERVAL_MS = 60L * 60L * 1000L;
    private static final long MIN_IDLE_MS = 5L * 60L * 1000L;
    private static final long AUTO_MAINT_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long BIG_CHANGE_KB = 1024L * 1024L;
    private static final float MAX_MAINT_BATTERY_C = 38.5f;

    private final Context context;
    private final SharedPreferences prefs;
    private final IPrivilegedService privileged;

    StorageHealthController(Context context, SharedPreferences prefs, IPrivilegedService privileged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.privileged = privileged;
    }

    void maybeRun(boolean interactive, boolean charging, float batteryTempC) {
        if (!prefs.getBoolean("storage_health_auto_enabled", true)) return;
        check(false, interactive, charging, batteryTempC);
    }

    void forceCheck(boolean interactive, boolean charging, float batteryTempC) {
        check(true, interactive, charging, batteryTempC);
    }

    private void check(boolean force, boolean interactive, boolean charging, float batteryTempC) {
        if (privileged == null) return;
        long now = System.currentTimeMillis();
        long last = prefs.getLong("storage_health_last_check_at", 0L);
        if (!force && last > 0L && now - last < CHECK_INTERVAL_MS) return;

        String mount = exec("mount 2>/dev/null | grep ' /data ' | head -1");
        String df = exec("df -k /data 2>/dev/null | tail -1");
        String fs = fileSystemType(mount);
        long[] usage = parseDf(df);
        long totalKb = usage[0], freeKb = usage[1];
        int freePct = totalKb > 0 ? (int)Math.max(0L, Math.min(100L, freeKb * 100L / totalKb)) : -1;

        boolean f2fs = "f2fs".equalsIgnoreCase(fs);
        boolean ext4 = "ext4".equalsIgnoreCase(fs);
        boolean bgGc = mount.contains("background_gc=on") || mount.contains("background_gc=sync");
        boolean gcMerge = mount.contains("gc_merge");
        boolean discard = hasMountOption(mount, "discard");
        boolean atgc = hasMountOption(mount, "atgc");

        String toolProbe = exec("command -v fstrim 2>/dev/null; command -v fsck.f2fs 2>/dev/null; command -v e2fsck 2>/dev/null; command -v f2fs_io 2>/dev/null; command -v defrag.f2fs 2>/dev/null");
        boolean hasFstrim = toolProbe.contains("fstrim");
        boolean hasFsckF2fs = toolProbe.contains("fsck.f2fs");
        boolean hasE2fsck = toolProbe.contains("e2fsck");
        boolean hasF2fsIo = toolProbe.contains("f2fs_io") || toolProbe.contains("defrag.f2fs");

        String integrity = exec("logcat -b kernel -d -t 120 2>/dev/null | grep -Ei 'F2FS-fs.*(corrupt|error|fsck|checkpoint.*fail)' | tail -3");
        boolean integrityAlert = integrity != null && !integrity.trim().isEmpty();

        long previousFree = prefs.getLong("storage_health_prev_free_kb", -1L);
        long deltaKb = previousFree >= 0L && freeKb >= 0L ? freeKb - previousFree : 0L;
        long absDeltaKb = Math.abs(deltaKb);
        long firstSeen = prefs.getLong("storage_health_first_seen_at", 0L);
        if (firstSeen == 0L) firstSeen = now;
        long lastMaint = prefs.getLong("storage_health_last_maintenance_at", 0L);

        boolean oldEnough = lastMaint > 0L
                ? now - lastMaint >= AUTO_MAINT_INTERVAL_MS
                : now - firstSeen >= 24L * 60L * 60L * 1000L;
        boolean lowFree = freePct >= 0 && freePct < 15;
        boolean veryLowFree = freePct >= 0 && freePct < 8;
        boolean bigChange = absDeltaKb >= BIG_CHANGE_KB;
        boolean maintenanceNeeded = oldEnough || lowFree || bigChange || (f2fs && !discard);

        long idleSince = prefs.getLong("storage_idle_since", 0L);
        if (!interactive && idleSince <= 0L) {
            idleSince = now;
            prefs.edit().putLong("storage_idle_since", idleSince).apply();
        }
        boolean idleLongEnough = !interactive && now - idleSince >= MIN_IDLE_MS;
        int batteryPct = batteryPercent();
        boolean powerSafe = charging || batteryPct < 0 || batteryPct >= 35;
        boolean thermalSafe = batteryTempC <= 0f || batteryTempC <= MAX_MAINT_BATTERY_C;

        String action = "Nenhuma ação necessária";
        if (integrityAlert) {
            action = "Alerta de integridade detectado; fsck será recomendado somente offline";
        } else if (maintenanceNeeded && idleLongEnough && powerSafe && thermalSafe) {
            String out = exec("sm idle-maint run 2>&1");
            boolean ok = out == null || !out.toLowerCase(Locale.US).matches("(?s).*(error|exception|failed|permission denial).*");
            if (ok) {
                lastMaint = now;
                action = "Manutenção Android iniciada: TRIM + tarefas de armazenamento em repouso";
                prefs.edit().putLong("storage_health_last_maintenance_at", now).apply();
            } else {
                action = "Manutenção necessária, mas o Android não permitiu iniciar agora";
            }

            if (veryLowFree) {
                int targetGb = (int)Math.max(4L, (totalKb * 12L / 100L + 1048575L) / 1048576L);
                action += " • cache preservado; limpeza somente manual";
            }
        } else if (maintenanceNeeded && interactive) {
            action = "Manutenção pendente; aguardando tela desligada";
        } else if (maintenanceNeeded && !thermalSafe) {
            action = "Manutenção pendente; aguardando bateria esfriar";
        } else if (maintenanceNeeded && !idleLongEnough) {
            action = "Manutenção pendente; aguardando 5 min de repouso";
        } else if (maintenanceNeeded && !powerSafe) {
            action = "Manutenção pendente; aguardando carga >=35% ou carregador";
        }

        String change = "Primeira leitura";
        if (previousFree >= 0L) {
            if (Math.abs(deltaKb) < 64L * 1024L) change = "Sem mudança relevante na última leitura";
            else change = String.format(Locale.US, "Espaço livre mudou %+.1f MB", deltaKb / 1024.0);
        }

        String gcStatus = f2fs
                ? ("GC F2FS " + (bgGc ? "ativo" : "não confirmado") + (gcMerge ? " + gc_merge" : "") + (atgc ? " + ATGC" : ""))
                : "GC F2FS não se aplica";
        String discardStatus = f2fs
                ? ("discard " + (discard ? "ativo em tempo real" : "não ativo"))
                : "discard conforme sistema de arquivos";
        String trimStatus = hasFstrim ? "fstrim disponível" : "TRIM via sm idle-maint";
        String defragStatus = f2fs
                ? (hasF2fsIo ? "defrag F2FS disponível, somente sob demanda" : "defrag dedicado indisponível; GC/ATGC assume reorganização")
                : "defrag F2FS não se aplica";
        String fsckStatus;
        if (f2fs) fsckStatus = "fsck.f2fs " + (hasFsckF2fs ? "disponível" : "não exposto ao shell") + " • somente offline";
        else if (ext4) fsckStatus = "e2fsck " + (hasE2fsck ? "disponível" : "não exposto ao shell") + " • somente offline";
        else fsckStatus = "fsck: sistema de arquivos " + (fs.isEmpty() ? "não identificado" : fs);

        String summary = (fs.isEmpty() ? "FS desconhecido" : fs.toUpperCase(Locale.US))
                + (freePct >= 0 ? " • " + freePct + "% livre" : "")
                + " • " + change + "\n"
                + trimStatus + " • " + gcStatus + "\n"
                + discardStatus + " • " + defragStatus + "\n"
                + fsckStatus + "\n"
                + action;

        prefs.edit()
                .putBoolean("storage_health_auto_enabled", prefs.getBoolean("storage_health_auto_enabled", true))
                .putLong("storage_health_last_check_at", now)
                .putLong("storage_health_first_seen_at", firstSeen)
                .putLong("storage_health_prev_free_kb", freeKb)
                .putLong("storage_health_total_kb", totalKb)
                .putInt("storage_health_free_pct", freePct)
                .putString("storage_health_fs", fs)
                .putBoolean("storage_health_f2fs_gc", bgGc)
                .putBoolean("storage_health_discard", discard)
                .putBoolean("storage_health_atgc", atgc)
                .putBoolean("storage_health_integrity_alert", integrityAlert)
                .putString("storage_health_integrity_detail", integrityAlert ? integrity : "")
                .putString("storage_health_action", action)
                .putString("storage_health_summary", summary)
                .apply();
    }

    private String exec(String command) {
        try {
            String out = privileged.exec(command);
            return out == null ? "" : out.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private int batteryPercent() {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            return bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean hasMountOption(String mount, String option) {
        Matcher m = Pattern.compile("\\(([^)]*)\\)").matcher(mount == null ? "" : mount);
        if (!m.find()) return false;
        for (String item : m.group(1).split(",")) if (option.equals(item.trim())) return true;
        return false;
    }

    private static String fileSystemType(String mount) {
        Matcher m = Pattern.compile("\\stype\\s+([^\\s]+)").matcher(mount == null ? "" : mount);
        return m.find() ? m.group(1).trim() : "";
    }

    private static long[] parseDf(String raw) {
        if (raw == null) return new long[]{0L, -1L};
        String[] lines = raw.trim().split("\\n");
        if (lines.length == 0) return new long[]{0L, -1L};
        String[] p = lines[lines.length - 1].trim().split("\\s+");
        try {
            if (p.length >= 4) return new long[]{Long.parseLong(p[1]), Long.parseLong(p[3])};
        } catch (Throwable ignored) {}
        return new long[]{0L, -1L};
    }
}


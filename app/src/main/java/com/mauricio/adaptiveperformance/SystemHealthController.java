package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Higher-level stability controller. Expensive diagnostics are deliberately slow
 * (minutes, not seconds) so the optimizer does not become a source of heat itself.
 */
public final class SystemHealthController {
    private static final long EXPENSIVE_SCAN_NORMAL_MS = 7L * 60L * 1000L;
    private static final long EXPENSIVE_SCAN_ALERT_MS = 2L * 60L * 1000L;
    private static final long ZRAM_SCAN_MS = 15L * 60L * 1000L;
    private static final long HISTORY_MS = 300_000L;
    private static final long WATCHDOG_WINDOW_MS = 300_000L;
    private static final long WATCHDOG_PAUSE_MS = 600_000L;
    private static final long ROLLBACK_COOLDOWN_MS = 600_000L;

    public static final class Result {
        public int thermalScore;
        public int memoryScore;
        public int cpuScore;
        public int ioScore;
        public int batteryScore;
        public int uiScore;
        public int antiStallLevel;
        public int learnedRisk;
        public int lmkDelta;
        public int crashLoopCount;
        public int jobCount;
        public float jankPct = -1f;
        public float zramPhysicalMb = -1f;
        public float zramSwapMb = -1f;
        public float zramCompression = -1f;
        public boolean batteryAnomaly;
        public boolean rollbackRequested;
        public boolean watchdogSlow;
        public String profile = "";
        public String summary = "";
    }

    private static final class Gfx {
        long total;
        long janky;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final IPrivilegedService privileged;

    private long lastExpensiveScan = 0L;
    private long lastHistory = 0L;
    private long lastZramScan = 0L;
    private long lastLmkKills = -1L;
    private long lastLmkAt = 0L;
    private final Map<String,Gfx> lastGfx = new HashMap<>();
    private final Map<String,Integer> lastCrashCounts = new HashMap<>();
    private volatile int cachedLmkDelta = 0;
    private volatile int cachedCrashLoops = 0;
    private volatile int cachedJobCount = 0;
    private volatile float cachedJank = -1f;
    private volatile float cachedZramPhysical = -1f;
    private volatile float cachedZramSwap = -1f;
    private volatile float cachedZramCompression = -1f;

    private final ArrayDeque<Long> slowCycles = new ArrayDeque<>();
    private final long createdAt = SystemClock.elapsedRealtime();
    private volatile long expensivePauseUntil = 0L;
    private long rollbackCooldownUntil = 0L;

    public SystemHealthController(Context context, SharedPreferences prefs, IPrivilegedService privileged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.privileged = privileged;
        this.cachedZramPhysical = prefs.getFloat("zram_physical_mb", -1f);
        this.cachedZramSwap = prefs.getFloat("zram_swap_mb", -1f);
        this.cachedZramCompression = prefs.getFloat("zram_compression", -1f);
        // A full dumpsys meminfo is expensive on HyperOS; reuse the last value after restart.
        this.lastZramScan = SystemClock.elapsedRealtime();
        prefs.edit()
                .remove("health_watchdog_note")
                .remove("health_expensive_pause_until_elapsed")
                .putBoolean("health_watchdog_slow", false)
                .apply();
    }

    public Result update(String fg, float socTemp, float batteryTemp, double cpuLoad,
                         double ramFreePct, float powerW, int pressureScore,
                         float ioSignal, int thermalLevel, boolean interactive) {
        Result r = new Result();
        long now = SystemClock.elapsedRealtime();

        boolean enabled = prefs.getBoolean("health_guard", true);
        if (!enabled) return r;

        learnApp(fg, socTemp, cpuLoad, ramFreePct, powerW);
        updateCalibration(socTemp, cpuLoad, ramFreePct, powerW, pressureScore, thermalLevel, interactive);

        r.lmkDelta = cachedLmkDelta;
        r.crashLoopCount = cachedCrashLoops;
        r.jobCount = cachedJobCount;
        r.jankPct = cachedJank;
        r.zramPhysicalMb = cachedZramPhysical;
        r.zramSwapMb = cachedZramSwap;
        r.zramCompression = cachedZramCompression;

        r.thermalScore = thermalScore(socTemp, batteryTemp, thermalLevel);
        r.cpuScore = cpuScore(cpuLoad);
        r.memoryScore = memoryScore(ramFreePct, pressureScore, r.lmkDelta, r.zramSwapMb);
        r.ioScore = ioScore(ioSignal);
        r.uiScore = uiScore(r.jankPct);
        r.batteryScore = batteryScore(powerW, interactive);
        r.batteryAnomaly = r.batteryScore >= 65;
        r.learnedRisk = learnedRisk(fg, socTemp, cpuLoad, powerW);

        if (prefs.getBoolean("anti_stall", true)) {
            int hard = Math.max(Math.max(r.cpuScore, r.memoryScore), Math.max(r.ioScore, r.uiScore));
            if (hard >= 85 || (r.cpuScore >= 65 && r.memoryScore >= 65)) r.antiStallLevel = 2;
            else if (hard >= 70 || r.learnedRisk >= 2 || (r.lmkDelta >= 8 && ramFreePct < 20)) r.antiStallLevel = 1;
        }

        r.profile = learnedProfile(fg, r.learnedRisk, r.antiStallLevel);
        r.watchdogSlow = now < expensivePauseUntil;

        if (prefs.getBoolean("rollback_guard", true) && now >= rollbackCooldownUntil) {
            // Only rollback non-thermal performance restrictions when temperature itself is safe.
            if (r.uiScore >= 75 && socTemp > 0f && socTemp < 58f &&
                    pressureScore <= 1 && thermalLevel <= 2) {
                r.rollbackRequested = true;
                rollbackCooldownUntil = now + ROLLBACK_COOLDOWN_MS;
                prefs.edit().putLong("last_rollback_request", System.currentTimeMillis()).apply();
            }
        }

        r.summary = String.format(Locale.US,
                "T%d M%d C%d I%d B%d UI%d • LMK +%d • Jank %s • zRAM %s",
                r.thermalScore, r.memoryScore, r.cpuScore, r.ioScore, r.batteryScore, r.uiScore,
                r.lmkDelta,
                r.jankPct >= 0 ? String.format(Locale.US, "%.1f%%", r.jankPct) : "--",
                r.zramSwapMb >= 0 ? String.format(Locale.US, "%.0f MB", r.zramSwapMb) : "--");

        prefs.edit()
                .putInt("health_thermal", r.thermalScore)
                .putInt("health_memory", r.memoryScore)
                .putInt("health_cpu", r.cpuScore)
                .putInt("health_io", r.ioScore)
                .putInt("health_battery", r.batteryScore)
                .putInt("health_ui", r.uiScore)
                .putInt("health_antistall", r.antiStallLevel)
                .putInt("health_learned_risk", r.learnedRisk)
                .putInt("lmk_delta", r.lmkDelta)
                .putInt("crash_loop_count", r.crashLoopCount)
                .putInt("jobs_count", r.jobCount)
                .putFloat("jank_pct", r.jankPct)
                .putFloat("zram_physical_mb", r.zramPhysicalMb)
                .putFloat("zram_swap_mb", r.zramSwapMb)
                .putFloat("zram_compression", r.zramCompression)
                .putBoolean("battery_anomaly", r.batteryAnomaly)
                .putBoolean("health_watchdog_slow", r.watchdogSlow)
                .putString("health_profile", r.profile)
                .putString("health_summary", r.summary)
                .apply();

        long wallNow = System.currentTimeMillis();
        long lastHistoryWall = prefs.getLong("history_last", 0L);
        if (prefs.getBoolean("history_24h", true) &&
                (lastHistoryWall <= 0L || wallNow - lastHistoryWall >= HISTORY_MS)) {
            lastHistory = now;
            appendHistory(socTemp, cpuLoad, ramFreePct, powerW, pressureScore, thermalLevel, r);
        }

        return r;
    }

    public synchronized void maybeRunExpensive(String fg, boolean interactive) {
        if (!prefs.getBoolean("health_guard", true) || !interactive) return;
        long now = SystemClock.elapsedRealtime();
        boolean elevated = prefs.getInt("thermal_level", 0) > 0
                || prefs.getInt("adaptive_pressure_score", 0) >= 2
                || prefs.getInt("health_antistall", 0) > 0
                || prefs.getBoolean("diagnostic_mode_active", false);
        long scanInterval = elevated ? EXPENSIVE_SCAN_ALERT_MS : EXPENSIVE_SCAN_NORMAL_MS;
        if (now < expensivePauseUntil || now - lastExpensiveScan < scanInterval) return;
        lastExpensiveScan = now;

        long started = SystemClock.elapsedRealtime();
        try {
            scanLmk(now);
            if (now - lastZramScan >= ZRAM_SCAN_MS) {
                lastZramScan = now;
                try { scanZram(); } catch (Throwable ignored) {}
            }
            try { scanGfx(fg); } catch (Throwable ignored) {}
            try { scanJobs(); } catch (Throwable ignored) {}
            try { scanCrashLoops(fg); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        } finally {
            long cost = Math.max(0L, SystemClock.elapsedRealtime() - started);
            prefs.edit().putLong("health_scan_cost_ms", cost).apply();
            if (cost >= 8_000L) {
                expensivePauseUntil = SystemClock.elapsedRealtime() + WATCHDOG_PAUSE_MS;
                prefs.edit().putString("health_watchdog_note",
                        "Diagnósticos pesados pausados por 10 min: varredura demorou " + cost + " ms.")
                        .apply();
            }
        }
    }

    public void recordCycleCost(long durationMs) {
        long now = SystemClock.elapsedRealtime();
        prefs.edit().putLong("last_cycle_cost_ms", durationMs).apply();
        // Binding Shizuku and restoring previous state can make the first cycles slower.
        if (now - createdAt < 90_000L) return;
        if (durationMs >= 2_000L) slowCycles.addLast(now);
        while (!slowCycles.isEmpty() && now - slowCycles.peekFirst() > WATCHDOG_WINDOW_MS) {
            slowCycles.removeFirst();
        }
        if (slowCycles.size() >= 3) {
            expensivePauseUntil = now + WATCHDOG_PAUSE_MS;
            slowCycles.clear();
            prefs.edit()
                    .putLong("health_expensive_pause_until_elapsed", expensivePauseUntil)
                    .putString("health_watchdog_note",
                            "Diagnósticos pesados pausados por 10 min: ciclos demoraram demais.")
                    .apply();
        }
    }

    public long watchdogMinimumDelay(long currentDelay) {
        long now = SystemClock.elapsedRealtime();
        if (now < expensivePauseUntil) return Math.max(currentDelay, 30_000L);
        return currentDelay;
    }

    private void scanLmk(long now) throws Exception {
        String raw = privileged.exec("dumpsys activity lmk 2>/dev/null | head -40");
        Matcher m = Pattern.compile("Total number of kills:\\s*([0-9]+)").matcher(raw == null ? "" : raw);
        if (!m.find()) return;
        long total;
        try { total = Long.parseLong(m.group(1)); } catch (Throwable t) { return; }
        if (lastLmkKills >= 0 && lastLmkAt > 0L) {
            long delta = Math.max(0, total - lastLmkKills);
            long dt = Math.max(1L, now - lastLmkAt);
            // Normalize approximately to a 2-minute scan interval.
            cachedLmkDelta = (int)Math.min(999, Math.round(delta * (120_000.0 / dt)));
        } else {
            cachedLmkDelta = 0;
        }
        lastLmkKills = total;
        lastLmkAt = now;
        prefs.edit().putLong("lmk_total", total).apply();
    }

    private void scanZram() throws Exception {
        String raw = privileged.exec(
                "dumpsys meminfo 2>/dev/null | grep -m1 'ZRAM:'");
        if (raw == null) return;
        Matcher m = Pattern.compile(
                "ZRAM:\\s*([0-9,]+)K physical used for ([0-9,]+)K in swap \\(([0-9,]+)K total swap\\)")
                .matcher(raw);
        if (!m.find()) return;
        float physical = parseCommaNumber(m.group(1));
        float swapUsed = parseCommaNumber(m.group(2));
        cachedZramPhysical = physical / 1024f;
        cachedZramSwap = swapUsed / 1024f;
        cachedZramCompression = physical > 0f ? swapUsed / physical : -1f;
    }

    private float parseCommaNumber(String s) {
        try { return Float.parseFloat(s.replace(",", "")); }
        catch (Throwable t) { return -1f; }
    }

    private void scanGfx(String fg) throws Exception {
        String pkg = safePackage(fg);
        if (pkg.isEmpty()) return;
        String raw = privileged.exec("dumpsys gfxinfo " + pkg + " 2>/dev/null | head -80");
        Matcher tf = Pattern.compile("Total frames rendered:\\s*([0-9]+)").matcher(raw == null ? "" : raw);
        Matcher jf = Pattern.compile("Janky frames:\\s*([0-9]+)").matcher(raw == null ? "" : raw);
        if (!tf.find() || !jf.find()) return;
        Gfx cur = new Gfx();
        try {
            cur.total = Long.parseLong(tf.group(1));
            cur.janky = Long.parseLong(jf.group(1));
        } catch (Throwable t) { return; }

        Gfx old = lastGfx.get(pkg);
        if (old != null && cur.total >= old.total && cur.janky >= old.janky) {
            long df = cur.total - old.total;
            long dj = cur.janky - old.janky;
            if (df >= 15) cachedJank = 100f * dj / df;
        } else {
            // First sample is only a baseline. Lifetime jank since process start is not
            // representative of current UI smoothness and can create false positives.
            cachedJank = -1f;
        }
        lastGfx.put(pkg, cur);
    }

    private void scanJobs() throws Exception {
        String raw = privileged.exec(
                "dumpsys jobscheduler 2>/dev/null | grep -c 'JOB #' ");
        try {
            cachedJobCount = Integer.parseInt(raw.trim());
            prefs.edit().putInt("jobs_count", cachedJobCount).apply();
        } catch (Throwable ignored) {}
    }

    private void scanCrashLoops(String fg) throws Exception {
        String raw = privileged.exec(
                "logcat -d -b crash -v brief -t 500 2>/dev/null | grep 'Process:' | tail -120");
        Map<String,Integer> counts = new HashMap<>();
        Matcher m = Pattern.compile("Process:\\s*([A-Za-z0-9_.]+)").matcher(raw == null ? "" : raw);
        while (m.find()) {
            String pkg = m.group(1);
            counts.put(pkg, counts.getOrDefault(pkg, 0) + 1);
        }

        int newLoops = 0;
        for (Map.Entry<String,Integer> e : counts.entrySet()) {
            int old = lastCrashCounts.getOrDefault(e.getKey(), e.getValue());
            int delta = Math.max(0, e.getValue() - old);
            if (delta >= 2) {
                newLoops++;
                if (prefs.getBoolean("crash_loop_guard", true) &&
                        AppSafety.isEligibleForAdaptiveOptimization(context, e.getKey()) &&
                        !e.getKey().equals(fg)) {
                    try {
                        privileged.exec("cmd activity make-uid-idle --user 0 " + e.getKey());
                    } catch (Throwable ignored) {}
                }
            }
        }
        lastCrashCounts.clear();
        lastCrashCounts.putAll(counts);
        cachedCrashLoops = newLoops;
    }

    private void learnApp(String pkg, float temp, double cpu, double ramFree, float power) {
        if (!prefs.getBoolean("app_learning", true)) return;
        pkg = safePackage(pkg);
        if (pkg.isEmpty()) return;
        String h = Integer.toHexString(pkg.hashCode());
        int n = prefs.getInt("app_learn_n_" + h, 0);
        float alpha = n < 10 ? 0.25f : 0.08f;
        SharedPreferences.Editor e = prefs.edit();
        if (temp > 0) e.putFloat("app_temp_" + h, ema(prefs.getFloat("app_temp_" + h, -1f), temp, alpha));
        if (cpu >= 0) e.putFloat("app_cpu_" + h, ema(prefs.getFloat("app_cpu_" + h, -1f), (float)cpu, alpha));
        if (ramFree >= 0) e.putFloat("app_ram_used_" + h,
                ema(prefs.getFloat("app_ram_used_" + h, -1f), (float)(100.0 - ramFree), alpha));
        if (power > 0) e.putFloat("app_power_" + h, ema(prefs.getFloat("app_power_" + h, -1f), power, alpha));
        e.putInt("app_learn_n_" + h, Math.min(10000, n + 1));
        e.putString("app_pkg_" + h, pkg);
        e.apply();
    }

    private int learnedRisk(String pkg, float temp, double cpu, float power) {
        if (!prefs.getBoolean("app_learning", true)) return 0;
        pkg = safePackage(pkg);
        if (pkg.isEmpty()) return 0;
        String h = Integer.toHexString(pkg.hashCode());
        int n = prefs.getInt("app_learn_n_" + h, 0);
        if (n < 8) return 0;
        float bt = prefs.getFloat("app_temp_" + h, -1f);
        float bc = prefs.getFloat("app_cpu_" + h, -1f);
        float bp = prefs.getFloat("app_power_" + h, -1f);
        int risk = 0;
        if (bt > 0 && temp >= bt + 4.5f) risk++;
        if (bc > 0 && cpu >= 0 && cpu >= Math.max(70f, bc + 30f)) risk++;
        if (bp > 0 && power > 0 && power >= Math.max(bp * 1.7f, bp + 3.0f)) risk++;
        return Math.min(3, risk);
    }

    private String learnedProfile(String pkg, int risk, int antiStall) {
        if (antiStall >= 2) return "Anti-travamento forte";
        if (antiStall == 1) return "Anti-travamento preventivo";
        if (risk >= 2) return "App acima do padrão aprendido";
        if (risk == 1) return "App em observação";
        return "Perfil aprendido estável";
    }

    private float ema(float old, float value, float alpha) {
        return old < 0 ? value : old * (1f - alpha) + value * alpha;
    }

    private void updateCalibration(float temp, double cpu, double ramFree, float power,
                                   int pressure, int thermal, boolean interactive) {
        if (!prefs.getBoolean("auto_calibration", true)) return;
        if (!interactive || pressure > 1 || thermal > 0 || ramFree < 20 || cpu < 0 || cpu > 55
                || prefs.getFloat("psi_memory", -1) < 0 || prefs.getFloat("psi_memory", 100) >= 8
                || cachedLmkDelta > 1) return;
        int n = prefs.getInt("cal_n", 0);
        float alpha = n < 30 ? 0.10f : 0.025f;
        SharedPreferences.Editor e = prefs.edit();
        if (temp > 0) e.putFloat("cal_temp", ema(prefs.getFloat("cal_temp", -1f), temp, alpha));
        if (cpu >= 0) e.putFloat("cal_cpu", ema(prefs.getFloat("cal_cpu", -1f), (float)cpu, alpha));
        if (ramFree >= 0) e.putFloat("cal_ram_used", ema(prefs.getFloat("cal_ram_used", -1f), (float)(100.0 - ramFree), alpha));
        if (power > 0) e.putFloat("cal_power", ema(prefs.getFloat("cal_power", -1f), power, alpha));
        e.putFloat("cal_ram_free", ema(prefs.getFloat("cal_ram_free", -1), (float)ramFree, alpha));
        e.putFloat("cal_psi_memory", ema(prefs.getFloat("cal_psi_memory", -1), prefs.getFloat("psi_memory", 0), alpha));
        if (cachedZramSwap >= 0) e.putFloat("cal_zram_usage", ema(prefs.getFloat("cal_zram_usage", -1), cachedZramSwap, alpha));
        e.putFloat("cal_lmk_rate", ema(prefs.getFloat("cal_lmk_rate", -1), cachedLmkDelta, alpha));
        int nn = Math.min(10000, n + 1);
        e.putInt("cal_n", nn).putBoolean("calibrated", nn >= 40).apply();
    }

    private int thermalScore(float soc, float bat, int level) {
        int s = 0;
        if (soc >= 66f || level >= 5) s = 100;
        else if (soc >= 63f || level >= 4) s = 85;
        else if (soc >= 60f || level >= 3) s = 70;
        else if (soc >= 57f || level >= 2) s = 55;
        else if (soc >= 54f || level >= 1) s = 35;
        else if (soc > 0f) s = Math.max(0, Math.min(30, Math.round((soc - 38f) * 2f)));
        if (bat >= 43f) s = Math.max(s, 85);
        else if (bat >= 40f) s = Math.max(s, 60);
        return clamp(s);
    }

    private int cpuScore(double cpu) {
        if (cpu < 0) return 0;
        if (cpu >= 95) return 100;
        if (cpu >= 85) return 85;
        if (cpu >= 70) return 65;
        if (cpu >= 55) return 45;
        return clamp((int)Math.round(cpu * 0.55));
    }

    private int memoryScore(double free, int pressure, int lmk, float zramSwapMb) {
        double warning = prefs.getBoolean("calibrated", false)
                ? Math.max(20, Math.min(24, prefs.getFloat("cal_ram_free", 30) * 0.7)) : 24;
        prefs.edit().putFloat("memory_warning_threshold", (float)warning).apply();
        if (free >= warning && prefs.getFloat("psi_memory", 0) < 8) lmk = 0;
        int s = pressure * 16;
        if (free > 0 && free < 6) s = Math.max(s, 95);
        else if (free > 0 && free < 10) s = Math.max(s, 75);
        else if (free > 0 && free < 16) s = Math.max(s, 55);
        else if (free > 0 && free < warning) s = Math.max(s, 35);
        if (lmk >= 12) s += 30;
        else if (lmk >= 5) s += 18;
        else if (lmk >= 2) s += 8;
        if (zramSwapMb >= 6500) s += 15;
        return clamp(s);
    }

    private int ioScore(float io) {
        if (io < 0) return 0;
        return clamp(Math.round(io));
    }

    private int uiScore(float jank) {
        if (jank < 0) return 0;
        if (jank >= 20) return 100;
        if (jank >= 12) return 85;
        if (jank >= 8) return 70;
        if (jank >= 5) return 55;
        if (jank >= 3) return 35;
        return clamp(Math.round(jank * 10f));
    }

    private int batteryScore(float watts, boolean interactive) {
        if (watts <= 0) return 0;
        float base = prefs.getFloat("cal_power", -1f);
        float abnormal = base > 0 ? Math.max(base * 2.2f, base + 3.0f) : (interactive ? 8.5f : 2.5f);
        if (watts >= abnormal * 1.5f) return 100;
        if (watts >= abnormal) return 75;
        if (watts >= abnormal * 0.75f) return 50;
        if (watts >= abnormal * 0.55f) return 30;
        return 10;
    }

    private int clamp(int v) { return Math.max(0, Math.min(100, v)); }

    private void appendHistory(float temp, double cpu, double ramFree, float power,
                               int pressure, int thermal, Result r) {
        long now = System.currentTimeMillis();
        String line = String.format(Locale.US,
                "%d,%.1f,%.0f,%.0f,%.1f,%d,%d,%d,%d,%d,%d,%d,%d",
                now, temp, cpu, ramFree, power, pressure, thermal,
                r.thermalScore, r.memoryScore, r.cpuScore, r.ioScore, r.batteryScore, r.uiScore);
        String old = prefs.getString("history_24h_data", "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\n");
        long cutoff = now - 24L * 60L * 60L * 1000L;
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (int i = Math.max(0, lines.length - 287); i < lines.length; i++) {
            String x = lines[i].trim();
            if (x.isEmpty()) continue;
            int comma = x.indexOf(',');
            if (comma <= 0) continue;
            try {
                long ts = Long.parseLong(x.substring(0, comma));
                if (ts < cutoff) continue;
            } catch (Throwable t) { continue; }
            if (sb.length() > 0) sb.append('\n');
            sb.append(x);
            kept++;
        }
        if (sb.length() > 0) sb.append('\n');
        sb.append(line);
        prefs.edit()
                .putString("history_24h_data", sb.toString())
                .putInt("history_points", kept + 1)
                .putLong("history_last", now)
                .apply();
    }

    private String safePackage(String pkg) {
        if (pkg == null) return "";
        String p = pkg.trim();
        return p.matches("[A-Za-z0-9_.]+") ? p : "";
    }
}

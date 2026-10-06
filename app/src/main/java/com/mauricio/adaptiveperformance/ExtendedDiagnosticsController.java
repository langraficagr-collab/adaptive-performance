package com.mauricio.adaptiveperformance;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.SystemClock;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extended diagnostics/prediction layer.
 *
 * The hot path (update) performs no shell commands. All dumpsys/logcat work is
 * done by maybeRunDiagnostics(), which OptimizationService invokes on a worker
 * executor. Thermal safety is never relaxed by this class.
 */
public final class ExtendedDiagnosticsController {
    private static final long NORMAL_SCAN_MS = 8L * 60L * 1000L;
    private static final long BURST_SCAN_MS = 60_000L;
    private static final long BINDER_SCAN_MS = 10L * 60L * 1000L;
    private static final long DIAGNOSTIC_BURST_MS = 10L * 60L * 1000L;
    private static final long SAFE_MODE_MS = 30L * 60L * 1000L;
    private static final long AB_WINDOW_MS = 6L * 60L * 60L * 1000L;
    private static final long MEMORY_LEAK_SAMPLE_MS = 4L * 60L * 1000L;
    private static final long MEMORY_LEAK_KEEP_MS = 30L * 60L * 1000L;

    private static final class MemPoint {
        final long at;
        final long pssKb;
        MemPoint(long at, long pssKb) { this.at = at; this.pssKb = pssKb; }
    }

    public static final class Result {
        public float predicted1m = -1f;
        public float predicted3m = -1f;
        public int predictedThermalLevel = 0;
        public int adaptiveAggressiveness = 0;
        public int appStability = 100;
        public int launchStabilizationMs = -1;
        public boolean diagnosticMode;
        public boolean safeMode;
        public String chargerProfile = "Bateria";
        public String activityProfile = "Uso leve";
        public String probableCause = "Sem causa anormal detectada";
        public String abState = "A/B aguardando calibração";
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final IPrivilegedService privileged;

    private volatile long lastScanElapsed = 0L;
    private volatile long lastBinderScanElapsed = 0L;
    private volatile long diagnosticBurstUntil = 0L;
    private volatile long safeModeUntil = 0L;
    private boolean anrBaselineEstablished = false;
    private final long currentVersionCode;

    private volatile int anrRecent = 0;
    private volatile int ownAnrRecent = 0;
    private volatile int binderSlowCalls = 0;
    private volatile int binderVerySlowCalls = 0;
    private volatile String binderTop = "";
    private volatile float gpuP90Ms = -1f;
    private volatile int activeSensors = 0;
    private volatile int openSensorConnections = 0;
    private volatile int activeLocationRequests = 0;
    private volatile String activeLocationPackages = "";
    private volatile boolean cameraActive = false;
    private volatile boolean micActive = false;
    private volatile int signalRsrp = 0;
    private volatile int signalLevel = -1;
    private volatile String radioType = "";
    private volatile int diskWriteKb = -1;
    private volatile int dataFreePct = -1;
    private volatile int duplicateServices = 0;
    private volatile String duplicateNames = "";
    private volatile int launchLogMs = -1;
    private volatile String lastAnrPackage = "";
    private volatile boolean memoryLeakSuspected = false;
    private volatile String memoryLeakPkg = "";
    private volatile String memoryLeakLabel = "";
    private volatile float memoryLeakGrowthMb = -1f;
    private volatile float memoryLeakPssMb = -1f;
    private volatile float memoryLeakSpanMin = -1f;
    private volatile long memoryLeakDetectedElapsed = 0L;
    private final Map<String,ArrayDeque<MemPoint>> memoryHistory = new HashMap<>();
    private final Map<String,Long> lastMemorySample = new HashMap<>();

    private String lastFg = "";
    private long fgChangedAt = 0L;
    private boolean launchPending = false;

    public ExtendedDiagnosticsController(Context context, SharedPreferences prefs,
                                         IPrivilegedService privileged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.privileged = privileged;
        long vc = -1L;
        try {
            vc = this.context.getPackageManager()
                    .getPackageInfo(this.context.getPackageName(), 0).getLongVersionCode();
        } catch (Throwable ignored) {}
        this.currentVersionCode = vc;
        long now = SystemClock.elapsedRealtime();
        this.safeModeUntil = prefs.getLong("extended_safe_until_elapsed", 0L);
        this.diagnosticBurstUntil = prefs.getLong("diagnostic_burst_until_elapsed", 0L);
        if (safeModeUntil > now + SAFE_MODE_MS * 2L) safeModeUntil = 0L;
        if (diagnosticBurstUntil > now + DIAGNOSTIC_BURST_MS * 2L) diagnosticBurstUntil = 0L;
        loadCachedDiagnostics();

        long safeVersion = prefs.getLong("extended_safe_version_code", -1L);
        String safeReason = prefs.getString("extended_safe_reason", "");
        if (safeModeUntil > now && safeVersion != currentVersionCode &&
                safeReason.startsWith("ANR do próprio")) {
            safeModeUntil = 0L;
            ownAnrRecent = 0;
            prefs.edit()
                    .remove("extended_safe_until_elapsed")
                    .remove("extended_safe_reason")
                    .putBoolean("extended_safe_mode", false)
                    .apply();
        }
    }

    public Result update(String fg, float socTemp, float batteryTemp, float tempTrend,
                         double cpuLoad, double ramFreePct, float powerW,
                         int thermalLevel, int pressureScore,
                         int healthThermal, int healthMemory, int healthCpu,
                         int healthIo, int healthBattery, int healthUi,
                         int lmkDelta, float jankPct, boolean interactive) {
        Result r = new Result();
        long now = SystemClock.elapsedRealtime();

        r.safeMode = now < safeModeUntil || prefs.getBoolean("extended_safe_mode_manual", false);
        r.diagnosticMode = now < diagnosticBurstUntil;
        r.chargerProfile = chargerProfile(powerW);
        r.launchStabilizationMs = observeLaunch(fg, now);

        float loadHeat = 0f;
        if (cpuLoad >= 0) loadHeat += Math.max(0f, (float)(cpuLoad - 55.0) / 45f) * 0.7f;
        if (powerW > 0) loadHeat += Math.max(0f, (powerW - 5f) / 8f) * 0.6f;
        float trend = clamp(tempTrend, -2.2f, 3.0f);
        if (socTemp > 0) {
            r.predicted1m = socTemp + trend + loadHeat;
            float delta3 = clamp(trend * 2.1f + loadHeat * 2.1f, -4.5f, 8.5f);
            r.predicted3m = socTemp + delta3;
            r.predictedThermalLevel = predictedLevel(r.predicted3m, batteryTemp, r.chargerProfile);
        }

        boolean abnormalHeat = r.predicted3m >= 62f || healthThermal >= 85 ||
                (batteryTemp >= 40f && socTemp >= 58f);
        boolean severeStall = healthUi >= 85 || healthMemory >= 85 || healthCpu >= 90;
        if (prefs.getBoolean("diagnostic_burst", true) && (abnormalHeat || severeStall)) {
            diagnosticBurstUntil = Math.max(diagnosticBurstUntil, now + DIAGNOSTIC_BURST_MS);
            prefs.edit().putLong("diagnostic_burst_until_elapsed", diagnosticBurstUntil).apply();
            r.diagnosticMode = true;
        }

        if (ownAnrRecent > 0 || prefs.getLong("health_scan_cost_ms", 0L) >= 12_000L) {
            safeModeUntil = Math.max(safeModeUntil, now + SAFE_MODE_MS);
            prefs.edit()
                    .putLong("extended_safe_until_elapsed", safeModeUntil)
                    .putLong("extended_safe_version_code", currentVersionCode)
                    .putString("extended_safe_reason",
                            ownAnrRecent > 0 ? "ANR do próprio Adaptive Performance" :
                                    "Diagnóstico profundo excedeu 12 segundos")
                    .apply();
            r.safeMode = true;
        }

        r.activityProfile = classifyActivity(fg, cpuLoad, powerW, interactive);
        r.appStability = updateAppStability(fg, healthThermal, healthMemory, healthCpu,
                healthIo, healthBattery, healthUi, lmkDelta, jankPct);

        int delta = 0;
        if (!r.safeMode && prefs.getBoolean("adaptive_aggressiveness", true) &&
                socTemp > 0f && socTemp < 60f && thermalLevel <= 2) {
            if (healthUi >= 70 || lmkDelta >= 8) delta = -1;
            boolean backgroundLeak = powerW >= 5.5f &&
                    (activeLocationRequests > 0 || activeSensors >= 12 ||
                            (signalRsrp != 0 && signalRsrp <= -115) ||
                            diskWriteKb >= 40_000);
            if (backgroundLeak && healthUi < 55 && healthMemory < 70) delta = 1;
        }

        int abDelta = updateAbTest(healthThermal, healthMemory, healthCpu, healthBattery,
                healthUi, lmkDelta, powerW, socTemp, thermalLevel);
        if (delta >= 0) delta = Math.max(delta, abDelta);
        r.adaptiveAggressiveness = clampInt(delta, -1, 1);
        r.abState = prefs.getString("ab_state", "A/B aguardando calibração");

        r.probableCause = buildProbableCause(r, socTemp, batteryTemp, powerW,
                healthMemory, healthCpu, healthIo, healthUi, lmkDelta);

        prefs.edit()
                .putFloat("thermal_pred_1m", r.predicted1m)
                .putFloat("thermal_pred_3m", r.predicted3m)
                .putInt("thermal_pred_level", r.predictedThermalLevel)
                .putString("charger_profile", r.chargerProfile)
                .putString("activity_profile_extended", r.activityProfile)
                .putString("probable_cause", r.probableCause)
                .putInt("adaptive_aggressiveness_delta", r.adaptiveAggressiveness)
                .putInt("current_app_stability", r.appStability)
                .putInt("launch_stabilization_ms", r.launchStabilizationMs)
                .putBoolean("diagnostic_mode_active", r.diagnosticMode)
                .putBoolean("extended_safe_mode", r.safeMode)
                .apply();

        CauseResolutionNotifier.maybeNotify(context, prefs, r.probableCause);
        return r;
    }

    public synchronized void maybeRunDiagnostics(String fg, boolean interactive) {
        if (!prefs.getBoolean("extended_diagnostics", true)) return;
        long now = SystemClock.elapsedRealtime();
        if (now < safeModeUntil || prefs.getBoolean("extended_safe_mode_manual", false)) return;

        long interval = now < diagnosticBurstUntil ? BURST_SCAN_MS : NORMAL_SCAN_MS;
        if (now - lastScanElapsed < interval) return;
        lastScanElapsed = now;

        long started = SystemClock.elapsedRealtime();
        try { scanAnr(); } catch (Throwable ignored) {}
        try { scanGpu(fg); } catch (Throwable ignored) {}
        if (prefs.getBoolean("sensor_modem_guard", true)) {
            try { scanSensorsAndLocation(); } catch (Throwable ignored) {}
            try { scanAppOps(); } catch (Throwable ignored) {}
            try { scanSignal(); } catch (Throwable ignored) {}
        }
        if (prefs.getBoolean("storage_guard", true)) {
            try { scanStorage(); } catch (Throwable ignored) {}
        }
        if (prefs.getBoolean("duplicate_detection", true)) {
            try { scanDuplicates(); } catch (Throwable ignored) {}
        }
        try { scanLaunchLog(); } catch (Throwable ignored) {}
        if (prefs.getBoolean("memory_leak_detector", true)) {
            try { scanMemoryLeak(fg, now); } catch (Throwable ignored) {}
        }

        if (now - lastBinderScanElapsed >= BINDER_SCAN_MS) {
            lastBinderScanElapsed = now;
            try { scanBinder(); } catch (Throwable ignored) {}
        }

        long cost = Math.max(0L, SystemClock.elapsedRealtime() - started);
        prefs.edit().putLong("extended_scan_cost_ms", cost).apply();
        persistCachedDiagnostics();

        if (cost >= 12_000L) {
            safeModeUntil = SystemClock.elapsedRealtime() + SAFE_MODE_MS;
            prefs.edit()
                    .putLong("extended_safe_until_elapsed", safeModeUntil)
                    .putLong("extended_safe_version_code", currentVersionCode)
                    .putString("extended_safe_reason",
                            "Varredura estendida demorou " + cost + " ms")
                    .apply();
        }
    }

    private void scanMemoryLeak(String fg, long now) throws Exception {
        String pkg = safePackage(fg);
        if (pkg.isEmpty() || pkg.equals(context.getPackageName())) return;

        long last = lastMemorySample.getOrDefault(pkg, 0L);
        if (last > 0L && now - last < MEMORY_LEAK_SAMPLE_MS) return;
        lastMemorySample.put(pkg, now);

        String raw = privileged.exec(
                "dumpsys meminfo " + pkg + " 2>/dev/null | " +
                "grep -E 'TOTAL PSS:|^[[:space:]]*TOTAL[[:space:]]+[0-9]' | head -2");
        if (raw == null || raw.isEmpty()) return;

        long pssKb = -1L;
        Matcher pss = Pattern.compile("TOTAL PSS:\s*([0-9]+)").matcher(raw);
        if (pss.find()) {
            try { pssKb = Long.parseLong(pss.group(1)); } catch (Throwable ignored) {}
        }
        if (pssKb < 0L) {
            Matcher total = Pattern.compile("(?m)^\s*TOTAL\s+([0-9]+)").matcher(raw);
            if (total.find()) {
                try { pssKb = Long.parseLong(total.group(1)); } catch (Throwable ignored) {}
            }
        }
        if (pssKb <= 0L) return;

        ArrayDeque<MemPoint> q = memoryHistory.computeIfAbsent(pkg, k -> new ArrayDeque<>());
        q.addLast(new MemPoint(now, pssKb));
        while (q.size() > 8) q.removeFirst();
        while (!q.isEmpty() && now - q.peekFirst().at > 45L * 60L * 1000L) q.removeFirst();

        if (q.size() >= 5) {
            MemPoint first = q.peekFirst();
            MemPoint lastPoint = q.peekLast();
            long span = lastPoint.at - first.at;
            if (span >= 20L * 60L * 1000L) {
                long growthKb = lastPoint.pssKb - first.pssKb;
                long required = Math.max(120L * 1024L, Math.round(first.pssKb * 0.30));
                int rises = 0;
                MemPoint prev = null;
                for (MemPoint point : q) {
                    if (prev != null && point.pssKb >= prev.pssKb * 0.97) rises++;
                    prev = point;
                }
                boolean sustained = rises >= q.size() - 2;
                boolean largeEnough = lastPoint.pssKb >= 300L * 1024L;
                if (growthKb >= required && sustained && largeEnough) {
                    memoryLeakSuspected = true;
                    memoryLeakPkg = pkg;
                    memoryLeakLabel = AppSafety.label(context, pkg);
                    memoryLeakGrowthMb = growthKb / 1024f;
                    memoryLeakPssMb = lastPoint.pssKb / 1024f;
                    memoryLeakSpanMin = span / 60000f;
                    memoryLeakDetectedElapsed = now;
                }
            }
        }

        if (memoryLeakSuspected && memoryLeakDetectedElapsed > 0L &&
                now - memoryLeakDetectedElapsed > MEMORY_LEAK_KEEP_MS) {
            memoryLeakSuspected = false;
            memoryLeakPkg = "";
            memoryLeakLabel = "";
            memoryLeakGrowthMb = -1f;
            memoryLeakPssMb = -1f;
            memoryLeakSpanMin = -1f;
        }

        prefs.edit()
                .putBoolean("memory_leak_suspected", memoryLeakSuspected)
                .putString("memory_leak_pkg", memoryLeakPkg)
                .putString("memory_leak_label", memoryLeakLabel)
                .putFloat("memory_leak_growth_mb", memoryLeakGrowthMb)
                .putFloat("memory_leak_pss_mb", memoryLeakPssMb)
                .putFloat("memory_leak_span_min", memoryLeakSpanMin)
                .putLong("memory_leak_last_sample", System.currentTimeMillis())
                .apply();
    }

    private void scanAnr() throws Exception {
        String raw = privileged.exec(
                "logcat -b events -d -t 3000 2>/dev/null | grep 'am_anr' | tail -120");
        int count = 0;
        String lastPkg = "";
        String newestOwnLine = "";
        if (raw != null) {
            Pattern p = Pattern.compile("([A-Za-z][A-Za-z0-9_.]{3,})");
            for (String line : raw.split("\n")) {
                if (!line.contains("am_anr")) continue;
                count++;
                Matcher m = p.matcher(line);
                while (m.find()) {
                    String v = m.group(1);
                    if (v.contains(".") && !v.startsWith("am_")) lastPkg = v;
                }
                if (line.contains(context.getPackageName())) newestOwnLine = line;
            }
        }
        int own = 0;
        if (!newestOwnLine.isEmpty()) {
            String hash = Integer.toHexString(newestOwnLine.hashCode());
            String oldHash = prefs.getString("last_own_anr_hash", "");
            if (!anrBaselineEstablished) {
                anrBaselineEstablished = true;
                prefs.edit().putString("last_own_anr_hash", hash).apply();
            } else if (!hash.equals(oldHash)) {
                own = 1;
                prefs.edit().putString("last_own_anr_hash", hash).apply();
            }
        } else {
            anrBaselineEstablished = true;
        }
        anrRecent = count;
        ownAnrRecent = own;
        lastAnrPackage = lastPkg;
    }

    private void scanBinder() throws Exception {
        String raw = privileged.exec("dumpsys binder_calls_stats 2>/dev/null | head -320");
        int slow = 0, very = 0;
        long max = 0L;
        String top = "";
        if (raw != null) {
            for (String line : raw.split("\n")) {
                if (!line.contains(",")) continue;
                String[] p = line.split(",");
                if (p.length < 8) continue;
                long maxLatency;
                try { maxLatency = Long.parseLong(p[7].trim()); }
                catch (Throwable t) { continue; }
                if (maxLatency >= 500_000L) slow++;
                if (maxLatency >= 2_000_000L) very++;
                if (maxLatency > max) {
                    max = maxLatency;
                    top = cleanBinderIdentity(p[0]);
                }
            }
        }
        binderSlowCalls = slow;
        binderVerySlowCalls = very;
        binderTop = top;
        prefs.edit().putLong("binder_max_latency_us", max).apply();
    }

    private String cleanBinderIdentity(String x) {
        if (x == null) return "";
        String s = x.trim();
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        return s;
    }

    private void scanGpu(String fg) throws Exception {
        String pkg = safePackage(fg);
        if (pkg.isEmpty()) return;
        String raw = privileged.exec(
                "dumpsys gfxinfo " + pkg + " 2>/dev/null | grep -m1 '90th gpu percentile'");
        Matcher m = Pattern.compile("90th gpu percentile:\\s*([0-9.]+)ms").matcher(raw == null ? "" : raw);
        if (m.find()) {
            try { gpuP90Ms = Float.parseFloat(m.group(1)); }
            catch (Throwable ignored) {}
        }
    }

    private void scanSensorsAndLocation() throws Exception {
        String raw = privileged.exec(
                "dumpsys sensorservice 2>/dev/null | grep -E 'active-count =|open event connections' | head -80; " +
                "echo __LOC__; dumpsys location 2>/dev/null | grep '(active)' | head -80");
        if (raw == null) return;
        String[] parts = raw.split("__LOC__", 2);
        int sensors = 0, open = 0;
        Matcher a = Pattern.compile("active-count = ([0-9]+)").matcher(parts[0]);
        while (a.find()) {
            try {
                int n = Integer.parseInt(a.group(1));
                if (n > 0) sensors++;
            } catch (Throwable ignored) {}
        }
        Matcher oc = Pattern.compile("([0-9]+) open event connections").matcher(parts[0]);
        if (oc.find()) {
            try { open = Integer.parseInt(oc.group(1)); } catch (Throwable ignored) {}
        }
        activeSensors = sensors;
        openSensorConnections = open;

        int loc = 0;
        LinkedHashSet<String> pkgs = new LinkedHashSet<>();
        if (parts.length > 1) {
            Matcher lp = Pattern.compile("[0-9]+/([A-Za-z0-9_.]+)").matcher(parts[1]);
            while (lp.find()) {
                loc++;
                pkgs.add(lp.group(1));
            }
        }
        activeLocationRequests = loc;
        activeLocationPackages = join(pkgs, ", ");
    }

    private void scanAppOps() throws Exception {
        String raw = privileged.exec(
                "dumpsys appops 2>/dev/null | grep -B4 -A2 'running=true' | head -240");
        if (raw == null) {
            cameraActive = false;
            micActive = false;
            return;
        }
        String lower = raw.toLowerCase(Locale.US);
        cameraActive = lower.contains("camera") && lower.contains("running=true");
        micActive = (lower.contains("record_audio") || lower.contains("microphone")) &&
                lower.contains("running=true");
    }

    private void scanSignal() throws Exception {
        String raw = privileged.exec(
                "dumpsys telephony.registry 2>/dev/null | grep 'mSignalStrength=' | tail -1");
        if (raw == null) return;
        Matcher r = Pattern.compile("rsrp=(-?[0-9]+)").matcher(raw);
        if (r.find()) {
            try { signalRsrp = Integer.parseInt(r.group(1)); } catch (Throwable ignored) {}
        }
        Matcher l = Pattern.compile("level=([0-9]+)").matcher(raw);
        if (l.find()) {
            try { signalLevel = Integer.parseInt(l.group(1)); } catch (Throwable ignored) {}
        }
        if (raw.contains("CellSignalStrengthNr") && !raw.contains("mNr=invalid")) radioType = "5G";
        else if (raw.contains("CellSignalStrengthLte") && !raw.contains("mLte=invalid")) radioType = "4G/LTE";
        else if (raw.contains("CellSignalStrengthWcdma") && !raw.contains("mWcdma=invalid")) radioType = "3G";
        else radioType = "Celular";
    }

    private void scanStorage() throws Exception {
        String raw = privileged.exec(
                "dumpsys diskstats 2>/dev/null | grep -E 'Recent Disk Write Speed|Data-Free:' | head -4");
        if (raw == null) return;
        Matcher w = Pattern.compile("Recent Disk Write Speed \\(kB/s\\) = ([0-9]+)").matcher(raw);
        if (w.find()) {
            try { diskWriteKb = Integer.parseInt(w.group(1)); } catch (Throwable ignored) {}
        }
        Matcher f = Pattern.compile("Data-Free:.*= ([0-9]+)% free").matcher(raw);
        if (f.find()) {
            try { dataFreePct = Integer.parseInt(f.group(1)); } catch (Throwable ignored) {}
        }
    }

    private final Map<String,Integer> duplicateStreaks = new HashMap<>();
    private final DuplicateProcessDetector duplicateDetector = new DuplicateProcessDetector();
    private void scanDuplicates() throws Exception {
        String raw;
        try { raw = privileged.exec("ps -A -o PID,NAME,ARGS"); }
        catch (Exception e) { duplicateDetector.scan(null); throw e; }
        Map<String, Integer> matches = duplicateDetector.scan(raw);
        duplicateServices = 0;
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Integer> e : matches.entrySet()) {
            duplicateServices += e.getValue() - 1;
            String readable = e.getKey();
            if (readable.length() > 240) readable = readable.substring(0, 240) + "…";
            names.add(readable + " ×" + e.getValue());
        }
        duplicateNames = join(names, "; ");
        SharedPreferences.Editor de = prefs.edit().putInt("duplicate_services", duplicateServices)
                .putString("duplicate_names", duplicateNames);
        if (duplicateServices == 0 && prefs.getString("probable_cause", "").toLowerCase(Locale.US).contains("sobreposição de automações")) {
            de.putString("probable_cause", "Sem causa anormal detectada")
                    .remove("active_cause_id").remove("active_cause_text")
                    .remove("active_cause_solution").remove("active_cause_autofix");
        }
        de.apply();
    }

    private void scanLaunchLog() throws Exception {
        String raw = privileged.exec(
                "logcat -b events -d -t 2500 2>/dev/null | grep -E 'am_activity_launch_time|am_activity_fully_drawn_time' | tail -60");
        if (raw == null || raw.isEmpty()) return;
        Matcher m = Pattern.compile("([0-9]{2,6})").matcher(raw.substring(Math.max(0, raw.length() - 500)));
        int last = -1;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 50 && v <= 60_000) last = v;
            } catch (Throwable ignored) {}
        }
        if (last > 0) launchLogMs = last;
    }

    private int observeLaunch(String fg, long now) {
        String pkg = safePackage(fg);
        if (pkg.isEmpty()) return launchLogMs;
        if (!pkg.equals(lastFg)) {
            lastFg = pkg;
            fgChangedAt = now;
            launchPending = true;
            return -1;
        }
        if (launchPending && fgChangedAt > 0L) {
            long elapsed = now - fgChangedAt;
            if (elapsed >= 100L && elapsed <= 20_000L) {
                launchPending = false;
                int ms = (int)elapsed;
                String h = Integer.toHexString(pkg.hashCode());
                float old = prefs.getFloat("app_launch_ms_" + h, -1f);
                float avg = old < 0 ? ms : old * 0.8f + ms * 0.2f;
                prefs.edit()
                        .putFloat("app_launch_ms_" + h, avg)
                        .putString("app_pkg_" + h, pkg)
                        .apply();
                return Math.round(avg);
            }
        }
        if (launchLogMs > 0) return launchLogMs;
        String h = Integer.toHexString(pkg.hashCode());
        float avg = prefs.getFloat("app_launch_ms_" + h, -1f);
        return avg > 0 ? Math.round(avg) : -1;
    }

    private int updateAppStability(String fg, int ht, int hm, int hc, int hi, int hb, int hu,
                                   int lmk, float jank) {
        String pkg = safePackage(fg);
        if (pkg.isEmpty()) return 100;
        int penalty = Math.round(ht * 0.16f + hm * 0.18f + hc * 0.12f + hi * 0.10f +
                hb * 0.12f + hu * 0.22f);
        penalty += Math.min(15, lmk);
        if (jank >= 8) penalty += 10;
        int current = clampInt(100 - penalty, 0, 100);
        String h = Integer.toHexString(pkg.hashCode());
        float old = prefs.getFloat("app_stability_" + h, -1f);
        float avg = old < 0 ? current : old * 0.88f + current * 0.12f;
        prefs.edit()
                .putFloat("app_stability_" + h, avg)
                .putString("app_pkg_" + h, pkg)
                .apply();
        return Math.round(avg);
    }

    private String classifyActivity(String fg, double cpu, float power, boolean interactive) {
        String p = fg == null ? "" : fg.toLowerCase(Locale.US);
        if (!interactive) return "Repouso";
        if (cameraActive) return "Câmera";
        if (activeLocationRequests > 0 &&
                (containsPackage(activeLocationPackages, fg) || p.contains("waze") || p.contains("maps")))
            return "Navegação/GPS";
        if (micActive) return "Áudio/voz";
        if (gpuP90Ms >= 16f) return "Renderização/GPU";
        if ((cpu >= 75 && power >= 5.5f) || p.contains("qwen") || p.contains("ai"))
            return "IA/carga pesada";
        if (p.contains("vibes") || p.contains("musically") || p.contains("tiktok") ||
                p.contains("youtube") || p.contains("video")) return "Vídeo/mídia";
        if (!"Bateria".equals(chargerProfile(power))) return "Carregando";
        return "Uso leve";
    }

    private boolean containsPackage(String csv, String pkg) {
        if (csv == null || pkg == null || pkg.isEmpty()) return false;
        return Arrays.asList(csv.split(",\\s*")).contains(pkg);
    }

    private String chargerProfile(float power) {
        try {
            Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return "Bateria";
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (plugged == BatteryManager.BATTERY_PLUGGED_USB)
                return power >= 10f ? "USB rápido" : "USB";
            if (plugged == BatteryManager.BATTERY_PLUGGED_AC)
                return power >= 18f ? "Carregamento rápido" : "Carregador AC";
            if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS)
                return "Sem fio";
            return "Bateria";
        } catch (Throwable t) {
            return "Bateria";
        }
    }

    private int predictedLevel(float pred3, float batteryTemp, String charger) {
        int level = 0;
        if (pred3 >= 66f) level = 5;
        else if (pred3 >= 64f) level = 4;
        else if (pred3 >= 62f) level = 3;
        else if (pred3 >= 59f) level = 2;
        else if (pred3 >= 56f) level = 1;

        boolean charging = !"Bateria".equals(charger);
        if (charging && batteryTemp >= 42f) level = Math.max(level, 4);
        else if (charging && batteryTemp >= 40f) level = Math.max(level, 3);
        else if (charging && batteryTemp >= 38.5f && pred3 >= 58f) level = Math.max(level, 2);
        return level;
    }

    private String buildProbableCause(Result r, float soc, float bat, float power,
                                      int memory, int cpu, int io, int ui, int lmk) {
        if (r.safeMode) return "Módulos avançados em modo seguro; proteção térmica básica preservada";
        if (prefs.getBoolean("persistent_wakelock_unresolved", false))
            return "Wakelock persistente sem tarefa ativa: " +
                    prefs.getString("persistent_wakelock_report", "processo mantendo o aparelho acordado");
        if (prefs.getBoolean("stuck_sync_detected", false))
            return "Sincronização pendente por tempo anormal: " +
                    prefs.getString("stuck_sync_report", "sincronização não concluída");
        if (prefs.getBoolean("background_sensor_suspected", false))
            return "Sensor em segundo plano persistente: " +
                    prefs.getString("background_sensor_report", "aplicativo mantendo sensor ativo");
        if (memoryLeakSuspected)
            return String.format(Locale.US,
                    "Possível vazamento de memória em %s: +%.0f MB em %.0f min",
                    memoryLeakLabel.isEmpty() ? memoryLeakPkg : memoryLeakLabel,
                    memoryLeakGrowthMb, memoryLeakSpanMin);
        if (prefs.getBoolean("deep_sleep_monitor", true) &&
                prefs.getBoolean("deep_sleep_poor", false)) {
            float ds = prefs.getFloat("deep_sleep_pct", -1f);
            if (ds >= 0f)
                return String.format(Locale.US,
                        "Repouso profundo baixo: apenas %.0f%% do período com a tela apagada", ds);
        }
        if (r.predicted3m >= 64f && !"Bateria".equals(r.chargerProfile))
            return "Aquecimento previsto: carga + processador/SoC";
        if (signalRsrp != 0 && signalRsrp <= -115 && power >= 4.5f)
            return "Consumo provável do modem por sinal celular fraco";
        if (cameraActive && power >= 5f) return "Câmera/ISP mantendo carga elevada";
        if (activeLocationRequests > 0 && power >= 4f) return "GPS/localização contribuindo para consumo";
        if (activeSensors >= 12 && power >= 4f) return "Muitos sensores ativos simultaneamente";
        if (binderVerySlowCalls >= 2 && ui >= 55) return "Binder lento associado a perda de fluidez";
        if (diskWriteKb >= 40_000 || io >= 70) return "Escrita/armazenamento saturando I/O";
        float ramFreeNow = prefs.getFloat("ram_free_pct", -1f);
        boolean aggressiveRam = prefs.getBoolean("aggressive_memory_cleanup", false);
        float ramPressureWindow = aggressiveRam ? 25f : 15f;
        boolean lmkStillRelevant = lmk >= 8 && ramFreeNow > 0f && ramFreeNow < ramPressureWindow;
        if (memory >= 75 || lmkStillRelevant) return "Pressão de RAM/LMKD e zRAM";
        if (gpuP90Ms >= 20f && ui >= 55) return "Renderização/GPU associada ao jank";
        if (duplicateServices > 0 && (power >= 4f || cpu >= 55))
            return "Possível sobreposição de automações: " + duplicateNames;
        if (cpu >= 80) return "Carga elevada de CPU";
        if (bat >= 40f && soc >= 58f) return "Bateria e SoC aquecendo em conjunto";
        return "Sem causa anormal detectada";
    }

    private int updateAbTest(int ht, int hm, int hc, int hb, int hu, int lmk,
                             float power, float soc, int thermalLevel) {
        if (!prefs.getBoolean("ab_testing", true)) {
            prefs.edit().putString("ab_state", "A/B desativado").apply();
            return 0;
        }
        if (!prefs.getBoolean("calibrated", false)) {
            prefs.edit().putString("ab_state", "A/B aguardando autocalibração").apply();
            return 0;
        }
        if (soc <= 0 || soc >= 58f || thermalLevel > 1) {
            prefs.edit().putString("ab_state", "A/B pausado por temperatura").apply();
            return selectedAbDelta();
        }

        long now = System.currentTimeMillis();
        long start = prefs.getLong("ab_window_start", 0L);
        int variant = prefs.getInt("ab_variant", 0);
        double penalty = ht * 0.16 + hm * 0.18 + hc * 0.14 + hb * 0.18 + hu * 0.24 +
                Math.min(20, lmk) + Math.max(0, power - 3f) * 2.0;

        SharedPreferences.Editor e = prefs.edit();
        if (start <= 0L) {
            e.putLong("ab_window_start", now)
                    .putInt("ab_variant", variant)
                    .putString("ab_state", variant == 0 ? "A/B testando perfil A" : "A/B testando perfil B")
                    .apply();
            return variant == 1 ? 1 : 0;
        }

        double sum = Double.longBitsToDouble(prefs.getLong("ab_sum_bits",
                Double.doubleToLongBits(0.0)));
        int n = prefs.getInt("ab_samples", 0);
        sum += penalty;
        n++;
        e.putLong("ab_sum_bits", Double.doubleToLongBits(sum)).putInt("ab_samples", n).apply();

        if (now - start >= AB_WINDOW_MS && n >= 60) {
            float score = (float)(sum / n);
            if (variant == 0) {
                prefs.edit()
                        .putFloat("ab_score_a", score)
                        .putBoolean("ab_done_a", true)
                        .putInt("ab_variant", 1)
                        .putLong("ab_window_start", now)
                        .putLong("ab_sum_bits", Double.doubleToLongBits(0.0))
                        .putInt("ab_samples", 0)
                        .putString("ab_state", "A/B testando perfil B")
                        .apply();
                return 1;
            } else {
                prefs.edit()
                        .putFloat("ab_score_b", score)
                        .putBoolean("ab_done_b", true)
                        .putLong("ab_window_start", now)
                        .putLong("ab_sum_bits", Double.doubleToLongBits(0.0))
                        .putInt("ab_samples", 0)
                        .apply();
                float a = prefs.getFloat("ab_score_a", Float.MAX_VALUE);
                int selected = a <= score ? 0 : 1;
                prefs.edit()
                        .putInt("ab_selected", selected)
                        .putBoolean("ab_complete", true)
                        .putString("ab_state", selected == 0 ?
                                "A/B selecionou perfil A" : "A/B selecionou perfil B")
                        .apply();
                return selected == 1 ? 1 : 0;
            }
        }

        if (prefs.getBoolean("ab_complete", false)) return selectedAbDelta();
        prefs.edit().putString("ab_state",
                variant == 0 ? "A/B testando perfil A" : "A/B testando perfil B").apply();
        return variant == 1 ? 1 : 0;
    }

    private int selectedAbDelta() {
        return prefs.getBoolean("ab_complete", false) && prefs.getInt("ab_selected", 0) == 1 ? 1 : 0;
    }

    private void loadCachedDiagnostics() {
        anrRecent = prefs.getInt("anr_recent", 0);
        ownAnrRecent = 0;
        binderSlowCalls = prefs.getInt("binder_slow_calls", 0);
        binderVerySlowCalls = prefs.getInt("binder_very_slow_calls", 0);
        binderTop = prefs.getString("binder_top", "");
        gpuP90Ms = prefs.getFloat("gpu_p90_ms", -1f);
        activeSensors = prefs.getInt("active_sensors", 0);
        openSensorConnections = prefs.getInt("open_sensor_connections", 0);
        activeLocationRequests = prefs.getInt("active_location_requests", 0);
        activeLocationPackages = prefs.getString("active_location_packages", "");
        cameraActive = prefs.getBoolean("camera_active_diag", false);
        micActive = prefs.getBoolean("mic_active_diag", false);
        signalRsrp = prefs.getInt("signal_rsrp", 0);
        signalLevel = prefs.getInt("signal_level", -1);
        radioType = prefs.getString("radio_type", "");
        diskWriteKb = prefs.getInt("disk_write_kb_s", -1);
        dataFreePct = prefs.getInt("data_free_pct_diag", -1);
        duplicateServices = prefs.getInt("duplicate_services", 0);
        duplicateNames = prefs.getString("duplicate_names", "");
        launchLogMs = prefs.getInt("launch_log_ms", -1);
        lastAnrPackage = prefs.getString("last_anr_package", "");
        memoryLeakSuspected = prefs.getBoolean("memory_leak_suspected", false);
        memoryLeakPkg = prefs.getString("memory_leak_pkg", "");
        memoryLeakLabel = prefs.getString("memory_leak_label", "");
        memoryLeakGrowthMb = prefs.getFloat("memory_leak_growth_mb", -1f);
        memoryLeakPssMb = prefs.getFloat("memory_leak_pss_mb", -1f);
        memoryLeakSpanMin = prefs.getFloat("memory_leak_span_min", -1f);
    }

    private void persistCachedDiagnostics() {
        prefs.edit()
                .putInt("anr_recent", anrRecent)
                .putInt("own_anr_recent", ownAnrRecent)
                .putString("last_anr_package", lastAnrPackage)
                .putInt("binder_slow_calls", binderSlowCalls)
                .putInt("binder_very_slow_calls", binderVerySlowCalls)
                .putString("binder_top", binderTop)
                .putFloat("gpu_p90_ms", gpuP90Ms)
                .putInt("active_sensors", activeSensors)
                .putInt("open_sensor_connections", openSensorConnections)
                .putInt("active_location_requests", activeLocationRequests)
                .putString("active_location_packages", activeLocationPackages)
                .putBoolean("camera_active_diag", cameraActive)
                .putBoolean("mic_active_diag", micActive)
                .putInt("signal_rsrp", signalRsrp)
                .putInt("signal_level", signalLevel)
                .putString("radio_type", radioType)
                .putInt("disk_write_kb_s", diskWriteKb)
                .putInt("data_free_pct_diag", dataFreePct)
                .putInt("duplicate_services", duplicateServices)
                .putString("duplicate_names", duplicateNames)
                .putInt("launch_log_ms", launchLogMs)
                .putBoolean("memory_leak_suspected", memoryLeakSuspected)
                .putString("memory_leak_pkg", memoryLeakPkg)
                .putString("memory_leak_label", memoryLeakLabel)
                .putFloat("memory_leak_growth_mb", memoryLeakGrowthMb)
                .putFloat("memory_leak_pss_mb", memoryLeakPssMb)
                .putFloat("memory_leak_span_min", memoryLeakSpanMin)
                .apply();
    }

    public String buildReport() {
        StringBuilder b = new StringBuilder();
        b.append("Adaptive Performance — diagnóstico\n");
        b.append("Gerado: ").append(new Date()).append("\n\n");
        b.append("Status: ").append(prefs.getString("status", "")).append("\n");
        b.append("Causa provável: ").append(prefs.getString("probable_cause", "")).append("\n");
        b.append("Perfil: ").append(prefs.getString("activity_profile_extended", "")).append("\n");
        b.append("Carregamento: ").append(prefs.getString("charger_profile", "")).append("\n");
        b.append("Previsão térmica 1/3 min: ")
                .append(prefs.getFloat("thermal_pred_1m", -1f)).append(" / ")
                .append(prefs.getFloat("thermal_pred_3m", -1f)).append(" °C\n");
        b.append("T/M/C/I/B/UI: ")
                .append(prefs.getInt("health_thermal",0)).append("/")
                .append(prefs.getInt("health_memory",0)).append("/")
                .append(prefs.getInt("health_cpu",0)).append("/")
                .append(prefs.getInt("health_io",0)).append("/")
                .append(prefs.getInt("health_battery",0)).append("/")
                .append(prefs.getInt("health_ui",0)).append("\n");
        b.append("Jank: ").append(prefs.getFloat("jank_pct",-1f)).append("%\n");
        b.append("LMKD delta: ").append(prefs.getInt("lmk_delta",0)).append("\n");
        b.append("zRAM swap MB: ").append(prefs.getFloat("zram_swap_mb",-1f)).append("\n");
        b.append("Binder lento/muito lento: ").append(binderSlowCalls).append("/")
                .append(binderVerySlowCalls).append(" top=").append(binderTop).append("\n");
        b.append("Sensores ativos/conexões: ").append(activeSensors).append("/")
                .append(openSensorConnections).append("\n");
        b.append("GPS ativos: ").append(activeLocationRequests).append(" ")
                .append(activeLocationPackages).append("\n");
        b.append("Câmera/mic: ").append(cameraActive).append("/").append(micActive).append("\n");
        b.append("Sinal: ").append(radioType).append(" RSRP=").append(signalRsrp)
                .append(" level=").append(signalLevel).append("\n");
        b.append("I/O: ").append(diskWriteKb).append(" kB/s; livre=")
                .append(dataFreePct).append("%\n");
        b.append("Serviços duplicados: ").append(duplicateServices).append(" ")
                .append(duplicateNames).append("\n");
        b.append("Deep sleep: ").append(prefs.getFloat("deep_sleep_pct",-1f)).append("%; Doze=")
                .append(prefs.getBoolean("device_idle_mode",false)).append("\n");
        b.append("Vazamento de memória: ").append(memoryLeakSuspected).append(" ")
                .append(memoryLeakLabel).append(" crescimento=")
                .append(memoryLeakGrowthMb).append(" MB PSS=")
                .append(memoryLeakPssMb).append(" MB\n");
        b.append("Brilho térmico: ").append(prefs.getBoolean("thermal_brightness_applied",false))
                .append(" ").append(prefs.getString("thermal_brightness_action","")).append("\n");
        b.append("Economia sistêmica: deepIdle=")
                .append(prefs.getBoolean("system_deep_idle_active",false))
                .append(" automação=").append(prefs.getBoolean("system_automation_active",false))
                .append(" ShizukuSuspenso=").append(prefs.getBoolean("shizuku_suspended_deep_idle",false))
                .append("\n");
        b.append("Wakelock persistente: ")
                .append(prefs.getString("persistent_wakelock_report","")).append("\n");
        b.append("Sync pendente: ")
                .append(prefs.getString("stuck_sync_report","")).append("\n");
        b.append("Sensor em segundo plano: ")
                .append(prefs.getString("background_sensor_report","")).append("\n");
        b.append("Sensores sistema ativos/abertos/mais rápido: ")
                .append(prefs.getInt("system_sensor_active_count",0)).append("/")
                .append(prefs.getInt("system_sensor_open_connections",0)).append("/")
                .append(prefs.getInt("system_sensor_fastest_ms",-1)).append(" ms\n");
        b.append("A/B: ").append(prefs.getString("ab_state","")).append("\n");
        b.append("Modo seguro: ").append(prefs.getBoolean("extended_safe_mode",false)).append("\n");
        b.append("Custo ciclo/saúde/estendido: ")
                .append(prefs.getLong("last_cycle_cost_ms",0)).append("/")
                .append(prefs.getLong("health_scan_cost_ms",0)).append("/")
                .append(prefs.getLong("extended_scan_cost_ms",0)).append(" ms\n");
        b.append("\nHistórico 24h (CSV)\n");
        b.append("timestamp,temp,cpu,ramLivre,potencia,pressao,thermal,T,M,C,I,B,UI\n");
        b.append(prefs.getString("history_24h_data", ""));
        return b.toString();
    }

    private String safePackage(String pkg) {
        if (pkg == null) return "";
        String p = pkg.trim();
        return p.matches("[A-Za-z0-9_.]+") ? p : "";
    }

    private String join(Collection<String> values, String sep) {
        StringBuilder b = new StringBuilder();
        for (String v : values) {
            if (v == null || v.isEmpty()) continue;
            if (b.length() > 0) b.append(sep);
            b.append(v);
        }
        return b.toString();
    }

    private float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

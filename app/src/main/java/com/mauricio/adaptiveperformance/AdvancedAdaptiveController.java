package com.mauricio.adaptiveperformance;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import java.text.DateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adaptive guard that combines thermal trend, PSI when available, memory,
 * battery power, storage state, foreground profile and low-frequency anomaly scans.
 * All interventions are reversible and limited to app UIDs; critical packages are excluded.
 */
public final class AdvancedAdaptiveController {
    private static final long MIN_THERMAL_HOLD_MS = 90_000L;
    private static final long MEMORY_RELIEF_COOLDOWN_MS = 12L * 60L * 1000L;
    private static final long MEMORY_COMPACTION_COOLDOWN_MS = 60_000L;
    private static final long SCREEN_OFF_SWEEP_MS = 20L * 60L * 1000L;
    private static final long ANOMALY_SCAN_MS = 30L * 60L * 1000L;
    private static final long STORAGE_NOTICE_MS = 6L * 60L * 60L * 1000L;

    public static final class Result {
        public int thermalTarget;
        public int pressureScore;
        public long nextSampleMs;
        public float tempTrendCPerMin;
        public float psiCpu;
        public float psiMemory;
        public float psiIo;
        public float powerW;
        public float storageFreePct;
        public boolean charging;
        public String profileHint = "";
        public String reason = "";
    }

    private static final class Psi {
        float cpu = -1f, memory = -1f, io = -1f;
        String source = "PSI";
    }

    private static final class VmSample {
        long at;
        long pgmajfault;
        long pgscanDirect;
        long pswpin;
        long pswpout;
        long pgpgin;
        long pgpgout;
        long nrDirty;
        long nrWriteback;
        long memTotalKb;
        long memAvailableKb;
        long swapTotalKb;
        long swapFreeKb;
    }

    private static final class Power {
        float watts = -1f;
        boolean charging;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final IPrivilegedService privileged;
    private final PackageManager pm;

    private float lastTemp = -1f;
    private long lastTempAt = 0L;
    private float trendEma = 0f;
    private int lastObservedThermal = -1;
    private long lastThermalChangeElapsed = 0L;
    private long lastMemoryReliefElapsed = 0L;
    private long lastMemoryCompactionElapsed = 0L;
    private boolean autoMemoryThermalPaused = false;
    private long lastScreenOffSweepElapsed = 0L;
    private long lastAnomalyScanElapsed = 0L;
    private long lastStorageNoticeElapsed = 0L;
    private long lastPressureReadElapsed = 0L;
    private boolean psiProbeDone = false;
    private boolean psiAvailable = false;
    private Psi cachedPressure = new Psi();
    private VmSample lastVmSample = null;

    public AdvancedAdaptiveController(Context context, SharedPreferences prefs, IPrivilegedService privileged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.privileged = privileged;
        this.pm = context.getPackageManager();
    }

    public Result evaluate(float controlTemp, float batteryTemp, double cpuLoad, double ramFreePct,
                           String foregroundPackage, boolean interactive, int currentThermalLevel,
                           boolean learnedHeavy) {
        Result r = new Result();
        long now = SystemClock.elapsedRealtime();
        boolean firstThermalObservation = lastObservedThermal < 0;

        if (currentThermalLevel != lastObservedThermal) {
            lastObservedThermal = currentThermalLevel;
            lastThermalChangeElapsed = now;
        }

        r.tempTrendCPerMin = updateTrend(controlTemp, now);
        Psi psi = readPressureSignals(now);
        r.psiCpu = psi.cpu;
        r.psiMemory = psi.memory;
        r.psiIo = psi.io;

        Power power = readPower();
        r.powerW = power.watts;
        r.charging = power.charging;
        r.storageFreePct = storageFreePct();

        r.pressureScore = pressureScore(cpuLoad, ramFreePct, controlTemp, r.tempTrendCPerMin,
                psi, power, r.storageFreePct);

        boolean advanced = prefs.getBoolean("advanced_adaptive", true);
        int rawTarget = advanced
                ? thermalTarget(controlTemp, batteryTemp, r.tempTrendCPerMin, power.charging)
                : legacyThermalTarget(controlTemp);
        r.thermalTarget = applyThermalHysteresis(rawTarget, currentThermalLevel, controlTemp, now,
                firstThermalObservation);

        r.profileHint = classifyForeground(foregroundPackage, learnedHeavy, interactive, power.charging,
                r.pressureScore, r.thermalTarget);
        r.reason = buildReason(r, psi);

        // Keep fast safety sampling for heat/pressure, but let Android sleep
        // between checks when the screen is off and the phone is cool.
        if (r.thermalTarget >= 4 || r.pressureScore >= 4) r.nextSampleMs = 8_000L;
        else if (r.thermalTarget > 0 || r.pressureScore >= 2 || r.tempTrendCPerMin >= 0.8f) r.nextSampleMs = 20_000L;
        else if (!interactive) r.nextSampleMs = power.charging ? 5L * 60_000L : 10L * 60_000L;
        else if (learnedHeavy || isRealtimeForeground(foregroundPackage)) r.nextSampleMs = 30_000L;
        else r.nextSampleMs = 60_000L;

        if (advanced) {
            maybeRelieveMemory(ramFreePct, psi.memory, foregroundPackage, now);
            maybeScreenOffSweep(interactive, foregroundPackage, now);
            maybeScanWakeupsAndNetwork(foregroundPackage, now);
            maybeNotifyStorage(r.storageFreePct, now);
        }
        maybeCompactMemory(ramFreePct, controlTemp, batteryTemp, now);

        prefs.edit()
                .putFloat("temp_trend_c_min", r.tempTrendCPerMin)
                .putFloat("psi_cpu", r.psiCpu)
                .putFloat("psi_memory", r.psiMemory)
                .putFloat("psi_io", r.psiIo)
                .putString("pressure_signal_source", psi.source)
                .putBoolean("psi_available", psiAvailable)
                .putFloat("power_w", r.powerW)
                .putFloat("storage_free_pct", r.storageFreePct)
                .putBoolean("charging_now", r.charging)
                .putInt("adaptive_pressure_score", r.pressureScore)
                .putString("adaptive_profile", r.profileHint)
                .putString("adaptive_reason", r.reason)
                .putLong("adaptive_next_sample_ms", r.nextSampleMs)
                .apply();
        return r;
    }

    private float updateTrend(float temp, long now) {
        if (temp <= 0f) return trendEma;
        if (lastTemp > 0f && lastTempAt > 0L) {
            long dt = now - lastTempAt;
            if (dt >= 2_000L && dt <= 180_000L) {
                float perMin = (temp - lastTemp) * 60_000f / dt;
                perMin = Math.max(-8f, Math.min(8f, perMin));
                trendEma = trendEma * 0.72f + perMin * 0.28f;
            }
        }
        lastTemp = temp;
        lastTempAt = now;
        return trendEma;
    }

    private Psi readPressureSignals(long now) {
        // Pressure sources are intentionally sampled less often than the thermal loop.
        // This prevents the optimizer itself from becoming a source of wakeups/CPU load.
        if (lastPressureReadElapsed > 0L && now - lastPressureReadElapsed < 60_000L) {
            return cachedPressure;
        }
        lastPressureReadElapsed = now;

        if (!psiProbeDone) {
            psiProbeDone = true;
            try {
                String raw = privileged.exec(
                        "for x in cpu memory io; do echo __$x__; cat /proc/pressure/$x 2>/dev/null | head -1; done");
                Psi p = parseRealPsi(raw);
                psiAvailable = p.cpu >= 0f || p.memory >= 0f || p.io >= 0f;
                if (psiAvailable) {
                    p.source = "PSI";
                    cachedPressure = p;
                    prefs.edit().putBoolean("psi_available", true).putString("pressure_signal_source", "PSI").apply();
                    return cachedPressure;
                }
            } catch (Throwable ignored) {}
            prefs.edit().putBoolean("psi_available", false).putString("pressure_signal_source", "VM proxy").apply();
        }

        if (psiAvailable) {
            try {
                String raw = privileged.exec(
                        "for x in cpu memory io; do echo __$x__; cat /proc/pressure/$x 2>/dev/null | head -1; done");
                Psi p = parseRealPsi(raw);
                if (p.cpu >= 0f || p.memory >= 0f || p.io >= 0f) {
                    p.source = "PSI";
                    cachedPressure = p;
                    return cachedPressure;
                }
                psiAvailable = false;
            } catch (Throwable ignored) {
                psiAvailable = false;
            }
        }

        cachedPressure = readVmPressureProxy(now);
        return cachedPressure;
    }

    private Psi parseRealPsi(String raw) {
        Psi p = new Psi();
        if (raw == null) return p;
        p.cpu = parsePsiSection(raw, "__cpu__");
        p.memory = parsePsiSection(raw, "__memory__");
        p.io = parsePsiSection(raw, "__io__");
        return p;
    }

    private float parsePsiSection(String raw, String marker) {
        int a = raw.indexOf(marker);
        if (a < 0) return -1f;
        int b = raw.indexOf("__", a + marker.length());
        String section = b > a ? raw.substring(a + marker.length(), b) : raw.substring(a + marker.length());
        Matcher m = Pattern.compile("avg10=([0-9.]+)").matcher(section);
        if (!m.find()) return -1f;
        try { return Float.parseFloat(m.group(1)); } catch (Throwable t) { return -1f; }
    }

    private Psi readVmPressureProxy(long now) {
        Psi out = new Psi();
        out.source = "VM proxy";
        try {
            String raw = privileged.exec(
                    "echo __MEM__; " +
                    "grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo 2>/dev/null; " +
                    "echo __VM__; " +
                    "grep -E '^(nr_dirty|nr_writeback|pgpgin|pgpgout|pswpin|pswpout|pgmajfault|pgscan_direct) ' /proc/vmstat 2>/dev/null");
            VmSample cur = parseVmSample(raw, now);
            if (cur == null) return out;

            float availPct = cur.memTotalKb > 0 ? 100f * cur.memAvailableKb / cur.memTotalKb : -1f;
            float swapUsedPct = cur.swapTotalKb > 0
                    ? 100f * (cur.swapTotalKb - cur.swapFreeKb) / cur.swapTotalKb : 0f;

            float memScore = 0f;
            if (availPct >= 0f) {
                if (availPct < 5f) memScore += 55f;
                else if (availPct < 8f) memScore += 40f;
                else if (availPct < 12f) memScore += 25f;
                else if (availPct < 18f) memScore += 10f;
            }
            if (swapUsedPct > 75f) memScore += 12f;
            else if (swapUsedPct > 55f) memScore += 6f;

            float ioScore = 0f;
            if (cur.nrWriteback > 4000) ioScore += 40f;
            else if (cur.nrWriteback > 1000) ioScore += 20f;
            if (cur.nrDirty > 12000) ioScore += 30f;
            else if (cur.nrDirty > 4000) ioScore += 12f;

            if (lastVmSample != null && cur.at > lastVmSample.at) {
                float sec = Math.max(1f, (cur.at - lastVmSample.at) / 1000f);
                float majorRate = delta(cur.pgmajfault, lastVmSample.pgmajfault) / sec;
                float scanRate = delta(cur.pgscanDirect, lastVmSample.pgscanDirect) / sec;
                float swapRate = (delta(cur.pswpin, lastVmSample.pswpin) +
                        delta(cur.pswpout, lastVmSample.pswpout)) / sec;
                float readRate = delta(cur.pgpgin, lastVmSample.pgpgin) / sec;
                float writeRate = delta(cur.pgpgout, lastVmSample.pgpgout) / sec;

                if (scanRate > 1500f) memScore += 35f;
                else if (scanRate > 400f) memScore += 20f;
                else if (scanRate > 80f) memScore += 8f;

                // Android with zRAM can sustain hundreds of major faults/s and swap pages/s
                // without being under real memory pressure. Keep these thresholds conservative.
                if (majorRate > 1500f) memScore += 25f;
                else if (majorRate > 600f) memScore += 12f;
                else if (majorRate > 250f) memScore += 5f;

                if (swapRate > 6000f) memScore += 20f;
                else if (swapRate > 2000f) memScore += 8f;

                if (writeRate > 120000f || readRate > 180000f) ioScore += 35f;
                else if (writeRate > 40000f || readRate > 60000f) ioScore += 18f;
                else if (writeRate > 10000f || readRate > 15000f) ioScore += 6f;
            }

            lastVmSample = cur;
            out.cpu = -1f; // Total CPU pressure is already measured separately from /proc/stat.
            out.memory = Math.min(100f, memScore);
            out.io = Math.min(100f, ioScore);
        } catch (Throwable ignored) {}
        return out;
    }

    private VmSample parseVmSample(String raw, long now) {
        if (raw == null || raw.isEmpty()) return null;
        VmSample v = new VmSample();
        v.at = now;
        for (String line : raw.split("\n")) {
            String x = line.trim();
            if (x.isEmpty() || x.startsWith("__")) continue;
            String[] parts = x.split("\s+");
            if (parts.length < 2) continue;
            String key = parts[0].replace(":", "");
            long value;
            try { value = Long.parseLong(parts[1]); } catch (Throwable t) { continue; }
            switch (key) {
                case "MemTotal": v.memTotalKb = value; break;
                case "MemAvailable": v.memAvailableKb = value; break;
                case "SwapTotal": v.swapTotalKb = value; break;
                case "SwapFree": v.swapFreeKb = value; break;
                case "nr_dirty": v.nrDirty = value; break;
                case "nr_writeback": v.nrWriteback = value; break;
                case "pgpgin": v.pgpgin = value; break;
                case "pgpgout": v.pgpgout = value; break;
                case "pswpin": v.pswpin = value; break;
                case "pswpout": v.pswpout = value; break;
                case "pgmajfault": v.pgmajfault = value; break;
                case "pgscan_direct": v.pgscanDirect = value; break;
            }
        }
        return v.memTotalKb > 0 ? v : null;
    }

    private long delta(long cur, long old) {
        return cur >= old ? cur - old : 0L;
    }

    private Power readPower() {
        Power p = new Power();
        try {
            Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int voltageMv = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            int status = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            int plugged = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            p.charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;

            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            int microA = bm == null ? Integer.MIN_VALUE
                    : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (microA != Integer.MIN_VALUE && microA != 0 && voltageMv > 0) {
                float amps = Math.abs(microA) / 1_000_000f;
                float volts = voltageMv / 1000f;
                p.watts = amps * volts;
                if (p.watts > 80f) p.watts = -1f;
            }
        } catch (Throwable ignored) {}
        return p;
    }

    private float storageFreePct() {
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long total = sf.getTotalBytes();
            long free = sf.getAvailableBytes();
            return total > 0 ? 100f * free / total : -1f;
        } catch (Throwable t) { return -1f; }
    }

    private int pressureScore(double cpu, double ram, float temp, float trend, Psi psi, Power power, float storage) {
        int s = 0;
        if (cpu >= 88) s += 2; else if (cpu >= 70) s += 1;
        if (ram > 0 && ram < 6) s += 2; else if (ram > 0 && ram < 12) s += 1;
        if (psi.memory >= 20) s += 2; else if (psi.memory >= 5) s += 1;
        if (psi.io >= 20) s += 2; else if (psi.io >= 7) s += 1;
        if (psi.cpu >= 65) s += 1;
        if (temp >= 54f && trend >= 1.2f) s += 1;
        if (power.watts >= 7.0f && (!power.charging || temp >= 56f)) s += 1;
        if (storage >= 0 && storage < 6f) s += 1;
        return Math.min(5, s);
    }

    private int thermalTarget(float temp, float batteryTemp, float trend, boolean charging) {
        int target = 0;
        if (temp >= 66f) target = 5;
        else if (temp >= 64f || (temp >= 62f && trend >= 1.5f)) target = 4;
        else if (temp >= 62f || (temp >= 60f && trend >= 1.2f)) target = 3;
        else if (temp >= 59f || (temp >= 57f && trend >= 1.0f)) target = 2;
        else if (temp >= 56f || (temp >= 54f && trend >= 0.8f)) target = 1;

        if (charging && batteryTemp >= 41f) target = Math.max(target, 3);
        else if (charging && batteryTemp >= 39f) target = Math.max(target, 2);
        return target;
    }

    private int legacyThermalTarget(float temp) {
        if (temp >= 66f) return 5;
        if (temp >= 56f) return 1;
        return 0;
    }

    private int applyThermalHysteresis(int requested, int current, float temp, long now,
                                       boolean firstObservation) {
        if (firstObservation) {
            // Um override persistido não deve prolongar um estado térmico antigo
            // quando a primeira leitura real já voltou a uma faixa segura.
            return requested;
        }
        if (requested >= current) return requested;
        if (current <= 0) return requested;
        if (now - lastThermalChangeElapsed < MIN_THERMAL_HOLD_MS) return current;

        float release;
        switch (current) {
            case 5: release = 62f; break;
            case 4: release = 60.5f; break;
            case 3: release = 58.5f; break;
            case 2: release = 55.5f; break;
            default: release = 52f; break;
        }
        if (temp > release) return current;
        return requested;
    }

    private String classifyForeground(String pkg, boolean learnedHeavy, boolean interactive,
                                      boolean charging, int pressure, int thermal) {
        if (!interactive) return "Repouso inteligente";
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        if (thermal >= 4) return "Resfriamento intensivo";
        if (pressure >= 4) return "Estabilidade prioritária";
        if (p.contains("camera") || p.contains("vibes") || p.contains("tiktok") ||
                p.contains("musically") || p.contains("instagram") || p.contains("creator"))
            return "Câmera/mídia adaptativa";
        if (p.contains("waze") || p.contains("maps") || p.contains("maposcope") || p.contains("spoke"))
            return "Navegação adaptativa";
        if (p.contains("game") || p.contains("gaming") || learnedHeavy)
            return "Carga pesada adaptativa";
        if (charging) return "Carregamento protegido";
        return "Equilibrado preditivo";
    }

    private boolean isRealtimeForeground(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.US);
        return p.contains("camera") || p.contains("vibes") || p.contains("musically") ||
                p.contains("tiktok") || p.contains("waze") || p.contains("maps") || p.contains("game");
    }

    private String buildReason(Result r, Psi psi) {
        List<String> reasons = new ArrayList<>();
        String source = "PSI".equals(psi.source) ? "PSI" : "VM";
        if (r.tempTrendCPerMin >= 0.8f) reasons.add(String.format(Locale.US, "temp +%.1f°C/min", r.tempTrendCPerMin));
        if (r.psiMemory >= 5) reasons.add(String.format(Locale.US, "%s RAM %.1f", source, r.psiMemory));
        if (r.psiIo >= 7) reasons.add(String.format(Locale.US, "%s I/O %.1f", source, r.psiIo));
        if (r.powerW >= 7) reasons.add(String.format(Locale.US, "%.1f W", r.powerW));
        if (r.storageFreePct >= 0 && r.storageFreePct < 8) reasons.add(String.format(Locale.US, "armazenamento %.0f%%", r.storageFreePct));
        return reasons.isEmpty() ? "sem pressão relevante" : join(reasons, " • ");
    }

    private String join(List<String> values, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(v);
        }
        return sb.toString();
    }

    private void maybeCompactMemory(double ramFreePct, float controlTemp, float batteryTemp, long now) {
        if (!prefs.getBoolean("memory_compaction_enabled", true)) return;
        if (prefs.getBoolean("diagnostic_only", false)) return;
        if (ramFreePct <= 0) return;

        int threshold = prefs.getInt("memory_compaction_threshold_pct", 50);
        threshold = Math.max(50, Math.min(95, threshold));

        String zramProfile = prefs.getString("zram_profile", "normal");
        String effectiveProfile = zramProfile;
        long compactionCooldown;

        if ("auto".equals(zramProfile)) {
            // No automático o ciclo é fixo em 30→50% e protegido pela temperatura.
            threshold = 50;
            float hottest = Math.max(controlTemp, batteryTemp);

            if (autoMemoryThermalPaused) {
                if (hottest <= 36.0f) {
                    autoMemoryThermalPaused = false;
                } else {
                    prefs.edit()
                            .putBoolean("auto_memory_thermal_paused", true)
                            .putString("auto_memory_effective_profile", "pausado")
                            .putFloat("auto_memory_temperature_c", hottest)
                            .apply();
                    return;
                }
            }
            if (hottest >= 40.0f) {
                autoMemoryThermalPaused = true;
                prefs.edit()
                        .putBoolean("auto_memory_thermal_paused", true)
                        .putString("auto_memory_effective_profile", "pausado")
                        .putFloat("auto_memory_temperature_c", hottest)
                        .apply();
                return;
            }

            // RAM muito baixa = máxima compactação; conforme recupera RAM, reduz a agressividade.
            if (ramFreePct <= 30.0) effectiveProfile = "extreme";
            else if (ramFreePct <= 40.0) effectiveProfile = "maximum";
            else if (ramFreePct <= 50.0) effectiveProfile = "normal";
            else return;

            // Ao esquentar, desce um nível antes de precisar pausar completamente.
            if (hottest >= 38.0f) {
                if ("extreme".equals(effectiveProfile)) effectiveProfile = "maximum";
                else if ("maximum".equals(effectiveProfile)) effectiveProfile = "normal";
                else return;
            }
            compactionCooldown = "extreme".equals(effectiveProfile) ? 30_000L
                    : ("maximum".equals(effectiveProfile) ? 45_000L : MEMORY_COMPACTION_COOLDOWN_MS);
            prefs.edit()
                    .putBoolean("auto_memory_thermal_paused", false)
                    .putString("auto_memory_effective_profile", effectiveProfile)
                    .putFloat("auto_memory_temperature_c", hottest)
                    .apply();
        } else {
            compactionCooldown = "extreme".equals(zramProfile) ? 30_000L
                    : ("maximum".equals(zramProfile) ? 45_000L : MEMORY_COMPACTION_COOLDOWN_MS);
        }

        if (ramFreePct > threshold) return;
        if (now - lastMemoryCompactionElapsed < compactionCooldown) return;

        lastMemoryCompactionElapsed = now;
        try {
            String output = privileged.exec("cmd activity compact system 2>&1");
            String normalized = output == null ? "" : output.replaceAll("\s+", " ").trim();
            if (normalized.length() > 240) normalized = normalized.substring(0, 240);
            boolean ok = normalized.contains("Finished system compaction")
                    && !normalized.toLowerCase(Locale.US).contains("error");
            String result = ok ? "ok" : (normalized.isEmpty() ? "sem retorno" : normalized);

            prefs.edit()
                    .putInt("memory_compaction_threshold_pct", threshold)
                    .putFloat("memory_compaction_last_free_pct", (float) ramFreePct)
                    .putLong("memory_compaction_last_at", System.currentTimeMillis())
                    .putInt("memory_compaction_count",
                            prefs.getInt("memory_compaction_count", 0) + (ok ? 1 : 0))
                    .putString("memory_compaction_last_result", result)
                    .apply();

            String msg = String.format(Locale.US,
                    "Compactação de RAM [%s] em %d%%: %s (RAM livre %.1f%%)",
                    effectiveProfile, threshold, ok ? "concluída" : "falhou", ramFreePct);
            log(msg);
            if (ok) {
                ChangeNotifier.notifyChange(context, "Compactação de RAM", msg, 4);
            } else {
                ChangeNotifier.notifyUnresolved(context, "Compactação de RAM", msg, 4);
            }
        } catch (Throwable t) {
            prefs.edit()
                    .putLong("memory_compaction_last_at", System.currentTimeMillis())
                    .putString("memory_compaction_last_result",
                            t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()))
                    .apply();
            log("Compactação de RAM falhou: " + t.getClass().getSimpleName());
        }
    }

    private void maybeRelieveMemory(double ramFreePct, float memoryPsi, String foreground, long now) {
        if (!AdaptiveIntelligenceController.mutationAllowed(prefs)) return;
        if (!prefs.getBoolean("critical_cleanup", true)) return;
        boolean aggressive = prefs.getBoolean("aggressive_memory_cleanup", false);
        String zramProfile = prefs.getString("zram_profile", "normal");
        double moderateThreshold;
        double severeThreshold;
        if ("auto".equals(zramProfile)) {
            moderateThreshold = 30.0;
            severeThreshold = 15.0;
        } else if ("extreme".equals(zramProfile)) {
            moderateThreshold = 30.0;
            severeThreshold = 15.0;
        } else if ("maximum".equals(zramProfile) || aggressive) {
            moderateThreshold = 20.0;
            severeThreshold = 10.0;
        } else {
            moderateThreshold = 10.0;
            severeThreshold = 6.0;
        }
        boolean severe = (ramFreePct > 0 && ramFreePct < severeThreshold) || memoryPsi >= 18f;
        boolean moderate = (ramFreePct > 0 && ramFreePct < moderateThreshold) || memoryPsi >= 8f;
        if (!moderate || now - lastMemoryReliefElapsed < MEMORY_RELIEF_COOLDOWN_MS) return;
        lastMemoryReliefElapsed = now;
        try {
            int idled = idleBackgroundPackages(foreground, severe ? 18 : 10);
            if (severe) privileged.exec("am kill-all");
            String msg = severe
                    ? "Pressão crítica de RAM: " + idled + " app(s) colocados em idle e cache secundário liberado."
                    : "Pressão de RAM: " + idled + " app(s) de segundo plano colocados em idle.";
            log(msg);
            ChangeNotifier.notifyChange(context, "Proteção de memória", msg, 4);
        } catch (Throwable ignored) {}
    }

    private void maybeScreenOffSweep(boolean interactive, String foreground, long now) {
        if (!AdaptiveIntelligenceController.mutationAllowed(prefs)) return;
        if (interactive || !prefs.getBoolean("screen_off_optimization", true)) return;
        if (now - lastScreenOffSweepElapsed < SCREEN_OFF_SWEEP_MS) return;
        lastScreenOffSweepElapsed = now;
        try {
            int n = idleBackgroundPackages(foreground, 24);
            if (n > 0) log("Tela desligada: " + n + " app(s) de segundo plano movidos para idle.");
        } catch (Throwable ignored) {}
    }

    private int idleBackgroundPackages(String foreground, int max) {
        int count = 0;
        try {
            Set<String> fgs = activeForegroundServicePackages();
            String ps = privileged.exec("ps -A -o NAME 2>/dev/null");
            if (ps == null) return 0;
            Set<String> seen = new LinkedHashSet<>();
            for (String line : ps.split("\n")) {
                String proc = line.trim();
                if (proc.isEmpty() || "NAME".equals(proc)) continue;
                String pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
                if (!pkg.matches("[A-Za-z0-9_.]+")) continue;
                seen.add(pkg);
            }
            for (String pkg : seen) {
                if (count >= max) break;
                if (pkg.equals(foreground) || fgs.contains(pkg)) continue;
                if (!AppSafety.isEligibleForAutomaticRestriction(context, pkg)) continue;
                privileged.exec("cmd activity make-uid-idle --user 0 " + pkg);
                count++;
            }
        } catch (Throwable ignored) {}
        return count;
    }

    private Set<String> activeForegroundServicePackages() {
        Set<String> out = new HashSet<>();
        try {
            String raw = privileged.exec("dumpsys activity services 2>/dev/null | " +
                    "grep -B30 'isForeground=true' | grep 'packageName=' | " +
                    "sed 's/.*packageName=//' | awk '{print $1}' | sort -u");
            if (raw != null) for (String line : raw.split("\n")) {
                String p = line.trim();
                if (p.matches("[A-Za-z0-9_.]+")) out.add(p);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void maybeScanWakeupsAndNetwork(String foreground, long now) {
        if (!prefs.getBoolean("wakeup_network_guard", true)) return;
        if (now - lastAnomalyScanElapsed < ANOMALY_SCAN_MS) return;
        lastAnomalyScanElapsed = now;
        try {
            scanAlarmWakeups(foreground, now);
            scanNetworkOpenSessions(foreground, now);
            scanHeldWakeLocks();
        } catch (Throwable t) {
            prefs.edit().putString("advanced_scan_error", t.getClass().getSimpleName()).apply();
        }
    }

    private void scanAlarmWakeups(String foreground, long now) {
        try {
            String raw = privileged.exec("dumpsys alarm 2>/dev/null | grep -E '[0-9]+ wakeups,.*alarms:' | head -120");
            Pattern pat = Pattern.compile("([0-9]+) wakeups,.*alarms:\\s+(?:u[0-9]+a[0-9]+|[0-9]+):([A-Za-z0-9_.]+)");
            Matcher m = pat.matcher(raw == null ? "" : raw);
            Map<String,Long> current = new HashMap<>();
            while (m.find()) {
                long c;
                try { c = Long.parseLong(m.group(1)); } catch (Throwable t) { continue; }
                String pkg = m.group(2);
                current.put(pkg, Math.max(c, current.getOrDefault(pkg, 0L)));
            }
            String worst = ""; long worstDelta = 0;
            for (Map.Entry<String,Long> e : current.entrySet()) {
                String h = hash(e.getKey());
                String key = "wake_count_" + h;
                long old = prefs.getLong(key, e.getValue());
                long delta = Math.max(0, e.getValue() - old);

                float oldEma = prefs.getFloat("wake_ema_" + h, -1f);
                float baseline = oldEma < 0f ? 0f : oldEma;
                long threshold = Math.max(180L, Math.round(baseline * 2.5f));
                boolean anomaly = delta >= threshold && delta >= 180L;
                int streak = anomaly ? prefs.getInt("wake_streak_" + h, 0) + 1 : 0;
                float newEma = oldEma < 0f ? delta : oldEma * 0.80f + delta * 0.20f;

                prefs.edit()
                        .putLong(key, e.getValue())
                        .putFloat("wake_ema_" + h, newEma)
                        .putInt("wake_streak_" + h, Math.min(5, streak))
                        .apply();

                if (delta > worstDelta) { worstDelta = delta; worst = e.getKey(); }
                if (streak >= 2 && safeToIdle(e.getKey(), foreground)) {
                    privileged.exec("cmd activity make-uid-idle --user 0 " + e.getKey());
                    log("Wakeups acima do padrão aprendido: " + AppSafety.label(context, e.getKey()) +
                            " (" + delta + " no intervalo; limite " + threshold + "); app movido para idle.");
                }
            }
            if (!worst.isEmpty())
                prefs.edit().putString("wake_report", AppSafety.label(context, worst) +
                        " • +" + worstDelta + " wakeups/intervalo").putLong("wake_scan_time", now).apply();
        } catch (Throwable ignored) {}
    }

    private void scanNetworkOpenSessions(String foreground, long now) {
        try {
            String raw = privileged.exec("dumpsys netstats detail 2>/dev/null | " +
                    "sed -n '/Top openSession callers:/,/Poll counts per reason:/p' | head -80");
            Pattern pat = Pattern.compile("\\{uid=[0-9]+,package=([A-Za-z0-9_.]+)\\}=([0-9]+)");
            Matcher m = pat.matcher(raw == null ? "" : raw);
            String worst = ""; long worstDelta = 0;
            while (m.find()) {
                String pkg = m.group(1);
                long cur;
                try { cur = Long.parseLong(m.group(2)); } catch (Throwable t) { continue; }
                String h = hash(pkg);
                String key = "net_open_" + h;
                long old = prefs.getLong(key, cur);
                long delta = Math.max(0, cur - old);

                float oldEma = prefs.getFloat("net_ema_" + h, -1f);
                float baseline = oldEma < 0f ? 0f : oldEma;
                long threshold = Math.max(800L, Math.round(baseline * 2.5f));
                boolean anomaly = delta >= threshold && delta >= 800L;
                int streak = anomaly ? prefs.getInt("net_streak_" + h, 0) + 1 : 0;
                float newEma = oldEma < 0f ? delta : oldEma * 0.80f + delta * 0.20f;

                prefs.edit()
                        .putLong(key, cur)
                        .putFloat("net_ema_" + h, newEma)
                        .putInt("net_streak_" + h, Math.min(5, streak))
                        .apply();

                if (delta > worstDelta) { worstDelta = delta; worst = pkg; }
                if (streak >= 2 && safeToIdle(pkg, foreground)) {
                    privileged.exec("cmd activity make-uid-idle --user 0 " + pkg);
                    log("Rede acima do padrão aprendido: " + AppSafety.label(context, pkg) +
                            " abriu " + delta + " sessões (limite " + threshold + "); app movido para idle.");
                }
            }
            if (!worst.isEmpty())
                prefs.edit().putString("network_report", AppSafety.label(context, worst) +
                        " • +" + worstDelta + " sessões/intervalo").putLong("network_scan_time", now).apply();
        } catch (Throwable ignored) {}
    }

    private void scanHeldWakeLocks() {
        try {
            String raw = privileged.exec("dumpsys power 2>/dev/null | sed -n '/Wake Locks: size=/,/Suspend Blockers: size=/p' | head -60");
            int held = 0;
            if (raw != null) {
                Matcher m = Pattern.compile("PARTIAL_WAKE_LOCK|FULL_WAKE_LOCK|SCREEN_BRIGHT_WAKE_LOCK").matcher(raw);
                while (m.find()) held++;
            }
            prefs.edit().putInt("held_wakelocks", held).apply();
        } catch (Throwable ignored) {}
    }

    private boolean safeToIdle(String pkg, String foreground) {
        if (!AdaptiveIntelligenceController.mutationAllowed(prefs)) return false;
        if (pkg == null || pkg.equals(foreground)) return false;
        return AppSafety.isEligibleForAutomaticRestriction(context, pkg);
    }

    private void maybeNotifyStorage(float pct, long now) {
        if (pct < 0 || pct >= 8f || now - lastStorageNoticeElapsed < STORAGE_NOTICE_MS) return;
        lastStorageNoticeElapsed = now;
        String msg = String.format(Locale.US,
                "Armazenamento livre em %.1f%%. Operações pesadas simultâneas podem aumentar I/O, aquecimento e travamentos.", pct);
        log(msg);
        ChangeNotifier.notifyUnresolved(context, "Armazenamento baixo", msg, 5);
    }

    private String hash(String s) { return Integer.toHexString(s.hashCode()); }

    private void log(String message) {
        String stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        String entry = stamp + " — " + message;
        String old = prefs.getString("maintenance_log", "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder sb = new StringBuilder(entry);
        for (int i = 0; i < Math.min(9, lines.length); i++) sb.append('\n').append(lines[i]);
        prefs.edit().putString("maintenance_log", sb.toString()).apply();
    }
}

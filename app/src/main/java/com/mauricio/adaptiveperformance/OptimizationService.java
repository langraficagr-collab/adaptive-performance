package com.mauricio.adaptiveperformance;

import android.app.*;
import android.content.*;
import android.os.*;
import android.content.pm.PackageManager;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

public class OptimizationService extends Service {
    private static ExecutorService idleSingleExecutor(String name) {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "AP-" + name);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        ThreadPoolExecutor ex = new ThreadPoolExecutor(
                0, 1, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), factory);
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    public static final String CHANNEL_ID = "adaptive_performance";
    public static final String ACTION_SET_THERMAL_MODE = "com.mauricio.adaptiveperformance.SET_THERMAL_MODE";
    public static final String ACTION_APPLY_CAUSE_FIX = "com.mauricio.adaptiveperformance.APPLY_CAUSE_FIX";
    public static final String ACTION_START_OPTIMIZATION = "com.mauricio.adaptiveperformance.START_OPTIMIZATION";
    public static final String ACTION_STOP_OPTIMIZATION = "com.mauricio.adaptiveperformance.STOP_OPTIMIZATION";
    public static final String ACTION_STORAGE_SCAN = "com.mauricio.adaptiveperformance.STORAGE_SCAN";
    public static final String ACTION_STORAGE_CLEAN = "com.mauricio.adaptiveperformance.STORAGE_CLEAN";
    public static final String ACTION_STORAGE_LARGE_SCAN = "com.mauricio.adaptiveperformance.STORAGE_LARGE_SCAN";
    public static final String ACTION_STORAGE_DELETE_LARGE = "com.mauricio.adaptiveperformance.STORAGE_DELETE_LARGE";
    public static final String ACTION_STORAGE_TRIM = "com.mauricio.adaptiveperformance.STORAGE_TRIM";
    public static final String ACTION_STORAGE_TRIM_ABORT = "com.mauricio.adaptiveperformance.STORAGE_TRIM_ABORT";
    private static final float SOC_LEVEL1_C = 56.0f;
    private static final float SOC_MAX_C = 66.0f;
    private static final float SOC_LEVEL1_RELEASE_C = 52.0f;
    private static final float SOC_MAX_RELEASE_C = 62.0f;

    private static class ThermalSnapshot {
        float cpu = -1f, gpu = -1f, soc = -1f, skin = -1f, battery = -1f;
        boolean hasSoc() { return soc > 0f; }
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService sampleExecutor = idleSingleExecutor("sample");
    private final ExecutorService cleanupExecutor = idleSingleExecutor("cleanup");
    private final AtomicBoolean sampleBusy = new AtomicBoolean(false);
    private volatile boolean serviceDestroyed = false;
    private SharedPreferences prefs;
    private IPrivilegedService privileged;
    private Shizuku.UserServiceArgs userServiceArgs;
    private long lastCpuTotal = -1, lastCpuIdle = -1;
    private long lastCleanup = 0, lastRefreshChange = 0;
    private long lastThermalReadElapsed = 0L;
    private ThermalSnapshot cachedThermals = new ThermalSnapshot();
    private boolean lowRefreshApplied = false;
    private volatile int thermalLevelApplied = 0;
    private boolean bindingInProgress = false;
    private long lastShizukuBindAttemptElapsed = 0L;
    private BackgroundMaintenance maintenance;
    private volatile boolean startupReady;
    private CpuPressureController cpuPressureController;
    private ManualFreezeManager manualFreezeManager;
    private AdvancedAdaptiveController advancedController;
    private SystemHealthController healthController;
    private ExtendedDiagnosticsController extendedController;
    private DeepSleepController deepSleepController;
    private ThermalBrightnessController thermalBrightnessController;
    private SystemBatteryController systemBatteryController;
    private volatile long nextLoopDelayMs = 12000L;
    private final ExecutorService maintenanceExecutor = idleSingleExecutor("maintenance");
    private final AtomicBoolean maintenanceBusy = new AtomicBoolean(false);
    private final ExecutorService cpuPressureExecutor = idleSingleExecutor("cpu");
    private final AtomicBoolean cpuPressureBusy = new AtomicBoolean(false);
    private final ExecutorService manualFreezeExecutor = idleSingleExecutor("freeze");
    private final AtomicBoolean manualFreezeBusy = new AtomicBoolean(false);
    private final ExecutorService healthExecutor = idleSingleExecutor("health");
    private final AtomicBoolean healthBusy = new AtomicBoolean(false);
    private final ExecutorService extendedExecutor = idleSingleExecutor("extended");
    private final AtomicBoolean extendedBusy = new AtomicBoolean(false);
    private final ExecutorService brightnessExecutor = idleSingleExecutor("brightness");
    private final AtomicBoolean brightnessBusy = new AtomicBoolean(false);
    private final ExecutorService systemBatteryExecutor = idleSingleExecutor("battery");
    private final AtomicBoolean systemBatteryBusy = new AtomicBoolean(false);

    private final BroadcastReceiver powerStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            boolean wake = Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_POWER_CONNECTED.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action);
            if (wake) {
                prefs.edit()
                        .putBoolean("system_deep_idle_active", false)
                        .putBoolean("shizuku_suspended_deep_idle", false)
                        .apply();
                nextLoopDelayMs = 3_000L;
                lastShizukuBindAttemptElapsed = 0L;
                bindShizukuIfPossible();
            }
            handler.removeCallbacks(loop);
            handler.post(loop);
        }
    };

    private volatile boolean recoveryReady = false;

    private final android.content.ServiceConnection connection = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            bindingInProgress = false;
            recoveryReady = false;
            startupReady = false;
            privileged = IPrivilegedService.Stub.asInterface(service);
            maintenance = new BackgroundMaintenance(OptimizationService.this, prefs, privileged);
            cpuPressureController = new CpuPressureController(OptimizationService.this, prefs, privileged);
            manualFreezeManager = new ManualFreezeManager(OptimizationService.this, prefs, privileged);
            advancedController = new AdvancedAdaptiveController(OptimizationService.this, prefs, privileged);
            healthController = new SystemHealthController(OptimizationService.this, prefs, privileged);
            extendedController = new ExtendedDiagnosticsController(OptimizationService.this, prefs, privileged);
            thermalBrightnessController = new ThermalBrightnessController(OptimizationService.this, prefs, privileged);
            systemBatteryController = new SystemBatteryController(OptimizationService.this, prefs, privileged);
            final SystemBatteryController initBattery = systemBatteryController;
            systemBatteryExecutor.execute(() -> {
                try { initBattery.auditWakeLocksNow(prefs.getString("foreground", "")); }
                catch (Throwable t) {
                    prefs.edit().putString("wakelock_startup_audit_error",
                            t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
                }
            });
            prefs.edit()
                    .putBoolean("shizuku_bound", true)
                    .putBoolean("shizuku_suspended_deep_idle", false)
                    .apply();

            final BackgroundMaintenance initMaintenance = maintenance;
            final CpuPressureController initCpu = cpuPressureController;
            final ManualFreezeManager initFreeze = manualFreezeManager;
            maintenanceExecutor.execute(() -> {
                try {
                    initFreeze.initialize();
                    initCpu.initialize();
                    initMaintenance.initialize();
                    restoreExpiredCauseInactivity();
                    captureRefreshBaseline();
                    StartupDiagnostics.record(prefs);
                } catch (Throwable t) {
                    prefs.edit().putString("startup_diagnostic", "Recuperação incompleta: " + t.getClass().getSimpleName()).apply();
                    IncidentHistory.add(prefs, "startup", "Recuperação", "Pendente; será tentada novamente", -1);
                } finally {
                    recoveryReady = true;
                    startupReady = true;
                    prefs.edit().putBoolean("startup_ready", true).apply();
                }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            recoveryReady = false;
            startupReady = false;
            prefs.edit().putBoolean("startup_ready", false).apply();
            bindingInProgress = false;
            maintenance = null;
            cpuPressureController = null;
            manualFreezeManager = null;
            advancedController = null;
            healthController = null;
            extendedController = null;
            thermalBrightnessController = null;
            systemBatteryController = null;
            privileged = null;
            prefs.edit().putBoolean("shizuku_bound", false).apply();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        serviceDestroyed = false;
        prefs = getSharedPreferences("adaptive", MODE_PRIVATE);
        thermalLevelApplied = prefs.getInt("thermal_level", prefs.getBoolean("thermal_stage1", false) ? 1 : 0);
        createChannel();
        ChangeNotifier.ensureChannel(this);
        CauseResolutionNotifier.ensureChannel(this);
        deepSleepController = new DeepSleepController(this, prefs);
        IntentFilter stateFilter = new IntentFilter();
        stateFilter.addAction(Intent.ACTION_SCREEN_ON);
        stateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        stateFilter.addAction(Intent.ACTION_USER_PRESENT);
        stateFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        stateFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerStateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(powerStateReceiver, stateFilter);
        startForeground(7014, buildNotification("Iniciando monitor adaptativo"));
        bindShizukuIfPossible();
        handler.post(loop);
    }

    private void bindShizukuIfPossible() {
        if (privileged != null || bindingInProgress) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastShizukuBindAttemptElapsed < 30_000L) return;
        lastShizukuBindAttemptElapsed = now;
        try {
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            bindingInProgress = true;
            userServiceArgs = new Shizuku.UserServiceArgs(
                    new ComponentName(this, PrivilegedService.class))
                    .processNameSuffix("optimizer")
                    .daemon(false);
            Shizuku.bindUserService(userServiceArgs, connection);
        } catch (Throwable ignored) {
            bindingInProgress = false;
        }
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (serviceDestroyed) return;
            boolean keep = prefs.getBoolean("master", true) || prefs.getBoolean("storage_cleanup_busy", false);
            if (!keep) { stopSelf(); return; }

            // Never overlap cycles. The active worker will schedule the next one.
            if (!sampleBusy.compareAndSet(false, true)) return;

            sampleExecutor.execute(() -> {
                try {
                    sampleAndOptimize();
                } catch (Throwable t) {
                    prefs.edit().putString("last_error",
                            t.getClass().getSimpleName()+": "+t.getMessage()).apply();
                } finally {
                    sampleBusy.set(false);
                    if (!serviceDestroyed && prefs.getBoolean("master", true)) {
                        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
                        long fallback = (pm != null && pm.isInteractive()) ? 15_000L : 300_000L;
                        long delay = nextLoopDelayMs >= 3_000L && nextLoopDelayMs <= 900_000L
                                ? nextLoopDelayMs : fallback;
                        handler.postDelayed(loop, delay);
                    }
                }
            });
        }
    };

    private void sampleAndOptimize() {
        final long cycleStarted = SystemClock.elapsedRealtime();
        ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double availPct = mi.totalMem > 0 ? (100.0 * mi.availMem / mi.totalMem) : 0;

        float tempC = batteryTemp();
        ThermalSnapshot thermals = new ThermalSnapshot();
        thermals.battery = tempC;
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        boolean interactive = pm != null && pm.isInteractive();
        boolean batteryChargingNow = isChargingNow();
        if (deepSleepController != null && prefs.getBoolean("deep_sleep_monitor", true)) {
            try { deepSleepController.update(interactive); } catch (Throwable ignored) {}
        }

        restoreExpiredCauseInactivity();
        AutoRepairController.evaluate(prefs, cpuPressureController);
        String fg = "";
        double cpuLoad = -1;
        long avgFreq = -1;

        if (privileged == null && !prefs.getBoolean("system_deep_idle_active", false)) {
            handler.post(this::bindShizukuIfPossible);
        }
        if (privileged != null) {
            try {
                long now = SystemClock.elapsedRealtime();
                boolean cachedHot = thermalLevelApplied > 0 || cachedThermals.soc >= SOC_LEVEL1_C
                        || tempC >= 38f;
                long thermalInterval = cachedHot ? 10_000L
                        : (batteryChargingNow ? 20_000L : (interactive ? 45_000L : 60_000L));
                boolean readThermal = lastThermalReadElapsed <= 0L
                        || now - lastThermalReadElapsed >= thermalInterval;

                String command =
                    "cat /proc/stat | head -1; echo __SEP__; " +
                    "for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq; do cat \"$f\" 2>/dev/null; done; " +
                    "echo __SEP__; dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity'; ";
                if (readThermal) {
                    command += "echo __THERM__; dumpsys thermalservice 2>/dev/null | grep 'Temperature{mValue='";
                }

                String snap = privileged.exec(command);
                String[] thermSplit = snap.split("__THERM__", 2);
                String[] parts = thermSplit[0].split("__SEP__");
                if (parts.length > 0) cpuLoad = parseCpu(parts[0].trim());
                if (parts.length > 1) avgFreq = parseFreq(parts[1]);
                if (parts.length > 2) fg = parseForeground(parts[2]);

                if (readThermal && thermSplit.length > 1) {
                    thermals = parseThermals(thermSplit[1], tempC);
                    cachedThermals = thermals;
                    lastThermalReadElapsed = now;
                } else {
                    thermals = cachedThermals;
                    thermals.battery = tempC;
                }
            } catch (Throwable ignored) {}
        }

        float controlTemp = thermals.hasSoc() ? thermals.soc : tempC;
        String profile;
        if (!interactive) profile = "Economia/repouso";
        else if (thermals.hasSoc() ? thermals.soc >= SOC_LEVEL1_C : tempC >= 38.0f) profile = "Proteção térmica";
        else if (isHeavyLearnedApp(fg) && controlTemp < SOC_LEVEL1_C) profile = "Desempenho adaptativo";
        else profile = "Equilibrado";

        String predictedPkg = AppProfilePolicy.predictedPackage(prefs);
        String predictedProfile = AppProfilePolicy.predictedProfile(this, prefs);
        boolean launcherNow = fg == null || fg.isEmpty() || fg.contains("launcher") || fg.contains("miui.home");
        if (interactive && launcherNow && !predictedPkg.isEmpty() && !"DEFAULT".equals(predictedProfile)) {
            profile = "Pré-perfil " + predictedProfile;
            nextLoopDelayMs = Math.min(nextLoopDelayMs, 15_000L);
            prefs.edit().putString("time_predicted_pkg", predictedPkg)
                    .putString("time_predicted_profile", predictedProfile).apply();
        }

        learnApp(fg, cpuLoad);

        AdvancedAdaptiveController.Result advanced = null;
        if (advancedController != null) {
            try {
                advanced = advancedController.evaluate(controlTemp, tempC, cpuLoad, availPct,
                        fg, interactive, thermalLevelApplied, isHeavyLearnedApp(fg));
                nextLoopDelayMs = advanced.nextSampleMs;
                if (advanced.profileHint != null && !advanced.profileHint.isEmpty()) profile = advanced.profileHint;
            } catch (Throwable t) {
                prefs.edit().putString("advanced_error", t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
            }
        }
        final boolean chargingNow = advanced != null ? advanced.charging : batteryChargingNow;
        SystemBatteryController.Result systemBattery = null;
        if (systemBatteryController != null) {
            try {
                systemBattery = systemBatteryController.evaluate(
                        interactive, chargingNow, thermals.soc, tempC, cpuLoad, fg);
                if (!interactive && systemBattery.suggestedLoopMs > 0L) {
                    nextLoopDelayMs = Math.max(nextLoopDelayMs, systemBattery.suggestedLoopMs);
                }
            } catch (Throwable t) {
                prefs.edit().putString("system_battery_error",
                        t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
            }
        }

        if (systemBatteryController != null && systemBatteryBusy.compareAndSet(false, true)) {
            final SystemBatteryController sbc = systemBatteryController;
            final boolean sbInteractive = interactive;
            final boolean sbCharging = chargingNow;
            final float sbSoc = thermals.soc;
            final float sbBattery = tempC;
            final double sbCpu = cpuLoad;
            final String sbFg = fg;
            systemBatteryExecutor.execute(() -> {
                try {
                    if (AdaptiveIntelligenceController.mutationAllowed(prefs))
                        sbc.maybeRunPolicies(sbInteractive, sbCharging, sbSoc, sbBattery, sbCpu, sbFg);
                    if (AdaptiveIntelligenceController.mutationAllowed(prefs) && sbc.shouldSuspendPrivileged()) {
                        handler.post(OptimizationService.this::suspendPrivilegedForDeepIdle);
                    }
                } catch (Throwable t) {
                    prefs.edit().putString("system_battery_worker_error",
                            t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
                } finally {
                    systemBatteryBusy.set(false);
                }
            });
        }

        SystemHealthController.Result health = null;
        if (healthController != null) {
            try {
                health = healthController.update(
                        fg, controlTemp, tempC, cpuLoad, availPct,
                        advanced != null ? advanced.powerW : -1f,
                        advanced != null ? advanced.pressureScore : 0,
                        advanced != null ? advanced.psiIo : -1f,
                        thermalLevelApplied, interactive);
                if (health.antiStallLevel > 0 && health.profile != null && !health.profile.isEmpty()) {
                    profile = health.profile;
                }
            } catch (Throwable t) {
                prefs.edit().putString("health_error",
                        t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
            }
        }

        if (healthController != null && healthBusy.compareAndSet(false, true)) {
            final SystemHealthController hc = healthController;
            final String healthFg = fg;
            final boolean healthInteractive = interactive;
            healthExecutor.execute(() -> {
                try { hc.maybeRunExpensive(healthFg, healthInteractive); }
                catch (Throwable ignored) {}
                finally { healthBusy.set(false); }
            });
        }

        ExtendedDiagnosticsController.Result extended = null;
        if (extendedController != null) {
            try {
                int ht = health != null ? health.thermalScore : prefs.getInt("health_thermal", 0);
                int hm = health != null ? health.memoryScore : prefs.getInt("health_memory", 0);
                int hc = health != null ? health.cpuScore : prefs.getInt("health_cpu", 0);
                int hi = health != null ? health.ioScore : prefs.getInt("health_io", 0);
                int hb = health != null ? health.batteryScore : prefs.getInt("health_battery", 0);
                int hu = health != null ? health.uiScore : prefs.getInt("health_ui", 0);
                int lmk = health != null ? health.lmkDelta : prefs.getInt("lmk_delta", 0);
                float jank = health != null ? health.jankPct : prefs.getFloat("jank_pct", -1f);
                extended = extendedController.update(
                        fg, controlTemp, tempC,
                        advanced != null ? advanced.tempTrendCPerMin : 0f,
                        cpuLoad, availPct,
                        advanced != null ? advanced.powerW : -1f,
                        thermalLevelApplied,
                        advanced != null ? advanced.pressureScore : 0,
                        ht, hm, hc, hi, hb, hu, lmk, jank, interactive);
                if (!extended.safeMode && health != null && health.antiStallLevel == 0 &&
                        extended.activityProfile != null && !"Uso leve".equals(extended.activityProfile)) {
                    profile = extended.activityProfile + " adaptativo";
                }
            } catch (Throwable t) {
                prefs.edit().putString("extended_error",
                        t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
            }
        }

        if (extendedController != null && extendedBusy.compareAndSet(false, true)) {
            final ExtendedDiagnosticsController ec = extendedController;
            final String extendedFg = fg;
            final boolean extendedInteractive = interactive;
            extendedExecutor.execute(() -> {
                try { ec.maybeRunDiagnostics(extendedFg, extendedInteractive); }
                catch (Throwable ignored) {}
                finally { extendedBusy.set(false); }
            });
        }

        final float socForPressure = thermals.soc;
        int effectivePressure = advanced == null ? 0 : advanced.pressureScore;
        int basePressure = effectivePressure;
        if (health != null && !health.rollbackRequested) {
            if (health.antiStallLevel >= 2) effectivePressure = Math.max(effectivePressure, 4);
            else if (health.antiStallLevel == 1) effectivePressure = Math.max(effectivePressure, 3);
        }
        if (extended != null && !extended.safeMode) {
            if (extended.adaptiveAggressiveness > 0)
                effectivePressure = Math.min(5, Math.max(effectivePressure, basePressure + 1));
            else if (extended.adaptiveAggressiveness < 0 && effectivePressure > basePressure)
                effectivePressure = Math.max(basePressure, effectivePressure - 1);
        }
        final int adaptivePressureScore = effectivePressure;
        int smartBatteryPct = -1;
        try {
            android.content.Intent bi = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                int bl = bi.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int bs = bi.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                if (bl >= 0 && bs > 0) smartBatteryPct = Math.round(bl * 100f / bs);
            }
        } catch (Throwable ignored) {}
        try {
            SmartRecommendationSuite.evaluate(this, prefs, privileged, fg, interactive,
                    smartBatteryPct, controlTemp, adaptivePressureScore);
        } catch (Throwable ignored) {}
        final boolean mutationsAllowed = AdaptiveIntelligenceController.mutationAllowed(prefs);

        if (mutationsAllowed && health != null && health.rollbackRequested) {
            try {
                if (cpuPressureController != null) cpuPressureController.releaseBatch(2);
                if (lowRefreshApplied && privileged != null) restoreRefresh();
                prefs.edit().putString("last_rollback_note",
                        "Rollback automático: restrições de desempenho removidas por jank com temperatura segura.")
                        .apply();
            } catch (Throwable ignored) {}
        }

        if (mutationsAllowed && startupReady && cpuPressureController != null && cpuPressureBusy.compareAndSet(false, true)) {
            final CpuPressureController cpc = cpuPressureController;
            final double cpuCopy = cpuLoad;
            final String fgPressure = fg;
            cpuPressureExecutor.execute(() -> {
                try { cpc.update(cpuCopy, fgPressure, socForPressure, adaptivePressureScore); }
                catch (Throwable ignored) {}
                finally { cpuPressureBusy.set(false); }
            });
        }

        if (mutationsAllowed && startupReady && manualFreezeManager != null && manualFreezeBusy.compareAndSet(false, true)) {
            final ManualFreezeManager mfm = manualFreezeManager;
            final String fgFreeze = fg;
            manualFreezeExecutor.execute(() -> {
                try { mfm.update(fgFreeze); }
                catch (Throwable ignored) {}
                finally { manualFreezeBusy.set(false); }
            });
        }

        if (mutationsAllowed && maintenance != null && maintenanceBusy.compareAndSet(false, true)) {
            final BackgroundMaintenance m = maintenance;
            final String fgCopy = fg;
            maintenanceExecutor.execute(() -> {
                try { m.maybeRun(fgCopy); }
                catch (Throwable ignored) {}
                finally { maintenanceBusy.set(false); }
            });
        }

        String thermalMode = prefs.getString("thermal_mode", "auto");
        int manualThermalLevel = thermalLevelFromMode(thermalMode);
        if (mutationsAllowed && privileged != null) {
            if (manualThermalLevel > 0) {
                setThermalLevel(manualThermalLevel);
            } else if (advanced != null) {
                int target = advanced.thermalTarget;
                if (extended != null && !extended.safeMode &&
                        prefs.getBoolean("thermal_prediction", true)) {
                    target = Math.max(target, extended.predictedThermalLevel);
                }
                if (target > 0) setThermalLevel(target);
                else resetThermalOverride();
            } else {
                boolean hasSoc = thermals.hasSoc();
                float hot = hasSoc ? thermals.soc : tempC;
                float l1On = hasSoc ? SOC_LEVEL1_C : 38.0f;
                float maxOn = hasSoc ? SOC_MAX_C : 40.0f;
                float l1Off = hasSoc ? SOC_LEVEL1_RELEASE_C : 37.5f;
                if (hot >= maxOn) setThermalLevel(5);
                else if (hot >= l1On) setThermalLevel(1);
                else if (thermalLevelApplied > 0 && hot <= l1Off) resetThermalOverride();
            }
        }

        if (mutationsAllowed && thermalBrightnessController != null && brightnessBusy.compareAndSet(false, true)) {
            final ThermalBrightnessController bc = thermalBrightnessController;
            final float bsoc = thermals.soc;
            final float bbat = tempC;
            final int blevel = thermalLevelApplied;
            final int bpred = extended != null ? extended.predictedThermalLevel : 0;
            final boolean binteractive = interactive;
            brightnessExecutor.execute(() -> {
                try { bc.update(bsoc, bbat, blevel, bpred, binteractive); }
                catch (Throwable ignored) {}
                finally { brightnessBusy.set(false); }
            });
        }

        if (manualThermalLevel > 0) profile = "Térmico nível " + manualThermalLevel + " travado";
        else if (thermalLevelApplied >= 5) profile = "Térmico máximo";
        else if (thermalLevelApplied >= 1) profile = "Térmico nível " + thermalLevelApplied;
        else if (cpuPressureController != null && cpuPressureController.isActive()) profile = "Pressão do sistema — segundo plano limitado";
        else if (extended != null && !extended.safeMode && extended.activityProfile != null &&
                !"Uso leve".equals(extended.activityProfile)) profile = extended.activityProfile + " adaptativo";

        boolean adaptiveRefresh = prefs.getBoolean("adaptive_refresh", true);
        if (mutationsAllowed && adaptiveRefresh && privileged != null) {
            boolean refreshHot = thermalLevelApplied >= 5 ||
                    (thermals.hasSoc() && thermals.soc >= SOC_MAX_C) ||
                    (health != null && health.antiStallLevel >= 2) ||
                    (extended != null && !extended.safeMode && extended.predictedThermalLevel >= 4);
            boolean refreshCool = thermals.hasSoc() ? thermals.soc <= 56.0f : tempC <= 38.5f;
            if (refreshHot && !lowRefreshApplied) {
                apply60Hz();
            } else if (refreshCool && lowRefreshApplied && System.currentTimeMillis()-lastRefreshChange > 180000) {
                restoreRefresh();
            }
        }

        if (mutationsAllowed && privileged != null) {
            EmergencyRamController.update(this, prefs, privileged, availPct,
                    prefs.getFloat("psi_memory", -1f), fg);
        }

        String thermalShort = thermals.hasSoc()
                ? String.format(Locale.US, "SoC %.1f°C • Bat %.1f°C", thermals.soc, tempC)
                : String.format(Locale.US, "Bat %.1f°C", tempC);
        String adaptiveBits = "";
        if (advanced != null) {
            adaptiveBits = String.format(Locale.US, " • P%d%s%s",
                    advanced.pressureScore,
                    Math.abs(advanced.tempTrendCPerMin) >= 0.5f
                            ? String.format(Locale.US, " • ΔT %+.1f°C/min", advanced.tempTrendCPerMin) : "",
                    advanced.powerW > 0f ? String.format(Locale.US, " • %.1f W", advanced.powerW) : "");
        }
        String status = String.format(Locale.US,
                "%s • %s • RAM %.0f%% livre%s%s%s",
                profile, thermalShort, availPct,
                cpuLoad >= 0 ? String.format(Locale.US, " • CPU %.0f%%", cpuLoad) : "",
                avgFreq > 0 ? String.format(Locale.US, " • %d MHz", avgFreq/1000) : "",
                adaptiveBits);
        prefs.edit()
                .putString("status", status)
                .putString("foreground", fg)
                .putFloat("temp_c", tempC)
                .putFloat("thermal_cpu_c", thermals.cpu)
                .putFloat("thermal_gpu_c", thermals.gpu)
                .putFloat("thermal_soc_c", thermals.soc)
                .putFloat("thermal_skin_c", thermals.skin)
                .putFloat("thermal_battery_c", thermals.battery)
                .putFloat("ram_free_pct", (float)availPct)
                .putFloat("cpu_load", (float)cpuLoad)
                .putLong("cpu_freq_khz", avgFreq)
                .putLong("last_sample", System.currentTimeMillis())
                .apply();

        try {
            AdaptiveIntelligenceController.update(this, prefs, fg, controlTemp,
                    advanced != null ? advanced.tempTrendCPerMin : 0f, cpuLoad, availPct,
                    advanced != null ? advanced.psiMemory : prefs.getFloat("psi_memory", -1f),
                    prefs.getString("probable_cause", "Sem causa anormal detectada"));
        } catch (Throwable t) {
            prefs.edit().putString("adaptive_intelligence_error", t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
        }

        AppProfilePolicy.batch(this, prefs, fg, 4);
        AutoRepairController.evaluate(prefs, cpuPressureController);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(7014, buildNotification(status));

        if (healthController != null) {
            long cost = Math.max(0L, SystemClock.elapsedRealtime() - cycleStarted);
            healthController.recordCycleCost(cost);
            nextLoopDelayMs = healthController.watchdogMinimumDelay(nextLoopDelayMs);
        }
    }

    private ThermalSnapshot parseThermals(String raw, float fallbackBattery) {
        ThermalSnapshot t = new ThermalSnapshot();
        t.battery = fallbackBattery;
        if (raw == null) return t;
        Pattern p = Pattern.compile("Temperature\\{mValue=([0-9.]+),\\s*mType=[0-9]+,\\s*mName=([^,}]+)");
        Matcher m = p.matcher(raw);
        while (m.find()) {
            float v;
            try { v = Float.parseFloat(m.group(1)); } catch (Exception e) { continue; }
            String name = m.group(2).trim().toUpperCase(Locale.US);
            if ("CPU".equals(name)) t.cpu = v;
            else if ("GPU".equals(name)) t.gpu = v;
            else if ("SOC".equals(name)) t.soc = v;
            else if ("SKIN".equals(name)) t.skin = v;
            else if ("BATTERY".equals(name)) t.battery = v;
        }
        return t;
    }

    private boolean isChargingNow() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return false;
            int plugged = b.getIntExtra("plugged", 0);
            int status = b.getIntExtra("status", 0);
            return plugged != 0 || status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            return false;
        }
    }

    private void suspendPrivilegedForDeepIdle() {
        if (!prefs.getBoolean("system_deep_idle_active", false)) return;
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        if (pm != null && pm.isInteractive()) return;
        if (isChargingNow()) return;
        float soc = prefs.getFloat("thermal_soc_c", -1f);
        float bat = prefs.getFloat("thermal_battery_c", prefs.getFloat("temp_c", -1f));
        if (soc >= 56f || bat >= 38f || thermalLevelApplied > 0) return;
        if (systemBatteryController != null && systemBatteryController.isAutomationActive()) return;

        try {
            if (userServiceArgs != null && privileged != null) {
                prefs.edit().putBoolean("shizuku_suspended_deep_idle", true).apply();
                Shizuku.unbindUserService(userServiceArgs, connection, true);
            }
        } catch (Throwable t) {
            prefs.edit().putString("deep_idle_unbind_error",
                    t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
        }
    }

    private float batteryTemp() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return 0f;
        int t = b.getIntExtra("temperature", 0);
        return t / 10f;
    }

    private double parseCpu(String line) {
        try {
            String[] p = line.trim().split("\\s+");
            long user=Long.parseLong(p[1]), nice=Long.parseLong(p[2]), system=Long.parseLong(p[3]);
            long idle=Long.parseLong(p[4]), iowait=Long.parseLong(p[5]), irq=Long.parseLong(p[6]), soft=Long.parseLong(p[7]);
            long total=user+nice+system+idle+iowait+irq+soft;
            long idleAll=idle+iowait;
            if (lastCpuTotal < 0) { lastCpuTotal=total; lastCpuIdle=idleAll; return -1; }
            long dt=total-lastCpuTotal, di=idleAll-lastCpuIdle;
            lastCpuTotal=total; lastCpuIdle=idleAll;
            return dt > 0 ? Math.max(0, Math.min(100, 100.0*(dt-di)/dt)) : -1;
        } catch (Throwable t) { return -1; }
    }

    private long parseFreq(String s) {
        long sum=0; int n=0;
        for (String line: s.split("\\n")) {
            try { long v=Long.parseLong(line.trim()); if (v>0) { sum+=v; n++; } } catch(Exception ignored){}
        }
        return n==0 ? -1 : sum/n;
    }

    private String parseForeground(String s) {
        Matcher m = Pattern.compile("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private void learnApp(String pkg, double cpu) {
        if (pkg == null || pkg.isEmpty() || cpu < 0) return;
        String key = "learn_" + Integer.toHexString(pkg.hashCode());
        float old = prefs.getFloat(key, -1f);
        float ema = old < 0 ? (float)cpu : (float)(old*0.85 + cpu*0.15);
        int visits = prefs.getInt(key+"_n", 0);
        prefs.edit().putFloat(key, ema).putInt(key+"_n", Math.min(10000, visits+1))
                .putString(key+"_pkg", pkg).apply();
    }

    private boolean isHeavyLearnedApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String key = "learn_" + Integer.toHexString(pkg.hashCode());
        return prefs.getInt(key+"_n", 0) >= 5 && prefs.getFloat(key, 0f) >= 55f;
    }

    private void captureRefreshBaseline() {
        if (privileged == null || prefs.contains("baseline_peak")) return;
        try {
            String s = privileged.exec("settings get system peak_refresh_rate; settings get system min_refresh_rate");
            String[] lines=s.split("\\n");
            String peak=lines.length>0?lines[0].trim():"";
            String min=lines.length>1?lines[1].trim():"";
            prefs.edit().putString("baseline_peak", peak).putString("baseline_min", min).apply();
        } catch(Throwable ignored){}
    }

    private int thermalLevelFromMode(String mode) {
        if (mode == null) return 0;
        if (mode.startsWith("level")) {
            try {
                int level = Integer.parseInt(mode.substring(5));
                return (level >= 1 && level <= 5) ? level : 0;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private void setThermalLevel(int level) {
        try {
            if (level < 1 || level > 5) return;
            if (thermalLevelApplied == level) return;
            privileged.exec("cmd thermalservice override-status " + level);
            thermalLevelApplied = level;
            prefs.edit()
                    .putInt("thermal_level", level)
                    .putBoolean("thermal_stage1", level == 1)
                    .apply();
            String msg = level == 5 ? "Thermal nível 5 (emergência) ativado." :
                    "Thermal nível " + level + " ativado.";
            ChangeNotifier.notifyChange(this, "Proteção térmica alterada", msg, 1);
        } catch (Throwable ignored) {}
    }

    private void resetThermalOverride() {
        try {
            if (thermalLevelApplied == 0) return;
            privileged.exec("cmd thermalservice reset");
            thermalLevelApplied = 0;
            prefs.edit()
                    .putInt("thermal_level", 0)
                    .putBoolean("thermal_stage1", false)
                    .apply();
            ChangeNotifier.notifyChange(this, "Temperatura normalizada",
                    "Override térmico removido; controle normal restaurado.", 1);
        } catch (Throwable ignored) {}
    }

    private void apply60Hz() {
        try {
            privileged.exec("settings put system peak_refresh_rate 60.0; settings put system min_refresh_rate 60.0");
            lowRefreshApplied=true; lastRefreshChange=System.currentTimeMillis();
            prefs.edit().putBoolean("low_refresh", true).apply();
            ChangeNotifier.notifyChange(this, "Tela limitada a 60 Hz", "Taxa de atualização reduzida por aquecimento.", 3);
        } catch(Throwable ignored){}
    }

    private void restoreRefresh() {
        String peak=prefs.getString("baseline_peak","");
        String min=prefs.getString("baseline_min","");
        try {
            StringBuilder cmd=new StringBuilder();
            if (peak.isEmpty() || "null".equals(peak)) cmd.append("settings delete system peak_refresh_rate;");
            else cmd.append("settings put system peak_refresh_rate ").append(shellSafeNumber(peak)).append(";");
            if (min.isEmpty() || "null".equals(min)) cmd.append("settings delete system min_refresh_rate;");
            else cmd.append("settings put system min_refresh_rate ").append(shellSafeNumber(min)).append(";");
            privileged.exec(cmd.toString());
            lowRefreshApplied=false; lastRefreshChange=System.currentTimeMillis();
            prefs.edit().putBoolean("low_refresh", false).apply();
            ChangeNotifier.notifyChange(this, "Taxa de atualização restaurada", "A tela voltou à configuração anterior.", 3);
        } catch(Throwable ignored){}
    }

    private String shellSafeNumber(String v) {
        try { return String.valueOf(Double.parseDouble(v)); } catch(Exception e){ return "60.0"; }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Adaptive Performance",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Monitor de desempenho, RAM e temperatura");
            NotificationManager nm=getSystemService(NotificationManager.class);
            if(nm!=null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent i=new Intent(this, MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.mauricio.adaptiveperformance.R.drawable.ic_app)
                .setContentTitle("Adaptive Performance")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void runStorageTask(String mode, boolean cache, boolean thumbs, boolean partial, boolean apk, boolean diag, boolean empty,
                                boolean logs, boolean stale, boolean editorTemp, boolean dexCache, int retry) {
        prefs.edit().putBoolean("storage_cleanup_busy", true)
                .putString("storage_cleanup_status", "Preparando " + ("clean".equals(mode) ? "limpeza" : "análise") + "…").apply();
        if (privileged == null) {
            bindShizukuIfPossible();
            if (retry < 8) {
                handler.postDelayed(() -> runStorageTask(mode, cache, thumbs, partial, apk, diag, empty, logs, stale, editorTemp, dexCache, retry + 1), 1200L);
            } else {
                prefs.edit().putBoolean("storage_cleanup_busy", false)
                        .putString("storage_cleanup_status", "Shizuku indisponível. Abra/autorize o Shizuku e tente novamente.").apply();
                if (!prefs.getBoolean("master", false)) stopSelf();
            }
            return;
        }
        cleanupExecutor.execute(() -> {
            try {
                if ("scan".equals(mode)) storageScan();
                else if ("large".equals(mode)) storageLargeScan();
                else storageClean(cache, thumbs, partial, apk, diag, empty, logs, stale, editorTemp, dexCache);
            } catch (Throwable t) {
                prefs.edit().putString("storage_cleanup_status", "Falha: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())).apply();
            } finally {
                prefs.edit().putBoolean("storage_cleanup_busy", false).apply();
                if (!prefs.getBoolean("master", false)) stopSelf();
            }
        });
    }

    private long shellLong(String cmd) {
        try {
            String out = privileged.exec(cmd);
            if (out == null) return 0L;
            Matcher m = Pattern.compile("(-?[0-9]+)").matcher(out.trim());
            return m.find() ? Long.parseLong(m.group(1)) : 0L;
        } catch (Throwable t) { return 0L; }
    }

    private long dataFreeKb() {
        return shellLong("df -k /data 2>/dev/null | tail -1 | awk '{print $4}'");
    }

    private void storageScan() throws Exception {
        prefs.edit().putString("storage_cleanup_status", "Analisando armazenamento…").apply();
        long cacheBytes = shellLong("dumpsys diskstats 2>/dev/null | awk -F': ' '/App Cache Size:/ {print $2; exit}'");
        long thumbsKb = shellLong("du -sk /sdcard/DCIM/.thumbnails /sdcard/Pictures/.thumbnails 2>/dev/null | awk '{s+=$1} END{print s+0}'");
        long partialBytes = shellLong("find /sdcard/Download -maxdepth 2 -type f \\(" +
                " -name '*.tmp' -o -name '*.part' -o -name '*.crdownload' -o -name '*.fdmdownload' -o -name '*.download' \\) -mtime +7 -exec stat -c %s {} \\; 2>/dev/null | awk '{s+=$1} END{print s+0}'");
        long apkBytes = shellLong("find /sdcard/Download -maxdepth 2 -type f -name '*.apk' -mtime +7 " +
                "! -name 'AdaptivePerformance-optimized.apk' ! -name 'TaskStatusBar.apk' -exec stat -c %s {} \\; 2>/dev/null | awk '{s+=$1} END{print s+0}'");
        long diagBytes = shellLong("find /sdcard/Download -maxdepth 1 -type f -name 'AdaptivePerformance-diagnostico-*.txt' -mtime +7 -exec stat -c %s {} \\; 2>/dev/null | awk '{s+=$1} END{print s+0}'");
        long cacheKb = Math.max(0L, cacheBytes / 1024L);
        long partialKb = Math.max(0L, partialBytes / 1024L);
        long apkKb = Math.max(0L, apkBytes / 1024L);
        long diagKb = Math.max(0L, diagBytes / 1024L);
        long total = cacheKb + thumbsKb + partialKb + apkKb + diagKb;
        prefs.edit().putLong("storage_scan_cache_kb", cacheKb)
                .putLong("storage_scan_thumbnails_kb", thumbsKb)
                .putLong("storage_scan_partial_kb", partialKb)
                .putLong("storage_scan_apk_kb", apkKb)
                .putLong("storage_scan_diag_kb", diagKb)
                .putLong("storage_scan_total_kb", total)
                .putLong("storage_scan_at", System.currentTimeMillis())
                .putString("storage_cleanup_status", "Análise concluída • nenhuma mídia pessoal foi alterada").apply();
    }

    private void storageLargeScan() throws Exception {
        prefs.edit().putString("storage_cleanup_status", "Procurando arquivos grandes…").apply();
        String out = privileged.exec("find /sdcard/Download -maxdepth 2 -type f -size +500M -exec stat -c '%s|%n' {} \\; 2>/dev/null | sort -nr | head -12");
        if (out == null || out.trim().isEmpty()) {
            prefs.edit().putString("storage_large_files", "Nenhum arquivo acima de 500 MB encontrado em Downloads.")
                    .putStringSet("storage_large_entries", new HashSet<>())
                    .putString("storage_cleanup_status", "Análise de arquivos grandes concluída").apply();
            return;
        }
        StringBuilder text = new StringBuilder();
        Set<String> entries = new HashSet<>();
        for (String line : out.split("\n")) {
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            try {
                long bytes = Long.parseLong(line.substring(0, bar).trim());
                String path = line.substring(bar + 1).trim();
                if (!path.startsWith("/sdcard/Download/")) continue;
                String name = path.substring(path.lastIndexOf('/') + 1);
                entries.add(bytes + "|" + path);
                if (text.length() > 0) text.append("\n");
                text.append(String.format(Locale.US, "%.2f GB • %s", bytes / 1073741824.0, name));
            } catch (Throwable ignored) {}
        }
        prefs.edit()
                .putString("storage_large_files", text.length() == 0 ? "Nenhum arquivo grande encontrado." : text.toString())
                .putStringSet("storage_large_entries", new HashSet<>(entries))
                .putString("storage_cleanup_status", "Análise de arquivos grandes concluída • selecione o que deseja excluir")
                .apply();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void deleteLargeFiles(ArrayList<String> requested) {
        prefs.edit().putBoolean("storage_cleanup_busy", true)
                .putString("storage_cleanup_status", "Excluindo arquivos grandes selecionados…").apply();
        if (privileged == null) {
            bindShizukuIfPossible();
            handler.postDelayed(() -> deleteLargeFiles(requested), 1200L);
            return;
        }
        cleanupExecutor.execute(() -> {
            int deleted = 0;
            long bytesDeleted = 0L;
            try {
                Set<String> allowedEntries = new HashSet<>(prefs.getStringSet("storage_large_entries", Collections.emptySet()));
                Map<String,Long> allowed = new HashMap<>();
                for (String entry : allowedEntries) {
                    int bar = entry.indexOf('|');
                    if (bar <= 0) continue;
                    try {
                        long bytes = Long.parseLong(entry.substring(0, bar));
                        String path = entry.substring(bar + 1);
                        if (path.startsWith("/sdcard/Download/")) allowed.put(path, bytes);
                    } catch (Throwable ignored) {}
                }
                if (requested != null) {
                    for (String path : requested) {
                        if (path == null || !allowed.containsKey(path) || !path.startsWith("/sdcard/Download/")) continue;
                        if (RestrictionGuard.command(privileged, "rm -f -- " + shellQuote(path))) {
                            deleted++;
                            bytesDeleted += allowed.get(path);
                        }
                    }
                }
                storageLargeScan();
                prefs.edit()
                        .putLong("storage_large_deleted_bytes", bytesDeleted)
                        .putInt("storage_large_deleted_count", deleted)
                        .putLong("storage_large_deleted_at", System.currentTimeMillis())
                        .putString("storage_cleanup_status", deleted > 0
                                ? "Arquivos grandes excluídos: " + deleted + " • " + String.format(Locale.US, "%.2f GB", bytesDeleted / 1073741824.0)
                                : "Nenhum arquivo grande foi excluído")
                        .apply();
            } catch (Throwable t) {
                prefs.edit().putString("storage_cleanup_status",
                        "Falha ao excluir arquivos grandes: " + t.getClass().getSimpleName()).apply();
            } finally {
                prefs.edit().putBoolean("storage_cleanup_busy", false).apply();
                if (!prefs.getBoolean("master", false)) stopSelf();
            }
        });
    }

    private void runStorageTrim(int retry) {
        long trimStart = System.currentTimeMillis();
        final long expectedMs = 120_000L;
        prefs.edit().putBoolean("storage_cleanup_busy", true)
                .putBoolean("storage_trim_abort_requested", false)
                .putBoolean("storage_trim_running", true)
                .putLong("storage_trim_started_at", trimStart)
                .putLong("storage_trim_expected_ms", expectedMs)
                .putInt("storage_trim_progress", 5)
                .putString("storage_cleanup_status", "Otimizando armazenamento com manutenção/TRIM…").apply();
        if (privileged == null) {
            bindShizukuIfPossible();
            if (retry < 8) handler.postDelayed(() -> runStorageTrim(retry + 1), 1200L);
            else {
                prefs.edit().putBoolean("storage_cleanup_busy", false)
                        .putBoolean("storage_trim_running", false)
                        .putString("storage_cleanup_status", "Shizuku indisponível para executar otimização/TRIM.").apply();
                if (!prefs.getBoolean("master", false)) stopSelf();
            }
            return;
        }
        cleanupExecutor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                String out = privileged.exec("sm idle-maint run 2>&1");
                boolean requested = prefs.getBoolean("storage_trim_abort_requested", false);
                boolean ok = out == null || !out.toLowerCase(Locale.US).matches("(?s).*(error|exception|failed|permission denial).*" );
                if (!requested && ok) {
                    prefs.edit().putString("storage_cleanup_status", "Manutenção/TRIM iniciada pelo Android • pode ser pausada").apply();
                }
                long sec = Math.max(1L, (System.currentTimeMillis() - start) / 1000L);
                if (requested) {
                    int pct = estimatedTrimProgress();
                    prefs.edit().putBoolean("storage_cleanup_busy", false)
                            .putBoolean("storage_trim_running", false)
                            .putInt("storage_trim_progress", pct)
                            .putLong("storage_last_trim_seconds", sec)
                            .putString("storage_cleanup_status", "Otimização pausada/abortada com segurança em " + pct + "% estimados").apply();
                    IncidentHistory.add(prefs, "storage_trim", "Otimização de armazenamento", "Pausada em " + pct + "% estimados", -1);
                } else if (ok) {
                    long elapsed = System.currentTimeMillis() - prefs.getLong("storage_trim_started_at", System.currentTimeMillis());
                    long remain = Math.max(0L, prefs.getLong("storage_trim_expected_ms", 120_000L) - elapsed);
                    prefs.edit().putString("storage_cleanup_status", "Manutenção/TRIM iniciada • progresso estimado em andamento").apply();
                    handler.postDelayed(() -> {
                        if (prefs.getBoolean("storage_trim_running", false) && !prefs.getBoolean("storage_trim_abort_requested", false)) {
                            prefs.edit().putBoolean("storage_cleanup_busy", false)
                                    .putBoolean("storage_trim_running", false)
                                    .putInt("storage_trim_progress", 100)
                                    .putLong("storage_last_trim_at", System.currentTimeMillis())
                                    .putString("storage_cleanup_status", "Otimização estimada concluída • Android pode finalizar manutenção interna em segundo plano").apply();
                            IncidentHistory.add(prefs, "storage_trim", "Otimização de armazenamento", "Janela estimada concluída (100%)", -1);
                            if (!prefs.getBoolean("master", false)) stopSelf();
                        }
                    }, remain);
                } else {
                    prefs.edit().putBoolean("storage_cleanup_busy", false)
                            .putBoolean("storage_trim_running", false)
                            .putString("storage_cleanup_status", "Não foi possível concluir TRIM neste aparelho").apply();
                }
            } catch (Throwable t) {
                boolean requested = prefs.getBoolean("storage_trim_abort_requested", false);
                int pct = estimatedTrimProgress();
                prefs.edit().putBoolean("storage_cleanup_busy", false)
                        .putBoolean("storage_trim_running", false)
                        .putInt("storage_trim_progress", pct)
                        .putString("storage_cleanup_status", requested ? "Otimização pausada/abortada em " + pct + "% estimados" : "Falha no TRIM: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())).apply();
            } finally {
                if (!prefs.getBoolean("master", false)) stopSelf();
            }
        });
    }

    private int estimatedTrimProgress() {
        int stored = prefs.getInt("storage_trim_progress", 0);
        long started = prefs.getLong("storage_trim_started_at", 0L);
        long expected = Math.max(30_000L, prefs.getLong("storage_trim_expected_ms", 120_000L));
        if (started <= 0L) return Math.max(0, Math.min(100, stored));
        long elapsed = Math.max(0L, System.currentTimeMillis() - started);
        int pct = (int) Math.min(95L, 5L + (elapsed * 90L / expected));
        return Math.max(stored, Math.max(5, pct));
    }

    private void requestStorageTrimAbort() {
        int pausePct = estimatedTrimProgress();
        prefs.edit().putBoolean("storage_trim_abort_requested", true)
                .putInt("storage_trim_progress", pausePct)
                .putString("storage_cleanup_status", "Solicitando pausa da otimização em " + pausePct + "% estimados…").apply();
        maintenanceExecutor.execute(() -> {
            try {
                if (privileged == null) bindShizukuIfPossible();
                if (privileged != null) privileged.exec("sm idle-maint abort 2>&1");
                int pct = prefs.getInt("storage_trim_progress", estimatedTrimProgress());
                prefs.edit().putBoolean("storage_trim_running", false)
                        .putBoolean("storage_cleanup_busy", false)
                        .putInt("storage_trim_progress", pct)
                        .putString("storage_cleanup_status", "Otimização pausada em " + pct + "% estimados. Toque em otimizar para retomar.").apply();
            } catch (Throwable t) {
                prefs.edit().putString("storage_cleanup_status", "Pausa solicitada; aguardando o Android encerrar a manutenção.").apply();
            }
        });
    }

    private void storageClean(boolean cache, boolean thumbs, boolean partial, boolean apk, boolean diag, boolean empty,
                              boolean logs, boolean stale, boolean editorTemp, boolean dexCache) throws Exception {
        prefs.edit().putString("storage_cleanup_status", "Limpando categorias selecionadas…").apply();
        long before = dataFreeKb();
        ArrayList<String> done = new ArrayList<>();
        if (cache) {
            privileged.exec("pm trim-caches 256G 2>/dev/null; true");
            done.add("cache de apps");
        }
        if (thumbs) {
            privileged.exec("find /sdcard/DCIM/.thumbnails /sdcard/Pictures/.thumbnails -mindepth 1 -type f -delete 2>/dev/null; true");
            done.add("miniaturas");
        }
        if (partial) {
            privileged.exec("find /sdcard/Download -maxdepth 2 -type f \\(" +
                    " -name '*.tmp' -o -name '*.part' -o -name '*.crdownload' -o -name '*.fdmdownload' -o -name '*.download' \\) -mtime +7 -delete 2>/dev/null; true");
            done.add("downloads incompletos");
        }
        if (apk) {
            privileged.exec("find /sdcard/Download -maxdepth 2 -type f -name '*.apk' -mtime +7 " +
                    "! -name 'AdaptivePerformance-optimized.apk' ! -name 'TaskStatusBar.apk' -delete 2>/dev/null; true");
            done.add("APKs antigos");
        }
        if (diag) {
            privileged.exec("find /sdcard/Download -maxdepth 1 -type f -name 'AdaptivePerformance-diagnostico-*.txt' -mtime +7 -delete 2>/dev/null; true");
            done.add("diagnósticos antigos");
        }
        if (empty) {
            privileged.exec("find /sdcard/Download -mindepth 1 -maxdepth 2 -type d -empty -delete 2>/dev/null; true");
            done.add("pastas vazias");
        }
        if (logs) {
            privileged.exec("find /data/local/tmp -maxdepth 2 -type f \\( -name '*.log' -o -name 'tombstone*' -o -name '*.trace' \\) -mtime +7 -delete 2>/dev/null; true");
            done.add("logs/relatórios antigos");
        }
        if (stale) {
            privileged.exec("find /sdcard/Download -maxdepth 2 -type f \\( -name '*.log' -o -name '*.bak' -o -name '*.old' \\) -mtime +14 -delete 2>/dev/null; true");
            done.add("arquivos auxiliares antigos");
        }
        if (editorTemp) {
            privileged.exec("find /sdcard/Download -maxdepth 2 -type f \\( -name '*~' -o -name '*.temp' -o -name '*.swp' -o -name '*.swo' \\) -mtime +7 -delete 2>/dev/null; true");
            done.add("temporários de editores");
        }
        if (dexCache) {
            // Solicita apenas a limpeza suportada pelo Package Manager; não remove APK/dados do usuário.
            privileged.exec("pm trim-caches 512G 2>/dev/null; true");
            done.add("caches temporários do Android");
        }
        try { Thread.sleep(700L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        long after = dataFreeKb();
        long freed = Math.max(0L, after - before);
        prefs.edit().putLong("storage_last_freed_kb", freed)
                .putLong("storage_last_clean_at", System.currentTimeMillis())
                .putString("storage_cleanup_status", "Limpeza concluída • " + (done.isEmpty() ? "nenhuma categoria selecionada" : String.join(", ", done))).apply();
        IncidentHistory.add(prefs, "storage_cleanup", "Limpeza de espaço", "Liberado aprox. " + freed + " KB; " + String.join(", ", done), -1);
        storageScan();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP_OPTIMIZATION.equals(action)) {
            prefs.edit()
                    .putBoolean("master", false)
                    .putBoolean("service_running", false)
                    .apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STORAGE_SCAN.equals(action)) {
            runStorageTask("scan", false,false,false,false,false,false,false,false,false,false, 0);
            return START_STICKY;
        }
        if (ACTION_STORAGE_LARGE_SCAN.equals(action)) {
            runStorageTask("large", false,false,false,false,false,false,false,false,false,false, 0);
            return START_STICKY;
        }
        if (ACTION_STORAGE_DELETE_LARGE.equals(action)) {
            ArrayList<String> paths = intent.getStringArrayListExtra("paths");
            deleteLargeFiles(paths == null ? new ArrayList<>() : paths);
            return START_STICKY;
        }
        if (ACTION_STORAGE_CLEAN.equals(action)) {
            runStorageTask("clean",
                    intent.getBooleanExtra("cache", true),
                    intent.getBooleanExtra("thumbs", true),
                    intent.getBooleanExtra("partial", true),
                    intent.getBooleanExtra("apk", false),
                    intent.getBooleanExtra("diag", true),
                    intent.getBooleanExtra("empty", true),
                    intent.getBooleanExtra("logs", true),
                    intent.getBooleanExtra("stale", false),
                    intent.getBooleanExtra("editorTemp", true),
                    intent.getBooleanExtra("dexCache", false), 0);
            return START_STICKY;
        }
        if (ACTION_STORAGE_TRIM.equals(action)) {
            runStorageTrim(0);
            return START_STICKY;
        }
        if (ACTION_STORAGE_TRIM_ABORT.equals(action)) {
            requestStorageTrimAbort();
            return START_STICKY;
        }

        if (ACTION_START_OPTIMIZATION.equals(action)) {
            prefs.edit()
                    .putBoolean("master", true)
                    .putBoolean("service_running", true)
                    .putString("last_error", "")
                    .apply();
        } else if (!prefs.getBoolean("master", false)) {
            // Never re-enable optimization just because Android recreated a sticky service.
            prefs.edit().putBoolean("service_running", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        } else {
            prefs.edit().putBoolean("service_running", true).apply();
        }

        if (intent != null && ACTION_APPLY_CAUSE_FIX.equals(intent.getAction())) {
            String causeId = intent.getStringExtra(CauseResolutionNotifier.EXTRA_CAUSE_ID);
            String causeText = intent.getStringExtra(CauseResolutionNotifier.EXTRA_CAUSE_TEXT);
            int retryCount = intent.getIntExtra("retry_count", 0);
            applyCauseFixAsync(causeId == null ? "" : causeId,
                    causeText == null ? "" : causeText, retryCount);
        }

        if (intent != null && ACTION_SET_THERMAL_MODE.equals(intent.getAction())) {
            String mode = intent.getStringExtra("mode");
            int requestedLevel = thermalLevelFromMode(mode);
            if (requestedLevel == 0) mode = "auto";
            prefs.edit().putString("thermal_mode", mode).apply();
            if (privileged != null) {
                if (requestedLevel > 0) setThermalLevel(requestedLevel);
                else if (thermalLevelApplied > 0) resetThermalOverride();
            }
        }
        return START_STICKY;
    }

    private void applyCauseFixAsync(String causeId, String causeText, int retryCount) {
        if (!startupReady) { scheduleCauseRetry(causeId, causeText, retryCount + 1, 6000L); return; }
        AutoRepairController.begin(prefs, causeId);
        if ((causeId.equals("ram_pressure") || causeId.equals("cpu_pressure") || causeId.equals("io_pressure"))
                && AutoRepairController.safe(prefs)) {
            reportCauseResult(causeId, true,
                    "Sistema já normalizado; nenhuma ação adicional necessária.", false);
            return;
        }
        prefs.edit()
                .putString("last_cause_decision", "apply")
                .putString("last_cause_applied_id", causeId)
                .putLong("last_cause_decision_at", System.currentTimeMillis())
                .apply();

        if (!prefs.getBoolean("auto_repair", true)) return;
        if (privileged != null && !recoveryReady) {
            if (retryCount < 3) scheduleCauseRetry(causeId, causeText, retryCount + 1, 6000L);
            return;
        }
        if (causeId.matches("ram_pressure|cpu_pressure|io_pressure|heat_charge|battery_soc_heat")
                && AutoRepairController.safe(prefs)) {
            reportCauseResult(causeId, true, "Sistema já normalizado; nenhuma ação adicional necessária.", false);
            return;
        }
        if ("deep_sleep_low".equals(causeId)) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isInteractive()) {
                reportCauseResult(causeId, false,
                        "Nenhuma mudança: aguardando a tela apagar para tentar as políticas de repouso.", true);
                return;
            }
        }

        if (privileged == null) {
            bindShizukuIfPossible();
            if (retryCount < 3) {
                scheduleCauseRetry(causeId, causeText, retryCount + 1, 8000L);
            } else {
                reportCauseResult(causeId, false,
                        "Tentei reconectar o Shizuku 3 vezes, mas ele continuou indisponível. " +
                        "Abra o app para autorizar/conectar ou escolha manter assim.", true);
            }
            return;
        }

        AutoRepairController.begin(prefs, causeId);

        if ("persistent_wakelock".equals(causeId) || "memory_leak".equals(causeId)) {
            maintenanceExecutor.execute(() -> applyDetectedAppFix(causeId));
            return;
        }

        if ("deep_sleep_low".equals(causeId)) {
            systemBatteryExecutor.execute(() -> {
                try {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    if (pm == null || pm.isInteractive()) {
                        reportCauseResult(causeId, false,
                                "Nenhuma mudança: aguardando a tela apagar para tentar as políticas de repouso.", true);
                        return;
                    }
                    SystemBatteryController controller = systemBatteryController;
                    if (controller == null) throw new IllegalStateException("Controlador indisponível");
                    Map<String, ?> before = prefs.getAll();
                    controller.maybeRunPolicies(false, isChargingNow(),
                            prefs.getFloat("thermal_soc_c", -1f), batteryTemp(),
                            prefs.getFloat("cpu_load", -1f), prefs.getString("foreground", ""));
                    List<String> changes = new ArrayList<>();
                    String[] tags = {"wifi_scan_throttle", "wifi_scan_always", "wifi_wakeup",
                            "mobile_always", "double_tap", "screen_timeout"};
                    String[] names = {"limite de buscas Wi-Fi", "buscas Wi-Fi contínuas",
                            "ativação automática de Wi-Fi", "dados móveis sempre ativos", "toque duplo para acordar",
                            "tempo para apagar a tela (ms)"};
                    for (int i = 0; i < tags.length; i++) {
                        String key = "sys_applied_" + tags[i];
                        String after = prefs.getString(key, null);
                        if (!Objects.equals(before.get(key), after)) {
                            changes.add(names[i] + ": " + (after == null ? "restaurado" : after));
                        }
                    }
                    Set<String> removed = prefs.getStringSet("doze_removed_by_adaptive", Collections.emptySet());
                    Set<String> previouslyRemoved = new HashSet<>();
                    Object previousDoze = before.get("doze_removed_by_adaptive");
                    if (previousDoze instanceof Set<?>) {
                        for (Object pkg : (Set<?>) previousDoze) {
                            if (pkg instanceof String) previouslyRemoved.add((String) pkg);
                        }
                    }
                    Set<String> newlyRemoved = new TreeSet<>(removed);
                    newlyRemoved.removeAll(previouslyRemoved);
                    Set<String> restored = new TreeSet<>(previouslyRemoved);
                    restored.removeAll(removed);
                    if (!newlyRemoved.isEmpty()) changes.add("apps retirados da exceção Doze: " + newlyRemoved);
                    if (!restored.isEmpty()) changes.add("exceção Doze restaurada para: " + restored);
                    boolean policyRan = !Objects.equals(before.get("system_battery_policy_at"),
                            prefs.getAll().get("system_battery_policy_at"));
                    boolean changed = !changes.isEmpty();
                    reportCauseResult(causeId, changed,
                            changed ? "Políticas reversíveis registradas pelo controlador: " + String.join("; ", changes)
                                    : (policyRan
                                        ? "Políticas reversíveis de deep idle, rádio e Doze tentadas com a tela apagada; nenhuma nova alteração registrada."
                                        : "Políticas de deep idle, rádio e Doze consultadas com a tela apagada; nenhuma nova alteração registrada (intervalo/condições do controlador ou políticas desativadas)."),
                            !changed);
                } catch (Throwable t) {
                    reportCauseResult(causeId, false,
                            "Falha ao tentar políticas de repouso: " + t.getMessage(), true);
                }
            });
            return;
        }

        if ("ram_pressure".equals(causeId) || "cpu_pressure".equals(causeId) ||
                "io_pressure".equals(causeId)) {
            if (cpuPressureController == null || !cpuPressureBusy.compareAndSet(false, true)) {
                if (retryCount < 3) {
                    scheduleCauseRetry(causeId, causeText, retryCount + 1, 6000L);
                } else {
                    reportCauseResult(causeId, false,
                            "Tentei aplicar a correção 3 vezes, mas o controlador de pressão continuou ocupado. " +
                            "Você pode tentar novamente agora ou manter como está.", true);
                }
                return;
            }
            final CpuPressureController cpc = cpuPressureController;
            cpuPressureExecutor.execute(() -> {
                boolean ok = false;
                Set<String> previouslyLimited = new HashSet<>(
                        prefs.getStringSet("cpu_limited_set", Collections.emptySet()));
                try {
                    double cpu = prefs.getFloat("cpu_load", -1f);
                    float soc = prefs.getFloat("thermal_soc_c", -1f);
                    String fg = prefs.getString("foreground", "");
                    cpc.update(cpu, fg, soc, AutoRepairController.safe(prefs) ? 0 : 4);
                    ok = true;
                } catch (Throwable ignored) {
                } finally {
                    cpuPressureBusy.set(false);
                }
                Set<String> limited = new TreeSet<>(
                        prefs.getStringSet("cpu_limited_set", Collections.emptySet()));
                Set<String> added = new TreeSet<>(limited);
                added.removeAll(previouslyLimited);
                Set<String> released = new TreeSet<>(previouslyLimited);
                released.removeAll(limited);
                String result = AutoRepairController.safe(prefs)
                        ? "Sistema já normalizado; nenhuma ação adicional necessária."
                        : "Controlador de pressão executado; nenhuma nova restrição ou liberação registrada.";
                if (!added.isEmpty() || !released.isEmpty()) {
                    result = "Segundo plano: " +
                            (added.isEmpty() ? "" : "restrição temporária background_restricted aplicada a " + added + ". ") +
                            (released.isEmpty() ? "" : "restrição anterior restaurada para " + released + ". ") +
                            "Restrições temporárias serão liberadas quando a pressão normalizar.";
                }
                if (added.isEmpty() && released.isEmpty() && AutoRepairController.safe(prefs))
                    result = "Sistema já normalizado; nenhuma ação adicional necessária.";
                reportCauseResult(causeId, ok,
                        ok ? result
                           : "Não foi possível aplicar a limitação de segundo plano. Nenhuma mudança adicional foi feita.",
                        !ok);
            });
            return;
        }

        if ("gpu_jank".equals(causeId)) {
            maintenanceExecutor.execute(() -> {
                boolean ok = false;
                try {
                    apply60Hz();
                    ok = lowRefreshApplied;
                } catch (Throwable ignored) {}
                reportCauseResult(causeId, ok,
                        ok ? "Solução aplicada: a tela foi reduzida temporariamente para 60 Hz. " +
                                "O modo adaptativo restaurará a taxa quando as condições normalizarem."
                           : "Não foi possível aplicar 60 Hz automaticamente. Você pode manter assim ou revisar os detalhes.",
                        !ok);
            });
            return;
        }

        if ("heat_charge".equals(causeId) || "battery_soc_heat".equals(causeId)) {
            maintenanceExecutor.execute(() -> {
                boolean ok = false;
                try {
                    int desired = "heat_charge".equals(causeId) ? 4 : 3;
                    if (thermalLevelApplied > desired) desired = thermalLevelApplied;
                    setThermalLevel(desired);
                    apply60Hz();
                    ok = thermalLevelApplied >= ("heat_charge".equals(causeId) ? 4 : 3);
                } catch (Throwable ignored) {}
                reportCauseResult(causeId, ok,
                        ok ? "Proteção térmica temporária no nível " + thermalLevelApplied +
                                (lowRefreshApplied ? "; tela em 60 Hz. " : "; 60 Hz não confirmado. ") +
                                "As medidas serão aliviadas automaticamente quando a temperatura cair."
                           : "Não foi possível reforçar a proteção automaticamente. Nenhuma proteção térmica existente foi reduzida.",
                        !ok);
            });
            return;
        }

        reportCauseResult(causeId, false,
                "Essa causa não tem uma correção automática suficientemente segura. " +
                "Abra os detalhes para ver a recomendação ou escolha manter assim.", false);
    }

    private void reportCauseResult(String causeId, boolean success, String text, boolean retry) {
        AutoRepairController.finish(prefs, causeId, success, text);
        if (success) {
            prefs.edit().putLong("cause_resolved_at_" + causeId, System.currentTimeMillis())
                    .putFloat("cause_resolved_ram_" + causeId, prefs.getFloat("ram_free_pct", -1))
                    .putFloat("cause_resolved_cpu_" + causeId, prefs.getFloat("cpu_load", -1))
                    .putFloat("cause_resolved_temp_" + causeId, prefs.getFloat("thermal_soc_c", -1))
                    .putFloat("cause_resolved_psi_" + causeId, prefs.getFloat("psi_memory", -1))
                    .remove("active_cause_id").remove("active_cause_text").remove("active_cause_solution").apply();
        }
        CauseResolutionNotifier.notifyResult(this, causeId, success, text, retry);
    }

    // All detected-package mutations pass through the same fail-closed guard.
    private void requireSafeBackgroundApp(String pkg) throws Exception {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
                || !AppSafety.isEligibleForAutomaticRestriction(this, pkg)
                || AppSafety.isSystemApp(this, pkg) || AppProfilePolicy.protectedActive(this, prefs, pkg)) {
            throw new IllegalStateException("App ausente, protegido, do sistema ou inelegível: " + pkg);
        }
        String services = privileged.exec("dumpsys activity services " + pkg);
        if (services == null || services.contains("isForeground=true")) throw new IllegalStateException("Serviço ativo ou desconhecido");
        if (!RestrictionGuard.background(this, prefs, privileged, pkg))
            throw new IllegalStateException("App ativo, serviço em primeiro plano ou estado não verificável");
        String resumed = privileged.exec("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
        if (resumed == null || parseForeground(resumed).isEmpty()) {
            throw new IllegalStateException("Não foi possível confirmar o app em primeiro plano");
        }
        if (pkg.equals(prefs.getString("foreground", "")) || resumed.contains(pkg + "/")) {
            throw new IllegalStateException("App em primeiro plano: " + pkg);
        }
    }

    private void runCauseCommand(String command) throws Exception {
        String output = privileged.exec(command + " && echo __CAUSE_OK__");
        if (output == null || !output.trim().endsWith("__CAUSE_OK__")
                || output.toLowerCase(Locale.US).matches("(?s).*(error|exception|unknown command|permission denial).*")) {
            throw new IllegalStateException("Comando não confirmado: " + output);
        }
    }

    private void applyDetectedAppFix(String causeId) {
        String pkg = prefs.getString(causeId + "_pkg", "");
        String action = "Nenhuma alteração confirmada";
        try {
            requireSafeBackgroundApp(pkg);
            if (!RestrictionGuard.claim(prefs, pkg, "cause", causeId))
                throw new IllegalStateException("App já controlado por outro módulo");
            String app = AppSafety.label(this, pkg) + " (" + pkg + ")";
            if ("persistent_wakelock".equals(causeId)) {
                String old = privileged.exec("cmd activity get-bg-restriction-level --user 0 " + pkg);
                old = old == null ? "" : old.trim();
                if (!old.matches("unrestricted|exempted|adaptive_bucket|restricted_bucket|background_restricted|hibernation")) {
                    throw new IllegalStateException("Nível anterior desconhecido; restrição não aplicada");
                }
                if ("background_restricted".equals(old) || "hibernation".equals(old)) {
                    reportCauseResult(causeId, false,
                            app + ": nenhuma mudança; segundo plano já está em " + old + ".", false);
                    return;
                }
                String key = "persistent_wakelock_previous_level_" + pkg;
                if (!prefs.contains(key) && !prefs.edit().putString(key, old).commit()) {
                    throw new IllegalStateException("Não foi possível salvar o nível anterior; restrição não aplicada");
                }
                requireSafeBackgroundApp(pkg);
                runCauseCommand("cmd activity set-bg-restriction-level --user 0 " + pkg + " background_restricted");
                prefs.edit().putLong("persistent_wakelock_applied_at_" + pkg, System.currentTimeMillis()).apply();
                action = app + ": segundo plano definido como background_restricted (antes: " + old +
                        "); nível anterior salvo para reversão. App não encerrado";
            } else {
                String inactive = privileged.exec("am get-inactive --user 0 " + pkg);
                if (inactive == null || !inactive.trim().matches("Idle=(true|false)")) {
                    throw new IllegalStateException("Estado de inatividade anterior desconhecido");
                }
                boolean wasInactive = inactive.trim().equals("Idle=true");
                requireSafeBackgroundApp(pkg);
                runCauseCommand("am force-stop --user 0 " + pkg);
                action = app + ": encerrado em segundo plano";
                prefs.edit().putString("memory_leak_last_fixed_pkg", pkg)
                        .putString("memory_leak_last_action", action).apply();
                requireSafeBackgroundApp(pkg);
                // Persist before changing state so a service restart can restore it.
                if (!wasInactive && !prefs.edit().putLong("memory_leak_inactive_until_" + pkg,
                        System.currentTimeMillis() + 10L * 60L * 1000L).commit()) {
                    throw new IllegalStateException("Não foi possível salvar a restauração; inatividade não aplicada");
                }
                runCauseCommand("am set-inactive --user 0 " + pkg + " true");
                if (!wasInactive) handler.postDelayed(() -> {
                    if (!serviceDestroyed) maintenanceExecutor.execute(this::restoreExpiredCauseInactivity);
                }, 10L * 60L * 1000L);
                action += wasInactive ? "; já estava inativo" : "; marcado inativo temporariamente (restauração em 10 minutos)";
            }
            prefs.edit().putString(causeId + "_last_fixed_pkg", pkg)
                    .putString(causeId + "_last_action", action).apply();
            reportCauseResult(causeId, true, action + ".", false);
        } catch (Throwable t) {
            reportCauseResult(causeId, false,
                    action + ". Correção incompleta: " + t.getMessage(), true);
        } finally {
            if (!prefs.contains("persistent_wakelock_previous_level_" + pkg)
                    && !prefs.contains("memory_leak_inactive_until_" + pkg)) RestrictionGuard.release(prefs, pkg, "cause");
        }
    }

    private void restoreExpiredCauseInactivity() {
        if (privileged == null) return;
        restoreExpiredWakelockRestrictions();
        String prefix = "memory_leak_inactive_until_";
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix) || !(entry.getValue() instanceof Long)) continue;
            String pkg = entry.getKey().substring(prefix.length());
            if (!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) continue;
            if ((Long) entry.getValue() > System.currentTimeMillis()
                    && !pkg.equals(prefs.getString("foreground", ""))) continue;
            try {
                runCauseCommand("am set-inactive --user 0 " + pkg + " false");
                prefs.edit().remove(entry.getKey()).apply();
                RestrictionGuard.release(prefs, pkg, "cause");
                IncidentHistory.add(prefs, "memory_leak", pkg, "Inatividade temporária restaurada", -1);
            } catch (Throwable ignored) { /* Retry on the next service sample. */ }
        }
    }

    private void restoreExpiredWakelockRestrictions() {
        for (String key : prefs.getAll().keySet()) {
            String prefix = "persistent_wakelock_previous_level_";
            if (!key.startsWith(prefix)) continue;
            String pkg = key.substring(prefix.length());
            if (!pkg.matches("[A-Za-z0-9_.]+")) continue;
            long at = prefs.getLong("persistent_wakelock_applied_at_" + pkg, 0);
            if (System.currentTimeMillis() - at < 600000 && !pkg.equals(prefs.getString("foreground", ""))
                    && !AppSafety.isAutoProtected(this, pkg)) continue;
            String owner = prefs.getString("restriction_owner_" + pkg, "");
            if (!owner.isEmpty() && !owner.equals("cause")) continue;
            try {
                String old = prefs.getString(key, "");
                String current = privileged.exec("cmd activity get-bg-restriction-level --user 0 " + pkg);
                if (!RestrictionGuard.level(old) || current == null || !RestrictionGuard.level(current.trim())) continue;
                if (current.trim().equals("background_restricted"))
                    runCauseCommand("cmd activity set-bg-restriction-level --user 0 " + pkg + " " + old);
                prefs.edit().remove(key).remove("persistent_wakelock_applied_at_" + pkg).commit();
                RestrictionGuard.release(prefs, pkg, "cause");
                IncidentHistory.add(prefs, "persistent_wakelock", pkg, "Restrição temporária restaurada", -1);
            } catch (Exception ignored) { }
        }
    }

    private void scheduleCauseRetry(String causeId, String causeText, int retryCount, long delayMs) {
        prefs.edit()
                .putInt("cause_retry_count", retryCount)
                .putLong("cause_retry_scheduled_at", System.currentTimeMillis())
                .putString("cause_retry_id", causeId)
                .apply();
        handler.postDelayed(() -> {
            if (!prefs.getBoolean("master", true)) return;
            Intent retry = new Intent(this, OptimizationService.class)
                    .setAction(ACTION_APPLY_CAUSE_FIX)
                    .putExtra(CauseResolutionNotifier.EXTRA_CAUSE_ID, causeId)
                    .putExtra(CauseResolutionNotifier.EXTRA_CAUSE_TEXT, causeText)
                    .putExtra("retry_count", retryCount);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(retry);
                else startService(retry);
            } catch (Throwable t) {
                reportCauseResult(causeId, false,
                        "Não consegui iniciar uma nova tentativa automática. " +
                        "Você pode tentar novamente manualmente ou manter como está.", true);
            }
        }, Math.max(1500L, delayMs));
    }

    @Override public void onDestroy() {
        serviceDestroyed = true;
        handler.removeCallbacksAndMessages(null);
        sampleExecutor.shutdownNow();
        try { unregisterReceiver(powerStateReceiver); } catch (Throwable ignored) {}

        // Privileged restore operations can take several seconds on HyperOS.
        // Never execute them on the main thread during service teardown.
        final SystemBatteryController sbc = systemBatteryController;
        final ThermalBrightnessController bc = thermalBrightnessController;
        final ManualFreezeManager mfm = manualFreezeManager;
        final CpuPressureController cpc = cpuPressureController;
        final boolean restoreRefreshNeeded = lowRefreshApplied;
        final boolean resetThermalNeeded = thermalLevelApplied > 0;
        final IPrivilegedService privilegedRef = privileged;

        cleanupExecutor.execute(() -> {
            try {
                // Release temporary inactivity even when stopping before its deadline.
                if (privilegedRef != null) {
                    for (String key : prefs.getAll().keySet()) {
                        if (!key.startsWith("memory_leak_inactive_until_")) continue;
                        prefs.edit().putLong(key, 0L).apply();
                    }
                    restoreExpiredCauseInactivity();
                }
                if (sbc != null && privilegedRef != null) {
                    try { sbc.restoreAll(); } catch (Throwable ignored) {}
                }
                if (bc != null) {
                    try { bc.restore(); } catch (Throwable ignored) {}
                }
                // O congelamento manual deve sobreviver ao encerramento/reinício do serviço.
                // A restauração só ocorre se o usuário desativar/remover o app da seleção.
                if (cpc != null) {
                    try { cpc.restoreAll(); } catch (Throwable ignored) {}
                }
                if (restoreRefreshNeeded && privilegedRef != null) {
                    try { restoreRefresh(); } catch (Throwable ignored) {}
                }
                if (resetThermalNeeded && privilegedRef != null) {
                    try { resetThermalOverride(); } catch (Throwable ignored) {}
                }
                try {
                    if (userServiceArgs != null) {
                        Shizuku.unbindUserService(userServiceArgs, connection, true);
                    }
                } catch (Throwable ignored) {}
                prefs.edit()
                        .putBoolean("shizuku_bound", false)
                        .putBoolean("service_running", false)
                        .apply();
            } finally {
                cleanupExecutor.shutdown();
            }
        });

        systemBatteryExecutor.shutdownNow();
        maintenanceExecutor.shutdownNow();
        cpuPressureExecutor.shutdownNow();
        manualFreezeExecutor.shutdownNow();
        healthExecutor.shutdownNow();
        extendedExecutor.shutdownNow();
        brightnessExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

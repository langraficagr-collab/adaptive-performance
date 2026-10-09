package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * System-wide battery policies. The hot path is shell-free; all privileged
 * inspection and setting changes are rate-limited and run on a worker.
 */
public final class SystemBatteryController {
    private static final long DEEP_IDLE_DELAY_MS = 5L * 60L * 1000L;
    private static final long ACTIVE_TASK_SCAN_MS = 2L * 60L * 1000L;
    private static final long POLICY_ACTIVE_MS = 5L * 60L * 1000L;
    private static final long POLICY_IDLE_MS = 10L * 60L * 1000L;
    private static final long WAKELOCK_AUDIT_MS = 10L * 60L * 1000L;
    private static final long SYNC_AUDIT_MS = 30L * 60L * 1000L;
    private static final long SENSOR_AUDIT_MS = 20L * 60L * 1000L;

    public static final class Result {
        public boolean deepIdle;
        public boolean automationActive;
        public boolean shouldSuspendPrivileged;
        public long suggestedLoopMs;
        public String report = "";
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final IPrivilegedService privileged;

    private long screenOffSince = 0L;
    private long lastTaskScan = 0L;
    private long lastPolicy = 0L;
    private long lastWakeAudit = 0L;
    private long lastSyncAudit = 0L;
    private long lastSensorAudit = 0L;

    private volatile boolean cachedAutomationActive = false;
    private volatile boolean deepIdleActive = false;
    private volatile boolean policiesApplied = false;

    private static final String[] MANAGED_DOZE_PACKAGES = {
            "com.mauricio.adaptiveperformance",
            "com.termux",
            "com.termux.api",
            "com.termux.widget",
            "com.termux.styling",
            "moe.shizuku.privileged.api",
            "com.coremate.opengui"
    };

    public SystemBatteryController(Context context, SharedPreferences prefs,
                                   IPrivilegedService privileged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.privileged = privileged;
        this.cachedAutomationActive = prefs.getBoolean("system_automation_active", false);
        this.deepIdleActive = prefs.getBoolean("system_deep_idle_active", false);
        this.policiesApplied = prefs.getBoolean("system_battery_policy_applied", false);
    }

    public Result evaluate(boolean interactive, boolean charging, float socTemp,
                           float batteryTemp, double cpuLoad) {
        long now = SystemClock.elapsedRealtime();
        if (interactive) {
            screenOffSince = 0L;
            deepIdleActive = false;
            prefs.edit().putBoolean("system_deep_idle_active", false).apply();
        } else if (screenOffSince == 0L) {
            screenOffSince = now;
        }

        boolean cool = (socTemp <= 0f || socTemp < 56f)
                && (batteryTemp <= 0f || batteryTemp < 38f);
        boolean cpuQuiet = cpuLoad < 0 || cpuLoad < 45.0;
        boolean offLongEnough = !interactive && screenOffSince > 0L
                && now - screenOffSince >= DEEP_IDLE_DELAY_MS;

        boolean deep = prefs.getBoolean("system_deep_idle", true)
                && offLongEnough && !charging && cool && cpuQuiet && !cachedAutomationActive;

        deepIdleActive = deep;
        prefs.edit()
                .putBoolean("system_deep_idle_active", deep)
                .putLong("system_screen_off_since_elapsed", screenOffSince)
                .apply();

        Result r = new Result();
        r.deepIdle = deep;
        r.automationActive = cachedAutomationActive;
        r.shouldSuspendPrivileged = deep && policiesApplied && !cachedAutomationActive;
        if (deep) r.suggestedLoopMs = 15L * 60L * 1000L;
        else if (!interactive) r.suggestedLoopMs = 5L * 60L * 1000L;
        else r.suggestedLoopMs = 0L;
        r.report = deep ? "Deep idle do sistema" :
                (!interactive ? "Tela apagada — espera econômica" :
                        (cachedAutomationActive ? "Automação/MCP ativo" : "Sistema ativo"));
        return r;
    }

    public synchronized void maybeRunPolicies(boolean interactive, boolean charging,
                                              float socTemp, float batteryTemp,
                                              double cpuLoad, String foreground) {
        long now = SystemClock.elapsedRealtime();
        if (!prefs.getBoolean("system_battery_guard", true)) {
            restoreAllChangedSettings();
            restoreDozeWhitelist();
            deepIdleActive = false;
            prefs.edit().putBoolean("system_deep_idle_active", false).apply();
            return;
        }

        if (now - lastTaskScan >= ACTIVE_TASK_SCAN_MS) {
            lastTaskScan = now;
            cachedAutomationActive = detectAutomationActive();
            prefs.edit().putBoolean("system_automation_active", cachedAutomationActive).apply();
        }

        boolean cool = (socTemp <= 0f || socTemp < 56f)
                && (batteryTemp <= 0f || batteryTemp < 38f);
        boolean quiet = cpuLoad < 0 || cpuLoad < 45.0;
        boolean deep = !interactive && screenOffSince > 0L
                && now - screenOffSince >= DEEP_IDLE_DELAY_MS
                && !charging && cool && quiet && !cachedAutomationActive
                && prefs.getBoolean("system_deep_idle", true);
        deepIdleActive = deep;

        long policyInterval = deep ? POLICY_IDLE_MS : POLICY_ACTIVE_MS;
        if (now - lastPolicy >= policyInterval) {
            lastPolicy = now;
            captureBaselinesIfNeeded();
            applyWifiAndRadioPolicies(interactive, deep, foreground);
            applyAdaptiveScreenTimeout(interactive, charging, foreground);
            applyDozePolicies(interactive, deep);
            applyOptionalMaximumSavings(interactive, deep);

            policiesApplied = true;
            prefs.edit()
                    .putBoolean("system_battery_policy_applied", true)
                    .putBoolean("system_deep_idle_active", deep)
                    .putLong("system_battery_policy_at", System.currentTimeMillis())
                    .apply();
        }

        if (!BuildConfig.CONSERVATIVE_MODE && !interactive && now - lastWakeAudit >= WAKELOCK_AUDIT_MS) {
            lastWakeAudit = now;
            auditPersistentWakeLocks(foreground);
        }
        if (!BuildConfig.CONSERVATIVE_MODE && now - lastSyncAudit >= SYNC_AUDIT_MS) {
            lastSyncAudit = now;
            auditStuckSyncs();
        }
        if (now - lastSensorAudit >= SENSOR_AUDIT_MS) {
            lastSensorAudit = now;
            auditSensorConnections(interactive, foreground);
        }
    }

    public boolean shouldSuspendPrivileged() {
        return deepIdleActive && policiesApplied && !cachedAutomationActive;
    }

    public boolean isAutomationActive() {
        return cachedAutomationActive;
    }

    public synchronized void auditWakeLocksNow(String foreground) {
        auditPersistentWakeLocks(foreground == null ? "" : foreground);
    }


    private boolean detectAutomationActive() {
        try {
            String raw = privileged.exec(
                    "ps -A -o ARGS 2>/dev/null | " +
                    "grep -E 'appium|uiautomator|ffmpeg|edge-tts|agentbridge|touchbridge|" +
                    "vibes-mapped|flow-run|scrcpy|am instrument|llama-server|llama-cli|ollama' | " +
                    "grep -v 'grep -E' | head -30");
            if (raw == null) return false;
            for (String line : raw.split("\n")) {
                String x = line.trim();
                if (!x.isEmpty() && !x.contains("SystemBatteryController")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void captureBaselinesIfNeeded() {
        if (prefs.getBoolean("system_settings_baseline_captured", false)) return;
        try {
            String raw = privileged.exec(
                    "echo wifi_scan_throttle=$(settings get global wifi_scan_throttle_enabled); " +
                    "echo wifi_scan_always=$(settings get global wifi_scan_always_enabled); " +
                    "echo wifi_wakeup=$(settings get global wifi_wakeup_enabled); " +
                    "echo mobile_always=$(settings get global mobile_data_always_on); " +
                    "echo screen_timeout=$(settings get system screen_off_timeout); " +
                    "echo double_tap=$(settings get secure double_tap_to_wake)");
            Map<String,String> values = parseKeyValues(raw);
            SharedPreferences.Editor e = prefs.edit();
            for (Map.Entry<String,String> v : values.entrySet()) {
                e.putString("baseline_sys_" + v.getKey(), v.getValue());
            }
            e.putBoolean("system_settings_baseline_captured", true).apply();
        } catch (Throwable ignored) {}
    }

    private void applyWifiAndRadioPolicies(boolean interactive, boolean deep, String foreground) {
        if (!prefs.getBoolean("system_radio_savings", true)) {
            restoreSetting("global", "wifi_scan_throttle_enabled", "wifi_scan_throttle");
            restoreSetting("global", "wifi_scan_always_enabled", "wifi_scan_always");
            restoreSetting("global", "wifi_wakeup_enabled", "wifi_wakeup");
            restoreSetting("global", "mobile_data_always_on", "mobile_always");
            return;
        }

        boolean realtime = isRealtimePackage(foreground);

        // Keep Android's background Wi-Fi scan throttle enabled by default.
        if (!realtime) putSetting("global", "wifi_scan_throttle_enabled", "1", "wifi_scan_throttle");
        else restoreSetting("global", "wifi_scan_throttle_enabled", "wifi_scan_throttle");

        if (deep) {
            putSetting("global", "wifi_scan_always_enabled", "0", "wifi_scan_always");
            putSetting("global", "wifi_wakeup_enabled", "0", "wifi_wakeup");
            putSetting("global", "mobile_data_always_on", "0", "mobile_always");
        } else if (interactive) {
            restoreSetting("global", "wifi_scan_always_enabled", "wifi_scan_always");
            restoreSetting("global", "wifi_wakeup_enabled", "wifi_wakeup");
            restoreSetting("global", "mobile_data_always_on", "mobile_always");
        }
    }

    private void applyAdaptiveScreenTimeout(boolean interactive, boolean charging, String foreground) {
        if (!prefs.getBoolean("adaptive_screen_timeout", true)) {
            restoreSetting("system", "screen_off_timeout", "screen_timeout");
            return;
        }
        if (!interactive) return;

        boolean keepLong = charging || isLongScreenPackage(foreground);
        if (keepLong) {
            restoreSetting("system", "screen_off_timeout", "screen_timeout");
            return;
        }

        long baseline = parseLong(prefs.getString("baseline_sys_screen_timeout", "600000"), 600000L);
        long target = Math.min(baseline, 180_000L);
        putSetting("system", "screen_off_timeout", Long.toString(target), "screen_timeout");
    }

    private void applyDozePolicies(boolean interactive, boolean deep) {
        if (!prefs.getBoolean("dynamic_doze_whitelist", true)) {
            restoreDozeWhitelist();
            return;
        }

        if (deep) {
            Set<String> currently = readDozeWhitelist();
            Set<String> removed = new HashSet<>(prefs.getStringSet(
                    "doze_removed_by_adaptive", Collections.emptySet()));
            for (String pkg : MANAGED_DOZE_PACKAGES) {
                if (isAutomationPackage(pkg) && cachedAutomationActive) continue;
                if (currently.contains(pkg)) {
                    try {
                        privileged.exec("cmd deviceidle whitelist -" + pkg);
                        removed.add(pkg);
                    } catch (Throwable ignored) {}
                }
            }
            prefs.edit().putStringSet("doze_removed_by_adaptive", removed).apply();
        } else if (interactive || cachedAutomationActive) {
            restoreDozeWhitelist();
        }
    }

    private void applyOptionalMaximumSavings(boolean interactive, boolean deep) {
        if (!prefs.getBoolean("maximum_battery_mode", false)) {
            restoreSetting("secure", "double_tap_to_wake", "double_tap");
            return;
        }
        if (deep) putSetting("secure", "double_tap_to_wake", "0", "double_tap");
        else if (interactive) restoreSetting("secure", "double_tap_to_wake", "double_tap");
    }

    private Set<String> readDozeWhitelist() {
        Set<String> out = new HashSet<>();
        try {
            String raw = privileged.exec("cmd deviceidle whitelist");
            if (raw != null) {
                Pattern p = Pattern.compile("(?:user|system|system-excidle),([A-Za-z0-9_.]+),[0-9]+");
                Matcher m = p.matcher(raw);
                while (m.find()) out.add(m.group(1));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void restoreDozeWhitelist() {
        Set<String> removed = new HashSet<>(prefs.getStringSet(
                "doze_removed_by_adaptive", Collections.emptySet()));
        if (removed.isEmpty()) return;
        for (String pkg : removed) {
            try { privileged.exec("cmd deviceidle whitelist +" + pkg); }
            catch (Throwable ignored) {}
        }
        prefs.edit().remove("doze_removed_by_adaptive").apply();
    }

    private void restoreAllChangedSettings() {
        restoreSetting("global", "wifi_scan_throttle_enabled", "wifi_scan_throttle");
        restoreSetting("global", "wifi_scan_always_enabled", "wifi_scan_always");
        restoreSetting("global", "wifi_wakeup_enabled", "wifi_wakeup");
        restoreSetting("global", "mobile_data_always_on", "mobile_always");
        restoreSetting("system", "screen_off_timeout", "screen_timeout");
        restoreSetting("secure", "double_tap_to_wake", "double_tap");
    }

    public synchronized boolean restoreConservativeOption(String key) {
        String[][] settings;
        if ("system_radio_savings".equals(key)) {
            settings = new String[][]{{"global","wifi_scan_throttle_enabled","wifi_scan_throttle"},
                {"global","wifi_scan_always_enabled","wifi_scan_always"},
                {"global","wifi_wakeup_enabled","wifi_wakeup"},
                {"global","mobile_data_always_on","mobile_always"}};
        } else if ("adaptive_screen_timeout".equals(key)) {
            settings = new String[][]{{"system","screen_off_timeout","screen_timeout"}};
        } else if ("maximum_battery_mode".equals(key)) {
            settings = new String[][]{{"secure","double_tap_to_wake","double_tap"}};
        } else if ("dynamic_doze_whitelist".equals(key)) {
            settings = new String[0][];
        } else {
            settings = new String[][]{{"global","wifi_scan_throttle_enabled","wifi_scan_throttle"},
                {"global","wifi_scan_always_enabled","wifi_scan_always"},
                {"global","wifi_wakeup_enabled","wifi_wakeup"},
                {"global","mobile_data_always_on","mobile_always"},
                {"system","screen_off_timeout","screen_timeout"},
                {"secure","double_tap_to_wake","double_tap"}};
        }
        try {
            for (String[] setting : settings) {
                String applied = "sys_applied_" + setting[2];
                if (!prefs.contains(applied)) continue;
                String expected = prefs.getString("baseline_sys_" + setting[2], "null");
                String command = "null".equals(expected) || expected.isEmpty()
                        ? "settings delete " + setting[0] + " " + setting[1]
                        : "settings put " + setting[0] + " " + setting[1] + " " + shellSafe(expected);
                privileged.exec(command);
                String actual = privileged.exec("settings get " + setting[0] + " " + setting[1]);
                if (actual == null || !actual.trim().equals(expected.isEmpty() ? "null" : expected)) return false;
                prefs.edit().remove(applied).commit();
            }
            if ("dynamic_doze_whitelist".equals(key) || "system_battery_guard".equals(key)) {
                Set<String> removed = new HashSet<>(prefs.getStringSet("doze_removed_by_adaptive", Collections.emptySet()));
                for (String pkg : removed) {
                    if (!pkg.matches("[A-Za-z0-9_.]+")) return false;
                    privileged.exec("cmd deviceidle whitelist +" + pkg);
                    String actual = privileged.exec("cmd deviceidle whitelist");
                    if (actual == null || !actual.contains("," + pkg + ",")) return false;
                    Set<String> remaining = new HashSet<>(prefs.getStringSet("doze_removed_by_adaptive", Collections.emptySet()));
                    remaining.remove(pkg);
                    prefs.edit().putStringSet("doze_removed_by_adaptive", remaining).commit();
                }
            }
            lastPolicy = 0L;
            return true;
        } catch (Throwable t) { return false; }
    }

    public synchronized void restoreAll() {
        try {
            StringBuilder cmd = new StringBuilder();
            appendRestoreCommand(cmd, "global", "wifi_scan_throttle_enabled", "wifi_scan_throttle");
            appendRestoreCommand(cmd, "global", "wifi_scan_always_enabled", "wifi_scan_always");
            appendRestoreCommand(cmd, "global", "wifi_wakeup_enabled", "wifi_wakeup");
            appendRestoreCommand(cmd, "global", "mobile_data_always_on", "mobile_always");
            appendRestoreCommand(cmd, "system", "screen_off_timeout", "screen_timeout");
            appendRestoreCommand(cmd, "secure", "double_tap_to_wake", "double_tap");

            Set<String> removed = new HashSet<>(prefs.getStringSet(
                    "doze_removed_by_adaptive", Collections.emptySet()));
            for (String pkg : removed) {
                cmd.append("cmd deviceidle whitelist +").append(pkg).append("; ");
            }

            if (cmd.length() > 0) privileged.exec(cmd.toString());

            prefs.edit()
                    .remove("sys_applied_wifi_scan_throttle")
                    .remove("sys_applied_wifi_scan_always")
                    .remove("sys_applied_wifi_wakeup")
                    .remove("sys_applied_mobile_always")
                    .remove("sys_applied_screen_timeout")
                    .remove("sys_applied_double_tap")
                    .remove("doze_removed_by_adaptive")
                    .putBoolean("system_battery_policy_applied", false)
                    .putBoolean("system_deep_idle_active", false)
                    .apply();
        } catch (Throwable ignored) {}
    }

    private void appendRestoreCommand(StringBuilder cmd, String scope, String name, String tag) {
        String appliedKey = "sys_applied_" + tag;
        if (!prefs.contains(appliedKey)) return;
        String value = prefs.getString("baseline_sys_" + tag, "null");
        if (value == null || value.isEmpty() || "null".equalsIgnoreCase(value)) {
            cmd.append("settings delete ").append(scope).append(' ').append(name).append("; ");
        } else {
            cmd.append("settings put ").append(scope).append(' ').append(name).append(' ')
                    .append(shellSafe(value)).append("; ");
        }
    }

    private void putSetting(String scope, String name, String value, String tag) {
        String appliedKey = "sys_applied_" + tag;
        String previous = prefs.getString(appliedKey, null);
        if (value.equals(previous)) return;
        try {
            privileged.exec("settings put " + scope + " " + name + " " + shellSafe(value));
            prefs.edit().putString(appliedKey, value).apply();
        } catch (Throwable ignored) {}
    }

    private void restoreSetting(String scope, String name, String baselineTag) {
        String appliedKey = "sys_applied_" + baselineTag;
        if (!prefs.contains(appliedKey)) return;
        String value = prefs.getString("baseline_sys_" + baselineTag, "null");
        try {
            if (value == null || value.isEmpty() || "null".equalsIgnoreCase(value)) {
                privileged.exec("settings delete " + scope + " " + name);
            } else {
                privileged.exec("settings put " + scope + " " + name + " " + shellSafe(value));
            }
            prefs.edit().remove(appliedKey).apply();
        } catch (Throwable ignored) {}
    }

    private String shellSafe(String value) {
        if (value == null) return "null";
        return value.matches("[-0-9.]+") ? value : "'" + value.replace("'", "") + "'";
    }

    private void auditPersistentWakeLocks(String foreground) {
        try {
            String raw = privileged.exec(
                    "dumpsys power 2>/dev/null | sed -n '/Wake Locks: size=/,/Suspend Blockers: size=/p' | head -100");
            if (raw == null) return;
            String worst = "";
            String worstPkg = "";
            long worstSec = 0L;

            for (String line : raw.split("\n")) {
                if (!line.contains("PARTIAL_WAKE_LOCK")) continue;
                Matcher tagM = Pattern.compile("'([^']+)'").matcher(line);
                Matcher durM = Pattern.compile("ACQ=-([^\s]+)").matcher(line);
                Matcher pkgM = Pattern.compile("pkg=([A-Za-z0-9_.]+)").matcher(line);
                String tag = tagM.find() ? tagM.group(1) : "wakelock";
                String duration = durM.find() ? durM.group(1) : "";
                String pkg = pkgM.find() ? pkgM.group(1) : "";
                long sec = parseDurationSeconds(duration);
                if (sec > worstSec) {
                    worstSec = sec;
                    worstPkg = pkg;
                    worst = (pkg.isEmpty() ? tag : AppSafety.label(context, pkg) + " • " + tag);
                }
            }

            boolean termux = worstPkg.startsWith("com.termux");
            boolean termuxBusy = termux && (prefs.getBoolean("treat_termux_wakelock_expected", true) || isTermuxWorkActive());
            boolean foregroundService = !worstPkg.isEmpty() && hasForegroundService(worstPkg);
            boolean userCandidate = !worstPkg.isEmpty()
                    && AppSafety.isEligibleForAdaptiveOptimization(context, worstPkg);

            String previousPkg = prefs.getString("persistent_wakelock_candidate_pkg", "");
            int confirmations = prefs.getInt("persistent_wakelock_confirmations", 0);
            boolean suspiciousNow = worstSec >= 10L * 60L
                    && !cachedAutomationActive
                    && !termuxBusy
                    && !foregroundService
                    && !worstPkg.equals(foreground)
                    && userCandidate;
            confirmations = suspiciousNow
                    ? (worstPkg.equals(previousPkg) ? Math.min(3, confirmations + 1) : 1)
                    : 0;
            boolean unresolved = suspiciousNow && confirmations >= 2;

            restorePersistentWakeLockRestrictions(suspiciousNow ? worstPkg : "");

            String report;
            if (worst.isEmpty()) report = "Nenhum wakelock persistente";
            else if (termuxBusy) report = "Wakelock esperado: " + worst + " • " + (worstSec / 60L) +
                    " min • MCP/túnel/automação ativa";
            else if (foregroundService) report = "Wakelock associado a serviço em primeiro plano: " + worst +
                    " • " + (worstSec / 60L) + " min";
            else if (suspiciousNow && confirmations < 2) report = "Wakelock suspeito aguardando confirmação: " + worst +
                    " • " + (worstSec / 60L) + " min";
            else report = worst + " • " + (worstSec / 60L) + " min";

            prefs.edit()
                    .putString("persistent_wakelock_pkg", worstPkg)
                    .putString("persistent_wakelock_candidate_pkg", suspiciousNow ? worstPkg : "")
                    .putInt("persistent_wakelock_confirmations", confirmations)
                    .putString("persistent_wakelock_report", report)
                    .putLong("persistent_wakelock_seconds", worstSec)
                    .putBoolean("persistent_wakelock_expected", termuxBusy || foregroundService)
                    .putBoolean("persistent_wakelock_unresolved", unresolved)
                    .apply();
        } catch (Throwable ignored) {}
    }

    private void restorePersistentWakeLockRestrictions(String activeSuspiciousPkg) {
        String prefix = "persistent_wakelock_previous_level_";
        String validLevels = "unrestricted|exempted|adaptive_bucket|restricted_bucket|background_restricted|hibernation";
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) continue;
            String pkg = key.substring(prefix.length());
            if (pkg.equals(activeSuspiciousPkg)
                    || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
                    || !(entry.getValue() instanceof String)) continue;
            String savedLevel = (String) entry.getValue();
            if (!savedLevel.matches(validLevels)) continue;

            try {
                String current = privileged.exec("cmd activity get-bg-restriction-level --user 0 " + pkg);
                current = current == null ? "" : current.trim();
                // Unknown state or a failed command must leave the recovery record intact.
                if (!current.matches(validLevels)) continue;
                boolean restore = "background_restricted".equals(current);
                if (restore) {
                    String output = privileged.exec("cmd activity set-bg-restriction-level --user 0 "
                            + pkg + " " + savedLevel + " && echo __WAKELOCK_RESTORE_OK__");
                    if (output == null || !output.trim().endsWith("__WAKELOCK_RESTORE_OK__")
                            || output.toLowerCase(Locale.US).matches(
                                    "(?s).*(error|exception|unknown command|permission denial).*")) continue;
                }
                // A different current level belongs to a newer policy; do not overwrite it.
                String report = AppSafety.label(context, pkg) + " (" + pkg + ")"
                        + ": restrição temporária por wakelock removida; "
                        + (restore ? "nível restaurado para " + savedLevel
                                   : "nível atual preservado: " + current);
                prefs.edit()
                        .remove(key)
                        .remove("persistent_wakelock_applied_at_" + pkg)
                        .putString("last_wakelock_restore", report)
                        .putLong("last_wakelock_restore_at", System.currentTimeMillis())
                        .apply();
                ChangeNotifier.notifyChange(context, "Restrição temporária de wakelock removida", report, 8);
            } catch (Throwable ignored) {
                // Retry this package on the next audit without blocking other restorations.
            }
        }
    }

    private void auditStuckSyncs() {
        try {
            String raw = privileged.exec(
                    "dumpsys content 2>/dev/null | sed -n '/Pending Syncs:/,/Periodic Syncs:/p' | head -120");
            if (raw == null) return;
            String report = "";
            boolean stuck = false;
            Pattern p = Pattern.compile(
                    "JobId=[^\\n]*?([A-Za-z0-9_.]+)\\] [^\\n]*ExpectedIn=-([0-9]+)([hm])([0-9]*)");
            Matcher m = p.matcher(raw);
            if (m.find()) {
                String pkg = m.group(1);
                long n = parseLong(m.group(2), 0L);
                String unit = m.group(3);
                long min = "h".equals(unit) ? n * 60L : n;
                if (min >= 30L) {
                    stuck = true;
                    report = AppSafety.label(context, pkg) + " • pendente há ~" + min + " min";
                }
            }
            prefs.edit()
                    .putBoolean("stuck_sync_detected", stuck)
                    .putString("stuck_sync_report", report)
                    .apply();
        } catch (Throwable ignored) {}
    }

    private void auditSensorConnections(boolean interactive, String foreground) {
        try {
            String raw = privileged.exec(
                    "dumpsys sensorservice 2>/dev/null | " +
                    "sed -n '/Active sensors:/,/Socket Buffer size/p' | head -80; " +
                    "echo __CONNECTIONS__; " +
                    "dumpsys sensorservice 2>/dev/null | " +
                    "sed -n '/Connection Number:/,/Previous Registrations:/p' | head -260");
            int active = 0, open = 0, fastestMs = -1;
            LinkedHashSet<String> backgroundApps = new LinkedHashSet<>();
            if (raw != null) {
                Matcher a = Pattern.compile(
                        "(?m)^.+[(]handle=0x[0-9A-Fa-f]+, connections=([0-9]+)[)]$").matcher(raw);
                while (a.find()) {
                    int c = (int) parseLong(a.group(1), 0L);
                    if (c > 0) active++;
                }
                Matcher o = Pattern.compile("(?m)^Connection Number:").matcher(raw);
                while (o.find()) open++;

                Matcher c = Pattern.compile(
                        "(?m)^[ \t]*([A-Za-z][A-Za-z0-9_.]+) [|] WakeLockRefCount [0-9]+ [|] uid [0-9]+").matcher(raw);
                while (c.find()) {
                    String candidate = c.group(1);
                    if (candidate.equals(foreground)) continue;
                    if (AppSafety.isEligibleForAdaptiveOptimization(context, candidate)) {
                        backgroundApps.add(candidate);
                    }
                }
            }

            String candidate = backgroundApps.isEmpty() ? "" : backgroundApps.iterator().next();
            int streak = 0;
            boolean suspected = false;
            if (!interactive && !candidate.isEmpty()) {
                String old = prefs.getString("bg_sensor_candidate", "");
                streak = candidate.equals(old) ? prefs.getInt("bg_sensor_streak", 0) + 1 : 1;
                suspected = streak >= 2;
            }

            SharedPreferences.Editor e = prefs.edit()
                    .putInt("system_sensor_active_count", active)
                    .putInt("system_sensor_open_connections", open)
                    .putInt("system_sensor_fastest_ms", fastestMs)
                    .putString("bg_sensor_candidate", candidate)
                    .putInt("bg_sensor_streak", streak)
                    .putBoolean("background_sensor_suspected", suspected);

            if (suspected) {
                e.putString("background_sensor_report",
                        AppSafety.label(context, candidate) + " mantém sensores ativos com a tela apagada");
            } else {
                e.remove("background_sensor_report");
            }
            e.apply();
        } catch (Throwable ignored) {}
    }

    private Map<String,String> parseKeyValues(String raw) {
        Map<String,String> out = new HashMap<>();
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }


    private boolean isTermuxWorkActive() {
        try {
            String raw = privileged.exec("ps -A -o NAME,ARGS 2>/dev/null | grep -E 'tunnel-client|cloudflared|secure-mcp|mauricio-mcp|android-mcp-server|remote_control_bridge|codex app-server|qwen|ffmpeg' | head -20");
            return raw != null && !raw.trim().isEmpty();
        } catch (Throwable t) {
            return cachedAutomationActive;
        }
    }

    private boolean hasForegroundService(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            String safe = pkg.replaceAll("[^A-Za-z0-9_.]", "");
            String raw = privileged.exec("dumpsys activity services " + safe + " 2>/dev/null | grep -m1 'isForeground=true'");
            return raw != null && raw.contains("isForeground=true");
        } catch (Throwable t) {
            return false;
        }
    }

    private long parseDurationSeconds(String text) {
        if (text == null) return 0L;
        long total = 0L;
        Matcher h = Pattern.compile("([0-9]+)h").matcher(text);
        if (h.find()) total += parseLong(h.group(1), 0L) * 3600L;
        Matcher m = Pattern.compile("([0-9]+)m(?!s)").matcher(text);
        if (m.find()) total += parseLong(m.group(1), 0L) * 60L;
        Matcher s = Pattern.compile("([0-9]+)s").matcher(text);
        if (s.find()) total += parseLong(s.group(1), 0L);
        return total;
    }

    private boolean isAutomationPackage(String pkg) {
        return pkg.startsWith("com.termux")
                || "moe.shizuku.privileged.api".equals(pkg)
                || "com.coremate.opengui".equals(pkg);
    }

    private boolean isRealtimePackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.US);
        return p.contains("waze") || p.contains("maps") || p.contains("camera")
                || p.contains("navigation") || p.contains("location");
    }

    private boolean isLongScreenPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.US);
        return p.contains("youtube") || p.contains("video") || p.contains("vibes")
                || p.contains("musically") || p.contains("tiktok")
                || p.contains("waze") || p.contains("maps") || p.contains("camera")
                || p.contains("game") || p.contains("gaming");
    }

    private long parseLong(String s, long fallback) {
        try { return Long.parseLong(s == null ? "" : s.trim()); }
        catch (Throwable t) { return fallback; }
    }
}

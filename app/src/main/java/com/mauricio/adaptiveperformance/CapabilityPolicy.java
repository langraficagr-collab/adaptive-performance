package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;

/** Real Shizuku-shell probes. Read-only: never changes kernel or radio state. */
final class CapabilityPolicy {
    private static final long INTERVAL_MS = 30L * 60L * 1000L;
    private CapabilityPolicy() {}

    static void reconcile(SharedPreferences p, IPrivilegedService s) {
        long now = System.currentTimeMillis();
        if (s == null) {
            if (p.getBoolean("cap_shell_ready", false))
                p.edit().putBoolean("cap_shell_ready", false).apply();
            return;
        }
        long checkedAt = p.getLong("cap_checked_at", 0L);
        if (checkedAt > 0L && now - checkedAt < INTERVAL_MS) return;
        try {
            // A single short call avoids shell-spawning overhead on every sample.
            String out = s.exec("echo UID:$(id -u); " +
                    "test -r /proc/pressure/memory && echo PSI_OK; " +
                    "test -w /sys/block/zram0/comp_algorithm && echo ZRAM_RW; " +
                    "test -w /sys/class/devfreq/13000000.mali/max_freq && echo GPU_RW; " +
                    "test -w /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq && echo CPU_RW; " +
                    "settings get global location_background_throttle_interval_ms");
            if (out == null) return;
            boolean shell = out.contains("UID:2000");
            boolean psi = out.contains("PSI_OK");
            SharedPreferences.Editor e = p.edit()
                    .putLong("cap_checked_at", now)
                    .putBoolean("cap_shell_ready", shell)
                    .putBoolean("cap_psi", psi)
                    .putBoolean("cap_zram_write", out.contains("ZRAM_RW"))
                    .putBoolean("cap_gpu_write", out.contains("GPU_RW"))
                    .putBoolean("cap_cpu_write", out.contains("CPU_RW"))
                    // Reading a modem mask does not prove write authorization.
                    .putBoolean("cap_radio_switch", false);
            if (!shell) {
                e.putBoolean("memory_compaction_enabled", false)
                    .putBoolean("signal_optimizer_enabled", false)
                    .putBoolean("gps_battery_saver_enabled", false);
            }
            // Unsupported sysfs or OEM-only controls must not appear to work.
            if (!out.contains("ZRAM_RW")) e.putString("zram_profile", "normal");
            e.putString("thermal_mode", "auto");
            e.putBoolean("smart_auto_cleanup", false);
            e.apply();
        } catch (Exception ignored) {
            p.edit().putBoolean("cap_shell_ready", false).apply();
        }
    }

    /** Only sample features that can actually take effect in the current privilege mode. */
    static boolean optionSupported(SharedPreferences p, String key) {
        if (key.equals("advanced_adaptive") || key.equals("extended_diagnostics") || key.equals("diagnostic_burst")
                || key.equals("sensor_modem_guard") || key.equals("duplicate_detection")
                || key.equals("storage_guard") || key.equals("history_24h")) return false;
        boolean shell = p.getBoolean("cap_shell_ready", false);
        if (key.equals("cpu_pressure_control") || key.equals("system_battery_guard")
                || key.equals("dynamic_doze_whitelist") || key.equals("system_radio_savings")
                || key.equals("adaptive_screen_timeout") || key.equals("maximum_battery_mode"))
            return shell;
        return true;
    }
}

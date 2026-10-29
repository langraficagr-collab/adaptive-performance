package com.mauricio.adaptiveperformance;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import java.util.Locale;
/** Process CPU and polling counters; wakeups and energy are proxies, not OS battery stats. */
final class AppCostGuard {
    private long lastWall, lastCpu, windowWall, windowCpu;
    private int cycles;
    private boolean throttle;
    long record(SharedPreferences p, boolean interactive, boolean urgent) {
        long wall = SystemClock.elapsedRealtime(), cpu = Process.getElapsedCpuTime();
        if (lastWall > 0 && wall > lastWall && cpu >= lastCpu) {
            long elapsed = wall - lastWall;
            if (elapsed < 900000L) {
                windowWall += elapsed;
                windowCpu += cpu - lastCpu;
            }
        }
        lastWall = wall;
        lastCpu = cpu;
        cycles++;
        if (windowWall >= 600000L) {
            float pct = 100f * windowCpu / Math.max(1L, windowWall);
            // Process CPU time is measurable; battery energy is not inferable from it.
            throttle = pct > 3f;
            p.edit().putFloat("app_cost_cpu_pct", pct)
                .putFloat("app_cost_estimated_mwh", -1f)
                .putInt("app_cost_poll_count", cycles)
                .putBoolean("app_cost_throttled", throttle)
                .putString("app_cost_status", String.format(Locale.US,
                    "CPU %.2f%% • %d ms de CPU / janela • %d verificações • %s",
                    pct, windowCpu, cycles, throttle ? "cadência reduzida" : "normal"))
                .apply();
            windowWall = windowCpu = 0L;
            cycles = 0;
        }
        return throttle && !urgent ? (interactive ? 180000L : 600000L) : 0L;
    }
}

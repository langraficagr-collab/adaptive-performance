package com.mauricio.adaptiveperformance;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import java.util.Locale;
/** CPU of the app + bridge/reaped shell children, plus shell latency; not battery energy. */
final class AppCostGuard {
    private long lastWall, lastCpu, windowWall, windowCpu;
    private long[] lastRemote;
    private long windowRemoteCpu, windowShellMs, windowCalls, windowTimeouts;
    private int cycles;
    private boolean throttle;
    private boolean remoteComplete;

    long record(SharedPreferences p, boolean interactive, boolean urgent, IPrivilegedService bridge) {
        long wall = SystemClock.elapsedRealtime(), cpu = Process.getElapsedCpuTime();
        long[] remote = null;
        if (bridge != null) try { remote = bridge.costStats(); } catch (Exception ignored) { }
        boolean valid = lastWall > 0 && wall > lastWall && cpu >= lastCpu
                && wall - lastWall < 900_000L;
        if (valid) {
            windowWall += wall - lastWall;
            windowCpu += cpu - lastCpu;
            if (remote != null && remote.length == 6 && lastRemote != null
                    && lastRemote.length == 6 && remote[0] == lastRemote[0]
                    && remote[1] >= lastRemote[1] && remote[3] >= lastRemote[3]
                    && remote[4] >= lastRemote[4] && remote[5] >= lastRemote[5]) {
                windowRemoteCpu += remote[1] - lastRemote[1];
                if (remote[2] >= 0 && lastRemote[2] >= 0 && remote[2] >= lastRemote[2])
                    windowRemoteCpu += remote[2] - lastRemote[2];
                else remoteComplete = false;
                windowCalls += remote[3] - lastRemote[3];
                windowShellMs += remote[4] - lastRemote[4];
                windowTimeouts += remote[5] - lastRemote[5];
            } else remoteComplete = false;
        } else if (lastWall != 0) {
            windowWall = windowCpu = windowRemoteCpu = windowShellMs = windowCalls = windowTimeouts = 0L;
            cycles = 0;
            remoteComplete = false;
        } else remoteComplete = remote != null && remote.length == 6 && remote[2] >= 0;
        lastWall = wall;
        lastCpu = cpu;
        lastRemote = remote;
        cycles++;
        if (windowWall >= 600_000L) {
            float appPct = 100f * windowCpu / windowWall;
            float bridgePct = 100f * windowRemoteCpu / windowWall;
            float shellPct = 100f * windowShellMs / windowWall;
            throttle = appPct + bridgePct > 3.0f || shellPct > 5f || windowTimeouts > 0;
            p.edit().putFloat("app_cost_cpu_pct", appPct)
                .putFloat("app_cost_bridge_cpu_pct", bridgePct)
                .putFloat("app_cost_total_cpu_pct", appPct + bridgePct)
                .putLong("app_cost_shell_calls", windowCalls)
                .putLong("app_cost_shell_ms", windowShellMs)
                .putLong("app_cost_shell_timeouts", windowTimeouts)
                .putBoolean("app_cost_bridge_complete", remoteComplete)
                .putFloat("app_cost_estimated_mwh", -1f)
                .putInt("app_cost_poll_count", cycles)
                .putBoolean("app_cost_throttled", throttle)
                .putString("app_cost_status", String.format(Locale.US,
                    "CPU app %.2f%% + ponte/shell %.2f%% • %d comandos / %d ms • %s%s",
                    appPct, bridgePct, windowCalls, windowShellMs,
                    throttle ? "cadência reduzida" : "normal", remoteComplete ? "" : " • leitura parcial"))
                .putString("app_cost_scope", "CPU medida e tempo dos comandos; trabalho provocado em serviços do sistema não é integralmente atribuível; não mede energia.")
                .apply();
            windowWall = windowCpu = windowRemoteCpu = windowShellMs = windowCalls = windowTimeouts = 0L;
            cycles = 0;
            remoteComplete = remote != null && remote.length == 6 && remote[2] >= 0;
        }
        return throttle && !urgent ? (interactive ? 180_000L : 600_000L) : 0L;
    }
}

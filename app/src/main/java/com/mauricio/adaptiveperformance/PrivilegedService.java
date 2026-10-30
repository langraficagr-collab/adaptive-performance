package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class PrivilegedService extends IPrivilegedService.Stub {
    private final long instance = android.os.SystemClock.elapsedRealtimeNanos();
    private final java.util.concurrent.atomic.AtomicLong calls = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong shellWallMs = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong timeouts = new java.util.concurrent.atomic.AtomicLong();

    public PrivilegedService() { cleanupSiblingServices(); }

    private void cleanupSiblingServices() {
        try {
            int self = Process.myPid();
            String name = BuildConfig.APPLICATION_ID + ":optimizer";
            String cmd = "for p in $(ps -A -o PID,NAME 2>/dev/null | awk '$2==\"" + name + "\"{print $1}'); do " +
                    "[ \"$p\" = \"" + self + "\" ] || kill \"$p\" 2>/dev/null; done";
            java.lang.Process proc = new ProcessBuilder("/system/bin/sh", "-c", cmd).start();
            proc.waitFor(1, TimeUnit.SECONDS);
        } catch (Throwable ignored) {}
    }

    @Override public int remoteUid() { return Process.myUid(); }

    @Override public String exec(String command) {
        long start = android.os.SystemClock.elapsedRealtime();
        calls.incrementAndGet();
        try {
            String result = ShellCommandRunner.run("/system/bin/sh", command, 5_000L);
            if (result.endsWith("[timeout]")) timeouts.incrementAndGet();
            return result;
        } finally {
            shellWallMs.addAndGet(Math.max(0L, android.os.SystemClock.elapsedRealtime() - start));
        }
    }

    @Override public long[] costStats() {
        // proc stat includes CPU of reaped shell children; no extra shell is spawned.
        long childCpu = -1L;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String stat = r.readLine();
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+");
            long ticks = Math.max(0L, Long.parseLong(fields[13]))
                    + Math.max(0L, Long.parseLong(fields[14]));
            long hz = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK);
            if (hz > 0L) childCpu = ticks * 1000L / hz;
        } catch (Exception ignored) { }
        return new long[]{instance, Process.getElapsedCpuTime(), childCpu,
                calls.get(), shellWallMs.get(), timeouts.get()};
    }

    @Override public void destroy() { System.exit(0); }
}

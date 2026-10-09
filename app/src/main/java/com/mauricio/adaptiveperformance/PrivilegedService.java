package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class PrivilegedService extends IPrivilegedService.Stub {
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
        return ShellCommandRunner.run("/system/bin/sh", command, 5_000L);
    }

    @Override public void destroy() { System.exit(0); }
}

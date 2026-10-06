package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class PrivilegedService extends IPrivilegedService.Stub {
    public PrivilegedService() { cleanupSiblingServices(); }
    public PrivilegedService(Context context) { cleanupSiblingServices(); }

    private void cleanupSiblingServices() {
        try {
            int self = Process.myPid();
            String name = "com.mauricio.adaptiveperformance:optimizer";
            String cmd = "for p in $(ps -A -o PID,NAME 2>/dev/null | awk '$2==\"" + name + "\"{print $1}'); do " +
                    "[ \"$p\" = \"" + self + "\" ] || kill \"$p\" 2>/dev/null; done";
            java.lang.Process proc = new ProcessBuilder("/system/bin/sh", "-c", cmd).start();
            proc.waitFor(1, TimeUnit.SECONDS);
        } catch (Throwable ignored) {}
    }

    @Override public int remoteUid() { return Process.myUid(); }

    @Override public String exec(String command) {
        StringBuilder out = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            java.lang.Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (out.length() < 65536) out.append(line).append('\n');
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                out.append("[timeout]");
            }
        } catch (Throwable t) {
            out.append("[error] ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return out.toString().trim();
    }

    @Override public void destroy() { System.exit(0); }
}

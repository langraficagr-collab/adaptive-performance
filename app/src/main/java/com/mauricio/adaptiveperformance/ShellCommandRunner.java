package com.mauricio.adaptiveperformance;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs shell commands while bounding both runtime and captured output. */
final class ShellCommandRunner {
    private static final int MAX_OUTPUT_BYTES = 65536;

    static String run(String shell, String command, long timeoutMs) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean stop = new AtomicBoolean();
        java.lang.Process process = null;
        Thread reader = null;
        String suffix = "";
        try {
            ProcessBuilder builder = new ProcessBuilder(shell, "-c", command);
            builder.redirectErrorStream(true);
            process = builder.start();
            process.getOutputStream().close();
            final java.lang.Process child = process;
            final InputStream stream = child.getInputStream();
            reader = new Thread(() -> {
                byte[] buffer = new byte[8192];
                try {
                    while (!stop.get()) {
                        int count = stream.read(buffer);
                        if (count < 0) break;
                        synchronized (output) {
                            int keep = Math.min(count, MAX_OUTPUT_BYTES - output.size());
                            if (keep > 0) output.write(buffer, 0, keep);
                        }
                    }
                } catch (java.io.IOException ignored) {
                    // Destruction or closing the pipe can race with a read.
                }
            }, "adaptive-shell-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                suffix = "[timeout]";
                process.destroyForcibly();
            } else {
                // Drain available output without waiting for inherited child pipes.
                reader.join(200L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            suffix = "[error] InterruptedException";
        } catch (Exception e) {
            suffix = "[error] " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            stop.set(true);
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                if (reader != null) reader.interrupt();
                try { process.getInputStream().close(); } catch (Exception ignored) {}
                try { process.getErrorStream().close(); } catch (Exception ignored) {}
                try { process.getOutputStream().close(); } catch (Exception ignored) {}
            }
        }
        synchronized (output) {
            return (new String(output.toByteArray(), StandardCharsets.UTF_8) + suffix).trim();
        }
    }
}

package com.mauricio.adaptiveperformance;

import java.util.*;

/** Counts commands, not interpreter names. Only whitespace is normalized. */
public final class DuplicateProcessDetector {
    private final Map<String, Integer> streaks = new HashMap<>();
    public synchronized Map<String, Integer> scan(String ps) {
        Map<String, Integer> counts = new TreeMap<>();
        if (ps != null) for (String row : ps.split("\n")) {
            String[] cols = row.trim().split("\\s+", 3);
            if (cols.length != 3 || !cols[0].matches("[0-9]+")) continue;
            String command = cols[2].trim();
            String[] args = command.split("\\s+");
            String exe = args[0].substring(args[0].lastIndexOf('/') + 1);
            if (exe.matches("(sh|bash|dash|zsh|env|timeout|nohup|grep|egrep|ps|sort|sed|awk|xargs|tee)")) continue;
            boolean interpreter = exe.matches("python[0-9.]*|node|nodejs|bun|deno");
            boolean automation = exe.matches("cloudflared|appium|uiautomator|qwen|codex|mcp.*");
            if (!interpreter && !automation) continue;
            // Bare interpreters and worker/resource-tracker roles are not actionable duplicates.
            if (args.length < 2 || command.contains("multiprocessing.resource_tracker")
                    || command.contains("multiprocessing.spawn") || command.contains("--type=")) continue;
            String signature = command.replaceAll("\\s+", " ");
            counts.put(signature, counts.getOrDefault(signature, 0) + 1);
        }
        streaks.keySet().retainAll(counts.keySet());
        Map<String, Integer> confirmed = new TreeMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int streak = e.getValue() > 1 ? Math.min(3, streaks.getOrDefault(e.getKey(), 0) + 1) : 0;
            streaks.put(e.getKey(), streak);
            if (streak >= 3) confirmed.put(e.getKey(), e.getValue());
        }
        return confirmed;
    }
}

package com.mauricio.adaptiveperformance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Safety bounds for reading APK bytes into Android's reclaimable filesystem page cache.
 * These are I/O limits, NOT process RSS reservations or enforced Android RAM quotas.
 */
final class PreloadAllocationPolicy {
    static final int MAX_APPS = 8;
    static final int MIN_PER_APP_MB = 8;
    static final int MAX_PER_APP_MB = 128;
    static final int MAX_TOTAL_MB = 256;

    private PreloadAllocationPolicy() {}

    static LinkedHashMap<String, Integer> allocate(List<String> candidates,
              Map<String, Integer> preferredMb, int configuredCount, int thermalBudgetMb) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        if (candidates == null || candidates.isEmpty() || thermalBudgetMb < MIN_PER_APP_MB)
            return result;
        int budget = Math.min(MAX_TOTAL_MB, thermalBudgetMb);
        int limit = Math.min(Math.max(0, configuredCount), MAX_APPS);
        limit = Math.min(limit, budget / MIN_PER_APP_MB);
        if (limit == 0) return result;

        List<String> chosen = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String pkg : candidates) {
            if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
                    || !seen.add(pkg)) continue;
            chosen.add(pkg);
            if (chosen.size() >= limit) break;
        }
        int remaining = budget;
        for (int i = 0; i < chosen.size(); i++) {
            String pkg = chosen.get(i);
            int desired = preferredMb == null ? 32 : preferredMb.getOrDefault(pkg, 32);
            desired = Math.max(MIN_PER_APP_MB, Math.min(MAX_PER_APP_MB, desired));
            int fair = remaining / (chosen.size() - i);
            int taken = Math.min(desired, fair) / MIN_PER_APP_MB * MIN_PER_APP_MB;
            if (taken >= MIN_PER_APP_MB) {
                result.put(pkg, taken);
                remaining -= taken;
            }
        }
        // Use spare budget without crossing any user's per-app request.
        while (remaining >= MIN_PER_APP_MB) {
            boolean added = false;
            for (String pkg : result.keySet()) {
                int desired = preferredMb == null ? 32 : preferredMb.getOrDefault(pkg, 32);
                desired = Math.max(MIN_PER_APP_MB, Math.min(MAX_PER_APP_MB, desired));
                if (result.get(pkg) + MIN_PER_APP_MB > desired) continue;
                result.put(pkg, result.get(pkg) + MIN_PER_APP_MB);
                remaining -= MIN_PER_APP_MB;
                added = true;
                if (remaining < MIN_PER_APP_MB) break;
            }
            if (!added) break;
        }
        return result;
    }
}

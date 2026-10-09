package com.mauricio.adaptiveperformance;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
/** Recent background-only candidates; never relies on a hardcoded list. */
final class TrimCandidateSelector {
    static List<String> candidates(Context c, SharedPreferences p, String foreground, int max) {
        UsageStatsManager usage = (UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usage == null) return Collections.emptyList();
        long now = System.currentTimeMillis();
        Map<String,UsageStats> records;
        try { records = usage.queryAndAggregateUsageStats(now - 7L*86400000L, now); }
        catch (Exception e) { return Collections.emptyList(); }
        if (records == null || records.isEmpty()) return Collections.emptyList();
        List<UsageStats> sorted = new ArrayList<>(records.values());
        sorted.sort((a,b)->Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        Set<String> preloaded = p.getStringSet("auto_preload_apps", Collections.emptySet());
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (UsageStats stat : sorted) {
            String pkg = stat.getPackageName();
            if (pkg == null || pkg.isEmpty() || pkg.equals(foreground)
                    || pkg.equals(c.getPackageName()) || preloaded.contains(pkg)
                    || pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.")
                    || AppSafety.isCritical(c, pkg) || AppSafety.isAutoProtected(c, pkg)
                    || AppProfilePolicy.protectedActive(c, p, pkg)) continue;
            selected.add(pkg);
            if (selected.size() == max) break;
        }
        return new ArrayList<>(selected);
    }
}

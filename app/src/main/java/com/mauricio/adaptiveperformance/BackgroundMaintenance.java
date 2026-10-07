package com.mauricio.adaptiveperformance;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.text.DateFormat;
import java.util.*;
import java.util.regex.*;

public class BackgroundMaintenance {
    private static final long BATTERY_INTERVAL = 30L * 60L * 1000L;
    private static final long BG_SCAN_INTERVAL = 10L * 60L * 1000L;
    private static final long RAM_SCAN_INTERVAL = 60_000L;
    private static final long UNUSED_SCAN_INTERVAL = 6L * 60L * 60L * 1000L;
    private static final long UNUSED_AGE = 3L * 24L * 60L * 60L * 1000L;
    private static final double HOT_CPU = 30.0;

    private final Context context;
    private final SharedPreferences prefs;
    private final IPrivilegedService privileged;
    private final PackageManager pm;
    private final Map<String,Integer> hotCounts = new HashMap<>();
    private final Map<String,Integer> highRamCounts = new HashMap<>();
    private long lastProtectedExceptionCheck = 0L;

    public BackgroundMaintenance(Context context, SharedPreferences prefs, IPrivilegedService privileged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.privileged = privileged;
        this.pm = context.getPackageManager();
        if (!prefs.getBoolean("inactivity_3day_v1", false)) {
            prefs.edit().putLong("last_unused_scan", 0).putBoolean("inactivity_3day_v1", true).apply();
        }
    }

    public void initialize() {
        ensureUsageAccess();
        recoverTrackedRestrictions();
    }

    public void maybeRun(String foregroundPackage) {
        long now = System.currentTimeMillis();
        if (!prefs.getBoolean("restriction_recovery_v2", false)) {
            try {
                recoverTrackedRestrictions();
                prefs.edit().putBoolean("restriction_recovery_v2", true).apply();
            } catch (Throwable ignored) {}
        }
        if (!prefs.getBoolean("restriction_safety_v3", false)) {
            try {
                recoverTrackedRestrictions();
                prefs.edit().putBoolean("restriction_safety_v3", true).apply();
            } catch (Throwable ignored) {}
        }
        if (now - lastProtectedExceptionCheck >= 60_000L) {
            try { maybeUnrestrictProtectedExceptions(); } catch (Throwable ignored) {}
            lastProtectedExceptionCheck = now;
        }
        try { maybeUnrestrictForeground(foregroundPackage); } catch (Throwable ignored) {}

        long lastBattery = prefs.getLong("last_battery_rank", 0);
        if (now - lastBattery >= BATTERY_INTERVAL) {
            try { updateBatteryRanking(); } catch (Throwable t) { log("Falha no ranking de bateria: " + shortErr(t)); }
            prefs.edit().putLong("last_battery_rank", now).apply();
        }

        if (prefs.getBoolean("auto_bug_cleanup", true)) {
            long lastBg = prefs.getLong("last_bg_scan", 0);
            if (now - lastBg >= BG_SCAN_INTERVAL) {
                try { scanBackgroundAnomalies(foregroundPackage); } catch (Throwable t) { log("Falha na verificação de segundo plano: " + shortErr(t)); }
                prefs.edit().putLong("last_bg_scan", now).apply();
            }
        }

        if (prefs.getBoolean("app_ram_limiter_enabled", false)) {
            long lastRam = prefs.getLong("last_app_ram_scan", 0);
            if (now - lastRam >= RAM_SCAN_INTERVAL) {
                try { scanHighRamApps(foregroundPackage); } catch (Throwable t) { log("Falha no limitador de RAM: " + shortErr(t)); }
                prefs.edit().putLong("last_app_ram_scan", now).apply();
            }
        }

        if (prefs.getBoolean("auto_unused_restrict", true)) {
            long lastUnused = prefs.getLong("last_unused_scan", 0);
            if (now - lastUnused >= UNUSED_SCAN_INTERVAL) {
                try { scanUnusedApps(); } catch (Throwable t) { log("Falha na verificação de apps sem uso: " + shortErr(t)); }
                prefs.edit().putLong("last_unused_scan", now).apply();
            }
        }
    }

    private void ensureUsageAccess() {
        try {
            privileged.exec("appops set " + context.getPackageName() + " GET_USAGE_STATS allow");
        } catch (Throwable ignored) {}
    }

    private void updateBatteryRanking() throws Exception {
        String raw = privileged.exec(
                "dumpsys batterystats --usage 2>/dev/null | " +
                "grep -E '^    UID (u[0-9]+a[0-9]+|[0-9]+):' | head -100");
        Map<Integer, BatteryItem> power = new HashMap<>();
        Pattern p = Pattern.compile("^\\s*UID\\s+(u(\\d+)a(\\d+)|(\\d+)):\\s+([0-9.]+)", Pattern.MULTILINE);
        Matcher m = p.matcher(raw == null ? "" : raw);
        while (m.find()) {
            int uid;
            if (m.group(4) != null) {
                uid = Integer.parseInt(m.group(4));
            } else {
                int user = Integer.parseInt(m.group(2));
                int appId = Integer.parseInt(m.group(3));
                uid = user * 100000 + 10000 + appId;
            }
            double mah = Double.parseDouble(m.group(5));
            power.put(uid, new BatteryItem(uid, mah));
        }

        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        Map<Integer,List<String>> uidPackages = new HashMap<>();
        for (ApplicationInfo ai : apps) {
            uidPackages.computeIfAbsent(ai.uid, k -> new ArrayList<>()).add(ai.packageName);
        }

        List<RankedApp> ranked = new ArrayList<>();
        for (BatteryItem bi : power.values()) {
            List<String> pkgs = uidPackages.get(bi.uid);
            if (pkgs == null || pkgs.isEmpty()) continue;
            String pkg = pkgs.get(0);
            ranked.add(new RankedApp(pkg, bi.mah));
        }
        ranked.sort((a,b) -> Double.compare(b.mah, a.mah));

        StringBuilder sb = new StringBuilder();
        int n = Math.min(6, ranked.size());
        for (int i=0; i<n; i++) {
            RankedApp r = ranked.get(i);
            if (i > 0) sb.append('\n');
            sb.append(i+1).append(". ").append(label(r.pkg))
              .append(" — ").append(String.format(Locale.US, "%.3f mAh", r.mah));
        }
        if (sb.length() == 0) sb.append("Ainda não há dados suficientes.");
        prefs.edit()
                .putString("battery_rank", sb.toString())
                .putLong("battery_rank_time", System.currentTimeMillis())
                .apply();
    }

    private void scanBackgroundAnomalies(String foregroundPackage) throws Exception {
        Set<String> thirdParty = thirdPartyPackages();
        String top = privileged.exec("top -b -n 1 -o PID,%CPU,ARGS 2>/dev/null");
        Pattern row = Pattern.compile("^\\s*\\d+\\s+([0-9.]+)\\s+([^\\s]+)", Pattern.MULTILINE);
        Matcher m = row.matcher(top == null ? "" : top);
        Map<String,Double> sums = new HashMap<>();

        while (m.find()) {
            double cpu;
            try { cpu = Double.parseDouble(m.group(1)); } catch (Exception e) { continue; }
            String proc = m.group(2);
            String pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
            if (!thirdParty.contains(pkg)) continue;
            sums.put(pkg, sums.getOrDefault(pkg, 0.0) + cpu);
        }

        Set<String> hotNow = new HashSet<>();
        for (Map.Entry<String,Double> e : sums.entrySet()) {
            String pkg = e.getKey();
            double cpu = e.getValue();
            if (cpu < HOT_CPU) continue;
            if (pkg.equals(foregroundPackage) || isProtected(pkg)) continue;
            if (hasForegroundService(pkg)) continue;
            hotNow.add(pkg);
            int count = hotCounts.getOrDefault(pkg, 0) + 1;
            hotCounts.put(pkg, count);
            if (count >= 2) {
                if (!RestrictionGuard.claim(prefs, pkg, "maintenance", "CPU anomaly")) continue;
                boolean stopped = RestrictionGuard.command(privileged, "am force-stop --user 0 " + pkg);
                RestrictionGuard.release(prefs, pkg, "maintenance");
                if (!stopped) continue;
                String msg = "Finalizado " + label(pkg) + ": CPU " +
                        String.format(Locale.US, "%.0f%%", cpu) +
                        " em segundo plano em 2 verificações.";
                log(msg);
                ChangeNotifier.notifyChange(context, "App anormal finalizado", msg, 7);
                hotCounts.remove(pkg);
            }
        }

        for (String pkg : new ArrayList<>(hotCounts.keySet())) {
            if (!hotNow.contains(pkg)) hotCounts.remove(pkg);
        }
    }

    private void scanHighRamApps(String foregroundPackage) throws Exception {
        int defaultLimitMb = Math.max(100, Math.min(1500, prefs.getInt("app_ram_limit_mb", 500)));
        Set<String> thirdParty = thirdPartyPackages();
        String raw = privileged.exec("ps -A -o RSS,ARGS 2>/dev/null");
        if (raw == null || raw.isEmpty()) return;

        Map<String,Long> rssByPkg = new HashMap<>();
        Pattern row = Pattern.compile("^\\s*(\\d+)\\s+([^\\s]+)", Pattern.MULTILINE);
        Matcher m = row.matcher(raw);
        while (m.find()) {
            long rss;
            try { rss = Long.parseLong(m.group(1)); } catch (Exception e) { continue; }
            String proc = m.group(2);
            String pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
            if (!thirdParty.contains(pkg)) continue;
            rssByPkg.put(pkg, rssByPkg.getOrDefault(pkg, 0L) + rss);
        }

        Set<String> highNow = new HashSet<>();
        for (Map.Entry<String,Long> e : rssByPkg.entrySet()) {
            String pkg = e.getKey();
            long rssKb = e.getValue();
            int limitMb = SmartRecommendationSuite.ramLimitFor(prefs, pkg, defaultLimitMb);
            long limitKb = limitMb * 1024L;
            if (rssKb < limitKb) continue;
            if (pkg.equals(foregroundPackage) || isProtected(pkg) || hasForegroundService(pkg)) continue;
            highNow.add(pkg);
            int count = highRamCounts.getOrDefault(pkg, 0) + 1;
            highRamCounts.put(pkg, count);

            if (count == 1) {
                RestrictionGuard.command(privileged, "am set-standby-bucket " + pkg + " restricted");
                log("RAM alta: " + label(pkg) + " — " + (rssKb / 1024L) + " MB; standby restrito aplicado.");
            } else if (count >= 2) {
                if (!RestrictionGuard.claim(prefs, pkg, "ram_limiter", "RAM excessiva")) continue;
                boolean stopped = RestrictionGuard.command(privileged, "am force-stop --user 0 " + pkg);
                RestrictionGuard.release(prefs, pkg, "ram_limiter");
                if (stopped) {
                    prefs.edit()
                            .putString("app_ram_limiter_last_pkg", pkg)
                            .putLong("app_ram_limiter_last_mb", rssKb / 1024L)
                            .putLong("app_ram_limiter_last_at", System.currentTimeMillis())
                            .apply();
                    log("RAM excessiva: " + label(pkg) + " finalizado após 2 verificações — " + (rssKb / 1024L) + " MB.");
                    highRamCounts.remove(pkg);
                }
            }
        }
        for (String pkg : new ArrayList<>(highRamCounts.keySet())) {
            if (!highNow.contains(pkg)) highRamCounts.remove(pkg);
        }
    }

    private void scanUnusedApps() throws Exception {
        ensureUsageAccess();
        long now = System.currentTimeMillis();
        long since = now - 45L * 24L * 60L * 60L * 1000L;

        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        Map<String,UsageStats> stats = usm == null ? Collections.emptyMap()
                : usm.queryAndAggregateUsageStats(since, now);

        Set<String> restricted = new HashSet<>(prefs.getStringSet("auto_restricted", Collections.emptySet()));
        Set<String> foregroundServices = activeForegroundServicePackages();
        int newlyRestricted = 0;

        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String pkg = ai.packageName;
            if (!isEligibleForAutoRestriction(pkg) || AppSafety.isSystemApp(context, pkg)
                    || pkg.equals(prefs.getString("foreground", ""))) continue;
            if (newlyRestricted >= AppProfilePolicy.batch(context, prefs, prefs.getString("foreground", ""), 4)) break;
            if (restricted.contains(pkg)) continue;
            if (foregroundServices.contains(pkg)) continue;

            PackageInfo pi;
            try { pi = pm.getPackageInfo(pkg, 0); } catch (Exception e) { continue; }
            if (now - pi.firstInstallTime < UNUSED_AGE) continue;

            UsageStats us = stats.get(pkg);
            long lastUse = 0;
            if (us != null) {
                lastUse = us.getLastTimeUsed();
                if (Build.VERSION.SDK_INT >= 29) lastUse = Math.max(lastUse, us.getLastTimeVisible());
            }
            if (lastUse > 0 && now - lastUse <= UNUSED_AGE) continue;

            if (!RestrictionGuard.claim(prefs, pkg, "maintenance", "unused")) continue;
            if (newlyRestricted >= AppProfilePolicy.batch(context, prefs, prefs.getString("foreground", ""), 4)) break;
            if (!RestrictionGuard.background(context, prefs, privileged, pkg)) continue;
            String oldBg = appOpMode(pkg, "RUN_IN_BACKGROUND");
            String oldAny = appOpMode(pkg, "RUN_ANY_IN_BACKGROUND");
            if (!oldBg.matches("allow|default") || !oldAny.matches("allow|default")) continue;
            String inactive = privileged.exec("am get-inactive --user 0 " + pkg);
            if (inactive == null || !inactive.trim().equals("Idle=false")) continue;
            if (!RestrictionGuard.claim(prefs, pkg, "maintenance", "unused app")) continue;
            restricted.add(pkg);
            if (!prefs.edit().putString("prev_bg_" + hash(pkg), oldBg)
                    .putString("prev_any_" + hash(pkg), oldAny)
                    .putStringSet("auto_restricted", new HashSet<>(restricted)).commit()) {
                restricted.remove(pkg); RestrictionGuard.release(prefs, pkg, "maintenance"); continue;
            }
            boolean ok = RestrictionGuard.command(privileged, "appops set --user 0 " + pkg + " RUN_IN_BACKGROUND ignore")
                    && RestrictionGuard.command(privileged, "appops set --user 0 " + pkg + " RUN_ANY_IN_BACKGROUND ignore");
            if (!ok) { restoreAutoRestriction(pkg, restricted); continue; }
            updateRestrictedText(restricted);
            IncidentHistory.add(prefs, "unused_app", pkg, "AppOps de segundo plano: ignore", -1);
            newlyRestricted++;
            log("Restrito em segundo plano: " + label(pkg) + " (sem uso há mais de 3 dias).");
        }

        prefs.edit().putStringSet("auto_restricted", restricted).apply();
        updateRestrictedText(restricted);
        if (newlyRestricted > 0) {
            ChangeNotifier.notifyChange(context, "Apps restringidos por inatividade",
                    newlyRestricted + " app(s), incluindo apps de sistema não críticos, foram restringidos após mais de 3 dias sem uso.", 8);
        }
        if (newlyRestricted == 0 && prefs.getString("maintenance_log", "").isEmpty()) {
            log("Verificação de apps sem uso concluída; nenhuma nova restrição.");
        }
    }

    private void maybeUnrestrictProtectedExceptions() throws Exception {
        Set<String> restricted = new HashSet<>(prefs.getStringSet("auto_restricted", Collections.emptySet()));
        if (restricted.isEmpty()) return;
        for (String pkg : new ArrayList<>(restricted)) {
            if (AppSafety.isAutoProtected(context, pkg)) {
                restoreAutoRestriction(pkg, restricted);
                log("Exceção do usuário restaurada: " + label(pkg) + ".");
            }
        }
    }

    private void maybeUnrestrictForeground(String pkg) throws Exception {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> restricted = new HashSet<>(prefs.getStringSet("auto_restricted", Collections.emptySet()));
        if (!restricted.contains(pkg)) return;

        restoreAutoRestriction(pkg, restricted);
        String msg = "Restrição automática removida ao abrir: " + label(pkg) + ".";
        log(msg);
        ChangeNotifier.notifyChange(context, "Restrição removida", msg, 8);
    }

    private boolean isEligibleForAutoRestriction(String pkg) {
        return AppSafety.isEligibleForAutomaticRestriction(context, pkg);
    }

    private void restoreAutoRestriction(String pkg, Set<String> restricted) throws Exception {
        if (!pkg.matches("[A-Za-z0-9_.]+")) return;
        String owner = prefs.getString("restriction_owner_" + pkg, "");
        if (!owner.isEmpty() && !owner.equals("maintenance")) return;
        for (String[] op : new String[][]{{"RUN_IN_BACKGROUND", "prev_bg_"}, {"RUN_ANY_IN_BACKGROUND", "prev_any_"}}) {
            String old = prefs.getString(op[1] + hash(pkg), "");
            String current = appOpMode(pkg, op[0]);
            if (!old.matches("allow|ignore|deny|default") || current.isEmpty()) return;
            if (current.equals("ignore") && !RestrictionGuard.command(privileged,
                    "appops set --user 0 " + pkg + " " + op[0] + " " + old)) return;
        }
        // Do not reset inactivity: legacy state cannot prove it still belongs to us.
        restricted.remove(pkg);
        prefs.edit()
                .putStringSet("auto_restricted", new HashSet<>(restricted))
                .remove("prev_bg_" + hash(pkg))
                .remove("prev_any_" + hash(pkg))
                .apply();
        updateRestrictedText(restricted);
        RestrictionGuard.release(prefs, pkg, "maintenance");
        IncidentHistory.add(prefs, "maintenance", pkg, "Restrição restaurada; alterações externas preservadas", -1);
    }

    private String appOpMode(String pkg, String op) {
        try {
            String out = privileged.exec("appops get --user 0 " + pkg + " " + op);
            Matcher m = Pattern.compile(Pattern.quote(op) + ":\\s*(allow|ignore|deny|default)").matcher(out == null ? "" : out);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}
        return "";
    }

    private String safeMode(String mode) {
        if ("allow".equals(mode) || "ignore".equals(mode) || "deny".equals(mode) || "default".equals(mode)) return mode;
        return "default";
    }

    private Set<String> activeForegroundServicePackages() {
        Set<String> out = new HashSet<>();
        try {
            String raw = privileged.exec("dumpsys activity services 2>/dev/null | " +
                    "grep -B30 'isForeground=true' | grep 'packageName=' | " +
                    "sed 's/.*packageName=//' | awk '{print $1}' | sort -u");
            if (raw != null) {
                for (String line : raw.split("\n")) {
                    String pkg = line.trim();
                    if (pkg.matches("[A-Za-z0-9_.]+")) out.add(pkg);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private boolean hasForegroundService(String pkg) {
        try {
            String s = privileged.exec("dumpsys activity services " + pkg +
                    " 2>/dev/null | grep -m1 'isForeground=true'");
            return s != null && s.contains("isForeground=true");
        } catch (Throwable t) { return false; }
    }

    private Set<String> thirdPartyPackages() {
        Set<String> out = new HashSet<>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) out.add(ai.packageName);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private boolean isProtected(String pkg) {
        return AppSafety.isCritical(context, pkg) || AppSafety.isAutoProtected(context, pkg) || AppProfilePolicy.protectedActive(context, prefs, pkg);
    }

    private String label(String pkg) { return AppSafety.label(context, pkg); }

    private void recoverTrackedRestrictions() {
        Set<String> restricted = new HashSet<>(prefs.getStringSet("auto_restricted", Collections.emptySet()));
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String pkg = ai.packageName;
                if (prefs.contains("prev_bg_" + hash(pkg)) || prefs.contains("prev_any_" + hash(pkg))) {
                    restricted.add(pkg);
                }
            }
            int restored = 0;
            for (String pkg : new ArrayList<>(restricted)) {
                if (!isEligibleForAutoRestriction(pkg)) {
                    try { restoreAutoRestriction(pkg, restricted); restored++; } catch (Throwable ignored) {}
                }
            }
            prefs.edit().putStringSet("auto_restricted", new HashSet<>(restricted)).apply();
            updateRestrictedText(restricted);
            if (restored > 0) {
                String msg = "Restaurados " + restored + " componentes protegidos/não elegíveis.";
                log(msg);
                ChangeNotifier.notifyChange(context, "Proteções restauradas", msg, 8);
            }
        } catch (Throwable ignored) {
            prefs.edit().putStringSet("auto_restricted", new HashSet<>(restricted)).apply();
            updateRestrictedText(restricted);
        }
    }

    private void updateRestrictedText(Set<String> restricted) {
        if (restricted.isEmpty()) {
            prefs.edit().putString("restricted_text", "Nenhum app restrito automaticamente.").apply();
            return;
        }
        List<String> names = new ArrayList<>();
        for (String pkg : restricted) names.add(label(pkg));
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder();
        int max = Math.min(12, names.size());
        for (int i=0; i<max; i++) {
            if (i>0) sb.append('\n');
            sb.append("• ").append(names.get(i));
        }
        if (names.size() > max) sb.append("\n+ ").append(names.size()-max).append(" outros");
        prefs.edit().putString("restricted_text", sb.toString()).apply();
    }

    private void log(String message) {
        IncidentHistory.add(prefs, "maintenance", "automatic", message, -1);
        String stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        String entry = stamp + " — " + message;
        String old = prefs.getString("maintenance_log", "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\\n");
        StringBuilder sb = new StringBuilder(entry);
        for (int i=0; i<Math.min(9, lines.length); i++) sb.append('\n').append(lines[i]);
        prefs.edit().putString("maintenance_log", sb.toString()).apply();
    }

    private String shortErr(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private String hash(String s) { return Integer.toHexString(s.hashCode()); }

    private static class BatteryItem {
        int uid; double mah;
        BatteryItem(int uid, double mah) { this.uid=uid; this.mah=mah; }
    }
    private static class RankedApp {
        String pkg; double mah;
        RankedApp(String pkg, double mah) { this.pkg=pkg; this.mah=mah; }
    }
}

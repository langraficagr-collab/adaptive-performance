package com.mauricio.adaptiveperformance;
import android.content.SharedPreferences;
import java.util.*;
public final class RestrictionGuard {
    private RestrictionGuard() {}
    public static synchronized boolean claim(SharedPreferences p, String pkg, String owner, String reason) {
        String current = p.getString("restriction_owner_" + pkg, "");
        // Ownership remains mandatory for safe restoration even when extra guarding is disabled.
        if (!current.isEmpty() && !current.equals(owner)) return false;
        if (p.getBoolean("over_optimization_guard", true)
                && (p.contains("persistent_wakelock_previous_level_" + pkg) || p.contains("memory_leak_inactive_until_" + pkg))
                && !owner.equals("cause")) return false;
        for (String key : new String[]{"cpu_limited_set", "manual_frozen_active", "auto_restricted"}) {
            String module = key.equals("cpu_limited_set") ? "cpu" : key.equals("manual_frozen_active") ? "manual" : "maintenance";
            if (!module.equals(owner) && p.getStringSet(key, Collections.emptySet()).contains(pkg)) return false;
        }
        return p.edit().putString("restriction_owner_" + pkg, owner)
                .putString("restriction_reason_" + pkg, reason).commit();
    }
    public static synchronized void release(SharedPreferences p, String pkg, String owner) {
        if (owner.equals(p.getString("restriction_owner_" + pkg, "")))
            p.edit().remove("restriction_owner_" + pkg).remove("restriction_reason_" + pkg).commit();
    }
    public static synchronized void clean(SharedPreferences p) {
        for (String key : p.getAll().keySet()) {
            if (!key.startsWith("restriction_owner_")) continue;
            String pkg = key.substring("restriction_owner_".length());
            String owner = p.getString(key, "");
            boolean tracked = (owner.equals("cpu") && p.getStringSet("cpu_limited_set", Collections.emptySet()).contains(pkg))
                    || (owner.equals("manual") && p.getStringSet("manual_frozen_active", Collections.emptySet()).contains(pkg))
                    || (owner.equals("maintenance") && p.getStringSet("auto_restricted", Collections.emptySet()).contains(pkg))
                    || (owner.equals("cause") && (p.contains("persistent_wakelock_previous_level_" + pkg)
                    || p.contains("memory_leak_inactive_until_" + pkg)));
            if (!tracked) release(p, pkg, owner);
        }
    }
    public static boolean background(android.content.Context c, SharedPreferences p, IPrivilegedService s, String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")
                || !AppSafety.isEligibleForAdaptiveOptimization(c, pkg)
                || pkg.equals(p.getString("foreground", "")) || AppProfilePolicy.protectedActive(c, p, pkg)) return false;
        try {
            String top = s.exec("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
            String services = s.exec("dumpsys activity services " + pkg);
            return top != null && top.contains("/") && !top.contains(pkg + "/")
                    && services != null && services.contains("ACTIVITY MANAGER") && !services.contains("isForeground=true");
        } catch (Exception e) { return false; }
    }
    public static boolean command(IPrivilegedService s, String cmd) throws Exception {
        String wrapped = cmd + " 2>&1; rc=$?; echo __AP_RC__$rc";
        String out = s.exec(wrapped);
        if (out == null) return false;
        String low = out.toLowerCase(Locale.US);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("__AP_RC__([0-9]+)").matcher(out);
        int rc = m.find() ? Integer.parseInt(m.group(1)) : -1;

        if (cmd.startsWith("cmd activity freeze ")) {
            if (rc == 0) return true;
            if (rc == 255 && low.contains("freezing process")) return true;
            return false;
        }
        if (cmd.startsWith("cmd activity unfreeze ")) {
            if (rc == 0) return true;
            if (low.contains("unfreezing process")) return true;
            if (rc == 255 && (low.contains("could not find process") || low.contains("not found"))) return true;
            return false;
        }

        if (low.matches("(?s).*(error|exception|unknown command|permission denial|not found|failed).*")) return false;
        return rc == 0;
    }
    public static boolean level(String value) {
        return value != null && value.matches("unrestricted|exempted|adaptive_bucket|restricted_bucket|background_restricted|hibernation");
    }
}

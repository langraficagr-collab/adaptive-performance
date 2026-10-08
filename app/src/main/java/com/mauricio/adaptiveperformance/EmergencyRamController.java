package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

public final class EmergencyRamController {
    private EmergencyRamController() {}
    public static synchronized void update(Context c, SharedPreferences p, IPrivilegedService service,
                                           double ram, float psi, String foreground) {
        boolean criticallyLow = ram >= 0 && ram < 8;
        boolean combinedPressure = ram >= 0 && ram < 15 && psi >= 25;
        if (!AdaptiveIntelligenceController.mutationAllowed(p) || service == null || !p.getBoolean("ram_emergency_mode", true) || !p.getBoolean("critical_cleanup", true)
                || !(criticallyLow || combinedPressure)) return;
        long now = System.currentTimeMillis(), last = p.getLong("ram_emergency_at", 0);
        if (last > 0 && now - last < 90000) return;
        // Persist even a failed attempt to avoid repeated privileged work under pressure.
        if (!p.edit().putLong("ram_emergency_at", now).commit()) return;
        List<String> actions = new ArrayList<>();
        try {
            String ps = service.exec("ps -A -o PID,NAME");
            if (ps == null || !ps.contains("PID")) return;
            Set<String> packages = new TreeSet<>();
            for (String row : ps.split("\n")) {
                String[] cols = row.trim().split("\\s+");
                if (cols.length != 2 || !cols[0].matches("[0-9]+")) continue;
                packages.add(cols[1].split(":", 2)[0]);
            }
            int max = AppProfilePolicy.batch(c, p, foreground, 12);
            for (String pkg : packages) {
                if (actions.size() >= max) break;
                if (pkg.equals(foreground)
                        || AppSafety.isNeverFreeze(c, pkg)
                        || !AppSafety.hasLeftForegroundLongEnough(p, pkg)
                        || p.getStringSet("manual_freeze_selected", Collections.emptySet()).contains(pkg)
                        || !RestrictionGuard.background(c, p, service, pkg)
                        || !RestrictionGuard.claim(p, pkg, "emergency", "cached/background RAM relief")) continue;
                try {
                    // am kill lets ActivityManager kill only processes safe to kill (background).
                    String command = "am kill --user 0 " + pkg;
                    if (RestrictionGuard.command(service, command)) actions.add(command);
                } finally { RestrictionGuard.release(p, pkg, "emergency"); }
            }
        } catch (Exception ignored) { }
        finally {
            String result = actions.isEmpty() ? "Nenhum app elegível/comando confirmado" : String.join("; ", actions);
            p.edit().putString("ram_emergency_action", result).apply();
            IncidentHistory.add(p, "ram_emergency", result, "RAM " + ram + "%; PSI " + psi, -1);
        }
    }
}

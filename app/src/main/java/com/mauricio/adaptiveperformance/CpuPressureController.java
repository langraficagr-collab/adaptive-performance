package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.text.DateFormat;
import java.util.*;
import java.util.regex.*;

public class CpuPressureController {
    private static final double ENGAGE_CPU = 70.0;
    private static final double RELEASE_CPU = 55.0;
    private static final int SAFE_SAMPLES_TO_RELEASE = 2;

    private final Context context;
    private final SharedPreferences prefs;
    private final IPrivilegedService privileged;
    private final PackageManager pm;
    private final Map<String,String> previousRestriction = new HashMap<>();
    private final Set<String> frozenPackages = new HashSet<>();
    private volatile boolean active = false;
    private int safeSamples = 0;

    public CpuPressureController(Context context, SharedPreferences prefs, IPrivilegedService privileged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.privileged = privileged;
        this.pm = context.getPackageManager();
    }

    public synchronized void initialize() {
        restoreStaleRestrictions();
    }

    public synchronized void update(double totalCpu, String foregroundPackage, float socTemp) {
        update(totalCpu, foregroundPackage, socTemp, 0);
    }

    public synchronized void update(double totalCpu, String foregroundPackage, float socTemp, int adaptivePressure) {
        if (!prefs.getBoolean("cpu_pressure_control", true)) {
            if (active) restoreAll();
            return;
        }

        boolean hotSoc = socTemp > 0f && socTemp >= 60.0f;
        boolean cpuHigh = totalCpu >= ENGAGE_CPU;
        boolean systemPressure = adaptivePressure >= 3;
        if (!active && (cpuHigh || hotSoc || systemPressure)) {
            active = true;
            safeSamples = 0;
            restrictBackground(foregroundPackage);
            active = !frozenPackages.isEmpty();
            setState(active);
            String msg;
            if (systemPressure && !cpuHigh && !hotSoc)
                msg = "Pressão combinada de RAM/I/O/energia: segundo plano limitado; primeiro plano preservado.";
            else if (hotSoc && !cpuHigh)
                msg = "SoC acima de 60°C: segundo plano limitado para ajudar no resfriamento.";
            else
                msg = "CPU acima de 70%: segundo plano limitado; primeiro plano preservado.";
            log(msg);
            ChangeNotifier.notifyChange(context, "Proteção de pressão ativada",
                    msg + " Apps limitados: " + frozenPackages.size() + ".", 2);
            return;
        }

        if (!active) return;

        // Exceções escolhidas pelo usuário e apps selecionados para congelamento manual têm prioridade.
        Set<String> manualSelected = prefs.getStringSet("manual_freeze_selected", Collections.emptySet());
        for (String pkg : new ArrayList<>(frozenPackages)) {
            if (AppSafety.isAutoProtected(context, pkg) || manualSelected.contains(pkg)) restorePackage(pkg);
        }

        // Se o usuário abrir um app que estava limitado, libera imediatamente esse app.
        if (foregroundPackage != null && !foregroundPackage.isEmpty()) {
            restorePackage(foregroundPackage);
        }

        if (frozenPackages.isEmpty()) { active = false; setState(false); return; }

        // Captura novos processos que tenham entrado no segundo plano durante a pressão.
        if (cpuHigh || hotSoc || systemPressure) restrictBackground(foregroundPackage);

        boolean socSafe = socTemp <= 0f || socTemp <= 55.0f;
        boolean cpuSafe = totalCpu < 0 || totalCpu <= RELEASE_CPU;
        boolean adaptiveSafe = adaptivePressure <= 1;
        if (cpuSafe && socSafe && adaptiveSafe) safeSamples++;
        else safeSamples = 0;

        if (safeSamples >= SAFE_SAMPLES_TO_RELEASE) {
            int restored = releaseBatch(2);
            String msg = "CPU/SoC/pressão do sistema normalizados: restrições temporárias removidas de " + restored + " app(s).";
            log(msg);
            ChangeNotifier.notifyChange(context, "Pressão de CPU normalizada", msg, 2);
        }
    }

    public boolean isActive() { return active; }
    public int limitedCount() { return prefs.getInt("cpu_limited_apps", 0); }

    public synchronized void restoreAll() { releaseBatch(Integer.MAX_VALUE); }

    public synchronized int releaseBatch(int max) {
        int before = frozenPackages.size();
        int attempted = 0;
        for (String pkg : new TreeSet<>(frozenPackages)) {
            if (attempted++ >= Math.max(0, max)) break;
            restorePackage(pkg);
        }
        active = !frozenPackages.isEmpty();
        if (!active) safeSamples = 0;
        setState(active);
        int released = before - frozenPackages.size();
        prefs.edit().putInt("rollback_remaining", frozenPackages.size())
                .putString("rollback_status", active ? "Liberação progressiva; restantes: " + frozenPackages.size() : "Concluído")
                .putInt("rollback_released", prefs.getInt("rollback_released", 0) + released).apply();
        if (released > 0) IncidentHistory.add(prefs, "pressure", "rollback", "Liberados: " + released + "; restantes: " + frozenPackages.size(), -1);
        return released;
    }

    private void restrictBackground(String foregroundPackage) {
        try {
            Set<String> fgs = activeForegroundServicePackages();
            String ps = privileged.exec("ps -A -o PID,NAME 2>/dev/null");
            if (ps == null) return;
            Pattern row = Pattern.compile("^\\s*(\\d+)\\s+([^\\s]+)", Pattern.MULTILINE);
            Matcher m = row.matcher(ps);
            Map<String,List<Integer>> packagePids = new HashMap<>();

            while (m.find()) {
                int pid;
                try { pid = Integer.parseInt(m.group(1)); } catch (Exception e) { continue; }
                String proc = m.group(2);
                String pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
                if (!AppSafety.isEligibleForAdaptiveOptimization(context, pkg)) continue;
                packagePids.computeIfAbsent(pkg, k -> new ArrayList<>()).add(pid);
            }

            int budget = AppProfilePolicy.batch(context, prefs, foregroundPackage, 4);
            for (Map.Entry<String,List<Integer>> e : packagePids.entrySet()) {
                if (budget <= 0) break;
                String pkg = e.getKey();
                if (pkg.equals(foregroundPackage) || isProtected(pkg) || fgs.contains(pkg)
                        || prefs.getStringSet("manual_freeze_selected", Collections.emptySet()).contains(pkg)) continue;
                if (frozenPackages.contains(pkg)) continue;
                if (!RestrictionGuard.background(context, prefs, privileged, pkg)) continue;
                if (!previousRestriction.containsKey(pkg)) {
                    String old = getRestrictionLevel(pkg);
                    if (!isValidLevel(old) || old.equals("hibernation") || old.equals("background_restricted")) continue;
                    if (!RestrictionGuard.claim(prefs, pkg, "cpu", "temporary pressure")) continue;
                    previousRestriction.put(pkg, old);
                    prefs.edit().putString("cpu_prev_" + hash(pkg), old).commit();
                }
                frozenPackages.add(pkg);
                prefs.edit().putStringSet("cpu_limited_set", new HashSet<>(frozenPackages)).commit();
                if (RestrictionGuard.command(privileged, "cmd activity set-bg-restriction-level --user 0 " + pkg + " background_restricted")) {
                    budget--;
                    prefs.edit().putLong("cpu_applied_at_" + hash(pkg), System.currentTimeMillis()).commit();
                    IncidentHistory.add(prefs, "pressure", pkg, "background_restricted", -1);
                }
            }
            prefs.edit().putInt("cpu_limited_apps", frozenPackages.size()).apply();
        } catch (Throwable ignored) {}
    }

    private void restorePackage(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+") || !frozenPackages.contains(pkg)) return;
        try {
            String owner = prefs.getString("restriction_owner_" + pkg, "");
            if (!owner.isEmpty() && !owner.equals("cpu")) return;
            // Only legacy versions froze processes; do not unfreeze unrelated current state.
            if (!prefs.contains("cpu_applied_at_" + hash(pkg))) {
                String ps = privileged.exec("ps -A -o PID,NAME");
                if (ps == null || !ps.contains("PID")) return;
                for (String row : ps.split("\n")) {
                    String[] cols = row.trim().split("\\s+");
                    if (cols.length == 2 && (cols[1].equals(pkg) || cols[1].startsWith(pkg + ":"))) {
                        if (!cols[1].matches("[A-Za-z0-9_.:]+") || !RestrictionGuard.command(privileged, "cmd activity unfreeze " + cols[1])) return;
                    }
                }
            }
            String old = prefs.getString("cpu_prev_" + hash(pkg), "");
            String current = getRestrictionLevel(pkg);
            if (!isValidLevel(current)) return;
            if ("background_restricted".equals(current)) {
                if (!isValidLevel(old)) {
                    prefs.edit().putString("startup_invalid_level", pkg + ": " + old).apply(); return;
                }
                if (!RestrictionGuard.command(privileged, "cmd activity set-bg-restriction-level --user 0 " + pkg + " " + old)) return;
            }
            // A different current level belongs to a newer actor and must remain untouched.
            frozenPackages.remove(pkg);
            previousRestriction.remove(pkg);
            prefs.edit().putStringSet("cpu_limited_set", new HashSet<>(frozenPackages))
                    .remove("cpu_prev_" + hash(pkg)).remove("cpu_applied_at_" + hash(pkg)).putInt("cpu_limited_apps", frozenPackages.size()).commit();
            RestrictionGuard.release(prefs, pkg, "cpu");
        } catch (Throwable ignored) { }
    }
    private void restoreStaleRestrictions() {
        frozenPackages.addAll(prefs.getStringSet("cpu_limited_set", Collections.emptySet()));
        releaseBatch(Integer.MAX_VALUE);
    }

    private String hash(String s) { return Integer.toHexString(s.hashCode()); }

    private String getRestrictionLevel(String pkg) {
        try {
            String s = privileged.exec("cmd activity get-bg-restriction-level --user 0 " + pkg);
            if (s != null) {
                s = s.trim();
                if (isValidLevel(s)) return s;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private boolean isValidLevel(String s) {
        return "unrestricted".equals(s) || "exempted".equals(s) || "adaptive_bucket".equals(s)
                || "restricted_bucket".equals(s) || "background_restricted".equals(s)
                || "hibernation".equals(s);
    }

    private Set<String> activeForegroundServicePackages() {
        Set<String> out = new HashSet<>();
        try {
            String raw = privileged.exec("dumpsys activity services 2>/dev/null | " +
                    "grep -B30 'isForeground=true' | grep 'packageName=' | " +
                    "sed 's/.*packageName=//' | awk '{print $1}' | sort -u");
            if (raw != null) {
                for (String line : raw.split("\\n")) {
                    String pkg = line.trim();
                    if (pkg.matches("[A-Za-z0-9_.]+")) out.add(pkg);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private boolean isEligiblePackage(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            // Apenas UIDs de aplicativo. Nunca mexe em daemons/serviços nativos do Android.
            int appId = ai.uid % 100000;
            if (appId < 10000) return false;
            // Processos persistentes fazem parte da infraestrutura essencial do sistema.
            if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) return false;
            return true;
        } catch (Throwable t) { return false; }
    }

    private boolean isProtected(String pkg) {
        return !AppSafety.isEligibleForAdaptiveOptimization(context, pkg);
    }

    private void setState(boolean on) {
        prefs.edit().putBoolean("cpu_pressure_active", on)
                .putInt("cpu_limited_apps", frozenPackages.size()).apply();
    }

    private void log(String message) {
        String stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        String entry = stamp + " — " + message;
        String old = prefs.getString("maintenance_log", "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\\n");
        StringBuilder sb = new StringBuilder(entry);
        for (int i=0; i<Math.min(9, lines.length); i++) sb.append('\n').append(lines[i]);
        prefs.edit().putString("maintenance_log", sb.toString()).apply();
    }
}

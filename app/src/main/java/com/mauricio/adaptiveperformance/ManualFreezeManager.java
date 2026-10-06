package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import java.util.regex.*;

public class ManualFreezeManager {
    private final Context context;
    private final SharedPreferences prefs;
    private final IPrivilegedService privileged;
    private final Set<String> active = new HashSet<>();

    public ManualFreezeManager(Context context, SharedPreferences prefs, IPrivilegedService privileged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.privileged = privileged;
    }

    public synchronized void initialize() {
        restoreStale();
    }

    public synchronized void update(String foregroundPackage) {
        Set<String> selected = new HashSet<>(prefs.getStringSet("manual_freeze_selected", Collections.emptySet()));
        Set<String> released = new HashSet<>();
        for (String pkg : new ArrayList<>(active)) {
            try {
                boolean changed = !processes(pkg).equals(prefs.getString("manual_process_snapshot_" + pkg, ""));
                boolean expired = System.currentTimeMillis() - prefs.getLong("manual_frozen_at_" + pkg, 0) >= 1800000L;
                if (!prefs.getBoolean("manual_freeze_enabled", false) || !selected.contains(pkg)
                        || !AppSafety.isEligibleForManualFreeze(context, pkg) || AppSafety.isSystemApp(context, pkg) || AppSafety.isAutoProtected(context, pkg)
                        || pkg.equals(foregroundPackage) || hasForegroundService(pkg) || changed || expired) {
                    unfreeze(pkg, pkg.equals(foregroundPackage)); released.add(pkg);
                    if (expired) prefs.edit().putBoolean("manual_watchdog_released_" + pkg, true).apply();
                }
            } catch (Throwable ignored) { }
        }
        if (!prefs.getBoolean("manual_freeze_enabled", false)) return;
        for (String pkg : selected) {
            String reason = null;
            if (!AppSafety.isEligibleForManualFreeze(context, pkg)) reason = "inelegível";
            else if (AppSafety.isSystemApp(context, pkg)) reason = "app de sistema";
            else if (AppSafety.isAutoProtected(context, pkg)) reason = "auto-protegido";
            else if (released.contains(pkg)) reason = "acabou de ser liberado";
            else if (prefs.getBoolean("manual_watchdog_released_" + pkg, false)) reason = "watchdog liberou";
            else if (pkg.equals(foregroundPackage)) reason = "em primeiro plano";
            else if (hasForegroundService(pkg)) reason = "serviço foreground";
            if (reason != null) {
                prefs.edit().putString("manual_freeze_last_reason", pkg + ": " + reason).apply();
                continue;
            }
            prefs.edit().putString("manual_freeze_last_reason", pkg + ": elegível; entrando no freeze").apply();
            freeze(pkg);
        }
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("manual_watchdog_released_") && !selected.contains(key.substring("manual_watchdog_released_".length())))
                prefs.edit().remove(key).apply();
        }
        persist();
    }
    public synchronized void restoreAll() {
        for (String pkg : new ArrayList<>(active)) unfreeze(pkg, false);
        persist();
    }
    private void persist() {
        prefs.edit().putStringSet("manual_frozen_active", new HashSet<>(active))
                .putInt("manual_frozen_active_count", active.size()).commit();
    }
    private Set<String> names(String ps, String pkg) {
        Set<String> out = new TreeSet<>();
        for (String row : ps.split("\n")) {
            String[] cols = row.trim().split("\\s+");
            if (cols.length == 2 && (cols[1].equals(pkg) || cols[1].startsWith(pkg + ":"))
                    && cols[1].matches("[A-Za-z0-9_.:]+")) out.add(cols[1]);
        }
        return out;
    }
    private void freeze(String pkg) {
        if (active.contains(pkg)) return;
        try {
            if (!RestrictionGuard.background(context, prefs, privileged, pkg)) {
                prefs.edit().putString("manual_freeze_last_reason", pkg + ": RestrictionGuard.background=false").apply();
                return;
            }
            String ps = processes(pkg);
            Set<String> names = names(ps, pkg);
            if (names.isEmpty()) {
                prefs.edit().putString("manual_freeze_last_reason", pkg + ": nenhum processo ativo").apply();
                return;
            }
            if (!RestrictionGuard.claim(prefs, pkg, "manual", "user freeze")) {
                prefs.edit().putString("manual_freeze_last_reason", pkg + ": RestrictionGuard.claim=false").apply();
                return;
            }
            // Persist intent before commands; partial failures are always recoverable.
            active.add(pkg); persist();
            prefs.edit().putStringSet("manual_frozen_names_" + pkg, names)
                    .putString("manual_process_snapshot_" + pkg, ps).commit();
            Set<String> frozenOk = new TreeSet<>();
            for (String name : names) {
                if (!RestrictionGuard.command(privileged, "cmd activity freeze " + name)) {
                    prefs.edit().putString("manual_freeze_last_reason", pkg + ": freeze falhou em " + name)
                            .putStringSet("manual_frozen_names_" + pkg, frozenOk).commit();
                    unfreeze(pkg, false); return;
                }
                frozenOk.add(name);
                prefs.edit().putStringSet("manual_frozen_names_" + pkg, frozenOk).commit();
            }
            prefs.edit().putLong("manual_frozen_at_" + pkg, System.currentTimeMillis())
                    .putString("manual_freeze_last_reason", pkg + ": congelado com sucesso " + names).commit();
            report(pkg, "Congelado por nome: " + names);
        } catch (Throwable ignored) { unfreeze(pkg, false); }
    }
    private void unfreeze(String pkg, boolean opened) {
        if (!active.contains(pkg) && !prefs.contains("manual_frozen_names_" + pkg)) return;
        try {
            Set<String> current = names(processes(pkg), pkg);
            Set<String> all = new TreeSet<>(prefs.getStringSet("manual_frozen_names_" + pkg, Collections.emptySet()));
            all.addAll(current);
            Set<String> pending = new TreeSet<>();
            for (String name : all) {
                if (!name.matches("[A-Za-z0-9_.:]+")) continue;
                boolean exists = names(processes(pkg), pkg).contains(name);
                if (!exists) continue; // Dead processes are already effectively unfrozen.
                boolean success = RestrictionGuard.command(privileged, "cmd activity unfreeze " + name);
                if (!success && names(processes(pkg), pkg).contains(name)) pending.add(name);
            }
            if (!pending.isEmpty()) {
                prefs.edit().putStringSet("manual_frozen_names_" + pkg, pending).commit();
                report(pkg, "Descongelamento parcial; pendentes: " + pending);
                return;
            }
            active.remove(pkg); persist();
            prefs.edit().remove("manual_frozen_names_" + pkg).remove("manual_process_snapshot_" + pkg)
                    .remove("manual_frozen_at_" + pkg).remove("manual_watchdog_released_" + pkg).commit();
            RestrictionGuard.release(prefs, pkg, "manual");
            report(pkg, opened ? "Descongelado imediatamente ao abrir" : "Descongelado/restaurado; estado limpo");
        } catch (Throwable t) {
            report(pkg, "Falha ao verificar descongelamento; nova tentativa será feita");
        }
    }
    private void report(String pkg, String action) {
        IncidentHistory.add(prefs, "manual_freeze", pkg, action, -1);
        ChangeNotifier.notifyChange(context, "Congelamento manual", AppSafety.label(context, pkg) + ": " + action, 5);
    }
    private void restoreStale() {
        active.addAll(prefs.getStringSet("manual_frozen_active", Collections.emptySet()));
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("manual_frozen_names_")) active.add(key.substring("manual_frozen_names_".length()));
        }
        restoreAll();
    }
    private String processes(String pkg) throws Exception {
        if (!pkg.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("package");
        String raw = privileged.exec("ps -A -o PID,NAME");
        if (raw == null || !raw.contains("PID")) throw new IllegalStateException("ps unavailable");
        StringBuilder out = new StringBuilder();
        for (String row : raw.split("\n")) {
            String[] cols = row.trim().split("\\s+");
            if (cols.length == 2 && (cols[1].equals(pkg) || cols[1].startsWith(pkg + ":"))) out.append(row.trim()).append('\n');
        }
        return out.toString();
    }
    private boolean hasForegroundService(String pkg) {
        try {
            String s = privileged.exec("dumpsys activity services " + pkg);
            return s == null || s.contains("isForeground=true");
        } catch (Throwable t) { return true; }
    }
}

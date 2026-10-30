package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

public class ManualFreezeManager {
    private final Context context;
    private final SharedPreferences prefs;
    private final IPrivilegedService privileged;
    private final Set<String> active = new HashSet<>();
    private final Set<String> openedByUser = new HashSet<>();

    public ManualFreezeManager(Context context, SharedPreferences prefs, IPrivilegedService privileged) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.privileged = privileged;
    }

    public synchronized void initialize() {
        active.addAll(prefs.getStringSet("manual_frozen_active", Collections.emptySet()));
        persist();
    }

    public synchronized void update(String foregroundPackage) {
        Set<String> selected = new HashSet<>(prefs.getStringSet("manual_freeze_selected", Collections.emptySet()));
        if (selected.removeIf(pkg -> AppSafety.isCritical(context, pkg))) {
            prefs.edit().putStringSet("manual_freeze_selected", new HashSet<>(selected)).commit();
        }
        boolean enabled = prefs.getBoolean("manual_freeze_enabled", false);

        // A manual selection freezes immediately when no longer on screen.
        // No idle-time delay is applied to user-selected apps.
        for (String pkg : new ArrayList<>(active)) {
            try {
                if (!enabled || !selected.contains(pkg) || !AppSafety.isEligibleForManualFreeze(context, pkg)
                        || AppSafety.isNeverFreeze(context, pkg)) {
                    unfreeze(pkg, false);
                } else if (pkg.equals(foregroundPackage)) {
                    // Explicit user launch is allowed: restore its previous standby bucket.
                    unfreeze(pkg, true);
                }
            } catch (Throwable t) {
                prefs.edit().putString("manual_freeze_last_reason",
                        pkg + ": erro ao restaurar: " + t.getClass().getSimpleName()).apply();
            }
        }
        if (!enabled) return;

        for (String pkg : selected) {
            if (active.contains(pkg) || pkg.equals(foregroundPackage)) continue;
            if (!AppSafety.isEligibleForManualFreeze(context, pkg)
                    || AppSafety.isNeverFreeze(context, pkg)) continue;
            freeze(pkg);
        }
        persist();
    }

    /** Invoked only after explicit confirmation on an internal thermal notification. */
    public synchronized boolean freezeApprovedNow(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")
                || AppSafety.isCritical(context, pkg)
                || AppSafety.isSystemApp(context, pkg)
                || !AppSafety.isEligibleForManualFreeze(context, pkg)
                || AppSafety.isNeverFreeze(context, pkg)
                || AppSafety.isAutoProtected(context, pkg)) return false;
        Set<String> selected = new HashSet<>(prefs.getStringSet("manual_freeze_selected", Collections.emptySet()));
        selected.add(pkg);
        if (!prefs.edit().putStringSet("manual_freeze_selected", selected)
                .putBoolean("manual_freeze_enabled", true).commit()) return false;
        if (!active.contains(pkg)) freeze(pkg);
        persist();
        return active.contains(pkg);
    }

    public synchronized void restoreAll() {
        for (String pkg : new ArrayList<>(active)) unfreeze(pkg, false);
        persist();
    }

    private void persist() {
        prefs.edit().putStringSet("manual_frozen_active", new HashSet<>(active))
                .putInt("manual_frozen_active_count", active.size()).commit();
    }

    private void freeze(String pkg) {
        if (active.contains(pkg)) return;
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
                || !AppSafety.isEligibleForManualFreeze(context, pkg)
                || AppSafety.isNeverFreeze(context, pkg)) return;
        try {
            // Fail closed if Android cannot confirm which package is now resumed.
            String resumed = privileged.exec(
                    "dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity'");
            if (resumed == null || !resumed.contains("/")) {
                prefs.edit().putString("manual_freeze_last_reason",
                        pkg + ": primeiro plano não verificável").apply();
                return;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/").matcher(resumed);
            if (!matcher.find() || pkg.equals(matcher.group(1))) return;
            // A escolha manual do usuário tem prioridade sobre restrições automáticas.
            // Primeiro restaura somente estados que sabemos terem sido aplicados por nós,
            // remove o rastreamento desses módulos e então assume ownership "manual".
            releaseAutomaticOwnership(pkg);
            if (!RestrictionGuard.claim(prefs, pkg, "manual", "persistent user freeze")) {
                prefs.edit().putString("manual_freeze_last_reason", pkg + ": controlado por outro módulo").apply();
                return;
            }

            String previousBucket = getStandbyBucket(pkg);
            prefs.edit().putString("manual_freeze_prev_bucket_" + pkg, previousBucket).commit();

            boolean restricted = RestrictionGuard.command(privileged,
                    "am set-standby-bucket " + pkg + " restricted");
            boolean stopped = RestrictionGuard.command(privileged,
                    "am force-stop --user 0 " + pkg);

            if (!stopped) {
                restoreBucket(pkg, previousBucket);
                RestrictionGuard.release(prefs, pkg, "manual");
                prefs.edit().remove("manual_freeze_prev_bucket_" + pkg)
                        .putString("manual_freeze_last_reason", pkg + ": force-stop falhou").commit();
                return;
            }

            openedByUser.remove(pkg);
            active.add(pkg);
            persist();
            prefs.edit()
                    .putLong("manual_frozen_at_" + pkg, System.currentTimeMillis())
                    .putBoolean("manual_freeze_restricted_" + pkg, restricted)
                    .putString("manual_freeze_last_reason", pkg + ": congelado persistentemente").commit();
            report(pkg, "Congelado: force-stop persistente" + (restricted ? " + standby restricted" : ""));
        } catch (Throwable t) {
            RestrictionGuard.release(prefs, pkg, "manual");
            prefs.edit().putString("manual_freeze_last_reason", pkg + ": falha ao congelar").apply();
        }
    }

    private void unfreeze(String pkg, boolean opened) {
        if (!active.contains(pkg) && !prefs.contains("manual_freeze_prev_bucket_" + pkg)) return;
        try {
            String previous = prefs.getString("manual_freeze_prev_bucket_" + pkg, "10");
            restoreBucket(pkg, previous);
            active.remove(pkg);
            openedByUser.remove(pkg);
            persist();
            prefs.edit()
                    .remove("manual_freeze_prev_bucket_" + pkg)
                    .remove("manual_frozen_at_" + pkg)
                    .remove("manual_freeze_restricted_" + pkg)
                    .commit();
            RestrictionGuard.release(prefs, pkg, "manual");
            report(pkg, opened ? "Descongelado ao abrir pelo usuário" : "Descongelado e estado restaurado");
        } catch (Throwable t) {
            report(pkg, "Falha ao restaurar; nova tentativa será feita");
        }
    }

    private void releaseAutomaticOwnership(String pkg) {
        String h = Integer.toHexString(pkg.hashCode());
        try {
            Set<String> auto = new HashSet<>(prefs.getStringSet("auto_restricted", Collections.emptySet()));
            if (auto.contains(pkg)) {
                String oldBg = prefs.getString("prev_bg_" + h, "");
                String oldAny = prefs.getString("prev_any_" + h, "");
                if (oldBg.matches("allow|ignore|deny|default")) {
                    try { RestrictionGuard.command(privileged,
                            "appops set --user 0 " + pkg + " RUN_IN_BACKGROUND " + oldBg); }
                    catch (Throwable ignored) {}
                }
                if (oldAny.matches("allow|ignore|deny|default")) {
                    try { RestrictionGuard.command(privileged,
                            "appops set --user 0 " + pkg + " RUN_ANY_IN_BACKGROUND " + oldAny); }
                    catch (Throwable ignored) {}
                }
                auto.remove(pkg);
                prefs.edit().putStringSet("auto_restricted", new HashSet<>(auto))
                        .remove("prev_bg_" + h).remove("prev_any_" + h).commit();
                RestrictionGuard.release(prefs, pkg, "maintenance");
            }

            Set<String> cpu = new HashSet<>(prefs.getStringSet("cpu_limited_set", Collections.emptySet()));
            if (cpu.contains(pkg)) {
                String old = prefs.getString("cpu_prev_" + h, "");
                if (RestrictionGuard.level(old)) {
                    try { RestrictionGuard.command(privileged,
                            "cmd activity set-bg-restriction-level --user 0 " + pkg + " " + old); }
                    catch (Throwable ignored) {}
                }
                cpu.remove(pkg);
                prefs.edit().putStringSet("cpu_limited_set", new HashSet<>(cpu))
                        .remove("cpu_prev_" + h).remove("cpu_applied_at_" + h).commit();
                RestrictionGuard.release(prefs, pkg, "cpu");
            }
        } catch (Throwable ignored) {}
    }

    private String getStandbyBucket(String pkg) {
        try {
            String out = privileged.exec("am get-standby-bucket " + pkg + " 2>/dev/null");
            if (out != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(10|20|30|40|45|50)").matcher(out);
                if (m.find()) return m.group(1);
            }
        } catch (Throwable ignored) {}
        return "10";
    }

    private void restoreBucket(String pkg, String bucket) {
        if (bucket == null || !bucket.matches("10|20|30|40|45|50")) bucket = "10";
        try { RestrictionGuard.command(privileged, "am set-standby-bucket " + pkg + " " + bucket); }
        catch (Throwable ignored) {}
    }

    private void report(String pkg, String action) {
        IncidentHistory.add(prefs, "manual_freeze", pkg, action, -1);
        ChangeNotifier.notifyChange(context, "Congelamento manual", AppSafety.label(context, pkg) + ": " + action, 5);
    }
}

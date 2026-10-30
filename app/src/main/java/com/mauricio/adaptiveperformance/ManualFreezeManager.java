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
                    // Always undo hard background blocks when the user opens an app.
                    unfreeze(pkg, true);
                } else if (prefs.getBoolean("manual_freeze_aggressive_enabled", true)) {
                    strengthen(pkg);
                } else {
                    restoreAggressiveState(pkg);
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

    public synchronized boolean addApprovedLeakTarget(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
                || AppSafety.isCritical(context, pkg) || AppSafety.isSystemApp(context, pkg)
                || !AppSafety.isUserFacing(context, pkg)
                || !AppSafety.isEligibleForManualFreeze(context, pkg)
                || AppSafety.isNeverFreeze(context, pkg) || AppSafety.isAutoProtected(context, pkg)) return false;
        Set<String> selected = new HashSet<>(prefs.getStringSet("manual_freeze_selected", Collections.emptySet()));
        selected.add(pkg);
        return prefs.edit().putStringSet("manual_freeze_selected", selected)
                .putBoolean("manual_freeze_enabled", true).commit();
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
            if (prefs.getBoolean("manual_freeze_aggressive_enabled", true)) strengthen(pkg);
            report(pkg, "Congelado: force-stop" + (restricted ? " + standby restricted" : "")
                    + (prefs.getBoolean("manual_freeze_aggressive_applied_" + pkg, false)
                            ? " + AppOps de segundo plano" : ""));
        } catch (Throwable t) {
            RestrictionGuard.release(prefs, pkg, "manual");
            prefs.edit().putString("manual_freeze_last_reason", pkg + ": falha ao congelar").apply();
        }
    }

    private void unfreeze(String pkg, boolean opened) {
        if (!active.contains(pkg) && !prefs.contains("manual_freeze_prev_bucket_" + pkg)) return;
        try {
            // Restore AppOps BEFORE restoring standby and allowing a user launch.
            if (!restoreAggressiveState(pkg)) {
                prefs.edit().putString("manual_freeze_last_reason",
                        pkg + ": restauração do segundo plano pendente; tentando novamente").apply();
                return;
            }
            String previous = prefs.getString("manual_freeze_prev_bucket_" + pkg, "10");
            if (prefs.getBoolean("manual_freeze_restricted_" + pkg, true)
                    && !restoreBucket(pkg, previous)) {
                prefs.edit().putString("manual_freeze_last_reason",
                        pkg + ": não foi possível restaurar standby; pendente").apply();
                return;
            }
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

    /** Extra restriction only for explicitly selected manual targets, not any automatic tuning. */
    private void strengthen(String pkg) {
        if (!prefs.getBoolean("manual_freeze_aggressive_enabled", true)
                || !active.contains(pkg) || !AppSafety.isEligibleForManualFreeze(context, pkg)
                || AppSafety.isSystemApp(context, pkg) || AppSafety.isNeverFreeze(context, pkg)) return;
        if (prefs.getBoolean("manual_freeze_aggressive_applied_" + pkg, false)) return;
        if (prefs.contains("manual_freeze_prev_bg_" + pkg)
                || prefs.contains("manual_freeze_prev_any_" + pkg)) {
            if (!restoreAggressiveState(pkg)) return;
        }
        try {
            String resumed = privileged.exec("dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity'");
            if (resumed == null || !resumed.contains("/") || resumed.contains(pkg + "/")) return;
            String bg = getAppOp(pkg, "RUN_IN_BACKGROUND");
            String any = getAppOp(pkg, "RUN_ANY_IN_BACKGROUND");
            if (bg.isEmpty() || any.isEmpty()) {
                prefs.edit().putString("manual_freeze_last_reason",
                        pkg + ": AppOps originais indisponíveis; força-parada preservada").apply();
                return;
            }
            // Synchronous commit before touching system settings makes crash recovery possible.
            if (!prefs.edit().putString("manual_freeze_prev_bg_" + pkg, bg)
                    .putString("manual_freeze_prev_any_" + pkg, any).commit()) return;
            boolean a = bg.equals("ignore") || RestrictionGuard.command(privileged,
                    "appops set --user 0 " + pkg + " RUN_IN_BACKGROUND ignore");
            boolean b = a && (any.equals("ignore") || RestrictionGuard.command(privileged,
                    "appops set --user 0 " + pkg + " RUN_ANY_IN_BACKGROUND ignore"));
            if (a && b && "ignore".equals(getAppOp(pkg, "RUN_IN_BACKGROUND"))
                    && "ignore".equals(getAppOp(pkg, "RUN_ANY_IN_BACKGROUND"))) {
                prefs.edit().putBoolean("manual_freeze_aggressive_applied_" + pkg, true)
                        .putString("manual_freeze_last_reason", pkg + ": congelamento forte (força-parada + standby + AppOps)")
                        .commit();
            } else {
                restoreAggressiveState(pkg);
                prefs.edit().putString("manual_freeze_last_reason",
                        pkg + ": força-parada ativa; AppOps não disponíveis nesta ROM").apply();
            }
        } catch (Throwable t) {
            restoreAggressiveState(pkg);
            prefs.edit().putString("manual_freeze_last_reason",
                    pkg + ": AppOps forte indisponível (" + t.getClass().getSimpleName() + ")").apply();
        }
    }

    private String getAppOp(String pkg, String operation) {
        try {
            String out = privileged.exec("appops get --user 0 " + pkg + " " + operation + " 2>&1");
            if (out == null || out.toLowerCase(Locale.US).contains("error")
                    || out.toLowerCase(Locale.US).contains("permission denial")) return "";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?m)^\\s*" + operation + ":\\s*(allow|ignore|deny|default)\\b").matcher(out);
            if (m.find()) return m.group(1);
            // Android returns "No operations" for a never-configured AppOp.
            if (out.toLowerCase(Locale.US).contains("no operations")) return "default";
        } catch (Throwable ignored) { }
        return "";
    }

    /** Idempotent restoration, including upgrades where only one AppOp was altered. */
    private boolean restoreAggressiveState(String pkg) {
        String bg = prefs.getString("manual_freeze_prev_bg_" + pkg, "");
        String any = prefs.getString("manual_freeze_prev_any_" + pkg, "");
        if (bg.isEmpty() && any.isEmpty()) {
            prefs.edit().remove("manual_freeze_aggressive_applied_" + pkg).apply();
            return true;
        }
        try {
            boolean okBg = bg.isEmpty() || (bg.matches("allow|ignore|deny|default")
                    && (bg.equals(getAppOp(pkg, "RUN_IN_BACKGROUND"))
                        || RestrictionGuard.command(privileged,
                            "appops set --user 0 " + pkg + " RUN_IN_BACKGROUND " + bg)));
            boolean okAny = any.isEmpty() || (any.matches("allow|ignore|deny|default")
                    && (any.equals(getAppOp(pkg, "RUN_ANY_IN_BACKGROUND"))
                        || RestrictionGuard.command(privileged,
                            "appops set --user 0 " + pkg + " RUN_ANY_IN_BACKGROUND " + any)));
            if (!(okBg && okAny)) return false;
            if ((!bg.isEmpty() && !bg.equals(getAppOp(pkg, "RUN_IN_BACKGROUND")))
                    || (!any.isEmpty() && !any.equals(getAppOp(pkg, "RUN_ANY_IN_BACKGROUND")))) return false;
            SharedPreferences.Editor e = prefs.edit()
                    .remove("manual_freeze_prev_bg_" + pkg)
                    .remove("manual_freeze_prev_any_" + pkg)
                    .remove("manual_freeze_aggressive_applied_" + pkg);
            return e.commit();
        } catch (Throwable ignored) { return false; }
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

    private boolean restoreBucket(String pkg, String bucket) {
        if (bucket == null || !bucket.matches("10|20|30|40|45|50")) return false;
        try { return RestrictionGuard.command(privileged, "am set-standby-bucket " + pkg + " " + bucket); }
        catch (Throwable ignored) { return false; }
    }

    private void report(String pkg, String action) {
        IncidentHistory.add(prefs, "manual_freeze", pkg, action, -1);
        ChangeNotifier.notifyChange(context, "Congelamento manual", AppSafety.label(context, pkg) + ": " + action, 5);
    }
}

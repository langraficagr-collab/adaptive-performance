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
        boolean enabled = prefs.getBoolean("manual_freeze_enabled", false);

        // Um lançamento explícito pelo usuário tira o pacote do estado stopped.
        // Assim que ele aparece em primeiro plano, restauramos o bucket original.
        for (String pkg : new ArrayList<>(active)) {
            try {
                if (!enabled || !selected.contains(pkg) || !AppSafety.isEligibleForManualFreeze(context, pkg)
                        || AppSafety.isNeverFreeze(context, pkg)) {
                    unfreeze(pkg, false);
                } else if (pkg.equals(foregroundPackage)) {
                    active.remove(pkg);
                    openedByUser.add(pkg);
                    persist();
                    prefs.edit().putString("manual_freeze_last_reason",
                            pkg + ": aberto pelo usuário; será finalizado ao sair").apply();
                    report(pkg, "Aberto pelo usuário; será finalizado ao sair");
                }
            } catch (Throwable ignored) {}
        }
        if (!enabled) return;

        for (String pkg : new HashSet<>(openedByUser)) {
            if (!pkg.equals(foregroundPackage)
                    && AppSafety.hasLeftForegroundLongEnough(prefs, pkg)) {
                openedByUser.remove(pkg);
                freeze(pkg);
            }
        }

        for (String pkg : selected) {
            if (active.contains(pkg) || pkg.equals(foregroundPackage)) continue;
            String reason = null;
            if (!AppSafety.isEligibleForManualFreeze(context, pkg)) reason = "inelegível/protegido";
            else if (AppSafety.isNeverFreeze(context, pkg)) reason = "lista nunca congelar";
            else if (!AppSafety.hasLeftForegroundLongEnough(prefs, pkg)) reason = "aguardando período após uso";
            if (reason != null) {
                prefs.edit().putString("manual_freeze_last_reason", pkg + ": " + reason).apply();
                continue;
            }
            freeze(pkg);
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

    private void freeze(String pkg) {
        if (active.contains(pkg)) return;
        try {
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

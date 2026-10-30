package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;

/** Shizuku-bound privileged service executes Android's ADB shell commands. */
public final class ManualFreezeShizuku {
    private ManualFreezeShizuku() { }

    public static boolean ready(SharedPreferences prefs, IPrivilegedService bridge) {
        int uid = -1;
        try { if (bridge != null) uid = bridge.remoteUid(); }
        catch (Exception ignored) { }
        String method = ManualFreezeShellPolicy.method(uid);
        if (!method.equals(prefs.getString("manual_freeze_shell_method", ""))) {
            prefs.edit().putString("manual_freeze_shell_method", method)
                    .putInt("manual_freeze_shell_uid", uid).apply();
        }
        return ManualFreezeShellPolicy.trustedUid(uid);
    }

    /** Same operation as `adb shell am force-stop --user 0 PACKAGE`, via Shizuku. */
    public static boolean forceStop(SharedPreferences prefs, IPrivilegedService bridge, String pkg) {
        if (!ManualFreezeShellPolicy.safePackage(pkg) || !ready(prefs, bridge)) return false;
        try {
            if (!RestrictionGuard.command(bridge, "am force-stop --user 0 " + pkg)) {
                record(prefs, pkg, false, "am force-stop retornou falha");
                return false;
            }
            // Do not record a successful freeze until the shell confirms no main process.
            String after = bridge.exec("pidof " + pkg + " 2>/dev/null || true");
            boolean verified = ManualFreezeShellPolicy.stopped(after);
            record(prefs, pkg, verified, verified ? "Processo encerrado e verificado por ADB shell"
                    : "PID ainda ativo ou verificação Shizuku indisponível");
            return verified;
        } catch (Exception e) {
            record(prefs, pkg, false, "Erro Shizuku: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static void record(SharedPreferences p, String pkg, boolean done, String detail) {
        p.edit().putLong("manual_freeze_shell_verified_at", System.currentTimeMillis())
                .putString("manual_freeze_shell_last_pkg", pkg)
                .putBoolean("manual_freeze_shell_last_success", done)
                .putString("manual_freeze_shell_last_status", detail).apply();
    }
}

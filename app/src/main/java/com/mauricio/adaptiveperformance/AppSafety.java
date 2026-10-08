package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public final class AppSafety {
    private AppSafety() {}

    public static boolean isCritical(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(context.getPackageName())) return true;
        if (pkg.startsWith("com.mauricio.")) return true;
        if (pkg.startsWith("io.appium.")) return true;
        if (pkg.equals("android") || pkg.startsWith("android.")) return true;
        if (pkg.startsWith("com.android.systemui")) return true;
        if (pkg.startsWith("com.android.phone")) return true;
        if (pkg.startsWith("com.android.server")) return true;
        if (pkg.startsWith("com.android.providers.")) return true;
        if (pkg.startsWith("com.android.inputmethod")) return true;
        if (pkg.startsWith("com.google.android.inputmethod")) return true;
        if (pkg.startsWith("com.google.android.networkstack")) return true;
        if (pkg.startsWith("com.android.networkstack")) return true;
        if (pkg.startsWith("com.miui.security")) return true;
        if (pkg.startsWith("com.lbe.security")) return true;
        if (pkg.startsWith("com.google.android.providers.")) return true;
        if (pkg.startsWith("com.google.android.ext.")) return true;
        if (pkg.startsWith("com.miui.home")) return true;
        if (pkg.startsWith("com.miui.powerkeeper")) return true;
        if (pkg.startsWith("com.xiaomi.xmsf")) return true;
        return pkg.equals("moe.shizuku.privileged.api")
                || pkg.equals("com.termux")
                || pkg.startsWith("com.termux.")
                || pkg.equals("com.openai.chatgpt")
                || pkg.equals("com.whatsapp")
                || pkg.equals("com.waze")
                || pkg.equals("com.google.android.gms")
                || pkg.equals("com.google.android.gsf")
                || pkg.equals("com.android.vending")
                || pkg.equals("com.google.android.euicc")
                || pkg.equals("com.google.android.cellbroadcastreceiver")
                || pkg.equals("com.google.android.apps.safetyhub")
                || pkg.equals("com.google.android.as")
                || pkg.equals("com.google.android.rkpdapp")
                || pkg.equals("com.mediatek.duraspeed")
                || pkg.equals("com.android.mms")
                || pkg.equals("com.xiaomi.account")
                || pkg.equals("com.google.android.permissioncontroller")
                || pkg.equals("com.google.android.webview")
                || pkg.equals("com.android.webview")
                || pkg.equals("com.android.keychain")
                || pkg.equals("com.google.android.apps.messaging")
                || pkg.equals("com.android.settings")
                || pkg.equals("com.android.permissioncontroller")
                || pkg.equals("com.android.bluetooth")
                || pkg.equals("com.android.nfc")
                || pkg.equals("com.mi.android.globallauncher");
    }

    public static boolean isAppUidCandidate(Context context, String pkg) {
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(pkg, 0);
            int appId = ai.uid % 100000;
            if (appId < 10000) return false;
            return (ai.flags & ApplicationInfo.FLAG_PERSISTENT) == 0;
        } catch (Throwable t) { return false; }
    }

    public static boolean isUserFacing(Context context, String pkg) {
        try { return context.getPackageManager().getLaunchIntentForPackage(pkg) != null; }
        catch (Throwable t) { return false; }
    }

    public static boolean isAutoProtected(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        try {
            java.util.Set<String> set = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE)
                    .getStringSet("auto_protected_apps", java.util.Collections.emptySet());
            return set != null && set.contains(pkg);
        } catch (Throwable t) { return false; }
    }

    /**
     * Pacotes seguros para otimização adaptativa reversível em segundo plano.
     * Não depende da lista pessoal de exceções nem do sinalizador FLAG_SYSTEM:
     * apps em uso, serviços ativos e componentes persistentes são filtrados nos
     * controladores que aplicam cada ação.
     */
    public static boolean isEligibleForAdaptiveOptimization(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) return false;
        if (pkg.startsWith("com.mauricio.") || pkg.startsWith("io.appium.")) return false;
        // Mantém a infraestrutura essencial protegida, mas permite pacotes de sistema
        // instalados com UID próprio (>= 10000) e sem processo persistente.
        if (pkg.equals("android") || pkg.startsWith("android.")) return false;
        if (pkg.startsWith("com.android.systemui") || pkg.startsWith("com.android.phone")
                || pkg.startsWith("com.android.server") || pkg.startsWith("com.android.providers.")
                || pkg.startsWith("com.android.inputmethod") || pkg.startsWith("com.google.android.inputmethod")
                || pkg.startsWith("com.android.networkstack") || pkg.startsWith("com.google.android.networkstack")
                || pkg.startsWith("com.miui.security") || pkg.startsWith("com.lbe.security")
                || pkg.startsWith("com.miui.home") || pkg.startsWith("com.miui.powerkeeper")
                || pkg.equals("com.xiaomi.xmsf") || pkg.equals("com.google.android.gms")
                || pkg.equals("com.google.android.gsf") || pkg.equals("com.android.permissioncontroller")
                || pkg.equals("com.google.android.permissioncontroller") || pkg.equals("com.android.settings")
                || pkg.equals("com.android.bluetooth") || pkg.equals("com.android.nfc")
                || pkg.equals("com.android.webview") || pkg.equals("com.google.android.webview")
                || pkg.equals("moe.shizuku.privileged.api")) return false;
        return isAppUidCandidate(context, pkg);
    }

    public static boolean isEligibleForAutomaticRestriction(Context context, String pkg) {
        return !isCritical(context, pkg)
                && !isAutoProtected(context, pkg)
                && isAppUidCandidate(context, pkg)
                && isUserFacing(context, pkg);
    }

    public static boolean isEligibleForManualFreeze(Context context, String pkg) {
        return isEligibleForAdaptiveOptimization(context, pkg);
    }

    public static boolean isFrequentlyUsedAuto(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            android.content.SharedPreferences p = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE);
            if (!"auto".equals(p.getString("user_mode", "auto"))) return false;
            java.util.Set<String> set = p.getStringSet("auto_frequent_apps", java.util.Collections.emptySet());
            return set != null && set.contains(pkg);
        } catch (Throwable t) { return false; }
    }

    /** Lista pessoal de apps que nunca devem ser finalizados/congelados automaticamente. */
    public static boolean isNeverFreeze(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        try {
            java.util.Set<String> never = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE)
                    .getStringSet("never_freeze_apps", java.util.Collections.emptySet());
            if (never != null && never.contains(pkg)) return true;
            if (isFrequentlyUsedAuto(context, pkg)) return true;
            // Migra as exceções criadas nas versões anteriores.
            java.util.Set<String> legacy = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE)
                    .getStringSet("auto_protected_apps", java.util.Collections.emptySet());
            return legacy != null && legacy.contains(pkg);
        } catch (Throwable t) { return false; }
    }

    /** Atualiza a última utilização em primeiro plano e o pacote atualmente ativo. */
    public static void recordForegroundUsage(SharedPreferences prefs, String pkg) {
        if (prefs == null || pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) return;
        long now = System.currentTimeMillis();
        prefs.edit().putString("foreground", pkg)
                .putLong("app_last_foreground_at_" + Integer.toHexString(pkg.hashCode()), now)
                .apply();
    }

    /** Aguarda o período configurado após o app sair do primeiro plano. */
    public static boolean hasLeftForegroundLongEnough(SharedPreferences prefs, String pkg) {
        if (prefs == null || pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(prefs.getString("foreground", ""))) return false;
        long delayMinutes = Math.max(1L, Math.min(360L,
                prefs.getLong("freeze_delay_minutes", 15L)));
        long last = prefs.getLong("app_last_foreground_at_" + Integer.toHexString(pkg.hashCode()), 0L);
        return last <= 0L || System.currentTimeMillis() - last >= delayMinutes * 60_000L;
    }

    public static boolean isSystemApp(Context context, String pkg) {
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Throwable t) { return false; }
    }

    public static String label(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Throwable ignored) {}
        return pkg;
    }
}

package com.mauricio.adaptiveperformance;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;
import java.util.Calendar;
public final class AppProfilePolicy {
    public static String classify(Context c, SharedPreferences p, String pkg) {
        if (!p.getBoolean("app_profiles", true) || pkg == null) return "DEFAULT";
        String s = (pkg + " " + AppSafety.label(c, pkg)).toLowerCase(Locale.ROOT);
        if (s.matches(".*(termux|shizuku|chatgpt|mcp|codex|automation).*")) return "AUTOMATION";
        if (s.matches(".*(waze|maps|navigation|navega).*")) return "REALTIME";
        if (s.matches(".*(dolphin|game|zf3d|gaming|emulator).*")) return "GAME";
        if (s.matches(".*(tiktok|musically|vibes|youtube|video|audio|music|spotify).*")) return "MEDIA";
        if (s.matches(".*(camera|câmera).*")) return "CAMERA";
        String h = Integer.toHexString(pkg.hashCode());
        if (p.getInt("app_learn_n_" + h, 0) >= 40 && p.getFloat("app_cpu_" + h, 0) > 65) return "GAME";
        return "DEFAULT";
    }

    public static String predictedPackage(SharedPreferences p) {
        if (!p.getBoolean("time_usage_learning", true)) return "";
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (p.getInt("hour_top_count_" + hour, 0) < 3) return "";
        return p.getString("hour_top_pkg_" + hour, "");
    }
    public static String predictedProfile(Context c, SharedPreferences p) {
        String pkg = predictedPackage(p);
        return pkg.isEmpty() ? "DEFAULT" : classify(c, p, pkg);
    }

    public static int batch(Context c, SharedPreferences p, String fg, int normal) {
        String profile = classify(c, p, fg);
        p.edit().putString("current_app_profile", profile).apply();
        return profile.equals("GAME") || profile.equals("MEDIA") || profile.equals("CAMERA") ? Math.min(2, normal) : normal;
    }
    public static boolean protectedActive(Context c, SharedPreferences p, String pkg) {
        if (pkg == null || !pkg.equals(p.getString("foreground", ""))) return false;
        String s = classify(c, p, pkg);
        return s.equals("AUTOMATION") || s.equals("REALTIME");
    }
}

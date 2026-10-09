package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.provider.Settings;

/** Low-cost, coarse cohorts for experiments; never implies causality. */
final class ExperimentContext {
    static void sample(Context c, SharedPreferences prefs, String foreground, boolean screenOn) {
        String net = "unknown";
        try {
            ConnectivityManager cm = (ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities cap = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (cap != null) {
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) net = "wifi";
                else if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) net = "cell";
                else if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) net = "ethernet";
                else net = "other";
            } else net = "offline";
        } catch (Throwable ignored) {}
        int brightness = -1;
        try {
            brightness = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Throwable ignored) {}
        PowerManager pm = (PowerManager)c.getSystemService(Context.POWER_SERVICE);
        boolean saver = pm != null && pm.isPowerSaveMode();
        // Cohorts MUST NOT depend on the setting currently being A/B tested.
        String f = foreground == null ? "" : foreground.toLowerCase(java.util.Locale.ROOT);
        String kind = f.contains("camera") ? "camera"
                : (f.contains("maps") || f.contains("waze") || f.contains("navigation")) ? "maps"
                : (f.contains("dolphin") || f.contains("game")) ? "game"
                : (f.contains("tiktok") || f.contains("vibes") || f.contains("youtube")) ? "media"
                : (f.contains("chatgpt") || f.contains("termux")) ? "automation"
                : (f.contains("launcher") || f.contains("miui.home")) ? "launcher" : "other";
        String fingerprint = (screenOn ? "on" : "off") + "|" + net + "|"
                + (screenOn && brightness >= 0 ? brightness / 32 : -1)
                + "|" + (saver ? "saver" : "normal") + "|" + kind;
        String previous = prefs.getString("experiment_context", "");
        if (!fingerprint.equals(previous))
            prefs.edit().putString("experiment_context", fingerprint).apply();
    }
}

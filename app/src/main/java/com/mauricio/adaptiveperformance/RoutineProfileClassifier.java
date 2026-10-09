package com.mauricio.adaptiveperformance;

import java.util.Locale;

/** Lightweight, coarse category hints. Never kill or freeze a foreground app. */
final class RoutineProfileClassifier {
    static String classify(boolean screen, String foreground, boolean game) {
        if (!screen) return "repouso";
        if (game) return "jogos";
        String f=foreground==null ? "" : foreground.toLowerCase(Locale.US);
        if (f.contains("waze") || f.contains("google.android.apps.maps") ||
                f.contains("maposcope") || f.contains("spoke") ||
                f.contains("navigation")) return "navegacao";
        if (f.contains("instagram") || f.contains("tiktok") || f.contains("facebook") ||
                f.contains("reddit") || f.contains("twitter") ||
                f.contains("snapchat")) return "redes_sociais";
        if (f.contains("whatsapp") || f.contains("telegram") || f.contains("signal")) return "mensagens";
        return "uso_geral";
    }
    static boolean neverWarm(String profile) {
        return "repouso".equals(profile) || "navegacao".equals(profile)
                || "jogos".equals(profile);
    }
}

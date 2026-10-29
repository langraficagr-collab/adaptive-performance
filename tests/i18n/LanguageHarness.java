package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;

public class LanguageHarness {
    private static SharedPreferences prefs(String lang) {
        return (SharedPreferences) Proxy.newProxyInstance(
                LanguageHarness.class.getClassLoader(), new Class[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getString") && args != null && args.length > 0 && "app_language".equals(args[0])) return lang;
                    return args != null && args.length > 1 ? args[1] : null;
                });
    }
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        SharedPreferences en = prefs("en"), pt = prefs("pt");
        check("Battery".equals(UiLanguage.tr(en, "Bateria")), "home metrics");
        check("Adaptive GPU rendering".equals(UiLanguage.tr(en, "Renderização/GPU adaptativo")), "GPU status");
        check("Available cleanup categories".equals(UiLanguage.tr(en, "O que pode ser limpo")), "cleanup heading");
        check("App and system caches".equals(UiLanguage.tr(en, "Cache de aplicativos e do sistema")), "cleanup action");
        check("Automatic mode settings".equals(UiLanguage.tr(en, "Configurações do modo Automático")), "settings dialog");
        check(UiLanguage.tr(en, "Potencial seguro identificado: 10 MB").startsWith("Potential safe cleanup:"), "dynamic cleanup");
        check("Bateria".equals(UiLanguage.tr(pt, "Bateria")), "Portuguese preserved");
        check(UiLanguage.tr(en, null) == null, "null handled");
        System.out.println("PASS: English UI, dynamic cleanup, settings, Portuguese and null fallback");
    }
}

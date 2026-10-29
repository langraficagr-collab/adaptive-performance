package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class BatteryFirstHarness {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Map<String, Object> values = new HashMap<>();
        values.put("auto_preload_enabled", true);
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                BatteryFirstHarness.class.getClassLoader(),
                new Class[]{SharedPreferences.Editor.class}, (proxy, method, a) -> {
                    if (method.getName().startsWith("put")) {
                        values.put((String) a[0], a[1]);
                        return proxy;
                    }
                    if (method.getName().equals("remove")) {
                        values.remove((String) a[0]);
                        return proxy;
                    }
                    if (method.getName().equals("commit")) return true;
                    return null;
                });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                BatteryFirstHarness.class.getClassLoader(),
                new Class[]{SharedPreferences.class}, (proxy, method, a) -> {
                    if (method.getName().equals("edit")) return editor;
                    if (method.getName().equals("contains")) return values.containsKey(a[0]);
                    if (method.getName().equals("getAll")) return values;
                    if (method.getName().startsWith("get")) return values.getOrDefault(a[0], a[1]);
                    return null;
                });
        BatteryFirstDefaults.apply(prefs);
        check(!prefs.getBoolean("auto_preload_enabled", true), "migration disables legacy auto preload");
        check(prefs.getBoolean("battery_first_defaults_117", false), "migration is recorded");
        prefs.edit().putBoolean("auto_preload_enabled", true).commit();
        BatteryFirstDefaults.apply(prefs);
        check(prefs.getBoolean("auto_preload_enabled", false), "explicit user opt-in survives restart");
        System.out.println("PASS: migration disables old preload; explicit opt-in remains available");
    }
}

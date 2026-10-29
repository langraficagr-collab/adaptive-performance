package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Plain-Java migration test with durable (commit/apply) preference semantics. */
public final class BatteryFirstHarness {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class MemoryPrefs implements InvocationHandler {
        final Map<String, Object> stored;
        MemoryPrefs(Map<String, Object> snapshot) {
            stored = new HashMap<>(snapshot);
        }
        SharedPreferences open() {
            return (SharedPreferences) Proxy.newProxyInstance(
                    BatteryFirstHarness.class.getClassLoader(),
                    new Class[]{SharedPreferences.class}, this);
        }
        @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            String name = method.getName();
            if ("edit".equals(name)) return editor();
            if ("contains".equals(name)) return stored.containsKey(args[0]);
            if ("getAll".equals(name)) return new HashMap<>(stored);
            if (name.startsWith("get") && args != null && args.length > 1)
                return stored.getOrDefault(args[0], args[1]);
            return null;
        }
        private SharedPreferences.Editor editor() {
            Map<String, Object> pending = new HashMap<>();
            Set<String> deleted = new HashSet<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    BatteryFirstHarness.class.getClassLoader(),
                    new Class[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("put")) {
                            String key = (String) args[0];
                            Object value = args[1];
                            if (value instanceof Set) value = new HashSet<>((Set<?>) value);
                            pending.put(key, value);
                            deleted.remove(key);
                            return proxy;
                        }
                        if ("remove".equals(name)) {
                            String key = (String) args[0];
                            deleted.add(key);
                            pending.remove(key);
                            return proxy;
                        }
                        if ("clear".equals(name)) {
                            deleted.addAll(stored.keySet());
                            pending.clear();
                            return proxy;
                        }
                        if ("commit".equals(name) || "apply".equals(name)) {
                            for (String key : deleted) stored.remove(key);
                            stored.putAll(pending);
                            return "commit".equals(name) ? Boolean.TRUE : null;
                        }
                        return null;
                    });
        }
    }

    public static void main(String[] args) {
        Map<String, Object> before = new HashMap<>();
        before.put("auto_preload_enabled", true);
        before.put("auto_preload_monitor_pending", true);
        before.put("auto_preload_count", 4);
        before.put("auto_preload_apps", new HashSet<>(java.util.Arrays.asList("legacy.app")));
        MemoryPrefs disk = new MemoryPrefs(before);
        SharedPreferences first = disk.open();

        BatteryFirstDefaults.apply(first);
        check(!first.getBoolean("auto_preload_enabled", true),
                "migration disables old automatic preload");
        check(!first.getBoolean("auto_preload_monitor_pending", true),
                "migration clears stale pending monitor");
        check(first.getInt("auto_preload_count", -1) == 0,
                "migration resets preload count");
        check(first.getStringSet("auto_preload_apps", java.util.Collections.emptySet()).isEmpty(),
                "migration clears old preload apps");
        check(first.getBoolean("battery_first_defaults_117", false),
                "migration records durable completion");

        SharedPreferences restarted = new MemoryPrefs(disk.stored).open();
        check(restarted.getBoolean("battery_first_defaults_117", false),
                "migration survives process restart");
        check(!restarted.getBoolean("auto_preload_monitor_pending", true),
                "pending monitor remains cleared after restart");
        restarted.edit().putBoolean("auto_preload_enabled", true).commit();
        BatteryFirstDefaults.apply(restarted);
        check(restarted.getBoolean("auto_preload_enabled", false),
                "explicit user opt-in survives subsequent migration calls");
        System.out.println("PASS: durable migration, pending reset, legacy apps cleared, user opt-in");
    }
}

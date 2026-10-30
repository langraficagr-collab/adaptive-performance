package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** JVM test: editor writes become visible only after commit/apply, including restarts. */
public final class BatteryFirstHarness {
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }

    static SharedPreferences reboot(Map<String,Object> disk) {
        return (SharedPreferences) Proxy.newProxyInstance(
            BatteryFirstHarness.class.getClassLoader(), new Class[]{SharedPreferences.class},
            (prefs, method, args) -> {
                if ("edit".equals(method.getName())) {
                    Map<String,Object> staged = new HashMap<>();
                    Set<String> removed = new HashSet<>();
                    return Proxy.newProxyInstance(BatteryFirstHarness.class.getClassLoader(),
                        new Class[]{SharedPreferences.Editor.class}, (editor, op, a) -> {
                            String name = op.getName();
                            if (name.startsWith("put")) {
                                staged.put((String) a[0], a[1]);
                                removed.remove((String) a[0]);
                                return editor;
                            }
                            if ("remove".equals(name)) {
                                removed.add((String) a[0]);
                                staged.remove((String) a[0]);
                                return editor;
                            }
                            if ("commit".equals(name) || "apply".equals(name)) {
                                for (String key : removed) disk.remove(key);
                                disk.putAll(staged);
                                return "commit".equals(name) ? true : null;
                            }
                            return null;
                        });
                }
                if ("contains".equals(method.getName())) return disk.containsKey(args[0]);
                if ("getAll".equals(method.getName())) return new HashMap<>(disk);
                if (method.getName().startsWith("get"))
                    return disk.getOrDefault(args[0], args[1]);
                return null;
            });
    }

    public static void main(String[] args) {
        Map<String,Object> disk = new HashMap<>();
        disk.put("auto_preload_enabled", true);
        disk.put("auto_preload_monitor_pending", true);
        disk.put("auto_preload_apps", new HashSet<>(Collections.singleton("com.sample.app")));
        disk.put("zram_profile", "extreme");
        SharedPreferences first = reboot(disk);
        BatteryFirstDefaults.apply(first);
        check(!first.getBoolean("auto_preload_enabled", true), "legacy preloading disabled");
        check(!first.getBoolean("auto_preload_monitor_pending", true), "pending monitor reset");
        check(first.getStringSet("auto_preload_apps", Collections.emptySet()).isEmpty(), "stale preloaded apps cleared");
        check("normal".equals(first.getString("zram_profile", "")), "legacy extreme zram normalized");
        check(first.getBoolean("battery_first_defaults_117", false), "migration flag persisted");

        // New instance simulates process restart; editor never mutates disk until commit.
        SharedPreferences second = reboot(disk);
        second.edit().putBoolean("auto_preload_enabled", true); // not persisted yet
        check(!second.getBoolean("auto_preload_enabled", true), "uncommitted edit invisible");
        second.edit().putBoolean("auto_preload_enabled", true).commit();
        SharedPreferences third = reboot(disk);
        BatteryFirstDefaults.apply(third);
        check(third.getBoolean("auto_preload_enabled", false), "manual opt-in survives restart");
        check(third.getBoolean("zram_profile_normalized_118", false), "new zram migration persisted");
        // Upgrade from v1.11.8: old migration already ran but stale app list persists.
        Map<String,Object> old = new HashMap<>();
        old.put("battery_first_defaults_117", true);
        old.put("auto_preload_enabled", false);
        old.put("auto_preload_apps", new HashSet<>(Collections.singleton("com.old.cached")));
        old.put("auto_preload_count", 1);
        SharedPreferences upgraded = reboot(old);
        BatteryFirstDefaults.apply(upgraded);
        check(upgraded.getStringSet("auto_preload_apps", Collections.emptySet()).isEmpty(),
                "old installation stale preloaded app list cleared");
        check(upgraded.getInt("auto_preload_count", -1) == 0, "old preload count reset");
        System.out.println("PASS: migration, read-after-commit, old upgrade, ZRAM, preload and restart");
    }
}

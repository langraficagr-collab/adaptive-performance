package com.mauricio.adaptiveperformance;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Standalone: javac -d /tmp/ap-cache app/src/main/java/com/mauricio/adaptiveperformance/PreloadAllocationPolicy.java tests/preload/PreloadAllocationPolicyHarness.java && java -cp /tmp/ap-cache com.mauricio.adaptiveperformance.PreloadAllocationPolicyHarness */
public final class PreloadAllocationPolicyHarness {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static int sum(Map<String,Integer> amounts) {
        int sum = 0;
        for (int n : amounts.values()) sum += n;
        return sum;
    }
    public static void main(String[] args) {
        List<String> names = Arrays.asList("com.demo.a", "com.demo.b", "com.demo.c",
                "com.demo.d", "com.demo.e", "com.demo.f", "com.demo.g", "com.demo.h", "com.demo.i");
        Map<String,Integer> requested = new HashMap<>();
        requested.put("com.demo.a", 128);
        requested.put("com.demo.b", 8);
        requested.put("com.demo.c", 64);

        LinkedHashMap<String,Integer> custom = PreloadAllocationPolicy.allocate(names,
                requested, 3, 48);
        check(custom.size() == 3, "manual limit not respected");
        check(sum(custom) <= 48, "exceeded total memory I/O budget");
        check(custom.get("com.demo.b") <= 8, "per-app quota ignored");
        check(custom.get("com.demo.a") >= 8, "first selected app omitted");
        check(custom.values().stream().allMatch(v -> v >= 8 && v <= 128 && v % 8 == 0),
                "quota not aligned");

        LinkedHashMap<String,Integer> low = PreloadAllocationPolicy.allocate(names,
                requested, 8, 16);
        check(low.size() <= 2 && sum(low) <= 16, "low-memory fallback failed");
        check(PreloadAllocationPolicy.allocate(names, requested, 8, 0).isEmpty(),
                "zero memory must not preload");
        check(PreloadAllocationPolicy.allocate(names, requested, 8, -1).isEmpty(),
                "negative budget must not preload");

        LinkedHashMap<String,Integer> huge = PreloadAllocationPolicy.allocate(names,
                requested, 999, 10000);
        check(huge.size() <= 8 && sum(huge) <= 256, "hard global bounds not enforced");

        LinkedHashMap<String,Integer> duplicate = PreloadAllocationPolicy.allocate(
                Arrays.asList("com.demo.a", "com.demo.a", "bad;package", "com.demo.b"),
                requested, 8, 64);
        check(duplicate.size() == 2 && !duplicate.containsKey("bad;package"),
                "unsafe package or duplicate included");

        LinkedHashMap<String,Integer> mixed = PreloadAllocationPolicy.allocate(
                Arrays.asList("com.demo.a", "com.demo.b"), requested, 2, 40);
        check(mixed.get("com.demo.b") == 8, "custom low quota must be honored");
        check(sum(mixed) <= 40, "mixed quotas exceed budget");
        System.out.println("PreloadAllocationPolicyHarness PASS");
    }
}

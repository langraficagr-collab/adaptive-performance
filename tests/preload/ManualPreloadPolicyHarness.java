package com.mauricio.adaptiveperformance;

/** Regression tests: manual cache refresh is independent of automatic cadence. */
public final class ManualPreloadPolicyHarness {
    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
    public static void main(String[] args) {
        check(ManualPreloadPolicy.intervalMinutes(-8) == 1, "negative interval");
        check(ManualPreloadPolicy.intervalMinutes(0) == 1, "zero interval");
        check(ManualPreloadPolicy.intervalMinutes(5) == 5, "custom interval");
        check(ManualPreloadPolicy.intervalMinutes(500) == 60, "upper bound");
        long now = 123456789L;
        check(ManualPreloadPolicy.due(now, 0L, 5), "first manual cycle must be immediate");
        check(!ManualPreloadPolicy.due(now, now - 299999L, 5), "must respect custom interval");
        check(ManualPreloadPolicy.due(now, now - 300000L, 5), "must run when due");
        check(ManualPreloadPolicy.due(now, now + 50000L, 5), "clock reset must not block");
        check(ManualPreloadPolicy.due(now, now - 60000L, 1), "one-minute option");
        check(!ManualPreloadPolicy.due(now, now - 60000L, 15), "fifteen-minute option");
        check(ManualPreloadPolicy.hardPause(55.0f, 40.0f, 34.0, 70) == null,
                "ordinary CPU temperature must not veto manual request");
        check(ManualPreloadPolicy.hardPause(68.0f, 40.0f, 34.0, 70) != null,
                "critical SoC must pause");
        check(ManualPreloadPolicy.hardPause(48.0f, 45.0f, 34.0, 70) != null,
                "critical battery temperature must pause");
        check(ManualPreloadPolicy.hardPause(45.0f, 34.0f, 9.0, 70) != null,
                "critical RAM must pause");
        check(ManualPreloadPolicy.hardPause(45.0f, 34.0f, 34.0, 4) != null,
                "critical battery level must pause");
        check(ManualPreloadPolicy.memoryBudgetMb(40.0) == 192,
                "manual memory budget ignores ordinary heat");
        check(ManualPreloadPolicy.memoryBudgetMb(13.0) == 32,
                "low RAM fallback");
        System.out.println("ManualPreloadPolicyHarness PASS");
    }
}

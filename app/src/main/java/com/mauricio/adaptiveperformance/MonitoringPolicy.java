package com.mauricio.adaptiveperformance;

/** Pure policy shared by the service, UI sampler and regression tests. */
final class MonitoringPolicy {
    private MonitoringPolicy() {}
    static long minimumDelay(boolean interactive, boolean urgent, boolean diagnostic) {
        if (!interactive) return 600_000L;
        return diagnostic ? 30_000L : urgent ? 60_000L : 120_000L;
    }
    static long frameBudgetNs(float hz, long deadlineNs) {
        if (deadlineNs > 0 && deadlineNs < 100_000_000L) return deadlineNs;
        if (!Float.isFinite(hz) || hz < 24f || hz > 360f) hz = 60f;
        return Math.round(1_000_000_000d / hz);
    }
    // Conservative noise screen, not a claim of statistical independence/causality.
    static boolean clearPowerReduction(float base, float baseCv, int baseN,
                                       float test, float testCv, int testN) {
        if (!Float.isFinite(base) || !Float.isFinite(test) || base <= 0 || test <= 0
                || !Float.isFinite(baseCv) || !Float.isFinite(testCv)
                || baseCv < 0 || testCv < 0 || baseCv > .40f || testCv > .40f
                || baseN < 12 || testN < 12) return false;
        double noise = 2.2d * Math.sqrt(Math.pow(base * baseCv, 2) / (baseN - 1)
                + Math.pow(test * testCv, 2) / (testN - 1));
        return test <= base * .93f && base - test > noise;
    }
}

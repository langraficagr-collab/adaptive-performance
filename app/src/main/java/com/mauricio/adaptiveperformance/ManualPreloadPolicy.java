package com.mauricio.adaptiveperformance;

/** Manual page-cache refresh policy; not a process memory pin or Android LMK override. */
final class ManualPreloadPolicy {
    private ManualPreloadPolicy() {}

    static int intervalMinutes(int requested) {
        return Math.max(1, Math.min(60, requested));
    }

    static boolean due(long now, long previous, int interval) {
        return previous <= 0L || previous > now
                || now - previous >= intervalMinutes(interval) * 60_000L;
    }

    /** True emergency limits only. Normal warmth, CPU load and ML are not vetoes. */
    static String hardPause(float socTemperatureC, float batteryTemperatureC,
                            double availableRamPct, int batteryPct) {
        if (batteryTemperatureC >= 45f) return "temperatura crítica da bateria";
        if (socTemperatureC >= 67f) return "temperatura crítica do processador";
        if (availableRamPct < 10.0) return "RAM insuficiente para pré-carregar";
        if (batteryPct >= 0 && batteryPct <= 5) return "bateria crítica";
        return null;
    }

    static int memoryBudgetMb(double availablePct) {
        if (availablePct < 10.0) return 0;
        if (availablePct >= 55.0) return 256;
        if (availablePct >= 40.0) return 192;
        if (availablePct >= 30.0) return 128;
        if (availablePct >= 22.0) return 96;
        if (availablePct >= 16.0) return 64;
        return 32;
    }
}

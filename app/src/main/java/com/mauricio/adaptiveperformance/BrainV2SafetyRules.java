package com.mauricio.adaptiveperformance;

/** Pure decision rules, separate from Android I/O for regression tests. */
final class BrainV2SafetyRules {
    private BrainV2SafetyRules(){}
    static boolean thermalRisk(float battery, float skin, float soc, boolean nativeRisk,
                               boolean hasPrior, float risePerMinute, float fiveMin) {
        return battery >= 39f || (skin>0f && skin>=44f) ||
                (soc>0f && soc>=61f) || nativeRisk ||
                (hasPrior && risePerMinute > 0.16f && fiveMin >= 39f);
    }
    static boolean ramRisk(double availablePercent, boolean hasPrior, float tenMinForecast) {
        return availablePercent<18d || (hasPrior && tenMinForecast<17f);
    }
    static boolean contextualAvoid(int samples, boolean policyEnabled, int bad, int good) {
        return policyEnabled && samples>=48 && bad>=3 && bad>=good;
    }
    static boolean regression(float riseC, float lostRamPct) {
        return (riseC>=1.5f && lostRamPct>=5f) || riseC>=2.6f;
    }
}

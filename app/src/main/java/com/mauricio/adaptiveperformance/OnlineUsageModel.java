package com.mauricio.adaptiveperformance;

import java.util.Locale;

/**
 * Small online binary logistic regressors (activity and thermal risk).
 * Pure Java, fixed size, no inference runtime or network dependency.
 * Trains on a FUTURE observation using the previous observation's features,
 * preventing training and scoring on the same outcome.
 */
final class OnlineUsageModel {
    static final int FEATURES = 11;
    static final int MIN_DECISION_SAMPLES = 48;
    static final int MAX_SAMPLES = 50000;
    private static final float MAX_WEIGHT = 3.0f;
    final float[] activity = new float[FEATURES];
    final float[] heat = new float[FEATURES];
    final int[] hourlyObservations = new int[6];
    final int[] hourlyActivity = new int[6];
    int samples;
    float activityScore = -1f;
    float heatScore = -1f;

    static float[] features(boolean interactive, boolean charging, int battery,
                            float batteryTemp, double cpu, double freeRam,
                            int hour, boolean weekend) {
        float[] x = new float[FEATURES];
        x[0] = 1f;
        x[1] = interactive ? 1f : 0f;
        x[2] = charging ? 1f : 0f;
        x[3] = bounded((batteryTemp - 30f) / 15f, -0.5f, 1.5f);
        x[4] = cpu < 0 ? 0.35f : bounded((float)cpu / 100f, 0f, 1f);
        x[5] = freeRam < 0 ? 0.4f : bounded(1f - (float)freeRam / 100f, 0f, 1f);
        x[6] = battery < 0 ? 0f : bounded((50f - battery) / 50f, 0f, 1f);
        x[7] = (float)Math.sin(2d * Math.PI * hour / 24d);
        x[8] = (float)Math.cos(2d * Math.PI * hour / 24d);
        x[9] = weekend ? 1f : 0f;
        // Interaction with charging: heat during foreground-heavy charging.
        x[10] = charging && interactive ? 1f : 0f;
        return x;
    }

    static float bounded(float x, float lo, float hi) {
        if (!Float.isFinite(x)) return lo;
        return Math.max(lo, Math.min(hi, x));
    }

    static float predict(float[] w, float[] x) {
        if (w.length != FEATURES || x.length != FEATURES) throw new IllegalArgumentException("features");
        double z = 0d;
        for (int i=0;i<FEATURES;i++) z += w[i]*x[i];
        z = Math.max(-12d, Math.min(12d, z));
        return (float)(1d / (1d + Math.exp(-z)));
    }

    float predictActivity(float[] x) { return predict(activity, x); }
    float predictHeat(float[] x) { return predict(heat, x); }
    boolean ready() { return samples >= MIN_DECISION_SAMPLES; }

    void learn(float[] prior, boolean nextActive, boolean nextHot, int hour) {
        if (prior == null || prior.length != FEATURES) return;
        float a = predictActivity(prior);
        float h = predictHeat(prior);
        activityScore = updateScore(activityScore, a, nextActive);
        heatScore = updateScore(heatScore, h, nextHot);
        float rate = 0.11f / (1f + samples / 280f);
        applyGradient(activity, prior, nextActive ? 1f : 0f, a, rate);
        applyGradient(heat, prior, nextHot ? 1f : 0f, h, rate);
        samples = Math.min(MAX_SAMPLES, samples+1);
        int slot = Math.floorMod(hour,24) / 4;
        // Fixed-size, ageing counters prevent stale habits from lasting forever.
        if (hourlyObservations[slot] > 2000) {
            for (int i=0;i<6;i++) {
                hourlyObservations[i] /= 2;
                hourlyActivity[i] /= 2;
            }
        }
        hourlyObservations[slot]++;
        if (nextActive) hourlyActivity[slot]++;
    }

    private static float updateScore(float old, float prediction, boolean target) {
        float correctness = 1f - Math.abs((target ? 1f : 0f) - prediction);
        return old < 0f ? correctness : old * 0.96f + correctness * 0.04f;
    }

    private static void applyGradient(float[] weights, float[] x, float expected,
                                      float predicted, float rate) {
        float difference = expected-predicted;
        for (int i=0;i<FEATURES;i++)
            weights[i] = bounded(weights[i]*(1f-0.00008f)
                    + rate*difference*x[i], -MAX_WEIGHT, MAX_WEIGHT);
    }

    String peakWindow() {
        int index = -1; float best = -1;
        for (int i=0;i<6;i++) {
            int total=hourlyObservations[i];
            if (total < 8) continue;
            float rate = (float)hourlyActivity[i] / total;
            if (rate > best) { best=rate; index=i; }
        }
        if (index < 0) return "";
        return String.format(Locale.US,"%02dh–%02dh",index*4,(index+1)*4);
    }

    String serialize() {
        StringBuilder s = new StringBuilder();
        s.append("1,").append(samples).append(',').append(activityScore)
                .append(',').append(heatScore);
        for (float v : activity) s.append(',').append(v);
        for (float v : heat) s.append(',').append(v);
        for (int v : hourlyObservations) s.append(',').append(v);
        for (int v : hourlyActivity) s.append(',').append(v);
        return s.toString();
    }

    static OnlineUsageModel parse(String saved) {
        OnlineUsageModel m=new OnlineUsageModel();
        if (saved==null || saved.isEmpty()) return m;
        try {
            String[] a=saved.split(",");
            if (a.length != 4 + 2*FEATURES + 12 || !"1".equals(a[0])) return m;
            int n=Integer.parseInt(a[1]);
            if (n < 0 || n > MAX_SAMPLES) return m;
            m.samples=n;
            m.activityScore=Float.parseFloat(a[2]);
            m.heatScore=Float.parseFloat(a[3]);
            int j=4;
            for (int i=0;i<FEATURES;i++) m.activity[i]=Float.parseFloat(a[j++]);
            for (int i=0;i<FEATURES;i++) m.heat[i]=Float.parseFloat(a[j++]);
            for (int i=0;i<6;i++) m.hourlyObservations[i]=Integer.parseInt(a[j++]);
            for (int i=0;i<6;i++) m.hourlyActivity[i]=Integer.parseInt(a[j++]);
            for (float v:m.activity) if (!Float.isFinite(v) || Math.abs(v)>MAX_WEIGHT) return new OnlineUsageModel();
            for (float v:m.heat) if (!Float.isFinite(v) || Math.abs(v)>MAX_WEIGHT) return new OnlineUsageModel();
            if (!Float.isFinite(m.activityScore) || !Float.isFinite(m.heatScore))
                return new OnlineUsageModel();
            for (int v:m.hourlyObservations) if (v < 0 || v > 2001) return new OnlineUsageModel();
            for (int i=0;i<6;i++)
                if (m.hourlyActivity[i]<0 || m.hourlyActivity[i]>m.hourlyObservations[i])
                    return new OnlineUsageModel();
            return m;
        } catch (RuntimeException error) { return new OnlineUsageModel(); }
    }
}

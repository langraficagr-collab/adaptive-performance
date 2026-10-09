package com.mauricio.adaptiveperformance;

import java.util.Arrays;

public final class LocalMlHarness {
    static void check(boolean b,String msg) { if (!b) throw new AssertionError(msg); }
    public static void main(String[] a) {
        OnlineUsageModel m = new OnlineUsageModel();
        float[] idle = OnlineUsageModel.features(false,false,70,34f,6,52,3,false);
        float[] busy = OnlineUsageModel.features(true,false,75,39f,83,20,20,false);
        check(OnlineUsageModel.FEATURES==idle.length,"feature vector");
        check(m.predictHeat(idle)>0 && m.predictHeat(idle)<1,"untrained prior");
        check(!m.ready(),"cannot enable policy before training");
        OnlineUsageModel confidence = new OnlineUsageModel();
        for (int i=0;i<47;i++) confidence.learn(idle,false,false,3);
        check(!confidence.ready(),"47 samples must remain observation-only");
        confidence.learn(idle,false,false,3);
        check(confidence.ready(),"48 samples may enable safe optional-preload policy");
        // Separable realistic toy sequence. Online model should learn both labels.
        for(int n=0;n<480;n++) {
            boolean active = (n%2)==0;
            m.learn(active ? busy : idle, active, active, active ? 20 : 3);
        }
        check(m.ready(),"learned enough samples");
        check(m.predictActivity(busy)>m.predictActivity(idle)+0.35f,
                "active routine should score higher");
        check(m.predictHeat(busy)>m.predictHeat(idle)+0.35f,
                "hot context should score higher");
        String encoded=m.serialize();
        check(encoded.length()<1400,"model should be compact");
        OnlineUsageModel roundTrip=OnlineUsageModel.parse(encoded);
        check(roundTrip.samples==m.samples,"training count survives restart");
        check(Math.abs(roundTrip.predictHeat(busy)-m.predictHeat(busy))<0.00001f,
                "weights survive restart");
        check(!roundTrip.peakWindow().isEmpty(),"hourly habits present");
        check(OnlineUsageModel.parse("corrupted").samples==0,"corrupted values reset safely");
        check(OnlineUsageModel.parse(encoded.replaceFirst("1,", "99,")).samples==0,
                "unknown model schema resets safely");
        float[] broken=Arrays.copyOf(busy,busy.length);
        broken[3]=Float.POSITIVE_INFINITY;
        check(OnlineUsageModel.bounded(Float.NaN,-1f,1f)==-1f,
                "NaN inputs contained");
        check(Float.isFinite(m.predictHeat(idle)) && Float.isFinite(m.predictActivity(busy)),
                "finite predictions");
        System.out.printf(java.util.Locale.US,
            "PASS local ML: activity=%.3f vs %.3f heat=%.3f vs %.3f samples=%d size=%d bytes%n",
            m.predictActivity(busy),m.predictActivity(idle),
            m.predictHeat(busy),m.predictHeat(idle),m.samples,encoded.length());
    }
}

package com.mauricio.adaptiveperformance;
public final class BrainV2Harness {
    static void check(boolean x,String s){if(!x)throw new AssertionError(s);}
    public static void main(String[] args) {
        UsageTransitionModel m = new UsageTransitionModel();
        for(int i=0;i<6;i++) {
            m.observe("com.whatsapp"); m.observe("com.instagram.android");
        }
        check(m.predict("com.whatsapp").equals("com.instagram.android"),"next app prediction");
        check(m.edgeCount()<=UsageTransitionModel.MAX_EDGES,"bounded transitions");
        UsageTransitionModel saved=UsageTransitionModel.parse(m.serialize());
        check(saved.predict("com.whatsapp").equals("com.instagram.android"),"persisted transitions");
        check(UsageTransitionModel.parse("garbage").edgeCount()==0,"corruption safe");
        UsageTransitionModel large = new UsageTransitionModel();
        String prefix = "com.test." + "long".repeat(33);
        for(int i=0;i<40;i++) {
            large.observe(prefix + (i%2==0?".a":".b")+i);
        }
        String bounded = large.serialize();
        check(bounded.length() <= 6500, "serialized model must be bounded");
        check(UsageTransitionModel.parse(bounded).edgeCount()>0,
                "bounded serialized model must remain readable");
        // Oversized early entries must not hide small, more recent transitions.
        // The model holds at most 32 edges, so this builds 27 very long edges,
        // followed by a small, valid transition that must survive serialization.
        UsageTransitionModel mixed = new UsageTransitionModel();
        String largePrefix = "com.app." + "x".repeat(132);
        for(int i=0;i<27;i++) mixed.observe(largePrefix + (char)('A'+i));
        mixed.observe("com.short.left");
        mixed.observe("com.short.right");
        String mixedEncoded=mixed.serialize();
        check(mixedEncoded.length()<=6500,"mixed model must remain bounded");
        check(mixedEncoded.contains("com.short.left>com.short.right:1;"),
                "late short edge must not be lost behind an oversized entry");
        check(UsageTransitionModel.parse(mixedEncoded).edgeCount()>0,
                "mixed model must deserialize correctly");
        check(RoutineProfileClassifier.neverWarm(
                RoutineProfileClassifier.classify(true,"com.google.android.apps.maps",false)),
                "navigation must be preserved");
        check(RoutineProfileClassifier.classify(true,"a.game",true).equals("jogos"),"game");
        check(RoutineProfileClassifier.classify(false,"com.instagram.android",false).equals("repouso"),
                "screen off");
        check(BrainV2SafetyRules.thermalRisk(40f,0f,0f,false,false,0f,40f),
                "hot battery guard");
        check(BrainV2SafetyRules.thermalRisk(36f,0f,0f,false,true,0.3f,40f),
                "predictive warming guard");
        check(!BrainV2SafetyRules.thermalRisk(35f,38f,49f,false,true,-0.1f,34.5f),
                "cool ordinary condition");
        check(BrainV2SafetyRules.ramRisk(24d,true,14.5f),
                "future RAM starvation");
        check(!BrainV2SafetyRules.ramRisk(33d,true,29f),"safe RAM reserve");
        check(!BrainV2SafetyRules.contextualAvoid(47,true,8,0),
                "training safety gate");
        check(!BrainV2SafetyRules.contextualAvoid(60,true,2,0),
                "minimum negative evidence required");
        check(BrainV2SafetyRules.contextualAvoid(60,true,3,1),
                "repeated negative evidence leads only to skipping optional preload");
        check(!BrainV2SafetyRules.contextualAvoid(60,true,3,5),
                "positive evidence removes contextual restriction");
        check(BrainV2SafetyRules.regression(1.6f,6f),
                "joint temperature/RAM regression");
        check(!BrainV2SafetyRules.regression(0.4f,7f),
                "memory change alone does not falsely claim thermal regression");
        System.out.println("PASS: transitions, context gates, prediction, thermal/RAM safety and recovery");
    }
}

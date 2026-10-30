package com.mauricio.adaptiveperformance;
import android.content.SharedPreferences;
import java.util.HashMap;
final class MonitoringHarness {
    static void ok(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
    static int record(SharedPreferences p, String cause, boolean abnormal, long now) {
        SharedPreferences.Editor e = p.edit();
        int count = RecurrenceTracker.record(p, e, cause, abnormal, now); e.commit(); return count;
    }
    public static void main(String[] args) {
        ok(MonitoringPolicy.minimumDelay(true,false,false)==120000,"normal monitoring");
        ok(MonitoringPolicy.minimumDelay(true,true,false)==60000,"thermal/pressure monitoring");
        ok(MonitoringPolicy.minimumDelay(false,true,true)==600000,"screen off remains quiet");
        ok(MonitoringPolicy.minimumDelay(true,false,true)==30000,"explicit diagnostics");
        ok(Math.abs(MonitoringPolicy.frameBudgetNs(60,-1)-16666667)<2,"60Hz");
        ok(Math.abs(MonitoringPolicy.frameBudgetNs(90,-1)-11111111)<2,"90Hz");
        ok(Math.abs(MonitoringPolicy.frameBudgetNs(120,-1)-8333333)<2,"120Hz");
        ok(MonitoringPolicy.frameBudgetNs(120,7000000)==7000000,"platform deadline wins");
        ok(MonitoringPolicy.frameBudgetNs(Float.NaN,-1)>16000000,"unknown display fallback");
        ok(!MonitoringPolicy.clearPowerReduction(5,.01f,4,4,.01f,4),"four readings cannot approve");
        ok(!MonitoringPolicy.clearPowerReduction(5,.4f,12,4.6f,.4f,12),"noise masks apparent 8% gain");
        ok(MonitoringPolicy.clearPowerReduction(5,.03f,16,4,.03f,16),"stable 20% provisional gain");
        ok(!MonitoringPolicy.clearPowerReduction(Float.NaN,0,20,1,0,20),"invalid reading");
        SharedPreferences p=BatteryFirstHarness.reboot(new HashMap<>());
        long start=1000000;
        for (int i=0;i<=20;i++) record(p,"CPU alta",true,start+i*30000);
        ok(p.getInt("recurrence_count",0)==3,"30-second polls must count at 0/5/10 minutes");
        ok(record(p,"RAM",true,start+610000)==1,"new cause resets counter");
        ok(record(p,"RAM",true,start-1)==1,"clock rollback resets window");
        ok(record(p,"",false,start+7*3600000)==0,"expired normal window cleared");
        p.edit().putBoolean("recurrence_guard",false).commit();
        ok(record(p,"CPU",true,start+8*3600000)==0,"disabled tracker cannot escalate strategy");
        System.out.println("PASS: cadence, screen off, frame deadlines, power noise and recurrence");
    }
}

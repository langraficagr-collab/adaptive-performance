package com.mauricio.adaptiveperformance;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import java.util.HashMap;
final class CostGuardHarness {
    static class Bridge implements IPrivilegedService {
        long instance=1, cpu=0, child=0, calls=0, latency=0, timeouts=0;
        public String exec(String cmd){throw new AssertionError("Cost sampling must not spawn commands");}
        public long[] costStats(){return new long[]{instance,cpu,child,calls,latency,timeouts};}
    }
    static void ok(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    public static void main(String[] args){
        SharedPreferences p=BatteryFirstHarness.reboot(new HashMap<>());
        Bridge b=new Bridge();AppCostGuard guard=new AppCostGuard();
        SystemClock.wall=1000;Process.cpu=0;guard.record(p,true,false,b);
        for(int i=0;i<5;i++){
            SystemClock.wall+=120000;Process.cpu+=120;b.cpu+=120;b.child+=4800;
            b.calls+=2;b.latency+=8000;guard.record(p,true,false,b);
        }
        ok(p.getFloat("app_cost_total_cpu_pct",0)>4,"include CPU of bridge and reaped children");
        ok(p.getLong("app_cost_shell_calls",0)==10,"shell call accounting");
        ok(p.getLong("app_cost_shell_ms",0)==40000,"latency accounting");
        ok(p.getBoolean("app_cost_throttled",false),"external cost throttles loop");
        ok(p.getBoolean("app_cost_bridge_complete",false),"complete remote window");
        ok(guard.record(p,true,true,b)==0,"thermal protection never delayed by budget");
        // A new bridge cannot turn its lifetime counters into a huge cost spike.
        b.instance=2;b.cpu=999999;b.child=999999;b.calls=999999;b.latency=999999;
        SystemClock.wall+=120000;guard.record(p,true,false,b);
        for(int i=0;i<4;i++){SystemClock.wall+=120000;guard.record(p,true,false,b);}
        ok(p.getFloat("app_cost_total_cpu_pct",-1)==0,"bridge restart has fresh baseline");
        ok(!p.getBoolean("app_cost_bridge_complete",true),"restart window marked partial");
        for(int i=0;i<5;i++){SystemClock.wall+=120000;guard.record(p,true,false,null);}
        ok(!p.getBoolean("app_cost_bridge_complete",true),"disconnect marked partial");
        ok(p.getFloat("app_cost_estimated_mwh",0)==-1,"no invented battery energy");
        System.out.println("PASS: remote CPU/latency budget, restart, disconnect and thermal priority");
    }
}

package com.mauricio.adaptiveperformance;
import android.content.SharedPreferences;
import java.util.HashMap;
public final class ConservativeHarness {
    static final String P="conservative_tune_";
    static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
    static SharedPreferences baseline(int n,long duration){
        SharedPreferences p=BatteryFirstHarness.reboot(new HashMap<>());
        ConservativeTuningController.initialize(p);
        long now=System.currentTimeMillis();
        p.edit().putBoolean(P+"initialized",true).putBoolean(P+"evidence_11110",true)
          .putInt(P+"phase",1).putInt(P+"samples",n).putString(P+"stage","baseline")
          .putLong(P+"stage_started",now-duration).putLong(P+"window_start",now-duration)
          .putLong(P+"last_sample",now-120001).putLong(P+"window_end",now-120001)
          .putFloat("thermal_soc_c",45).putFloat("jank_pct",1).putLong("jank_sample_at",now)
          .putFloat(P+"sum_temp",30*n).putFloat(P+"sum_cpu",20*n)
          .putFloat(P+"sum_ram",40*n).putFloat(P+"max_temp",30)
          .putFloat(P+"sum_soc",45*n).putFloat(P+"max_soc",45).putInt(P+"soc_samples",n)
          .putFloat(P+"sum_power",5*n).putFloat(P+"sum_power_sq",25*n).putInt(P+"power_samples",n)
          .putFloat(P+"sum_jank",n).putInt(P+"jank_samples",n)
          .putBoolean(P+"window_interactive",true).putInt(P+"battery_start",80)
          .putInt(P+"battery_end",80).commit();
        return p;
    }
    static void evaluate(SharedPreferences p,float temp,double cpu,double ram,boolean on,boolean charge,float power){
        ConservativeTuningController.evaluate(p,80,temp,cpu,ram,on,charge,power);
    }
    static SharedPreferences trial(){
        SharedPreferences p=baseline(15,1801000);
        evaluate(p,30,20,40,true,false,5);
        ok(ConservativeTuningController.trialActive(p),"starts supported trial after 30 min");
        ok(p.getBoolean("app_profiles",false),"candidate enabled");return p;
    }
    static void rolled(SharedPreferences p,String label){
        ok(!p.getBoolean("app_profiles",true),label+" restored setting");
        ok(!ConservativeTuningController.trialActive(p),label+" ended trial");
    }
    public static void main(String[] args){
        SharedPreferences p=baseline(3,121000);evaluate(p,30,20,40,true,false,5);
        ok(!ConservativeTuningController.trialActive(p),"2 min/four readings cannot start trial");
        p=baseline(11,1500000);evaluate(p,30,20,40,true,false,5);
        ok(!ConservativeTuningController.trialActive(p),"12 readings without 30 minutes insufficient");
        p=trial();evaluate(p,32,20,40,true,false,5);rolled(p,"heat");
        p=trial();evaluate(p,30,50,40,true,false,5);rolled(p,"CPU");
        p=trial();evaluate(p,30,20,30,true,false,5);rolled(p,"RAM");
        p=trial();evaluate(p,30,-1,40,true,false,5);rolled(p,"missing CPU");
        p=trial();evaluate(p,30,20,40,false,false,5);rolled(p,"screen change");
        p=trial();evaluate(p,30,20,40,true,true,5);rolled(p,"charging");
        p=trial();p.edit().putLong(P+"deadline",0).commit();evaluate(p,30,20,40,true,false,5);rolled(p,"deadline");
        p=trial();ConservativeTuningController.recoverAfterRestart(p);rolled(p,"restart");
        p=trial();ConservativeTuningController.stopIfNeeded(p);rolled(p,"stop");
        p=trial();p.edit().putString("experiment_context","changed").commit();
        evaluate(p,30,20,40,true,false,5);rolled(p,"context changed");
        p=trial();p.edit().remove(P+"evidence_11110").commit();
        evaluate(p,30,20,40,true,false,5);rolled(p,"old evidence migration");
        p=trial();long now=System.currentTimeMillis();
        p.edit().putLong(P+"stage_started",now-1801000).putLong(P+"window_start",now-1801000)
          .putLong(P+"last_sample",now-120001).putInt(P+"samples",15)
          .putFloat(P+"sum_temp",450).putFloat(P+"sum_cpu",300).putFloat(P+"sum_ram",600)
          .putFloat(P+"max_temp",30).putFloat(P+"sum_soc",675).putFloat(P+"max_soc",45)
          .putInt(P+"soc_samples",15).putFloat(P+"sum_power",60).putFloat(P+"sum_power_sq",240)
          .putInt(P+"power_samples",15).putFloat(P+"sum_jank",15).putInt(P+"jank_samples",15).commit();
        evaluate(p,30,20,40,true,false,4);
        ok("probation".equals(p.getString(P+"stage","")),"stable gain remains provisional");
        ok(!p.getBoolean(P+"accepted_1",false),"not confirmed early");
        p.edit().putLong(P+"probation_start",now-86400001).putLong(P+"probation_last",now)
          .putInt(P+"probation_n",8).putFloat(P+"probation_power",32)
          .putFloat(P+"probation_temp",240).putFloat(P+"probation_cpu",160)
          .putFloat(P+"probation_ram",320).putBoolean(P+"probation_6h_ok",true).commit();
        evaluate(p,30,20,40,true,false,4);
        ok(!p.getBoolean(P+"accepted_1",false),"eight 24h readings insufficient");
        p.edit().putInt(P+"probation_n",12).putFloat(P+"probation_power",48)
          .putFloat(P+"probation_temp",360).putFloat(P+"probation_cpu",240)
          .putFloat(P+"probation_ram",480).commit();
        evaluate(p,30,20,40,true,false,4);
        ok(p.getBoolean(P+"accepted_1",false),"12 matched readings after 24h confirm");
        System.out.println("PASS: stronger evidence, 24h validation, migration and safety rollback");
    }
}

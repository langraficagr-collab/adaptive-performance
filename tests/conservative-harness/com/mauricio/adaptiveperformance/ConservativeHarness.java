package com.mauricio.adaptiveperformance;
import java.util.*;
import android.content.SharedPreferences;
public class ConservativeHarness {
 static final String P="conservative_tune_";
 static class Prefs implements SharedPreferences {
  Map<String,Object> m=new HashMap<>();
  @Override public Map<String,?> getAll(){return new HashMap<>(m);}
  @Override public boolean contains(String k){return m.containsKey(k);}
  @Override public boolean getBoolean(String k,boolean d){return (Boolean)m.getOrDefault(k,d);}
  @Override public int getInt(String k,int d){return (Integer)m.getOrDefault(k,d);}
  @Override public long getLong(String k,long d){return (Long)m.getOrDefault(k,d);}
  @Override public float getFloat(String k,float d){return (Float)m.getOrDefault(k,d);}
  @Override public String getString(String k,String d){return (String)m.getOrDefault(k,d);}
  @Override public Editor edit(){return new Editor(){
   Map<String,Object> changes=new HashMap<>();
   @Override public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}
   @Override public Editor putInt(String k,int v){changes.put(k,v);return this;}
   @Override public Editor putLong(String k,long v){changes.put(k,v);return this;}
   @Override public Editor putFloat(String k,float v){changes.put(k,v);return this;}
   @Override public Editor putString(String k,String v){changes.put(k,v);return this;}
   @Override public Editor remove(String k){changes.put(k,null);return this;}
   @Override public void apply(){commit();}
   @Override public boolean commit(){changes.forEach((k,v)->{if(v==null)m.remove(k);else m.put(k,v);});return true;}
  };}
 }
 static void ok(boolean b,String why){if(!b)throw new AssertionError(why);}
 static Prefs base(){
  Prefs p=new Prefs();ConservativeTuningController.initialize(p);
  long now=System.currentTimeMillis();
  p.edit().putFloat("thermal_soc_c",45).putFloat("jank_pct",1).putLong("jank_sample_at",now)
   .putLong(P+"stage_started",now-901000).putLong(P+"window_start",now-901000)
   .putLong(P+"window_end",now-1000).putLong(P+"last_sample",now-1000)
   .putBoolean(P+"window_interactive",true).putInt(P+"samples",3)
   .putFloat(P+"sum_temp",90).putFloat(P+"sum_cpu",60).putFloat(P+"sum_ram",120)
   .putFloat(P+"max_temp",30).putFloat(P+"sum_soc",135).putFloat(P+"max_soc",45)
   .putInt(P+"soc_samples",3).putFloat(P+"sum_power",15).putInt(P+"power_samples",3)
   .putFloat(P+"sum_jank",3).putInt(P+"jank_samples",3)
   .putInt(P+"battery_start",80).putInt(P+"battery_end",80).commit();
  return p;
 }
 static Prefs trial(){
  Prefs p=base();ConservativeTuningController.evaluate(p,80,30,20,40,true,false,5);
  ok(ConservativeTuningController.trialActive(p),"starts trial");
  ok(p.getBoolean("advanced_adaptive",false),"only candidate enabled");
  ok(!p.getBoolean("app_profiles",true),"next candidate untouched");
  return p;
 }
 static void rolled(Prefs p,String why){
  ok(!p.getBoolean("advanced_adaptive",true),why+" restored setting");
  ok(!ConservativeTuningController.trialActive(p),why+" ended trial");
  ok(!p.getString(P+"restore_pending","").isEmpty(),why+" restoration requested");
 }
 public static void main(String[] args){
  Prefs p=base();ok(p.getInt(P+"option_count",0)==26,"catalogue");
  ok(p.getBoolean("rollback_guard",false)&&p.getBoolean("health_guard",false),"protections");
  ok(!p.getBoolean("smart_auto_cleanup",true)&&!p.getBoolean("manual_freeze_enabled",true),"irreversible disabled");
  p=trial();ConservativeTuningController.evaluate(p,80,32,20,40,true,false,5);rolled(p,"heat");
  p=trial();ConservativeTuningController.evaluate(p,80,30,50,40,true,false,5);rolled(p,"CPU");
  p=trial();ConservativeTuningController.evaluate(p,80,30,20,30,true,false,5);rolled(p,"RAM");
  p=trial();ConservativeTuningController.evaluate(p,80,30,-1,40,true,false,5);rolled(p,"missing CPU");
  p=trial();p.edit().putFloat("jank_pct",6).commit();
  ConservativeTuningController.evaluate(p,80,30,20,40,true,false,5);rolled(p,"jank");
  p=trial();ConservativeTuningController.evaluate(p,80,30,20,40,false,false,5);rolled(p,"screen changed");
  p=trial();ConservativeTuningController.evaluate(p,80,30,20,40,true,true,5);rolled(p,"charging");
  p=trial();p.edit().putLong(P+"deadline",0).commit();
  ConservativeTuningController.evaluate(p,80,30,20,40,true,false,5);rolled(p,"deadline without samples");
  p=trial();ConservativeTuningController.recoverAfterRestart(p);rolled(p,"restart");
  p=trial();ConservativeTuningController.stopIfNeeded(p);rolled(p,"stop");
  p=trial();p.edit().putLong("jank_sample_at",1).commit();
  ConservativeTuningController.evaluate(p,80,30,20,40,true,false,5);rolled(p,"stale jank");
  p=base();p.edit().putInt(P+"phase",13).commit();
  ConservativeTuningController.evaluate(p,80,30,20,40,true,false,5);
  ok(!ConservativeTuningController.trialActive(p),"dependency skip");
  ok(p.getString(P+"last_result","").contains("Não testado"),"skip honest result");
  p=trial();long now=System.currentTimeMillis();
  p.edit().putLong(P+"deadline",now-1).putLong(P+"stage_started",now-901000)
   .putLong(P+"window_start",now-901000).putLong(P+"window_end",now-1000)
   .putLong(P+"last_sample",now-1000).putInt(P+"samples",3)
   .putFloat(P+"sum_temp",90).putFloat(P+"sum_cpu",60).putFloat(P+"sum_ram",120)
   .putFloat(P+"max_temp",30).putFloat(P+"sum_soc",135).putFloat(P+"max_soc",45)
   .putInt(P+"soc_samples",3).putFloat(P+"sum_power",12).putInt(P+"power_samples",3)
   .putFloat(P+"sum_jank",3).putInt(P+"jank_samples",3)
   .putInt(P+"battery_start",80).putInt(P+"battery_end",80).commit();
  ConservativeTuningController.evaluate(p,80,30,20,40,true,false,4);
  ok(p.getBoolean("advanced_adaptive",false),"measurable benefit kept");
  ok(!p.getBoolean(P+"accepted_0",false),"not accepted before 24 hours");
  ok("probation".equals(p.getString(P+"stage","")),"provisional confirmation stage");
  long t=System.currentTimeMillis();
  p.edit().putLong(P+"probation_start",t-86400000L)
   .putLong(P+"probation_last",t).putInt(P+"probation_n",8)
   .putFloat(P+"probation_power",32).putFloat(P+"probation_temp",240)
   .putFloat(P+"probation_cpu",160).putFloat(P+"probation_ram",320)
   .putBoolean(P+"probation_6h_ok",true).commit();
  ConservativeTuningController.evaluate(p,80,30,20,40,true,false,4);
  ok(p.getBoolean(P+"accepted_0",false),"confirmed after 24h with 8 matched samples");
  p=trial();long t2=System.currentTimeMillis();
  p.edit().putString(P+"stage","probation").putLong(P+"probation_start",t2-86400000L)
   .putLong(P+"probation_last",t2).putInt(P+"probation_n",8)
   .putFloat(P+"probation_power",56).putFloat(P+"probation_temp",256)
   .putFloat(P+"probation_cpu",160).putFloat(P+"probation_ram",320).commit();
  ConservativeTuningController.evaluate(p,80,30,20,40,true,false,5);
  rolled(p,"24h regression");
  ok(!ConservativeTuningController.trialActive(p),"one test at a time");
  p=trial(); p.edit().putString("experiment_context", "on|wifi|3|normal|media").commit();
  ConservativeTuningController.evaluate(p,80,30,20,40,true,false,5);
  rolled(p,"workload cohort change");
  p=base(); p.edit().putBoolean(P+"accepted_1",true).commit();
  ok(ConservativeTuningController.evidenceSummary(p).contains("Confirmadas: 0"),
    "legacy approval must not become confirmed without eight samples");
  p=base(); ok(ConservativeTuningController.evidenceSummary(p).contains("Nenhuma economia"),
    "no unsupported savings claims");
  System.out.println("PASS: rollback, 24h confidence, environmental matching, safety and evidence.");
 }
}
package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.BatteryManager;
import android.os.SystemClock;
import java.util.Locale;

/**
 * Ten improvements in one bounded, opportunistic observation pass.
 * Never launches background timers, kills an app, changes charging current,
 * edits OEM thermal state or uses cloud storage.
 */
final class AdaptiveBrainV2 {
    private static final long HOUR_MS = 3600000L;
    private static final long PRELOAD_COOLDOWN_MS=6L*HOUR_MS;
    private final Context context;
    private final SharedPreferences prefs;
    private UsageTransitionModel transitions;
    private int resetSeen;
    private long last=0L;
    private long lastWall=0L;
    private long chargingSince=0L;
    private float lastTemp=-1f, lastRam=-1f;
    private String lastFg="";
    private boolean cachedGame=false;
    private long trackedPreloadAt=0L;
    private long preloadObservedAt=0L;
    private float preloadStartTemp, preloadStartRam;
    private boolean preloadStartInteractive;
    private boolean preloadStartCharging;
    private int preloadContext;

    AdaptiveBrainV2(Context ctx, SharedPreferences p) {
        context=ctx.getApplicationContext();
        prefs=p;
        transitions=UsageTransitionModel.parse(p.getString("v2_transitions",""));
        resetSeen=p.getInt("v2_reset_token",0);
    }

    /** At most once per 60 seconds, on the existing OptimizationService worker. */
    void observe(boolean screen, boolean charging, int level,
                 float batteryTemp, float skin, float soc,
                 double cpu, double freeRam, String fg, boolean nativeRisk) {
        if (!prefs.getBoolean("v2_enabled",true)) return;
        int resetNow=prefs.getInt("v2_reset_token",0);
        if(resetNow!=resetSeen) {
            transitions=new UsageTransitionModel();
            lastFg="";
            trackedPreloadAt=0L;
            preloadObservedAt=0L;
            resetSeen=resetNow;
        }
        long now=SystemClock.elapsedRealtime();
        if(last>0 && now-last<60000L) return;
        long wall=System.currentTimeMillis();
        if(!Float.isFinite(batteryTemp) || batteryTemp<10 || batteryTemp>70
                || !Double.isFinite(freeRam) || freeRam<0 || freeRam>100) return;
        if(!charging) chargingSince=0L;
        else if(chargingSince<=0L) chargingSince=wall;
        String packageName=fg==null?"":fg;
        if(UsageTransitionModel.valid(packageName) && !packageName.equals(lastFg)) {
            transitions.observe(packageName);
            if(prefs.getBoolean("v2_next_app_enabled",true))
                prefs.edit().putString("v2_transitions",transitions.serialize())
                        .putString("v2_next_app",transitions.predict(packageName))
                        .putInt("v2_transition_edges",transitions.edgeCount()).apply();
            lastFg=packageName;
            cachedGame=false;
            if(prefs.getBoolean("v2_routines_enabled",true)) {
                try {
                    ApplicationInfo ai=context.getPackageManager().getApplicationInfo(packageName,0);
                    cachedGame=ai.category==ApplicationInfo.CATEGORY_GAME;
                } catch (Exception ignored) {}
            }
        }
        String routine=RoutineProfileClassifier.classify(screen,packageName,cachedGame);
        boolean hasPrior=last!=0 && now-last<=15L*60L*1000L;
        double dtMinutes=hasPrior ? (now-last)/60000d : 0d;
        float tempSlope=hasPrior && dtMinutes>0.2d
                ? OnlineUsageModel.bounded((float)((batteryTemp-lastTemp)/dtMinutes),-1.5f,1.5f):0f;
        float inFiveMinutes=OnlineUsageModel.bounded(batteryTemp + 5f*tempSlope,0f,65f);
        boolean thermalWarning=BrainV2SafetyRules.thermalRisk(batteryTemp,
                skin,soc,nativeRisk,hasPrior,tempSlope,inFiveMinutes);
        float ramSlope=hasPrior && dtMinutes>0.2d
                ? OnlineUsageModel.bounded((float)((freeRam-lastRam)/dtMinutes),-12f,12f):0f;
        float ramForecast=hasPrior
                ? OnlineUsageModel.bounded((float)freeRam + ramSlope*10f,0f,100f)
                : (float)freeRam;
        boolean ramRisk=BrainV2SafetyRules.ramRisk(freeRam,hasPrior,ramForecast);
        float selfCpu=prefs.getFloat("app_cost_cpu_pct",-1f);
        boolean selfThrottle=selfCpu>4f && prefs.getBoolean("app_cost_throttled",false);
        // Own-app budget always takes precedence over optional experimentation.
        boolean protect=thermalWarning || ramRisk || selfThrottle || charging;
        int ctx=contextId(screen,charging,batteryTemp,freeRam);
        collectPreloadEvidence(wall,batteryTemp,(float)freeRam,screen,charging,ctx,protect);
        boolean lowSampleConfidence=prefs.getInt("ml_training_samples",0)<48;
        boolean strategyAvoid=contextualAvoid(ctx);
        boolean mlAvoid=!lowSampleConfidence && PersonalUsageML.avoidPreload(prefs);
        boolean skipPreload=protect || RoutineProfileClassifier.neverWarm(routine)
                || strategyAvoid || mlAvoid
                || wall<prefs.getLong("v2_preload_cooldown_until",0L);
        String reason=thermalWarning?"aquecimento / previsão térmica"
                : charging?"carregamento"
                : ramRisk?"pressão futura de RAM"
                : selfThrottle?"orçamento de CPU do otimizador"
                : RoutineProfileClassifier.neverWarm(routine)?"perfil "+routine
                : strategyAvoid?"histórico de piora em contexto semelhante"
                : mlAvoid?"previsão ML 2.0"
                : wall<prefs.getLong("v2_preload_cooldown_until",0L)
                    ?"recuperação automática temporária":"";
        BatteryManager bm=(BatteryManager)context.getSystemService(Context.BATTERY_SERVICE);
        long currentMicroA=Long.MIN_VALUE;
        if(bm!=null && charging) {
            try {
                int current=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                // Signed direction is OEM-specific. Treat as magnitude only.
                if(current!=Integer.MIN_VALUE && Math.abs((long)current)<=20000000L)
                    currentMicroA=Math.abs((long)current);
            } catch(RuntimeException ignored) {}
        }
        String chargingNote=charging
                ? String.format(Locale.US,"Carregando • Bat %.1f°C%s%s",batteryTemp,
                    currentMicroA==Long.MIN_VALUE?"":String.format(Locale.US,
                        " • corrente relatada ~%.2f A",currentMicroA/1000000d),
                    thermalWarning?" • alto aquecimento; evitar uso pesado":"")
                : "Fora do carregador";
        if(chargingSince>0L && charging && batteryTemp>=40f &&
                wall-chargingSince>=10L*60L*1000L)
            chargingNote += " • carga quente há mais de 10 min; verificar ventilação";

        SharedPreferences.Editor e=prefs.edit();
        e.putBoolean("v2_thermal_warning",thermalWarning)
                .putFloat("v2_thermal_5min",inFiveMinutes)
                .putFloat("v2_thermal_slope",tempSlope)
                .putBoolean("v2_ram_warning",ramRisk)
                .putFloat("v2_ram_10min",ramForecast)
                .putBoolean("v2_self_budget",selfThrottle)
                .putBoolean("v2_skip_optional_preload",skipPreload)
                .putString("v2_preload_skip_reason",reason)
                .putString("v2_routine",routine)
                .putString("v2_charge_note",chargingNote)
                .putString("v2_strategy",strategyAvoid?"Histórico: pausar pré-carga neste contexto":
                        lowSampleConfidence?"Aprendizado: observando sem decisões adicionais":
                        "Estratégia conservadora: observar resultado")
                .putInt("v2_observations",Math.min(1000000,
                        prefs.getInt("v2_observations",0)+1))
                .putLong("v2_last_observation",wall);
        e.apply();

        last=now;
        lastWall=wall;
        lastTemp=batteryTemp;
        lastRam=(float)freeRam;
    }

    static int contextId(boolean screen, boolean charging, float temp, double ram) {
        return (screen?1:0) | (charging?2:0) | (temp>=37f?4:0) | (ram<30?8:0);
    }

    private boolean contextualAvoid(int ctx){
        if(!prefs.getBoolean("v2_strategy_enabled",true)
                || prefs.getInt("ml_training_samples",0)<48) return false;
        int bad=prefs.getInt("v2_context_bad_"+ctx,0);
        int good=prefs.getInt("v2_context_good_"+ctx,0);
        return BrainV2SafetyRules.contextualAvoid(
                prefs.getInt("ml_training_samples",0),
                prefs.getBoolean("v2_strategy_enabled",true),bad,good);
    }

    /**
     * Observational safety rollback, not a causal proof of power savings.
     * An automatically warmed cache can be paused for 6h after a strong
     * context-matched regression, without altering user preference.
     */
    private void collectPreloadEvidence(long wall, float temp, float ram,
                                        boolean screen, boolean charging,
                                        int context, boolean unsafe) {
        long cycle=prefs.getLong("auto_preload_cycle_at",0L);
        String result=prefs.getString("auto_preload_status","");
        if(cycle>0 && cycle!=trackedPreloadAt) {
            trackedPreloadAt=cycle;
            if(!unsafe && !charging
                    && (result.startsWith("Pré-carregados")
                        || result.startsWith("APKs aquecidos"))
                    && prefs.getInt("auto_preload_count",0)>0
                    && Math.abs(wall-cycle)<15L*60L*1000L) {
                preloadObservedAt=wall;
                preloadStartTemp=temp;
                preloadStartRam=ram;
                preloadStartInteractive=screen;
                preloadStartCharging=charging;
                preloadContext=context;
                prefs.edit().putInt("v2_preload_trials",Math.min(10000,
                        prefs.getInt("v2_preload_trials",0)+1)).apply();
            }
        }
        long gap=wall-preloadObservedAt;
        if(preloadObservedAt<=0L || gap<3L*60L*1000L || gap>20L*60L*1000L) {
            if(gap>20L*60L*1000L) preloadObservedAt=0;
            return;
        }
        if(screen!=preloadStartInteractive || charging!=preloadStartCharging ||
                charging || context!=preloadContext) {
            preloadObservedAt=0;
            return;
        }
        boolean worse=BrainV2SafetyRules.regression(
                temp-preloadStartTemp,preloadStartRam-ram);
        SharedPreferences.Editor e=prefs.edit();
        String key="v2_context_"+(worse?"bad_":"good_")+context;
        e.putInt(key, Math.min(2000,prefs.getInt(key,0)+1));
        if(worse) {
            e.putLong("v2_preload_cooldown_until",wall+PRELOAD_COOLDOWN_MS)
                    .putString("v2_last_rollback",
                            String.format(Locale.US,
                                "Pré-carga opcional pausada 6h: subida de %.1f°C no mesmo contexto",
                                temp-preloadStartTemp))
                    .putInt("v2_auto_rollbacks",prefs.getInt("v2_auto_rollbacks",0)+1);
        } else {
            e.putInt("v2_provisional_ok",prefs.getInt("v2_provisional_ok",0)+1);
        }
        e.apply();
        preloadObservedAt=0;
    }

    static boolean avoidOptionalPreload(SharedPreferences p) {
        if(!p.getBoolean("v2_enabled",true))return false;
        if(System.currentTimeMillis()-p.getLong("v2_last_observation",0L)>20L*60L*1000L)
            return false;
        return p.getBoolean("v2_skip_optional_preload",false);
    }

    static String dashboard(SharedPreferences p, boolean en) {
        if(!p.getBoolean("v2_enabled",true))
            return en?"Adaptive Brain 2 disabled":"Cérebro adaptativo 2 desativado";
        if(en) return String.format(Locale.US,
            "Routine: %s • next app: %s\nThermal forecast (5m): %.1f°C • RAM forecast (10m): %.0f%%\n%s\nOptional preload: %s\nML strategy: %s\nObservation samples: %d • preload trials: %d • provisional stable: %d • reversions: %d\nSelf CPU budget: %s • UI jank: %s\nComparisons are observational; battery savings are NOT proven.",
            p.getString("v2_routine","unknown"), p.getString("v2_next_app","").isEmpty()?"insufficient data":p.getString("v2_next_app",""),
            p.getFloat("v2_thermal_5min",-1f),p.getFloat("v2_ram_10min",-1f),
            p.getString("v2_charge_note",""),
            p.getBoolean("v2_skip_optional_preload",false)?"paused":"allowed",
            p.getString("v2_strategy","observing"),
            p.getInt("v2_observations",0),p.getInt("v2_preload_trials",0),
            p.getInt("v2_provisional_ok",0),p.getInt("v2_auto_rollbacks",0),
            p.getBoolean("v2_self_budget",false)?"throttling optional work":"normal",
            p.getString("v2_ui_jank_note","not yet measured"));
        return String.format(Locale.US,
            "Rotina: %s • próximo app: %s\nPrevisão térmica (5 min): %.1f°C • RAM (10 min): %.0f%%\n%s\nPré-carga opcional: %s (%s)\nEstratégia ML: %s\nObservações: %d • testes de pré-carga: %d • estáveis provisórios: %d • reversões: %d\nOrçamento de CPU próprio: %s • fluidez: %s\nComparações são observacionais; economia de bateria NÃO comprovada.",
            p.getString("v2_routine","desconhecida"),
            p.getString("v2_next_app","").isEmpty()?"ainda aprendendo":p.getString("v2_next_app",""),
            p.getFloat("v2_thermal_5min",-1f),p.getFloat("v2_ram_10min",-1f),
            p.getString("v2_charge_note",""),
            p.getBoolean("v2_skip_optional_preload",false)?"pausada":"permitida",
            p.getString("v2_preload_skip_reason",""),
            p.getString("v2_strategy","observando"),
            p.getInt("v2_observations",0),p.getInt("v2_preload_trials",0),
            p.getInt("v2_provisional_ok",0),p.getInt("v2_auto_rollbacks",0),
            p.getBoolean("v2_self_budget",false)?"limitando tarefas opcionais":"normal",
            p.getString("v2_ui_jank_note","aguardando medição"));
    }

    static void clearLearning(SharedPreferences p) {
        SharedPreferences.Editor e=p.edit();
        for(int i=0;i<16;i++) {
            e.remove("v2_context_bad_"+i).remove("v2_context_good_"+i);
        }
        e.remove("v2_transitions").remove("v2_next_app")
                .remove("v2_transition_edges").remove("v2_last_rollback")
                .remove("v2_preload_cooldown_until").remove("v2_preload_trials")
                .remove("v2_auto_rollbacks").remove("v2_provisional_ok")
                .remove("v2_observations")
                .putInt("v2_reset_token",p.getInt("v2_reset_token",0)+1).apply();
    }
}

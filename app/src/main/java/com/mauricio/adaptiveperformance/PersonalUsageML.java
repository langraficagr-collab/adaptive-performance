package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.Calendar;
import java.util.Locale;

/** Per-device, private lightweight trainer; O(11), once per >=3 minutes. */
final class PersonalUsageML {
    private static final long MIN_OBSERVATION_MS=180000L;
    private static final long MAX_SUPERVISION_MS=900000L;
    private static final String KEY="ml_model_v1";
    private final SharedPreferences prefs;
    private OnlineUsageModel model;
    private float[] previous;
    private long lastObservedElapsed;
    private String previousForeground="";
    private int lastResetSeen;
    private boolean wasHot;
    private float currentHeat, currentActivity;

    PersonalUsageML(SharedPreferences prefs) {
        this.prefs=prefs;
        this.model=OnlineUsageModel.parse(prefs.getString(KEY, ""));
        lastResetSeen=prefs.getInt("ml_reset_token",0);
    }

    void sample(boolean interactive, boolean charging, int battery,
                float temp, double cpu, double ram, String foreground) {
        if (!prefs.getBoolean("ml_enabled", true)) return;
        int reset=prefs.getInt("ml_reset_token",0);
        if (lastResetSeen!=reset) {
            model=new OnlineUsageModel();
            previous=null;
            lastObservedElapsed=0;
            previousForeground="";
            lastResetSeen=reset;
        }
        long now=SystemClock.elapsedRealtime();
        if (lastObservedElapsed!=0 && now-lastObservedElapsed<MIN_OBSERVATION_MS) return;
        // Privileged CPU telemetry is optional; screen/charging/temperature
        // still form a valid local learning signal when Shizuku is disconnected.
        if (temp<=0f || temp>75f || cpu<-1d || !Double.isFinite(cpu)
                || !Double.isFinite(ram) || ram < 0d) return;
        Calendar cal=Calendar.getInstance();
        int hour=cal.get(Calendar.HOUR_OF_DAY);
        int dow=cal.get(Calendar.DAY_OF_WEEK);
        float[] x=OnlineUsageModel.features(interactive,charging,battery,temp,cpu,ram,hour,
                dow==Calendar.SATURDAY || dow==Calendar.SUNDAY);
        boolean active=interactive && (cpu<0d || cpu>=32d
                || (foreground!=null && !foreground.isEmpty()
                    && !foreground.equals(previousForeground)));
        boolean hot=temp>=39f;
        long gap=lastObservedElapsed>0 ? now-lastObservedElapsed : 0L;
        if (previous!=null && gap>=MIN_OBSERVATION_MS && gap<=MAX_SUPERVISION_MS) {
            model.learn(previous,active,hot || (!wasHot && temp>=38.5f),hour);
            prefs.edit().putString(KEY,model.serialize()).apply();
        }
        previous=x;
        previousForeground=foreground==null ? "" : foreground;
        wasHot=hot;
        lastObservedElapsed=now;
        currentActivity=model.predictActivity(x);
        currentHeat=model.predictHeat(x);
        prefs.edit()
                .putInt("ml_training_samples",model.samples)
                .putFloat("ml_activity_probability",currentActivity)
                .putFloat("ml_heat_probability",currentHeat)
                .putBoolean("ml_ready",model.ready())
                .putString("ml_peak_window",model.peakWindow())
                .putLong("ml_last_training_at",System.currentTimeMillis())
                .putString("ml_status",model.ready()
                        ? "Modelo ativo; decisões conservadoras baseadas em hábitos locais"
                        : "Aprendendo no aparelho; sem decisões automáticas até 48 amostras")
                .apply();
    }

    static boolean avoidPreload(SharedPreferences p) {
        if (!p.getBoolean("ml_enabled",true) || !p.getBoolean("ml_ready",false)
                || !"auto".equals(p.getString("user_mode","auto"))) return false;
        float heat=p.getFloat("ml_heat_probability",0.5f);
        float activity=p.getFloat("ml_activity_probability",0.5f);
        if (!Float.isFinite(heat) || !Float.isFinite(activity)) return false;
        // ML may ONLY prevent optional APK preloading (I/O/heat), not force-stop apps.
        return heat>=0.75f || activity<=0.20f;
    }

    static String summary(SharedPreferences p, boolean en) {
        if (!p.getBoolean("ml_enabled",true))
            return en ? "Disabled; predictions have no effect" : "Desativado; previsões sem efeito";
        int n=p.getInt("ml_training_samples",0);
        if (n<OnlineUsageModel.MIN_DECISION_SAMPLES)
            return en ? "Private on-device training: "+n+"/48 samples. No decisions yet."
                    : "Aprendizado privado no aparelho: "+n+"/48 amostras. Sem ações do modelo.";
        int activity=Math.round(p.getFloat("ml_activity_probability",0.5f)*100f);
        int heat=Math.round(p.getFloat("ml_heat_probability",0.5f)*100f);
        String peak=p.getString("ml_peak_window","");
        return en
                ? String.format(Locale.US,
                    "Trained on %d samples • next-period activity %.0f%% • thermal risk %.0f%%%s\nOnly reduces optional background preloading; never freezes apps or overrides Android thermals.",
                    n,(float)activity,(float)heat,peak.isEmpty()?"":" • busiest "+peak)
                : String.format(Locale.US,
                    "%d amostras • atividade futura estimada %d%% • risco térmico estimado %d%%%s\nO modelo só pode pausar a pré-carga opcional. Não congela apps nem altera o controle térmico.",
                    n,activity,heat,peak.isEmpty()?"":" • horário frequente "+peak);
    }

    static void reset(SharedPreferences prefs) {
        prefs.edit().remove(KEY).putInt("ml_training_samples",0)
                .remove("ml_activity_probability").remove("ml_heat_probability")
                .remove("ml_ready").remove("ml_peak_window")
                .remove("ml_last_training_at").remove("ml_status")
                .putInt("ml_reset_token",prefs.getInt("ml_reset_token",0)+1).apply();
    }
}

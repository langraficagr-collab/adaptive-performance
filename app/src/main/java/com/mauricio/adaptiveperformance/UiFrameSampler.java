package com.mauricio.adaptiveperformance;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Window;
import java.util.Locale;

/** Bounded frame-metrics sampling of Adaptive Performance's OWN UI only. */
final class UiFrameSampler {
    private final Activity activity;
    private final SharedPreferences prefs;
    private Window.OnFrameMetricsAvailableListener listener;
    private boolean listening=false, stopPosted=false;
    private int frames=0, dropped=0, slow=0;
    private long duration=0L;
    private long startElapsed;

    UiFrameSampler(Activity activity, SharedPreferences prefs){
        this.activity=activity; this.prefs=prefs;
    }

    void start(){
        if(Build.VERSION.SDK_INT<24 || listening ||
                !prefs.getBoolean("v2_ui_frames_enabled",true))return;
        long last=prefs.getLong("v2_ui_last_sample",0L);
        if(last>0 && System.currentTimeMillis()-last<10L*60L*1000L)return;
        frames=slow=dropped=0;duration=0L;stopPosted=false;
        startElapsed=SystemClock.elapsedRealtime();
        listener=(window, metrics, droppedFrames)-> {
            // Ignore callbacks from a window other than the sampled activity.
            if(!listening || window != activity.getWindow()) return;
            long ns=metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            if(ns>0 && ns<2_000_000_000L) {
                frames++;
                duration+=ns;
                if(ns>32_000_000L)slow++;
                dropped+=Math.max(0,droppedFrames);
            }
            if((frames>=150 || SystemClock.elapsedRealtime()-startElapsed>=10_000L)
                    && !stopPosted){
                stopPosted=true;
                activity.getWindow().getDecorView().post(this::stop);
            }
        };
        try{
            listening=true;
            activity.getWindow().addOnFrameMetricsAvailableListener(
                    listener,new Handler(Looper.getMainLooper()));
        } catch(RuntimeException problem){listening=false;listener=null;}
    }

    void stop(){
        if(!listening)return;
        listening=false;
        try{activity.getWindow().removeOnFrameMetricsAvailableListener(listener);}
        catch(RuntimeException ignored){}
        listener=null;
        if(frames>=12){
            float pct=100f*slow/frames;
            prefs.edit().putInt("v2_ui_frames",frames)
                    .putFloat("v2_ui_jank_pct",pct)
                    .putLong("v2_ui_last_sample",System.currentTimeMillis())
                    .putString("v2_ui_jank_note",
                            String.format(Locale.US,
                                "Interface do Adaptive Performance: %.1f%% de quadros >32 ms (%d quadros)",
                                pct,frames))
                    .apply();
        }
    }
}

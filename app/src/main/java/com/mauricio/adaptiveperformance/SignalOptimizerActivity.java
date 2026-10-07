package com.mauricio.adaptiveperformance;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.text.*;
import java.util.*;

public class SignalOptimizerActivity extends Activity {
    private android.content.SharedPreferences prefs;
    private TextView status, signal, intervalLabel, thresholdLabel, cooldownLabel;
    private Switch enabled, test5g, test4g, test3g, test2g;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs=getSharedPreferences("adaptive",MODE_PRIVATE);
        build();
    }

    private void build() {
        int bg=Color.rgb(7,18,27), card=Color.rgb(14,34,48), text=Color.rgb(234,244,250), muted=Color.rgb(158,180,194);
        ScrollView sv=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16),dp(18),dp(16),dp(30)); root.setBackgroundColor(bg); sv.addView(root);

        root.addView(t("Otimizador automático de sinal móvel",24,text,true));
        TextView sub=t("Quando o sinal ficar muito baixo, o Adaptive Performance compara as tecnologias permitidas no SIM e mantém a que entregar o melhor sinal.",13,muted,false);
        sub.setPadding(0,dp(5),0,dp(14)); root.addView(sub);

        LinearLayout c=card(card);
        enabled=sw("Ativar otimização automática de sinal","signal_optimizer_enabled",false,text);
        c.addView(enabled);
        status=t("",13,muted,false); status.setPadding(0,dp(8),0,dp(3)); c.addView(status);
        signal=t("",13,muted,false); c.addView(signal);
        root.addView(c);

        LinearLayout tech=card(card);
        tech.addView(t("Tecnologias que podem ser testadas",16,text,true));
        test5g=sw("5G + 4G, somente se NR já estiver permitido pelo SIM","signal_optimizer_test_5g",true,text);
        test4g=sw("4G / LTE","signal_optimizer_test_4g",true,text);
        test3g=sw("3G / WCDMA / HSPA","signal_optimizer_test_3g",true,text);
        test2g=sw("2G / GSM / EDGE","signal_optimizer_test_2g",true,text);
        tech.addView(test5g); tech.addView(test4g); tech.addView(test3g); tech.addView(test2g);
        root.addView(tech);

        LinearLayout cfg=card(card);
        cfg.addView(t("Sensibilidade e intervalo",16,text,true));

        intervalLabel=t("",13,muted,false); cfg.addView(intervalLabel);
        SeekBar interval=new SeekBar(this); interval.setMax(28); interval.setProgress(Math.max(0,prefs.getInt("signal_optimizer_interval_min",5)-2)); cfg.addView(interval);
        interval.setOnSeekBarChangeListener(listener(v->{ int min=v+2; prefs.edit().putInt("signal_optimizer_interval_min",min).commit(); updateLabels(); }));

        thresholdLabel=t("",13,muted,false); thresholdLabel.setPadding(0,dp(10),0,0); cfg.addView(thresholdLabel);
        SeekBar thr=new SeekBar(this); thr.setMax(25); thr.setProgress(Math.max(0,Math.min(25,-100-prefs.getInt("signal_optimizer_rsrp_threshold",-115)))); cfg.addView(thr);
        thr.setOnSeekBarChangeListener(listener(v->{ int dbm=-100-v; prefs.edit().putInt("signal_optimizer_rsrp_threshold",dbm).commit(); updateLabels(); }));

        cooldownLabel=t("",13,muted,false); cooldownLabel.setPadding(0,dp(10),0,0); cfg.addView(cooldownLabel);
        SeekBar cool=new SeekBar(this); cool.setMax(110); cool.setProgress(Math.max(0,prefs.getInt("signal_optimizer_cooldown_min",30)-10)); cfg.addView(cool);
        cool.setOnSeekBarChangeListener(listener(v->{ int min=v+10; prefs.edit().putInt("signal_optimizer_cooldown_min",min).commit(); updateLabels(); }));
        root.addView(cfg);

        Button check=btn("Verificar sinal agora");
        check.setOnClickListener(v->{
            prefs.edit().putBoolean("signal_optimizer_force_check",true).commit();
            if(!prefs.getBoolean("master",false)){
                Toast.makeText(this,"Ative a otimização principal para executar a verificação automática.",Toast.LENGTH_LONG).show();
            } else {
                Intent i=new Intent(this,OptimizationService.class);
                if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
                Toast.makeText(this,"Verificação solicitada",Toast.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(this::refresh,1800L);
            }
        });
        root.addView(check);

        Button restore=btn("Restaurar modo de rede original");
        restore.setOnClickListener(v->{
            prefs.edit().putBoolean("signal_optimizer_enabled",false).putBoolean("signal_optimizer_force_check",true).commit();
            enabled.setChecked(false);
            if(prefs.getBoolean("master",false)){
                Intent i=new Intent(this,OptimizationService.class);
                if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
            }
            Toast.makeText(this,"Restauração solicitada",Toast.LENGTH_SHORT).show();
        });
        root.addView(restore);

        TextView warn=t("Segurança: não troca a rede durante chamadas. Cada tecnologia é testada por pelo menos 20 s. Pode haver uma breve queda de dados durante a comparação. Ao desativar, o modo de rede original é restaurado.",12,muted,false);
        warn.setPadding(0,dp(14),0,0); root.addView(warn);

        setContentView(sv);
        enabled.setOnCheckedChangeListener((b,on)->{
            prefs.edit().putBoolean("signal_optimizer_enabled",on).commit();
            if(on) prefs.edit().putBoolean("signal_optimizer_force_check",true).apply();
            if(prefs.getBoolean("master",false)){
                Intent i=new Intent(this,OptimizationService.class);
                if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
            }
            refresh();
        });
        updateLabels();
        refresh();
    }

    private void refresh(){
        status.setText(prefs.getString("signal_optimizer_status","Aguardando monitoramento"));
        signal.setText("Atual: "+prefs.getString("signal_optimizer_current_tech","—")+" • nível "+
                prefs.getInt("signal_optimizer_current_level",0)+"/4 • "+prefs.getInt("signal_optimizer_current_dbm",-140)+" dBm"+
                "\nMelhor modo: "+prefs.getString("signal_optimizer_selected_mode","ainda não testado"));
    }
    @Override protected void onResume(){super.onResume(); if(status!=null)refresh();}

    private void updateLabels(){
        intervalLabel.setText("Verificar a cada "+prefs.getInt("signal_optimizer_interval_min",5)+" min");
        thresholdLabel.setText("Considerar LTE/5G muito fraco em ≤ "+prefs.getInt("signal_optimizer_rsrp_threshold",-115)+" dBm");
        cooldownLabel.setText("Cooldown após otimização: "+prefs.getInt("signal_optimizer_cooldown_min",30)+" min");
    }

    private SeekBar.OnSeekBarChangeListener listener(java.util.function.IntConsumer c){
        return new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean f){if(f)c.accept(p);}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        };
    }
    private Switch sw(String title,String key,boolean def,int color){
        Switch x=new Switch(this); x.setText(title); x.setTextColor(color); x.setTextSize(14); x.setPadding(dp(5),dp(8),dp(5),dp(8)); x.setChecked(prefs.getBoolean(key,def));
        x.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean(key,v).commit()); return x;
    }
    private TextView t(String s,int z,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(color);if(bold)v.setTypeface(null, android.graphics.Typeface.BOLD);return v;}
    private LinearLayout card(int color){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(14),dp(14),dp(14),dp(14));l.setBackgroundColor(color);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(7),0,dp(7));l.setLayoutParams(p);return l;}
    private Button btn(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(56));p.setMargins(0,dp(8),0,0);b.setLayoutParams(p);return b;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

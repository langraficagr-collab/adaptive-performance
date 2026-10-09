package com.mauricio.adaptiveperformance;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.net.Uri;
import android.os.storage.StorageManager;
import android.view.*;
import android.widget.*;
import java.text.*;
import java.util.*;

/** Separate Lite UI: only ordinary Android SDK functionality, no privileged commands. */
public final class LiteMainActivity extends Activity {
    private static final int BG=Color.rgb(7,18,29), PANEL=Color.rgb(17,35,51);
    private static final int TEXT=Color.rgb(232,244,252), MUTED=Color.rgb(155,180,193);
    private static final int TEAL=Color.rgb(58,221,187);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView battery, temperature, ram, thermal, storage, forecast, service;
    private Button toggle;
    private final Runnable refresh=new Runnable() {
        @Override public void run(){ render(); handler.postDelayed(this, 15000L); }
    };
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(20),dp(18),dp(30)); root.setBackgroundColor(BG);
        scroll.addView(root);
        root.addView(label("Adaptive Performance Lite",26,TEXT,true));
        TextView subtitle=label("Monitoramento sem Shizuku ou ADB",14,MUTED,false);
        subtitle.setPadding(0,dp(5),0,dp(16)); root.addView(subtitle);
        LinearLayout readings=card(root);
        readings.addView(label("Estado do aparelho",19,TEXT,true));
        battery=metric(readings); temperature=metric(readings); ram=metric(readings);
        thermal=metric(readings); storage=metric(readings);
        LinearLayout power=card(root);
        power.addView(label("Consumo e autonomia",19,TEXT,true));
        forecast=metric(power);
        LinearLayout status=card(root);
        status.addView(label("Monitoramento",19,TEXT,true));
        service=metric(status);
        toggle=button(status,"Iniciar monitoramento",()->toggle());
        LinearLayout tools=card(root);
        tools.addView(label("Ferramentas disponíveis",19,TEXT,true));
        button(tools,"Histórico da bateria, temperatura e RAM (24 h)",this::history);
        button(tools,"Aplicativos sem uso há 15 dias",this::unusedApps);
        button(tools,"Configurar DNS privado no Android",this::openDnsSettings);
        TextView note=label("Esta versão apenas monitora e recomenda. Não congela aplicativos, não altera CPU/GPU, não compacta RAM e não executa limpeza privilegiada.",12,MUTED,false);
        note.setPadding(dp(3),dp(12),dp(3),0); root.addView(note);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},101);
    }
    private LinearLayout card(LinearLayout parent){
        LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(16),dp(16),dp(16),dp(16));
        GradientDrawable bg=new GradientDrawable();bg.setColor(PANEL);bg.setCornerRadius(dp(16));bg.setStroke(dp(1),Color.rgb(43,74,91));c.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,0,0,dp(14));parent.addView(c,lp);return c;
    }
    private TextView label(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold) { t.setTypeface(null,Typeface.BOLD); } return t;
    }
    private TextView metric(LinearLayout parent){TextView t=label("Aguardando leitura...",15,TEXT,false);t.setPadding(0,dp(12),0,0);parent.addView(t);return t;}
    private Button button(LinearLayout parent,String title,Runnable action){
        Button b=new Button(this);b.setText(title);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(TEXT);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(19,109,136)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(9),0,0);parent.addView(b,lp);
        b.setOnClickListener(v->action.run());return b;
    }
    private int dp(float px){return Math.round(px*getResources().getDisplayMetrics().density);}
    private android.content.SharedPreferences prefs(){return getSharedPreferences(LiteMonitorService.PREFS,MODE_PRIVATE);}
    private void render(){
        android.content.SharedPreferences p=prefs();
        boolean active=p.getBoolean("active",false);
        service.setText(active?"Monitoramento solicitado e coletando leituras":"Desligado. Você pode consultar os dados atuais abaixo.");
        toggle.setText(active?"Parar monitoramento":"Iniciar monitoramento");
        Intent i=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(i!=null){
            int lev=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
            int scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);
            int pct=scale>0?Math.round(lev*100f/scale):-1;
            float t=i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1)/10f;
            boolean charging=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
            battery.setText(String.format(Locale.getDefault(),"Bateria: %d%% (%s)",pct,charging?"carregando":"descarregando"));
            temperature.setText(String.format(Locale.getDefault(),"Temperatura da bateria: %.1f °C",t));
            forecast.setText(forecast(p,pct,charging));
        }
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(info);
        ram.setText(String.format(Locale.getDefault(),"RAM disponível: %.1f%% (%.1f de %.1f GB)",100.0*info.availMem/info.totalMem,info.availMem/1073741824.0,info.totalMem/1073741824.0));
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        int ts=Build.VERSION.SDK_INT>=29 && pm!=null?pm.getCurrentThermalStatus():-1;
        String[] states={"normal","leve","moderado","elevado","severo","crítico","emergência"};
        thermal.setText("Estado térmico Android: "+(ts>=0&&ts<states.length?states[ts]:"indisponível"));
        StatFs fs=new StatFs(Environment.getDataDirectory().getAbsolutePath());
        long total=fs.getTotalBytes(),avail=fs.getAvailableBytes();
        storage.setText(String.format(Locale.getDefault(),"Armazenamento livre: %.1f de %.1f GB",avail/1073741824.0,total/1073741824.0));
    }
    private String forecast(android.content.SharedPreferences p,int pct,boolean charging){
        if(charging)return "Carregando: previsão disponível após o início da descarga.";
        long now=System.currentTimeMillis(),oldest=0;int startPct=-1;
        String[] lines=p.getString("history","").split("\\n");
        for(String line:lines){
            String[] x=line.split(",");if(x.length<5)continue;
            try{
                long time=Long.parseLong(x[0]);int v=Integer.parseInt(x[1]);boolean wasCharging=Integer.parseInt(x[4])==1;
                if(time>=now-6L*3600000L && !wasCharging && v>pct && (oldest==0||time<oldest)) {oldest=time;startPct=v;}
            }catch(Exception ignored){}
        }
        if(oldest==0 || now-oldest<600000L)return "Coletando histórico de descarga para estimar autonomia.";
        double rate=(startPct-pct)*3600000.0/(now-oldest);
        if(rate<=0 || rate>50)return "Histórico insuficiente para uma previsão confiável.";
        long minutes=Math.round(pct*60.0/rate);
        return String.format(Locale.getDefault(),"Consumo recente: %.1f%%/h\nAutonomia aproximada: %d h %02d min",rate,minutes/60,minutes%60);
    }
    private void toggle(){
        boolean running=prefs().getBoolean("active",false);
        Intent i=new Intent(this,LiteMonitorService.class);
        if(running)i.setAction(LiteMonitorService.ACTION_STOP);
        try{
            if(running)startService(i);else if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            if(!running)prefs().edit().putBoolean("active",true).apply();
            else prefs().edit().putBoolean("active",false).apply();
        }catch(Exception e){new AlertDialog.Builder(this).setMessage("Não foi possível iniciar o serviço: "+e.getMessage()).setPositiveButton("OK",null).show();}
        handler.postDelayed(this::render,400);
    }
    private void history(){
        String raw=prefs().getString("history","");
        StringBuilder out=new StringBuilder();
        String[] lines=raw.trim().split("\\n");
        DateFormat fmt=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
        for(int n=lines.length-1;n>=0&&out.length()<7500;n--){
            String[] x=lines[n].split(",");if(x.length<5)continue;
            try{
                long time=Long.parseLong(x[0]);
                if(time<System.currentTimeMillis()-86400000L)continue;
                out.append(fmt.format(new Date(time))).append("  •  ").append(x[1]).append("%\n")
                   .append("Bateria ").append(x[2]).append(" °C  |  RAM livre ").append(x[3]).append("%\n\n");
            }catch(Exception ignored){}
        }
        new AlertDialog.Builder(this).setTitle("Histórico das últimas 24 horas")
            .setMessage(out.length()==0?"Inicie o monitoramento para criar histórico.":out.toString())
            .setPositiveButton("Fechar",null).show();
    }
    private boolean usageGranted(){
        android.app.AppOpsManager appOps=(android.app.AppOpsManager)getSystemService(APP_OPS_SERVICE);
        int result=appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),getPackageName());
        return result==android.app.AppOpsManager.MODE_ALLOWED;
    }
    private void unusedApps(){
        if(!usageGranted()){
            new AlertDialog.Builder(this).setTitle("Permissão necessária")
                .setMessage("Permita 'Acesso ao uso' para a versão Lite identificar aplicativos que você não usa há 15 dias.")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Abrir configurações",(d,w)->startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))).show();return;
        }
        new Thread(()->{
            long now=System.currentTimeMillis();
            UsageStatsManager manager=(UsageStatsManager)getSystemService(USAGE_STATS_SERVICE);
            Map<String,UsageStats> stats=manager==null?Collections.emptyMap():manager.queryAndAggregateUsageStats(now-120L*86400000L,now);
            PackageManager pm=getPackageManager();
            ArrayList<String> names=new ArrayList<>();ArrayList<String> packages=new ArrayList<>();
            for(ApplicationInfo ai:pm.getInstalledApplications(0)){
                if((ai.flags&ApplicationInfo.FLAG_SYSTEM)!=0 || ai.packageName.equals(getPackageName()))continue;
                if(pm.getLaunchIntentForPackage(ai.packageName)==null)continue;
                UsageStats u=stats.get(ai.packageName);
                // Unknown usage != unused; show only packages with a known last-used timestamp.
                if(u==null || u.getLastTimeUsed()<=0 || now-u.getLastTimeUsed()<15L*86400000L)continue;
                packages.add(ai.packageName);names.add(pm.getApplicationLabel(ai).toString());
            }
            runOnUiThread(()->{
                if(isFinishing())return;
                if(packages.isEmpty()){
                    new AlertDialog.Builder(this).setTitle("Aplicativos sem uso")
                        .setMessage("Nenhum aplicativo com mais de 15 dias sem uso foi identificado com dados confiáveis.")
                        .setPositiveButton("Fechar",null).show();return;
                }
                new AlertDialog.Builder(this).setTitle("Escolha um aplicativo para desinstalar")
                    .setItems(names.toArray(new String[0]),(dialog,which)->{
                        String pkg=packages.get(which);
                        new AlertDialog.Builder(this).setMessage("Abrir a confirmação oficial do Android para desinstalar "+names.get(which)+"?")
                            .setNegativeButton("Cancelar",null)
                            .setPositiveButton("Abrir",(d,w)->startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+pkg))))
                            .show();
                    }).setNegativeButton("Fechar",null).show();
            });
        },"LiteUsageList").start();
    }
    private void openDnsSettings(){
        try{startActivity(new Intent("android.settings.PRIVATE_DNS_SETTINGS"));}
        catch(Exception e){startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));}
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);refresh.run();}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}

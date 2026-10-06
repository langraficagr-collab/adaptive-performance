package com.mauricio.adaptiveperformance;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public class StorageCleanupActivity extends Activity {
    private SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status, summary, largeFiles, trimPercent;
    private ProgressBar trimProgress;
    private Switch cacheSw, thumbsSw, partialSw, apkSw, diagSw, emptySw;
    private static final int BG=Color.rgb(6,16,25), CARD=Color.rgb(12,30,43), BORDER=Color.rgb(35,72,92), TEXT=Color.rgb(239,247,255), MUTED=Color.rgb(150,174,194), CYAN=Color.rgb(49,190,255), GREEN=Color.rgb(75,230,125);

    @Override protected void onCreate(Bundle b){
        super.onCreate(b); prefs=getSharedPreferences("adaptive",MODE_PRIVATE);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        build(); h.post(refreshLoop);
    }
    @Override protected void onDestroy(){ h.removeCallbacks(refreshLoop); super.onDestroy(); }

    private void build(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(16),dp(12),dp(16),dp(12));
        TextView title=t("Limpeza avançada",27,TEXT,true); root.addView(title);
        TextView sub=t("Libere espaço sem mexer em fotos, vídeos ou documentos pessoais por padrão.",13,MUTED,false); sub.setPadding(0,0,0,dp(10)); root.addView(sub);
        ScrollView sv=new ScrollView(this); LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); sv.addView(c);
        root.addView(sv,new LinearLayout.LayoutParams(-1,0,1f));

        LinearLayout scan=card();
        addTitle(scan,"◫","Análise de espaço","Mede itens seguros antes de qualquer limpeza.");
        status=t("Ainda não analisado",13,MUTED,false); scan.addView(status);
        summary=t("Toque em Analisar armazenamento.",14,TEXT,false); summary.setPadding(0,dp(10),0,0); scan.addView(summary);
        Button scanBtn=btn("Analisar armazenamento"); scanBtn.setOnClickListener(v->requestScan()); scan.addView(scanBtn); c.addView(scan);

        LinearLayout options=card(); addTitle(options,"✦","O que pode ser limpo","Você escolhe as categorias. Itens pessoais ficam fora.");
        cacheSw=sw("Cache de aplicativos e do sistema",true); options.addView(cacheSw);
        thumbsSw=sw("Miniaturas recriáveis de fotos",true); options.addView(thumbsSw);
        partialSw=sw("Downloads temporários/incompletos antigos (>7 dias)",false); options.addView(partialSw);
        apkSw=sw("Instaladores APK antigos (mais de 7 dias)",false); options.addView(apkSw);
        diagSw=sw("Diagnósticos antigos do Adaptive Performance",true); options.addView(diagSw);
        emptySw=sw("Pastas vazias dentro de Downloads",true); options.addView(emptySw);
        TextView note=t("Não são apagados automaticamente: fotos, vídeos, músicas, documentos, backups ou arquivos grandes encontrados na análise.",12,MUTED,false); note.setPadding(0,dp(10),0,0); options.addView(note);
        Button clean=btn("Limpar categorias selecionadas"); clean.setOnClickListener(v->confirmClean()); options.addView(clean);
        LinearLayout trimBox=card();
        addTitle(trimBox,"↻","Progresso da otimização","Estimativa visual: o Android/F2FS não fornece porcentagem real do TRIM.");
        trimPercent=t("0% • parado",14,TEXT,true); trimBox.addView(trimPercent);
        trimProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); trimProgress.setMax(100); trimProgress.setProgress(0);
        LinearLayout.LayoutParams tpp=new LinearLayout.LayoutParams(-1,dp(12)); tpp.setMargins(0,dp(8),0,dp(2)); trimProgress.setLayoutParams(tpp); trimBox.addView(trimProgress);
        options.addView(trimBox);

        Button trim=btn("Desfragmentar / otimizar armazenamento (TRIM)");
        trim.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Otimizar armazenamento")
                .setMessage("Em armazenamento flash/F2FS não é feita desfragmentação tradicional. Este botão executa TRIM/manutenção do Android para sinalizar blocos livres e ajudar o sistema a manter o armazenamento eficiente. Não apaga arquivos pessoais.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Otimizar", (d,w) -> requestTrim()).show());
        options.addView(trim);
        Button pauseTrim=btn("Pausar otimização / TRIM");
        pauseTrim.setOnClickListener(v -> requestPauseTrim());
        options.addView(pauseTrim); c.addView(options);

        LinearLayout large=card(); addTitle(large,"⌕","Arquivos grandes","Somente análise: mostra arquivos acima de 500 MB em Downloads.");
        largeFiles=t("Nenhuma análise feita.",13,MUTED,false); large.addView(largeFiles);
        Button largeBtn=btn("Localizar arquivos grandes"); largeBtn.setOnClickListener(v->requestLarge()); large.addView(largeBtn); c.addView(large);

        LinearLayout footer=card(); addTitle(footer,"✓","Proteções","A limpeza usa Shizuku e mantém uma lista fixa de exclusões.");
        footer.addView(t("• não limpa mídia pessoal\n• não apaga dados de login\n• não mexe em WhatsApp/Termux/ChatGPT\n• cache pode ser recriado pelos próprios apps\n• instaladores antigos só são removidos se você marcar a opção",12,MUTED,false)); c.addView(footer);
        setContentView(root);
    }

    private void requestScan(){ status.setText("Analisando…"); Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_SCAN); startForegroundService(i); }
    private void requestLarge(){ largeFiles.setText("Analisando arquivos grandes…"); Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_LARGE_SCAN); startForegroundService(i); }
    private void requestTrim(){ status.setText("Otimizando armazenamento…"); Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_TRIM); startForegroundService(i); }
    private void requestPauseTrim(){ status.setText("Pausando otimização…"); Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_TRIM_ABORT); startForegroundService(i); }
    private void confirmClean(){
        String msg="A limpeza vai atuar somente nas categorias marcadas. Fotos, vídeos, músicas e documentos pessoais não serão apagados automaticamente.";
        new AlertDialog.Builder(this).setTitle("Confirmar limpeza").setMessage(msg).setNegativeButton("Cancelar",null).setPositiveButton("Limpar",(d,w)->requestClean()).show();
    }
    private void requestClean(){
        Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_CLEAN)
                .putExtra("cache",cacheSw.isChecked()).putExtra("thumbs",thumbsSw.isChecked()).putExtra("partial",partialSw.isChecked())
                .putExtra("apk",apkSw.isChecked()).putExtra("diag",diagSw.isChecked()).putExtra("empty",emptySw.isChecked());
        startForegroundService(i); status.setText("Limpando…");
    }

    private final Runnable refreshLoop=new Runnable(){ public void run(){ refresh(); h.postDelayed(this,1500); }};
    private void refresh(){
        String st=prefs.getString("storage_cleanup_status","Ainda não analisado"); status.setText(st);
        long kb=prefs.getLong("storage_scan_total_kb",0L);
        long th=prefs.getLong("storage_scan_thumbnails_kb",0L), pa=prefs.getLong("storage_scan_partial_kb",0L), ap=prefs.getLong("storage_scan_apk_kb",0L), dg=prefs.getLong("storage_scan_diag_kb",0L);
        String s="Potencial seguro identificado: "+fmt(kb)+"\nMiniaturas: "+fmt(th)+" • temporários: "+fmt(pa)+"\nAPKs antigos: "+fmt(ap)+" • diagnósticos: "+fmt(dg);
        long freed=prefs.getLong("storage_last_freed_kb",0L); if(freed>0) s += "\nÚltima limpeza liberou aproximadamente "+fmt(freed);
        summary.setText(s);
        updateTrimProgress();
        largeFiles.setText(prefs.getString("storage_large_files","Nenhuma análise feita."));
    }
    private void updateTrimProgress(){
        if(trimProgress==null || trimPercent==null) return;
        boolean running=prefs.getBoolean("storage_trim_running",false);
        int stored=prefs.getInt("storage_trim_progress",0);
        boolean paused=prefs.getBoolean("storage_trim_abort_requested",false) && !running && stored>0 && stored<100;
        long started=prefs.getLong("storage_trim_started_at",0L);
        long expected=Math.max(30_000L,prefs.getLong("storage_trim_expected_ms",120_000L));
        int pct=stored;
        if(running && started>0){
            long elapsed=Math.max(0L,System.currentTimeMillis()-started);
            pct=(int)Math.min(95L,5L+(elapsed*90L/expected));
            if(pct<5)pct=5;
        }
        if(pct<0)pct=0; if(pct>100)pct=100;
        trimProgress.setProgress(pct);
        String state=running?"em andamento":paused?"pausado":"parado";
        if(pct>=100) state="estimativa concluída";
        trimPercent.setText(pct+"% • "+state+" • estimado");
    }

    private String fmt(long kb){ if(kb>=1024*1024) return String.format(Locale.US,"%.2f GB",kb/1048576.0); if(kb>=1024) return String.format(Locale.US,"%.1f MB",kb/1024.0); return kb+" KB"; }

    private LinearLayout card(){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16),dp(16),dp(16),dp(16)); l.setBackground(bg(CARD,BORDER,18)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(12)); l.setLayoutParams(p); return l; }
    private void addTitle(LinearLayout p,String icon,String a,String b){ TextView x=t(icon+"  "+a,19,TEXT,true); p.addView(x); TextView y=t(b,12,MUTED,false); y.setPadding(0,dp(4),0,dp(8)); p.addView(y); }
    private Switch sw(String s,boolean checked){ Switch x=new Switch(this); x.setText(s); x.setTextColor(TEXT); x.setTextSize(14); x.setChecked(checked); x.setPadding(dp(8),dp(8),dp(8),dp(8)); x.setBackground(bg(Color.rgb(13,34,48),Color.rgb(28,62,81),14)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(6),0,0); x.setLayoutParams(p); return x; }
    private Button btn(String s){ Button b=new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setAllCaps(false); b.setBackground(bg(Color.rgb(25,132,220),Color.rgb(39,188,255),15)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(56)); p.setMargins(0,dp(10),0,0); b.setLayoutParams(p); return b; }
    private TextView t(String s,float z,int c,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextColor(c); t.setTextSize(z); if(bold)t.setTypeface(null,1); return t; }
    private GradientDrawable bg(int c,int stroke,int r){ GradientDrawable g=new GradientDrawable(); g.setColor(c); g.setCornerRadius(dp(r)); g.setStroke(dp(1),stroke); return g; }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
}

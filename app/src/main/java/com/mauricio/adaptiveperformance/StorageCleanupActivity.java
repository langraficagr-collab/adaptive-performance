package com.mauricio.adaptiveperformance;

import android.app.*;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;

public class StorageCleanupActivity extends Activity {
    private SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status, summary, largeFiles, trimPercent, unusedAppsSummary, storageHealth;
    private ProgressBar trimProgress;
    private Switch cacheSw, thumbsSw, partialSw, apkSw, diagSw, emptySw;
    private Switch logsSw, staleSw, editorTempSw, dexCacheSw, storageHealthSw;
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
        cacheSw=swPref("Cache de aplicativos e do sistema","cleanup_cache",true); options.addView(cacheSw);
        thumbsSw=swPref("Miniaturas recriáveis de fotos","cleanup_thumbs",true); options.addView(thumbsSw);
        partialSw=swPref("Downloads temporários/incompletos antigos (>7 dias)","cleanup_partial",false); options.addView(partialSw);
        apkSw=swPref("Instaladores APK antigos (mais de 7 dias)","cleanup_apk",false); options.addView(apkSw);
        diagSw=swPref("Diagnósticos antigos do Adaptive Performance","cleanup_diag",true); options.addView(diagSw);
        emptySw=swPref("Pastas vazias dentro de Downloads","cleanup_empty",true); options.addView(emptySw);
        logsSw=swPref("Logs e relatórios de falha antigos acessíveis (>7 dias)","cleanup_logs",true); options.addView(logsSw);
        staleSw=swPref("Arquivos .log / .bak / .old antigos em Downloads (>14 dias)","cleanup_stale",false); options.addView(staleSw);
        editorTempSw=swPref("Resíduos temporários de editores em Downloads (>7 dias)","cleanup_editor_temp",true); options.addView(editorTempSw);
        dexCacheSw=swPref("Caches temporários de compilação do Android (somente se permitido)","cleanup_dex_cache",false); options.addView(dexCacheSw);
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

        LinearLayout health=card();
        addTitle(health,"◎","Manutenção inteligente do armazenamento","Verifica mudanças a cada hora e só executa ações pesadas em repouso.");
        storageHealthSw=swPref("Monitorar armazenamento automaticamente a cada hora","storage_health_auto_enabled",true);
        health.addView(storageHealthSw);
        storageHealth=t("Aguardando primeira verificação.",13,TEXT,false);
        storageHealth.setPadding(0,dp(10),0,0);
        health.addView(storageHealth);
        TextView healthNote=t("TRIM: manutenção do Android • F2FS GC/discard: monitorados • defrag: somente se suportado • fsck/e2fsck: nunca executados com /data montado.",12,MUTED,false);
        healthNote.setPadding(0,dp(8),0,0);
        health.addView(healthNote);
        Button healthNow=btn("Verificar armazenamento agora");
        healthNow.setOnClickListener(v->requestStorageHealthCheck());
        health.addView(healthNow);
        c.addView(health);

        LinearLayout large=card(); addTitle(large,"⌕","Arquivos grandes","Localiza arquivos acima de 500 MB em Downloads e permite escolher quais excluir.");
        largeFiles=t("Nenhuma análise feita.",13,MUTED,false); large.addView(largeFiles);
        Button largeBtn=btn("Localizar arquivos grandes"); largeBtn.setOnClickListener(v->requestLarge()); large.addView(largeBtn);
        Button deleteLargeBtn=btn("Selecionar arquivos grandes para excluir"); deleteLargeBtn.setOnClickListener(v->showLargeDeleteDialog()); large.addView(deleteLargeBtn);
        c.addView(large);

        LinearLayout unused=card();
        addTitle(unused,"⌛","Apps sem uso há mais de 15 dias","Recomenda apps que podem ser desinstalados. Apps de sistema e protegidos ficam fora.");
        unusedAppsSummary=t("Toque para analisar o histórico de uso.",13,MUTED,false); unused.addView(unusedAppsSummary);
        Button unusedBtn=btn("Recomendar apps para desinstalar"); unusedBtn.setOnClickListener(v->showUnusedApps()); unused.addView(unusedBtn);
        c.addView(unused);

        LinearLayout footer=card(); addTitle(footer,"✓","Proteções","A limpeza usa Shizuku e mantém uma lista fixa de exclusões.");
        footer.addView(t("• não limpa mídia pessoal\n• não apaga dados de login\n• não mexe em WhatsApp/Termux/ChatGPT\n• cache pode ser recriado pelos próprios apps\n• instaladores antigos só são removidos se você marcar a opção",12,MUTED,false)); c.addView(footer);
        setContentView(root);
    }

    private void requestScan(){ status.setText("Analisando…"); Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_SCAN); startForegroundService(i); }
    private void requestLarge(){ largeFiles.setText("Analisando arquivos grandes…"); Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_LARGE_SCAN); startForegroundService(i); }
    private void showLargeDeleteDialog(){
        Set<String> raw=new HashSet<>(prefs.getStringSet("storage_large_entries",Collections.emptySet()));
        if(raw.isEmpty()){
            new AlertDialog.Builder(this).setTitle("Arquivos grandes")
                    .setMessage("Nenhum arquivo grande disponível. Execute a análise primeiro.")
                    .setPositiveButton("OK",null).show();
            return;
        }
        ArrayList<String> entries=new ArrayList<>(raw);
        entries.sort((a,b)->Long.compare(entryBytes(b),entryBytes(a)));
        String[] labels=new String[entries.size()];
        boolean[] checked=new boolean[entries.size()];
        for(int i=0;i<entries.size();i++){
            String path=entryPath(entries.get(i));
            String name=path.substring(path.lastIndexOf('/')+1);
            labels[i]=fmt(entryBytes(entries.get(i))/1024L)+" • "+name;
        }
        new AlertDialog.Builder(this)
                .setTitle("Excluir arquivos grandes — marque os arquivos")
                .setMultiChoiceItems(labels,checked,(d,which,isChecked)->checked[which]=isChecked)
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Continuar",(d,w)->{
                    ArrayList<String> paths=new ArrayList<>(); long total=0;
                    for(int i=0;i<checked.length;i++) if(checked[i]){
                        paths.add(entryPath(entries.get(i))); total+=entryBytes(entries.get(i));
                    }
                    if(paths.isEmpty()){ Toast.makeText(this,"Nenhum arquivo selecionado",Toast.LENGTH_SHORT).show(); return; }
                    confirmLargeDelete(paths,total);
                }).show();
    }
    private void confirmLargeDelete(ArrayList<String> paths,long bytes){
        new AlertDialog.Builder(this).setTitle("Confirmar exclusão")
                .setMessage("Excluir permanentemente "+paths.size()+" arquivo(s), aproximadamente "+fmt(bytes/1024L)+"?\n\nEsta ação não pode ser desfeita.")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Excluir",(d,w)->{
                    Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_DELETE_LARGE);
                    i.putStringArrayListExtra("paths",paths); startForegroundService(i);
                    status.setText("Excluindo arquivos grandes selecionados…");
                }).show();
    }
    private long entryBytes(String entry){ try{ int b=entry.indexOf('|'); return Long.parseLong(entry.substring(0,b)); }catch(Exception e){return 0L;} }
    private String entryPath(String entry){ int b=entry.indexOf('|'); return b>=0?entry.substring(b+1):""; }

    private boolean hasUsageAccess(){
        AppOpsManager a=(AppOpsManager)getSystemService(APP_OPS_SERVICE);
        return a!=null && a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),getPackageName())==AppOpsManager.MODE_ALLOWED;
    }
    private void showUnusedApps(){
        if(!hasUsageAccess()){
            new AlertDialog.Builder(this).setTitle("Acesso ao uso necessário")
                    .setMessage("Para recomendar apps realmente sem uso há 15 dias, permita Acesso ao uso para o Adaptive Performance.")
                    .setNegativeButton("Cancelar",null)
                    .setPositiveButton("Abrir configurações",(d,w)->startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))).show();
            return;
        }
        long now=System.currentTimeMillis(), cutoff=now-15L*24L*60L*60L*1000L;
        UsageStatsManager usm=(UsageStatsManager)getSystemService(USAGE_STATS_SERVICE);
        Map<String,UsageStats> stats=usm==null?Collections.emptyMap():usm.queryAndAggregateUsageStats(now-120L*24L*60L*60L*1000L,now);
        PackageManager pm=getPackageManager();
        ArrayList<AppCandidate> apps=new ArrayList<>();
        for(ApplicationInfo ai:pm.getInstalledApplications(0)){
            String pkg=ai.packageName;
            boolean system=(ai.flags & ApplicationInfo.FLAG_SYSTEM)!=0 || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;
            if(system || pm.getLaunchIntentForPackage(pkg)==null) continue;
            if(pkg.equals(getPackageName()) || AppSafety.isSystemApp(this,pkg) || AppSafety.isAutoProtected(this,pkg)) continue;
            PackageInfo pi; try{pi=pm.getPackageInfo(pkg,0);}catch(Exception e){continue;}
            if(pi.firstInstallTime>cutoff) continue;
            UsageStats u=stats.get(pkg); long last=0;
            if(u!=null){ last=u.getLastTimeUsed(); if(Build.VERSION.SDK_INT>=29) last=Math.max(last,u.getLastTimeVisible()); }
            if(last>cutoff) continue;
            apps.add(new AppCandidate(pkg,AppSafety.label(this,pkg),last));
        }
        apps.sort((a,b)->Long.compare(a.lastUse,b.lastUse));
        unusedAppsSummary.setText(apps.isEmpty()?"Nenhum app elegível sem uso há mais de 15 dias.":apps.size()+" app(s) podem ser revisados para desinstalação.");
        if(apps.isEmpty()) return;
        String[] labels=new String[apps.size()];
        for(int i=0;i<apps.size();i++){
            AppCandidate a=apps.get(i);
            labels[i]=a.label+"\n"+(a.lastUse<=0?"Sem uso registrado nos últimos 120 dias":("Último uso há "+Math.max(15,(now-a.lastUse)/(24L*60L*60L*1000L))+" dias"));
        }
        new AlertDialog.Builder(this).setTitle("Apps sem uso há mais de 15 dias")
                .setItems(labels,(d,which)->confirmUninstall(apps.get(which)))
                .setNegativeButton("Fechar",null).show();
    }
    private void confirmUninstall(AppCandidate app){
        new AlertDialog.Builder(this).setTitle("Desinstalar "+app.label+"?")
                .setMessage("O Android abrirá a tela oficial de desinstalação. Nenhum app será removido automaticamente.")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Abrir desinstalação",(d,w)->startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+app.pkg))))
                .show();
    }
    private static class AppCandidate{
        final String pkg,label; final long lastUse;
        AppCandidate(String p,String l,long u){pkg=p;label=l;lastUse=u;}
    }

    private void requestTrim(){ status.setText("Otimizando armazenamento…"); Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_TRIM); startForegroundService(i); }
    private void requestPauseTrim(){ status.setText("Pausando otimização…"); Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_TRIM_ABORT); startForegroundService(i); }
    private void requestStorageHealthCheck(){
        if(storageHealth!=null) storageHealth.setText("Verificando agora…");
        Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_HEALTH_CHECK);
        startForegroundService(i);
    }
    private void confirmClean(){
        String msg="A limpeza vai atuar somente nas categorias marcadas. Fotos, vídeos, músicas e documentos pessoais não serão apagados automaticamente.";
        new AlertDialog.Builder(this).setTitle("Confirmar limpeza").setMessage(msg).setNegativeButton("Cancelar",null).setPositiveButton("Limpar",(d,w)->requestClean()).show();
    }
    private void requestClean(){
        Intent i=new Intent(this,OptimizationService.class).setAction(OptimizationService.ACTION_STORAGE_CLEAN)
                .putExtra("cache",cacheSw.isChecked()).putExtra("thumbs",thumbsSw.isChecked()).putExtra("partial",partialSw.isChecked())
                .putExtra("apk",apkSw.isChecked()).putExtra("diag",diagSw.isChecked()).putExtra("empty",emptySw.isChecked())
                .putExtra("logs",logsSw.isChecked()).putExtra("stale",staleSw.isChecked())
                .putExtra("editorTemp",editorTempSw.isChecked()).putExtra("dexCache",dexCacheSw.isChecked());
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
        if(storageHealth!=null){
            String hs=prefs.getString("storage_health_summary","Aguardando primeira verificação.");
            long hc=prefs.getLong("storage_health_last_check_at",0L);
            if(hc>0L){
                long min=Math.max(0L,(System.currentTimeMillis()-hc)/60000L);
                hs += "\nÚltima verificação: " + (min<1 ? "agora" : (min+" min atrás"));
            }
            storageHealth.setText(hs);
        }
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
    private Switch sw(String s,boolean checked){ Switch x=new Switch(this); x.setText(UiLanguage.tr(prefs,s)); x.setTextColor(TEXT); x.setTextSize(14); x.setChecked(checked); x.setPadding(dp(8),dp(8),dp(8),dp(8)); x.setBackground(bg(Color.rgb(13,34,48),Color.rgb(28,62,81),14)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(6),0,0); x.setLayoutParams(p); return x; }
    private Switch swPref(String title,String key,boolean def){
        Switch x=sw(title,prefs.getBoolean(key,def));
        x.setOnCheckedChangeListener((button,checked)->prefs.edit().putBoolean(key,checked).commit());
        return x;
    }
    private Button btn(String s){ Button b=new Button(this); b.setText(UiLanguage.tr(prefs,s)); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setAllCaps(false); b.setBackground(bg(Color.rgb(25,132,220),Color.rgb(39,188,255),15)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(56)); p.setMargins(0,dp(10),0,0); b.setLayoutParams(p); return b; }
    private TextView t(String s,float z,int c,boolean bold){ TextView t=new TextView(this); t.setText(UiLanguage.tr(prefs,s)); t.setTextColor(c); t.setTextSize(z); if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD); return t; }
    private GradientDrawable bg(int c,int stroke,int r){ GradientDrawable g=new GradientDrawable(); g.setColor(c); g.setCornerRadius(dp(r)); g.setStroke(dp(1),stroke); return g; }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
}

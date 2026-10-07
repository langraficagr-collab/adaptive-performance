package com.mauricio.adaptiveperformance;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.net.VpnService;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class DnsFirewallActivity extends Activity {
    private android.content.SharedPreferences prefs;
    private LinearLayout appsBox;
    private TextView status, count;
    private Switch master;
    private boolean bindingUi;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("adaptive", MODE_PRIVATE);
        buildUi();
    }

    private void buildUi() {
        int bg=Color.rgb(7,18,27), card=Color.rgb(14,34,48), text=Color.rgb(234,244,250), muted=Color.rgb(158,180,194);
        ScrollView sv=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16),dp(20),dp(16),dp(30)); root.setBackgroundColor(bg); sv.addView(root);

        TextView title=t("Firewall DNS por aplicativo",24,text,true); root.addView(title);
        TextView sub=t("Apps marcados usam AdGuard DNS para bloquear anúncios e rastreadores. Apps livres continuam usando a rede normal.",13,muted,false); sub.setPadding(0,dp(4),0,dp(12)); root.addView(sub);

        LinearLayout c=card(card);
        master=new Switch(this); master.setText("Ativar firewall DNS AdGuard"); master.setTextColor(text); master.setTextSize(16);
        master.setChecked(prefs.getBoolean("dns_firewall_enabled",false)); c.addView(master);
        status=t("",13,muted,false); status.setPadding(0,dp(8),0,dp(4)); c.addView(status);
        count=t("",13,muted,false); c.addView(count);

        TextView dns=t("DNS filtrante: 94.140.14.14 / 94.140.15.15",12,muted,false); dns.setPadding(0,dp(8),0,0); c.addView(dns);
        TextView note=t("Observação: apps que usam DNS próprio/DoH podem ignorar filtros DNS. O restante do tráfego não passa pelo túnel.",12,muted,false); note.setPadding(0,dp(6),0,0); c.addView(note);
        root.addView(c);

        LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button all=btn("Filtrar todos"); Button none=btn("Deixar todos livres");
        actions.addView(all,new LinearLayout.LayoutParams(0,-2,1)); actions.addView(none,new LinearLayout.LayoutParams(0,-2,1)); root.addView(actions);

        TextView listTitle=t("Aplicativos — marcado = AdGuard • desmarcado = livre",16,text,true); listTitle.setPadding(0,dp(16),0,dp(6)); root.addView(listTitle);
        appsBox=new LinearLayout(this); appsBox.setOrientation(LinearLayout.VERTICAL); root.addView(appsBox);
        setContentView(sv);

        populateApps();
        all.setOnClickListener(v->setAll(true));
        none.setOnClickListener(v->setAll(false));
        master.setOnCheckedChangeListener((button,on)->{
            if(bindingUi) return;
            if(on) requestStart(); else stopFirewall();
        });
        refreshStatus();
    }

    private void populateApps() {
        appsBox.removeAllViews();
        PackageManager pm=getPackageManager();
        List<ApplicationInfo> apps=pm.getInstalledApplications(0);
        apps.sort(Comparator.comparing(a->String.valueOf(pm.getApplicationLabel(a)),String.CASE_INSENSITIVE_ORDER));
        Set<String> selected=new HashSet<>(prefs.getStringSet("dns_firewall_filtered_apps",Collections.emptySet()));
        for(ApplicationInfo ai:apps) {
            String pkg=ai.packageName;
            if(pkg.equals(getPackageName())) continue;
            if(pm.getLaunchIntentForPackage(pkg)==null) continue;
            CheckBox cb=new CheckBox(this);
            String label=String.valueOf(pm.getApplicationLabel(ai));
            cb.setText(label+"\n"+pkg); cb.setTextColor(Color.rgb(232,242,248)); cb.setTextSize(14);
            cb.setPadding(dp(4),dp(7),dp(4),dp(7)); cb.setTag(pkg); cb.setChecked(selected.contains(pkg));
            cb.setOnCheckedChangeListener((b,on)->{
                if (!bindingUi) updateSelection((String)b.getTag(),on);
            });
            appsBox.addView(cb,new LinearLayout.LayoutParams(-1,-2));
        }
        updateCount();
    }

    private void updateSelection(String pkg, boolean on) {
        Set<String> s=new HashSet<>(prefs.getStringSet("dns_firewall_filtered_apps",Collections.emptySet()));
        if(on) s.add(pkg); else s.remove(pkg);
        prefs.edit().putStringSet("dns_firewall_filtered_apps",s).commit();
        updateCount();
        if(prefs.getBoolean("dns_firewall_enabled",false) && VpnService.prepare(this)==null) restartFirewall();
    }

    private void setAll(boolean on) {
        Set<String> s=new HashSet<>();
        for(int i=0;i<appsBox.getChildCount();i++){
            View v=appsBox.getChildAt(i);
            if(v instanceof CheckBox){
                CheckBox cb=(CheckBox)v;
                bindingUi=true; cb.setChecked(on); bindingUi=false;
                if(on) s.add((String)cb.getTag());
            }
        }
        prefs.edit().putStringSet("dns_firewall_filtered_apps",s).commit();
        updateCount();
        if(prefs.getBoolean("dns_firewall_enabled",false) && VpnService.prepare(this)==null) restartFirewall();
    }

    private void requestStart() {
        Set<String> s=prefs.getStringSet("dns_firewall_filtered_apps",Collections.emptySet());
        if(s.isEmpty()) {
            bindingUi=true; master.setChecked(false); bindingUi=false;
            Toast.makeText(this,"Selecione pelo menos um aplicativo para filtrar",Toast.LENGTH_SHORT).show();
            return;
        }
        Intent prep=VpnService.prepare(this);
        if(prep!=null) startActivityForResult(prep,401);
        else enableAndStart();
    }

    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req==401) {
            if(result==RESULT_OK) enableAndStart();
            else { bindingUi=true; master.setChecked(false); bindingUi=false; prefs.edit().putBoolean("dns_firewall_enabled",false).apply(); }
        }
    }

    private void enableAndStart() {
        prefs.edit().putBoolean("dns_firewall_enabled",true).commit();
        Intent i=new Intent(this,AdGuardDnsVpnService.class).setAction(AdGuardDnsVpnService.ACTION_START);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
        refreshStatusDelayed();
    }
    private void restartFirewall() {
        Intent i=new Intent(this,AdGuardDnsVpnService.class).setAction(AdGuardDnsVpnService.ACTION_RESTART);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
        refreshStatusDelayed();
    }
    private void stopFirewall() {
        prefs.edit().putBoolean("dns_firewall_enabled",false).commit();
        startService(new Intent(this,AdGuardDnsVpnService.class).setAction(AdGuardDnsVpnService.ACTION_STOP));
        refreshStatusDelayed();
    }

    private void refreshStatusDelayed(){ new Handler(Looper.getMainLooper()).postDelayed(this::refreshStatus,700); }
    private void refreshStatus() {
        boolean on=prefs.getBoolean("dns_firewall_running",false);
        status.setText(prefs.getString("dns_firewall_status",on?"Ativo":"Desativado"));
        bindingUi=true; master.setChecked(prefs.getBoolean("dns_firewall_enabled",false)); bindingUi=false;
        updateCount();
    }
    private void updateCount() {
        int n=prefs.getStringSet("dns_firewall_filtered_apps",Collections.emptySet()).size();
        count.setText(n+" app(s) selecionados para filtragem");
    }
    @Override protected void onResume(){ super.onResume(); if(status!=null) refreshStatus(); }

    private TextView t(String s,int size,int color,boolean bold){ TextView v=new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); if(bold)v.setTypeface(null,1); return v; }
    private LinearLayout card(int color){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(14),dp(14),dp(14),dp(14)); l.setBackgroundColor(color); return l; }
    private Button btn(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}

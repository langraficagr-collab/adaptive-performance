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
    private LinearLayout appsBox, vpnSection;
    private TextView privateStatus, vpnStatus, count;
    private Switch privateDnsMaster, vpnMaster;
    private boolean bindingUi;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("adaptive", MODE_PRIVATE);
        buildUi();
    }

    private void buildUi() {
        int bg=Color.rgb(7,18,27), card=Color.rgb(14,34,48), text=Color.rgb(234,244,250), muted=Color.rgb(158,180,194);
        ScrollView sv=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(20),dp(16),dp(30)); root.setBackgroundColor(bg); sv.addView(root);

        root.addView(t("Proteção DNS AdGuard",24,text,true));
        TextView intro=t("Escolha entre o modo recomendado sem VPN, que protege todo o aparelho, ou o modo experimental por aplicativo.",13,muted,false);
        intro.setPadding(0,dp(4),0,dp(12)); root.addView(intro);

        LinearLayout global=card(card);
        global.addView(t("Modo recomendado • sem VPN",17,text,true));
        privateDnsMaster=new Switch(this);
        privateDnsMaster.setText("Usar DNS Privado AdGuard no aparelho inteiro");
        privateDnsMaster.setTextColor(text); privateDnsMaster.setTextSize(15);
        privateDnsMaster.setChecked(prefs.getBoolean("private_dns_enabled",false));
        global.addView(privateDnsMaster);
        privateStatus=t("",13,muted,false); privateStatus.setPadding(0,dp(7),0,0); global.addView(privateStatus);
        TextView host=t("DNS-over-TLS: dns.adguard-dns.com",12,muted,false); host.setPadding(0,dp(6),0,0); global.addView(host);
        TextView globalNote=t("Mais estável: usa o DNS Privado nativo do Android e não mantém VPN ou serviço de túnel. Aplica-se a todos os apps.",12,muted,false);
        globalNote.setPadding(0,dp(6),0,0); global.addView(globalNote);
        root.addView(global);

        vpnSection=card(card);
        vpnSection.addView(t("Modo experimental • por aplicativo",17,text,true));
        vpnMaster=new Switch(this);
        vpnMaster.setText("Ativar VPN DNS seletiva");
        vpnMaster.setTextColor(text); vpnMaster.setTextSize(15);
        vpnMaster.setChecked(prefs.getBoolean("dns_firewall_enabled",false));
        vpnSection.addView(vpnMaster);
        vpnStatus=t("",13,muted,false); vpnStatus.setPadding(0,dp(7),0,dp(3)); vpnSection.addView(vpnStatus);
        count=t("",13,muted,false); vpnSection.addView(count);
        TextView warning=t("Este modo usa VpnService e pode ser encerrado por algumas ROMs/gerenciadores de bateria. Use apenas se precisar escolher apps individualmente.",12,muted,false);
        warning.setPadding(0,dp(7),0,dp(8)); vpnSection.addView(warning);

        LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button all=btn("Filtrar todos"); Button none=btn("Deixar todos livres");
        actions.addView(all,new LinearLayout.LayoutParams(0,-2,1)); actions.addView(none,new LinearLayout.LayoutParams(0,-2,1));
        vpnSection.addView(actions);

        TextView listTitle=t("Aplicativos — marcado = AdGuard • desmarcado = livre",14,text,true);
        listTitle.setPadding(0,dp(12),0,dp(6)); vpnSection.addView(listTitle);
        appsBox=new LinearLayout(this); appsBox.setOrientation(LinearLayout.VERTICAL); vpnSection.addView(appsBox);
        root.addView(vpnSection);
        setContentView(sv);

        populateApps();
        all.setOnClickListener(v->setAll(true));
        none.setOnClickListener(v->setAll(false));

        privateDnsMaster.setOnCheckedChangeListener((button,on)->{
            if(bindingUi) return;
            if(on) enablePrivateDns(); else disablePrivateDns();
        });
        vpnMaster.setOnCheckedChangeListener((button,on)->{
            if(bindingUi) return;
            if(on) requestVpnStartExclusive(); else stopFirewall();
        });
        refreshStatus();
    }

    private void enablePrivateDns() {
        // Os dois modos são exclusivos. Primeiro encerra o VPN e só depois aplica DNS Privado.
        boolean vpnWasEnabled = prefs.getBoolean("dns_firewall_enabled",false)
                || prefs.getBoolean("dns_firewall_running",false);

        prefs.edit()
                .putBoolean("private_dns_requested",true)
                .putBoolean("dns_firewall_enabled",false)
                .commit();

        bindingUi=true;
        vpnMaster.setChecked(false);
        bindingUi=false;

        if (vpnWasEnabled) {
            try {
                startService(new Intent(this,AdGuardDnsVpnService.class)
                        .setAction(AdGuardDnsVpnService.ACTION_STOP));
            } catch(Throwable ignored) {}
        }

        privateStatus.setText(vpnWasEnabled
                ? "Encerrando VPN e ativando DNS Privado AdGuard…"
                : "Ativando DNS Privado AdGuard…");

        long delay = vpnWasEnabled ? 1200L : 100L;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startOptimizationAction(OptimizationService.ACTION_PRIVATE_DNS_ENABLE);
            refreshStatusDelayed(1200L);
        }, delay);
    }

    private void disablePrivateDns() {
        prefs.edit().putBoolean("private_dns_requested",false).commit();
        startOptimizationAction(OptimizationService.ACTION_PRIVATE_DNS_DISABLE);
        privateStatus.setText("Restaurando DNS anterior…");
        refreshStatusDelayed(1200L);
    }

    private void requestVpnStartExclusive() {
        Set<String> s=prefs.getStringSet("dns_firewall_filtered_apps",Collections.emptySet());
        if(s.isEmpty()) {
            bindingUi=true; vpnMaster.setChecked(false); bindingUi=false;
            Toast.makeText(this,"Selecione pelo menos um aplicativo para filtrar",Toast.LENGTH_SHORT).show();
            return;
        }

        if (prefs.getBoolean("private_dns_enabled",false) || prefs.getBoolean("private_dns_requested",false)) {
            prefs.edit().putBoolean("private_dns_requested",false).commit();
            startOptimizationAction(OptimizationService.ACTION_PRIVATE_DNS_DISABLE);
            new Handler(Looper.getMainLooper()).postDelayed(this::requestVpnPermission, 900L);
        } else {
            requestVpnPermission();
        }
    }

    private void requestVpnPermission() {
        Intent prep=VpnService.prepare(this);
        if(prep!=null) startActivityForResult(prep,401);
        else enableAndStartVpn();
    }

    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req==401) {
            if(result==RESULT_OK) enableAndStartVpn();
            else {
                bindingUi=true; vpnMaster.setChecked(false); bindingUi=false;
                prefs.edit().putBoolean("dns_firewall_enabled",false).apply();
            }
        }
    }

    private void startOptimizationAction(String action) {
        Intent i=new Intent(this,OptimizationService.class).setAction(action);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
    }

    private void populateApps() {
        appsBox.removeAllViews();
        TextView loading=t("Carregando aplicativos…",13,Color.rgb(158,180,194),false);
        appsBox.addView(loading);
        Set<String> selected=new HashSet<>(prefs.getStringSet("dns_firewall_filtered_apps",Collections.emptySet()));

        new Thread(() -> {
            PackageManager pm=getPackageManager();
            List<ApplicationInfo> apps=pm.getInstalledApplications(0);
            ArrayList<AppRow> rows=new ArrayList<>();
            for(ApplicationInfo ai:apps) {
                String pkg=ai.packageName;
                if(pkg.equals(getPackageName())) continue;
                try {
                    if(pm.getLaunchIntentForPackage(pkg)==null) continue;
                    rows.add(new AppRow(String.valueOf(pm.getApplicationLabel(ai)),pkg));
                } catch(Throwable ignored) {}
            }
            rows.sort((a,b)->a.label.compareToIgnoreCase(b.label));

            runOnUiThread(() -> {
                if(isFinishing() || isDestroyed()) return;
                appsBox.removeAllViews();
                for(AppRow row:rows) {
                    CheckBox cb=new CheckBox(this);
                    cb.setText(row.label+"\n"+row.pkg);
                    cb.setTextColor(Color.rgb(232,242,248)); cb.setTextSize(14);
                    cb.setPadding(dp(4),dp(7),dp(4),dp(7)); cb.setTag(row.pkg); cb.setChecked(selected.contains(row.pkg));
                    cb.setOnCheckedChangeListener((b,on)->{ if(!bindingUi) updateSelection((String)b.getTag(),on); });
                    appsBox.addView(cb,new LinearLayout.LayoutParams(-1,-2));
                }
                updateCount();
            });
        },"DnsAppList").start();
    }

    private static final class AppRow {
        final String label,pkg;
        AppRow(String l,String p){label=l;pkg=p;}
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
        for(int i=0;i<appsBox.getChildCount();i++) {
            View v=appsBox.getChildAt(i);
            if(v instanceof CheckBox) {
                CheckBox cb=(CheckBox)v;
                bindingUi=true; cb.setChecked(on); bindingUi=false;
                if(on) s.add((String)cb.getTag());
            }
        }
        prefs.edit().putStringSet("dns_firewall_filtered_apps",s).commit();
        updateCount();
        if(prefs.getBoolean("dns_firewall_enabled",false) && VpnService.prepare(this)==null) restartFirewall();
    }

    private void enableAndStartVpn() {
        prefs.edit().putBoolean("dns_firewall_enabled",true).commit();
        Intent i=new Intent(this,AdGuardDnsVpnService.class).setAction(AdGuardDnsVpnService.ACTION_START);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
        refreshStatusDelayed(900L);
    }

    private void restartFirewall() {
        Intent i=new Intent(this,AdGuardDnsVpnService.class).setAction(AdGuardDnsVpnService.ACTION_RESTART);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
        refreshStatusDelayed(900L);
    }

    private void stopFirewall() {
        prefs.edit().putBoolean("dns_firewall_enabled",false).commit();
        try { startService(new Intent(this,AdGuardDnsVpnService.class).setAction(AdGuardDnsVpnService.ACTION_STOP)); }
        catch(Throwable ignored) {}
        refreshStatusDelayed(700L);
    }

    private void refreshStatusDelayed(long delay) {
        new Handler(Looper.getMainLooper()).postDelayed(this::refreshStatus,delay);
    }

    private void refreshStatus() {
        boolean privateOn=prefs.getBoolean("private_dns_enabled",false);
        boolean vpnOn=prefs.getBoolean("dns_firewall_running",false);
        privateStatus.setText(prefs.getString("private_dns_status",privateOn?"Ativo sem VPN":"Desativado"));
        vpnStatus.setText(prefs.getString("dns_firewall_status",vpnOn?"VPN ativa":"VPN desativada"));

        bindingUi=true;
        privateDnsMaster.setChecked(privateOn || prefs.getBoolean("private_dns_requested",false));
        vpnMaster.setChecked(prefs.getBoolean("dns_firewall_enabled",false));
        bindingUi=false;
        updateCount();
    }

    private void updateCount() {
        int n=prefs.getStringSet("dns_firewall_filtered_apps",Collections.emptySet()).size();
        count.setText(n+" app(s) selecionados para o modo VPN");
    }

    @Override protected void onResume(){ super.onResume(); if(privateStatus!=null) refreshStatus(); }

    private TextView t(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(null,1);return v;}
    private LinearLayout card(int color){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(14),dp(14),dp(14),dp(14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(7),0,dp(7));l.setLayoutParams(p);l.setBackgroundColor(color);return l;}
    private Button btn(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

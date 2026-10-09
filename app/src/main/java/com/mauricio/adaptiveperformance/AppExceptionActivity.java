package com.mauricio.adaptiveperformance;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import java.util.*;

public class AppExceptionActivity extends Activity {
    private SharedPreferences prefs;
    private final Map<String, CheckBox> boxes = new LinkedHashMap<>();
    private final List<Item> items = new ArrayList<>();
    private final Set<String> workingSelected = new HashSet<>();
    private LinearLayout list;
    private TextView countText;

    private static final int BG = Color.rgb(6,16,25);
    private static final int CARD = Color.rgb(12,30,43);
    private static final int BORDER = Color.rgb(35,72,92);
    private static final int TEXT = Color.rgb(239,247,255);
    private static final int MUTED = Color.rgb(150,174,194);
    private static final int CYAN = Color.rgb(49,190,255);

    private static class Item {
        String pkg, label;
        boolean system;
        Item(String p, String l, boolean s) { pkg=p; label=l; system=s; }
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable bg(int color, int stroke, float r) {
        GradientDrawable g=new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(r));
        if(stroke!=0) g.setStroke(dp(1),stroke);
        return g;
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView v=new TextView(this);
        v.setText(UiLanguage.tr(prefs,s)); v.setTextSize(sp); v.setTextColor(color);
        if(bold) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return v;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("adaptive",MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(16),dp(14),dp(16),dp(12));

        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=tv("‹",34,CYAN,true);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v->finish());
        top.addView(back,new LinearLayout.LayoutParams(dp(42),dp(42)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(tv("Apps que nunca devem ser congelados",23,TEXT,true));
        titles.addView(tv("Apps marcados ficam fora do congelamento automático",13,MUTED,false));
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1f));
        page.addView(top);

        TextView note=tv("Escolha aqui os apps que nunca devem ser finalizados ou congelados automaticamente. Apps em primeiro plano sempre esperam o período configurado antes de serem congelados.",13,MUTED,false);
        note.setPadding(dp(4),dp(12),dp(4),dp(12));
        page.addView(note);

        EditText search=new EditText(this);
        search.setHint(UiLanguage.tr(prefs,"Buscar aplicativo ou pacote"));
        search.setHintTextColor(Color.rgb(99,130,150));
        search.setTextColor(TEXT);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setPadding(dp(14),0,dp(14),0);
        search.setBackground(bg(CARD,BORDER,15));
        page.addView(search,new LinearLayout.LayoutParams(-1,dp(50)));

        countText=tv("",12,MUTED,false);
        countText.setPadding(dp(4),dp(10),0,dp(8));
        page.addView(countText);

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));

        Button save=new Button(this);
        save.setText(UiLanguage.tr(prefs,"Salvar lista"));
        save.setTextColor(Color.WHITE);
        save.setTextSize(15);
        save.setAllCaps(false);
        save.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable sb=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(31,181,244),Color.rgb(18,104,231)});
        sb.setCornerRadius(dp(16));
        save.setBackground(sb);
        page.addView(save,new LinearLayout.LayoutParams(-1,dp(56)));

        setContentView(page);

        workingSelected.addAll(prefs.getStringSet("never_freeze_apps",Collections.emptySet()));
        // Migra automaticamente a lista de exceções das versões anteriores.
        workingSelected.addAll(prefs.getStringSet("auto_protected_apps",Collections.emptySet()));
        PackageManager pm=getPackageManager();
        for(ApplicationInfo ai:pm.getInstalledApplications(0)) {
            String pkg=ai.packageName;
            if(!AppSafety.isEligibleForAdaptiveOptimization(this,pkg)) continue;
            items.add(new Item(pkg,AppSafety.label(this,pkg),AppSafety.isSystemApp(this,pkg)));
        }
        items.sort((a,b)->a.label.compareToIgnoreCase(b.label));
        rebuildList("");

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            @Override public void onTextChanged(CharSequence s,int st,int b,int c){ rebuildList(s.toString()); }
            @Override public void afterTextChanged(Editable e){}
        });

        save.setOnClickListener(v->{
            prefs.edit().putStringSet("never_freeze_apps",new HashSet<>(workingSelected))
                    .putStringSet("auto_protected_apps",new HashSet<>(workingSelected)).apply();
            finish();
        });
    }

    private void rebuildList(String filter) {
        for(Map.Entry<String,CheckBox> e:boxes.entrySet()) {
            if(e.getValue().isChecked()) workingSelected.add(e.getKey());
            else workingSelected.remove(e.getKey());
        }

        boxes.clear();
        list.removeAllViews();
        String f=filter==null?"":filter.trim().toLowerCase(Locale.ROOT);
        int shown=0;
        for(Item item:items) {
            String hay=(item.label+" "+item.pkg).toLowerCase(Locale.ROOT);
            if(!f.isEmpty() && !hay.contains(f)) continue;
            CheckBox cb=new CheckBox(this);
            cb.setText((item.system?"[Sistema] ":"")+item.label+"\n"+item.pkg);
            cb.setTextColor(TEXT);
            cb.setTextSize(14);
            cb.setPadding(dp(14),dp(10),dp(10),dp(10));
            cb.setChecked(workingSelected.contains(item.pkg));
            cb.setOnCheckedChangeListener((button,checked)->{
                if(checked) workingSelected.add(item.pkg);
                else workingSelected.remove(item.pkg);
                countText.setText(items.size()+" apps disponíveis • "+workingSelected.size()+" nunca congelar");
            });
            cb.setBackground(bg(CARD,BORDER,14));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.setMargins(0,0,0,dp(8));
            cb.setLayoutParams(lp);
            boxes.put(item.pkg,cb);
            list.addView(cb);
            shown++;
        }
        countText.setText(shown+" apps exibidos • "+workingSelected.size()+" nunca congelar");
    }
}

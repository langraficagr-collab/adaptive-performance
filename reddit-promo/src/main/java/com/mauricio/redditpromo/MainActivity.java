package com.mauricio.redditpromo;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public final class MainActivity extends Activity {
    private static final int BG=0xFF0B1119, CARD=0xFF131D29, TEAL=0xFF48D9BD, MUTED=0xFFA7B4C4;
    private SharedPreferences prefs;
    private EditText community, contribution;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("reddit_promo",MODE_PRIVATE);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(22),dp(30),dp(22),dp(28)); scroll.addView(root);
        TextView badge=text("FERRAMENTA DE DIVULGAÇÃO",12,TEAL,true); root.addView(badge);
        TextView title=text("Compartilhe seu projeto com transparência",28,Color.WHITE,true); title.setPadding(0,dp(12),0,dp(8)); root.addView(title);
        TextView desc=text("Prepare uma publicação para o Reddit, confira as regras da comunidade e revise tudo antes de enviar. O aplicativo não publica sozinho.",15,MUTED,false); desc.setPadding(0,0,0,dp(22)); root.addView(desc);
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16),dp(18),dp(16),dp(18)); card.setBackground(shape(CARD,dp(18))); root.addView(card);
        card.addView(text("Comunidade",14,Color.WHITE,true)); community=new EditText(this); community.setSingleLine(true); community.setHint("Ex.: androidapps"); community.setText(prefs.getString("community","")); styleInput(community); card.addView(community,lp(0,dp(52),dp(10)));
        card.addView(text("Sua contribuição",14,Color.WHITE,true)); contribution=new EditText(this); contribution.setHint("Conte o que o app faz e peça feedback honesto."); contribution.setMinLines(4); contribution.setGravity(Gravity.TOP|Gravity.START); styleInput(contribution); card.addView(contribution,lp(0,dp(124),dp(8)));
        status=text("A publicação sempre será aberta para sua revisão manual.",13,MUTED,false); status.setPadding(0,dp(12),0,dp(12)); card.addView(status);
        Button prepare=new Button(this); prepare.setText("Revisar no Reddit"); prepare.setTextColor(BG); prepare.setAllCaps(false); prepare.setTypeface(null,Typeface.BOLD); prepare.setBackground(shape(TEAL,dp(14))); card.addView(prepare,lp(0,dp(52),0)); prepare.setOnClickListener(v->openDraft());
        Button mark=new Button(this); mark.setText("Registrar que publiquei"); mark.setAllCaps(false); mark.setTextColor(Color.WHITE); mark.setBackground(shape(0xFF263748,dp(14))); LinearLayout.LayoutParams mp=lp(0,dp(50),dp(12)); card.addView(mark,mp); mark.setOnClickListener(v->recordPost());
        TextView note=text("Respeite as regras de cada comunidade e evite repetir divulgação. O intervalo de 7 dias é lembrado neste aparelho.",12,MUTED,false); note.setPadding(0,dp(20),0,0); root.addView(note);
        setContentView(scroll);
    }
    private void openDraft(){
        String sub=community.getText().toString().trim().replaceFirst("(?i)^r/","");
        if(!sub.matches("[A-Za-z0-9_]{2,21}")){community.setError("Informe um nome válido de comunidade");return;}
        String last=prefs.getString("last_community",""); long at=prefs.getLong("last_post",0L);
        if(sub.equalsIgnoreCase(last)&&System.currentTimeMillis()-at<7L*24*60*60*1000){status.setText("Você registrou uma publicação recente aqui. Espere 7 dias antes de repetir.");return;}
        prefs.edit().putString("community",sub).apply();
        String own=contribution.getText().toString().trim(); if(own.isEmpty()) own="Estou desenvolvendo o Adaptive Performance, um app Android para acompanhar e ajustar desempenho, temperatura e bateria. Gostaria de receber feedback honesto sobre recursos e compatibilidade.";
        String body=own+"\n\nSou o desenvolvedor do Adaptive Performance; esta é uma divulgação própria. Projeto: https://github.com/langraficagr-collab/adaptive-performance\n\nSe este tipo de divulgação não for permitido nesta comunidade, não publique.";
        Uri uri=Uri.parse("https://www.reddit.com/r/"+Uri.encode(sub)+"/submit").buildUpon().appendQueryParameter("type","self").appendQueryParameter("title","Como vocês monitoram desempenho e bateria no Android?").appendQueryParameter("text",body).build();
        Intent i=new Intent(Intent.ACTION_VIEW,uri);
        try{startActivity(i);status.setText("Formulário aberto. Revise as regras e o texto antes de publicar.");}catch(Exception e){status.setText("Não foi possível abrir o Reddit ou o navegador.");}
    }
    private void recordPost(){String sub=community.getText().toString().trim().replaceFirst("(?i)^r/","");if(!sub.matches("[A-Za-z0-9_]{2,21}")){community.setError("Informe um nome válido");return;}prefs.edit().putString("community",sub).putString("last_community",sub).putLong("last_post",System.currentTimeMillis()).apply();status.setText("Intervalo de 7 dias registrado para r/"+sub+".");}
    private void styleInput(EditText e){e.setTextColor(Color.WHITE);e.setHintTextColor(MUTED);e.setPadding(dp(12),dp(8),dp(12),dp(8));e.setBackground(shape(0xFF0E1722,dp(12)));}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold) { t.setTypeface(null,Typeface.BOLD); } return t;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams lp(int w,int h,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));if(top>0) { p.topMargin=dp(top); } return p;}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
}

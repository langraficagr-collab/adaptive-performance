package com.mauricio.adaptiveperformance;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;

public class HealthHistoryActivity extends Activity {
    private static final int BG = Color.rgb(6,16,25);
    private static final int CARD = Color.rgb(12,30,43);
    private static final int BORDER = Color.rgb(35,72,92);
    private static final int TEXT = Color.rgb(239,247,255);
    private static final int MUTED = Color.rgb(150,174,194);
    private SharedPreferences prefs;

    private static final class Point {
        long ts;
        float temp, cpu, ram, power;
        int pressure, thermal, ht, hm, hc, hi, hb, hu;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("adaptive", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        root.setBackgroundColor(BG);
        scroll.addView(root);

        TextView title = text("Histórico de saúde — 24 horas", 25, TEXT, true);
        root.addView(title);
        TextView sub = text("Amostras locais do Adaptive Performance. Um ponto novo é salvo aproximadamente a cada 5 minutos.", 13, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        List<Point> points = parseHistory();
        if (points.isEmpty()) {
            TextView empty = text("Ainda não há dados suficientes. O histórico começa a aparecer após as primeiras amostras.", 15, MUTED, false);
            empty.setPadding(dp(14), dp(18), dp(14), dp(18));
            empty.setBackground(cardBg());
            root.addView(empty);
            setContentView(scroll);
            return;
        }

        root.addView(summaryCard(points));
        root.addView(chartCard("Temperatura do SoC", points, 0, "°C"));
        root.addView(chartCard("CPU total", points, 1, "%"));
        root.addView(chartCard("RAM livre", points, 2, "%"));
        root.addView(chartCard("Potência da bateria", points, 3, "W"));
        root.addView(healthScoreCard());

        setContentView(scroll);
    }

    private View summaryCard(List<Point> points) {
        LinearLayout c = card();
        Point first = points.get(0), last = points.get(points.size()-1);
        float maxTemp=-1, maxCpu=-1, minRam=101, maxPower=-1;
        for (Point p:points) {
            maxTemp=Math.max(maxTemp,p.temp);
            maxCpu=Math.max(maxCpu,p.cpu);
            minRam=Math.min(minRam,p.ram);
            maxPower=Math.max(maxPower,p.power);
        }
        String from = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(first.ts));
        String to = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(last.ts));
        TextView h=text("Resumo",18,TEXT,true);
        c.addView(h);
        TextView body=text(String.format(Locale.US,
                "%d amostra(s) • %s–%s\nMáx. SoC %.1f°C • Máx. CPU %.0f%% • Mín. RAM livre %.0f%% • Máx. potência %.1f W",
                points.size(),from,to,maxTemp,maxCpu,minRam,maxPower),14,MUTED,false);
        body.setPadding(0,dp(8),0,0);
        c.addView(body);
        return c;
    }

    private View chartCard(String title, List<Point> points, int metric, String unit) {
        LinearLayout c=card();
        TextView t=text(title,17,TEXT,true);
        c.addView(t);
        HistoryChart chart=new HistoryChart(this,points,metric,unit);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(180));
        lp.setMargins(0,dp(10),0,0);
        c.addView(chart,lp);
        return c;
    }

    private View healthScoreCard() {
        LinearLayout c=card();
        c.addView(text("Estado atual por subsistema",17,TEXT,true));
        String s=String.format(Locale.US,
                "Térmico %d • Memória %d • CPU %d • I/O %d • Bateria %d • Interface %d\nLMKD +%d • Jank %s • zRAM %s • calibração %d/40",
                prefs.getInt("health_thermal",0),prefs.getInt("health_memory",0),
                prefs.getInt("health_cpu",0),prefs.getInt("health_io",0),
                prefs.getInt("health_battery",0),prefs.getInt("health_ui",0),
                prefs.getInt("lmk_delta",0),
                prefs.getFloat("jank_pct",-1)>=0?String.format(Locale.US,"%.1f%%",prefs.getFloat("jank_pct",-1)):"--",
                prefs.getFloat("zram_swap_mb",-1)>=0?String.format(Locale.US,"%.0f MB",prefs.getFloat("zram_swap_mb",-1)):"--",
                prefs.getInt("cal_n",0));
        TextView b=text(s,14,MUTED,false);
        b.setPadding(0,dp(8),0,0);
        c.addView(b);
        return c;
    }

    private List<Point> parseHistory() {
        List<Point> out=new ArrayList<>();
        String raw=prefs.getString("history_24h_data","");
        if(raw==null||raw.isEmpty()) return out;
        long cutoff=System.currentTimeMillis()-24L*60L*60L*1000L;
        for(String line:raw.split("\n")) {
            String[] x=line.trim().split(",");
            if(x.length<13) continue;
            try {
                Point p=new Point();
                p.ts=Long.parseLong(x[0]);
                if(p.ts<cutoff) continue;
                p.temp=Float.parseFloat(x[1]);
                p.cpu=Float.parseFloat(x[2]);
                p.ram=Float.parseFloat(x[3]);
                p.power=Float.parseFloat(x[4]);
                p.pressure=Integer.parseInt(x[5]);
                p.thermal=Integer.parseInt(x[6]);
                p.ht=Integer.parseInt(x[7]);
                p.hm=Integer.parseInt(x[8]);
                p.hc=Integer.parseInt(x[9]);
                p.hi=Integer.parseInt(x[10]);
                p.hb=Integer.parseInt(x[11]);
                p.hu=Integer.parseInt(x[12]);
                out.add(p);
            } catch(Throwable ignored){}
        }
        out.sort(Comparator.comparingLong(a->a.ts));
        return out;
    }

    private LinearLayout card() {
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16),dp(16),dp(16),dp(16));
        c.setBackground(cardBg());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,0,0,dp(12));
        c.setLayoutParams(lp);
        return c;
    }

    private GradientDrawable cardBg() {
        GradientDrawable g=new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1),BORDER);
        return g;
    }

    private TextView text(String s,float sp,int color,boolean bold) {
        TextView v=new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        if(bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private static final class HistoryChart extends View {
        private final List<Point> points;
        private final int metric;
        private final String unit;
        private final Paint grid=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);

        HistoryChart(Context c,List<Point> points,int metric,String unit){
            super(c); this.points=points; this.metric=metric; this.unit=unit;
            grid.setColor(Color.rgb(35,72,92)); grid.setStrokeWidth(1f);
            line.setColor(Color.rgb(52,225,198)); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(4f);
            label.setColor(Color.rgb(150,174,194)); label.setTextSize(28f);
            setBackgroundColor(Color.rgb(7,24,35));
        }

        private float value(Point p){
            if(metric==0)return p.temp;
            if(metric==1)return p.cpu;
            if(metric==2)return p.ram;
            return p.power;
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            int w=getWidth(),h=getHeight();
            if(w<=0||h<=0)return;
            float left=12f,right=w-12f,top=26f,bottom=h-34f;
            for(int i=0;i<=4;i++){
                float y=top+(bottom-top)*i/4f;
                c.drawLine(left,y,right,y,grid);
            }
            if(points.isEmpty())return;
            float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
            for(Point p:points){float v=value(p);if(v<0)continue;min=Math.min(min,v);max=Math.max(max,v);}
            if(min==Float.MAX_VALUE)return;
            if(max-min<1f){max+=0.5f;min-=0.5f;}
            float pad=(max-min)*0.12f; min-=pad; max+=pad;
            Path path=new Path(); int valid=0;
            int n=points.size();
            for(int i=0;i<n;i++){
                float v=value(points.get(i)); if(v<0)continue;
                float x=n<=1?(left+right)/2f:left+(right-left)*i/(n-1f);
                float y=bottom-(v-min)/(max-min)*(bottom-top);
                if(valid++==0)path.moveTo(x,y); else path.lineTo(x,y);
            }
            if(valid>1)c.drawPath(path,line);
            c.drawText(String.format(Locale.US,"%.1f%s",max,unit),left,22f,label);
            c.drawText(String.format(Locale.US,"%.1f%s",min,unit),left,h-6f,label);
        }
    }
}

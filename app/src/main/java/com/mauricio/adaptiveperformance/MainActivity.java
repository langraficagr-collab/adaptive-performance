package com.mauricio.adaptiveperformance;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView shizukuText, statusText, statusSubText;
    private TextView socValue, batValue, ramValue, cpuValue, freqValue;
    private TextView batteryText, maintenanceText, restrictedText, cpuPressureText, freezeStateText, advancedText, healthText, extendedText, limitingDashboardText;
    private TextView thermalLevel1, thermalLevel2, thermalLevel3, thermalLevel4, thermalLevel5, thermalAuto;
    private Switch autoRepairSwitch, effectivenessSwitch, profilesSwitch, restrictionGuardSwitch, emergencySwitch, startupSwitch;
    private Switch refreshSwitch, cleanupSwitch, aggressiveMemorySwitch, bugCleanupSwitch, unusedRestrictSwitch, cpuPressureSwitch, manualFreezeSwitch, notifySwitch;
    private Switch memoryCompactionSwitch, appRamLimiterSwitch;
    private SeekBar appRamLimitSeekBar;
    private TextView appRamLimitLabel;
    private TextView memoryCompactionLabel;
    private SeekBar memoryCompactionSeekBar;
    private Spinner zramProfileSpinner;
    private TextView zramProfileHint;
    private Switch advancedAdaptiveSwitch, screenOffSwitch, wakeupGuardSwitch;
    private Switch healthGuardSwitch, autoCalibrationSwitch, appLearningSwitch, antiStallSwitch, historySwitch, rollbackSwitch, crashLoopSwitch;
    private Switch extendedDiagnosticsSwitch, thermalPredictionSwitch, diagnosticBurstSwitch, adaptiveAggressivenessSwitch, abTestingSwitch, sensorModemSwitch, storageGuardSwitch, duplicateDetectionSwitch, safeModeManualSwitch;
    private Switch deepSleepSwitch, memoryLeakSwitch, thermalBrightnessSwitch;
    private Switch systemBatteryGuardSwitch, systemDeepIdleSwitch, systemRadioSwitch;
    private Switch dynamicDozeSwitch, adaptiveTimeoutSwitch, maximumBatterySwitch;
    private Button startButton, permissionButton;
    private LinearLayout content;
    private ScrollView mainScroll;
    private SparklineView chart;
    private View actionsPlaceholder;
    private boolean actionsBuilt = false;
    private int actionsInsertIndex = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int SHIZUKU_REQ = 9042;
    private long lastShizukuUiCheckElapsed = 0L;
    private boolean lastRenderedMaster;
    private boolean lastRenderedMasterValid = false;
    private String lastThermalStyleKey = "";
    private float lastChartSoc = Float.NaN;

    private static final int BG = Color.rgb(6,16,25);
    private static final int CARD = Color.rgb(12,30,43);
    private static final int CARD_2 = Color.rgb(15,37,52);
    private static final int BORDER = Color.rgb(35,72,92);
    private static final int TEXT = Color.rgb(239,247,255);
    private static final int MUTED = Color.rgb(150,174,194);
    private static final int CYAN = Color.rgb(49,190,255);
    private static final int TEAL = Color.rgb(52,225,198);
    private static final int GREEN = Color.rgb(75,230,125);
    private static final int ORANGE = Color.rgb(255,190,74);
    private static final int RED = Color.rgb(255,102,92);

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_REQ) {
            updateShizuku();
            if (grantResult == PackageManager.PERMISSION_GRANTED) startOptimizer();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("adaptive", MODE_PRIVATE);
        if (!prefs.contains("user_mode")) {
            // Atualizações mantêm o comportamento atual; instalações novas começam simplificadas.
            String initialMode = prefs.contains("master") ? "advanced" : "auto";
            prefs.edit().putString("user_mode", initialMode).apply();
        }
        if (!prefs.contains("user_mode")) {
            // Atualizações mantêm o comportamento atual; instalações novas começam simplificadas.
            String initialMode = prefs.contains("master") ? "advanced" : "auto";
            prefs.edit().putString("user_mode", initialMode).apply();
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        // A tela de monitoramento não precisa renderizar em 120 Hz; 60 Hz reduz custo de GPU/FramePolicy.
        WindowManager.LayoutParams windowParams = getWindow().getAttributes();
        windowParams.preferredRefreshRate = 60f;
        getWindow().setAttributes(windowParams);
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(0);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 300);
        }
        if (prefs.getBoolean("master", false)) {
            try { startOptimizer(); } catch (Throwable ignored) {}
        }
        handler.post(() -> maybeShowCauseDialog(getIntent()));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handler.post(() -> maybeShowCauseDialog(intent));
    }

    private void maybeShowCauseDialog(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("show_cause_dialog", false)) return;
        intent.removeExtra("show_cause_dialog");

        String causeId = intent.getStringExtra(CauseResolutionNotifier.EXTRA_CAUSE_ID);
        String cause = intent.getStringExtra(CauseResolutionNotifier.EXTRA_CAUSE_TEXT);
        String solution = intent.getStringExtra(CauseResolutionNotifier.EXTRA_SOLUTION_TEXT);
        if (causeId == null) causeId = prefs.getString("active_cause_id", "");
        if (cause == null || cause.isEmpty())
            cause = prefs.getString("active_cause_text", "Problema não identificado");
        if (solution == null || solution.isEmpty())
            solution = prefs.getString("active_cause_solution", "Revise os detalhes antes de decidir.");

        CauseResolutionNotifier.Cause spec = CauseResolutionNotifier.classify(cause);
        boolean failure = intent.getBooleanExtra("cause_failure", false);
        boolean canRetry = intent.getBooleanExtra("cause_can_retry", false);

        final String id = causeId;
        final String causeText = cause;
        final String solutionText = solution;

        String title = failure
                ? "Não consegui resolver automaticamente"
                : "Causa provável detectada";
        String body;
        if (failure) {
            body = (causeText.isEmpty() ? "" : "Causa:\n" + causeText + "\n\n")
                    + "O que aconteceu:\n" + solutionText;
        } else {
            body = causeText + "\n\nSolução sugerida:\n" + solutionText;
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body);

        if (failure && canRetry) {
            b.setPositiveButton("Tentar novamente", (d, w) -> {
                Intent service = new Intent(this, OptimizationService.class)
                        .setAction(OptimizationService.ACTION_APPLY_CAUSE_FIX)
                        .putExtra(CauseResolutionNotifier.EXTRA_CAUSE_ID, id)
                        .putExtra(CauseResolutionNotifier.EXTRA_CAUSE_TEXT, causeText)
                        .putExtra("retry_count", 0);
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                    else startService(service);
                } catch (Throwable t) {
                    Toast.makeText(this, "Não foi possível iniciar a nova tentativa.",
                            Toast.LENGTH_LONG).show();
                }
            });
        } else if (spec != null && spec.autoFix) {
            b.setPositiveButton("Aplicar solução", (d, w) -> {
                Intent service = new Intent(this, OptimizationService.class)
                        .setAction(OptimizationService.ACTION_APPLY_CAUSE_FIX)
                        .putExtra(CauseResolutionNotifier.EXTRA_CAUSE_ID, id)
                        .putExtra(CauseResolutionNotifier.EXTRA_CAUSE_TEXT, causeText)
                        .putExtra(CauseResolutionNotifier.EXTRA_SOLUTION_TEXT, solutionText)
                        .putExtra("retry_count", 0);
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                    else startService(service);
                } catch (Throwable t) {
                    Toast.makeText(this, "Não foi possível iniciar a correção: " + t.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        } else {
            b.setPositiveButton("Entendi", null);
        }

        b.setNegativeButton("Manter assim", (d, w) -> {
            prefs.edit()
                    .putString("cause_kept_id", id)
                    .putLong("cause_kept_at", System.currentTimeMillis())
                    .putString("last_cause_decision", "keep")
                    .putLong("last_cause_decision_at", System.currentTimeMillis())
                    .apply();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(CauseResolutionNotifier.NOTIFICATION_ID);
                nm.cancel(CauseResolutionNotifier.RESULT_NOTIFICATION_ID);
            }
        });
        b.setNeutralButton("Fechar", null);
        b.show();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable bordered(int color, int stroke, float radiusDp) {
        GradientDrawable g = rounded(color, radiusDp);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable gradient(int c1, int c2, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1,c2});
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), BORDER);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return v;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(18), dp(18), dp(18));
        l.setBackground(bordered(CARD, BORDER, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(14));
        l.setLayoutParams(lp);
        return l;
    }

    private void addSectionTitle(LinearLayout parent, String icon, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = text(icon, 23, TEAL, true);
        ic.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(42), dp(42));
        ic.setLayoutParams(ilp);
        ic.setBackground(rounded(Color.rgb(14,62,65), 14));
        row.addView(ic);

        LinearLayout txts = new LinearLayout(this);
        txts.setOrientation(LinearLayout.VERTICAL);
        txts.setPadding(dp(10),0,0,0);
        TextView t = text(title, 19, TEXT, true);
        TextView st = text(subtitle, 13, MUTED, false);
        txts.addView(t); txts.addView(st);
        row.addView(txts, new LinearLayout.LayoutParams(0,-2,1f));
        parent.addView(row);
    }

    private TextView metricTile(LinearLayout row, String label, String initial, int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12),dp(10),dp(12),dp(10));
        box.setBackground(bordered(CARD_2, Color.rgb(29,69,90), 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), dp(76));
        lp.setMargins(0,0,dp(5),0);
        box.setLayoutParams(lp);

        TextView l = text(label, 10, MUTED, false);
        l.setSingleLine(true);
        TextView v = text(initial, 13, accent, true);
        v.setSingleLine(true);
        v.setPadding(0,dp(4),0,0);
        box.addView(l); box.addView(v);
        row.addView(box);
        return v;
    }

    private Switch actionSwitch(String title, String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextColor(TEXT);
        sw.setTextSize(14);
        sw.setPadding(dp(12), dp(10), dp(8), dp(10));
        sw.setChecked(prefs.getBoolean(key, def));
        // Persistência padrão para todos os switches, inclusive opções adicionadas
        // dinamicamente que não possuem listener específico.
        sw.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(key, checked).commit());
        sw.setButtonTintList(null);
        sw.setBackground(bordered(Color.rgb(13,34,48), Color.rgb(28,62,81), 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(7),0,0);
        sw.setLayoutParams(lp);
        return sw;
    }

    private Button actionButton(String title) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setBackground(gradient(Color.rgb(29,182,244), Color.rgb(19,107,231), 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.setMargins(0,dp(10),0,0);
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setOnApplyWindowInsetsListener((v,insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            return insets;
        });

        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(18));
        mainScroll.addView(content);
        page.addView(mainScroll, new LinearLayout.LayoutParams(-1,0,1f));

        buildHeader();
        buildHero();
        buildModeSelector();
        if (isAdvancedUserMode()) {
            buildThermal();
            buildActionsPlaceholder();
        } else {
            buildAutomaticModeCard();
        }
        buildInsights();
        buildSystemCard();

        page.addView(buildBottomNav(), new LinearLayout.LayoutParams(-1, dp(68)));
        setContentView(page);
    }

    private boolean isAdvancedUserMode() {
        return "advanced".equals(prefs.getString("user_mode", "auto"));
    }

    private void setUserMode(String mode) {
        String normalized = "advanced".equals(mode) ? "advanced" : "auto";
        if (normalized.equals(prefs.getString("user_mode", "auto"))) return;
        prefs.edit().putString("user_mode", normalized).apply();
        if ("auto".equals(normalized)) {
            prefs.edit().putLong("auto_user_last_apply", 0L).apply();
        }
        recreate();
    }

    private void buildModeSelector() {
        LinearLayout c = card();
        addSectionTitle(c, "◎", "Modo de uso", "Automático para simplicidade ou Avançado para controle total");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, 0);

        Button automatic = actionButton("Automático");
        Button advanced = actionButton("Avançado");
        automatic.setLayoutParams(new LinearLayout.LayoutParams(0, dp(54), 1f));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        alp.setMargins(dp(8), 0, 0, 0);
        advanced.setLayoutParams(alp);

        boolean advancedMode = isAdvancedUserMode();
        automatic.setBackground(advancedMode
                ? bordered(Color.rgb(18,44,60), Color.rgb(44,91,116), 16)
                : gradient(Color.rgb(21,153,112), Color.rgb(15,107,155), 16));
        advanced.setBackground(advancedMode
                ? gradient(Color.rgb(29,182,244), Color.rgb(19,107,231), 16)
                : bordered(Color.rgb(18,44,60), Color.rgb(44,91,116), 16));
        automatic.setOnClickListener(v -> setUserMode("auto"));
        advanced.setOnClickListener(v -> setUserMode("advanced"));
        row.addView(automatic);
        row.addView(advanced);
        c.addView(row);

        TextView help = text(advancedMode
                ? "Avançado: todas as opções ficam disponíveis para ajuste manual."
                : "Automático: o app aprende seu padrão de uso, compara perfis e prioriza menor consumo sem abandonar proteções de estabilidade e temperatura.",
                12, MUTED, false);
        help.setPadding(dp(4), dp(10), dp(4), 0);
        c.addView(help);
        content.addView(c);
    }

    private void buildAutomaticModeCard() {
        LinearLayout c = card();
        addSectionTitle(c, "✦", "Otimização automática", "O aplicativo escolhe e testa as melhores configurações por você");
        String autoStatus = prefs.getString("auto_user_status",
                "Aprendizado inicial • priorizando economia de bateria");
        // Remove temperatura de versões de teste antigas; a temperatura atual já aparece no painel principal.
        autoStatus = autoStatus.replaceAll("\\s*•\\s*[0-9]+(?:\\.[0-9]+)?°C$", "");
        TextView status = text(autoStatus, 14, TEXT, true);
        status.setPadding(dp(4), dp(14), dp(4), dp(8));
        c.addView(status);

        TextView details = text("O modo automático usa temperatura, CPU, RAM, consumo estimado, estado da tela e nível da bateria. Ele alterna entre perfis econômicos em janelas longas, mede o resultado e mantém a configuração que apresentar menor consumo com segurança.",
                12, MUTED, false);
        details.setPadding(dp(4), dp(2), dp(4), dp(4));
        c.addView(details);

        TextView goalTitle = text("Meta de bateria", 15, TEXT, true);
        goalTitle.setPadding(dp(4), dp(16), dp(4), dp(6));
        c.addView(goalTitle);
        int goalPct = prefs.getInt("battery_goal_pct", 20);
        int goalHour = prefs.getInt("battery_goal_hour", 22);
        TextView goalLabel = text("Chegar às " + String.format(Locale.US, "%02d:00", goalHour)
                + " com pelo menos " + goalPct + "%", 13, CYAN, true);
        c.addView(goalLabel);
        TextView goalHint = text("O modo automático compara a bateria atual com o ritmo necessário até o horário escolhido e aumenta ou reduz a economia para tentar cumprir a meta.", 12, MUTED, false);
        goalHint.setPadding(dp(4), dp(4), dp(4), dp(8));
        c.addView(goalHint);

        TextView pctLabel = text("Bateria mínima: " + goalPct + "%", 12, MUTED, false);
        c.addView(pctLabel);
        SeekBar pctSeek = new SeekBar(this);
        pctSeek.setMax(40);
        pctSeek.setProgress(Math.max(0, Math.min(40, goalPct - 10)));
        pctSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                int v = 10 + progress;
                pctLabel.setText("Bateria mínima: " + v + "%");
                if (fromUser) prefs.edit().putInt("battery_goal_pct", v).putLong("auto_user_last_apply", 0L).apply();
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) { recreate(); }
        });
        c.addView(pctSeek);

        TextView hourLabel = text("Horário alvo: " + String.format(Locale.US, "%02d:00", goalHour), 12, MUTED, false);
        c.addView(hourLabel);
        SeekBar hourSeek = new SeekBar(this);
        hourSeek.setMax(23);
        hourSeek.setProgress(Math.max(0, Math.min(23, goalHour)));
        hourSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                hourLabel.setText("Horário alvo: " + String.format(Locale.US, "%02d:00", progress));
                if (fromUser) prefs.edit().putInt("battery_goal_hour", progress).putLong("auto_user_last_apply", 0L).apply();
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) { recreate(); }
        });
        c.addView(hourSeek);

        Button resetLearning = actionButton("Reiniciar aprendizado automático");
        resetLearning.setBackground(bordered(Color.rgb(18,44,60), Color.rgb(44,91,116), 16));
        resetLearning.setOnClickListener(v -> {
            prefs.edit()
                    .remove("auto_user_best_variant")
                    .remove("auto_user_best_score")
                    .remove("auto_user_trial_variant")
                    .remove("auto_user_trial_start")
                    .remove("auto_user_trial_start_battery")
                    .remove("auto_user_avg_temp")
                    .remove("auto_user_avg_cpu")
                    .remove("auto_user_avg_power")
                    .putLong("auto_user_last_apply", 0L)
                    .putString("auto_user_status", "Aprendizado reiniciado • coletando novos dados")
                    .apply();
            Toast.makeText(this, "Aprendizado automático reiniciado.", Toast.LENGTH_SHORT).show();
            recreate();
        });
        c.addView(resetLearning);
        content.addView(c);
    }

    private void buildHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2),dp(4),dp(2),dp(14));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Adaptive Performance", 27, TEXT, true);
        TextView sub = text("Monitoramento inteligente do sistema", 14, Color.rgb(127,175,213), false);
        titles.addView(title); titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0,-2,1f));

        TextView bell = text("●", 18, CYAN, true);
        bell.setGravity(Gravity.CENTER);
        bell.setBackground(bordered(Color.rgb(12,34,49), BORDER, 15));
        head.addView(bell, new LinearLayout.LayoutParams(dp(44),dp(44)));
        content.addView(head);
    }

    private void buildHero() {
        LinearLayout hero = card();
        hero.setBackground(gradient(Color.rgb(8,75,68), Color.rgb(8,29,47), 22));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView shield = text("✓", 26, GREEN, true);
        shield.setGravity(Gravity.CENTER);
        shield.setBackground(bordered(Color.rgb(9,90,65), GREEN, 24));
        top.addView(shield, new LinearLayout.LayoutParams(dp(58),dp(58)));

        LinearLayout stBox = new LinearLayout(this);
        stBox.setOrientation(LinearLayout.VERTICAL);
        stBox.setPadding(dp(14),0,0,0);
        TextView stateLabel = text("Estado atual", 13, Color.rgb(172,206,219), false);
        statusText = text("Equilibrado", 30, Color.rgb(201,255,216), true);
        statusSubText = text("Desempenho e temperatura sob controle", 13, Color.rgb(179,210,222), false);
        stBox.addView(stateLabel); stBox.addView(statusText); stBox.addView(statusSubText);
        top.addView(stBox, new LinearLayout.LayoutParams(0,-2,1f));

        TextView stable = text("●  Sistema ativo", 12, GREEN, false);
        stable.setGravity(Gravity.CENTER);
        stable.setBackground(bordered(Color.rgb(6,54,54), Color.rgb(17,96,91), 18));
        top.addView(stable, new LinearLayout.LayoutParams(dp(120),dp(38)));
        hero.addView(top);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setPadding(0,dp(16),0,dp(8));
        socValue = metricTile(metrics,"SoC","--.-°C",ORANGE);
        batValue = metricTile(metrics,"Bateria","--.-°C",TEAL);
        ramValue = metricTile(metrics,"RAM livre","--%",CYAN);
        cpuValue = metricTile(metrics,"CPU","--%",Color.rgb(155,137,255));
        freqValue = metricTile(metrics,"Frequência","---- MHz",Color.rgb(199,235,255));
        hs.addView(metrics);
        hero.addView(hs);

        TextView chartTitle = text("Temperatura do SoC (últimas leituras)", 12, MUTED, false);
        chartTitle.setPadding(dp(4),dp(4),0,dp(4));
        hero.addView(chartTitle);
        chart = new SparklineView(this);
        chart.setBackground(bordered(Color.rgb(7,24,35), Color.rgb(23,57,73), 14));
        hero.addView(chart, new LinearLayout.LayoutParams(-1, dp(110)));
        content.addView(hero);
    }

    private void buildThermal() {
        LinearLayout c = card();
        addSectionTitle(c,"◆","Proteção térmica","Escolha um nível travado de 1 a 5 ou use o modo automático");

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0,dp(14),0,dp(2));

        thermalLevel1 = thermalChip("Nível 1\nTravado");
        thermalLevel2 = thermalChip("Nível 2\nTravado");
        thermalLevel3 = thermalChip("Nível 3\nTravado");
        thermalLevel4 = thermalChip("Nível 4\nTravado");
        thermalLevel5 = thermalChip("Nível 5\nEmergência");
        thermalAuto = thermalChip("Automático\nInteligente");

        thermalLevel1.setOnClickListener(v -> selectThermalMode("level1"));
        thermalLevel2.setOnClickListener(v -> selectThermalMode("level2"));
        thermalLevel3.setOnClickListener(v -> selectThermalMode("level3"));
        thermalLevel4.setOnClickListener(v -> selectThermalMode("level4"));
        thermalLevel5.setOnClickListener(v -> selectThermalMode("level5"));
        thermalAuto.setOnClickListener(v -> selectThermalMode("auto"));

        TextView[] chips = {thermalLevel1, thermalLevel2, thermalLevel3, thermalLevel4, thermalLevel5, thermalAuto};
        for (int i=0; i<chips.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i==5 ? 150 : 112), dp(70));
            lp.setMargins(0,0,dp(8),0);
            row.addView(chips[i], lp);
        }
        hs.addView(row);
        c.addView(hs);

        TextView warn = text("Níveis 4 e 5 são fortes; o nível 5 corresponde ao estado térmico de emergência do Android.", 12, MUTED, false);
        warn.setPadding(dp(2),dp(10),dp(2),0);
        c.addView(warn);
        content.addView(c);
    }

    private TextView thermalChip(String label) {
        TextView t = text(label, 14, MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(bordered(Color.rgb(15,36,50), Color.rgb(40,70,88), 15));
        return t;
    }

    private void buildActionsPlaceholder() {
        buildActionsPlaceholderAt(content.getChildCount());
    }

    private void buildActionsPlaceholderAt(int index) {
        LinearLayout c = card();
        addSectionTitle(c,"⚡","Ações inteligentes","Automatize estabilidade, temperatura e segundo plano");
        Button open = actionButton("Abrir ações inteligentes");
        open.setOnClickListener(v -> openActions());
        c.addView(open);
        actionsPlaceholder = c;
        actionsInsertIndex = Math.max(0, Math.min(index, content.getChildCount()));
        content.addView(c, actionsInsertIndex);
    }

    private void openActions() {
        if (!actionsBuilt) {
            content.removeView(actionsPlaceholder);
            actionsPlaceholder = null;
            buildActions();
            actionsBuilt = true;
            updateUi();
            content.getChildAt(actionsInsertIndex).addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    v.removeOnLayoutChangeListener(this);
                    mainScroll.post(() -> mainScroll.smoothScrollTo(0, v.getTop()));
                }
            });
        } else {
            mainScroll.post(() -> mainScroll.smoothScrollTo(0, content.getChildAt(actionsInsertIndex).getTop()));
        }
    }

    private void buildActions() {
        LinearLayout c = card();
        addSectionTitle(c,"⚡","Ações inteligentes","Automatize estabilidade, temperatura e segundo plano");

        autoRepairSwitch = actionSwitch("Auto-Reparo com medição antes/depois", "auto_repair", true);
        effectivenessSwitch = actionSwitch("Pontuação de eficácia e histórico", "action_effectiveness", true);
        profilesSwitch = actionSwitch("Perfis por aplicativo", "app_profiles", true);
        restrictionGuardSwitch = actionSwitch("Proteção contra otimização excessiva", "over_optimization_guard", true);
        emergencySwitch = actionSwitch("RAM emergencial: <8% livre ou <15% + PSI ≥25", "ram_emergency_mode", true);
        startupSwitch = actionSwitch("Diagnóstico na inicialização", "startup_diagnostics", true);
        Switch[] extra = {autoRepairSwitch, effectivenessSwitch, profilesSwitch, restrictionGuardSwitch, emergencySwitch, startupSwitch};
        String[] keys = {"auto_repair", "action_effectiveness", "app_profiles", "over_optimization_guard", "ram_emergency_mode", "startup_diagnostics"};
        for (int i = 0; i < extra.length; i++) {
            final String key = keys[i];
            extra[i].setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply());
            c.addView(extra[i]);
        }
        Switch timeUsageLearningSwitch = actionSwitch("Aprender padrões por horário e uso", "time_usage_learning", true);
        Switch confidenceGuardSwitch = actionSwitch("Exigir confiança mínima antes de Auto-Reparo", "cause_confidence_guard", true);
        Switch recurrenceGuardSwitch = actionSwitch("Detectar recidiva e variar estratégia", "recurrence_guard", true);
        Switch postCorrectionGuardSwitch = actionSwitch("Observar 60 s após correções antes de agir novamente", "post_correction_guard", true);
        Switch updateRegressionSwitch = actionSwitch("Detectar regressão de consumo após atualização de apps", "update_regression_detector", true);
        Switch diagnosticOnlySwitch = actionSwitch("Modo somente diagnóstico: não alterar o sistema", "diagnostic_only", false);
        Switch[] intel = {timeUsageLearningSwitch, confidenceGuardSwitch, recurrenceGuardSwitch, postCorrectionGuardSwitch, updateRegressionSwitch, diagnosticOnlySwitch};
        String[] intelKeys = {"time_usage_learning", "cause_confidence_guard", "recurrence_guard", "post_correction_guard", "update_regression_detector", "diagnostic_only"};
        for (int i = 0; i < intel.length; i++) {
            final String key = intelKeys[i];
            intel[i].setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply());
            c.addView(intel[i]);
        }
        limitingDashboardText = text(prefs.getString("limiting_dashboard", "Nenhuma limitação ativa"),12,MUTED,false);
        limitingDashboardText.setPadding(dp(8),dp(12),dp(8),dp(8));
        c.addView(limitingDashboardText);
        Button dashboard = actionButton("Ver o que está limitando agora");
        dashboard.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Limitações e inteligência adaptativa")
                .setMessage(prefs.getString("limiting_dashboard", "Nenhuma limitação ativa") + "\n\n" +
                        "Estratégia: " + prefs.getString("recurrence_strategy", "Estratégia normal") + "\n" +
                        "Confiança: " + prefs.getInt("cause_confidence",0) + "%\n" +
                        "Eficácia: " + prefs.getInt("action_effectiveness_score",0) + "%\n" +
                        (prefs.getBoolean("app_regression_detected",false) ? prefs.getString("app_regression_report","") : "Sem regressão pós-atualização detectada") + "\n\n" +
                        AdaptiveIntelligenceController.comparative24h(prefs))
                .setPositiveButton("Fechar", null).show());
        c.addView(dashboard);

        Button incidents = actionButton("Histórico de incidentes e correções");
        incidents.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Incidentes e correções")
                .setMessage(prefs.getString("incident_history", "Nenhum incidente registrado."))
                .setPositiveButton("Fechar", null).show());
        c.addView(incidents);

        refreshSwitch = actionSwitch("Reduzir a tela para 60 Hz somente com aquecimento","adaptive_refresh",true);
        cleanupSwitch = actionSwitch("Liberar processos em cache quando RAM livre < 7%","critical_cleanup",true);
        memoryCompactionSwitch = actionSwitch("Compactação automática de RAM", "memory_compaction_enabled", true);
        memoryCompactionLabel = text("", 12, MUTED, false);
        memoryCompactionLabel.setPadding(dp(12), dp(4), dp(8), 0);
        memoryCompactionSeekBar = new SeekBar(this);
        memoryCompactionSeekBar.setMax(45);
        int compactionThreshold = Math.max(50, Math.min(95,
                prefs.getInt("memory_compaction_threshold_pct", 50)));
        memoryCompactionSeekBar.setProgress(compactionThreshold - 50);
        memoryCompactionLabel.setText("Compactar quando RAM livre ≤ " + compactionThreshold + "%");

        TextView zramProfileTitle = text("Perfil de ZRAM", 13, TEXT, true);
        zramProfileTitle.setPadding(dp(12), dp(12), dp(8), dp(2));
        zramProfileSpinner = new Spinner(this);
        String[] zramProfiles = {"Normal", "Máxima", "Extrema", "Automático"};
        ArrayAdapter<String> zramAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, zramProfiles);
        zramAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        zramProfileSpinner.setAdapter(zramAdapter);
        String savedZramProfile = prefs.getString("zram_profile", "normal");
        // Migração das opções antigas para os três perfis simplificados.
        if ("aggressive".equals(savedZramProfile) || "zstd1".equals(savedZramProfile)
                || "zstd3".equals(savedZramProfile) || "zstd6".equals(savedZramProfile)) {
            savedZramProfile = "maximum";
        } else if ("extreme".equals(savedZramProfile)
                || "zstd19".equals(savedZramProfile) || "zstd22".equals(savedZramProfile)) {
            savedZramProfile = "extreme";
        }
        int zramSelection = "auto".equals(savedZramProfile) ? 3
                : ("extreme".equals(savedZramProfile) ? 2
                : ("maximum".equals(savedZramProfile) ? 1 : 0));
        zramProfileSpinner.setSelection(zramSelection, false);
        zramProfileHint = text(zramProfileDescription(savedZramProfile), 12, MUTED, false);
        zramProfileHint.setPadding(dp(12), dp(2), dp(8), dp(6));

        TextView smartTitle = text("Automação inteligente recomendada", 16, TEXT, true);
        smartTitle.setPadding(dp(12), dp(16), dp(8), dp(4));
        c.addView(smartTitle);
        Switch smartSuiteSwitch = actionSwitch("Modo automático geral: RAM + PSI + temperatura + bateria", "smart_suite_enabled", true);
        Switch smartCleanupSwitch = actionSwitch("Limpeza automática com pouco armazenamento e tela apagada", "smart_auto_cleanup", false);
        Switch adaptiveFreezeSwitch = actionSwitch("Congelamento adaptativo de apps ociosos/restritos", "adaptive_freeze_enabled", false);
        Switch recurrenceSwitch = actionSwitch("Detectar processos que reiniciam repetidamente", "restart_recurrence_guard", true);
        Switch lowBatterySwitch = actionSwitch("Modo automático de bateria baixa (≤25%)", "low_battery_adaptive", true);
        Switch thrashSwitch = actionSwitch("Proteção contra thrashing da ZRAM/swap", "zram_thrash_guard", true);
        Switch psiSmartSwitch = actionSwitch("Usar PSI real de CPU, memória e I/O nas decisões", "advanced_adaptive", true);
        Switch perAppProfileSwitch = actionSwitch("Perfis automáticos Econômico/Balanceado/Desempenho por app", "app_profiles", true);
        Switch leak2Switch = actionSwitch("Detector de vazamento de RAM por tendência", "memory_leak_detector", true);
        Switch thermalSmartSwitch = actionSwitch("Controle térmico adaptativo gradual", "thermal_prediction", true);
        for (Switch x : new Switch[]{smartSuiteSwitch,smartCleanupSwitch,adaptiveFreezeSwitch,recurrenceSwitch,lowBatterySwitch,
                thrashSwitch,psiSmartSwitch,perAppProfileSwitch,leak2Switch,thermalSmartSwitch}) c.addView(x);
        Button economyDashboard = actionButton("Painel de economia e automação");
        economyDashboard.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Economia e automação")
                .setMessage(prefs.getString("economy_dashboard","Aguardando dados do monitor…")+"\n\n"+
                        "Reinício recorrente: "+prefs.getString("restart_recurrence_pkg","nenhum")+" ("+
                        prefs.getInt("restart_recurrence_count",0)+")\n"+
                        "Swap: "+String.format(Locale.US,"%.0f páginas/s",prefs.getFloat("zram_swap_pages_sec",0))+
                        (prefs.getBoolean("zram_thrashing",false)?" • THRASHING":""))
                .setPositiveButton("Fechar",null).show());
        c.addView(economyDashboard);

        appRamLimiterSwitch = actionSwitch("Limitar apps em segundo plano com RAM excessiva (limite individual suportado)", "app_ram_limiter_enabled", false);
        appRamLimitLabel = text("", 12, MUTED, false);
        appRamLimitLabel.setPadding(dp(12), dp(4), dp(8), 0);
        appRamLimitSeekBar = new SeekBar(this);
        appRamLimitSeekBar.setMax(1400); // 100–1500 MB
        int appRamLimitMb = Math.max(100, Math.min(1500, prefs.getInt("app_ram_limit_mb", 500)));
        appRamLimitSeekBar.setProgress(appRamLimitMb - 100);
        appRamLimitLabel.setText("Limite por app em segundo plano: " + appRamLimitMb + " MB");

        aggressiveMemorySwitch = actionSwitch("Modo RAM agressivo: agir quando RAM livre < 20%","aggressive_memory_cleanup",false);
        bugCleanupSwitch = actionSwitch("Finalizar apps anormais travados em segundo plano","auto_bug_cleanup",true);
        unusedRestrictSwitch = actionSwitch("Restringir apps sem uso há mais de 3 dias","auto_unused_restrict",true);
        cpuPressureSwitch = actionSwitch("Reduzir segundo plano quando CPU/VM/energia indicarem pressão","cpu_pressure_control",true);
        advancedAdaptiveSwitch = actionSwitch("Motor preditivo: temperatura + PSI + energia + armazenamento","advanced_adaptive",true);
        screenOffSwitch = actionSwitch("Economia profunda e segura quando a tela estiver desligada","screen_off_optimization",true);
        wakeupGuardSwitch = actionSwitch("Detectar wakeups excessivos e loops de rede","wakeup_network_guard",true);
        healthGuardSwitch = actionSwitch("Saúde avançada: jank + LMKD + zRAM + Jobs","health_guard",true);
        autoCalibrationSwitch = actionSwitch("Autocalibrar limites conforme o comportamento do aparelho","auto_calibration",true);
        appLearningSwitch = actionSwitch("Aprender temperatura, CPU, RAM e energia de cada app","app_learning",true);
        antiStallSwitch = actionSwitch("Modo anti-travamento preventivo","anti_stall",true);
        historySwitch = actionSwitch("Guardar histórico de saúde das últimas 24 horas","history_24h",true);
        rollbackSwitch = actionSwitch("Rollback automático de restrições se piorarem a fluidez","rollback_guard",true);
        crashLoopSwitch = actionSwitch("Conter apps em loop de crash/reinício","crash_loop_guard",true);
        extendedDiagnosticsSwitch = actionSwitch("Diagnóstico estendido: ANR + Binder + GPU + sensores","extended_diagnostics",true);
        thermalPredictionSwitch = actionSwitch("Prever aquecimento com 1–3 minutos de antecedência","thermal_prediction",true);
        diagnosticBurstSwitch = actionSwitch("Modo diagnóstico de 10 min ao detectar anomalia","diagnostic_burst",true);
        adaptiveAggressivenessSwitch = actionSwitch("Ajustar agressividade conforme estabilidade e consumo","adaptive_aggressiveness",true);
        abTestingSwitch = actionSwitch("Teste A/B interno após autocalibração","ab_testing",true);
        sensorModemSwitch = actionSwitch("Monitorar sensores, GPS, câmera, microfone e modem","sensor_modem_guard",true);
        storageGuardSwitch = actionSwitch("Monitorar saturação de armazenamento/I/O","storage_guard",true);
        duplicateDetectionSwitch = actionSwitch("Detectar serviços de automação duplicados","duplicate_detection",true);
        deepSleepSwitch = actionSwitch("Monitorar deep sleep e Doze com a tela apagada","deep_sleep_monitor",true);
        memoryLeakSwitch = actionSwitch("Detectar crescimento anormal de memória por aplicativo","memory_leak_detector",true);
        thermalBrightnessSwitch = actionSwitch("Reduzir brilho temporariamente em aquecimento forte","thermal_brightness_control",true);
        systemBatteryGuardSwitch = actionSwitch("Economia sistêmica adaptativa","system_battery_guard",true);
        systemDeepIdleSwitch = actionSwitch("Deep idle: pausar monitoramento e liberar Doze","system_deep_idle",true);
        systemRadioSwitch = actionSwitch("Economizar scans Wi-Fi e dados paralelos em repouso","system_radio_savings",true);
        dynamicDozeSwitch = actionSwitch("Gerenciar exceções do Doze automaticamente","dynamic_doze_whitelist",true);
        adaptiveTimeoutSwitch = actionSwitch("Timeout da tela adaptativo (até 3 min em uso comum)","adaptive_screen_timeout",true);
        maximumBatterySwitch = actionSwitch("Máxima economia: desativar dois-toques no repouso","maximum_battery_mode",false);
        safeModeManualSwitch = actionSwitch("Forçar modo seguro dos módulos avançados","extended_safe_mode_manual",false);
        manualFreezeSwitch = actionSwitch("Ativar congelamento manual dos apps selecionados","manual_freeze_enabled",false);
        notifySwitch = actionSwitch("Notificar somente problemas que não puderem ser resolvidos","notify_changes",true);

        refreshSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("adaptive_refresh",v).apply());
        cleanupSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("critical_cleanup",v).apply());
        memoryCompactionSwitch.setOnCheckedChangeListener((b,v) ->
                prefs.edit().putBoolean("memory_compaction_enabled", v).apply());
        memoryCompactionSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int pct = 50 + progress;
                memoryCompactionLabel.setText("Compactar quando RAM livre ≤ " + pct + "%");
                if (fromUser) prefs.edit()
                        .putInt("memory_compaction_threshold_pct", pct)
                        .apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        zramProfileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String profile = position == 3 ? "auto" : (position == 2 ? "extreme" : (position == 1 ? "maximum" : "normal"));
                prefs.edit()
                        .putString("zram_profile", profile)
                        .putInt("zstd_requested_level",
                                "extreme".equals(profile) ? 19 : ("maximum".equals(profile) ? 1 : ("auto".equals(profile) ? 19 : 0)))
                        .apply();
                if (zramProfileHint != null) zramProfileHint.setText(zramProfileDescription(profile));
                boolean aggressiveMode = !"normal".equals(profile);
                if (aggressiveMemorySwitch != null && aggressiveMemorySwitch.isChecked() != aggressiveMode) {
                    aggressiveMemorySwitch.setChecked(aggressiveMode);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        appRamLimiterSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("app_ram_limiter_enabled",v).apply());
        appRamLimitSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int mb = 100 + progress;
                appRamLimitLabel.setText("Limite por app em segundo plano: " + mb + " MB");
                if (fromUser) prefs.edit().putInt("app_ram_limit_mb", mb).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        aggressiveMemorySwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("aggressive_memory_cleanup",v).apply());
        bugCleanupSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("auto_bug_cleanup",v).apply());
        unusedRestrictSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("auto_unused_restrict",v).apply());
        cpuPressureSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("cpu_pressure_control",v).apply());
        advancedAdaptiveSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("advanced_adaptive",v).apply());
        screenOffSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("screen_off_optimization",v).apply());
        wakeupGuardSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("wakeup_network_guard",v).apply());
        healthGuardSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("health_guard",v).apply());
        autoCalibrationSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("auto_calibration",v).apply());
        appLearningSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("app_learning",v).apply());
        antiStallSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("anti_stall",v).apply());
        historySwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("history_24h",v).apply());
        rollbackSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("rollback_guard",v).apply());
        crashLoopSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("crash_loop_guard",v).apply());
        extendedDiagnosticsSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("extended_diagnostics",v).apply());
        thermalPredictionSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("thermal_prediction",v).apply());
        diagnosticBurstSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("diagnostic_burst",v).apply());
        adaptiveAggressivenessSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("adaptive_aggressiveness",v).apply());
        abTestingSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("ab_testing",v).apply());
        sensorModemSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("sensor_modem_guard",v).apply());
        storageGuardSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("storage_guard",v).apply());
        duplicateDetectionSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("duplicate_detection",v).apply());
        deepSleepSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("deep_sleep_monitor",v).apply());
        memoryLeakSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("memory_leak_detector",v).apply());
        thermalBrightnessSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("thermal_brightness_control",v).apply());
        systemBatteryGuardSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("system_battery_guard",v).apply());
        systemDeepIdleSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("system_deep_idle",v).apply());
        systemRadioSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("system_radio_savings",v).apply());
        dynamicDozeSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("dynamic_doze_whitelist",v).apply());
        adaptiveTimeoutSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("adaptive_screen_timeout",v).apply());
        maximumBatterySwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("maximum_battery_mode",v).apply());
        safeModeManualSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("extended_safe_mode_manual",v).apply());
        manualFreezeSwitch.setOnCheckedChangeListener((b,v)->{
            prefs.edit().putBoolean("manual_freeze_enabled",v).apply();
            ChangeNotifier.notifyChange(this,"Congelamento manual",v?"Congelamento manual ativado.":"Congelamento manual desativado; apps serão liberados.",6);
        });
        notifySwitch.setOnCheckedChangeListener((b,v)->{
            prefs.edit().putBoolean("notify_changes",v).apply();
            if(v) ChangeNotifier.notifyChange(this,"Notificações ativadas","O Adaptive Performance avisará sobre mudanças importantes.",9);
        });

        c.addView(refreshSwitch); c.addView(cleanupSwitch); c.addView(memoryCompactionSwitch); c.addView(memoryCompactionLabel); c.addView(memoryCompactionSeekBar);
        c.addView(zramProfileTitle); c.addView(zramProfileSpinner); c.addView(zramProfileHint);
        c.addView(appRamLimiterSwitch); c.addView(appRamLimitLabel); c.addView(appRamLimitSeekBar);
        c.addView(aggressiveMemorySwitch); c.addView(bugCleanupSwitch);
        c.addView(unusedRestrictSwitch); c.addView(cpuPressureSwitch);
        c.addView(advancedAdaptiveSwitch); c.addView(screenOffSwitch); c.addView(wakeupGuardSwitch);
        c.addView(healthGuardSwitch); c.addView(autoCalibrationSwitch); c.addView(appLearningSwitch);
        c.addView(antiStallSwitch); c.addView(historySwitch); c.addView(rollbackSwitch); c.addView(crashLoopSwitch);
        c.addView(extendedDiagnosticsSwitch); c.addView(thermalPredictionSwitch); c.addView(diagnosticBurstSwitch);
        c.addView(adaptiveAggressivenessSwitch); c.addView(abTestingSwitch); c.addView(sensorModemSwitch);
        c.addView(storageGuardSwitch); c.addView(duplicateDetectionSwitch);
        c.addView(deepSleepSwitch); c.addView(memoryLeakSwitch); c.addView(thermalBrightnessSwitch);
        c.addView(systemBatteryGuardSwitch); c.addView(systemDeepIdleSwitch); c.addView(systemRadioSwitch);
        c.addView(dynamicDozeSwitch); c.addView(adaptiveTimeoutSwitch); c.addView(maximumBatterySwitch);
        c.addView(safeModeManualSwitch);
        c.addView(manualFreezeSwitch);

        Button signalOptimizer = actionButton("📶  Otimizador automático de sinal móvel");
        signalOptimizer.setOnClickListener(v -> startActivity(new Intent(this, SignalOptimizerActivity.class)));
        c.addView(signalOptimizer);

        Button dnsFirewall = actionButton("🛡  Proteção DNS AdGuard • sem VPN / por app");
        dnsFirewall.setOnClickListener(v -> startActivity(new Intent(this, DnsFirewallActivity.class)));
        c.addView(dnsFirewall);

        Button freezeSelect = actionButton("❄  Selecionar apps para congelar");
        freezeSelect.setOnClickListener(v->startActivity(new Intent(this, FreezeSelectionActivity.class)));
        c.addView(freezeSelect);

        Button appExceptions = actionButton("Apps que nunca devem ser limitados automaticamente");
        appExceptions.setOnClickListener(v->startActivity(new Intent(this, AppExceptionActivity.class)));
        c.addView(appExceptions);
        c.addView(notifySwitch);

        freezeStateText = text("",12,MUTED,false);
        freezeStateText.setPadding(dp(8),dp(10),0,0);
        c.addView(freezeStateText);

        content.addView(c, actionsInsertIndex);
    }

    private void buildInsights() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout battery = card();
        battery.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1f));
        addSectionTitle(battery,"▣","Apps que mais gastam bateria","Ranking atual");
        batteryText = text("Aguardando dados…",13,Color.rgb(206,224,236),false);
        batteryText.setPadding(dp(2),dp(12),0,0);
        battery.addView(batteryText);

        LinearLayout activity = card();
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0,-2,1f);
        alp.setMargins(dp(10),0,0,dp(14));
        activity.setLayoutParams(alp);
        addSectionTitle(activity,"◷","Atividade recente","Últimas mudanças");
        maintenanceText = text("Nenhuma atividade recente.",13,Color.rgb(206,224,236),false);
        maintenanceText.setPadding(dp(2),dp(12),0,0);
        activity.addView(maintenanceText);

        row.addView(battery); row.addView(activity);
        content.addView(row);

        LinearLayout restrict = card();
        addSectionTitle(restrict,"◉","Apps restritos por inatividade","Restrições automáticas reversíveis");
        restrictedText = text("Nenhum app restrito automaticamente.",13,Color.rgb(206,224,236),false);
        restrictedText.setPadding(0,dp(10),0,0);
        restrict.addView(restrictedText);
        content.addView(restrict);

        Button historyButton = actionButton("Histórico de saúde — 24 horas");
        historyButton.setOnClickListener(v -> startActivity(new Intent(this, HealthHistoryActivity.class)));
        content.addView(historyButton);

        Button exportButton = actionButton("Exportar diagnóstico para Downloads");
        exportButton.setOnClickListener(v -> exportDiagnostics());
        content.addView(exportButton);
    }

    private void buildSystemCard() {
        LinearLayout c = card();
        addSectionTitle(c,"⚙","Sistema e serviço","Shizuku, monitor e controles do aplicativo");

        shizukuText = text("Shizuku: verificando…",13,MUTED,false);
        shizukuText.setPadding(0,dp(12),0,dp(4));
        c.addView(shizukuText);

        cpuPressureText = text("",12,MUTED,false);
        c.addView(cpuPressureText);

        advancedText = text("",12,MUTED,false);
        advancedText.setPadding(0,dp(5),0,0);
        c.addView(advancedText);

        healthText = text("",12,MUTED,false);
        healthText.setPadding(0,dp(7),0,0);
        c.addView(healthText);

        extendedText = text("",12,MUTED,false);
        extendedText.setPadding(0,dp(7),0,0);
        c.addView(extendedText);

        startButton = actionButton("Parar otimização");
        startButton.setOnClickListener(v -> {
            if (prefs.getBoolean("master", false)) stopOptimizer(); else ensurePermissionAndStart();
        });
        c.addView(startButton);

        permissionButton = actionButton("Autorizar Shizuku");
        permissionButton.setBackground(bordered(Color.rgb(18,44,60), Color.rgb(44,91,116), 16));
        permissionButton.setOnClickListener(v->requestShizuku());
        c.addView(permissionButton);

        Button open = actionButton("Abrir Shizuku");
        open.setBackground(bordered(Color.rgb(18,44,60), Color.rgb(44,91,116), 16));
        open.setOnClickListener(v->{
            try {
                Intent i=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if(i!=null) startActivity(i);
            } catch(Throwable ignored){}
        });
        c.addView(open);

        TextView note = text("Automático preditivo: térmico, VM/PSI, jank, LMKD, zRAM, Binder/ANR, GPU, sensores, modem, I/O, deep sleep, vazamento de memória e aprendizado por app. Diagnósticos pesados são assíncronos e entram em modo seguro se ficarem lentos.",12,MUTED,false);
        note.setPadding(0,dp(12),0,0);
        c.addView(note);
        content.addView(c);
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(12),dp(7),dp(12),dp(7));
        nav.setBackground(bordered(Color.rgb(7,23,34), Color.rgb(24,56,73), 20));

        TextView home = navItem("⌂\nInício",CYAN,true);
        TextView apps = navItem("▦\nApps",MUTED,false);
        TextView clean = navItem("✦\nLimpeza",MUTED,false);
        TextView settings = navItem("⚙\nAjustes",MUTED,false);

        home.setOnClickListener(v -> {
            if (mainScroll != null) mainScroll.post(() -> mainScroll.smoothScrollTo(0,0));
        });
        apps.setOnClickListener(v -> startActivity(new Intent(this,FreezeSelectionActivity.class)));
        clean.setOnClickListener(v -> startActivity(new Intent(this,StorageCleanupActivity.class)));
        settings.setOnClickListener(v -> openActions());

        nav.addView(home,new LinearLayout.LayoutParams(0,-1,1f));
        nav.addView(apps,new LinearLayout.LayoutParams(0,-1,1f));
        nav.addView(clean,new LinearLayout.LayoutParams(0,-1,1f));
        nav.addView(settings,new LinearLayout.LayoutParams(0,-1,1f));
        return nav;
    }

    private TextView navItem(String label, int color, boolean bold) {
        TextView t=text(label,12,color,bold);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int manualThermalLevel(String mode) {
        if (mode == null || !mode.startsWith("level")) return 0;
        try {
            int level = Integer.parseInt(mode.substring(5));
            return (level >= 1 && level <= 5) ? level : 0;
        } catch (Throwable ignored) { return 0; }
    }

    private void selectThermalMode(String mode) {
        int level = manualThermalLevel(mode);
        if (level == 0) mode = "auto";
        prefs.edit().putString("thermal_mode", mode).apply();
        if (prefs.getBoolean("master", false)) {
            Intent i = new Intent(this, OptimizationService.class);
            i.setAction(OptimizationService.ACTION_SET_THERMAL_MODE);
            i.putExtra("mode", mode);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
        String label = level > 0 ? "Nível " + level + " travado" : "Automático inteligente";
        ChangeNotifier.notifyChange(this, "Modo térmico alterado", label + " selecionado.", 1);
        updateUi();
    }

    private void ensurePermissionAndStart() {
        if (!Shizuku.pingBinder()) { updateShizuku(); return; }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) startOptimizer();
        else requestShizuku();
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) { updateShizuku(); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(SHIZUKU_REQ);
            else updateShizuku();
        } catch(Throwable t) { updateShizuku(); }
    }

    private void startOptimizer() {
        Intent i = new Intent(this, OptimizationService.class)
                .setAction(OptimizationService.ACTION_START_OPTIMIZATION);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            handler.postDelayed(this::updateUi, 250L);
        } catch (Throwable t) {
            prefs.edit()
                    .putBoolean("master", false)
                    .putBoolean("service_running", false)
                    .putString("last_error", "Falha ao iniciar serviço: " + t.getClass().getSimpleName())
                    .apply();
            Toast.makeText(this, "Não foi possível iniciar a otimização.", Toast.LENGTH_LONG).show();
            updateUi();
        }
    }

    private void stopOptimizer() {
        prefs.edit().putBoolean("master", false).apply();
        Intent stop = new Intent(this, OptimizationService.class)
                .setAction(OptimizationService.ACTION_STOP_OPTIMIZATION);
        try { startService(stop); } catch (Throwable ignored) {}
        stopService(new Intent(this, OptimizationService.class));
        updateUi();
    }

    private void updateShizuku() {
        String s;
        try {
            if(!Shizuku.pingBinder()) s="Shizuku indisponível";
            else if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED) s="Shizuku autorizado • acesso shell ativo";
            else s="Shizuku ativo • aguardando autorização";
        } catch(Throwable t){ s="Shizuku indisponível"; }
        if (shizukuText != null) shizukuText.setText(s);
    }

    private void setTextIfChanged(TextView view, CharSequence value) {
        if (view == null) return;
        if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private void updateUi() {
        boolean master=prefs.getBoolean("master",false);
        if (startButton != null && (!lastRenderedMasterValid || lastRenderedMaster != master)) {
            startButton.setText(master ? "Parar otimização" : "Iniciar otimização");
            startButton.setBackground(master
                    ? gradient(Color.rgb(22,157,220),Color.rgb(17,105,210),16)
                    : gradient(Color.rgb(35,178,105),Color.rgb(17,126,91),16));
            lastRenderedMaster = master;
            lastRenderedMasterValid = true;
        }

        String raw=prefs.getString("status", master?"Aguardando primeira leitura":"Monitor parado");
        String profile=raw;
        int idx=raw.indexOf(" • ");
        if(idx>0) profile=raw.substring(0,idx);
        if(profile.matches("Térmico nível [1-5] travado")) {
            profile = profile.replace("Térmico ", "");
        } else if(profile.startsWith("Térmico máximo")) profile="Proteção máxima";
        else if(profile.startsWith("Térmico nível 1")) profile="Proteção térmica";
        else if(profile.startsWith("CPU alta")) profile="CPU sob controle";
        setTextIfChanged(statusText, profile);

        float soc=prefs.getFloat("thermal_soc_c",-1f);
        float bat=prefs.getFloat("thermal_battery_c",prefs.getFloat("temp_c",-1f));
        float ram=prefs.getFloat("ram_free_pct",-1f);
        float cpu=prefs.getFloat("cpu_load",-1f);
        long freq=prefs.getLong("cpu_freq_khz",-1L);

        setTextIfChanged(socValue, soc>0?String.format(Locale.US,"%.1f°C",soc):"--.-°C");
        setTextIfChanged(batValue, bat>0?String.format(Locale.US,"%.1f°C",bat):"--.-°C");
        setTextIfChanged(ramValue, ram>=0?String.format(Locale.US,"%.0f%%",ram):"--%");
        setTextIfChanged(cpuValue, cpu>=0?String.format(Locale.US,"%.0f%%",cpu):"--%");
        setTextIfChanged(freqValue, freq>0?String.format(Locale.US,"%.2f GHz",freq/1000000.0):"---- MHz");
        if(chart!=null && soc>0 && (Float.isNaN(lastChartSoc) || Math.abs(soc-lastChartSoc)>=0.1f)) { chart.addValue(soc); lastChartSoc=soc; }

        int thermal=prefs.getInt("thermal_level",0);
        String thermalMode=prefs.getString("thermal_mode","auto");
        String thermalStyleKey = thermalMode + ":" + thermal;
        if (!thermalStyleKey.equals(lastThermalStyleKey)) {
            styleThermal(thermalLevel1, "level1".equals(thermalMode), GREEN);
            styleThermal(thermalLevel2, "level2".equals(thermalMode), ORANGE);
            styleThermal(thermalLevel3, "level3".equals(thermalMode), Color.rgb(255,151,62));
            styleThermal(thermalLevel4, "level4".equals(thermalMode), RED);
            styleThermal(thermalLevel5, "level5".equals(thermalMode), Color.rgb(255,58,58));
            styleThermal(thermalAuto, "auto".equals(thermalMode), CYAN);
            lastThermalStyleKey = thermalStyleKey;
        }

        if(statusSubText!=null) {
            int lockedLevel = manualThermalLevel(thermalMode);
            if(lockedLevel > 0) statusSubText.setText("Thermal nível " + lockedLevel + " mantido manualmente");
            else if(thermal>=5) statusSubText.setText("Proteção máxima ativa para reduzir o aquecimento");
            else if(thermal==1) statusSubText.setText("Proteção térmica automática em ação");
            else if(prefs.getInt("health_antistall",0)>=2) statusSubText.setText("Modo anti-travamento forte em ação");
            else if(prefs.getInt("health_antistall",0)==1) statusSubText.setText("Prevenção de travamentos ativa");
            else if(prefs.getBoolean("cpu_pressure_active",false)) statusSubText.setText("Apps em segundo plano temporariamente limitados");
            else statusSubText.setText("Desempenho e temperatura sob controle");
        }

        if(batteryText!=null) {
            String rank=prefs.getString("battery_rank","Aguardando coleta de dados…");
            String[] lines=rank.split("\n");
            StringBuilder shortRank=new StringBuilder();
            for(int i=0;i<Math.min(4,lines.length);i++) {
                if(i>0) shortRank.append("\n");
                shortRank.append(lines[i]);
            }
            setTextIfChanged(batteryText, shortRank.toString());
        }

        if(maintenanceText!=null) {
            String log=prefs.getString("maintenance_log","Nenhuma atividade recente.");
            String[] lines=log.split("\n");
            StringBuilder out=new StringBuilder();
            for(int i=0;i<Math.min(4,lines.length);i++) {
                if(i>0) out.append("\n");
                out.append(lines[i]);
            }
            setTextIfChanged(maintenanceText, out.toString());
        }

        if(restrictedText!=null) {
            String r=prefs.getString("restricted_text","Nenhum app restrito automaticamente.");
            String[] lines=r.split("\n");
            StringBuilder out=new StringBuilder();
            for(int i=0;i<Math.min(8,lines.length);i++) {
                if(i>0) out.append("\n");
                out.append(lines[i]);
            }
            setTextIfChanged(restrictedText, out.toString());
        }

        int sel=prefs.getStringSet("manual_freeze_selected",Collections.emptySet()).size();
        int active=prefs.getInt("manual_frozen_active_count",0);
        if(freezeStateText!=null) freezeStateText.setText(sel+" selecionado(s) • "+active+" congelado(s) agora\n"+prefs.getString("last_effectiveness_summary", ""));
        if(limitingDashboardText!=null) limitingDashboardText.setText(prefs.getString("limiting_dashboard", "Nenhuma limitação ativa"));

        if(cpuPressureText!=null) {
            if(prefs.getBoolean("cpu_pressure_active",false))
                cpuPressureText.setText("Proteção de pressão ativa • "+prefs.getInt("cpu_limited_apps",0)+" apps limitados");
            else cpuPressureText.setText("Pressão combinada: normal");
        }

        if(advancedText!=null) {
            int pscore=prefs.getInt("adaptive_pressure_score",0);
            float trend=prefs.getFloat("temp_trend_c_min",0f);
            float psiM=prefs.getFloat("psi_memory",-1f);
            float psiIo=prefs.getFloat("psi_io",-1f);
            float watts=prefs.getFloat("power_w",-1f);
            float storage=prefs.getFloat("storage_free_pct",-1f);
            String adaptiveProfile=prefs.getString("adaptive_profile","Aguardando leitura preditiva");
            StringBuilder a=new StringBuilder(adaptiveProfile).append(" • P").append(pscore)
                    .append(String.format(Locale.US," • ΔT %+.1f°C/min",trend));
            String pressureSource=prefs.getString("pressure_signal_source","PSI");
            String pressurePrefix="PSI".equals(pressureSource) ? "PSI" : "VM";
            if(psiM>=0) a.append(String.format(Locale.US," • %s-RAM %.1f",pressurePrefix,psiM));
            if(psiIo>=0) a.append(String.format(Locale.US," • %s-I/O %.1f",pressurePrefix,psiIo));
            if(watts>0) a.append(String.format(Locale.US," • %.1f W",watts));
            if(storage>=0) a.append(String.format(Locale.US," • armazenamento %.0f%% livre",storage));
            int wl=prefs.getInt("held_wakelocks",0);
            if(wl>0) a.append(" • wakelocks ").append(wl);
            setTextIfChanged(advancedText, a.toString());
        }

        if(healthText!=null) {
            int ht=prefs.getInt("health_thermal",0);
            int hm=prefs.getInt("health_memory",0);
            int hc=prefs.getInt("health_cpu",0);
            int hi=prefs.getInt("health_io",0);
            int hb=prefs.getInt("health_battery",0);
            int hu=prefs.getInt("health_ui",0);
            int lmk=prefs.getInt("lmk_delta",0);
            int jobs=prefs.getInt("jobs_count",0);
            int crashes=prefs.getInt("crash_loop_count",0);
            float jank=prefs.getFloat("jank_pct",-1f);
            float zr=prefs.getFloat("zram_swap_mb",-1f);
            float zc=prefs.getFloat("zram_compression",-1f);
            int points=prefs.getInt("history_points",0);
            int cal=prefs.getInt("cal_n",0);
            long cycle=prefs.getLong("last_cycle_cost_ms",0);
            String hp=prefs.getString("health_profile","Aguardando diagnóstico de saúde");
            StringBuilder h=new StringBuilder(hp)
                    .append(" • T").append(ht).append(" M").append(hm)
                    .append(" C").append(hc).append(" I").append(hi)
                    .append(" B").append(hb).append(" UI").append(hu);
            if(jank>=0) h.append(String.format(Locale.US," • jank %.1f%%",jank));
            if(zr>=0) h.append(String.format(Locale.US," • zRAM %.0f MB",zr));
            if(zc>0) h.append(String.format(Locale.US," (%.1fx)",zc));
            h.append(" • LMK +").append(lmk).append(" • Jobs ").append(jobs);
            if(crashes>0) h.append(" • loops ").append(crashes);
            h.append(" • calib ").append(cal).append("/40")
                    .append(" • hist ").append(points)
                    .append(" • ciclo ").append(cycle).append("ms");
            if(prefs.getBoolean("health_watchdog_slow",false)) h.append(" • watchdog econômico");
            setTextIfChanged(healthText, h.toString());
        }

        if(extendedText!=null) {
            float p1=prefs.getFloat("thermal_pred_1m",-1f);
            float p3=prefs.getFloat("thermal_pred_3m",-1f);
            int plevel=prefs.getInt("thermal_pred_level",0);
            int stable=prefs.getInt("current_app_stability",100);
            int launch=prefs.getInt("launch_stabilization_ms",-1);
            int anr=prefs.getInt("anr_recent",0);
            int binderSlow=prefs.getInt("binder_slow_calls",0);
            int binderVery=prefs.getInt("binder_very_slow_calls",0);
            float gpu=prefs.getFloat("gpu_p90_ms",-1f);
            int sensors=prefs.getInt("active_sensors",0);
            int sensorConn=prefs.getInt("open_sensor_connections",0);
            int gps=prefs.getInt("active_location_requests",0);
            int rsrp=prefs.getInt("signal_rsrp",0);
            int level=prefs.getInt("signal_level",-1);
            int disk=prefs.getInt("disk_write_kb_s",-1);
            int dup=prefs.getInt("duplicate_services",0);
            long scan=prefs.getLong("extended_scan_cost_ms",0);
            String charger=prefs.getString("charger_profile","Bateria");
            String activity=prefs.getString("activity_profile_extended","Uso leve");
            String cause=prefs.getString("probable_cause","Aguardando diagnóstico estendido");
            String ab=prefs.getString("ab_state","A/B aguardando autocalibração");
            String radio=prefs.getString("radio_type","");
            StringBuilder e=new StringBuilder();
            e.append("Causa provável: ").append(cause)
                    .append("\nPerfil: ").append(activity)
                    .append(" • ").append(charger)
                    .append(" • estabilidade ").append(stable).append("/100");
            if(p1>0 && p3>0)
                e.append(String.format(Locale.US," • previsão %.1f/%.1f°C (N%d)",p1,p3,plevel));
            if(launch>0) e.append(" • estabilização ~").append(launch).append("ms");
            e.append("\nANR ").append(anr)
                    .append(" • Binder ").append(binderSlow).append("/").append(binderVery);
            if(gpu>=0) e.append(String.format(Locale.US," • GPU p90 %.0fms",gpu));
            e.append(" • sensores ").append(sensors).append("/").append(sensorConn)
                    .append(" • GPS ").append(gps);
            if(rsrp!=0) e.append(" • ").append(radio).append(" ").append(rsrp).append("dBm L").append(level);
            if(disk>=0) e.append(" • escrita ").append(disk).append("kB/s");
            if(dup>0) e.append(" • duplicados ").append(dup);
            e.append("\n").append(ab).append(" • varredura ").append(scan).append("ms");
            if(prefs.getBoolean("diagnostic_mode_active",false)) e.append(" • diagnóstico 10min");
            if(prefs.getBoolean("extended_safe_mode",false)) e.append(" • MODO SEGURO");

            float deep=prefs.getFloat("deep_sleep_pct",-1f);
            long deepObs=prefs.getLong("deep_sleep_observed_ms",0L);
            boolean doze=prefs.getBoolean("device_idle_mode",false);
            boolean leak=prefs.getBoolean("memory_leak_suspected",false);
            String leakLabel=prefs.getString("memory_leak_label","");
            float leakGrowth=prefs.getFloat("memory_leak_growth_mb",-1f);
            boolean brightness=prefs.getBoolean("thermal_brightness_applied",false);
            String brightnessAction=prefs.getString("thermal_brightness_action","");
            int protectedApps=prefs.getStringSet("auto_protected_apps",Collections.emptySet()).size();

            e.append("\nDeep sleep ");
            if(deep>=0) e.append(String.format(Locale.US,"%.0f%%",deep));
            else e.append("--");
            e.append(" • observado ").append(deepObs/60000L).append("min")
                    .append(" • Doze ").append(doze?"sim":"não")
                    .append(" • exceções ").append(protectedApps);
            if(leak) e.append(String.format(Locale.US," • possível vazamento: %s +%.0f MB",leakLabel,leakGrowth));
            if(brightness) e.append(" • brilho térmico ativo");
            else if(!brightnessAction.isEmpty()) e.append(" • ").append(brightnessAction);

            boolean sysDeep=prefs.getBoolean("system_deep_idle_active",false);
            boolean automation=prefs.getBoolean("system_automation_active",false);
            String wakeReport=prefs.getString("persistent_wakelock_report","");
            boolean stuckSync=prefs.getBoolean("stuck_sync_detected",false);
            String syncReport=prefs.getString("stuck_sync_report","");
            boolean bgSensor=prefs.getBoolean("background_sensor_suspected",false);
            String sensorReport=prefs.getString("background_sensor_report","");
            int sysSensors=prefs.getInt("system_sensor_active_count",0);
            int sysOpen=prefs.getInt("system_sensor_open_connections",0);
            int sysFast=prefs.getInt("system_sensor_fastest_ms",-1);
            e.append("\nSistema: ")
                    .append(sysDeep?"deep idle":"ativo")
                    .append(" • automação ").append(automation?"em execução":"inativa")
                    .append(" • sensores ").append(sysSensors).append("/").append(sysOpen);
            if(sysFast>=0) e.append(" • mais rápido ").append(sysFast).append("ms");
            if(!wakeReport.isEmpty()) e.append("\nWakelock: ").append(wakeReport);
            if(stuckSync && !syncReport.isEmpty()) e.append("\nSync: ").append(syncReport);
            if(bgSensor && !sensorReport.isEmpty()) e.append("\nSensor: ").append(sensorReport);
            setTextIfChanged(extendedText, e.toString());
        }
    }

    private void exportDiagnostics() {
        try {
            ExtendedDiagnosticsController controller =
                    new ExtendedDiagnosticsController(this, prefs, null);
            String report = controller.buildReport() + "\n\n=== Comparativo 24h ===\n" +
                    AdaptiveIntelligenceController.comparative24h(prefs) +
                    "\n\n=== Inteligência adaptativa ===\n" +
                    prefs.getString("limiting_dashboard", "Nenhuma limitação ativa") +
                    "\nEstratégia: " + prefs.getString("recurrence_strategy", "Estratégia normal") +
                    "\nConfiança da causa: " + prefs.getInt("cause_confidence",0) + "%" +
                    "\nEficácia da última ação: " + prefs.getInt("action_effectiveness_score",0) + "%";
            String name = "AdaptivePerformance-diagnostico-" +
                    new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                            .format(new Date()) + ".txt";
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            android.net.Uri collection;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            } else {
                java.io.File downloads = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.exists()) downloads.mkdirs();
                values.put(android.provider.MediaStore.MediaColumns.DATA,
                        new java.io.File(downloads, name).getAbsolutePath());
                collection = android.provider.MediaStore.Files.getContentUri("external");
            }
            android.net.Uri uri = getContentResolver().insert(collection, values);
            if (uri == null) throw new IllegalStateException("Não foi possível criar o arquivo.");
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Não foi possível abrir o arquivo.");
                out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            }
            Toast.makeText(this, "Diagnóstico salvo em Downloads: " + name, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Falha ao exportar: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void styleThermal(TextView v, boolean active, int accent) {
        if(v==null) return;
        v.setTextColor(active ? TEXT : MUTED);
        v.setBackground(active
                ? bordered(Color.rgb(12,48,64),accent,15)
                : bordered(Color.rgb(15,36,50),Color.rgb(40,70,88),15));
    }

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (now - lastShizukuUiCheckElapsed >= 60_000L) {
                updateShizuku();
                lastShizukuUiCheckElapsed = now;
            }
            updateUi();
            handler.postDelayed(this,15000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshLoop);
        lastShizukuUiCheckElapsed = 0L;
        handler.post(refreshLoop);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshLoop);
        unloadActionsSection();
        super.onPause();
    }

    private String zramProfileDescription(String profile) {
        if ("auto".equals(profile)) {
            return "Automático: Extrema com ≤30% de RAM livre, reduz para Máxima e Normal até 50%. Reduz com calor, pausa a 40 °C e retoma após normalizar em ≤36 °C.";
        }
        if ("extreme".equals(profile)) {
            return "Extrema: referência ZSTD-19/22, maior compactação no teste de 512 MB (2,641×). Maior custo de CPU; fallback seguro se o kernel bloquear ZSTD.";
        }
        if ("maximum".equals(profile)) {
            return "Máxima: referência ZSTD-1, quase a mesma compactação do máximo com muito menos processamento.";
        }
        return "Normal: equilíbrio entre RAM disponível, CPU e bateria.";
    }

    private void unloadActionsSection() {
        autoRepairSwitch = effectivenessSwitch = profilesSwitch = restrictionGuardSwitch = emergencySwitch = startupSwitch = null;
        if (!actionsBuilt || content == null) return;
        int idx = actionsInsertIndex;
        if (idx >= 0 && idx < content.getChildCount()) content.removeViewAt(idx);
        refreshSwitch = cleanupSwitch = aggressiveMemorySwitch = bugCleanupSwitch = unusedRestrictSwitch = cpuPressureSwitch = null;
        memoryCompactionSwitch = null; memoryCompactionLabel = null; memoryCompactionSeekBar = null;
        zramProfileSpinner = null; zramProfileHint = null;
        manualFreezeSwitch = notifySwitch = advancedAdaptiveSwitch = screenOffSwitch = wakeupGuardSwitch = null;
        healthGuardSwitch = autoCalibrationSwitch = appLearningSwitch = antiStallSwitch = historySwitch = null;
        rollbackSwitch = crashLoopSwitch = extendedDiagnosticsSwitch = thermalPredictionSwitch = diagnosticBurstSwitch = null;
        adaptiveAggressivenessSwitch = abTestingSwitch = sensorModemSwitch = storageGuardSwitch = duplicateDetectionSwitch = null;
        safeModeManualSwitch = deepSleepSwitch = memoryLeakSwitch = thermalBrightnessSwitch = null;
        systemBatteryGuardSwitch = systemDeepIdleSwitch = systemRadioSwitch = dynamicDozeSwitch = adaptiveTimeoutSwitch = maximumBatterySwitch = null;
        freezeStateText = null;
        limitingDashboardText = null;
        actionsBuilt = false;
        actionsPlaceholder = null;
        buildActionsPlaceholderAt(idx < 0 ? content.getChildCount() : idx);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }

    public static class SparklineView extends View {
        private final ArrayDeque<Float> values = new ArrayDeque<>();
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        public SparklineView(Context c) {
            super(c);
            grid.setColor(Color.rgb(24,55,69));
            grid.setStrokeWidth(1f);
            line.setColor(Color.rgb(55,230,220));
            line.setStrokeWidth(4f);
            line.setStyle(Paint.Style.STROKE);
        }

        public void addValue(float v) {
            if(values.size()>=30) values.removeFirst();
            values.addLast(v);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w=getWidth(), h=getHeight();
            for(int i=1;i<4;i++) {
                float y=h*i/4f;
                c.drawLine(0,y,w,y,grid);
            }
            if(values.size()<2) return;
            float min=35f,max=80f;
            Path p=new Path();
            int i=0, n=values.size();
            for(float v:values) {
                float x=n<=1?0:(w-12f)*i/(n-1f)+6f;
                float cl=Math.max(min,Math.min(max,v));
                float y=h-10f-(cl-min)/(max-min)*(h-20f);
                if(i==0)p.moveTo(x,y); else p.lineTo(x,y);
                i++;
            }
            c.drawPath(p,line);
        }
    }
}

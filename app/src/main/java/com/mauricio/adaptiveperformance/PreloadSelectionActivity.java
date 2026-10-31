package com.mauricio.adaptiveperformance;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * User-selected APK page-cache prewarming. It never pins processes in RAM
 * or changes kernel/OOM scores; the OS may reclaim cached pages at any time.
 */
public final class PreloadSelectionActivity extends Activity {
    private static final int BG = Color.rgb(6, 16, 25);
    private static final int CARD = Color.rgb(15, 37, 52);
    private static final int TEXT = Color.rgb(239, 247, 255);
    private static final int MUTED = Color.rgb(156, 185, 204);
    private static final int ACCENT = Color.rgb(49, 190, 255);
    private static final int PAGE_SIZE = 30;

    private final List<Item> apps = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, Integer> quotas = new HashMap<>();
    private SharedPreferences prefs;
    private LinearLayout list;
    private TextView summary;
    private EditText search;
    private Switch manualSwitch;
    private int limit;
    private int visibleLimit = PAGE_SIZE;
    private boolean loaded;

    private static final class Item {
        final String pkg;
        final String label;
        Item(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String pt, String en, int sp, int color) {
        TextView result = new TextView(this);
        result.setText(UiLanguage.english(prefs) ? en : pt);
        result.setTextSize(sp);
        result.setTextColor(color);
        return result;
    }

    private void summary() {
        String label = selected.size() + (UiLanguage.english(prefs)
                ? " selected • up to " : " selecionados • até ") + limit
                + (UiLanguage.english(prefs) ? " warmed per cycle" : " pré-carregados por ciclo");
        summary.setText(label);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("adaptive", MODE_PRIVATE);
        selected.addAll(prefs.getStringSet("auto_preload_manual_apps", Collections.emptySet()));
        limit = Math.max(1, Math.min(PreloadAllocationPolicy.MAX_APPS,
                prefs.getInt("auto_preload_manual_count", 2)));
        for (String pkg : selected) {
            if (pkg != null && pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
                quotas.put(pkg, prefs.getInt("auto_preload_quota_mb_" + pkg, 32));
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(16), dp(16), dp(16), dp(16));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(16), dp(16) + insets.getSystemWindowInsetTop(),
                    dp(16), dp(16) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = text("Cache de aplicativos", "App cache configuration", 23, TEXT);
        title.setOnClickListener(v -> finish());
        page.addView(title);
        TextView note = text(
                "Selecione os apps, quantos pré-carregar e a cota de leitura em MB de cada um. " +
                "Isto aquece arquivos no cache recuperável do Android, NÃO reserva RAM nem limita " +
                "a memória do processo. O sistema pode liberar o cache. Há pausas por bateria, " +
                "temperatura e RAM baixa.",
                "Select apps, how many to warm and each app's read budget in MB. " +
                "This warms Android's reclaimable file cache; it does NOT reserve RAM or limit " +
                "a process's memory. Android may reclaim the cache. Battery, thermal and low-RAM guards apply.",
                13, MUTED);
        note.setPadding(0, dp(10), 0, dp(14));
        page.addView(note);

        manualSwitch = new Switch(this);
        manualSwitch.setText(UiLanguage.english(prefs)
                ? "Use selected apps instead of recently used apps"
                : "Usar meus aplicativos em vez dos recentes");
        manualSwitch.setTextColor(TEXT);
        manualSwitch.setChecked(prefs.getBoolean("auto_preload_manual_mode", false));
        page.addView(manualSwitch);

        TextView countLabel = text("Máximo por ciclo: " + limit,
                "Max apps per cycle: " + limit, 14, ACCENT);
        countLabel.setPadding(0, dp(12), 0, 0);
        page.addView(countLabel);
        SeekBar count = new SeekBar(this);
        count.setMax(PreloadAllocationPolicy.MAX_APPS - 1);
        count.setProgress(limit - 1);
        count.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                limit = progress + 1;
                countLabel.setText((UiLanguage.english(prefs) ? "Max apps per cycle: "
                        : "Máximo por ciclo: ") + limit);
                summary();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        page.addView(count);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setHint(UiLanguage.english(prefs) ? "Search apps" : "Buscar aplicativos");
        page.addView(search);
        summary = text("Carregando aplicativos…", "Loading apps…", 13, MUTED);
        summary.setPadding(0, dp(7), 0, dp(7));
        page.addView(summary);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        Button save = new Button(this);
        save.setAllCaps(false);
        save.setText(UiLanguage.english(prefs) ? "Save cache settings" : "Salvar configurações");
        save.setEnabled(false);
        page.addView(save);
        Button back = new Button(this);
        back.setAllCaps(false);
        back.setText(UiLanguage.english(prefs) ? "Back" : "Voltar");
        back.setOnClickListener(v -> finish());
        page.addView(back);
        setContentView(page);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                visibleLimit = PAGE_SIZE;
                if (loaded) rebuild();
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        save.setOnClickListener(v -> saveSelection());

        new Thread(() -> {
            List<Item> found = new ArrayList<>();
            try {
                PackageManager pm = getPackageManager();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    try {
                        String pkg = ai.packageName;
                        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                                || !AppSafety.isEligibleForAdaptiveOptimization(this, pkg)
                                || !AppSafety.isUserFacing(this, pkg)) continue;
                        String label = AppSafety.label(this, pkg);
                        found.add(new Item(pkg, label));
                    } catch (RuntimeException ignored) {}
                }
                found.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            } catch (RuntimeException ignored) {}
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                apps.clear();
                apps.addAll(found);
                loaded = !apps.isEmpty();
                save.setEnabled(loaded);
                if (loaded) rebuild();
                else summary.setText(UiLanguage.english(prefs)
                        ? "No eligible apps found" : "Nenhum aplicativo elegível encontrado");
            });
        }, "AP-preload-app-loader").start();
    }

    private void rebuild() {
        list.removeAllViews();
        List<Item> sorted = new ArrayList<>(apps);
        sorted.sort((a, b) -> {
            if (selected.contains(a.pkg) != selected.contains(b.pkg))
                return selected.contains(a.pkg) ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        String term = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0, matches = 0;
        for (Item item : sorted) {
            if (!(item.label + " " + item.pkg).toLowerCase(Locale.ROOT).contains(term)) continue;
            matches++;
            if (shown++ >= visibleLimit) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackgroundColor(CARD);

            CheckBox checked = new CheckBox(this);
            checked.setText(item.label + "\n" + item.pkg);
            checked.setTextColor(TEXT);
            checked.setChecked(selected.contains(item.pkg));
            row.addView(checked);

            LinearLayout quota = new LinearLayout(this);
            quota.setOrientation(LinearLayout.VERTICAL);
            int initial = Math.max(8, Math.min(128,
                    quotas.getOrDefault(item.pkg, prefs.getInt("auto_preload_quota_mb_" + item.pkg, 32))));
            TextView value = text("Cota de pré-carga: " + initial + " MB",
                    "Preload budget: " + initial + " MB", 13, ACCENT);
            quota.addView(value);
            SeekBar amount = new SeekBar(this);
            amount.setMax(15);
            amount.setProgress((initial / 8) - 1);
            amount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    int mb = (progress + 1) * 8;
                    quotas.put(item.pkg, mb);
                    value.setText((UiLanguage.english(prefs) ? "Preload budget: "
                            : "Cota de pré-carga: ") + mb + " MB");
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            quota.addView(amount);
            quota.setVisibility(selected.contains(item.pkg) ? View.VISIBLE : View.GONE);
            row.addView(quota);
            checked.setOnCheckedChangeListener((view, isChecked) -> {
                if (isChecked) selected.add(item.pkg);
                else selected.remove(item.pkg);
                quota.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                summary();
            });

            LinearLayout.LayoutParams margins = new LinearLayout.LayoutParams(-1, -2);
            margins.setMargins(0, 0, 0, dp(6));
            list.addView(row, margins);
        }
        if (matches > visibleLimit) {
            Button more = new Button(this);
            more.setAllCaps(false);
            more.setText((UiLanguage.english(prefs) ? "Show more (" : "Mostrar mais (")
                    + (matches - visibleLimit) + ")");
            more.setOnClickListener(v -> {
                visibleLimit += PAGE_SIZE;
                rebuild();
            });
            list.addView(more);
        }
        summary();
    }

    private void saveSelection() {
        if (!loaded) return;
        Set<String> valid = new LinkedHashSet<>();
        Set<String> installed = new HashSet<>();
        for (Item item : apps) installed.add(item.pkg);
        SharedPreferences.Editor edit = prefs.edit();
        for (String pkg : selected) {
            if (!installed.contains(pkg)) continue;
            valid.add(pkg);
            int mb = Math.max(8, Math.min(128, quotas.getOrDefault(pkg,
                    prefs.getInt("auto_preload_quota_mb_" + pkg, 32))));
            edit.putInt("auto_preload_quota_mb_" + pkg, mb);
        }
        boolean manual = manualSwitch.isChecked() && !valid.isEmpty();
        edit.putStringSet("auto_preload_manual_apps", valid)
                .putInt("auto_preload_manual_count", limit)
                .putBoolean("auto_preload_manual_mode", manual)
                .putLong("auto_preload_cycle_at", 0L)
                .putString("auto_preload_status", "Configuração de cache atualizada");
        if (manual) edit.putBoolean("auto_preload_enabled", true);
        edit.apply();
        Toast.makeText(this, UiLanguage.english(prefs) ? "Settings saved" :
                "Configurações salvas", Toast.LENGTH_SHORT).show();
        finish();
    }
}

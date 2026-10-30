package com.mauricio.adaptiveperformance;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import java.util.*;

/** Advisory only: correlated thermal and per-process CPU observations do not prove causality. */
public final class ThermalAppAdvisor {
    static final String CHANNEL = "adaptive_heat_advisor";
    static final int NOTIFICATION_ID = 8156;
    static final String ACTION_FREEZE = "com.mauricio.adaptiveperformance.HEAT_FREEZE";
    static final String ACTION_IGNORE = "com.mauricio.adaptiveperformance.HEAT_IGNORE";
    static final String EXTRA_PKG = "pkg";
    static final String EXTRA_TOKEN = "token";
    private static final long PROBE_INTERVAL_MS = 120_000L;
    private static final long WINDOW_MS = 12 * 60_000L;
    private static final long ALERT_TTL_MS = 30 * 60_000L;
    private static final long GLOBAL_COOLDOWN_MS = 60 * 60_000L;
    private static final long PER_APP_COOLDOWN_MS = 4 * 60 * 60_000L;

    private ThermalAppAdvisor() {}

    public static void observe(Context context, SharedPreferences prefs, IPrivilegedService shell,
                               boolean interactive, float socC, float batteryC, boolean charging) {
        if (!prefs.getBoolean("app_heat_suggestions_enabled", true) || !interactive || shell == null) return;
        long now = System.currentTimeMillis();
        String pending = prefs.getString("heat_prompt_pkg", "");
        long at = prefs.getLong("heat_prompt_at", 0L);
        if (!pending.isEmpty()) {
            if (now >= at && now - at < ALERT_TTL_MS) return;
            clearPending(prefs);
        }
        boolean hot = (socC >= 59.0f && socC < 115.0f)
                || (batteryC >= 40.0f && batteryC < 65.0f);
        if (!hot) {
            prefs.edit().putString("heat_candidate_pkg", "").putInt("heat_candidate_samples", 0).apply();
            return;
        }
        long lastProbe = prefs.getLong("heat_last_probe_at", 0L);
        if (lastProbe > 0L && now >= lastProbe && now - lastProbe < PROBE_INTERVAL_MS) return;
        prefs.edit().putLong("heat_last_probe_at", now).apply();
        ProcessSample candidate = findCandidate(context, prefs, shell);
        if (candidate == null) {
            prefs.edit().putString("heat_candidate_pkg", "").putInt("heat_candidate_samples", 0)
                    .putString("heat_advisor_status", "Aquecimento observado; nenhum app com CPU alta confirmado").apply();
            return;
        }
        String lastPkg = prefs.getString("heat_candidate_pkg", "");
        long lastAt = prefs.getLong("heat_candidate_at", 0L);
        int samples = candidate.pkg.equals(lastPkg) && now >= lastAt && now - lastAt < WINDOW_MS
                ? prefs.getInt("heat_candidate_samples", 0) + 1 : 1;
        prefs.edit().putString("heat_candidate_pkg", candidate.pkg)
                .putLong("heat_candidate_at", now).putInt("heat_candidate_samples", samples)
                .putString("heat_advisor_status", "Suspeito: " + AppSafety.label(context, candidate.pkg)
                        + " • CPU " + Math.round(candidate.cpu) + "% de um núcleo • "
                        + samples + "/2 leituras; associação térmica não comprovada").apply();
        if (samples < 2) return;
        if (now - prefs.getLong("heat_last_alert_at", 0L) < GLOBAL_COOLDOWN_MS
                || now - prefs.getLong("heat_alert_" + candidate.pkg, 0L) < PER_APP_COOLDOWN_MS) return;
        suggest(context, prefs, candidate, socC, batteryC, charging, now);
    }

    /** Second top frame avoids the first frame's process-lifetime average on Android. */
    private static ProcessSample findCandidate(Context context, SharedPreferences prefs, IPrivilegedService shell) {
        try {
            String output = shell.exec("top -b -n 2 -d 1 -m 30 2>/dev/null | tail -n 40");
            if (output == null || output.contains("[timeout]")) return null;
            String[] lines = output.split("\\r?\\n");
            int header = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("PID") && lines[i].contains("%CPU") && lines[i].contains("ARGS")) header = i;
            }
            if (header < 0) return null;
            Map<String, ProcessSample> byApp = new HashMap<>();
            for (int i = header + 1; i < lines.length; i++) {
                ProcessSample p = parseTopLine(lines[i]);
                if (p == null || !safeCandidate(context, prefs, p.pkg)) continue;
                ProcessSample agg = byApp.get(p.pkg);
                if (agg == null) byApp.put(p.pkg, p);
                else { agg.cpu += p.cpu; agg.rssMb += p.rssMb; }
            }
            ProcessSample result = null;
            for (ProcessSample p : byApp.values()) {
                if (!(p.cpu >= 25.0 || (p.cpu >= 15.0 && p.rssMb >= 600.0))) continue;
                if (result == null || p.cpu > result.cpu) result = p;
            }
            return result;
        } catch (Exception ignored) { return null; }
    }

    static final class ProcessSample {
        final String pkg;
        double cpu;
        double rssMb;
        ProcessSample(String pkg, double cpu, double rssMb) {
            this.pkg = pkg; this.cpu = cpu; this.rssMb = rssMb;
        }
    }

    static ProcessSample parseTopLine(String line) {
        try {
            String[] a = line.trim().split("\\s+", 12);
            if (a.length < 12 || !a[0].matches("[0-9]+")) return null;
            double cpu = Double.parseDouble(a[8]);
            if (cpu < 0 || cpu > 1600) return null;
            String command = a[11].trim().split("\\s+", 2)[0];
            String pkg = command.split(":", 2)[0];
            if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return null;
            String res = a[5].toUpperCase(Locale.ROOT);
            double rss = Double.parseDouble(res.replaceAll("[KMG]$", ""));
            if (res.endsWith("K")) rss /= 1024.0;
            else if (res.endsWith("G")) rss *= 1024.0;
            return new ProcessSample(pkg, cpu, rss);
        } catch (Exception ignored) { return null; }
    }

    static boolean safeCandidate(Context c, SharedPreferences prefs, String pkg) {
        return pkg != null && !AppSafety.isCritical(c, pkg)
                && !AppSafety.isSystemApp(c, pkg)
                && AppSafety.isEligibleForManualFreeze(c, pkg)
                && AppSafety.isUserFacing(c, pkg)
                && !AppSafety.isNeverFreeze(c, pkg)
                && !AppSafety.isAutoProtected(c, pkg)
                && !prefs.getStringSet("manual_freeze_selected", Collections.emptySet()).contains(pkg);
    }

    private static void suggest(Context c, SharedPreferences prefs, ProcessSample p,
                                float soc, float battery, boolean charging, long now) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putString("heat_advisor_status", "Ative a permissão de notificações para receber perguntas sobre congelamento").apply();
            return;
        }
        if (!nm.areNotificationsEnabled()) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Sugestões de aquecimento", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Pergunta antes de congelar apps suspeitos; nunca executa sem sua confirmação");
        nm.createNotificationChannel(channel);
        long token = SystemClock.elapsedRealtimeNanos();
        prefs.edit().putString("heat_prompt_pkg", p.pkg).putLong("heat_prompt_at", now)
                .putLong("heat_prompt_token", token).putLong("heat_last_alert_at", now)
                .putLong("heat_alert_" + p.pkg, now).apply();
        String name = AppSafety.label(c, p.pkg);
        String details = name + " usa ~" + Math.round(p.cpu) + "% de um núcleo (2 leituras). "
                + (soc > 0 ? "SoC " + Math.round(soc) + "°C. " : "Bateria " + Math.round(battery) + "°C. ")
                + (charging ? "Carregamento também pode aquecer. " : "")
                + "É apenas um indício; deseja fechar e restringir esse app?";
        Intent view = new Intent(c, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(c, 8156, view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("Possível app sobrecarregando o celular")
                .setContentText(details)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setAutoCancel(false).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Congelar agora", action(c, ACTION_FREEZE, p.pkg, token, 8157)).build())
                .addAction(new Notification.Action.Builder(null, "Ignorar", action(c, ACTION_IGNORE, p.pkg, token, 8158)).build())
                .build();
        nm.notify(NOTIFICATION_ID, n);
        prefs.edit().putString("heat_advisor_status", "Pergunta enviada: " + name + " • aguardando sua decisão").apply();
    }

    private static PendingIntent action(Context c, String act, String pkg, long token, int requestCode) {
        Intent intent = new Intent(c, ThermalDecisionReceiver.class)
                .setAction(act).putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_TOKEN, token);
        return PendingIntent.getBroadcast(c, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static synchronized boolean consume(Context c, SharedPreferences prefs, String pkg, long token, boolean approve) {
        long now = System.currentTimeMillis();
        if (pkg == null || pkg.isEmpty() || token == 0 || token != prefs.getLong("heat_prompt_token", 0)
                || !pkg.equals(prefs.getString("heat_prompt_pkg", ""))) return false;
        long at = prefs.getLong("heat_prompt_at", 0);
        if (at <= 0 || now < at || now - at > ALERT_TTL_MS) { clearPending(prefs); return false; }
        if (approve && !safeCandidate(c, prefs, pkg)) { clearPending(prefs); return false; }
        clearPending(prefs);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        prefs.edit().putString("heat_advisor_status", approve
                ? "Congelamento autorizado: " + AppSafety.label(c, pkg)
                : "Sugestão ignorada: " + AppSafety.label(c, pkg)).apply();
        return true;
    }

    static void clearPending(SharedPreferences prefs) {
        prefs.edit().remove("heat_prompt_pkg").remove("heat_prompt_at").remove("heat_prompt_token").apply();
    }
}

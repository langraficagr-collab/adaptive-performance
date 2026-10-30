package com.mauricio.adaptiveperformance;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import java.util.Collections;

/** Two separate sustained-growth incidents are needed before a consent prompt. */
public final class MemoryLeakFreezeAdvisor {
    public static final String CHANNEL = "adaptive_memory_leak_advisor";
    public static final String ACTION_APPROVE = "com.mauricio.adaptiveperformance.LEAK_FREEZE_APPROVE";
    public static final String ACTION_IGNORE = "com.mauricio.adaptiveperformance.LEAK_FREEZE_IGNORE";
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_TOKEN = "token";
    public static final int NOTIFICATION_ID = 8186;
    private static final long ASK_INTERVAL = 12L * 60L * 60_000L;
    private static final long PROMPT_TTL = 45L * 60_000L;

    private MemoryLeakFreezeAdvisor() { }

    public static boolean safeCandidate(Context context, SharedPreferences prefs, String pkg) {
        return pkg != null && pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
                && !AppSafety.isCritical(context, pkg) && !AppSafety.isSystemApp(context, pkg)
                && !AppSafety.isAutoProtected(context, pkg) && !AppSafety.isNeverFreeze(context, pkg)
                && AppSafety.isUserFacing(context, pkg)
                && AppSafety.isEligibleForManualFreeze(context, pkg)
                && !prefs.getStringSet("manual_freeze_selected", Collections.emptySet()).contains(pkg);
    }

    /** Called only after a new 20-minute PSS growth trend, never on every diagnostic poll. */
    public static synchronized void confirmedEpisode(Context c, SharedPreferences p, String pkg,
                                                     float growthMb, float spanMinutes) {
        if (!p.getBoolean("leak_freeze_suggestions_enabled", true)
                || !safeCandidate(c, p, pkg)) return;
        long now = System.currentTimeMillis();
        String key = Integer.toHexString(pkg.hashCode());
        long last = p.getLong("leak_episode_at_" + key, 0L);
        int count = MemoryLeakEpisodeCounter.count(
                p.getInt("leak_episode_count_" + key, 0), last, now);
        if (count < 0) return;
        p.edit().putInt("leak_episode_count_" + key, count)
                .putLong("leak_episode_at_" + key, now)
                .putString("leak_advisor_status", AppSafety.label(c, pkg) + ": " + count
                        + "/2 tendências distintas de RAM observadas").apply();
        if (count < 2 || now - p.getLong("leak_asked_at_" + key, 0L) < ASK_INTERVAL) return;
        String current = p.getString("leak_prompt_pkg", "");
        if (!current.isEmpty() && now - p.getLong("leak_prompt_at", 0L) < PROMPT_TTL) return;
        long token = SystemClock.elapsedRealtimeNanos();
        if (!p.edit().putString("leak_prompt_pkg", pkg).putLong("leak_prompt_at", now)
                .putLong("leak_prompt_token", token).putLong("leak_asked_at_" + key, now)
                .putString("leak_advisor_status", "Aguardando autorização para congelar " + AppSafety.label(c, pkg))
                .commit()) return;
        IncidentHistory.add(p, "memory_leak", pkg,
                "Segunda tendência independente: +" + Math.round(growthMb) + " MB de PSS em "
                        + Math.round(spanMinutes) + " minutos; congelamento solicitado", -1);
        notifyUser(c, p, pkg, growthMb, spanMinutes, token);
    }

    public static synchronized boolean isPending(SharedPreferences p) {
        return !p.getString("leak_prompt_pkg", "").isEmpty()
                && System.currentTimeMillis() - p.getLong("leak_prompt_at", 0L) < PROMPT_TTL;
    }

    public static synchronized boolean consume(Context c, SharedPreferences p, String pkg,
                                               long token, boolean approved) {
        long now = System.currentTimeMillis();
        long at = p.getLong("leak_prompt_at", 0L);
        if (pkg == null || !pkg.equals(p.getString("leak_prompt_pkg", ""))
                || token == 0 || token != p.getLong("leak_prompt_token", 0L)
                || at <= 0 || now < at || now - at > PROMPT_TTL) return false;
        if (approved && !safeCandidate(c, p, pkg)) return false;
        p.edit().remove("leak_prompt_pkg").remove("leak_prompt_at")
                .remove("leak_prompt_token")
                .putString("leak_advisor_status", approved ? "Congelamento autorizado para " + AppSafety.label(c, pkg)
                        : "Sugestão ignorada para " + AppSafety.label(c, pkg)).commit();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        return true;
    }

    private static PendingIntent action(Context c, String action, String pkg, long token, int requestCode) {
        Intent intent = new Intent(c, MemoryLeakDecisionReceiver.class).setAction(action)
                .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_TOKEN, token);
        return PendingIntent.getBroadcast(c, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notifyUser(Context c, SharedPreferences p, String pkg,
                                   float growthMb, float span, long token) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null || !nm.areNotificationsEnabled()
                    || (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
                p.edit().putString("leak_advisor_delivery", "Ative as notificações; pergunta disponível no aplicativo").apply();
                return;
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Sugestões por vazamento de memória", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Solicita autorização após duas tendências distintas de crescimento de RAM");
            nm.createNotificationChannel(channel);
            if (nm.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) {
                p.edit().putString("leak_advisor_delivery", "Canal bloqueado; pergunta disponível no aplicativo").apply();
                return;
            }
            String label = AppSafety.label(c, pkg);
            String body = "" + label + " apresentou 2 tendências distintas de crescimento de RAM (última: +"
                    + Math.round(growthMb) + " MB em " + Math.round(span) + " min). "
                    + "Pode haver outras causas. Adicionar ao congelamento manual quando sair do app?";
            PendingIntent launch = PendingIntent.getActivity(c, NOTIFICATION_ID,
                    new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_app)
                    .setContentTitle("Possível vazamento de RAM recorrente")
                    .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(false).setContentIntent(launch)
                    .addAction(new Notification.Action.Builder(null, "Congelar ao sair",
                            action(c, ACTION_APPROVE, pkg, token, NOTIFICATION_ID + 1)).build())
                    .addAction(new Notification.Action.Builder(null, "Ignorar",
                            action(c, ACTION_IGNORE, pkg, token, NOTIFICATION_ID + 2)).build())
                    .build();
            nm.notify(NOTIFICATION_ID, n);
            p.edit().putString("leak_advisor_delivery", "Pergunta enviada por notificação").apply();
        } catch (Throwable e) {
            p.edit().putString("leak_advisor_delivery", "Pergunta disponível no aplicativo: "
                    + e.getClass().getSimpleName()).apply();
        }
    }
}

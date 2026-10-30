package com.mauricio.adaptiveperformance;

import android.app.*;
import android.content.*;
import android.os.Build;

/** In-app history always records changes; OS notifications respect the user's channel block. */
public final class ChangeNotifier {
    public static final String CHANNEL = "adaptive_changes";
    private ChangeNotifier() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    "Mudanças do Adaptive Performance", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Mudanças térmicas, restrições, congelamentos e manutenção");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    public static void notifyChange(Context context, String title, String body, int categoryId) {
        SharedPreferences p = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE);
        RecentActivityStore.add(p, "change", title, body);
        p.edit().putString("last_change_title", title)
                .putString("last_change_text", body).putInt("last_change_category", categoryId)
                .putLong("last_change_at", System.currentTimeMillis()).apply();
        post(context, p, title, body, categoryId, false);
    }

    public static void notifyUnresolved(Context context, String title, String body, int categoryId) {
        SharedPreferences p = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE);
        RecentActivityStore.add(p, "alert", title, body);
        post(context, p, title, body, categoryId, true);
    }

    public static String deliveryStatus(Context context, SharedPreferences p) {
        if (!p.getBoolean("notify_changes", true)) return "Avisos desligados no aplicativo";
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return "Serviço de notificações indisponível";
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "Permissão de notificações não concedida";
        if (!manager.areNotificationsEnabled()) return "Notificações do aplicativo bloqueadas no Android";
        if (Build.VERSION.SDK_INT >= 26) {
            ensureChannel(context);
            NotificationChannel ch = manager.getNotificationChannel(CHANNEL);
            if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE)
                return "Canal Mudanças bloqueado nas configurações do Android";
        }
        return "Alertas de mudanças habilitados";
    }

    private static void post(Context context, SharedPreferences prefs, String title,
                             String body, int categoryId, boolean unresolved) {
        if (!prefs.getBoolean("notify_changes", true)) return;
        String status = deliveryStatus(context, prefs);
        prefs.edit().putString("notification_delivery_status", status).apply();
        if (!"Alertas de mudanças habilitados".equals(status)) return;
        long now = System.currentTimeMillis();
        // Prevent thermal-state loops and recurring maintenance scans from spamming the user.
        long minGap = unresolved ? 60_000L : 300_000L;
        String key = "notification_sent_at_" + categoryId;
        if (now - prefs.getLong(key, 0L) < minGap) return;
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, categoryId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_app).setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true).setContentIntent(pi).setCategory(Notification.CATEGORY_STATUS);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        try {
            if (manager != null) {
                manager.notify(8100 + categoryId, builder.build());
                prefs.edit().putLong(key, now).putLong("notification_last_post_at", now).apply();
            }
        } catch (SecurityException e) {
            prefs.edit().putString("notification_delivery_status", "Permissão negada pelo Android").apply();
        }
    }
}

package com.mauricio.adaptiveperformance;

import android.app.*;
import android.content.*;
import android.os.Build;

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

    public static void notifyChange(Context context, String title, String text, int categoryId) {
        // Automatic corrections and normal state changes stay silent.
        // Keep the latest event for the in-app history/status instead of notifying the user.
        SharedPreferences prefs = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("last_change_title", title)
                .putString("last_change_text", text)
                .putInt("last_change_category", categoryId)
                .putLong("last_change_at", System.currentTimeMillis())
                .apply();
    }

    public static void notifyUnresolved(Context context, String title, String text, int categoryId) {
        SharedPreferences prefs = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("notify_changes", true)) return;
        ensureChannel(context);
        Intent i = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, categoryId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_app)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_STATUS);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(8100 + categoryId, b.build());
    }
}
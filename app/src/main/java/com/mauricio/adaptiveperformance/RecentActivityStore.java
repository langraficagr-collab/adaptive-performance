package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;

/** Compact, always-on record of confirmed actions. Independent of optional ML/scoring. */
public final class RecentActivityStore {
    private static final String KEY = "recent_activity_v2";
    private RecentActivityStore() { }

    public static synchronized void add(SharedPreferences p, String source, String title, String detail) {
        String content = ((title == null ? "" : title) + (detail == null || detail.isEmpty() ? "" : ": " + detail))
                .replace('\n', ' ').replace('\r', ' ').trim();
        if (content.isEmpty()) return;
        if (content.length() > 240) content = content.substring(0, 240);
        String lastSignature = p.getString("recent_activity_signature", "");
        long now = System.currentTimeMillis();
        if (content.equals(lastSignature) && now - p.getLong("recent_activity_signature_at", 0) < 240_000L) return;
        String row = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(now)) + " — " + content;
        String older = p.getString(KEY, "");
        StringBuilder feed = new StringBuilder(row);
        int count = 1;
        for (String previous : older.split("\\n")) {
            if (previous.isEmpty() || count >= 60 || feed.length() + previous.length() + 1 > 15000) break;
            feed.append('\n').append(previous);
            count++;
        }
        p.edit().putString(KEY, feed.toString())
                .putLong("recent_activity_updated_at", now)
                .putString("recent_activity_signature", content)
                .putLong("recent_activity_signature_at", now).apply();
    }

    public static String history(SharedPreferences p) {
        String result = p.getString(KEY, "");
        if (!result.isEmpty()) return result;
        return p.getString("maintenance_log", "Nenhuma atividade recente.");
    }

    public static synchronized void migrate(SharedPreferences p) {
        if (p.getBoolean("recent_activity_v2_migrated", false)) return;
        String old = p.getString("maintenance_log", "");
        if (!old.isEmpty()) {
            String[] rows = old.split("\\n");
            StringBuilder feed = new StringBuilder();
            for (int i=0; i<Math.min(rows.length, 5); i++) {
                if (feed.length()>0) feed.append('\n');
                feed.append("Anterior — ").append(rows[i]);
            }
            p.edit().putString(KEY, feed.toString()).apply();
        }
        String title = p.getString("last_change_title", "");
        if (!title.isEmpty()) add(p, "previous", title, p.getString("last_change_text", ""));
        p.edit().putBoolean("recent_activity_v2_migrated", true).apply();
    }
}

package com.mauricio.adaptiveperformance;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;
public final class IncidentHistory {
    private IncidentHistory() {}
    public static synchronized void add(SharedPreferences p, String cause, String action, String result, int score) {
        if (!p.getBoolean("action_effectiveness", true)) return;
        String row = DateFormat.getDateTimeInstance().format(new Date()) + " | " + cause + " | " + action + " | " + result + " | score=" + score;
        row = row.replace('\n', ' ');
        if (row.length() > 1500) row = row.substring(0, 1500);
        StringBuilder b = new StringBuilder(row);
        int n = 1;
        for (String old : p.getString("incident_history", "").split("\n")) {
            if (old.isEmpty() || n >= 100 || (b.length() + old.length()) * 3 > 32000) break;
            b.append('\n').append(old); n++;
        }
        p.edit().putString("incident_history", b.toString()).apply();
    }
}

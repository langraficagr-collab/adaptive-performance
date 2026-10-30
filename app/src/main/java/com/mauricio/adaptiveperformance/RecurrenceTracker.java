package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;

/** Count sustained/repeated observations no more than once every five minutes. */
final class RecurrenceTracker {
    static int record(SharedPreferences p, SharedPreferences.Editor e, String cause,
                      boolean abnormal, long now) {
        if (!p.getBoolean("recurrence_guard", true)) return 0;
        long start = p.getLong("recurrence_window_start", 0L);
        long last = p.getLong("recurrence_last_seen", 0L);
        boolean expired = start <= 0 || now < start || now < last
                || now - start > 6L * 60L * 60L * 1000L;
        int count = p.getInt("recurrence_count", 0);
        if (!abnormal) {
            if (expired) e.putInt("recurrence_count", 0).remove("recurrence_cause")
                    .remove("recurrence_window_start").remove("recurrence_last_seen");
            return expired ? 0 : count;
        }
        if (expired || !cause.equals(p.getString("recurrence_cause", ""))) {
            count = 1;
            e.putLong("recurrence_window_start", now);
        } else if (now - last >= 300_000L) count = Math.min(20, count + 1);
        else return count; // Do not move the counting timestamp on every poll.
        e.putString("recurrence_cause", cause).putLong("recurrence_last_seen", now)
                .putInt("recurrence_count", count);
        return count;
    }
}

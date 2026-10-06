package com.mauricio.adaptiveperformance;

import android.app.NotificationManager;
import android.content.*;

public class CauseDecisionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!CauseResolutionNotifier.ACTION_KEEP.equals(intent.getAction())) return;

        String causeId = intent.getStringExtra(CauseResolutionNotifier.EXTRA_CAUSE_ID);
        if (causeId == null) causeId = "";
        SharedPreferences prefs = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("cause_kept_id", causeId)
                .putLong("cause_kept_at", System.currentTimeMillis())
                .putString("last_cause_decision", "keep")
                .putLong("last_cause_decision_at", System.currentTimeMillis())
                .apply();

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(CauseResolutionNotifier.NOTIFICATION_ID);
            nm.cancel(CauseResolutionNotifier.RESULT_NOTIFICATION_ID);
        }
    }
}

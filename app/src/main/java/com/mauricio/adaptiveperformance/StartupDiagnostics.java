package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;
import java.util.*;

public final class StartupDiagnostics {
    private StartupDiagnostics() {}
    public static void record(SharedPreferences p) {
        RestrictionGuard.clean(p);
        if (!p.getBoolean("startup_diagnostics", true)) return;
        int invalid = 0;
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if ((e.getKey().startsWith("cpu_prev_") || e.getKey().startsWith("persistent_wakelock_previous_level_"))
                    && (!(e.getValue() instanceof String) || !RestrictionGuard.level((String)e.getValue()))) invalid++;
        }
        int pending = p.getStringSet("cpu_limited_set", Collections.emptySet()).size()
                + p.getStringSet("manual_frozen_active", Collections.emptySet()).size();
        String summary = "Shizuku=" + p.getBoolean("shizuku_bound", false)
                + "; serviço=" + p.getBoolean("service_running", false)
                + "; monitor=" + p.getBoolean("master", false)
                + "; recuperação pendente=" + pending + "; níveis inválidos preservados=" + invalid;
        p.edit().putString("startup_diagnostic", summary).putLong("startup_diagnostic_at", System.currentTimeMillis()).apply();
        IncidentHistory.add(p, "startup", "Auditoria e recuperação de estados temporários", summary, -1);
    }
}

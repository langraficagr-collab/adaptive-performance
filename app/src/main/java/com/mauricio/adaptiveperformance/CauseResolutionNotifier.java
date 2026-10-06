package com.mauricio.adaptiveperformance;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.util.Locale;

public final class CauseResolutionNotifier {
    public static final String CHANNEL = "adaptive_decisions";
    public static final int NOTIFICATION_ID = 8220;
    public static final int RESULT_NOTIFICATION_ID = 8221;

    public static final String ACTION_KEEP = "com.mauricio.adaptiveperformance.KEEP_CAUSE";
    public static final String EXTRA_CAUSE_ID = "cause_id";
    public static final String EXTRA_CAUSE_TEXT = "cause_text";
    public static final String EXTRA_SOLUTION_TEXT = "solution_text";

    private static final long SAME_PROMPT_COOLDOWN_MS = 30L * 60L * 1000L;

    private CauseResolutionNotifier() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL,
                    "Decisões do Adaptive Performance",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Causas prováveis detectadas e opções de solução");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    public static void maybeNotify(Context context, SharedPreferences prefs, String cause) {
        if (!prefs.getBoolean("notify_changes", true)) return;
        Cause c = classify(cause);
        NotificationManager nm = context.getSystemService(NotificationManager.class);

        if (c == null) {
            prefs.edit()
                    .remove("active_cause_id")
                    .remove("active_cause_text")
                    .remove("active_cause_solution")
                    .remove("active_cause_autofix")
                    .remove("cause_kept_id")
                    .remove("cause_kept_at")
                    .remove("auto_fix_cause_id")
                    .remove("auto_fix_attempt_at")
                    .apply();
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - prefs.getLong("cause_resolved_at_" + c.id, 0) < 600000L) {
            float ram = prefs.getFloat("ram_free_pct", -1), cpu = prefs.getFloat("cpu_load", -1);
            boolean worse = ram >= 0 && ram < prefs.getFloat("cause_resolved_ram_" + c.id, 0) - 5
                    || cpu > prefs.getFloat("cause_resolved_cpu_" + c.id, 100) + 20
                    || prefs.getFloat("thermal_soc_c", 0) > prefs.getFloat("cause_resolved_temp_" + c.id, 100) + 5
                    || prefs.getFloat("psi_memory", 0) > prefs.getFloat("cause_resolved_psi_" + c.id, 100) + 10;
            if (!worse) return;
        }

        String keptId = prefs.getString("cause_kept_id", "");
        if (c.id.equals(keptId)) return;

        int confidence = prefs.getInt("cause_confidence", 50);
        int recurrence = prefs.getBoolean("recurrence_guard", true) ? prefs.getInt("recurrence_count", 0) : 0;
        int minConfidence = prefs.getBoolean("cause_confidence_guard", true) ? (recurrence >= 2 ? 75 : 60) : 0;
        boolean repairCooldown = now < prefs.getLong("auto_repair_block_until_" + c.id, 0L);
        boolean autoAllowed = c.autoFix && AdaptiveIntelligenceController.mutationAllowed(prefs)
                && confidence >= minConfidence && !repairCooldown;
        if (autoAllowed) {
            if (nm != null) nm.cancel(NOTIFICATION_ID);
            String lastAuto = prefs.getString("auto_fix_cause_id", "");
            long lastAutoAt = prefs.getLong("auto_fix_attempt_at", 0L);
            if (c.id.equals(lastAuto) && now - lastAutoAt < 10L * 60L * 1000L) return;

            prefs.edit()
                    .putString("auto_fix_cause_id", c.id)
                    .putLong("auto_fix_attempt_at", now)
                    .putString("active_cause_id", c.id)
                    .putString("active_cause_text", cause)
                    .putString("active_cause_solution", c.solution)
                    .putBoolean("active_cause_autofix", true)
                    .putString("last_cause_decision", "auto")
                    .putLong("last_cause_decision_at", now)
                    .apply();

            Intent apply = new Intent(context, OptimizationService.class)
                    .setAction(OptimizationService.ACTION_APPLY_CAUSE_FIX)
                    .putExtra(EXTRA_CAUSE_ID, c.id)
                    .putExtra(EXTRA_CAUSE_TEXT, cause)
                    .putExtra(EXTRA_SOLUTION_TEXT, c.solution);
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(apply);
                else context.startService(apply);
            } catch (Throwable t) {
                notifyResult(context, c.id, false,
                        "Detectei uma causa que normalmente consigo corrigir, mas não consegui iniciar a correção automática. " +
                        "Abra os detalhes para decidir o que fazer.", true);
            }
            return;
        }

        String last = prefs.getString("last_cause_prompt_id", "");
        long lastAt = prefs.getLong("last_cause_prompt_at", 0L);
        if (c.id.equals(last) && now - lastAt < SAME_PROMPT_COOLDOWN_MS) return;

        ensureChannel(context);

        Intent detail = new Intent(context, MainActivity.class)
                .putExtra(EXTRA_CAUSE_ID, c.id)
                .putExtra(EXTRA_CAUSE_TEXT, cause)
                .putExtra(EXTRA_SOLUTION_TEXT, c.solution)
                .putExtra("show_cause_dialog", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent detailPi = PendingIntent.getActivity(
                context, requestCode(c.id, 1), detail,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent keep = new Intent(context, CauseDecisionReceiver.class)
                .setAction(ACTION_KEEP)
                .putExtra(EXTRA_CAUSE_ID, c.id)
                .putExtra(EXTRA_CAUSE_TEXT, cause);
        PendingIntent keepPi = PendingIntent.getBroadcast(
                context, requestCode(c.id, 2), keep,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_app)
                .setContentTitle("Causa provável detectada")
                .setContentText(cause)
                .setStyle(new Notification.BigTextStyle().bigText(
                        cause + "\n\nSugestão: " + c.solution))
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentIntent(detailPi);

        b.addAction(new Notification.Action.Builder(
                null, "Ver solução", detailPi).build());
        b.addAction(new Notification.Action.Builder(
                null, "Manter assim", keepPi).build());

        prefs.edit()
                .putString("active_cause_id", c.id)
                .putString("active_cause_text", cause)
                .putString("active_cause_solution", c.solution)
                .putBoolean("active_cause_autofix", autoAllowed)
                .putString("last_cause_prompt_id", c.id)
                .putLong("last_cause_prompt_at", now)
                .apply();

        if (nm != null) nm.notify(NOTIFICATION_ID, b.build());
    }

    public static void notifyResult(Context context, String causeId, boolean success,
                                    String text, boolean canRetry) {
        SharedPreferences prefs = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE);
        AutoRepairController.finish(prefs, causeId, success, text);
        if (success) prefs.edit().putLong("cause_resolved_at_" + causeId, System.currentTimeMillis())
                .putFloat("cause_resolved_ram_" + causeId, prefs.getFloat("ram_free_pct", 0))
                .putFloat("cause_resolved_cpu_" + causeId, prefs.getFloat("cpu_load", 0))
                .putFloat("cause_resolved_temp_" + causeId, prefs.getFloat("thermal_soc_c", 0))
                .putFloat("cause_resolved_psi_" + causeId, prefs.getFloat("psi_memory", 0))
                .remove("active_cause_id").apply();
        prefs.edit()
                .putString("last_cause_solution_id", causeId)
                .putString("last_cause_solution_result", text)
                .putBoolean("last_cause_solution_success", success)
                .putLong("last_cause_solution_at", System.currentTimeMillis())
                .apply();

        ensureChannel(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        String causeText = prefs.getString("active_cause_text", "");
        Intent detail = new Intent(context, MainActivity.class)
                .putExtra(EXTRA_CAUSE_ID, causeId)
                .putExtra(EXTRA_CAUSE_TEXT, causeText)
                .putExtra(EXTRA_SOLUTION_TEXT, text)
                .putExtra("show_cause_dialog", !success)
                .putExtra("cause_failure", !success)
                .putExtra("cause_can_retry", !success && canRetry)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent detailPi = PendingIntent.getActivity(
                context, requestCode(causeId, 8), detail,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent keep = new Intent(context, CauseDecisionReceiver.class)
                .setAction(ACTION_KEEP)
                .putExtra(EXTRA_CAUSE_ID, causeId);
        PendingIntent keepPi = PendingIntent.getBroadcast(
                context, requestCode(causeId, 9), keep,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_app)
                .setContentTitle(success ? "Correção automática aplicada" : "Não consegui resolver automaticamente")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(
                        (causeText.isEmpty() ? "" : "Causa: " + causeText + "\n\n") + text))
                .setAutoCancel(true)
                .setContentIntent(detailPi)
                .setCategory(Notification.CATEGORY_STATUS);

        if (!success) {
            if (canRetry) {
                Intent retry = new Intent(context, OptimizationService.class)
                        .setAction(OptimizationService.ACTION_APPLY_CAUSE_FIX)
                        .putExtra(EXTRA_CAUSE_ID, causeId)
                        .putExtra(EXTRA_CAUSE_TEXT, causeText);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
                PendingIntent retryPi = Build.VERSION.SDK_INT >= 26
                        ? PendingIntent.getForegroundService(context, requestCode(causeId, 10), retry, flags)
                        : PendingIntent.getService(context, requestCode(causeId, 10), retry, flags);
                b.addAction(new Notification.Action.Builder(
                        null, "Tentar novamente", retryPi).build());
            }
            b.addAction(new Notification.Action.Builder(
                    null, "Ver detalhes", detailPi).build());
            b.addAction(new Notification.Action.Builder(
                    null, "Manter assim", keepPi).build());
        }
        // Replace only the decision prompt; successful fixes must retain their result.
        nm.cancel(NOTIFICATION_ID);
        nm.notify(RESULT_NOTIFICATION_ID, b.build());
    }

    public static Cause classify(String cause) {
        if (cause == null) return null;
        String c = cause.trim();
        if (c.isEmpty() ||
                c.equals("Sem causa anormal detectada") ||
                c.startsWith("Módulos avançados em modo seguro")) return null;

        String l = c.toLowerCase(Locale.US);
        if (l.contains("wakelock persistente")) {
            return new Cause("persistent_wakelock",
                    "Restringir reversivelmente o segundo plano do app indicado, somente se elegível, fora de uso e não protegido nem do sistema; salvar o nível anterior sem forçar encerramento nem reduzir uma restrição existente.", true);
        }
        if (l.contains("sincronização pendente")) {
            return new Cause("stuck_sync",
                    "A mesma sincronização está pendente por tempo anormal. Verifique conexão e conta do aplicativo indicado; o Adaptive Performance não cancela uploads/sincronizações válidas automaticamente.", false);
        }
        if (l.contains("sensor em segundo plano")) {
            return new Cause("background_sensor",
                    "Um aplicativo comum manteve sensores ativos em segundo plano em verificações consecutivas. Revise esse app antes de restringi-lo; sensores de segurança e do sistema permanecem protegidos.", false);
        }
        if (l.contains("vazamento de memória")) {
            return new Cause("memory_leak",
                    "Encerrar somente o app elegível indicado em segundo plano e marcá-lo inativo temporariamente, com restauração prevista em 10 minutos; apps em uso, protegidos e do sistema não serão alterados.", true);
        }
        if (l.contains("repouso profundo baixo")) {
            return new Cause("deep_sleep_low",
                    "Com a tela apagada, tentar as políticas reversíveis existentes de deep idle, rádio e Doze, respeitando seus limites e proteções; com a tela ligada, aguardar.", true);
        }
        if (l.contains("aquecimento previsto") && l.contains("carga")) {
            return new Cause("heat_charge",
                    "Aplicar proteção térmica temporária e 60 Hz até a temperatura normalizar.", true);
        }
        if (l.contains("pressão de ram") || l.contains("lmkd") || l.contains("zram")) {
            return new Cause("ram_pressure",
                    "Limitar temporariamente apps seguros em segundo plano; eles serão liberados quando a pressão normalizar.", true);
        }
        if (l.contains("carga elevada de cpu")) {
            return new Cause("cpu_pressure",
                    "Limitar temporariamente apps seguros em segundo plano para aliviar a CPU.", true);
        }
        if (l.contains("armazenamento") || l.contains("i/o") || l.contains("escrita")) {
            return new Cause("io_pressure",
                    "Reduzir temporariamente a atividade de segundo plano para aliviar o armazenamento.", true);
        }
        if (l.contains("renderização") || l.contains("gpu") || l.contains("jank")) {
            return new Cause("gpu_jank",
                    "Usar 60 Hz temporariamente para reduzir a carga de renderização; o modo adaptativo restaura depois.", true);
        }
        if (l.contains("bateria e soc") || l.contains("aquecendo em conjunto")) {
            return new Cause("battery_soc_heat",
                    "Aplicar proteção térmica temporária e reduzir a tela para 60 Hz.", true);
        }
        if (l.contains("modem") || l.contains("sinal celular fraco")) {
            return new Cause("weak_signal",
                    "O app não deve trocar sua rede sozinho. Se possível, use Wi‑Fi ou melhore o sinal; você também pode manter como está.", false);
        }
        if (l.contains("gps") || l.contains("localização")) {
            return new Cause("location",
                    "Revise o app que está usando localização e feche/restrinja-o apenas se o GPS não for necessário.", false);
        }
        if (l.contains("câmera") || l.contains("isp")) {
            return new Cause("camera",
                    "Feche a câmera ou o app que está mantendo o ISP ativo se essa atividade não for necessária.", false);
        }
        if (l.contains("sensor")) {
            return new Cause("sensors",
                    "Revise os apps que mantêm sensores ativos. Não é seguro desligar sensores automaticamente sem saber qual função está em uso.", false);
        }
        if (l.contains("binder")) {
            return new Cause("binder",
                    "Abra os detalhes para identificar o serviço mais lento. O app não encerrará serviços do sistema automaticamente.", false);
        }
        if (l.contains("sobreposição de automações") || l.contains("duplicad")) {
            return new Cause("duplicates",
                    "Revise os processos duplicados antes de encerrá-los. O app não mata automações automaticamente para evitar interromper tarefas válidas.", false);
        }
        return new Cause("generic_" + Integer.toHexString(c.hashCode()),
                "Abra os detalhes para revisar a causa. Se não houver uma correção segura, mantenha o estado atual.", false);
    }

    private static int requestCode(String id, int suffix) {
        return Math.abs((id + ":" + suffix).hashCode());
    }

    public static final class Cause {
        public final String id;
        public final String solution;
        public final boolean autoFix;
        Cause(String id, String solution, boolean autoFix) {
            this.id = id;
            this.solution = solution;
            this.autoFix = autoFix;
        }
    }
}

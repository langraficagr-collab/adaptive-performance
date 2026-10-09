package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;

/**
 * Low-overhead A/B tuner for the conservative edition.
 * It changes one setting at a time and keeps it only after a measurable energy win
 * without a thermal, CPU, memory, or frame-jank regression.
 */
final class ConservativeTuningController {
    private static final String P = "conservative_tune_";
    private static final String[][] OPTIONS = {
        {"advanced_adaptive", "Motor de PSI e energia", ""},
        {"app_profiles", "Perfis por aplicativo", ""},
        {"app_learning", "Aprendizado por aplicativo", ""},
        {"time_usage_learning", "Padrões por horário", ""},
        {"action_effectiveness", "Pontuação de eficácia", ""},
        {"update_regression_detector", "Regressão após atualização", ""},
        {"restart_recurrence_guard", "Detecção de reinícios", ""},
        {"low_battery_adaptive", "Economia com bateria baixa", ""},
        {"cpu_pressure_control", "Limite reversível de segundo plano", ""},
        {"anti_stall", "Prevenção de travamentos", ""},
        {"history_24h", "Histórico de saúde", ""},
        {"thermal_prediction", "Previsão térmica", ""},
        {"extended_diagnostics", "Diagnóstico estendido", ""},
        {"diagnostic_burst", "Diagnóstico temporário", "extended_diagnostics"},
        {"sensor_modem_guard", "Sensores e modem", "extended_diagnostics"},
        {"storage_guard", "Saturação de armazenamento", "extended_diagnostics"},
        {"duplicate_detection", "Serviços duplicados", "extended_diagnostics"},
        {"memory_leak_detector", "Tendência de memória por app", "extended_diagnostics"},
        {"deep_sleep_monitor", "Monitor de repouso", ""},
        {"thermal_brightness_control", "Brilho térmico reversível", ""},
        {"system_battery_guard", "Economia sistêmica", ""},
        {"system_radio_savings", "Economia de rádio", "system_battery_guard"},
        {"dynamic_doze_whitelist", "Exceções de Doze", "system_battery_guard"},
        {"adaptive_screen_timeout", "Tempo de tela", "system_battery_guard"},
        {"maximum_battery_mode", "Dois-toques em repouso", "system_battery_guard"},
        {"notify_changes", "Notificação de problemas", ""},
    };
    private static final int PHASES = OPTIONS.length;
    private static final long SAMPLE_INTERVAL_MS = 30L * 1000L;
    private static final long WINDOW_MS = 2L * 60L * 1000L;
    private static final long MAX_WINDOW_MS = 8L * 60L * 1000L;
    private static final long CYCLE_COOLDOWN_MS = 24L * 60L * 60L * 1000L;
    private static final int MIN_SAMPLES = 3;
    private static final long REVIEW_6H = 21600000L, CONFIRM_24H = 86400000L;
    private static final long PROBATION_INTERVAL = 1800000L;

    private ConservativeTuningController() {}

    static void applySafeDefaults(SharedPreferences.Editor e) {
        e.putBoolean("memory_compaction_enabled", false)
                .putInt("memory_compaction_threshold_pct", 65)
                .putBoolean("app_ram_limiter_enabled", false)
                .putInt("app_ram_limit_mb", 600)
                .putBoolean("adaptive_freeze_enabled", false)
                .putInt("adaptive_freeze_hours", 72)
                .putInt("freeze_delay_minutes", 60)
                .putBoolean("smart_auto_cleanup", false)
                .putInt("smart_cleanup_free_pct", 10)
                .putBoolean("screen_off_optimization", false)
                .putBoolean("wakeup_network_guard", false)
                .putBoolean("system_radio_savings", false)
                .putBoolean("adaptive_refresh", true)
                .putBoolean("auto_preload_enabled", true);
    }

    static void evaluate(SharedPreferences prefs, int batteryPct, float tempC,
                         double cpuLoad, double freeRamPct, boolean interactive,
                         boolean charging, float powerW) {
        long now = System.currentTimeMillis();
        initialize(prefs);
        if (!prefs.getBoolean(P + "initialized", false)) resetSession(prefs);
        if (!prefs.getString(P + "restore_pending", "").isEmpty()) {
            setStatus(prefs, "Aguardando restauração do ajuste anterior");
            return;
        }

        if ("probation".equals(prefs.getString(P + "stage", "baseline"))) {
            reviewProbation(prefs, now, batteryPct, tempC, cpuLoad, freeRamPct, interactive, charging, powerW);
            return;
        }
        long completedAt = prefs.getLong(P + "completed_at", 0L);
        if (completedAt > 0L) {
            if (now - completedAt < CYCLE_COOLDOWN_MS) {
                setStatus(prefs, "Ciclo conservador concluído • ajustes sem melhora mantidos desativados");
                return;
            }
            prefs.edit().remove(P + "completed_at")
                    .putInt(P + "phase", 0).putInt(P + "candidate", 0)
                    .putString(P + "stage", "baseline").apply();
            startWindow(prefs, "baseline", now);
        }

        int phase = prefs.getInt(P + "phase", 0);
        if (phase >= PHASES) {
            prefs.edit().putLong(P + "completed_at", now).apply();
            setStatus(prefs, "Ciclo conservador concluído • ajustes sem melhora mantidos desativados");
            return;
        }

        String stage = prefs.getString(P + "stage", "baseline");
        String environment = prefs.getString("experiment_context", "");
        String windowEnv = prefs.getString(P + "window_environment", "");
        if ("trial".equals(stage) && !environment.isEmpty()
                && !environment.equals(prefs.getString(P + "base_environment", environment))) {
            restoreSnapshot(prefs, phase);
            advance(prefs, phase, now, "Revertido: brilho, rede ou tipo de uso mudou");
            return;
        }
        if ("baseline".equals(stage) && !environment.isEmpty()
                && !windowEnv.isEmpty() && !windowEnv.equals(environment)) {
            startWindow(prefs, "baseline", now);
            setStatus(prefs, "Referência reiniciada: condições de uso diferentes");
            return;
        }
        float socTempC = prefs.getFloat("thermal_soc_c", -1f);
        boolean unsafe = charging || (batteryPct >= 0 && batteryPct <= 30)
                || tempC >= 38.5f || socTempC >= 56f;
        if (unsafe) {
            if ("trial".equals(stage)) {
                restoreSnapshot(prefs, phase);
                advance(prefs, phase, now,
                        charging ? "teste revertido: carregando" :
                                (batteryPct >= 0 && batteryPct <= 30
                                        ? "teste revertido: bateria baixa"
                                        : "teste revertido: temperatura subiu"));
            } else {
                startWindow(prefs, "baseline", now);
                setStatus(prefs, charging ? "Aguardando usar a bateria para comparar consumo"
                        : (batteryPct >= 0 && batteryPct <= 30
                                ? "Testes pausados para preservar a bateria"
                                : "Testes pausados até o aparelho esfriar"));
            }
            return;
        }

        if ("trial".equals(stage)) {
            boolean screenChanged = interactive != prefs.getBoolean(P + "base_interactive", interactive);
            long jankAt = prefs.getLong("jank_sample_at", 0L);
            boolean invalid = tempC <= 0f || cpuLoad < 0d || freeRamPct < 0d || powerW <= 0f
                    || (prefs.getFloat("jank_pct", -1f) >= 0f && jankAt > 0L
                        && now - jankAt > 300_000L);
            float jank = prefs.getFloat("jank_pct", -1f);
            boolean regression = cpuLoad > prefs.getFloat(P + "base_cpu", 0f) + 10f
                    || freeRamPct < prefs.getFloat(P + "base_ram", 0f) - 5f
                    || (interactive && jank >= 0f && prefs.getFloat(P + "base_jank", -1f) >= 0f
                        && jank > prefs.getFloat(P + "base_jank", 0f) + 3f);
            if (screenChanged || invalid || regression) {
                restoreSnapshot(prefs, phase);
                advance(prefs, phase, now, "Revertido " + phaseName(phase) + ": "
                        + (screenChanged ? "mudou o estado da tela" : invalid
                        ? "medição indisponível" : "piora de CPU, RAM ou fluidez"));
                return;
            }
            if (now >= prefs.getLong(P + "deadline", now + WINDOW_MS)
                    && prefs.getInt(P + "samples", 0) < MIN_SAMPLES) {
                restoreSnapshot(prefs, phase);
                advance(prefs, phase, now, "Revertido " + phaseName(phase) + ": janela de 2 min sem dados suficientes");
                return;
            }
        }

        if ("trial".equals(stage) && immediateRegression(prefs, tempC, socTempC, powerW, interactive)) {
            restoreSnapshot(prefs, phase);
            String cause = tempC > prefs.getFloat(P + "base_temp", 0f) + 1.5f
                    || (socTempC > 0f && prefs.getFloat(P + "base_soc", -1f) > 0f
                    && socTempC > prefs.getFloat(P + "base_soc", 0f) + 2.0f)
                    ? "temperatura subiu rapidamente" : "consumo disparou";
            advance(prefs, phase, now, "Revertido " + phaseName(phase) + ": " + cause);
            return;
        }

        long lastCheck = prefs.getLong(P + "last_check", 0L);
        boolean deadline = "trial".equals(stage) && now >= prefs.getLong(P + "deadline", Long.MAX_VALUE);
        if (!deadline && lastCheck > 0L && now - lastCheck < SAMPLE_INTERVAL_MS) return;
        prefs.edit().putLong(P + "last_check", now).apply();
        long started = prefs.getLong(P + "stage_started", now);
        int samples = prefs.getInt(P + "samples", 0);
        long lastSample = prefs.getLong(P + "last_sample", 0L);
        if (lastSample > 0L && now - lastSample > 10L * 60L * 1000L) {
            if ("trial".equals(stage)) {
                restoreSnapshot(prefs, phase);
                advance(prefs, phase, now, "teste revertido: medição interrompida");
            } else {
                startWindow(prefs, "baseline", now);
                setStatus(prefs, "Referência reiniciada após pausa do monitoramento");
            }
            return;
        }
        if (now - started >= MAX_WINDOW_MS && samples < MIN_SAMPLES) {
            if ("trial".equals(stage)) restoreSnapshot(prefs, phase);
            advance(prefs, phase, now, "teste pulado/revertido: faltam amostras comparáveis");
            return;
        }
        if (tempC <= 0f || powerW <= 0f || freeRamPct < 0d) {
            setStatus(prefs, "Aguardando leituras confiáveis de temperatura, energia e RAM");
            return;
        }
        if (cpuLoad < 0d) {
            setStatus(prefs, "CPU indisponível • testes pausados para evitar perda de fluidez");
            return;
        }
        boolean expectedInteractive = "trial".equals(stage)
                ? prefs.getBoolean(P + "base_interactive", interactive)
                : (samples > 0 ? prefs.getBoolean(P + "window_interactive", interactive) : interactive);
        if (interactive != expectedInteractive) {
            setStatus(prefs, expectedInteractive
                    ? "Aguardando tela ativa para comparar a configuração"
                    : "Aguardando tela apagada para comparar a configuração");
            return;
        }

        addSample(prefs, now, batteryPct, tempC, socTempC, cpuLoad, freeRamPct,
                powerW, prefs.getFloat("jank_pct", -1f), interactive);
        samples = prefs.getInt(P + "samples", 0);
        if ((!deadline && now - started < WINDOW_MS) || samples < MIN_SAMPLES) {
            setStatus(prefs, stageStatus(prefs, stage, phase, samples));
            return;
        }

        Metrics current = readMetrics(prefs);
        if ("baseline".equals(stage)) {
            if (!current.hasSafetySignals()) {
                if (now - started >= MAX_WINDOW_MS) {
                    advance(prefs, phase, now, "teste pulado: faltam dados de referência");
                } else {
                    setStatus(prefs, "Aguardando uma referência de consumo estável");
                }
                return;
            }
            String dependency = OPTIONS[phase][2];
            if (!dependency.isEmpty() && !prefs.getBoolean(dependency, false)) {
                advance(prefs, phase, now, "Não testado " + phaseName(phase)
                        + ": módulo necessário não foi aprovado");
                return;
            }
            saveBaseline(prefs, current);
            snapshotPhase(prefs, phase);
            int candidate = prefs.getInt(P + "candidate", 0);
            applyCandidate(prefs, phase, candidate);
            prefs.edit().putString(P + "stage", "trial").apply();
            startWindow(prefs, "trial", now);
            setStatus(prefs, "Testando " + phaseName(phase) + " • nível "
                    + (candidate + 1) + "/" + candidateCount(phase));
            return;
        }

        Metrics baseline = readBaseline(prefs);
        String verdict = verdict(baseline, current);
        boolean keep = "MELHORA".equals(verdict);
        if (keep) {
            prefs.edit().putString(P + "stage", "probation")
                    .putLong(P + "probation_start", now)
                    .putLong(P + "probation_last", 0L)
                    .putInt(P + "probation_n", 0)
                    .putFloat(P + "probation_power", 0f)
                    .putFloat(P + "probation_temp", 0f)
                    .putFloat(P + "probation_cpu", 0f)
                    .putFloat(P + "probation_ram", 0f)
                    .putBoolean(P + "probation_6h_ok", false).commit();
            setStatus(prefs, "Melhora provisória: " + phaseName(phase) + " • acompanhando por 24h");
        } else {
            restoreSnapshot(prefs, phase);
            String reason = verdictReason(verdict);
            advance(prefs, phase, now, "Revertido " + phaseName(phase) + ": " + reason);
        }
    }

    private static void reviewProbation(SharedPreferences p, long now, int battery,
            float temp, double cpu, double ram, boolean interactive, boolean charging, float power) {
        int phase = p.getInt(P + "phase", 0);
        long elapsed = Math.max(0L, now - p.getLong(P + "probation_start", now));
        float basePower = p.getFloat(P + "base_power", -1f);
        float baseTemp = p.getFloat(P + "base_temp", -1f);
        String baselineContext = p.getString(P + "base_environment", "");
        String currentContext = p.getString("experiment_context", "");
        boolean sameContext = baselineContext.isEmpty() || baselineContext.equals(currentContext);
        boolean matched = sameContext && !charging && battery > 30 && temp > 0f && temp < 38.5f
                && cpu >= 0 && ram >= 0 && power > 0f
                && interactive == p.getBoolean(P + "base_interactive", interactive);
        float soc = p.getFloat("thermal_soc_c", -1f);
        if ((baseTemp > 0f && temp >= 38f && temp >= baseTemp + 2f)
                || (soc >= 56f && p.getFloat(P + "base_soc", -1f) > 0f
                    && soc >= p.getFloat(P + "base_soc", 0f) + 3f)
                || (matched && basePower > 0f && power > basePower * 1.7f)) {
            restoreSnapshot(p, phase);
            advance(p, phase, now, "Revertido: piora grave durante avaliação de 24h");
            return;
        }
        if (matched && now - p.getLong(P + "probation_last", 0L) >= PROBATION_INTERVAL) {
            p.edit().putInt(P + "probation_n", p.getInt(P + "probation_n", 0) + 1)
                    .putFloat(P + "probation_power", p.getFloat(P + "probation_power", 0f) + power)
                    .putFloat(P + "probation_temp", p.getFloat(P + "probation_temp", 0f) + temp)
                    .putFloat(P + "probation_cpu", p.getFloat(P + "probation_cpu", 0f) + (float) cpu)
                    .putFloat(P + "probation_ram", p.getFloat(P + "probation_ram", 0f) + (float) ram)
                    .putLong(P + "probation_last", now).apply();
        }
        int n = p.getInt(P + "probation_n", 0);
        if (elapsed >= REVIEW_6H && n >= 3) {
            float avgPower = p.getFloat(P + "probation_power", 0) / n;
            float avgTemp = p.getFloat(P + "probation_temp", 0) / n;
            float avgCpu = p.getFloat(P + "probation_cpu", 0) / n;
            float avgRam = p.getFloat(P + "probation_ram", 0) / n;
            if ((basePower > 0 && avgPower > basePower * 1.15f)
                    || (baseTemp > 0 && avgTemp > baseTemp + 1f)
                    || avgCpu > p.getFloat(P + "base_cpu", 0) + 12f
                    || avgRam < p.getFloat(P + "base_ram", 0) - 7f) {
                restoreSnapshot(p, phase);
                advance(p, phase, now, "Revertido: piora sustentada após 6h");
                return;
            }
            p.edit().putBoolean(P + "probation_6h_ok", true).apply();
        }
        if (elapsed >= CONFIRM_24H) {
            if (n < 8 && elapsed < CONFIRM_24H + REVIEW_6H) {
                setStatus(p, "Validação 24h: aguardando 8 leituras comparáveis");
                return;
            }
            boolean confirmed = n >= 8 && basePower > 0f
                    && p.getFloat(P + "probation_power", 0) / n <= basePower * .98f
                    && p.getBoolean(P + "probation_6h_ok", false);
            if (confirmed) {
                float savingPct = Math.max(0f, 100f * (basePower
                        - p.getFloat(P + "probation_power", 0f) / n) / basePower);
                p.edit().putFloat(P + "accepted_saving_pct_" + phase, savingPct)
                        .putInt(P + "accepted_samples_" + phase, n).apply();
                p.edit().putBoolean(P + "accepted_" + phase, true)
                        .putInt(P + "accepted_level_" + phase, p.getInt(P + "candidate", 0)).apply();
            } else restoreSnapshot(p, phase);
            advance(p, phase, now, (confirmed ? "Confirmado: " : "Revertido: ")
                    + phaseName(phase) + (confirmed ? " • economia validada em 24h"
                    : " • economia ou dados insuficientes em 24h"));
            return;
        }
        setStatus(p, "Validação de 24h: " + phaseName(phase) + " • "
                + elapsed/3600000L + "h • " + n + " amostras");
    }

    static void stopIfNeeded(SharedPreferences prefs) {
        String stage = prefs.getString(P + "stage", "baseline");
        if (!"trial".equals(stage) && !"probation".equals(stage)) return;
        int phase = prefs.getInt(P + "phase", 0);
        restoreSnapshot(prefs, phase);
        prefs.edit().putString(P + "stage", "baseline")
                .putLong(P + "stage_started", System.currentTimeMillis())
                .putLong(P + "last_sample", 0L).putLong(P + "last_check", 0L).apply();
        clearWindow(prefs);
        setStatus(prefs, "Teste interrompido ao sair do modo automático • opção revertida");
    }

    static void resetSession(SharedPreferences prefs) {
        if (trialActive(prefs) || "probation".equals(prefs.getString(P + "stage", ""))) {
            restoreSnapshot(prefs, prefs.getInt(P + "phase", 0));
        }
        SharedPreferences.Editor e = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(P)) e.remove(key);
        }
        applySafeDefaults(e);
        e.putBoolean(P + "initialized", true)
                .putInt(P + "phase", 0).putInt(P + "candidate", 0)
                .putString(P + "stage", "baseline")
                .putLong(P + "stage_started", System.currentTimeMillis())
                .putLong(P + "last_sample", 0L).putLong(P + "last_check", 0L)
                .putString("auto_tune_status", "Novo ciclo conservador • medindo referência");
        e.commit();
    }

    private static boolean immediateRegression(SharedPreferences p, float tempC,
                                               float socTempC, float powerW,
                                               boolean interactive) {
        float baseTemp = p.getFloat(P + "base_temp", -1f);
        float baseSoc = p.getFloat(P + "base_soc", -1f);
        float basePower = p.getFloat(P + "base_power", -1f);
        return (baseTemp > 0f && tempC > baseTemp + 1.5f)
                || (baseSoc > 0f && socTempC > 0f && socTempC > baseSoc + 2.0f)
                || (p.getBoolean(P + "base_interactive", interactive)
                == interactive && basePower > 0f && powerW > basePower * 1.5f);
    }

    private static String verdict(Metrics base, Metrics test) {
        if (!base.hasSafetySignals() || !test.hasSafetySignals()) return "DADOS";
        if (test.avgTemp > base.avgTemp + 0.25f
                || test.maxTemp > base.maxTemp + 0.8f
                || (base.avgSoc > 0f && test.avgSoc > base.avgSoc + 0.5f)
                || (base.maxSoc > 0f && test.maxSoc > base.maxSoc + 1.5f)) return "CALOR";
        if (test.avgCpu > base.avgCpu + Math.max(4f, base.avgCpu * 0.10f)) return "CPU";
        if (test.avgRam < base.avgRam - 3f) return "RAM";
        if (base.avgJank >= 0f && test.avgJank < 0f) return "DADOS";
        if (base.avgJank >= 0f && test.avgJank >= 0f
                && (test.avgJank > base.avgJank + 1.0f
                || (base.avgJank > 1f && test.avgJank > base.avgJank * 1.10f))) return "FLUIDEZ";

        double baseDrain = base.drainPercentPerHour();
        double testDrain = test.drainPercentPerHour();
        if (baseDrain >= 0d && testDrain >= 0d && testDrain > baseDrain + 0.10d) {
            return "CONSUMO";
        }
        if (base.avgPower > 0f && test.avgPower > 0f) {
            if (test.avgPower > base.avgPower * 1.02f) return "CONSUMO";
            if (test.avgPower <= base.avgPower * 0.95f) return "MELHORA";
            if (test.avgCpu <= base.avgCpu * 0.90f && test.avgCpu <= base.avgCpu - 3f) return "MELHORA";
            if (test.avgRam >= base.avgRam + 4f) return "MELHORA";
            return "SEM_MELHORA";
        }
        if (baseDrain >= 0d && testDrain >= 0d) {
            if (testDrain > baseDrain + 0.10d) return "CONSUMO";
            if (baseDrain > 0.1d && testDrain <= baseDrain * 0.90d) return "MELHORA";
        }
        return "DADOS";
    }

    private static String verdictReason(String verdict) {
        switch (verdict) {
            case "CALOR": return "a temperatura aumentou";
            case "CPU": return "o uso de CPU aumentou";
            case "RAM": return "a RAM livre caiu";
            case "FLUIDEZ": return "a fluidez da interface piorou";
            case "CONSUMO": return "o consumo de energia aumentou";
            case "DADOS": return "não houve dados suficientes para comprovar economia";
            default: return "não comprovou economia mensurável";
        }
    }

    private static void addSample(SharedPreferences p, long now, int batteryPct,
                                  float tempC, float socTempC, double cpuLoad, double freeRamPct,
                                  float powerW, float jankPct, boolean interactive) {
        SharedPreferences.Editor e = p.edit();
        int n = p.getInt(P + "samples", 0);
        if (n == 0) {
            e.putString(P + "window_environment", p.getString("experiment_context", ""));
            e.putLong(P + "window_start", now).putBoolean(P + "window_interactive", interactive);
            if (batteryPct >= 0) e.putInt(P + "battery_start", batteryPct);
        }
        e.putInt(P + "samples", n + 1)
                .putFloat(P + "sum_temp", p.getFloat(P + "sum_temp", 0f) + tempC)
                .putFloat(P + "sum_cpu", p.getFloat(P + "sum_cpu", 0f) + (float) cpuLoad)
                .putFloat(P + "sum_ram", p.getFloat(P + "sum_ram", 0f) + (float) freeRamPct)
                .putFloat(P + "max_temp", Math.max(p.getFloat(P + "max_temp", 0f), tempC))
                .putLong(P + "last_sample", now)
                .putLong(P + "window_end", now);
        if (socTempC > 0f) e.putFloat(P + "sum_soc",
                p.getFloat(P + "sum_soc", 0f) + socTempC)
                .putInt(P + "soc_samples", p.getInt(P + "soc_samples", 0) + 1)
                .putFloat(P + "max_soc", Math.max(p.getFloat(P + "max_soc", 0f), socTempC));
        if (powerW > 0f) e.putFloat(P + "sum_power",
                p.getFloat(P + "sum_power", 0f) + powerW)
                .putInt(P + "power_samples", p.getInt(P + "power_samples", 0) + 1);
        if (jankPct >= 0f) e.putFloat(P + "sum_jank",
                p.getFloat(P + "sum_jank", 0f) + jankPct)
                .putInt(P + "jank_samples", p.getInt(P + "jank_samples", 0) + 1);
        if (batteryPct >= 0) e.putInt(P + "battery_end", batteryPct);
        e.apply();
    }

    private static Metrics readMetrics(SharedPreferences p) {
        Metrics m = new Metrics();
        m.n = p.getInt(P + "samples", 0);
        m.avgTemp = average(p.getFloat(P + "sum_temp", 0f), m.n);
        m.avgCpu = average(p.getFloat(P + "sum_cpu", 0f), m.n);
        m.avgRam = average(p.getFloat(P + "sum_ram", 0f), m.n);
        int sn = p.getInt(P + "soc_samples", 0);
        m.avgSoc = sn > 0 ? p.getFloat(P + "sum_soc", 0f) / sn : -1f;
        m.maxSoc = p.getFloat(P + "max_soc", -1f);
        int pn = p.getInt(P + "power_samples", 0);
        m.avgPower = pn > 0 ? p.getFloat(P + "sum_power", 0f) / pn : -1f;
        int jn = p.getInt(P + "jank_samples", 0);
        m.avgJank = jn > 0 ? p.getFloat(P + "sum_jank", 0f) / jn : -1f;
        m.maxTemp = p.getFloat(P + "max_temp", 0f);
        m.startAt = p.getLong(P + "window_start", 0L);
        m.endAt = p.getLong(P + "window_end", 0L);
        m.startBattery = p.getInt(P + "battery_start", -1);
        m.endBattery = p.getInt(P + "battery_end", -1);
        return m;
    }

    private static void saveBaseline(SharedPreferences p, Metrics m) {
        p.edit().putFloat(P + "base_temp", m.avgTemp)
                .putFloat(P + "base_cpu", m.avgCpu)
                .putFloat(P + "base_ram", m.avgRam)
                .putFloat(P + "base_power", m.avgPower)
                .putFloat(P + "base_jank", m.avgJank)
                .putFloat(P + "base_max_temp", m.maxTemp)
                .putFloat(P + "base_soc", m.avgSoc)
                .putFloat(P + "base_max_soc", m.maxSoc)
                .putFloat(P + "base_drain", (float) m.drainPercentPerHour()).apply();
    }

    private static Metrics readBaseline(SharedPreferences p) {
        Metrics m = new Metrics();
        m.n = MIN_SAMPLES;
        m.avgTemp = p.getFloat(P + "base_temp", -1f);
        m.avgCpu = p.getFloat(P + "base_cpu", -1f);
        m.avgRam = p.getFloat(P + "base_ram", -1f);
        m.avgPower = p.getFloat(P + "base_power", -1f);
        m.avgJank = p.getFloat(P + "base_jank", -1f);
        m.maxTemp = p.getFloat(P + "base_max_temp", -1f);
        m.avgSoc = p.getFloat(P + "base_soc", -1f);
        m.maxSoc = p.getFloat(P + "base_max_soc", -1f);
        m.startAt = p.getLong(P + "base_window_start", 0L);
        m.endAt = p.getLong(P + "base_window_end", 0L);
        m.startBattery = p.getInt(P + "base_battery_start", -1);
        m.endBattery = p.getInt(P + "base_battery_end", -1);
        return m;
    }

    private static void snapshotPhase(SharedPreferences p, int phase) {
        String key = OPTIONS[phase][0];
        p.edit().putString(P + "snapshot_key", key)
                .putBoolean(P + "snapshot_present", p.contains(key))
                .putBoolean(P + "snapshot_value", p.getBoolean(key, false))
                .putLong(P + "base_window_start", p.getLong(P + "window_start", 0L))
                .putLong(P + "base_window_end", p.getLong(P + "window_end", 0L))
                .putBoolean(P + "base_interactive", p.getBoolean(P + "window_interactive", false))
                .putString(P + "base_environment", p.getString(P + "window_environment", ""))
                .putInt(P + "base_battery_start", p.getInt(P + "battery_start", -1))
                .putInt(P + "base_battery_end", p.getInt(P + "battery_end", -1)).commit();
    }

    private static void restoreSnapshot(SharedPreferences p, int phase) {
        String key = p.getString(P + "snapshot_key", "");
        if (key.isEmpty()) return;
        SharedPreferences.Editor e = p.edit();
        if (p.getBoolean(P + "snapshot_present", false))
            e.putBoolean(key, p.getBoolean(P + "snapshot_value", false));
        else e.remove(key);
        e.putString(P + "restore_pending", key).commit();
    }

    private static void applyCandidate(SharedPreferences p, int phase, int candidate) {
        String key = OPTIONS[phase][0];
        // Snapshot and trial marker are durable BEFORE the setting can take effect.
        p.edit().putString(P + "stage", "trial")
                .putLong(P + "deadline", System.currentTimeMillis() + WINDOW_MS)
                .putBoolean(key, !p.getBoolean(P + "snapshot_value", false)).commit();
    }

    private static void advance(SharedPreferences p, int phase, long now, String priorResult) {
        String entry = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(now)) + " | " + priorResult;
        String history = entry + "\n" + p.getString("auto_tune_decision_history", "");
        p.edit().putString("auto_tune_decision_history",
                history.length() > 3600 ? history.substring(0, 3600) : history).apply();
        p.edit().putString(P + "result_" + OPTIONS[phase][0], priorResult == null ? "" : priorResult)
                .putString(P + "last_result", priorResult == null ? "" : priorResult).apply();
        int candidate = p.getInt(P + "candidate", 0) + 1;
        // Repeat only inconclusive measurements. Never reapply a known regression.
        if (priorResult == null || (!priorResult.contains("não comprovou")
                && !priorResult.contains("dados suficientes")
                && !priorResult.contains("SEM_MELHORA"))) candidate = candidateCount(phase);
        int nextPhase = phase;
        if (candidate >= candidateCount(phase)) {
            candidate = 0;
            nextPhase++;
        }
        SharedPreferences.Editor e = p.edit();
        if (nextPhase >= PHASES) {
            e.putLong(P + "completed_at", now).putInt(P + "phase", PHASES)
                    .putString(P + "stage", "completed");
            if (priorResult != null) e.putString("auto_tune_status",
                    priorResult + " • ciclo concluído; somente melhorias comprovadas foram mantidas");
            else e.putString("auto_tune_status", "Ciclo concluído • somente melhorias comprovadas foram mantidas");
            e.apply();
            return;
        }
        e.putInt(P + "phase", nextPhase).putInt(P + "candidate", candidate)
                .putString(P + "stage", "baseline").apply();
        startWindow(p, "baseline", now);
        if (priorResult != null) setStatus(p, priorResult + " • próxima referência: " + phaseName(nextPhase));
        else setStatus(p, "Medindo referência para " + phaseName(nextPhase));
    }

    private static void startWindow(SharedPreferences p, String stage, long now) {
        clearWindow(p);
        p.edit().putString(P + "stage", stage)
                .putLong(P + "stage_started", now)
                .putLong(P + "last_sample", 0L).apply();
    }

    private static void clearWindow(SharedPreferences p) {
        p.edit().putInt(P + "samples", 0)
                .putInt(P + "power_samples", 0).putInt(P + "jank_samples", 0)
                .putFloat(P + "sum_temp", 0f).putFloat(P + "sum_cpu", 0f)
                .putFloat(P + "sum_ram", 0f).putFloat(P + "sum_power", 0f)
                .putFloat(P + "sum_jank", 0f).putFloat(P + "max_temp", 0f)
                .putFloat(P + "sum_soc", 0f).putFloat(P + "max_soc", 0f)
                .putInt(P + "soc_samples", 0)
                .remove(P + "window_start").remove(P + "window_end")
                .remove(P + "window_interactive").remove(P + "window_environment").remove(P + "last_check")
                .remove(P + "battery_start").remove(P + "battery_end").apply();
    }

    private static void setStatus(SharedPreferences p, String status) {
        if (!status.equals(p.getString("auto_tune_status", ""))) {
            p.edit().putString("auto_tune_status", status).apply();
        }
    }

    private static String stageStatus(SharedPreferences p, String stage, int phase, int samples) {
        String label = "baseline".equals(stage) ? "Medindo referência" : "Testando";
        return label + " • " + phaseName(phase) + " • amostras " + samples + "/" + MIN_SAMPLES;
    }

    // Repeat inconclusive trials once; do not retry regressions or unsafe settings.
    private static int candidateCount(int phase) { return 2; }

    private static String phaseName(int phase) {
        return OPTIONS[Math.max(0, Math.min(OPTIONS.length - 1, phase))][1];
    }

    static boolean trialActive(SharedPreferences p) {
        return "trial".equals(p.getString(P + "stage", "baseline"));
    }

    static void initialize(SharedPreferences p) {
        if (p.getInt(P + "schema", 0) == 4) return;
        // Old version snapshots use numeric phases; migrate before replacing their meaning.
        if (trialActive(p) && p.getString(P + "snapshot_key", "").isEmpty()) {
            int old = p.getInt(P + "phase", 0);
            String[] keys = {"memory_compaction_enabled", "app_ram_limiter_enabled",
                "adaptive_freeze_enabled", "", "smart_auto_cleanup", "screen_off_optimization",
                "wakeup_network_guard", "system_radio_savings"};
            if (old >= 0 && old < keys.length && !keys[old].isEmpty())
                p.edit().putBoolean(keys[old], p.getBoolean(P + "base_enabled_" + old, false)).commit();
        }
        resetSession(p);
        SharedPreferences.Editor e = p.edit();
        for (String[] option : OPTIONS) e.putBoolean(option[0], false);
        // Health/thermal/rollback protections are never experimentally disabled.
        e.putBoolean("health_guard", true).putBoolean("rollback_guard", true)
                .putBoolean("over_optimization_guard", true).putBoolean("cause_confidence_guard", true)
                .putBoolean("recurrence_guard", true).putBoolean("post_correction_guard", true)
                .putBoolean("zram_thrash_guard", true).putBoolean("smart_suite_enabled", true)
                .putBoolean("adaptive_refresh", true)
                .putBoolean("diagnostic_only", false).putBoolean("extended_safe_mode_manual", false)
                .putBoolean("auto_repair", false).putBoolean("critical_cleanup", false)
                .putBoolean("ram_emergency_mode", false).putBoolean("auto_bug_cleanup", false)
                .putBoolean("auto_unused_restrict", false).putBoolean("aggressive_memory_cleanup", false)
                .putBoolean("manual_freeze_enabled", false).putBoolean("screen_off_optimization", false)
                .putBoolean("wakeup_network_guard", false).putBoolean("system_deep_idle", false)
                .putBoolean("auto_calibration", false).putBoolean("adaptive_aggressiveness", false)
                .putBoolean("ab_testing", false).putBoolean("crash_loop_guard", false)
                .putBoolean("startup_diagnostics", false)
                .putBoolean("conservative_profile_initialized", true)
                .putString("user_mode", "auto").putInt(P + "schema", 4)
                .putInt(P + "option_count", PHASES)
                .putString(P + "exclusions", "Proteções permanecem ativas. Limpeza de dados/cache, "
                    + "encerramento de processos, congelamento, compactação/ZRAM e ações sem restauração "
                    + "ficam desativados. Autocalibração, A/B paralelo e deep idle não podem interferir "
                    + "na medição. Diagnóstico de inicialização e ações manuais não são testes de 2 min.")
                .commit();
    }

    static void recoverAfterRestart(SharedPreferences p) {
        initialize(p);
        if (trialActive(p)) {
            restoreSnapshot(p, p.getInt(P + "phase", 0));
            startWindow(p, "baseline", System.currentTimeMillis());
            setStatus(p, "Teste interrompido pelo reinício • ajuste restaurado; medindo nova referência");
        }
    }

    static String evidenceSummary(SharedPreferences p) {
        boolean en = "en".equals(p.getString("app_language", "pt"));
        int confirmed = 0;
        int legacy = 0;
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < PHASES; i++) {
            if (!p.getBoolean(P + "accepted_" + i, false)) continue;
            if (p.getInt(P + "accepted_samples_" + i, 0) < 8
                    || p.getFloat(P + "accepted_saving_pct_" + i, -1f) < 0f) {
                legacy++;
                continue;
            }
            confirmed++;
            if (confirmed <= 5) {
                float pct = p.getFloat(P + "accepted_saving_pct_" + i, -1f);
                lines.append("\n").append(UiLanguage.tr(p, OPTIONS[i][1]));
                if (pct >= 0f) lines.append(String.format(java.util.Locale.US,
                        " • ~%.1f%% ", pct)).append(en ? "less measured draw" : "menos potência medida");
                lines.append(" • ").append(p.getInt(P + "accepted_samples_" + i, 0))
                        .append(en ? " comparable samples" : " amostras comparáveis");
            }
        }
        String stage = p.getString(P + "stage", "baseline");
        String current = p.getString("auto_tune_status", "");
        String history = p.getString("auto_tune_decision_history", "");
        int reverted = history.split("Revertido", -1).length - 1;
        return (en ? "Confirmed: " : "Confirmadas: ") + confirmed
                + (en ? " • Reversals (recent history): " : " • Reversões (histórico recente): ") + reverted
                + lines + (legacy > 0
                    ? (en ? "\nLegacy approvals awaiting sample verification: "
                        : "\nAprovações antigas sem amostras verificáveis: ") + legacy : "")
                + (confirmed == 0 ? (en ? "\nNo long-term savings confirmed yet."
                    : "\nNenhuma economia prolongada confirmada ainda.") : "")
                + "\n" + (en ? "Current stage: " : "Etapa atual: ") + stage
                + "\n" + current
                + "\n" + (en ? "Estimated draw only; not proof of battery-life gains."
                    : "Estimativa de potência, não comprovação de autonomia.");
    }

    private static float average(float sum, int n) { return n > 0 ? sum / n : -1f; }

    private static final class Metrics {
        int n;
        float avgTemp, avgCpu, avgRam, avgPower, avgJank, maxTemp, avgSoc, maxSoc;
        int startBattery, endBattery;
        long startAt, endAt;

        boolean hasSafetySignals() {
            return n >= MIN_SAMPLES && avgTemp > 0f && avgCpu >= 0f && avgRam >= 0f
                    && avgPower > 0f;
        }

        double drainPercentPerHour() {
            long duration = endAt - startAt;
            if (startBattery < 0 || endBattery < 0 || duration <= 0L
                    || startBattery < endBattery) return -1d;
            return (startBattery - endBattery) * 3_600_000d / duration;
        }
    }
}

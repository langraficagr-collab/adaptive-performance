package com.mauricio.adaptiveperformance;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import java.util.regex.*;

public final class CellularSignalOptimizer {
    private static final long TEST_SETTLE_MS = 20_000L;
    private static final long DEFAULT_COOLDOWN_MS = 30L * 60L * 1000L;

    // Android TelephonyManager network bitmasks.
    private static final long GPRS = 1L;
    private static final long EDGE = 2L;
    private static final long UMTS = 4L;
    private static final long CDMA = 8L;
    private static final long EVDO_0 = 16L;
    private static final long EVDO_A = 32L;
    private static final long RTT_1X = 64L;
    private static final long HSDPA = 128L;
    private static final long HSUPA = 256L;
    private static final long HSPA = 512L;
    private static final long EVDO_B = 2048L;
    private static final long LTE = 4096L;
    private static final long EHRPD = 8192L;
    private static final long HSPAP = 16384L;
    private static final long GSM = 32768L;
    private static final long TD_SCDMA = 65536L;
    private static final long LTE_CA = 262144L;
    private static final long NR = 524288L;

    private CellularSignalOptimizer() {}

    public static void evaluate(Context c, SharedPreferences p, IPrivilegedService s) {
        if (s == null) return;
        boolean enabled = p.getBoolean("signal_optimizer_enabled", false);
        if (!enabled) {
            restoreIfNeeded(p, s);
            return;
        }

        long now = System.currentTimeMillis();
        try {
            if (callActive(s)) {
                p.edit().putString("signal_optimizer_status", "Pausado: chamada em andamento").apply();
                return;
            }

            String phase = p.getString("signal_optimizer_phase", "idle");
            if ("testing".equals(phase)) {
                continueTest(p, s, now);
                return;
            }

            long interval = Math.max(2, Math.min(30, p.getInt("signal_optimizer_interval_min", 5))) * 60_000L;
            boolean force = p.getBoolean("signal_optimizer_force_check", false);
            long last = p.getLong("signal_optimizer_last_check", 0L);
            if (!force && now - last < interval) return;
            if (!force && now < p.getLong("signal_optimizer_cooldown_until", 0L)) return;
            p.edit().putBoolean("signal_optimizer_force_check", false)
                    .putLong("signal_optimizer_last_check", now).apply();

            Signal sig = readSignal(s);
            saveSignal(p, sig);
            int weakLevel = Math.max(0, Math.min(2, p.getInt("signal_optimizer_weak_level", 1)));
            int weakRsrp = Math.max(-125, Math.min(-100, p.getInt("signal_optimizer_rsrp_threshold", -115)));

            if (!sig.registered) {
                p.edit().putInt("signal_optimizer_weak_confirm", 0)
                        .putString("signal_optimizer_status", "Leitura de sinal indisponível; aguardando amostra válida").apply();
                return;
            }

            boolean weak = sig.level <= weakLevel;
            if (("LTE".equals(sig.tech) || "NR".equals(sig.tech)) && sig.dbm <= weakRsrp) weak = true;

            if (!weak) {
                p.edit().putInt("signal_optimizer_weak_confirm", 0)
                        .putString("signal_optimizer_status",
                        "Sinal aceitável • " + sig.tech + " • nível " + sig.level + "/4 • " + sig.dbm + " dBm").apply();
                return;
            }

            int confirm = p.getInt("signal_optimizer_weak_confirm", 0) + 1;
            if (confirm < 2) {
                // Confirma novamente em ~30 s para não reagir a uma queda transitória.
                p.edit().putInt("signal_optimizer_weak_confirm", confirm)
                        .putLong("signal_optimizer_last_check", now - interval + 30_000L)
                        .putString("signal_optimizer_status",
                                "Sinal baixo detectado; confirmando antes de trocar a rede…").apply();
                return;
            }
            p.edit().putInt("signal_optimizer_weak_confirm", 0).apply();
            startTest(p, s, sig, now);
        } catch (Throwable t) {
            p.edit().putString("signal_optimizer_status",
                    "Falha ao verificar sinal: " + t.getClass().getSimpleName()).apply();
        }
    }

    public static void restoreIfNeeded(SharedPreferences p, IPrivilegedService s) {
        if (s == null || !p.getBoolean("signal_optimizer_changed", false)) return;
        try {
            int slot = p.getInt("signal_optimizer_slot", -1);
            long original = p.getLong("signal_optimizer_original_mask", 0L);
            if (slot >= 0 && original > 0 && !callActive(s)) {
                if (setMaskExact(s, slot, original)) {
                    p.edit().putBoolean("signal_optimizer_changed", false)
                            .putString("signal_optimizer_phase", "idle")
                            .putString("signal_optimizer_status", "Modo de rede original restaurado")
                            .apply();
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void startTest(SharedPreferences p, IPrivilegedService s, Signal baseline, long now) throws Exception {
        SlotInfo info = activeSlot(s);
        if (info == null || info.mask <= 0L) {
            p.edit().putString("signal_optimizer_status", "Não foi possível identificar o SIM ativo").apply();
            return;
        }

        ArrayList<Candidate> candidates = candidates(info.mask, p);
        if (candidates.isEmpty()) {
            p.edit().putString("signal_optimizer_status", "Nenhuma tecnologia alternativa disponível no SIM").apply();
            return;
        }

        int baselineScore = score(baseline);
        p.edit()
                .putInt("signal_optimizer_slot", info.slot)
                .putLong("signal_optimizer_original_mask", info.mask)
                .putLong("signal_optimizer_best_mask", info.mask)
                .putInt("signal_optimizer_best_score", baselineScore)
                .putString("signal_optimizer_best_mode", "Automático/original")
                .putString("signal_optimizer_candidates", encodeCandidates(candidates))
                .putInt("signal_optimizer_candidate_index", 0)
                .putString("signal_optimizer_phase", "testing")
                .putBoolean("signal_optimizer_changed", true)
                .putLong("signal_optimizer_candidate_applied_at", 0L)
                .putString("signal_optimizer_status", "Sinal baixo detectado; iniciando comparação de redes")
                .apply();

        applyNextCandidate(p, s, now);
    }

    private static void continueTest(SharedPreferences p, IPrivilegedService s, long now) throws Exception {
        long appliedAt = p.getLong("signal_optimizer_candidate_applied_at", 0L);
        if (appliedAt <= 0L) {
            applyNextCandidate(p, s, now);
            return;
        }
        if (now - appliedAt < TEST_SETTLE_MS) return;

        Signal sig = readSignal(s);
        saveSignal(p, sig);
        int currentScore = score(sig);
        int bestScore = p.getInt("signal_optimizer_best_score", Integer.MIN_VALUE);
        int idx = p.getInt("signal_optimizer_candidate_index", 0);
        ArrayList<Candidate> list = decodeCandidates(p.getString("signal_optimizer_candidates", ""));

        int measuredIndex = Math.max(0, idx - 1);
        if (measuredIndex < list.size()) {
            Candidate measured = list.get(measuredIndex);
            if (currentScore > bestScore) {
                p.edit().putInt("signal_optimizer_best_score", currentScore)
                        .putLong("signal_optimizer_best_mask", measured.mask)
                        .putString("signal_optimizer_best_mode", measured.name)
                        .apply();
            }
        }
        applyNextCandidate(p, s, now);
    }

    private static void applyNextCandidate(SharedPreferences p, IPrivilegedService s, long now) throws Exception {
        ArrayList<Candidate> list = decodeCandidates(p.getString("signal_optimizer_candidates", ""));
        int idx = p.getInt("signal_optimizer_candidate_index", 0);
        int slot = p.getInt("signal_optimizer_slot", -1);
        if (slot < 0) {
            finish(p, s, now);
            return;
        }

        if (idx >= list.size()) {
            finish(p, s, now);
            return;
        }

        Candidate c = list.get(idx);
        long original = p.getLong("signal_optimizer_original_mask", 0L);
        if (!safeSetCandidate(s, slot, c.mask, original)) {
            p.edit().putInt("signal_optimizer_candidate_index", idx + 1)
                    .putLong("signal_optimizer_candidate_applied_at", 0L)
                    .putString("signal_optimizer_status", c.name + " não aceito; testando próxima rede")
                    .apply();
            applyNextCandidate(p, s, now);
            return;
        }

        p.edit().putInt("signal_optimizer_candidate_index", idx + 1)
                .putLong("signal_optimizer_candidate_applied_at", now)
                .putString("signal_optimizer_testing_mode", c.name)
                .putString("signal_optimizer_status", "Testando " + c.name + " por 20 s…")
                .apply();
    }

    private static void finish(SharedPreferences p, IPrivilegedService s, long now) throws Exception {
        int slot = p.getInt("signal_optimizer_slot", -1);
        long best = p.getLong("signal_optimizer_best_mask", p.getLong("signal_optimizer_original_mask", 0L));
        String bestMode = p.getString("signal_optimizer_best_mode", "Automático/original");
        long original = p.getLong("signal_optimizer_original_mask", 0L);
        boolean applied = slot >= 0 && best > 0 && safeSetCandidate(s, slot, best, original);

        long cooldown = Math.max(10, Math.min(120, p.getInt("signal_optimizer_cooldown_min", 30))) * 60_000L;
        p.edit()
                .putBoolean("signal_optimizer_changed", applied && best != original)
                .putString("signal_optimizer_phase", "idle")
                .putLong("signal_optimizer_candidate_applied_at", 0L)
                .putLong("signal_optimizer_cooldown_until", now + cooldown)
                .putLong("signal_optimizer_last_optimization", now)
                .putString("signal_optimizer_selected_mode", bestMode)
                .putString("signal_optimizer_status", "Melhor rede mantida: " + bestMode + " • nova verificação após cooldown")
                .apply();
    }

    private static ArrayList<Candidate> candidates(long supported, SharedPreferences p) {
        ArrayList<Candidate> out = new ArrayList<>();

        if (p.getBoolean("signal_optimizer_test_5g", true) && (supported & NR) != 0 && (supported & LTE) != 0) {
            out.add(new Candidate("5G + 4G", NR | LTE | LTE_CA));
        }
        if (p.getBoolean("signal_optimizer_test_4g", true) && (supported & LTE) != 0) {
            long mask = LTE | ((supported & LTE_CA) != 0 ? LTE_CA : 0);
            out.add(new Candidate("4G/LTE", mask));
        }
        if (p.getBoolean("signal_optimizer_test_3g", true)) {
            long m = supported & (UMTS | HSDPA | HSUPA | HSPA | HSPAP | TD_SCDMA);
            if (m != 0) out.add(new Candidate("3G", m));
        }
        if (p.getBoolean("signal_optimizer_test_2g", true)) {
            long m = supported & (GPRS | EDGE | GSM);
            if (m != 0) out.add(new Candidate("2G", m));
        }
        return out;
    }

    private static SlotInfo activeSlot(IPrivilegedService s) throws Exception {
        String raw = s.exec("for x in 0 1 2; do o=$(cmd phone get-allowed-network-types-for-users -s $x 2>/dev/null); [ -n \"$o\" ] && echo \"$x|$o\"; done");
        if (raw == null) return null;
        for (String line : raw.split("\n")) {
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            try {
                int slot = Integer.parseInt(line.substring(0, bar).trim());
                long mask = maskFromNames(line.substring(bar + 1).trim());
                if (mask > 0) return new SlotInfo(slot, mask);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static long maskFromNames(String raw) {
        long m = 0L;
        for (String x : raw.split("\\|")) {
            switch (x.trim()) {
                case "GPRS": m |= GPRS; break;
                case "EDGE": m |= EDGE; break;
                case "UMTS": m |= UMTS; break;
                case "CDMA": m |= CDMA; break;
                case "CDMA - EvDo rev. 0": m |= EVDO_0; break;
                case "CDMA - EvDo rev. A": m |= EVDO_A; break;
                case "CDMA - 1xRTT": m |= RTT_1X; break;
                case "HSDPA": m |= HSDPA; break;
                case "HSUPA": m |= HSUPA; break;
                case "HSPA": m |= HSPA; break;
                case "CDMA - EvDo rev. B": m |= EVDO_B; break;
                case "LTE": m |= LTE; break;
                case "CDMA - eHRPD": m |= EHRPD; break;
                case "HSPA+": m |= HSPAP; break;
                case "GSM": m |= GSM; break;
                case "TD_SCDMA": m |= TD_SCDMA; break;
                case "LTE_CA": m |= LTE_CA; break;
                case "NR": m |= NR; break;
            }
        }
        return m;
    }

    private static boolean setMaskRaw(IPrivilegedService s, int slot, long mask) throws Exception {
        if (mask <= 0L) return false;
        String binary = Long.toBinaryString(mask);
        String out = s.exec("cmd phone set-allowed-network-types-for-users -s " + slot + " " + binary + " 2>&1");
        return out != null && out.contains("completed");
    }

    private static long readSlotMask(IPrivilegedService s, int slot) throws Exception {
        String out = s.exec("cmd phone get-allowed-network-types-for-users -s " + slot + " 2>/dev/null");
        if (out == null || out.trim().isEmpty()) return 0L;
        return maskFromNames(out.trim().split("\n")[0]);
    }

    private static boolean setMaskExact(IPrivilegedService s, int slot, long mask) throws Exception {
        if (!setMaskRaw(s, slot, mask)) return false;
        try { Thread.sleep(350L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return readSlotMask(s, slot) == mask;
    }

    private static boolean safeSetCandidate(IPrivilegedService s, int slot, long target, long original) throws Exception {
        if (target <= 0L || original <= 0L) return false;
        // Never enable a radio technology that the user/SIM profile did not already allow.
        if ((target & ~original) != 0L) return false;
        if (!setMaskRaw(s, slot, target)) return false;
        try { Thread.sleep(350L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        long actual = readSlotMask(s, slot);
        if (actual == target) return true;

        // Unexpected platform normalization: immediately restore the exact original profile.
        setMaskRaw(s, slot, original);
        pSafeSleep();
        return false;
    }

    private static void pSafeSleep() {
        try { Thread.sleep(350L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static boolean callActive(IPrivilegedService s) throws Exception {
        String out = s.exec("dumpsys telephony.registry 2>/dev/null | grep -E 'mCallState=[12]' | head -1");
        return out != null && !out.trim().isEmpty();
    }

    private static Signal readSignal(IPrivilegedService s) throws Exception {
        String raw = s.exec("dumpsys telephony.registry 2>/dev/null | grep 'mSignalStrength='");
        Signal best = new Signal("UNKNOWN", 0, -140, false);
        if (raw == null) return best;
        for (String line : raw.split("\n")) {
            if (!line.contains("CellSignalStrength")) continue;
            String tech = tech(line);
            String segment = segment(line, tech);
            if (segment == null || segment.contains("invalid")) continue;

            int level = intMatch(segment, "miuiLevel=(-?\\d+)", Integer.MIN_VALUE);
            if (level == Integer.MIN_VALUE) level = intMatch(segment, "(?:^|[ ,])level=(-?\\d+)", 0);
            int dbm;
            if ("NR".equals(tech)) {
                dbm = intMatch(segment, "(?:ssRsrp|csiRsrp)=(-?\\d+)", -140);
            } else if ("LTE".equals(tech)) {
                dbm = intMatch(segment, "rsrp=(-?\\d+)", -140);
            } else if ("WCDMA".equals(tech) || "TDSCDMA".equals(tech)) {
                dbm = intMatch(segment, "(?:rscp|rssi)=(-?\\d+)", -140);
            } else {
                dbm = intMatch(segment, "rssi=(-?\\d+)", -140);
            }
            Signal x = new Signal(tech, Math.max(0, Math.min(4, level)), dbm, true);
            if (score(x) > score(best)) best = x;
        }
        return best;
    }

    private static String tech(String line) {
        Matcher m = Pattern.compile("primary=CellSignalStrength([A-Za-z]+)").matcher(line);
        if (!m.find()) {
            if (line.contains("mNr=CellSignalStrengthNr:")) return "NR";
            if (line.contains("mLte=CellSignalStrengthLte:")) return "LTE";
            if (line.contains("mWcdma=CellSignalStrengthWcdma:")) return "WCDMA";
            if (line.contains("mGsm=CellSignalStrengthGsm:")) return "GSM";
            return "UNKNOWN";
        }
        String t = m.group(1).toUpperCase(Locale.US);
        if ("LTE".equals(t)) return "LTE";
        if ("NR".equals(t)) return "NR";
        if ("WCDMA".equals(t)) return "WCDMA";
        if ("GSM".equals(t)) return "GSM";
        if ("TDSCDMA".equals(t)) return "TDSCDMA";
        return t;
    }

    private static String segment(String line, String tech) {
        String key;
        String next;
        switch (tech) {
            case "NR": key = "mNr="; next = ",primary="; break;
            case "LTE": key = "mLte="; next = ",mNr="; break;
            case "WCDMA": key = "mWcdma="; next = ",mTdscdma="; break;
            case "TDSCDMA": key = "mTdscdma="; next = ",mLte="; break;
            case "GSM": key = "mGsm="; next = ",mWcdma="; break;
            default: return line;
        }
        int a = line.indexOf(key);
        if (a < 0) return null;
        int b = line.indexOf(next, a);
        return b > a ? line.substring(a, b) : line.substring(a);
    }

    private static int intMatch(String text, String regex, int def) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) return def;
        try { return Integer.parseInt(m.group(1)); } catch (Throwable t) { return def; }
    }

    private static int score(Signal s) {
        if (!s.registered) return -100000;
        int dbm = Math.max(-150, Math.min(-40, s.dbm));
        return s.level * 1000 + (dbm + 150);
    }

    private static void saveSignal(SharedPreferences p, Signal s) {
        p.edit().putString("signal_optimizer_current_tech", s.tech)
                .putInt("signal_optimizer_current_level", s.level)
                .putInt("signal_optimizer_current_dbm", s.dbm)
                .putLong("signal_optimizer_signal_at", System.currentTimeMillis()).apply();
    }

    private static String encodeCandidates(ArrayList<Candidate> list) {
        StringBuilder b = new StringBuilder();
        for (Candidate c : list) {
            if (b.length() > 0) b.append(';');
            b.append(c.name).append('|').append(c.mask);
        }
        return b.toString();
    }

    private static ArrayList<Candidate> decodeCandidates(String raw) {
        ArrayList<Candidate> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String x : raw.split(";")) {
            int bar = x.lastIndexOf('|');
            if (bar <= 0) continue;
            try { out.add(new Candidate(x.substring(0, bar), Long.parseLong(x.substring(bar + 1)))); }
            catch (Throwable ignored) {}
        }
        return out;
    }

    static final class Signal {
        final String tech; final int level; final int dbm; final boolean registered;
        Signal(String t, int l, int d, boolean r) { tech=t; level=l; dbm=d; registered=r; }
    }
    static final class Candidate {
        final String name; final long mask;
        Candidate(String n, long m) { name=n; mask=m; }
    }
    static final class SlotInfo {
        final int slot; final long mask;
        SlotInfo(int s, long m) { slot=s; mask=m; }
    }
}

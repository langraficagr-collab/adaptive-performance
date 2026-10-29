package com.mauricio.adaptiveperformance;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded user-initiated cache cleanup, never 256G/512G target space. */
final class CachePolicy {
    private CachePolicy() {}
    static String manualTrim(IPrivilegedService shell) throws Exception {
        if (shell == null) return "Shizuku indisponível";
        String df = shell.exec("df -k /data 2>/dev/null | tail -1");
        if (df == null) return "Armazenamento indisponível";
        String[] parts = df.trim().split("\\s+");
        if (parts.length < 4) return "Medição indisponível";
        long total, available;
        try {
            total = Long.parseLong(parts[1]);
            available = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) { return "Medição inválida"; }
        if (total <= 0 || available < 0) return "Medição inválida";
        if (available >= total / 5) return "Cache preservado; espaço suficiente";
        long requestKb = Math.min(total / 5, available + 512L * 1024L);
        if (requestKb <= available) return "Cache preservado";
        String result = shell.exec("pm trim-caches " + requestKb + "K 2>&1");
        if (result != null && (result.contains("Error") || result.contains("Exception")
                || result.contains("SecurityException"))) return "Falha: " + result;
        return "Solicitação de limpeza limitada a 512 MiB extras";
    }
}

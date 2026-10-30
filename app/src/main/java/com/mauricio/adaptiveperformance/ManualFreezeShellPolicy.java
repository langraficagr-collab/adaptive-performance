package com.mauricio.adaptiveperformance;

/** Trust only an actual privileged Shizuku service: Android ADB shell or root. */
public final class ManualFreezeShellPolicy {
    private ManualFreezeShellPolicy() { }
    public static boolean trustedUid(int uid) { return uid == 2000 || uid == 0; }
    public static boolean safePackage(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }
    /** ADB pidof must return no processes after a successful force-stop. */
    public static boolean stopped(String output) {
        return output != null && !output.contains("[timeout]")
                && !output.toLowerCase(java.util.Locale.ROOT).contains("permission denied")
                && output.trim().isEmpty();
    }
    public static String method(int uid) {
        return uid == 2000 ? "Shizuku · ADB shell (UID 2000)"
                : uid == 0 ? "Shizuku · root (UID 0)" : "Shizuku/ADB shell indisponível";
    }
}

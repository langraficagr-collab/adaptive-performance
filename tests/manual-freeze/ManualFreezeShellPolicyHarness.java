import com.mauricio.adaptiveperformance.ManualFreezeShellPolicy;
public final class ManualFreezeShellPolicyHarness {
    private static void check(boolean pass, String label) {
        if (!pass) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    public static void main(String[] args) {
        check(ManualFreezeShellPolicy.trustedUid(2000), "ADB shell uid accepted");
        check(ManualFreezeShellPolicy.trustedUid(0), "Shizuku root uid accepted");
        check(!ManualFreezeShellPolicy.trustedUid(10334), "normal application uid blocked");
        check(!ManualFreezeShellPolicy.trustedUid(-1), "missing bridge blocked");
        check(ManualFreezeShellPolicy.safePackage("com.facebook.vibes"), "valid package accepted");
        check(!ManualFreezeShellPolicy.safePackage("com.foo;reboot"), "shell injection denied");
        check(!ManualFreezeShellPolicy.safePackage("com.foo bar"), "whitespace denied");
        check(ManualFreezeShellPolicy.stopped("\n"), "empty PID list confirms stop");
        check(!ManualFreezeShellPolicy.stopped("1234\n"), "active PID rejects stop");
        check(!ManualFreezeShellPolicy.stopped("[timeout]"), "timeout rejects stop");
        check(!ManualFreezeShellPolicy.stopped(null), "missing shell output rejects stop");
        check(ManualFreezeShellPolicy.method(2000).contains("ADB shell"), "correct backend reporting");
    }
}

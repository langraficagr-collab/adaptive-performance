import com.mauricio.adaptiveperformance.ManualFreezeRecoveryPolicy;
public final class ManualFreezeRecoveryHarness {
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    public static void main(String[] args) {
        check(!ManualFreezeRecoveryPolicy.restore("default", () -> false, () -> "ignore"),
                "failed shell command must preserve state");
        check(!ManualFreezeRecoveryPolicy.restore("default", () -> true, () -> "ignore"),
                "exit zero but unchanged state must preserve state");
        check(ManualFreezeRecoveryPolicy.restore("default", () -> false, () -> "default"),
                "idempotent already restored state");
        check(ManualFreezeRecoveryPolicy.restore("allow", () -> true, () -> "allow"),
                "successful restore confirms state");
        check(!ManualFreezeRecoveryPolicy.restore("10", () -> {throw new Exception("Shizuku disconnected");}, () -> "40"),
                "Shizuku exception must preserve state");
        check(!ManualFreezeRecoveryPolicy.restore("10", () -> true, () -> ""),
                "missing readback must preserve original bucket");
        check(!ManualFreezeRecoveryPolicy.restore("10", () -> true, () -> "100"),
                "unexpected readback must preserve original bucket");
        check(!ManualFreezeRecoveryPolicy.commandOnly(() -> false),
                "CPU restriction failure does not release ownership");
        check(ManualFreezeRecoveryPolicy.commandOnly(() -> true),
                "successful CPU restore can release ownership");
    }
}

import com.mauricio.adaptiveperformance.MemoryLeakEpisodeCounter;
public final class MemoryLeakCounterHarness {
    static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
        System.out.println("PASS " + reason);
    }
    public static void main(String[] args) {
        long m = 60_000L, t = 1_000_000L;
        check(MemoryLeakEpisodeCounter.count(0, 0, t) == 1, "one incident is insufficient");
        check(MemoryLeakEpisodeCounter.count(1, t, t + 2*m) == -1, "same episode is deduplicated");
        check(MemoryLeakEpisodeCounter.count(1, t, t + 20*m) == 2, "two independent episodes qualify");
        check(MemoryLeakEpisodeCounter.count(2, t, t + 30*m) == 2, "bounded count");
        check(MemoryLeakEpisodeCounter.count(2, t, t + 24*60*m) == 1, "reset after one day");
        check(MemoryLeakEpisodeCounter.count(1, t, t-m) == 1, "clock reset safe");
    }
}

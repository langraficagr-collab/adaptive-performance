package android.os;
public final class SystemClock {
    public static long wall;
    public static long elapsedRealtime() { return wall; }
}

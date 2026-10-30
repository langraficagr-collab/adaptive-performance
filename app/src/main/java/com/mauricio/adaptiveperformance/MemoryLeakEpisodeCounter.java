package com.mauricio.adaptiveperformance;

/** Pure timing decisions; every episode represents a new sustained PSS growth window. */
public final class MemoryLeakEpisodeCounter {
    private MemoryLeakEpisodeCounter() { }
    private static final long MIN_SEPARATION = 20L * 60_000L;
    private static final long MAX_SEPARATION = 24L * 60L * 60_000L;

    /** Return -1 for a duplicate, 1 for the first/new day, or 2 for recurrence. */
    public static int count(int previous, long previousAt, long now) {
        if (previousAt <= 0L || now < previousAt || now - previousAt >= MAX_SEPARATION) return 1;
        if (now - previousAt < MIN_SEPARATION) return -1;
        return Math.min(2, Math.max(0, previous) + 1);
    }
}

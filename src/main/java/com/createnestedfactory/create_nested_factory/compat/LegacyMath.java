package com.createnestedfactory.create_nested_factory.compat;

/** Java 17 clamps keep long resource counts exact; Mth has no long overload in 1.20.1. */
public final class LegacyMath {
    private LegacyMath() {}
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
    public static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(value, max));
    }
}

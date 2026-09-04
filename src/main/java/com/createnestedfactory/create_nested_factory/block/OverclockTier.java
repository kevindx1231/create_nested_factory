package com.createnestedfactory.create_nested_factory.block;

public enum OverclockTier {
    NORMAL(0, 1.0f),
    HALF(1, 0.5f),
    DOUBLE(2, 2.0f),
    TRIPLE(3, 3.0f),
    QUADRUPLE(4, 4.0f),
    QUINTUPLE(5, 5.0f);

    private final int id;
    private final float multiplier;

    OverclockTier(int id, float multiplier) {
        this.id = id;
        this.multiplier = multiplier;
    }

    public int id() {
        return id;
    }

    public float multiplier() {
        return multiplier;
    }

    public boolean unlockedBy(int batteries) {
        if (batteries <= 0) {
            return this == NORMAL;
        }
        if (this == NORMAL) {
            return false;
        }
        if (this == HALF || this == DOUBLE) {
            return true;
        }
        return id <= batteries + 1;
    }

    public static OverclockTier highestForBatteries(int batteries) {
        return switch (Math.max(0, Math.min(4, batteries))) {
            case 0 -> NORMAL;
            case 1 -> DOUBLE;
            case 2 -> TRIPLE;
            case 3 -> QUADRUPLE;
            default -> QUINTUPLE;
        };
    }

    public static OverclockTier normalizeSelection(OverclockTier selected, int batteries) {
        if (batteries <= 0) {
            return NORMAL;
        }
        if (selected == null || selected == NORMAL) {
            return DOUBLE;
        }
        return selected.unlockedBy(batteries) ? selected : highestForBatteries(batteries);
    }

    public static OverclockTier byId(int id) {
        for (OverclockTier tier : values()) {
            if (tier.id == id) {
                return tier;
            }
        }
        return NORMAL;
    }
}

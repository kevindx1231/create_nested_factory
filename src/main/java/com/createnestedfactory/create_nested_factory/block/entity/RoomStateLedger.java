package com.createnestedfactory.create_nested_factory.block.entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Stable learning baseline for finite room resources and tool durability. */
public final class RoomStateLedger {
    private final Map<ItemVariant, Long> items = new HashMap<>();
    private final Map<FluidVariant, Long> fluids = new HashMap<>();
    private final Map<ItemVariant, Long> toolDurability = new HashMap<>();

    public void capture(Map<ItemVariant, Long> itemSnapshot, Map<FluidVariant, Long> fluidSnapshot,
                        Map<ItemVariant, Long> toolSnapshot) {
        clear();
        if (itemSnapshot != null) items.putAll(itemSnapshot);
        if (fluidSnapshot != null) fluids.putAll(fluidSnapshot);
        if (toolSnapshot != null) toolDurability.putAll(toolSnapshot);
    }

    /**
     * Signed correction for material which crossed the room boundary while a single continuous
     * observation was running. Positive values were consumed from the starting room buffer;
     * negative values accumulated in that buffer. Keeping the sign makes intermediate window
     * fluctuations cancel instead of charging every temporary depletion as another input.
     */
    public Map<ItemVariant, Long> itemBalanceAdjustments(Map<ItemVariant, Long> current) {
        return signedDifference(items, current);
    }

    public Map<FluidVariant, Long> fluidBalanceAdjustments(Map<FluidVariant, Long> current) {
        return signedDifference(fluids, current);
    }

    public Map<ItemVariant, Long> consumedToolDurability(Map<ItemVariant, Long> current) {
        return positiveDifference(toolDurability, current);
    }

    public void clear() {
        items.clear();
        fluids.clear();
        toolDurability.clear();
    }

    public String debugSummary() {
        return "items=" + items + ", fluids=" + fluids + ", toolDurability=" + toolDurability;
    }

    private static <K> Map<K, Long> positiveDifference(Map<K, Long> baseline, Map<K, Long> current) {
        Map<K, Long> result = new HashMap<>();
        baseline.forEach((key, initial) -> {
            long remaining = current == null ? 0L : current.getOrDefault(key, 0L);
            long depleted = initial - remaining;
            if (depleted > 0L) result.put(key, depleted);
        });
        return result;
    }

    private static <K> Map<K, Long> signedDifference(Map<K, Long> baseline, Map<K, Long> current) {
        Map<K, Long> result = new HashMap<>();
        Set<K> keys = new HashSet<>(baseline.keySet());
        if (current != null) keys.addAll(current.keySet());
        for (K key : keys) {
            long initial = baseline.getOrDefault(key, 0L);
            long remaining = current == null ? 0L : current.getOrDefault(key, 0L);
            long difference = Math.subtractExact(initial, remaining);
            if (difference != 0L) result.put(key, difference);
        }
        return result;
    }
}

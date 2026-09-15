package com.createnestedfactory.create_nested_factory.block.entity;

import java.util.Map;

/** Applies whole-session room inventory changes without confusing products with recipe inputs. */
final class RoomBalanceReconciler {
    private RoomBalanceReconciler() {
    }

    /**
     * A positive adjustment means the room held less of a resource at the end of learning.
     * Depleted input or hidden material is a real additional cost. Depleted output-only material
     * is startup backlog which crossed the output boundary and must be removed from learned output.
     *
     * <p>A negative adjustment is inventory accumulated during the observation. Boundary transfer
     * remains authoritative in that case: accumulation cannot refund an input or mint an output.
     */
    static <K> void reconcile(Map<K, Long> inputs, Map<K, Long> outputs,
                              Map<K, Long> roomAdjustments) {
        if (inputs == null || outputs == null || roomAdjustments == null) return;
        roomAdjustments.forEach((key, adjustment) -> {
            if (key == null || adjustment == null || adjustment <= 0L) return;
            boolean boundaryInput = inputs.getOrDefault(key, 0L) > 0L;
            boolean boundaryOutput = outputs.getOrDefault(key, 0L) > 0L;
            if (boundaryOutput && !boundaryInput) {
                subtract(outputs, key, adjustment);
                return;
            }
            inputs.merge(key, adjustment, Math::addExact);
        });
    }

    private static <K> void subtract(Map<K, Long> values, K key, long amount) {
        long remaining = values.getOrDefault(key, 0L) - amount;
        if (remaining > 0L) values.put(key, remaining);
        else values.remove(key);
    }
}

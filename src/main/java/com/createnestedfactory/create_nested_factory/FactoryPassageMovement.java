package com.createnestedfactory.create_nested_factory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/** Batch-scoped suppression of destruction and registration while a carrier copies its blocks. */
public final class FactoryPassageMovement {
    private record Address(Level level, BlockPos pos) {
        private Address { pos = pos.immutable(); }
    }

    private static final ThreadLocal<Set<Address>> MOVING = new ThreadLocal<>();

    private FactoryPassageMovement() {}

    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    public static boolean isMoving(Level level, BlockPos pos) {
        Set<Address> current = MOVING.get();
        return current != null && current.contains(new Address(level, pos));
    }

    public static Scope begin(Level source, Iterable<BlockPos> oldPositions,
                              Level target, Iterable<BlockPos> newPositions) {
        Set<Address> added = new HashSet<>();
        oldPositions.forEach(pos -> added.add(new Address(source, pos)));
        newPositions.forEach(pos -> added.add(new Address(target, pos)));
        Set<Address> active = MOVING.get();
        if (active == null) {
            active = new HashSet<>();
            MOVING.set(active);
        }
        added.removeAll(active);
        active.addAll(added);
        Set<Address> batch = active;
        return () -> {
            batch.removeAll(added);
            if (batch.isEmpty()) MOVING.remove();
        };
    }
}

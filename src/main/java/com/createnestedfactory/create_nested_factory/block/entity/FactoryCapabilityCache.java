package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Per-block, non-persistent optional handlers. Native capability invalidation clears them. */
public final class FactoryCapabilityCache {
    private record Key(BlockCapability<?, ?> capability, Direction side) {}
    private record Entry(Object routing, Object handler) {}
    private final Map<Key, Entry> entries = new HashMap<>();
    private ServerLevel level;
    private BlockPos pos;
    private long binding;
    // Keep our listener alive; NeoForge's level stores listeners weakly.
    private ICapabilityInvalidationListener listener;

    public <T> T get(BlockEntity owner, BlockCapability<T, Direction> capability, Direction side,
                     Object routing, Supplier<T> create) {
        if (owner.isRemoved()) {
            entries.clear();
            listener = null;
            binding++;
            return null;
        }
        if (side == null || !(owner.getLevel() instanceof ServerLevel currentLevel)) return null;
        if (listener == null || currentLevel != level || !owner.getBlockPos().equals(pos)) {
            entries.clear();
            level = currentLevel;
            pos = owner.getBlockPos().immutable();
            long currentBinding = ++binding;
            ServerLevel boundLevel = level;
            BlockPos boundPos = pos;
            listener = () -> {
                if (binding != currentBinding) return false;
                entries.clear();
                boolean valid = !owner.isRemoved() && owner.getLevel() == boundLevel && owner.getBlockPos().equals(boundPos);
                if (!valid) listener = null;
                return valid;
            };
            level.registerCapabilityListener(pos, listener);
        }
        Key key = new Key(capability, side);
        Entry entry = entries.get(key);
        if (entry != null && Objects.equals(entry.routing(), routing)) {
            @SuppressWarnings("unchecked") T handler = (T) entry.handler();
            return handler;
        }
        entries.remove(key);
        T handler = create.get();
        // Null availability can change when an endpoint appears without a routing change.
        if (handler != null) entries.put(key, new Entry(routing, handler));
        return handler;
    }
}

package com.createnestedfactory.create_nested_factory;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PocketRegistry {
    public record FactoryLocation(ResourceKey<Level> dimension, BlockPos pos) {}

    private record PortKey(BlockPos roomOrigin, int portId) {}

    private static final Map<BlockPos, FactoryLocation> FACTORIES = new ConcurrentHashMap<>();
    private static final Map<PortKey, BlockPos> PORTS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Set<BlockPos>> STRESS_PORTS = new ConcurrentHashMap<>();

    public static void register(BlockPos roomOrigin, FactoryLocation location) {
        FACTORIES.put(roomOrigin, location);
    }

    public static void unregister(BlockPos roomOrigin) {
        FACTORIES.remove(roomOrigin);
    }

    public static FactoryLocation get(BlockPos roomOrigin) {
        return FACTORIES.get(roomOrigin);
    }

    public static void registerPort(BlockPos roomOrigin, int portId, BlockPos portPos) {
        PORTS.put(new PortKey(roomOrigin, portId), portPos);
    }

    public static void unregisterPort(BlockPos roomOrigin, int portId) {
        PORTS.remove(new PortKey(roomOrigin, portId));
    }

    public static BlockPos getPort(BlockPos roomOrigin, int portId) {
        return PORTS.get(new PortKey(roomOrigin, portId));
    }

    public static void registerStressPort(BlockPos roomOrigin, BlockPos portPos) {
        STRESS_PORTS.computeIfAbsent(roomOrigin, k -> ConcurrentHashMap.newKeySet()).add(portPos);
    }

    public static void unregisterStressPort(BlockPos roomOrigin, BlockPos portPos) {
        Set<BlockPos> ports = STRESS_PORTS.get(roomOrigin);
        if (ports == null) {
            return;
        }
        ports.remove(portPos);
        if (ports.isEmpty()) {
            STRESS_PORTS.remove(roomOrigin);
        }
    }

    public static Set<BlockPos> getStressPorts(BlockPos roomOrigin) {
        Set<BlockPos> ports = STRESS_PORTS.get(roomOrigin);
        return ports == null ? Set.of() : ports;
    }
}

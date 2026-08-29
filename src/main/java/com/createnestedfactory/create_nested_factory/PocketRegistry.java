package com.createnestedfactory.create_nested_factory;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PocketRegistry {
    public record FactoryLocation(ResourceKey<Level> dimension, BlockPos pos) {}
    public record NestedSlot(int id, int slotX, int slotZ, FactoryLocation location) {}

    private record PortKey(BlockPos roomOrigin, int portId) {}
    private record SlotKey(int slotX, int slotZ) {}

    public static final int SLOT_GRID_WIDTH = 1024;
    public static final int ROOT_REGION_SIZE = 256;

    private static final Map<BlockPos, FactoryLocation> FACTORIES = new ConcurrentHashMap<>();
    private static final Map<String, Set<BlockPos>> ROOT_REGIONS = new ConcurrentHashMap<>();
    private static final Map<SlotKey, NestedSlot> NESTED_SLOTS = new ConcurrentHashMap<>();
    private static final Map<Integer, SlotKey> SLOT_KEYS_BY_ID = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_SLOT_ID = new AtomicInteger();
    private static final Map<PortKey, BlockPos> PORTS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Set<BlockPos>> STRESS_PORTS = new ConcurrentHashMap<>();

    public static void register(BlockPos roomOrigin, FactoryLocation location) {
        registerRoot(roomOrigin, location);
    }

    public static void registerRoot(BlockPos roomOrigin, FactoryLocation location) {
        FACTORIES.put(roomOrigin, location);
        for (String region : rootRegionsForOrigin(roomOrigin)) {
            ROOT_REGIONS.computeIfAbsent(region, k -> ConcurrentHashMap.newKeySet()).add(roomOrigin);
        }
    }

    public static void unregister(BlockPos roomOrigin) {
        unregisterRoot(roomOrigin);
    }

    public static void unregisterRoot(BlockPos roomOrigin) {
        FACTORIES.remove(roomOrigin);
        for (String region : rootRegionsForOrigin(roomOrigin)) {
            Set<BlockPos> origins = ROOT_REGIONS.get(region);
            if (origins != null) {
                origins.remove(roomOrigin);
                if (origins.isEmpty()) {
                    ROOT_REGIONS.remove(region);
                }
            }
        }
    }

    public static FactoryLocation get(BlockPos roomOrigin) {
        return FACTORIES.get(roomOrigin);
    }

    public static Set<BlockPos> getRootOriginsInRegion(int regionX, int regionZ) {
        Set<BlockPos> origins = ROOT_REGIONS.get(regionKey(regionX, regionZ));
        return origins == null ? Set.of() : origins;
    }

    public static NestedSlot allocateAndRegisterNestedSlot(FactoryLocation location, ServerLevel level) {
        NestedFactorySaveData saveData = NestedFactorySaveData.get(level.getServer());
        int id = saveData.allocateSlotId();
        return registerNestedSlot(id, location, level);
    }

    public static NestedSlot registerNestedSlot(int slotId, FactoryLocation location, ServerLevel level) {
        int slotX = slotXForId(slotId);
        int slotZ = slotZForId(slotId);
        SlotKey key = new SlotKey(slotX, slotZ);
        NestedSlot slot = new NestedSlot(slotId, slotX, slotZ, location);
        NESTED_SLOTS.put(key, slot);
        SLOT_KEYS_BY_ID.put(slotId, key);
        NestedFactorySaveData.get(level.getServer()).observeSlotId(slotId);
        NEXT_SLOT_ID.updateAndGet(current -> Math.max(current, slotId + 1));
        return slot;
    }

    public static NestedSlot getNestedSlot(int slotX, int slotZ) {
        return NESTED_SLOTS.get(new SlotKey(slotX, slotZ));
    }

    public static NestedSlot getNestedSlotById(int slotId) {
        SlotKey key = SLOT_KEYS_BY_ID.get(slotId);
        return key == null ? null : NESTED_SLOTS.get(key);
    }

    public static void unregisterNestedSlot(int slotId) {
        SlotKey key = SLOT_KEYS_BY_ID.remove(slotId);
        if (key != null) {
            NESTED_SLOTS.remove(key);
        }
    }

    private static int slotXForId(int slotId) {
        return slotId % SLOT_GRID_WIDTH;
    }

    private static int slotZForId(int slotId) {
        return slotId / SLOT_GRID_WIDTH;
    }

    private static Set<String> rootRegionsForOrigin(BlockPos origin) {
        // 根工厂最多向每个方向扩展一个区块，登记相邻区域可避免查询跨区域边界时漏掉候选。
        int minRegionX = Math.floorDiv(origin.getX() - 16, ROOT_REGION_SIZE);
        int maxRegionX = Math.floorDiv(origin.getX() + 47, ROOT_REGION_SIZE);
        int minRegionZ = Math.floorDiv(origin.getZ() - 16, ROOT_REGION_SIZE);
        int maxRegionZ = Math.floorDiv(origin.getZ() + 47, ROOT_REGION_SIZE);
        Set<String> regions = new HashSet<>();
        for (int x = minRegionX; x <= maxRegionX; x++) {
            for (int z = minRegionZ; z <= maxRegionZ; z++) {
                regions.add(regionKey(x, z));
            }
        }
        return regions;
    }

    private static String regionKey(int regionX, int regionZ) {
        return regionX + ":" + regionZ;
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

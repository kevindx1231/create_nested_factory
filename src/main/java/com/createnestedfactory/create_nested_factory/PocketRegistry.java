package com.createnestedfactory.create_nested_factory;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PocketRegistry {
    public record FactoryLocation(String factoryId, ResourceKey<Level> dimension, BlockPos pos) {
        public FactoryLocation {
            pos = pos.immutable();
        }
    }
    public record NestedSlot(int id, int slotX, int slotZ, FactoryLocation location) {}

    private record PortKey(BlockPos roomOrigin, int portId) {}
    private record SlotKey(int slotX, int slotZ) {}

    public static final int SLOT_GRID_WIDTH = 1024;
    public static final int ROOT_REGION_SIZE = 256;

    private static final Map<BlockPos, FactoryLocation> FACTORIES = new ConcurrentHashMap<>();
    private static final Map<String, Set<BlockPos>> ROOT_REGIONS = new ConcurrentHashMap<>();
    private static final Map<SlotKey, NestedSlot> NESTED_SLOTS = new ConcurrentHashMap<>();
    private static final Map<Integer, SlotKey> SLOT_KEYS_BY_ID = new ConcurrentHashMap<>();
    private static final Map<PortKey, Set<BlockPos>> PORTS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Set<BlockPos>> STRESS_PORTS = new ConcurrentHashMap<>();

    public static boolean register(BlockPos roomOrigin, FactoryLocation location) {
        return registerRoot(roomOrigin, location);
    }

    /**
     * Registers a root room without allowing a later factory to overwrite an existing owner.
     */
    public static boolean registerRoot(BlockPos roomOrigin, FactoryLocation location) {
        FactoryLocation existing = FACTORIES.putIfAbsent(roomOrigin, location);
        if (existing != null && !existing.equals(location)) {
            return false;
        }
        for (String region : rootRegionsForOrigin(roomOrigin)) {
            ROOT_REGIONS.computeIfAbsent(region, k -> ConcurrentHashMap.newKeySet()).add(roomOrigin);
        }
        return true;
    }

    public static void unregister(BlockPos roomOrigin, FactoryLocation expectedOwner) {
        unregisterRoot(roomOrigin, expectedOwner);
    }

    /**
     * Removes a root registration only when the caller still owns that origin.
     */
    public static void unregisterRoot(BlockPos roomOrigin, FactoryLocation expectedOwner) {
        if (!FACTORIES.remove(roomOrigin, expectedOwner)) {
            return;
        }
        for (String region : rootRegionsForOrigin(roomOrigin)) {
            Set<BlockPos> origins = ROOT_REGIONS.get(region);
            if (origins != null) {
                origins.remove(roomOrigin);
                if (origins.isEmpty()) {
                    ROOT_REGIONS.remove(region, origins);
                }
            }
        }
    }

    public static FactoryLocation get(BlockPos roomOrigin) {
        return FACTORIES.get(roomOrigin);
    }

    public static boolean isFactoryRegistered(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return false;
        }
        return FACTORIES.values().stream().anyMatch(location -> factoryId.equals(location.factoryId()))
                || NESTED_SLOTS.values().stream().anyMatch(slot -> factoryId.equals(slot.location().factoryId()));
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
        NestedSlot existing = NESTED_SLOTS.get(key);
        if (existing != null && !existing.location().equals(location)) {
            return null;
        }
        NestedSlot slot = new NestedSlot(slotId, slotX, slotZ, location);
        NESTED_SLOTS.put(key, slot);
        SLOT_KEYS_BY_ID.put(slotId, key);
        NestedFactorySaveData.get(level.getServer()).observeSlotId(slotId);
        return slot;
    }

    public static boolean canClaimNestedSlot(int slotId, FactoryLocation location) {
        NestedSlot existing = getNestedSlotById(slotId);
        return existing == null || existing.location().equals(location);
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
        return Math.floorMod(slotId, SLOT_GRID_WIDTH);
    }

    private static int slotZForId(int slotId) {
        return Math.floorDiv(slotId, SLOT_GRID_WIDTH);
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
        PORTS.computeIfAbsent(new PortKey(roomOrigin, portId), ignored -> ConcurrentHashMap.newKeySet())
                .add(portPos.immutable());
    }

    public static void unregisterPort(BlockPos roomOrigin, int portId, BlockPos portPos) {
        PortKey key = new PortKey(roomOrigin, portId);
        Set<BlockPos> ports = PORTS.get(key);
        if (ports == null) {
            return;
        }
        ports.remove(portPos);
        if (ports.isEmpty()) {
            PORTS.remove(key, ports);
        }
    }

    public static Set<BlockPos> getPorts(BlockPos roomOrigin, int portId) {
        Set<BlockPos> ports = PORTS.get(new PortKey(roomOrigin, portId));
        return ports == null ? Set.of() : Set.copyOf(ports);
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
            STRESS_PORTS.remove(roomOrigin, ports);
        }
    }

    public static Set<BlockPos> getStressPorts(BlockPos roomOrigin) {
        Set<BlockPos> ports = STRESS_PORTS.get(roomOrigin);
        return ports == null ? Set.of() : ports;
    }

    /** Removes all transient port registrations for a factory room that is being destroyed. */
    public static void clearRoomRegistrations(BlockPos roomOrigin) {
        PORTS.keySet().removeIf(key -> key.roomOrigin().equals(roomOrigin));
        STRESS_PORTS.remove(roomOrigin);
    }
}

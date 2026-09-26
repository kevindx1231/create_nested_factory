package com.createnestedfactory.create_nested_factory;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
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

    /** One transient registry per running server; persistent allocation remains in SavedData. */
    static final class RuntimeState {
        private final Map<BlockPos, FactoryLocation> factories = new ConcurrentHashMap<>();
        private final Map<String, Set<BlockPos>> rootRegions = new ConcurrentHashMap<>();
        private final Map<SlotKey, NestedSlot> nestedSlots = new ConcurrentHashMap<>();
        private final Map<Integer, SlotKey> slotKeysById = new ConcurrentHashMap<>();
        private final Map<PortKey, Set<BlockPos>> ports = new ConcurrentHashMap<>();
        private final Map<BlockPos, Set<BlockPos>> stressPorts = new ConcurrentHashMap<>();

        boolean registerRoot(BlockPos roomOrigin, FactoryLocation location) {
            FactoryLocation existing = factories.putIfAbsent(roomOrigin, location);
            if (existing != null) {
                if (!existing.factoryId().equals(location.factoryId())) return false;
                factories.replace(roomOrigin, existing, location);
            }
            for (String region : rootRegionsForOrigin(roomOrigin)) {
                rootRegions.computeIfAbsent(region, ignored -> ConcurrentHashMap.newKeySet()).add(roomOrigin);
            }
            return true;
        }
    }

    /** Generic only so the world-isolation invariant can be regression-tested without booting Minecraft. */
    static final class ServerStates<K> {
        private final Map<K, RuntimeState> states = new WeakHashMap<>();

        synchronized RuntimeState get(K server) {
            return states.computeIfAbsent(Objects.requireNonNull(server, "server"), ignored -> new RuntimeState());
        }

        synchronized void remove(K server) {
            if (server != null) states.remove(server);
        }
    }

    private static final ServerStates<MinecraftServer> SERVER_STATES = new ServerStates<>();

    private static RuntimeState state(MinecraftServer server) {
        return SERVER_STATES.get(server);
    }

    public static void releaseServer(MinecraftServer server) {
        SERVER_STATES.remove(server);
    }

    public static boolean register(MinecraftServer server, BlockPos roomOrigin, FactoryLocation location) {
        return registerRoot(server, roomOrigin, location);
    }

    /**
     * Registers a root room without allowing a later factory to overwrite an existing owner.
     */
    public static boolean registerRoot(MinecraftServer server, BlockPos roomOrigin, FactoryLocation location) {
        return state(server).registerRoot(roomOrigin, location);
    }

    public static void unregister(MinecraftServer server, BlockPos roomOrigin, FactoryLocation expectedOwner) {
        unregisterRoot(server, roomOrigin, expectedOwner);
    }

    /**
     * Removes a root registration only when the caller still owns that origin.
     */
    public static void unregisterRoot(MinecraftServer server, BlockPos roomOrigin, FactoryLocation expectedOwner) {
        RuntimeState state = state(server);
        if (!state.factories.remove(roomOrigin, expectedOwner)) {
            return;
        }
        for (String region : rootRegionsForOrigin(roomOrigin)) {
            Set<BlockPos> origins = state.rootRegions.get(region);
            if (origins != null) {
                origins.remove(roomOrigin);
                if (origins.isEmpty()) {
                    state.rootRegions.remove(region, origins);
                }
            }
        }
    }

    public static FactoryLocation get(MinecraftServer server, BlockPos roomOrigin) {
        return state(server).factories.get(roomOrigin);
    }

    public static boolean isFactoryRegistered(MinecraftServer server, String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return false;
        }
        RuntimeState state = state(server);
        return state.factories.values().stream().anyMatch(location -> factoryId.equals(location.factoryId()))
                || state.nestedSlots.values().stream().anyMatch(slot -> factoryId.equals(slot.location().factoryId()));
    }

    public static FactoryLocation findFactoryLocationById(MinecraftServer server, String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return null;
        }
        RuntimeState state = state(server);
        for (FactoryLocation location : state.factories.values()) {
            if (factoryId.equals(location.factoryId())) {
                return location;
            }
        }
        for (NestedSlot slot : state.nestedSlots.values()) {
            if (factoryId.equals(slot.location().factoryId())) {
                return slot.location();
            }
        }
        return null;
    }

    public static Set<BlockPos> getRootOriginsInRegion(MinecraftServer server, int regionX, int regionZ) {
        Set<BlockPos> origins = state(server).rootRegions.get(regionKey(regionX, regionZ));
        return origins == null ? Set.of() : origins;
    }

    public static NestedSlot allocateAndRegisterNestedSlot(FactoryLocation location, ServerLevel level) {
        NestedFactorySaveData saveData = NestedFactorySaveData.get(level.getServer());
        int id = saveData.allocateSlotId();
        return registerNestedSlot(id, location, level);
    }

    public static NestedSlot registerNestedSlot(int slotId, FactoryLocation location, ServerLevel level) {
        RuntimeState state = state(level.getServer());
        int slotX = slotXForId(slotId);
        int slotZ = slotZForId(slotId);
        SlotKey key = new SlotKey(slotX, slotZ);
        NestedSlot existing = state.nestedSlots.get(key);
        if (existing != null) {
            if (!existing.location().factoryId().equals(location.factoryId())) {
                return null;
            }
            location = new FactoryLocation(existing.location().factoryId(), location.dimension(), location.pos());
        }
        NestedSlot slot = new NestedSlot(slotId, slotX, slotZ, location);
        state.nestedSlots.put(key, slot);
        state.slotKeysById.put(slotId, key);
        NestedFactorySaveData.get(level.getServer()).observeSlotId(slotId);
        return slot;
    }

    public static boolean canClaimNestedSlot(MinecraftServer server, int slotId, FactoryLocation location) {
        NestedSlot existing = getNestedSlotById(server, slotId);
        return existing == null || existing.location().equals(location);
    }

    public static NestedSlot getNestedSlot(MinecraftServer server, int slotX, int slotZ) {
        return state(server).nestedSlots.get(new SlotKey(slotX, slotZ));
    }

    public static NestedSlot getNestedSlotById(MinecraftServer server, int slotId) {
        RuntimeState state = state(server);
        SlotKey key = state.slotKeysById.get(slotId);
        return key == null ? null : state.nestedSlots.get(key);
    }

    public static void unregisterNestedSlot(MinecraftServer server, int slotId) {
        RuntimeState state = state(server);
        SlotKey key = state.slotKeysById.remove(slotId);
        if (key != null) {
            state.nestedSlots.remove(key);
        }
    }

    public static void unregisterNestedSlot(MinecraftServer server, int slotId, FactoryLocation expectedOwner) {
        RuntimeState state = state(server);
        SlotKey key = state.slotKeysById.get(slotId);
        if (key == null) {
            return;
        }
        NestedSlot existing = state.nestedSlots.get(key);
        if (existing != null && existing.location().equals(expectedOwner)) {
            if (state.nestedSlots.remove(key, existing)) {
                state.slotKeysById.remove(slotId, key);
            }
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

    public static void registerPort(MinecraftServer server, BlockPos roomOrigin, int portId, BlockPos portPos) {
        state(server).ports.computeIfAbsent(new PortKey(roomOrigin, portId), ignored -> ConcurrentHashMap.newKeySet())
                .add(portPos.immutable());
    }

    public static void unregisterPort(MinecraftServer server, BlockPos roomOrigin, int portId, BlockPos portPos) {
        RuntimeState state = state(server);
        PortKey key = new PortKey(roomOrigin, portId);
        Set<BlockPos> ports = state.ports.get(key);
        if (ports == null) {
            return;
        }
        ports.remove(portPos);
        if (ports.isEmpty()) {
            state.ports.remove(key, ports);
        }
    }

    public static Set<BlockPos> getPorts(MinecraftServer server, BlockPos roomOrigin, int portId) {
        Set<BlockPos> ports = state(server).ports.get(new PortKey(roomOrigin, portId));
        return ports == null ? Set.of() : Set.copyOf(ports);
    }

    public static void registerStressPort(MinecraftServer server, BlockPos roomOrigin, BlockPos portPos) {
        state(server).stressPorts.computeIfAbsent(roomOrigin, k -> ConcurrentHashMap.newKeySet())
                .add(portPos.immutable());
    }

    public static void unregisterStressPort(MinecraftServer server, BlockPos roomOrigin, BlockPos portPos) {
        RuntimeState state = state(server);
        Set<BlockPos> ports = state.stressPorts.get(roomOrigin);
        if (ports == null) {
            return;
        }
        ports.remove(portPos);
        if (ports.isEmpty()) {
            state.stressPorts.remove(roomOrigin, ports);
        }
    }

    public static Set<BlockPos> getStressPorts(MinecraftServer server, BlockPos roomOrigin) {
        Set<BlockPos> ports = state(server).stressPorts.get(roomOrigin);
        return ports == null ? Set.of() : ports;
    }

    /** Removes all transient port registrations for a factory room that is being destroyed. */
    public static void clearRoomRegistrations(MinecraftServer server, BlockPos roomOrigin) {
        RuntimeState state = state(server);
        state.ports.keySet().removeIf(key -> key.roomOrigin().equals(roomOrigin));
        state.stressPorts.remove(roomOrigin);
    }
}

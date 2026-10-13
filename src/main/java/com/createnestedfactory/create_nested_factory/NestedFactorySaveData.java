package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * World-level persistent allocation data for factory pocket rooms.
 *
 * <p>Nested rooms use a sequential slot id. Root rooms additionally keep an owner-to-origin
 * reservation so their identity cannot be derived from only the source X/Z coordinates.</p>
 */
public final class NestedFactorySaveData extends SavedData {
    private static final String DATA_NAME = "create_nested_factory_slots";
    private static final int NO_ROOT_SLOT_ID = -1;
    private static final int DATA_FORMAT = 2;

    public record RootAllocation(int slotId, BlockPos roomOrigin) {
        public RootAllocation {
            roomOrigin = roomOrigin.immutable();
        }
    }

    private int nextSlotId;
    private int nextRootSlotId;
    /** Only completed command deletions place slots in these persistent reuse pools. */
    private final TreeSet<Integer> freeNestedSlots = new TreeSet<>();
    private final TreeSet<Integer> freeRootSlots = new TreeSet<>();
    private final TreeSet<BlockPos> freeLegacyRootOrigins = new TreeSet<>();
    private final Map<String, RootAllocation> rootAllocations = new HashMap<>();
    private final Map<BlockPos, String> rootOwnersByOrigin = new HashMap<>();
    /** Root factory identities whose complete nested tree is forbidden from running physically. */
    private final Set<String> frozenRootFactoryIds = new HashSet<>();
    private final Map<String, String> parentByFactoryId = new HashMap<>();

    public static NestedFactorySaveData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                tag -> NestedFactorySaveData.load(tag, null), NestedFactorySaveData::new,
                DATA_NAME);
    }

    public synchronized int allocateSlotId() {
        int id = freeNestedSlots.isEmpty() ? nextSlotId++ : freeNestedSlots.pollFirst();
        setDirty();
        return id;
    }

    public synchronized void observeSlotId(int slotId) {
        if (freeNestedSlots.remove(slotId)) setDirty();
        if (slotId >= nextSlotId) {
            nextSlotId = slotId + 1;
            setDirty();
        }
    }

    /** Returns the one persistent room allocation for a root factory ID. */
    public synchronized RootAllocation claimRootAllocation(String factoryId, int persistedSlotId,
                                                             BlockPos persistedOrigin) {
        RootAllocation existing = rootAllocations.get(factoryId);
        if (existing != null) {
            return existing;
        }

        RootAllocation persisted = reserveIfAvailable(factoryId, persistedSlotId, persistedOrigin);
        if (persisted != null) {
            return persisted;
        }

        while (!freeLegacyRootOrigins.isEmpty()) {
            RootAllocation allocation = reserveIfAvailable(factoryId, NO_ROOT_SLOT_ID, freeLegacyRootOrigins.pollFirst());
            if (allocation != null) return allocation;
        }

        while (true) {
            int slotId = freeRootSlots.isEmpty() ? nextRootSlotId++ : freeRootSlots.pollFirst();
            BlockPos origin = NestedFactoryBlock.getRootRoomOrigin(slotId);
            RootAllocation allocation = reserveIfAvailable(factoryId, slotId, origin);
            if (allocation != null) {
                return allocation;
            }
        }
    }

    public synchronized void releaseRootAllocation(String factoryId) {
        RootAllocation allocation = rootAllocations.remove(factoryId);
        if (allocation != null) {
            rootOwnersByOrigin.remove(allocation.roomOrigin(), factoryId);
            setDirty();
        }
    }

    /** The caller must wait for actual room cleanup, rather than merely unregistering its block. */
    public synchronized void recycleCommandSlot(boolean nested, int slotId, BlockPos origin) {
        if (origin == null) return;
        if (nested) {
            if (slotId >= 0 && slotId < nextSlotId && freeNestedSlots.add(slotId)) setDirty();
        } else if (!rootOwnersByOrigin.containsKey(origin)) {
            if (slotId >= 0 && slotId < nextRootSlotId
                    && NestedFactoryBlock.getRootRoomOrigin(slotId).equals(origin)) {
                if (freeRootSlots.add(slotId)) setDirty();
            } else if (freeLegacyRootOrigins.add(origin)) setDirty();
        }
    }

    /** Includes unloaded and portable descendants already known to the allocation data. */
    public synchronized List<String> descendantFactoryIds(String factoryId) {
        Set<String> visited = new HashSet<>();
        visited.add(factoryId);
        boolean changed;
        do {
            changed = false;
            for (var entry : parentByFactoryId.entrySet()) {
                if (visited.contains(entry.getValue()) && visited.add(entry.getKey())) changed = true;
            }
        } while (changed);
        visited.remove(factoryId);
        return visited.stream().sorted().toList();
    }

    public synchronized void acquireFreezeLease(String rootFactoryId) {
        if (rootFactoryId != null && !rootFactoryId.isBlank() && frozenRootFactoryIds.add(rootFactoryId)) {
            setDirty();
        }
    }

    public synchronized void releaseFreezeLease(String rootFactoryId) {
        if (rootFactoryId != null && frozenRootFactoryIds.remove(rootFactoryId)) {
            setDirty();
        }
    }

    public synchronized void observeFactoryParent(String factoryId, String parentFactoryId) {
        if (factoryId == null || factoryId.isBlank()) return;
        String normalizedParent = parentFactoryId == null ? "" : parentFactoryId;
        if (!normalizedParent.equals(parentByFactoryId.put(factoryId, normalizedParent))) setDirty();
    }

    public synchronized void forgetFactoryParent(String factoryId) {
        if (factoryId != null && !factoryId.isBlank() && parentByFactoryId.remove(factoryId) != null) {
            setDirty();
        }
    }

    public synchronized boolean hasFreezeLeaseInAncestry(String factoryId) {
        Set<String> visited = new HashSet<>();
        String current = factoryId;
        while (current != null && !current.isBlank() && visited.add(current)) {
            if (frozenRootFactoryIds.contains(current)) return true;
            current = parentByFactoryId.get(current);
        }
        return false;
    }

    public synchronized boolean hasFreezeLeaseInAncestors(String factoryId) {
        return factoryId != null && hasFreezeLeaseInAncestry(parentByFactoryId.get(factoryId));
    }

    private RootAllocation reserveIfAvailable(String factoryId, int slotId, BlockPos origin) {
        if (origin == null) {
            return null;
        }
        BlockPos immutableOrigin = origin.immutable();
        String existingOwner = rootOwnersByOrigin.get(immutableOrigin);
        if (existingOwner != null && !existingOwner.equals(factoryId)) {
            return null;
        }

        RootAllocation allocation = new RootAllocation(slotId, immutableOrigin);
        rootAllocations.put(factoryId, allocation);
        rootOwnersByOrigin.put(immutableOrigin, factoryId);
        freeRootSlots.remove(slotId);
        freeLegacyRootOrigins.remove(immutableOrigin);
        if (slotId >= nextRootSlotId) {
            nextRootSlotId = slotId + 1;
        }
        setDirty();
        return allocation;
    }

    public static NestedFactorySaveData load(CompoundTag tag, HolderLookup.Provider registries) {
        NestedFactorySaveData data = new NestedFactorySaveData();
        if (tag.getInt("Format") != DATA_FORMAT) {
            return data;
        }
        data.nextSlotId = tag.getInt("NextSlotId");
        data.nextRootSlotId = tag.getInt("NextRootSlotId");

        for (Tag entryTag : tag.getList("RootAllocations", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) entryTag;
            String factoryId = entry.getString("FactoryId");
            if (factoryId.isBlank() || !entry.contains("RoomOrigin")) {
                continue;
            }
            try {
                BlockPos origin = BlockPos.of(entry.getLong("RoomOrigin"));
                if (data.rootAllocations.containsKey(factoryId) || data.rootOwnersByOrigin.containsKey(origin)) {
                    continue;
                }
                int slotId = entry.contains("SlotId") ? entry.getInt("SlotId") : NO_ROOT_SLOT_ID;
                data.rootAllocations.put(factoryId, new RootAllocation(slotId, origin));
                data.rootOwnersByOrigin.put(origin, factoryId);
                if (slotId >= data.nextRootSlotId) {
                    data.nextRootSlotId = slotId + 1;
                }
            } catch (IllegalArgumentException ignored) {
                // Corrupt coordinates must not prevent the whole world data from loading.
            }
        }
        for (Tag leaseTag : tag.getList("FrozenRootFactoryIds", Tag.TAG_STRING)) {
            String rootFactoryId = leaseTag.getAsString();
            if (!rootFactoryId.isBlank()) data.frozenRootFactoryIds.add(rootFactoryId);
        }
        for (Tag parentTag : tag.getList("FactoryParents", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) parentTag;
            String factoryId = entry.getString("FactoryId");
            if (!factoryId.isBlank()) data.parentByFactoryId.put(factoryId, entry.getString("ParentFactoryId"));
        }
        for (int slot : tag.getIntArray("FreeNestedSlots")) {
            if (slot >= 0 && slot < data.nextSlotId) data.freeNestedSlots.add(slot);
        }
        for (int slot : tag.getIntArray("FreeRootSlots")) {
            if (slot >= 0 && slot < data.nextRootSlotId
                    && !data.rootOwnersByOrigin.containsKey(NestedFactoryBlock.getRootRoomOrigin(slot))) {
                data.freeRootSlots.add(slot);
            }
        }
        for (long encodedOrigin : tag.getLongArray("FreeLegacyRootOrigins")) {
            BlockPos origin = BlockPos.of(encodedOrigin);
            if (!data.rootOwnersByOrigin.containsKey(origin)) data.freeLegacyRootOrigins.add(origin);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        HolderLookup.Provider registries = null;
        tag.putInt("Format", DATA_FORMAT);
        tag.putInt("NextSlotId", nextSlotId);
        tag.putInt("NextRootSlotId", nextRootSlotId);
        tag.putIntArray("FreeNestedSlots", freeNestedSlots.stream().mapToInt(Integer::intValue).toArray());
        tag.putIntArray("FreeRootSlots", freeRootSlots.stream().mapToInt(Integer::intValue).toArray());
        tag.putLongArray("FreeLegacyRootOrigins", freeLegacyRootOrigins.stream().mapToLong(BlockPos::asLong).toArray());

        ListTag roots = new ListTag();
        for (Map.Entry<String, RootAllocation> entry : rootAllocations.entrySet()) {
            String factoryId = entry.getKey();
            RootAllocation allocation = entry.getValue();
            CompoundTag root = new CompoundTag();
            root.putString("FactoryId", factoryId);
            root.putInt("SlotId", allocation.slotId());
            root.putLong("RoomOrigin", allocation.roomOrigin().asLong());
            roots.add(root);
        }
        tag.put("RootAllocations", roots);
        ListTag leases = new ListTag();
        frozenRootFactoryIds.stream().sorted()
                .forEach(rootFactoryId -> leases.add(net.minecraft.nbt.StringTag.valueOf(rootFactoryId)));
        tag.put("FrozenRootFactoryIds", leases);
        ListTag parents = new ListTag();
        parentByFactoryId.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag parent = new CompoundTag();
            parent.putString("FactoryId", entry.getKey());
            parent.putString("ParentFactoryId", entry.getValue());
            parents.add(parent);
        });
        tag.put("FactoryParents", parents);
        return tag;
    }
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) { return save(tag); }
}

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
import java.util.Map;

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
    private final Map<String, RootAllocation> rootAllocations = new HashMap<>();
    private final Map<BlockPos, String> rootOwnersByOrigin = new HashMap<>();

    public static NestedFactorySaveData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(NestedFactorySaveData::new, NestedFactorySaveData::load),
                DATA_NAME);
    }

    public synchronized int allocateSlotId() {
        int id = nextSlotId++;
        setDirty();
        return id;
    }

    public synchronized void observeSlotId(int slotId) {
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

        while (true) {
            int slotId = nextRootSlotId++;
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
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Format", DATA_FORMAT);
        tag.putInt("NextSlotId", nextSlotId);
        tag.putInt("NextRootSlotId", nextRootSlotId);

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
        return tag;
    }
}

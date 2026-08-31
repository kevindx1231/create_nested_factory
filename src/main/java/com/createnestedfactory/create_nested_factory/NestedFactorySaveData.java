package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
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

    public record RootFactoryKey(String factoryId, ResourceKey<Level> dimension, BlockPos sourcePos) {
        public RootFactoryKey {
            sourcePos = sourcePos.immutable();
        }
    }

    public record RootAllocation(int slotId, BlockPos roomOrigin) {
        public RootAllocation {
            roomOrigin = roomOrigin.immutable();
        }
    }

    private int nextSlotId;
    private int nextRootSlotId;
    private final Map<RootFactoryKey, RootAllocation> rootAllocations = new HashMap<>();
    private final Map<BlockPos, RootFactoryKey> rootOwnersByOrigin = new HashMap<>();

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

    /**
     * Returns the one persistent room allocation for a root factory owner.
     *
     * <p>An existing owner reservation always wins. Otherwise an already serialized origin is
     * reclaimed when safe. For pre-allocation saves, the legacy coordinate-derived origin is
     * retained only if no other factory has claimed it; collided legacy factories receive a fresh
     * empty slot instead.</p>
     */
    public synchronized RootAllocation claimRootAllocation(RootFactoryKey owner, int persistedSlotId,
                                                             BlockPos persistedOrigin, BlockPos legacyOrigin) {
        RootAllocation existing = rootAllocations.get(owner);
        if (existing != null) {
            return existing;
        }

        RootAllocation persisted = reserveIfAvailable(owner, persistedSlotId, persistedOrigin);
        if (persisted != null) {
            return persisted;
        }

        RootAllocation legacy = reserveIfAvailable(owner, NO_ROOT_SLOT_ID, legacyOrigin);
        if (legacy != null) {
            return legacy;
        }

        while (true) {
            int slotId = nextRootSlotId++;
            BlockPos origin = NestedFactoryBlock.getRootRoomOrigin(slotId);
            RootAllocation allocation = reserveIfAvailable(owner, slotId, origin);
            if (allocation != null) {
                return allocation;
            }
        }
    }

    private RootAllocation reserveIfAvailable(RootFactoryKey owner, int slotId, BlockPos origin) {
        if (origin == null) {
            return null;
        }
        BlockPos immutableOrigin = origin.immutable();
        RootFactoryKey existingOwner = rootOwnersByOrigin.get(immutableOrigin);
        if (existingOwner != null && !existingOwner.equals(owner)) {
            return null;
        }

        RootAllocation allocation = new RootAllocation(slotId, immutableOrigin);
        rootAllocations.put(owner, allocation);
        rootOwnersByOrigin.put(immutableOrigin, owner);
        if (slotId >= nextRootSlotId) {
            nextRootSlotId = slotId + 1;
        }
        setDirty();
        return allocation;
    }

    public static NestedFactorySaveData load(CompoundTag tag, HolderLookup.Provider registries) {
        NestedFactorySaveData data = new NestedFactorySaveData();
        data.nextSlotId = tag.getInt("NextSlotId");
        data.nextRootSlotId = tag.getInt("NextRootSlotId");

        for (Tag entryTag : tag.getList("RootAllocations", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) entryTag;
            String factoryId = entry.getString("FactoryId");
            if (factoryId.isBlank() || !entry.contains("SourcePos") || !entry.contains("RoomOrigin")) {
                continue;
            }
            try {
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(entry.getString("SourceDimension")));
                RootFactoryKey owner = new RootFactoryKey(factoryId, dimension, BlockPos.of(entry.getLong("SourcePos")));
                BlockPos origin = BlockPos.of(entry.getLong("RoomOrigin"));
                if (data.rootAllocations.containsKey(owner) || data.rootOwnersByOrigin.containsKey(origin)) {
                    continue;
                }
                int slotId = entry.contains("SlotId") ? entry.getInt("SlotId") : NO_ROOT_SLOT_ID;
                data.rootAllocations.put(owner, new RootAllocation(slotId, origin));
                data.rootOwnersByOrigin.put(origin, owner);
                if (slotId >= data.nextRootSlotId) {
                    data.nextRootSlotId = slotId + 1;
                }
            } catch (IllegalArgumentException ignored) {
                // Corrupt externally supplied dimension ids must not prevent the whole world data from loading.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextSlotId", nextSlotId);
        tag.putInt("NextRootSlotId", nextRootSlotId);

        ListTag roots = new ListTag();
        for (Map.Entry<RootFactoryKey, RootAllocation> entry : rootAllocations.entrySet()) {
            RootFactoryKey owner = entry.getKey();
            RootAllocation allocation = entry.getValue();
            CompoundTag root = new CompoundTag();
            root.putString("FactoryId", owner.factoryId());
            root.putString("SourceDimension", owner.dimension().location().toString());
            root.putLong("SourcePos", owner.sourcePos().asLong());
            root.putInt("SlotId", allocation.slotId());
            root.putLong("RoomOrigin", allocation.roomOrigin().asLong());
            roots.add(root);
        }
        tag.put("RootAllocations", roots);
        return tag;
    }
}

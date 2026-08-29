package com.createnestedfactory.create_nested_factory;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class NestedFactorySaveData extends SavedData {
    private static final String DATA_NAME = "create_nested_factory_slots";

    private int nextSlotId;

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

    public static NestedFactorySaveData load(CompoundTag tag, HolderLookup.Provider registries) {
        NestedFactorySaveData data = new NestedFactorySaveData();
        data.nextSlotId = tag.getInt("NextSlotId");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextSlotId", nextSlotId);
        return tag;
    }
}

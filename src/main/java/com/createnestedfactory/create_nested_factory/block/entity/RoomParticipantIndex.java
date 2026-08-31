package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

/** Runtime-only participant positions for one Pocket room. Never persisted. */
final class RoomParticipantIndex {
    private final Set<BlockPos> kineticPositions = new HashSet<>();
    private final Set<BlockPos> energyPositions = new HashSet<>();
    private final Set<BlockPos> inventoryPositions = new HashSet<>();
    private final Set<BlockPos> childFactoryPositions = new HashSet<>();
    private boolean built;
    private boolean dirty = true;

    void clear() {
        kineticPositions.clear();
        energyPositions.clear();
        inventoryPositions.clear();
        childFactoryPositions.clear();
        built = false;
        dirty = true;
    }

    void beginRebuild() {
        kineticPositions.clear();
        energyPositions.clear();
        inventoryPositions.clear();
        childFactoryPositions.clear();
    }

    void completeRebuild() {
        built = true;
        dirty = false;
    }

    boolean needsRebuild() {
        return !built || dirty;
    }

    void markDirty() {
        dirty = true;
    }

    Set<BlockPos> kineticPositions() {
        return kineticPositions;
    }

    Set<BlockPos> energyPositions() {
        return energyPositions;
    }

    Set<BlockPos> inventoryPositions() {
        return inventoryPositions;
    }

    Set<BlockPos> childFactoryPositions() {
        return childFactoryPositions;
    }

    int kineticCount() {
        return kineticPositions.size();
    }

    int energyCount() {
        return energyPositions.size();
    }

    int inventoryCount() {
        return inventoryPositions.size();
    }
}

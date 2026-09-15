package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Chunk-indexed dispatcher for block mutations in rooms that are actively learning.
 * No entry exists for ordinary worlds or inactive Pocket rooms.
 */
public final class PocketLearningObserver {
    private static final Map<ServerLevel, Map<Long, Set<NestedFactoryBlockEntity>>> ACTIVE = new WeakHashMap<>();

    private PocketLearningObserver() {
    }

    public static synchronized void register(ServerLevel pocket, NestedFactoryBlockEntity factory,
                                             PocketBounds bounds, BlockPos origin) {
        unregister(factory);
        if (pocket == null || factory == null || bounds == null || origin == null) return;
        Map<Long, Set<NestedFactoryBlockEntity>> chunks = ACTIVE.computeIfAbsent(pocket, ignored -> new HashMap<>());
        int minChunkX = SectionPosCompat.blockToSectionCoord(bounds.minX(origin));
        int maxChunkX = SectionPosCompat.blockToSectionCoord(bounds.maxX(origin));
        int minChunkZ = SectionPosCompat.blockToSectionCoord(bounds.minZ(origin));
        int maxChunkZ = SectionPosCompat.blockToSectionCoord(bounds.maxZ(origin));
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                chunks.computeIfAbsent(ChunkPos.asLong(x, z), ignored -> new HashSet<>()).add(factory);
            }
        }
    }

    public static synchronized void unregister(NestedFactoryBlockEntity factory) {
        if (factory == null) return;
        ACTIVE.values().forEach(chunks -> chunks.values().forEach(factories -> factories.remove(factory)));
        ACTIVE.values().forEach(chunks -> chunks.entrySet().removeIf(entry -> entry.getValue().isEmpty()));
        ACTIVE.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (level == null || pos == null || oldState == null || newState == null || oldState == newState) return;
        ArrayList<NestedFactoryBlockEntity> listeners;
        synchronized (PocketLearningObserver.class) {
            Map<Long, Set<NestedFactoryBlockEntity>> chunks = ACTIVE.get(level);
            if (chunks == null) return;
            Set<NestedFactoryBlockEntity> factories = chunks.get(ChunkPos.asLong(pos));
            if (factories == null || factories.isEmpty()) return;
            listeners = new ArrayList<>(factories);
        }
        for (NestedFactoryBlockEntity factory : listeners) {
            factory.onLearningBlockChanged(level, pos, oldState, newState);
        }
    }

    /** Routes completed Create drill cycles only to actively learning rooms in the target chunk. */
    public static void onDrillProduction(ServerLevel level, DrillProductionEvent event) {
        if (level == null || event == null) return;
        ArrayList<NestedFactoryBlockEntity> listeners;
        synchronized (PocketLearningObserver.class) {
            Map<Long, Set<NestedFactoryBlockEntity>> chunks = ACTIVE.get(level);
            if (chunks == null) return;
            Set<NestedFactoryBlockEntity> factories = chunks.get(ChunkPos.asLong(event.targetPos()));
            if (factories == null || factories.isEmpty()) return;
            listeners = new ArrayList<>(factories);
        }
        for (NestedFactoryBlockEntity factory : listeners) {
            factory.onLearningDrillProduction(level, event);
        }
    }

    /** Avoids depending on a mapping-specific SectionPos helper name in the dispatcher body. */
    private static final class SectionPosCompat {
        private static int blockToSectionCoord(int coordinate) {
            return coordinate >> 4;
        }
    }
}

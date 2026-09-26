package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.PocketChunkForceManager;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Owns every runtime chunk ticket associated with one factory.
 *
 * <p>The factory block entity remains the Minecraft lifecycle adapter. This module owns the
 * reference counts, delayed release, random-tick lease and the external return-area lease so
 * those states cannot diverge across mode changes, player entry and removal.</p>
 */
final class FactoryChunkLeaseController {
    private static final int RELEASE_DELAY_TICKS = 100;
    private static final int RANDOM_TICK_SCAN_INTERVAL = 100;

    private final NestedFactoryBlockEntity factory;
    private final Map<String, Integer> roomReferences = new HashMap<>();
    private boolean roomChunksForced;
    private long roomChunksReleaseAt = -1L;
    private boolean randomTicksForced;
    private long nextRandomTickScan;
    private int playersInside;

    FactoryChunkLeaseController(NestedFactoryBlockEntity factory) {
        this.factory = factory;
    }

    void retainRoom(String reason) {
        if (factory.isChunkExecutionSuspended()) return;
        int count = roomReferences.merge(reason, 1, Integer::sum);
        if (count == 1 && !roomChunksForced) {
            roomChunksForced = true;
            applyRoomLease(true);
        }
    }

    void releaseRoom(String reason) {
        int count = roomReferences.getOrDefault(reason, 0);
        if (count <= 1) {
            roomReferences.remove(reason);
        } else {
            roomReferences.put(reason, count - 1);
        }
        Level level = factory.getLevel();
        if (roomReferences.isEmpty() && roomChunksForced && level != null) {
            roomChunksReleaseAt = level.getGameTime() + RELEASE_DELAY_TICKS;
        }
    }

    void refreshForMode() {
        releaseImmediately();
        if (!factory.isChunkExecutionSuspended()) retainRoom("load");
    }

    void onPlayerEntered(ResourceKey<Level> sourceDimension, BlockPos sourcePosition) {
        playersInside++;
        if (playersInside != 1) return;
        setExternalAreaForced(true, sourceDimension, sourcePosition);
        retainRoom("player");
        refreshRandomTickLease(true);
    }

    void onPlayerExited() {
        if (playersInside <= 0) return;
        playersInside--;
        if (playersInside != 0) return;
        setExternalAreaForced(false, null, null);
        releaseRoom("player");
        refreshRandomTickLease(true);
    }

    void tick() {
        refreshRandomTickLease(false);
        Level level = factory.getLevel();
        if (level != null && roomReferences.isEmpty() && roomChunksForced
                && level.getGameTime() >= roomChunksReleaseAt) {
            roomChunksForced = false;
            applyRoomLease(false);
        }
    }

    void refreshAfterBoundsChange() {
        if (roomChunksForced) applyRoomLease(true);
    }

    void releaseImmediately() {
        Level level = factory.getLevel();
        if (level == null || level.isClientSide()) return;
        MinecraftServer server = level.getServer();
        if (server != null) PocketChunkForceManager.releaseAll(server, roomOwner());
        roomReferences.clear();
        roomChunksForced = false;
        roomChunksReleaseAt = -1L;
        randomTicksForced = false;
        nextRandomTickScan = 0L;
    }

    void close() {
        Level level = factory.getLevel();
        if (level != null && !level.isClientSide()) {
            MinecraftServer server = level.getServer();
            if (server != null) {
                PocketChunkForceManager.releaseAll(server, externalOwner());
                PocketChunkForceManager.releaseAll(server, roomOwner());
            }
        }
        roomReferences.clear();
        roomChunksForced = false;
        roomChunksReleaseAt = -1L;
        randomTicksForced = false;
        nextRandomTickScan = 0L;
        playersInside = 0;
    }

    private String roomOwner() {
        return factory.getFactoryId() + ":room";
    }

    private String externalOwner() {
        return factory.getFactoryId() + ":external";
    }

    private Set<ChunkPos> roomChunks() {
        BlockPos origin = factory.roomOrigin();
        PocketBounds bounds = factory.getBounds();
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        Set<ChunkPos> chunks = new HashSet<>();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        return chunks;
    }

    private static Set<ChunkPos> externalAreaChunks(BlockPos center) {
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        Set<ChunkPos> chunks = new HashSet<>();
        for (int deltaX = -1; deltaX <= 1; deltaX++) {
            for (int deltaZ = -1; deltaZ <= 1; deltaZ++) {
                chunks.add(new ChunkPos(chunkX + deltaX, chunkZ + deltaZ));
            }
        }
        return chunks;
    }

    private void applyRoomLease(boolean forced) {
        ServerLevel pocket = factory.getPocketLevel();
        if (pocket == null) return;
        if (forced) {
            updateRandomTickLease(pocket);
        } else {
            PocketChunkForceManager.releaseAll(pocket.getServer(), roomOwner());
            randomTicksForced = false;
            nextRandomTickScan = 0L;
        }
    }

    private void refreshRandomTickLease(boolean immediate) {
        Level level = factory.getLevel();
        if (!roomChunksForced || level == null || level.isClientSide()) return;
        long now = level.getGameTime();
        if (!immediate && now < nextRandomTickScan) return;
        ServerLevel pocket = factory.getPocketLevel();
        if (pocket != null) updateRandomTickLease(pocket);
    }

    private void updateRandomTickLease(ServerLevel pocket) {
        OperationMode mode = factory.getOperationMode();
        boolean physicalMode = mode == OperationMode.CHUNK_LOADED
                || mode == OperationMode.BLACKBOX_PREPARING
                || mode == OperationMode.BLACKBOX_LEARNING;
        boolean playerPresent = playersInside > 0 || factory.hasPlayersInside();
        boolean forceRandomTicks = physicalMode && !playerPresent
                && roomContainsRandomlyTickingBlocks(pocket);
        boolean changed = forceRandomTicks != randomTicksForced;
        randomTicksForced = forceRandomTicks;
        Level level = factory.getLevel();
        nextRandomTickScan = level == null ? 0L : level.getGameTime() + RANDOM_TICK_SCAN_INTERVAL;
        PocketChunkForceManager.replace(pocket, roomOwner(), roomChunks(), forceRandomTicks);
        if (changed) {
            factory.blackboxDebug("pocket_random_ticks", () -> "enabled=" + forceRandomTicks
                    + ", physicalMode=" + physicalMode + ", playerPresent=" + playerPresent
                    + ", scanInterval=" + RANDOM_TICK_SCAN_INTERVAL);
        }
    }

    /** Scans only room-intersecting sections whose palettes report random-ticking blocks. */
    private boolean roomContainsRandomlyTickingBlocks(ServerLevel pocket) {
        BlockPos origin = factory.roomOrigin();
        PocketBounds bounds = factory.getBounds();
        int minX = bounds.minX(origin) + 1;
        int minY = bounds.minY(origin) + 1;
        int minZ = bounds.minZ(origin) + 1;
        int maxX = bounds.maxX(origin) - 1;
        int maxY = bounds.maxY(origin) - 1;
        int maxZ = bounds.maxZ(origin) - 1;
        if (minX > maxX || minY > maxY || minZ > maxZ) return false;

        int minSectionY = SectionPos.blockToSectionCoord(minY);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY);
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            int sectionMinX = Math.max(minX, chunkX << 4);
            int sectionMaxX = Math.min(maxX, (chunkX << 4) + 15);
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int sectionMinZ = Math.max(minZ, chunkZ << 4);
                int sectionMaxZ = Math.min(maxZ, (chunkZ << 4) + 15);
                LevelChunk chunk = pocket.getChunk(chunkX, chunkZ);
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
                    if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) continue;
                    LevelChunkSection section = chunk.getSection(sectionIndex);
                    if (!section.isRandomlyTickingBlocks()) continue;
                    int sectionMinY = Math.max(minY, SectionPos.sectionToBlockCoord(sectionY));
                    int sectionMaxY = Math.min(maxY, SectionPos.sectionToBlockCoord(sectionY) + 15);
                    for (int y = sectionMinY; y <= sectionMaxY; y++) {
                        for (int x = sectionMinX; x <= sectionMaxX; x++) {
                            for (int z = sectionMinZ; z <= sectionMaxZ; z++) {
                                if (section.getBlockState(x & 15, y & 15, z & 15).isRandomlyTicking()) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Keeps the factory block's surrounding area loaded while a player is inside. */
    private void setExternalAreaForced(boolean forced, ResourceKey<Level> sourceDimension,
                                       BlockPos sourcePosition) {
        Level level = factory.getLevel();
        if (level == null || level.isClientSide()) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;
        if (forced) {
            if (sourceDimension == null || sourcePosition == null) return;
            ServerLevel sourceLevel = server.getLevel(sourceDimension);
            if (sourceLevel == null) return;
            PocketChunkForceManager.replace(sourceLevel, externalOwner(), externalAreaChunks(sourcePosition));
        } else {
            PocketChunkForceManager.releaseAll(server, externalOwner());
        }
    }
}

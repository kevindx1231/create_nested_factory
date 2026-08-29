package com.createnestedfactory.create_nested_factory;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Owns the mod's {@link ServerLevel#setChunkForced(int, int, boolean)} calls.
 *
 * <p>Minecraft stores forced chunks as one boolean per dimension/chunk, not per caller.
 * A direct {@code setChunkForced(chunk, false)} from one factory can therefore unload a
 * chunk which is still required by its parent or another nested factory. This manager
 * adds the missing owner-level reference counting.</p>
 */
public final class PocketChunkForceManager {
    private record ChunkKey(ResourceKey<Level> dimension, int x, int z) {
    }

    private static final Map<ChunkKey, Set<String>> OWNERS_BY_CHUNK = new HashMap<>();
    private static final Map<String, Set<ChunkKey>> CHUNKS_BY_OWNER = new HashMap<>();
    private static MinecraftServer activeServer;

    private PocketChunkForceManager() {
    }

    /**
     * Makes {@code owner} own exactly {@code chunks} in {@code level}. Existing ownership
     * outside that range is released, while shared chunks remain forced until their last
     * owner releases them.
     */
    public static synchronized void replace(ServerLevel level, String owner, Set<ChunkPos> chunks) {
        ensureServer(level.getServer());

        Set<ChunkKey> desired = new HashSet<>();
        for (ChunkPos chunk : chunks) {
            desired.add(new ChunkKey(level.dimension(), chunk.x, chunk.z));
        }

        Set<ChunkKey> current = new HashSet<>(CHUNKS_BY_OWNER.getOrDefault(owner, Set.of()));
        for (ChunkKey key : current) {
            if (!desired.contains(key)) {
                release(level.getServer(), owner, key);
            }
        }
        for (ChunkKey key : desired) {
            if (!current.contains(key)) {
                acquire(level, owner, key);
            }
        }
    }

    /** Releases every chunk currently owned by {@code owner}. */
    public static synchronized void releaseAll(MinecraftServer server, String owner) {
        ensureServer(server);
        for (ChunkKey key : new HashSet<>(CHUNKS_BY_OWNER.getOrDefault(owner, Set.of()))) {
            release(server, owner, key);
        }
    }

    private static void acquire(ServerLevel level, String owner, ChunkKey key) {
        Set<String> owners = OWNERS_BY_CHUNK.computeIfAbsent(key, ignored -> new HashSet<>());
        if (!owners.add(owner)) {
            return;
        }

        CHUNKS_BY_OWNER.computeIfAbsent(owner, ignored -> new HashSet<>()).add(key);
        if (owners.size() == 1) {
            level.setChunkForced(key.x(), key.z(), true);
        }
    }

    private static void release(MinecraftServer server, String owner, ChunkKey key) {
        Set<String> owners = OWNERS_BY_CHUNK.get(key);
        if (owners == null || !owners.remove(owner)) {
            return;
        }

        Set<ChunkKey> ownedChunks = CHUNKS_BY_OWNER.get(owner);
        if (ownedChunks != null) {
            ownedChunks.remove(key);
            if (ownedChunks.isEmpty()) {
                CHUNKS_BY_OWNER.remove(owner);
            }
        }

        if (!owners.isEmpty()) {
            return;
        }

        OWNERS_BY_CHUNK.remove(key);
        ServerLevel level = server.getLevel(key.dimension());
        if (level != null) {
            level.setChunkForced(key.x(), key.z(), false);
        }
    }

    private static void ensureServer(MinecraftServer server) {
        if (activeServer != null && activeServer != server) {
            OWNERS_BY_CHUNK.clear();
            CHUNKS_BY_OWNER.clear();
        }
        activeServer = server;
    }
}

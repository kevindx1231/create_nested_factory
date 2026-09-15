package com.createnestedfactory.create_nested_factory;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Owns the mod's persistent chunk loads and their paired force-ticking tickets.
 *
 * <p>Minecraft stores forced chunks as one boolean per dimension/chunk, not per caller.
 * A direct {@code setChunkForced(chunk, false)} from one factory can therefore unload a
 * chunk which is still required by its parent or another nested factory. This manager
 * adds the missing owner-level reference counting.</p>
 *
 * <p>The persistent {@link ServerLevel#setChunkForced(int, int, boolean)} ticket only keeps
 * a chunk loaded; its vanilla ticket has {@code forceTicks=false}. Callers may separately mark
 * an ownership lease as force-ticking when unloaded-from-player simulation is actually needed.
 * Shared chunks retain that ticket until their last ticking owner releases it.</p>
 */
public final class PocketChunkForceManager {
    private static final TicketType<ChunkPos> FORCE_TICK_TICKET = TicketType.create(
            "create_nested_factory:factory_force_tick", Comparator.comparingLong(ChunkPos::toLong));
    /** FULL is level 33; radius 2 requests entity ticking at level 31. */
    private static final int FORCE_TICK_RADIUS = 2;

    private record ChunkKey(ResourceKey<Level> dimension, int x, int z) {
    }

    private static final Map<ChunkKey, Set<String>> OWNERS_BY_CHUNK = new HashMap<>();
    private static final Map<ChunkKey, Set<String>> TICKING_OWNERS_BY_CHUNK = new HashMap<>();
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
        replace(level, owner, chunks, false);
    }

    /**
     * Makes {@code owner} own exactly {@code chunks}, optionally requesting natural chunk ticks
     * while no player is nearby. Changing only {@code forceTicks} updates the existing lease
     * without unloading the chunk.
     */
    public static synchronized void replace(ServerLevel level, String owner, Set<ChunkPos> chunks,
                                            boolean forceTicks) {
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
                acquire(level, owner, key, forceTicks);
            } else {
                setForceTicks(level, owner, key, forceTicks);
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

    private static void acquire(ServerLevel level, String owner, ChunkKey key, boolean forceTicks) {
        Set<String> owners = OWNERS_BY_CHUNK.computeIfAbsent(key, ignored -> new HashSet<>());
        if (!owners.add(owner)) {
            setForceTicks(level, owner, key, forceTicks);
            return;
        }

        CHUNKS_BY_OWNER.computeIfAbsent(owner, ignored -> new HashSet<>()).add(key);
        if (owners.size() == 1) {
            level.setChunkForced(key.x(), key.z(), true);
        }
        setForceTicks(level, owner, key, forceTicks);
    }

    private static void release(MinecraftServer server, String owner, ChunkKey key) {
        Set<String> owners = OWNERS_BY_CHUNK.get(key);
        if (owners == null || !owners.remove(owner)) {
            return;
        }

        ServerLevel level = server.getLevel(key.dimension());
        if (level != null) {
            setForceTicks(level, owner, key, false);
        } else {
            removeTickingOwner(owner, key);
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
        if (level != null) {
            level.setChunkForced(key.x(), key.z(), false);
        }
    }

    private static void setForceTicks(ServerLevel level, String owner, ChunkKey key, boolean forceTicks) {
        Set<String> tickingOwners = TICKING_OWNERS_BY_CHUNK.get(key);
        if (forceTicks) {
            if (tickingOwners == null) {
                tickingOwners = new HashSet<>();
                TICKING_OWNERS_BY_CHUNK.put(key, tickingOwners);
            }
            if (tickingOwners.add(owner) && tickingOwners.size() == 1) {
                ChunkPos pos = new ChunkPos(key.x(), key.z());
                level.getChunkSource().addRegionTicket(
                        FORCE_TICK_TICKET, pos, FORCE_TICK_RADIUS, pos, true);
            }
            return;
        }
        if (!removeTickingOwner(owner, key) || TICKING_OWNERS_BY_CHUNK.containsKey(key)) {
            return;
        }
        ChunkPos pos = new ChunkPos(key.x(), key.z());
        level.getChunkSource().removeRegionTicket(
                FORCE_TICK_TICKET, pos, FORCE_TICK_RADIUS, pos, true);
    }

    /** Returns true only when the last ticking owner was removed. */
    private static boolean removeTickingOwner(String owner, ChunkKey key) {
        Set<String> tickingOwners = TICKING_OWNERS_BY_CHUNK.get(key);
        if (tickingOwners == null || !tickingOwners.remove(owner)) {
            return false;
        }
        if (!tickingOwners.isEmpty()) {
            return false;
        }
        TICKING_OWNERS_BY_CHUNK.remove(key);
        return true;
    }

    private static void ensureServer(MinecraftServer server) {
        if (activeServer != null && activeServer != server) {
            OWNERS_BY_CHUNK.clear();
            TICKING_OWNERS_BY_CHUNK.clear();
            CHUNKS_BY_OWNER.clear();
        }
        activeServer = server;
    }
}

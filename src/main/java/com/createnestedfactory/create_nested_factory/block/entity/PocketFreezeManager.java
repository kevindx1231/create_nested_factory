package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.NestedFactorySaveData;
import com.createnestedfactory.create_nested_factory.PocketChunkForceManager;
import com.createnestedfactory.create_nested_factory.PocketFreezeHooks;
import com.createnestedfactory.create_nested_factory.PocketFreezeSavedData;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Acquires and releases the execution lease for a complete loaded factory subtree.
 * A descendant carrying an ancestor freeze flag never recreates its own Pocket chunk tickets.
 */
public final class PocketFreezeManager {
    /** Runtime-only thaw progress; persistent truth remains in PocketFreezeSavedData. */
    private static final Map<MinecraftServer, Map<String, ThawState>> THAW_STATES =
            new WeakHashMap<>();

    private static final class ThawState {
        private final List<PocketFreezeSavedData.FrozenRoom> rooms;
        private final Set<ChunkPos> chunks;
        private boolean ticketsRequested;

        private ThawState(PocketFreezeSavedData.FrozenTree tree) {
            rooms = tree.rooms();
            chunks = Set.copyOf(roomChunks(rooms));
        }

        private boolean matches(PocketFreezeSavedData.FrozenTree tree) {
            return rooms.equals(tree.rooms());
        }
    }

    private PocketFreezeManager() {
    }

    public static boolean freezeLoadedTree(NestedFactoryBlockEntity root) {
        List<NestedFactoryBlockEntity> factories = collect(root);
        if (factories.stream().anyMatch(NestedFactoryBlockEntity::hasPlayersInside)) {
            root.blackboxDebug("freeze_tree_rejected", () -> "reason=players_inside, loadedFactories=" + factories.size());
            return false;
        }
        MinecraftServer server = root.getLevel() == null ? null : root.getLevel().getServer();
        if (server == null) {
            root.blackboxDebug("freeze_tree_rejected", () -> "reason=server_unavailable, loadedFactories=" + factories.size());
            return false;
        }
        ServerLevel pocket = server.getLevel(NestedFactoryBlock.POCKET_DIMENSION);
        if (pocket == null) {
            root.blackboxDebug("freeze_tree_rejected", () -> "reason=pocket_unavailable, loadedFactories=" + factories.size());
            return false;
        }
        root.blackboxDebug("freeze_tree_started", () -> "loadedFactories=" + factories.size());
        List<PocketFreezeSavedData.FrozenRoom> rooms = factories.stream()
                .filter(NestedFactoryBlockEntity::hasPhysicalRoom)
                .map(PocketFreezeManager::describeRoom)
                .toList();
        PocketFreezeSavedData freezeData = PocketFreezeSavedData.get(server);
        boolean alreadyFrozen = freezeData.hasTree(root.getFactoryId());
        freezeData.beginFreeze(root.getFactoryId(), rooms);
        PocketChunkForceManager.releaseAll(server, thawOwner(root.getFactoryId()));
        forgetThawState(server, root.getFactoryId());
        if (!alreadyFrozen) {
            try {
                // Publish the spatial guard before removing queued work or releasing any chunk ticket.
                PocketFreezeHooks.capturePhysicalQueues(pocket, root.getFactoryId(), rooms);
            } catch (RuntimeException exception) {
                freezeData.removeTree(root.getFactoryId());
                root.blackboxDebug("freeze_tree_rejected", () -> "reason=queue_capture_failed, error="
                        + exception.getClass().getSimpleName());
                return false;
            }
        }
        NestedFactorySaveData.get(server).acquireFreezeLease(root.getFactoryId());
        for (NestedFactoryBlockEntity factory : factories) {
            factory.clearStressRelayBeforeFreeze();
            if (factory != root) factory.setAncestorFrozen(true);
        }
        // Release deepest rooms first so no child keeps a logical subtree alive after its parent stops ticking.
        for (int i = factories.size() - 1; i >= 0; i--) factories.get(i).releasePocketChunksImmediately();
        root.blackboxDebug("freeze_tree_completed", () -> "loadedFactories=" + factories.size()
                + ", leaseOwner=" + root.getFactoryId());
        return true;
    }

    public static boolean hasPlayersInsideLoadedTree(NestedFactoryBlockEntity root) {
        return collect(root).stream().anyMatch(NestedFactoryBlockEntity::hasPlayersInside);
    }

    public static void thawLoadedTree(NestedFactoryBlockEntity root) {
        MinecraftServer server = root.getLevel() == null ? null : root.getLevel().getServer();
        if (server == null) return;
        PocketFreezeSavedData freezeData = PocketFreezeSavedData.get(server);
        if (!freezeData.beginThaw(root.getFactoryId())) {
            // Missing manifests are deliberately not treated as a valid simulated save.
            NestedFactorySaveData.get(server).releaseFreezeLease(root.getFactoryId());
            root.refreshChunkRefsAfterFreeze();
            return;
        }
        root.blackboxDebug("thaw_tree_started", () -> "leaseOwner=" + root.getFactoryId());
        requestThawChunks(server, freezeData.thawingTrees().stream()
                .filter(tree -> tree.rootFactoryId().equals(root.getFactoryId()))
                .findFirst().orElse(null));
    }

    public static boolean hasPersistentLease(NestedFactoryBlockEntity factory) {
        MinecraftServer server = factory.getLevel() == null ? null : factory.getLevel().getServer();
        return server != null && NestedFactorySaveData.get(server)
                .hasFreezeLeaseInAncestors(factory.getFactoryId());
    }

    public static boolean ensurePersistentLease(NestedFactoryBlockEntity root) {
        MinecraftServer server = root.getLevel() == null ? null : root.getLevel().getServer();
        if (server != null && PocketFreezeSavedData.get(server).hasTree(root.getFactoryId())) {
            NestedFactorySaveData.get(server).acquireFreezeLease(root.getFactoryId());
            root.blackboxDebug("freeze_lease_ensured", () -> "leaseOwner=" + root.getFactoryId());
            return true;
        }
        return false;
    }

    /** Completes deferred thaws only after every physical room chunk is resident again. */
    public static void tick(MinecraftServer server) {
        PocketFreezeSavedData freezeData = PocketFreezeSavedData.get(server);
        List<PocketFreezeSavedData.FrozenTree> thawingTrees = freezeData.thawingTrees();
        for (PocketFreezeSavedData.FrozenTree tree : thawingTrees) {
            ServerLevel pocket = server.getLevel(NestedFactoryBlock.POCKET_DIMENSION);
            if (pocket == null) continue;
            ThawState thaw = thawState(server, tree);
            requestThawTickets(pocket, tree.rootFactoryId(), thaw);
            boolean ready = thaw.chunks.stream()
                    .allMatch(chunk -> pocket.getChunkSource().hasChunk(chunk.x, chunk.z));
            if (!ready) continue;

            PocketFreezeSavedData.FrozenTree removed = freezeData.removeTree(tree.rootFactoryId());
            if (removed == null) continue;
            NestedFactorySaveData.get(server).releaseFreezeLease(tree.rootFactoryId());
            PocketFreezeHooks.restorePhysicalQueues(pocket, removed);
            for (PocketFreezeSavedData.FrozenRoom room : removed.rooms()) {
                NestedFactoryBlockEntity factory = resolveFactory(server, room);
                if (factory == null) continue;
                if (!factory.getFactoryId().equals(tree.rootFactoryId())) {
                    factory.setAncestorFrozen(hasPersistentLease(factory));
                }
                factory.refreshChunkRefsAfterFreeze();
            }
            PocketChunkForceManager.releaseAll(server, thawOwner(tree.rootFactoryId()));
            forgetThawState(server, tree.rootFactoryId());
        }
    }

    private static void requestThawChunks(MinecraftServer server, PocketFreezeSavedData.FrozenTree tree) {
        if (tree == null) return;
        ServerLevel pocket = server.getLevel(NestedFactoryBlock.POCKET_DIMENSION);
        if (pocket != null) {
            requestThawTickets(pocket, tree.rootFactoryId(), thawState(server, tree));
        }
    }

    private static void requestThawTickets(ServerLevel pocket, String rootFactoryId, ThawState thaw) {
        if (thaw.ticketsRequested) return;
        PocketChunkForceManager.replace(pocket, thawOwner(rootFactoryId), thaw.chunks);
        thaw.ticketsRequested = true;
    }

    private static synchronized ThawState thawState(MinecraftServer server,
                                                    PocketFreezeSavedData.FrozenTree tree) {
        Map<String, ThawState> states = THAW_STATES.computeIfAbsent(server,
                ignored -> new LinkedHashMap<>());
        ThawState current = states.get(tree.rootFactoryId());
        if (current == null || !current.matches(tree)) {
            current = new ThawState(tree);
            states.put(tree.rootFactoryId(), current);
        }
        return current;
    }

    private static synchronized void forgetThawState(MinecraftServer server, String rootFactoryId) {
        Map<String, ThawState> states = THAW_STATES.get(server);
        if (states == null) return;
        states.remove(rootFactoryId);
        if (states.isEmpty()) THAW_STATES.remove(server);
    }

    public static synchronized void releaseServer(MinecraftServer server) {
        THAW_STATES.remove(server);
    }

    private static String thawOwner(String rootFactoryId) {
        return rootFactoryId + ":thaw";
    }

    private static Set<ChunkPos> roomChunks(List<PocketFreezeSavedData.FrozenRoom> rooms) {
        Set<ChunkPos> chunks = new HashSet<>();
        for (PocketFreezeSavedData.FrozenRoom room : rooms) {
            for (int chunkX = room.minChunkX(); chunkX <= room.maxChunkX(); chunkX++) {
                for (int chunkZ = room.minChunkZ(); chunkZ <= room.maxChunkZ(); chunkZ++) {
                    chunks.add(new ChunkPos(chunkX, chunkZ));
                }
            }
        }
        return chunks;
    }

    private static PocketFreezeSavedData.FrozenRoom describeRoom(NestedFactoryBlockEntity factory) {
        BlockPos origin = factory.roomOrigin();
        PocketBounds bounds = factory.getBounds();
        Level factoryLevel = factory.getLevel();
        String dimension = factoryLevel == null ? "" : factoryLevel.dimension().location().toString();
        return new PocketFreezeSavedData.FrozenRoom(factory.getFactoryId(), dimension, factory.getBlockPos(),
                bounds.minX(origin), bounds.minY(origin), bounds.minZ(origin),
                bounds.maxX(origin), bounds.maxY(origin), bounds.maxZ(origin), factory.getBoundsVersion());
    }

    private static NestedFactoryBlockEntity resolveFactory(MinecraftServer server,
                                                            PocketFreezeSavedData.FrozenRoom room) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(room.factoryDimension());
        if (dimensionId == null) return null;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (level == null || !level.hasChunkAt(room.factoryPos())) return null;
        return level.getBlockEntity(room.factoryPos()) instanceof NestedFactoryBlockEntity factory ? factory : null;
    }

    private static List<NestedFactoryBlockEntity> collect(NestedFactoryBlockEntity root) {
        List<NestedFactoryBlockEntity> result = new ArrayList<>();
        collect(root, new HashSet<>(), result);
        return result;
    }

    private static void collect(NestedFactoryBlockEntity factory, Set<String> visited,
                                List<NestedFactoryBlockEntity> result) {
        if (factory == null || !visited.add(factory.getFactoryId())) return;
        result.add(factory);
        for (NestedFactoryBlockEntity child : factory.loadedChildrenForFreeze()) collect(child, visited, result);
    }
}

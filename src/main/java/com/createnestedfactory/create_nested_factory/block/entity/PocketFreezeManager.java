package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.NestedFactorySaveData;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Acquires and releases the execution lease for a complete loaded factory subtree.
 * A descendant carrying an ancestor freeze flag never recreates its own Pocket chunk tickets.
 */
public final class PocketFreezeManager {
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
        root.blackboxDebug("freeze_tree_started", () -> "loadedFactories=" + factories.size());
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
        List<NestedFactoryBlockEntity> factories = collect(root);
        root.blackboxDebug("thaw_tree_started", () -> "loadedFactories=" + factories.size());
        MinecraftServer server = root.getLevel() == null ? null : root.getLevel().getServer();
        if (server != null) NestedFactorySaveData.get(server).releaseFreezeLease(root.getFactoryId());
        for (NestedFactoryBlockEntity factory : factories) {
            if (factory != root) factory.setAncestorFrozen(hasPersistentLease(factory));
            factory.refreshChunkRefsAfterFreeze();
        }
        root.blackboxDebug("thaw_tree_completed", () -> "loadedFactories=" + factories.size());
    }

    public static boolean hasPersistentLease(NestedFactoryBlockEntity factory) {
        MinecraftServer server = factory.getLevel() == null ? null : factory.getLevel().getServer();
        return server != null && NestedFactorySaveData.get(server)
                .hasFreezeLeaseInAncestors(factory.getFactoryId());
    }

    public static void ensurePersistentLease(NestedFactoryBlockEntity root) {
        MinecraftServer server = root.getLevel() == null ? null : root.getLevel().getServer();
        if (server != null) {
            NestedFactorySaveData.get(server).acquireFreezeLease(root.getFactoryId());
            root.blackboxDebug("freeze_lease_ensured", () -> "leaseOwner=" + root.getFactoryId());
        }
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

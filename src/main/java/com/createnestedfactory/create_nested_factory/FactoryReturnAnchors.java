package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Optional-compat seam for return points attached to moving physical carriers. */
public final class FactoryReturnAnchors {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Adapter> ADAPTERS = new LinkedHashMap<>();

    private FactoryReturnAnchors() {
    }

    public interface Adapter {
        String id();

        ModAttachments.DynamicReturnAnchor capture(ServerPlayer player, NestedFactoryBlockEntity factory);

        Resolution resolve(ServerPlayer player, ModAttachments.DynamicReturnAnchor anchor,
                           NestedFactoryBlockEntity currentFactory);

        void release(MinecraftServer server, UUID playerId, ModAttachments.DynamicReturnAnchor anchor);
    }

    public enum Status {
        UNHANDLED,
        RESOLVED,
        RETRY_LATER,
        INVALID
    }

    public record Destination(ResourceKey<Level> dimension, Vec3 position, float yRot, float xRot,
                              Vec3 velocity) {
    }

    public record Resolution(Status status, Destination destination) {
        public static Resolution unhandled() {
            return new Resolution(Status.UNHANDLED, null);
        }

        public static Resolution resolved(Destination destination) {
            return new Resolution(Status.RESOLVED, destination);
        }

        public static Resolution retryLater() {
            return new Resolution(Status.RETRY_LATER, null);
        }

        public static Resolution invalid() {
            return new Resolution(Status.INVALID, null);
        }
    }

    public static synchronized void register(Adapter adapter) {
        Adapter previous = ADAPTERS.put(adapter.id(), adapter);
        if (previous != null && previous != adapter) {
            LOGGER.warn("Replaced factory return-anchor adapter {}", adapter.id());
        }
    }

    public static ModAttachments.DynamicReturnAnchor capture(ServerPlayer player,
                                                              NestedFactoryBlockEntity factory) {
        for (Adapter adapter : snapshotAdapters()) {
            try {
                ModAttachments.DynamicReturnAnchor anchor = adapter.capture(player, factory);
                if (anchor != null) {
                    return anchor;
                }
            } catch (LinkageError | RuntimeException exception) {
                LOGGER.warn("Factory return-anchor adapter {} failed to capture", adapter.id(), exception);
            }
        }
        return null;
    }

    public static Resolution resolve(ServerPlayer player, ModAttachments.DynamicReturnAnchor anchor,
                                     NestedFactoryBlockEntity currentFactory) {
        if (anchor == null) {
            return Resolution.unhandled();
        }
        Adapter adapter = adapter(anchor.resolverId());
        if (adapter == null) {
            return Resolution.invalid();
        }
        try {
            return adapter.resolve(player, anchor, currentFactory);
        } catch (LinkageError | RuntimeException exception) {
            LOGGER.warn("Factory return-anchor adapter {} failed to resolve", adapter.id(), exception);
            return Resolution.invalid();
        }
    }

    public static void release(ServerPlayer player, ModAttachments.DynamicReturnAnchor anchor) {
        if (anchor == null) {
            return;
        }
        Adapter adapter = adapter(anchor.resolverId());
        if (adapter == null) {
            return;
        }
        try {
            adapter.release(player.serverLevel().getServer(), player.getUUID(), anchor);
        } catch (LinkageError | RuntimeException exception) {
            LOGGER.warn("Factory return-anchor adapter {} failed to release", adapter.id(), exception);
        }
    }

    private static synchronized Adapter adapter(String id) {
        return ADAPTERS.get(id);
    }

    private static synchronized Adapter[] snapshotAdapters() {
        return ADAPTERS.values().toArray(Adapter[]::new);
    }
}

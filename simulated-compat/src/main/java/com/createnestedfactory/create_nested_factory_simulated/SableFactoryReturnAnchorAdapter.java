package com.createnestedfactory.create_nested_factory_simulated;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.FactoryReturnAnchors;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Keeps a player's external return point attached to the current pose of a Sable SubLevel. */
final class SableFactoryReturnAnchorAdapter implements FactoryReturnAnchors.Adapter {
    static final SableFactoryReturnAnchorAdapter INSTANCE = new SableFactoryReturnAnchorAdapter();
    private static final String ID = Create_nested_factory.MODID + ":sable_sublevel";
    private static final int VERSION = 1;
    private static final SubLevelLoadingTicketType<String> RETURN_SESSION_TICKET =
            SubLevelLoadingTicketType.create(
                    ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "return_session"),
                    Codec.STRING);
    private static final int MIN_RELEASE_DELAY_TICKS = 2;
    private static final int RELEASE_TIMEOUT_TICKS = 40;
    private static final Map<MinecraftServer, Map<ReleaseKey, PendingRelease>> PENDING_RELEASES =
            new IdentityHashMap<>();
    private static boolean eventsRegistered;

    private SableFactoryReturnAnchorAdapter() {
    }

    static synchronized void registerEvents() {
        if (eventsRegistered) return;
        eventsRegistered = true;
        NeoForge.EVENT_BUS.addListener(SableFactoryReturnAnchorAdapter::onServerTick);
        NeoForge.EVENT_BUS.addListener(SableFactoryReturnAnchorAdapter::onServerStopped);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModAttachments.DynamicReturnAnchor capture(ServerPlayer player, NestedFactoryBlockEntity factory) {
        if (!(factory.getLevel() instanceof ServerLevel level) || player.serverLevel() != level) {
            return null;
        }
        SubLevel containing = Sable.HELPER.getContaining(factory);
        if (!(containing instanceof ServerSubLevel carrier)) {
            return null;
        }

        SubLevelReturnTransform.Anchor captured = SubLevelReturnTransform.capture(
                new SablePose(carrier.logicalPose()), vector(player.position()), vector(player.getLookAngle()),
                vector(Vec3.atLowerCornerOf(factory.getBlockPos())));
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        container.addForceLoadTicket(carrier, RETURN_SESSION_TICKET, player.getUUID().toString());
        return new ModAttachments.DynamicReturnAnchor(ID, VERSION, factory.getFactoryId(),
                carrier.getUniqueId().toString(), vec3(captured.offsetFromFactory()),
                vec3(captured.localLookDirection()), null);
    }

    @Override
    public FactoryReturnAnchors.Resolution resolve(ServerPlayer player, ModAttachments.DynamicReturnAnchor anchor,
                                                   NestedFactoryBlockEntity currentFactory) {
        if (anchor.version() != VERSION || !ID.equals(anchor.resolverId())) {
            return FactoryReturnAnchors.Resolution.invalid();
        }
        // Legacy passage anchors are deliberately not resolved: passages on a physical carrier are disabled.
        if (anchor.returnGuardPos() != null) {
            return FactoryReturnAnchors.Resolution.invalid();
        }
        if (currentFactory == null
                || !anchor.factoryId().equals(currentFactory.getFactoryId())
                || !(currentFactory.getLevel() instanceof ServerLevel level)) {
            return FactoryReturnAnchors.Resolution.invalid();
        }

        SubLevelReturnTransform.Anchor captured = new SubLevelReturnTransform.Anchor(
                vector(anchor.localOffset()), vector(anchor.localLookDirection()));
        SubLevel containing = Sable.HELPER.getContaining(currentFactory);
        if (!(containing instanceof ServerSubLevel carrier)) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.inBounds(currentFactory.getBlockPos())) {
                return FactoryReturnAnchors.Resolution.retryLater();
            }
            SubLevelReturnTransform.Vector position = vector(Vec3.atLowerCornerOf(currentFactory.getBlockPos()))
                    .add(captured.offsetFromFactory());
            SubLevelReturnTransform.Vector look = captured.localLookDirection().normalize();
            return FactoryReturnAnchors.Resolution.resolved(destination(level, position, look, Vec3.ZERO));
        }

        SubLevelReturnTransform.Resolved resolved = SubLevelReturnTransform.resolve(
                new SablePose(carrier.logicalPose()), vector(Vec3.atLowerCornerOf(currentFactory.getBlockPos())),
                captured);
        Vec3 velocity = Sable.HELPER.getVelocity(level, carrier, vec3(resolved.localPosition())).scale(1.0 / 20.0);
        return FactoryReturnAnchors.Resolution.resolved(
                destination(level, resolved.position(), resolved.lookDirection(), velocity));
    }

    @Override
    public void release(MinecraftServer server, UUID playerId,
                        ModAttachments.DynamicReturnAnchor anchor) {
        if (anchor.version() != VERSION || !ID.equals(anchor.resolverId())) {
            return;
        }
        UUID carrierId;
        try {
            carrierId = UUID.fromString(anchor.carrierId());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        int currentTick = server.getTickCount();
        synchronized (SableFactoryReturnAnchorAdapter.class) {
            PENDING_RELEASES.computeIfAbsent(server, ignored -> new HashMap<>())
                    .put(new ReleaseKey(playerId, carrierId), new PendingRelease(
                            currentTick + MIN_RELEASE_DELAY_TICKS, currentTick + RELEASE_TIMEOUT_TICKS));
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        Map<ReleaseKey, PendingRelease> releases;
        synchronized (SableFactoryReturnAnchorAdapter.class) {
            releases = PENDING_RELEASES.get(server);
            if (releases == null || releases.isEmpty()) return;
        }

        int currentTick = server.getTickCount();
        Iterator<Map.Entry<ReleaseKey, PendingRelease>> iterator = releases.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ReleaseKey, PendingRelease> entry = iterator.next();
            ReleaseKey key = entry.getKey();
            PendingRelease pending = entry.getValue();
            if (currentTick < pending.earliestTick()) continue;

            ServerPlayer player = server.getPlayerList().getPlayer(key.playerId());
            SubLevel tracking = player == null ? null : Sable.HELPER.getTrackingSubLevel(player);
            boolean trackingDestination = tracking != null && key.carrierId().equals(tracking.getUniqueId());
            if (player != null && !trackingDestination && currentTick < pending.deadlineTick()) continue;

            removeTicket(server, key);
            iterator.remove();
        }
        synchronized (SableFactoryReturnAnchorAdapter.class) {
            if (releases.isEmpty()) PENDING_RELEASES.remove(server);
        }
    }

    private static synchronized void onServerStopped(ServerStoppedEvent event) {
        PENDING_RELEASES.remove(event.getServer());
    }

    private static void removeTicket(MinecraftServer server, ReleaseKey key) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(key.carrierId()) instanceof ServerSubLevel carrier) {
                container.removeForceLoadTicket(carrier, RETURN_SESSION_TICKET, key.playerId().toString());
                return;
            }
        }
    }

    private static FactoryReturnAnchors.Destination destination(ServerLevel level,
                                                                SubLevelReturnTransform.Vector position,
                                                                SubLevelReturnTransform.Vector look,
                                                                Vec3 velocity) {
        double horizontal = Math.sqrt(look.x() * look.x() + look.z() * look.z());
        float yRot = (float) Math.toDegrees(Math.atan2(-look.x(), look.z()));
        float xRot = (float) Math.toDegrees(Math.atan2(-look.y(), horizontal));
        return new FactoryReturnAnchors.Destination(level.dimension(), vec3(position), yRot, xRot, velocity);
    }

    private static SubLevelReturnTransform.Vector vector(Vec3 vector) {
        return new SubLevelReturnTransform.Vector(vector.x, vector.y, vector.z);
    }

    private static Vec3 vec3(SubLevelReturnTransform.Vector vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private record SablePose(Pose3dc pose) implements SubLevelReturnTransform.Pose {
        @Override
        public SubLevelReturnTransform.Vector toLocal(SubLevelReturnTransform.Vector global) {
            return vector(pose.transformPositionInverse(vec3(global)));
        }

        @Override
        public SubLevelReturnTransform.Vector toWorld(SubLevelReturnTransform.Vector local) {
            return vector(pose.transformPosition(vec3(local)));
        }

        @Override
        public SubLevelReturnTransform.Vector normalToLocal(SubLevelReturnTransform.Vector global) {
            return vector(pose.transformNormalInverse(vec3(global)));
        }

        @Override
        public SubLevelReturnTransform.Vector normalToWorld(SubLevelReturnTransform.Vector local) {
            return vector(pose.transformNormal(vec3(local)));
        }
    }

    private record ReleaseKey(UUID playerId, UUID carrierId) {
    }

    private record PendingRelease(int earliestTick, int deadlineTick) {
    }
}

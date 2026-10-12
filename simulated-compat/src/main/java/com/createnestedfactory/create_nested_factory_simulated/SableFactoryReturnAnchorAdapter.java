package com.createnestedfactory.create_nested_factory_simulated;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.FactoryReturnAnchors;
import com.createnestedfactory.create_nested_factory.FactoryPassageNavigation;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Keeps a player's external return point attached to the current pose of a Sable SubLevel. */
public final class SableFactoryReturnAnchorAdapter implements FactoryReturnAnchors.Adapter {
    static final SableFactoryReturnAnchorAdapter INSTANCE = new SableFactoryReturnAnchorAdapter();
    private static final String ID = Create_nested_factory.MODID + ":sable_sublevel";
    private static final int VERSION = 1;
    private static final SubLevelLoadingTicketType<String> RETURN_SESSION_TICKET =
            SubLevelLoadingTicketType.create(
                    ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "return_session"),
                    Codec.STRING);
    private static final int MIN_RELEASE_DELAY_TICKS = 2;
    private static final int RELEASE_TIMEOUT_TICKS = 40;
    private static final int ARRIVAL_TIMEOUT_TICKS = 160;
    private static final Map<MinecraftServer, Map<ReleaseKey, PendingRelease>> PENDING_RELEASES =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, PendingArrival>> ARRIVALS = new IdentityHashMap<>();
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
        if (!retain(level, carrier, player.getUUID())) return null;
        finishArrival(player, "new_entry");
        return new ModAttachments.DynamicReturnAnchor(ID, VERSION, factory.getFactoryId(),
                carrier.getUniqueId().toString(), vec3(captured.offsetFromFactory()),
                vec3(captured.localLookDirection()), null);
    }

    @Override
    public void arrived(ServerPlayer player, ModAttachments.DynamicReturnAnchor anchor,
                        NestedFactoryBlockEntity currentFactory) {
        if (anchor.version() != VERSION || !ID.equals(anchor.resolverId()) || anchor.returnGuardPos() != null
                || currentFactory == null || !anchor.factoryId().equals(currentFactory.getFactoryId())
                || !(currentFactory.getLevel() instanceof ServerLevel level) || player.serverLevel() != level
                || !(Sable.HELPER.getContaining(currentFactory) instanceof ServerSubLevel carrier)
                || !(player instanceof EntityMovementExtension)) return;
        var reference = new ModAttachments.FactoryReference(level.dimension(), currentFactory.getBlockPos(),
                currentFactory.getFactoryId(), currentFactory.getRootFactoryId());
        var pending = new PendingArrival(reference, anchor, player.position(),
                player.server.getTickCount() + ARRIVAL_TIMEOUT_TICKS);
        ARRIVALS.computeIfAbsent(player.server, ignored -> new HashMap<>()).put(player.getUUID(), pending);
        bindCarrier(player, level, carrier, localPosition(currentFactory, anchor), pending);
        FactoryPassageNavigation.trace(player, "dynamic_arrival_follow_started", "factory=" + anchor.factoryId());
    }

    public static boolean hasPendingArrival(ServerPlayer player) {
        var arrivals = ARRIVALS.get(player.server);
        return arrivals != null && arrivals.containsKey(player.getUUID());
    }

    /** Updates vanilla's existing pending teleport; no extra teleport or teleport ID is issued. */
    public static Vec3 followArrival(ServerPlayer player, Vec3 awaitingPosition) {
        var arrivals = ARRIVALS.get(player.server);
        PendingArrival pending = arrivals == null ? null : arrivals.get(player.getUUID());
        if (pending == null) return awaitingPosition;
        if (awaitingPosition == null) {
            finishArrival(player, "acknowledged");
            return null;
        }
        if (!player.isAlive() || player.server.getTickCount() >= pending.deadline
                || !pending.reference.dimension().equals(player.serverLevel().dimension())
                || !awaitingPosition.equals(pending.awaitingPosition)) {
            finishArrival(player, "interrupted");
            return awaitingPosition;
        }
        var resolution = FactoryPassageNavigation.resolveFactory(player.server, pending.reference);
        if (resolution.status() == FactoryPassageNavigation.Status.RETRY_LATER) return awaitingPosition;
        NestedFactoryBlockEntity factory = resolution.factory();
        if (resolution.status() != FactoryPassageNavigation.Status.READY || factory == null
                || factory.getLevel() != player.serverLevel()) {
            finishArrival(player, "factory_unavailable");
            return awaitingPosition;
        }
        ServerLevel level = player.serverLevel();
        Vec3 local = localPosition(factory, pending.anchor);
        SubLevel containing = Sable.HELPER.getContaining(factory);
        Vec3 world = containing == null ? local : containing.logicalPose().transformPosition(local);
        var space = SableFactoryPassageAdapter.INSTANCE.space(level, factory.getBlockPos());
        AABB body = player.getBoundingBox().move(world.subtract(player.position()));
        if (!level.noCollision(player, body)
                || (space != null && !space.coordinates().noCollision(level, player, body))) {
            finishArrival(player, "position_obstructed");
            return awaitingPosition;
        }
        if (containing instanceof ServerSubLevel carrier) {
            if (!carrier.getUniqueId().equals(pending.carrierId)) {
                bindCarrier(player, level, carrier, local, pending);
            } else {
                ((EntityMovementExtension) player).sable$setTrackingSubLevel(carrier);
            }
        } else {
            ((EntityMovementExtension) player).sable$setTrackingSubLevel(null);
            finishArrival(player, "carrier_dismantled");
        }
        player.setPos(world);
        player.fallDistance = 0;
        pending.awaitingPosition = world;
        return world;
    }

    private static Vec3 localPosition(NestedFactoryBlockEntity factory, ModAttachments.DynamicReturnAnchor anchor) {
        return Vec3.atLowerCornerOf(factory.getBlockPos()).add(anchor.localOffset());
    }

    private static void bindCarrier(ServerPlayer player, ServerLevel level, ServerSubLevel carrier,
                                    Vec3 local, PendingArrival pending) {
        if (!retain(level, carrier, player.getUUID())) return;
        deferRelease(player.server, player.getUUID(), carrier.getUniqueId());
        SablePlayerArrival.initialize(player, carrier, local);
        pending.carrierId = carrier.getUniqueId();
    }

    private static boolean retain(ServerLevel level, ServerSubLevel carrier, UUID playerId) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        container.addForceLoadTicket(carrier, RETURN_SESSION_TICKET, playerId.toString());
        // Reacquiring a ticket for a new session must cancel an earlier session's scheduled release.
        var releases = PENDING_RELEASES.get(level.getServer());
        if (releases != null) {
            releases.remove(new ReleaseKey(playerId, carrier.getUniqueId()));
            if (releases.isEmpty()) PENDING_RELEASES.remove(level.getServer());
        }
        return true;
    }

    private static void finishArrival(ServerPlayer player, String reason) {
        var arrivals = ARRIVALS.get(player.server);
        PendingArrival pending = arrivals == null ? null : arrivals.remove(player.getUUID());
        if (pending == null) return;
        if (arrivals.isEmpty()) ARRIVALS.remove(player.server);
        FactoryPassageNavigation.trace(player, "dynamic_arrival_follow_finished",
                "factory=" + pending.anchor.factoryId() + ", reason=" + reason);
    }

    @Override
    public FactoryReturnAnchors.Resolution resolve(ServerPlayer player, ModAttachments.DynamicReturnAnchor anchor,
                                                   NestedFactoryBlockEntity currentFactory) {
        if (anchor.version() != VERSION || !ID.equals(anchor.resolverId())) {
            return FactoryReturnAnchors.Resolution.invalid();
        }
        // Legacy passage anchors cannot identify a stable source passage; current sessions use passage IDs.
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
            return FactoryReturnAnchors.Resolution.resolved(destination(level, position, look));
        }

        SubLevelReturnTransform.Resolved resolved = SubLevelReturnTransform.resolve(
                new SablePose(carrier.logicalPose()), vector(Vec3.atLowerCornerOf(currentFactory.getBlockPos())),
                captured);
        // The arrival hook restores Sable tracking, which supplies carrier motion separately.
        return FactoryReturnAnchors.Resolution.resolved(
                destination(level, resolved.position(), resolved.lookDirection()));
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
        deferRelease(server, playerId, carrierId);
    }

    private static void deferRelease(MinecraftServer server, UUID playerId, UUID carrierId) {
        int currentTick = server.getTickCount();
        synchronized (SableFactoryReturnAnchorAdapter.class) {
            PENDING_RELEASES.computeIfAbsent(server, ignored -> new HashMap<>())
                    .put(new ReleaseKey(playerId, carrierId), new PendingRelease(
                            currentTick + MIN_RELEASE_DELAY_TICKS, currentTick + RELEASE_TIMEOUT_TICKS));
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int currentTick = server.getTickCount();
        var arrivals = ARRIVALS.get(server);
        if (arrivals != null) {
            arrivals.entrySet().removeIf(entry -> {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                return player == null || !player.isAlive() || currentTick >= entry.getValue().deadline
                        || !entry.getValue().reference.dimension().equals(player.serverLevel().dimension());
            });
            if (arrivals.isEmpty()) ARRIVALS.remove(server);
        }
        Map<ReleaseKey, PendingRelease> releases;
        synchronized (SableFactoryReturnAnchorAdapter.class) {
            releases = PENDING_RELEASES.get(server);
            if (releases == null || releases.isEmpty()) return;
        }

        Iterator<Map.Entry<ReleaseKey, PendingRelease>> iterator = releases.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ReleaseKey, PendingRelease> entry = iterator.next();
            ReleaseKey key = entry.getKey();
            PendingRelease pending = entry.getValue();
            if (currentTick < pending.earliestTick()) continue;
            // Server-side tracking can resume before the client has loaded and acknowledged the teleport.
            if (arrivals != null && arrivals.containsKey(key.playerId())) continue;

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
        ARRIVALS.remove(event.getServer());
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
                                                                SubLevelReturnTransform.Vector look) {
        double horizontal = Math.sqrt(look.x() * look.x() + look.z() * look.z());
        float yRot = (float) Math.toDegrees(Math.atan2(-look.x(), look.z()));
        float xRot = (float) Math.toDegrees(Math.atan2(-look.y(), horizontal));
        return new FactoryReturnAnchors.Destination(level.dimension(), vec3(position), yRot, xRot, Vec3.ZERO);
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

    private static final class PendingArrival {
        private final ModAttachments.FactoryReference reference;
        private final ModAttachments.DynamicReturnAnchor anchor;
        private final int deadline;
        private UUID carrierId;
        private Vec3 awaitingPosition;

        private PendingArrival(ModAttachments.FactoryReference reference, ModAttachments.DynamicReturnAnchor anchor,
                               Vec3 awaitingPosition, int deadline) {
            this.reference = reference;
            this.anchor = anchor;
            this.awaitingPosition = awaitingPosition;
            this.deadline = deadline;
        }
    }
}

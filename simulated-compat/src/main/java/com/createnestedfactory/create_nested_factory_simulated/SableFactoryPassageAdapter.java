package com.createnestedfactory.create_nested_factory_simulated;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.FactoryPassageNavigation;
import com.createnestedfactory.create_nested_factory.FactoryPassageGeometry;
import com.createnestedfactory.create_nested_factory.FactoryPassageSavedData;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData;
import dev.ryanhcode.sable.sublevel.tracking_points.TrackingPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sable owns carrier loading and poses; the base passage index continues to own identity and routing. */
public final class SableFactoryPassageAdapter implements FactoryPassageNavigation.Adapter {
    static final SableFactoryPassageAdapter INSTANCE = new SableFactoryPassageAdapter();
    private static final SubLevelLoadingTicketType<String> TICKET = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "passage_session"), Codec.STRING);
    private record Key(ServerLevel level, UUID player, UUID carrier) {}
    private record Lease(int earliestRelease, int deadline) {}
    private static final Map<MinecraftServer, Map<Key, Lease>> LEASES = new IdentityHashMap<>();
    private record PendingArrival(String passageId, Vec3 offset, int deadline) {}
    private static final Map<MinecraftServer, Map<UUID, PendingArrival>> ARRIVALS = new IdentityHashMap<>();

    private SableFactoryPassageAdapter() {}

    static void initialize() {
        NeoForge.EVENT_BUS.addListener(SableFactoryPassageAdapter::tick);
        NeoForge.EVENT_BUS.addListener(SableFactoryPassageAdapter::stopped);
    }

    public String id() { return Create_nested_factory.MODID + ":sable_passage"; }

    private static UUID trackingId(String passageId) {
        return trackingId("passage", passageId);
    }

    private static UUID trackingId(String kind, String identity) {
        return UUID.nameUUIDFromBytes((Create_nested_factory.MODID + ":" + kind + ":" + identity)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static SubLevel carrier(Level level, BlockPos pos) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(pos)) return null;
        var plot = container.getPlot(new ChunkPos(pos));
        return plot == null ? null : plot.getSubLevel();
    }

    @Override
    public FactoryPassageNavigation.Space space(Level level, BlockPos pos) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(pos)) return null;
        SubLevel carrier = carrier(level, pos);
        if (carrier == null || carrier.isRemoved()) return new FactoryPassageNavigation.Space(null, true);
        return new FactoryPassageNavigation.Space(coordinates(level, carrier), true);
    }

    private static FactoryPassageNavigation.Coordinates coordinates(Level level, SubLevel carrier) {
        Pose3d pose = new Pose3d(carrier.logicalPose());
        return new FactoryPassageNavigation.Coordinates() {
            public Vec3 toLocal(Vec3 world) { return pose.transformPositionInverse(world); }
            public Vec3 toWorld(Vec3 local) { return pose.transformPosition(local); }
            public Vec3 normalToWorld(Vec3 local) { return pose.transformNormal(local); }
            public Vec3 velocity(Vec3 local) { return Sable.HELPER.getVelocity(level, carrier, local).scale(1.0 / 20); }
            public boolean noCollision(Level target, ServerPlayer player, AABB world) {
                if (!FactoryPassageGeometry.noCollision(target, player, world, this)) return false;
                var container = SubLevelContainer.getContainer(target);
                if (container == null) return false;
                for (SubLevel other : container.queryIntersecting(new BoundingBox3d(world))) {
                    if (other != carrier && !other.isRemoved() && !FactoryPassageGeometry.noCollision(target, player,
                            world, coordinates(target, other))) return false;
                }
                return true;
            }
        };
    }

    @Override
    public void arrived(ServerPlayer player, FactoryPassageNavigation.Arrival arrival) {
        if (!(arrival.passage().getLevel() instanceof ServerLevel level)
                || !(carrier(level, arrival.passage().getBlockPos()) instanceof ServerSubLevel carrier)
                || !(player instanceof EntityMovementExtension) || arrival.localPosition() == null) return;
        Vec3 local = arrival.localPosition();
        SablePlayerArrival.initialize(player, carrier, local);
        ARRIVALS.computeIfAbsent(player.server, ignored -> new HashMap<>()).put(player.getUUID(),
                new PendingArrival(arrival.passage().getPassageId(),
                        local.subtract(Vec3.atLowerCornerOf(arrival.passage().getBlockPos())), player.server.getTickCount() + 160));
        retain(player, level, carrier);
    }

    /** Called before vanilla processes/resends its awaiting teleport position, preserving the teleport ID. */
    public static Vec3 followArrival(ServerPlayer player, Vec3 awaitingPosition) {
        var arrivals = ARRIVALS.get(player.server);
        if (arrivals == null) return awaitingPosition;
        PendingArrival pending = arrivals.get(player.getUUID());
        if (pending == null) return awaitingPosition;
        if (awaitingPosition == null || !player.isAlive() || player.server.getTickCount() >= pending.deadline()) {
            arrivals.remove(player.getUUID());
            FactoryPassageNavigation.trace(player, "arrival_follow_finished", "passage=" + pending.passageId()
                    + ", acknowledged=" + (awaitingPosition == null));
            return awaitingPosition;
        }
        var endpoint = FactoryPassageSavedData.get(player.server).findByPassageId(pending.passageId());
        if (endpoint == null || !endpoint.dimension().equals(player.serverLevel().dimension())) {
            arrivals.remove(player.getUUID());
            return awaitingPosition;
        }
        var space = INSTANCE.space(player.serverLevel(), endpoint.pos());
        if (space == null) {
            arrivals.remove(player.getUUID());
            return awaitingPosition;
        }
        if (!space.ready()) return awaitingPosition;
        Vec3 world = space.coordinates().toWorld(Vec3.atLowerCornerOf(endpoint.pos()).add(pending.offset()));
        AABB body = player.getBoundingBox().move(world.subtract(player.position()));
        if (!player.serverLevel().noCollision(player, body) || !space.coordinates().noCollision(player.serverLevel(), player, body)) {
            arrivals.remove(player.getUUID());
            FactoryPassageNavigation.trace(player, "arrival_follow_finished", "passage=" + pending.passageId()
                    + ", reason=position_obstructed");
            return awaitingPosition;
        }
        if (carrier(player.serverLevel(), endpoint.pos()) instanceof ServerSubLevel carrier) {
            ((EntityMovementExtension) player).sable$setTrackingSubLevel(carrier);
            retain(player, player.serverLevel(), carrier);
        }
        player.setPos(world);
        player.fallDistance = 0;
        return world;
    }

    @Override
    public FactoryPassageNavigation.Status prepareEndpoint(ServerLevel level, FactoryPassageSavedData.Endpoint endpoint) {
        if (endpoint.passageId().isBlank()) return FactoryPassageNavigation.Status.READY;
        return prepareTrackingPoint(level, trackingId(endpoint.passageId()));
    }

    @Override
    public FactoryPassageNavigation.Status prepareFactory(ServerLevel level, ModAttachments.FactoryReference reference) {
        return prepareTrackingPoint(level, trackingId("factory", reference.factoryId()));
    }

    private static FactoryPassageNavigation.Status prepareTrackingPoint(ServerLevel level, UUID id) {
        var data = SubLevelTrackingPointSavedData.getOrLoad(level);
        TrackingPoint point = data.getTrackingPoint(id);
        if (point == null || !point.inSubLevel()) return FactoryPassageNavigation.Status.READY;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return FactoryPassageNavigation.Status.RETRY_LATER;
        if (!trackingCarrierReady(level, point)) {
            var holding = point.subLevelID() == null ? null : container.getHoldingChunkMap()
                    .getHoldingSubLevel(point.subLevelID());
            if (holding != null) container.getHoldingChunkMap().loadHoldingSubLevel(holding);
            else if (point.lastSavedSubLevelPointer() != null && point.subLevelID() != null) {
                container.getHoldingChunkMap().snatchAndLoad(point.lastSavedSubLevelPointer(), point.subLevelID());
            }
        }
        point = data.getTrackingPoint(id);
        return point == null || (point.inSubLevel() && !trackingCarrierReady(level, point))
                ? FactoryPassageNavigation.Status.RETRY_LATER
                : FactoryPassageNavigation.Status.READY;
    }

    private static boolean trackingCarrierReady(ServerLevel level, TrackingPoint point) {
        SubLevel carrier = carrier(level, BlockPos.containing(point.point().x, point.point().y, point.point().z));
        return carrier != null && !carrier.isRemoved() && carrier.getUniqueId().equals(point.subLevelID());
    }

    @Override
    public void registered(FactoryPassageBlockEntity passage) {
        registerTrackingPoint(passage, trackingId(passage.getPassageId()));
    }

    @Override
    public void registeredFactory(NestedFactoryBlockEntity factory) {
        if (!(factory.getLevel() instanceof ServerLevel level)) return;
        UUID id = trackingId("factory", factory.getFactoryId());
        // Static factories need no additional index unless a previous physical carrier was dismantled.
        if (carrier(level, factory.getBlockPos()) != null
                || SubLevelTrackingPointSavedData.getOrLoad(level).getTrackingPoint(id) != null) {
            registerTrackingPoint(factory, id);
        }
    }

    private void registerTrackingPoint(BlockEntity endpoint, UUID id) {
        if (!(endpoint.getLevel() instanceof ServerLevel level)) return;
        var space = space(level, endpoint.getBlockPos());
        if (space != null && !space.ready()) return;
        Vec3 position = Vec3.atLowerCornerOf(endpoint.getBlockPos()).add(.5, .125, .5);
        SubLevel carrier = carrier(level, endpoint.getBlockPos());
        Vec3 world = carrier == null ? position : carrier.logicalPose().transformPosition(position);
        var data = SubLevelTrackingPointSavedData.getOrLoad(level);
        TrackingPoint point = new TrackingPoint(carrier != null, carrier == null ? null : carrier.getUniqueId(),
                        carrier instanceof ServerSubLevel serverCarrier ? serverCarrier.getLastSerializationPointer() : null,
                        new Vector3d(position.x, position.y, position.z), carrier == null ? null
                        : new Vector3d(world.x, world.y, world.z));
        if (!point.equals(data.getTrackingPoint(id))) data.setTrackingPoint(id, point);
    }

    @Override
    public void destroyed(FactoryPassageBlockEntity passage) {
        if (passage.getLevel() instanceof ServerLevel level) {
            SubLevelTrackingPointSavedData.getOrLoad(level).removeTrackingPoint(trackingId(passage.getPassageId()));
        }
    }

    @Override
    public void destroyedFactory(NestedFactoryBlockEntity factory) {
        if (factory.getLevel() instanceof ServerLevel level) {
            SubLevelTrackingPointSavedData.getOrLoad(level).removeTrackingPoint(trackingId("factory", factory.getFactoryId()));
        }
    }

    @Override
    public void retain(ServerPlayer player, BlockEntity endpoint) {
        if (endpoint.getLevel() instanceof ServerLevel level
                && carrier(level, endpoint.getBlockPos()) instanceof ServerSubLevel carrier) {
            retain(player, level, carrier);
        }
    }

    private static Key retain(ServerPlayer player, ServerLevel level, ServerSubLevel carrier) {
        var container = SubLevelContainer.getContainer(level);
        container.addForceLoadTicket(carrier, TICKET, player.getUUID().toString());
        Key key = new Key(level, player.getUUID(), carrier.getUniqueId());
        int tick = player.server.getTickCount();
        LEASES.computeIfAbsent(player.server, ignored -> new HashMap<>())
                .put(key, new Lease(tick + 2, tick + 40));
        return key;
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        var arrivals = ARRIVALS.get(server);
        if (arrivals != null) {
            arrivals.entrySet().removeIf(entry -> {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                return player == null || !player.isAlive() || server.getTickCount() >= entry.getValue().deadline();
            });
            if (arrivals.isEmpty()) ARRIVALS.remove(server);
        }
        Map<Key, Lease> leases = LEASES.computeIfAbsent(server, ignored -> new HashMap<>());
        int tick = server.getTickCount();
        // Also adopt tickets from carriers loaded later, without scanning active tickets every tick.
        if (tick == 1 || tick % 200 == 0) for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (var entry : container.collectForceLoadTickets().entrySet()) {
                for (var ticket : entry.getValue()) {
                    if (ticket.type() != TICKET) continue;
                    Key key = new Key(level, UUID.fromString((String) ticket.key()), entry.getKey().getUniqueId());
                    leases.putIfAbsent(key, new Lease(tick + 2, tick + 40));
                }
            }
        }
        Set<Key> desired = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Set<String> passages = new HashSet<>();
            var session = player.getData(ModAttachments.FACTORY_SESSION);
            if (!session.isActive()) continue;
            Set<ModAttachments.FactoryReference> factories = new HashSet<>();
            for (var frame : session.stack()) {
                if (frame.passage() && !frame.returnPassageId().isBlank()) passages.add(frame.returnPassageId());
                if (frame.sourceFactory() != null) factories.add(frame.sourceFactory());
            }
            if (!passages.isEmpty() && session.currentFactory() != null) factories.add(session.currentFactory());
            for (String passageId : passages) {
                var endpoint = FactoryPassageSavedData.get(server).findByPassageId(passageId);
                if (endpoint == null) continue;
                ServerLevel level = server.getLevel(endpoint.dimension());
                if (level == null || FactoryPassageNavigation.resolve(server, endpoint).status()
                        != FactoryPassageNavigation.Status.READY) continue;
                endpoint = FactoryPassageSavedData.get(server).findByPassageId(passageId);
                if (endpoint == null) continue;
                level = server.getLevel(endpoint.dimension());
                if (level == null) continue;
                if (carrier(level, endpoint.pos()) instanceof ServerSubLevel carrier) desired.add(retain(player, level, carrier));
            }
            if (!passages.isEmpty()) for (var reference : factories) {
                ServerLevel level = server.getLevel(reference.dimension());
                if (level == null || FactoryPassageNavigation.prepareFactory(level, reference)
                        != FactoryPassageNavigation.Status.READY) continue;
                var location = com.createnestedfactory.create_nested_factory.PocketRegistry
                        .findFactoryLocationById(server, reference.factoryId());
                if (location == null) continue;
                ServerLevel currentLevel = server.getLevel(location.dimension());
                if (currentLevel != null && carrier(currentLevel, location.pos()) instanceof ServerSubLevel carrier) {
                    desired.add(retain(player, currentLevel, carrier));
                }
            }
        }
        var iterator = leases.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Key key = entry.getKey();
            if (desired.contains(key) || tick < entry.getValue().earliestRelease()) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(key.player());
            SubLevel tracking = player == null ? null : Sable.HELPER.getTrackingSubLevel(player);
            if (player != null && (tracking == null || !key.carrier().equals(tracking.getUniqueId()))
                    && tick < entry.getValue().deadline()) continue;
            var container = SubLevelContainer.getContainer(key.level());
            if (container != null && container.getSubLevel(key.carrier()) instanceof ServerSubLevel carrier) {
                container.removeForceLoadTicket(carrier, TICKET, key.player().toString());
            }
            iterator.remove();
        }
    }

    private static void stopped(ServerStoppedEvent event) {
        LEASES.remove(event.getServer());
        ARRIVALS.remove(event.getServer());
    }
}

package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Resolves stable passage identities and all local/world geometry through one optional-compat seam. */
public final class FactoryPassageNavigation {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Adapter> ADAPTERS = new LinkedHashMap<>();
    private static final List<Vec3> STATIC_OFFSETS = offsets();
    private static final Map<Direction, List<Vec3>> MOVING_OFFSETS = movingOffsets();
    private static volatile Adapter[] adapterSnapshot = new Adapter[0];
    private static final int FAILURE_TRACE_INTERVAL = 40;
    // Weak player keys avoid retaining logged-out players or stopped servers for diagnostics.
    private static final Map<ServerPlayer, ArrivalFailureTrace> FAILURE_TRACES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private record ArrivalFailureTrace(String passageId, boolean free, boolean supported, boolean waiting, int tick) {}

    public enum Status { READY, RETRY_LATER, INVALID }

    public interface Coordinates {
        Vec3 toLocal(Vec3 world);
        Vec3 toWorld(Vec3 local);
        Vec3 normalToWorld(Vec3 local);
        Vec3 velocity(Vec3 local);
        default boolean noCollision(Level level, ServerPlayer player, AABB world) {
            return FactoryPassageGeometry.noCollision(level, player, world, this);
        }
    }

    public record Space(Coordinates coordinates, boolean moving) {
        public boolean ready() { return coordinates != null; }
    }

    public interface Adapter {
        String id();
        /** Null means this adapter does not own this space; a space without coordinates is still loading. */
        Space space(Level level, BlockPos pos);
        default Status prepareEndpoint(ServerLevel level, FactoryPassageSavedData.Endpoint endpoint) {
            return Status.READY;
        }
        default void registered(FactoryPassageBlockEntity passage) {}
        default void destroyed(FactoryPassageBlockEntity passage) {}
        default void registeredFactory(NestedFactoryBlockEntity factory) {}
        default void destroyedFactory(NestedFactoryBlockEntity factory) {}
        default Status prepareFactory(ServerLevel level, ModAttachments.FactoryReference reference) { return Status.READY; }
        default void retain(ServerPlayer player, BlockEntity endpoint) {}
        default void arrived(ServerPlayer player, Arrival arrival) {}
    }

    public record EndpointResolution(Status status, FactoryPassageBlockEntity passage) {}
    public record FactoryResolution(Status status, NestedFactoryBlockEntity factory) {}
    public record Arrival(Status status, FactoryPassageBlockEntity passage,
                          FactoryReturnAnchors.Destination destination, Vec3 localPosition) {
        public Arrival(Status status, FactoryPassageBlockEntity passage, FactoryReturnAnchors.Destination destination) {
            this(status, passage, destination, null);
        }
    }

    private static final Space STATIC = new Space(new Coordinates() {
        public Vec3 toLocal(Vec3 value) { return value; }
        public Vec3 toWorld(Vec3 value) { return value; }
        public Vec3 normalToWorld(Vec3 value) { return value; }
        public Vec3 velocity(Vec3 value) { return Vec3.ZERO; }
    }, false);

    private FactoryPassageNavigation() {}

    public static synchronized void register(Adapter adapter) {
        ADAPTERS.put(adapter.id(), adapter);
        adapterSnapshot = ADAPTERS.values().toArray(Adapter[]::new);
    }
    private static Adapter[] adapters() { return adapterSnapshot; }

    public static Space space(Level level, BlockPos pos) {
        if (FactoryPassageMovement.isMoving(level, pos)) return new Space(null, true);
        for (Adapter adapter : adapters()) {
            try {
                Space space = adapter.space(level, pos);
                if (space != null) return space;
            } catch (LinkageError | RuntimeException exception) {
                LOGGER.warn("Passage space adapter {} failed", adapter.id(), exception);
                return new Space(null, true);
            }
        }
        return STATIC;
    }

    public static EndpointResolution resolve(MinecraftServer server, FactoryPassageSavedData.Endpoint endpoint) {
        if (endpoint == null) return new EndpointResolution(Status.INVALID, null);
        ServerLevel level = server.getLevel(endpoint.dimension());
        if (level == null) return new EndpointResolution(Status.INVALID, null);
        for (Adapter adapter : adapters()) {
            try {
                Status status = adapter.prepareEndpoint(level, endpoint);
                if (status != Status.READY) return new EndpointResolution(status, null);
            } catch (LinkageError | RuntimeException exception) {
                LOGGER.warn("Passage endpoint adapter {} failed", adapter.id(), exception);
                return new EndpointResolution(Status.RETRY_LATER, null);
            }
        }
        if (!endpoint.passageId().isBlank()) {
            var current = FactoryPassageSavedData.get(server).findByPassageId(endpoint.passageId());
            if (current != null && !current.equals(endpoint)) return resolve(server, current);
        }
        if (!space(level, endpoint.pos()).ready()) return new EndpointResolution(Status.RETRY_LATER, null);
        level.getChunkAt(endpoint.pos());
        var state = level.getBlockState(endpoint.pos());
        if (!(state.getBlock() instanceof FactoryPassageBlock)
                || state.getValue(FactoryPassageBlock.HALF) != DoubleBlockHalf.LOWER
                || !(level.getBlockEntity(endpoint.pos()) instanceof FactoryPassageBlockEntity passage)
                || (!endpoint.passageId().isBlank() && !endpoint.passageId().equals(passage.getPassageId()))) {
            FactoryPassageSavedData.get(server).unregister(endpoint);
            return new EndpointResolution(Status.INVALID, null);
        }
        var upper = level.getBlockState(endpoint.pos().above());
        if (!upper.is(state.getBlock()) || upper.getValue(FactoryPassageBlock.HALF) != DoubleBlockHalf.UPPER
                || upper.getValue(FactoryPassageBlock.FACING) != state.getValue(FactoryPassageBlock.FACING)) {
            return new EndpointResolution(Status.INVALID, null);
        }
        if (!endpoint.targetFactoryId().equals(passage.getTargetFactoryId())) {
            passage.registerEndpoint();
            return new EndpointResolution(Status.INVALID, null);
        }
        return new EndpointResolution(Status.READY, passage);
    }

    public static Arrival arrival(ServerPlayer player, FactoryPassageSavedData.Endpoint endpoint) {
        EndpointResolution resolved = resolve(player.server, endpoint);
        if (resolved.status() != Status.READY) return new Arrival(resolved.status(), null, null);
        return arrival(player, resolved.passage(), null, null);
    }

    public static Arrival returnArrival(ServerPlayer player, ModAttachments.ReturnFrame frame) {
        var endpoint = FactoryPassageSavedData.get(player.server).findByPassageId(frame.returnPassageId());
        EndpointResolution resolved = resolve(player.server, endpoint);
        if (resolved.status() != Status.READY) return new Arrival(resolved.status(), null, null);
        // Preserve the original static return point when it is still inside this very same passage.
        return arrival(player, resolved.passage(), frame.dimension().equals(resolved.passage().getLevel().dimension())
                ? frame.pos() : null, frame);
    }

    private static Arrival arrival(ServerPlayer player, FactoryPassageBlockEntity passage, Vec3 preferred,
                                   ModAttachments.ReturnFrame frame) {
        Level level = passage.getLevel();
        Space space = space(level, passage.getBlockPos());
        if (!space.ready()) return new Arrival(Status.RETRY_LATER, passage, null);
        Coordinates coordinates = space.coordinates();
        Vec3 local = Vec3.atLowerCornerOf(passage.getBlockPos()).add(.5, .125, .5);
        boolean original = !space.moving() && preferred != null
                && FactoryPassageBlock.isInside(preferred, passage.getBlockPos());
        if (original) local = preferred;
        Vec3 world = coordinates.toWorld(local);
        boolean free = canOccupy(player, level, space, world);
        boolean supported = !space.moving() || hasSupport(player, level, space, world);
        if (!free || !supported) {
            Vec3 rejectedLocal = local, rejectedWorld = world;
            world = null;
            var offsets = space.moving() ? MOVING_OFFSETS.get(passage.getBlockState().getValue(FactoryPassageBlock.FACING))
                    : STATIC_OFFSETS;
            for (Vec3 offset : offsets) {
                Vec3 candidate = local.add(offset);
                Vec3 candidateWorld = coordinates.toWorld(candidate);
                if (canOccupy(player, level, space, candidateWorld) && hasSupport(player, level, space, candidateWorld)) {
                    local = candidate;
                    world = candidateWorld;
                    break;
                }
            }
            traceRejectedArrival(player, passage.getPassageId(), rejectedLocal, rejectedWorld, free, supported, world == null);
            if (world == null) {
                return new Arrival(Status.RETRY_LATER, passage, null);
            }
        }
        Vec3 look = coordinates.normalToWorld(Vec3.atLowerCornerOf(
                passage.getBlockState().getValue(FactoryPassageBlock.FACING).getNormal())).normalize();
        float yaw = original ? frame.yRot() : (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = original ? frame.xRot() : space.moving()
                ? (float) Math.toDegrees(Math.atan2(-look.y, Math.sqrt(look.x * look.x + look.z * look.z)))
                : player.getXRot();
        retain(player, passage);
        trace(player, "arrival_ready", "passage=" + passage.getPassageId() + ", moving=" + space.moving()
                + ", local=" + local + ", world=" + world);
        return new Arrival(Status.READY, passage, new FactoryReturnAnchors.Destination(level.dimension(),
                world, yaw, pitch, coordinates.velocity(local)), local);
    }

    private static boolean canOccupy(ServerPlayer player, Level level, Space space, Vec3 world) {
        return noCollision(player, level, space, player.getBoundingBox().move(world.subtract(player.position())));
    }

    private static boolean hasSupport(ServerPlayer player, Level level, Space space, Vec3 world) {
        AABB below = player.getBoundingBox().move(world.subtract(player.position())).move(0, -.25, 0);
        return space.moving() ? !FactoryPassageGeometry.noCollision(level, player, below, space.coordinates())
                : !level.noCollision(player, below);
    }

    private static List<Vec3> offsets() {
        var result = new ArrayList<Vec3>();
        for (int radius = 1; radius <= 3; radius++) {
            for (int y = -1; y <= 2; y++) for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == radius) result.add(new Vec3(x, y, z));
                }
            }
        }
        return List.copyOf(result);
    }

    private static Map<Direction, List<Vec3>> movingOffsets() {
        var result = new EnumMap<Direction, List<Vec3>>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var offsets = new ArrayList<>(STATIC_OFFSETS);
            Vec3 forward = Vec3.atLowerCornerOf(facing.getNormal());
            offsets.sort(Comparator.comparingDouble(Vec3::lengthSqr)
                    .thenComparingDouble(offset -> -Math.abs(offset.dot(forward)))
                    .thenComparingDouble(offset -> -offset.dot(forward))
                    .thenComparingDouble(offset -> Math.abs(offset.y))
                    .thenComparingDouble(offset -> offset.y).thenComparingDouble(offset -> offset.x)
                    .thenComparingDouble(offset -> offset.z));
            result.put(facing, List.copyOf(offsets));
        }
        return result;
    }

    public static void arrived(ServerPlayer player, Arrival arrival) {
        dispatch(adapter -> adapter.arrived(player, arrival));
    }

    private static boolean noCollision(ServerPlayer player, Level level, Space space, AABB world) {
        return level.noCollision(player, world) && (!space.moving() || space.coordinates().noCollision(level, player, world));
    }

    public static void registered(FactoryPassageBlockEntity passage) {
        dispatch(adapter -> adapter.registered(passage));
    }

    public static void destroyed(FactoryPassageBlockEntity passage) {
        dispatch(adapter -> adapter.destroyed(passage));
    }

    public static void registeredFactory(NestedFactoryBlockEntity factory) {
        dispatch(adapter -> adapter.registeredFactory(factory));
    }

    public static void destroyedFactory(NestedFactoryBlockEntity factory) {
        dispatch(adapter -> adapter.destroyedFactory(factory));
    }

    public static Status prepareFactory(ServerLevel level, ModAttachments.FactoryReference reference) {
        for (Adapter adapter : adapters()) {
            try {
                Status status = adapter.prepareFactory(level, reference);
                if (status != Status.READY) return status;
            } catch (LinkageError | RuntimeException exception) {
                LOGGER.warn("Passage factory adapter {} failed", adapter.id(), exception);
                return Status.RETRY_LATER;
            }
        }
        return Status.READY;
    }

    public static FactoryResolution resolveFactory(MinecraftServer server, ModAttachments.FactoryReference reference) {
        if (reference == null || !reference.isComplete()) return new FactoryResolution(Status.INVALID, null);
        var current = PocketRegistry.findFactoryLocationById(server, reference.factoryId());
        ServerLevel level = server.getLevel(current == null ? reference.dimension() : current.dimension());
        if (level == null) return new FactoryResolution(Status.INVALID, null);
        Status status = prepareFactory(level, reference);
        if (status != Status.READY) return new FactoryResolution(status, null);
        // Loading can allocate a new plot, so resolve the stable identity again before reading blocks.
        current = PocketRegistry.findFactoryLocationById(server, reference.factoryId());
        BlockPos pos = current == null ? reference.pos() : current.pos();
        if (current != null) level = server.getLevel(current.dimension());
        if (level == null) return new FactoryResolution(Status.INVALID, null);
        if (!space(level, pos).ready()) return new FactoryResolution(Status.RETRY_LATER, null);
        level.getChunkAt(pos);
        if (!(level.getBlockEntity(pos) instanceof NestedFactoryBlockEntity factory)
                || !reference.factoryId().equals(factory.getFactoryId())) return new FactoryResolution(Status.INVALID, null);
        return factory.isSimulatedMoveInProgress() ? new FactoryResolution(Status.RETRY_LATER, null)
                : new FactoryResolution(Status.READY, factory);
    }

    public static void retain(ServerPlayer player, BlockEntity endpoint) {
        dispatch(adapter -> adapter.retain(player, endpoint));
    }

    private static void dispatch(java.util.function.Consumer<Adapter> action) {
        for (Adapter adapter : adapters()) {
            try { action.accept(adapter); }
            catch (LinkageError | RuntimeException exception) {
                LOGGER.warn("Passage lifecycle adapter {} failed", adapter.id(), exception);
            }
        }
    }

    public static void guard(ServerPlayer player, FactoryPassageBlockEntity passage) {
        player.setData(ModAttachments.PASSAGE_TRAVEL_GUARD, ModAttachments.PassageTravelGuard.arrival(
                passage.getPassageId(), passage.getLevel().dimension(), passage.getBlockPos()));
    }

    public static void updateTravelGuard(ServerPlayer player) {
        var guard = player.getData(ModAttachments.PASSAGE_TRAVEL_GUARD);
        if (!guard.isActive()) return;
        var endpoint = FactoryPassageSavedData.get(player.server).findByPassageId(guard.passageId());
        var resolution = resolve(player.server, endpoint);
        ModAttachments.PassageTravelGuard updated;
        if (resolution.status() == Status.INVALID) {
            updated = ModAttachments.PassageTravelGuard.empty();
        } else if (resolution.status() == Status.RETRY_LATER) {
            updated = guard.afterTick(player.serverLevel().dimension(), null, true, guard.pos());
        } else {
            var passage = resolution.passage();
            var space = space(passage.getLevel(), passage.getBlockPos());
            AABB region = new AABB(passage.getBlockPos()).expandTowards(0, 1, 0).inflate(.15);
            Boolean inside = space.ready() ? FactoryPassageGeometry.intersects(player.getBoundingBox(), region,
                    space.coordinates()) : null;
            updated = guard.afterTick(player.serverLevel().dimension(), inside, space.moving(), passage.getBlockPos());
        }
        if (!updated.equals(guard)) {
            player.setData(ModAttachments.PASSAGE_TRAVEL_GUARD, updated);
            if (!updated.isActive()) trace(player, "guard_released", "passage=" + guard.passageId()
                    + ", reason=" + (resolution.status() == Status.INVALID ? "endpoint_removed"
                    : !guard.dimension().equals(player.serverLevel().dimension()) ? "dimension_changed" : "left_region")
                    + ", position=" + player.position());
        }
    }

    private static void traceRejectedArrival(ServerPlayer player, String passageId, Vec3 local, Vec3 world,
                                             boolean free, boolean supported, boolean waiting) {
        if (!Config.blackboxDebugLogging) return;
        int tick = player.server.getTickCount();
        var previous = FAILURE_TRACES.get(player);
        if (previous != null && passageId.equals(previous.passageId()) && free == previous.free()
                && supported == previous.supported() && waiting == previous.waiting()) {
            int elapsed = tick - previous.tick();
            if (elapsed >= 0 && elapsed < FAILURE_TRACE_INTERVAL) return;
        }
        FAILURE_TRACES.put(player, new ArrivalFailureTrace(passageId, free, supported, waiting, tick));
        trace(player, "arrival_center_rejected", "passage=" + passageId + ", local=" + local
                + ", world=" + world + ", collisionFree=" + free + ", supported=" + supported);
        if (waiting) trace(player, "arrival_waiting", "passage=" + passageId + ", reason=no_safe_supported_position");
    }

    public static void trace(ServerPlayer player, String event, String details) {
        if (Config.blackboxDebugLogging) LOGGER.info("[CNF-PASSAGE] event={} player={} {}", event,
                player.getGameProfile().getName(), details);
    }
}

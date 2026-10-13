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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Resolves static passage identities and safe arrivals without physical-carrier integrations. */
public final class FactoryPassageNavigation {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Vec3> STATIC_OFFSETS = offsets();
    private static final int FAILURE_TRACE_INTERVAL = 40;
    // Weak player keys avoid retaining logged-out players or stopped servers for diagnostics.
    private static final Map<ServerPlayer, ArrivalFailureTrace> FAILURE_TRACES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private record ArrivalFailureTrace(String passageId, boolean free, boolean supported, boolean waiting, int tick) {}

    public enum Status { READY, RETRY_LATER, INVALID }

    public record EndpointResolution(Status status, FactoryPassageBlockEntity passage) {}
    public record FactoryResolution(Status status, NestedFactoryBlockEntity factory) {}
    public record Destination(net.minecraft.resources.ResourceKey<Level> dimension, Vec3 position,
                              float yRot, float xRot, Vec3 velocity) {}
    public record Arrival(Status status, FactoryPassageBlockEntity passage, Destination destination) {}

    private FactoryPassageNavigation() {}

    public static EndpointResolution resolve(MinecraftServer server, FactoryPassageSavedData.Endpoint endpoint) {
        if (endpoint == null) return new EndpointResolution(Status.INVALID, null);
        ServerLevel level = server.getLevel(endpoint.dimension());
        if (level == null) return new EndpointResolution(Status.INVALID, null);

        if (!endpoint.passageId().isBlank()) {
            var current = FactoryPassageSavedData.get(server).findByPassageId(endpoint.passageId());
            if (current != null && !current.equals(endpoint)) return resolve(server, current);
        }
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
        Vec3 local = Vec3.atLowerCornerOf(passage.getBlockPos()).add(.5, .125, .5);
        boolean original = preferred != null && FactoryPassageBlock.isInside(preferred, passage.getBlockPos());
        if (original) local = preferred;
        Vec3 world = local;
        boolean free = canOccupy(player, level, world);
        if (!free) {
            Vec3 rejected = world;
            world = null;
            for (Vec3 offset : STATIC_OFFSETS) {
                Vec3 candidate = local.add(offset);
                if (canOccupy(player, level, candidate) && hasSupport(player, level, candidate)) {
                    world = candidate;
                    break;
                }
            }
            traceRejectedArrival(player, passage.getPassageId(), rejected, rejected, false, true, world == null);
            if (world == null) return new Arrival(Status.RETRY_LATER, passage, null);
        }
        Vec3 look = Vec3.atLowerCornerOf(passage.getBlockState().getValue(FactoryPassageBlock.FACING).getNormal());
        float yaw = original ? frame.yRot() : (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = original ? frame.xRot() : player.getXRot();
        trace(player, "arrival_ready", "passage=" + passage.getPassageId() + ", world=" + world);
        return new Arrival(Status.READY, passage, new Destination(level.dimension(), world, yaw, pitch, Vec3.ZERO));
    }

    private static boolean canOccupy(ServerPlayer player, Level level, Vec3 world) {
        return level.noCollision(player, player.getBoundingBox().move(world.subtract(player.position())));
    }

    private static boolean hasSupport(ServerPlayer player, Level level, Vec3 world) {
        return !level.noCollision(player, player.getBoundingBox().move(world.subtract(player.position())).move(0, -.25, 0));
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

    public static FactoryResolution resolveFactory(MinecraftServer server, ModAttachments.FactoryReference reference) {
        if (reference == null || !reference.isComplete()) return new FactoryResolution(Status.INVALID, null);
        var current = PocketRegistry.findFactoryLocationById(server, reference.factoryId());
        ServerLevel level = server.getLevel(current == null ? reference.dimension() : current.dimension());
        if (level == null) return new FactoryResolution(Status.INVALID, null);
        // Resolve the stable identity again before reading its current block position.
        current = PocketRegistry.findFactoryLocationById(server, reference.factoryId());
        BlockPos pos = current == null ? reference.pos() : current.pos();
        if (current != null) level = server.getLevel(current.dimension());
        if (level == null) return new FactoryResolution(Status.INVALID, null);
        level.getChunkAt(pos);
        if (!(level.getBlockEntity(pos) instanceof NestedFactoryBlockEntity factory)
                || !reference.factoryId().equals(factory.getFactoryId())) return new FactoryResolution(Status.INVALID, null);
        return new FactoryResolution(Status.READY, factory);
    }

    public static void guard(ServerPlayer player, FactoryPassageBlockEntity passage) {
        ModAttachments.set(player, ModAttachments.PASSAGE_TRAVEL_GUARD, ModAttachments.PassageTravelGuard.arrival(
                passage.getPassageId(), passage.getLevel().dimension(), passage.getBlockPos()));
    }

    public static void updateTravelGuard(ServerPlayer player) {
        var guard = ModAttachments.get(player, ModAttachments.PASSAGE_TRAVEL_GUARD);
        if (!guard.isActive()) return;
        var endpoint = FactoryPassageSavedData.get(player.server).findByPassageId(guard.passageId());
        var resolution = resolve(player.server, endpoint);
        ModAttachments.PassageTravelGuard updated;
        if (resolution.status() == Status.INVALID) {
            updated = ModAttachments.PassageTravelGuard.empty();
        } else if (resolution.status() == Status.RETRY_LATER) {
            updated = guard.afterTick(player.serverLevel().dimension(), null, guard.pos());
        } else {
            var passage = resolution.passage();
            AABB region = new AABB(passage.getBlockPos()).expandTowards(0, 1, 0).inflate(.15);
            boolean inside = region.deflate(1e-9).intersects(player.getBoundingBox());
            updated = guard.afterTick(player.serverLevel().dimension(), inside, passage.getBlockPos());
        }
        if (!updated.equals(guard)) {
            ModAttachments.set(player, ModAttachments.PASSAGE_TRAVEL_GUARD, updated);
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

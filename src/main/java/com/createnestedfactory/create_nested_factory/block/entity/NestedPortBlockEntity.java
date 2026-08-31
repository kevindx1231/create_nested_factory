package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;

import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The room-side endpoint for a factory face.
 *
 * Besides sharing the face's IFluidHandler, this block mirrors Create's pipe pressure
 * across the pocket boundary. An external mechanical pump feeding an INPUT face therefore
 * becomes a virtual room-side pump that pushes from this port into nearby Create pipes.
 * An external pump extracting an OUTPUT face similarly pulls the room-side pipe network
 * into this port.
 */
public class NestedPortBlockEntity extends SyncedBlockEntity implements IHaveGoggleInformation {
    private static final float PRESSURE_EPSILON = 0.001f;

    private int targetPortId = 1;
    private PortMode mappedFaceMode = PortMode.NONE;

    /** Pressure currently mirrored from the external pipe network. */
    private float mirroredPressure = 0f;
    /** True when the room-side virtual pump pulls fluid from nearby room pipes into this port. */
    private boolean mirroredPull = false;
    private boolean mirroredPressureApplied = false;
    private boolean pressureRefreshRequested = true;
    /** Cached local precondition for entering the cross-dimension fluid bridge. */
    private boolean roomSideFluidBridge = false;
    private boolean roomSideFluidBridgeDirty = true;

    /** Runtime-only room-side consumers that have queried INPUT capabilities on this port. */
    private final Block[] itemInputConsumers = new Block[Direction.values().length];
    private final Block[] fluidInputConsumers = new Block[Direction.values().length];

    public NestedPortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NESTED_PORT.get(), pos, state);
    }

    public int getTargetPortId() {
        return targetPortId;
    }

    public void cycleTargetPortId(Player player) {
        NestedFactoryBlockEntity factoryBeforeChange = findFactory();
        if (factoryBeforeChange != null && factoryBeforeChange.isRoomMutationLocked()) {
            if (player != null) {
                PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.room_mutation.active").withStyle(ChatFormatting.YELLOW), false);
            }
            return;
        }
        int old = targetPortId;
        clearInputConsumers();
        targetPortId = targetPortId % 6 + 1;
        refreshMappedMode();
        requestFluidPressureRefresh();
        setChanged();
        sendData();
        if (level != null && !level.isClientSide()) {
            NestedFactoryBlockEntity factory = findFactory();
            if (factory != null) {
                factory.onPortRoutingChanged(player);
            }
            BlockPos roomOrigin = NestedFactoryBlock.findRoomOrigin((ServerLevel) level, worldPosition);
            if (roomOrigin != null) {
                PocketRegistry.unregisterPort(roomOrigin, old, worldPosition);
            }
            level.invalidateCapabilities(worldPosition);
            registerPort();
        }
    }

    public IItemHandler getItemHandler(Direction side) {
        NestedFactoryBlockEntity factory = findFactory();
        if (factory == null) {
            return null;
        }
        if (isMappedInput(factory)) {
            markInputConsumer(side, false, factory);
        }
        return factory.getRoomItemHandler(targetPortId, side);
    }

    public IFluidHandler getFluidHandler(Direction side) {
        NestedFactoryBlockEntity factory = findFactory();
        if (factory == null) {
            return null;
        }
        if (isMappedInput(factory)) {
            markInputConsumer(side, true, factory);
        }
        return factory.getRoomFluidHandler(targetPortId, side);
    }

    private boolean isMappedInput(NestedFactoryBlockEntity factory) {
        Direction face = factory.getFaceForPortId(targetPortId);
        return face != null && factory.getFaceMode(face) == PortMode.INPUT;
    }

    boolean hasItemInputConsumer() {
        return hasInputConsumer(itemInputConsumers);
    }

    boolean hasFluidInputConsumer() {
        return hasInputConsumer(fluidInputConsumers);
    }

    private boolean hasInputConsumer(Block[] consumers) {
        if (level == null || level.isClientSide()) {
            return false;
        }
        for (Direction side : Direction.values()) {
            int index = side.get3DDataValue();
            Block expected = consumers[index];
            if (expected != null && level.getBlockState(worldPosition.relative(side)).getBlock() == expected) {
                return true;
            }
        }
        return false;
    }

    private void markInputConsumer(Direction side, boolean fluid, NestedFactoryBlockEntity factory) {
        if (level == null || level.isClientSide() || side == null) {
            return;
        }
        int index = side.get3DDataValue();
        Block[] consumers = fluid ? fluidInputConsumers : itemInputConsumers;
        Block current = level.getBlockState(worldPosition.relative(side)).getBlock();
        if (consumers[index] == current) {
            return;
        }
        consumers[index] = current;
        factory.onRoomPortLogisticsConnectionChanged();
    }

    /** Called by the block when an adjacent block changes. */
    public void onNeighborChanged(BlockPos neighborPos) {
        boolean changed = false;
        if (level != null && !level.isClientSide()) {
            for (Direction side : Direction.values()) {
                if (!worldPosition.relative(side).equals(neighborPos)) {
                    continue;
                }
                int index = side.get3DDataValue();
                Block current = level.getBlockState(neighborPos).getBlock();
                if (itemInputConsumers[index] != null && itemInputConsumers[index] != current) {
                    itemInputConsumers[index] = null;
                    changed = true;
                }
                if (fluidInputConsumers[index] != null && fluidInputConsumers[index] != current) {
                    fluidInputConsumers[index] = null;
                    changed = true;
                }
                break;
            }
            if (changed) {
                NestedFactoryBlockEntity factory = findFactory();
                if (factory != null) {
                    factory.onRoomPortLogisticsConnectionChanged();
                }
            }
        }
        requestFluidPressureRefresh();
    }

    private void clearInputConsumers() {
        java.util.Arrays.fill(itemInputConsumers, null);
        java.util.Arrays.fill(fluidInputConsumers, null);
    }

    /** Called by the block when the room-side pipe topology changes. */
    public void requestFluidPressureRefresh() {
        pressureRefreshRequested = true;
        roomSideFluidBridgeDirty = true;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(GoggleTooltips.title(getBlockState().getBlock().getName()));
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.target_port",
                String.valueOf(targetPortId), ChatFormatting.AQUA));
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.mapped_mode",
                Component.translatable(modeKey(mappedFaceMode)).withStyle(modeColor(mappedFaceMode))));
        return true;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, NestedPortBlockEntity be) {
        if (level.getGameTime() % 20 == 0) {
            PortMode before = be.mappedFaceMode;
            be.refreshMappedMode();
            if (be.mappedFaceMode != before) {
                be.requestFluidPressureRefresh();
                level.invalidateCapabilities(pos);
                be.sendData();
            }
        }
        if (be.roomSideFluidBridgeDirty) {
            be.refreshRoomSideFluidBridge();
        }
        if (!be.roomSideFluidBridge) {
            be.clearMirroredPressure();
            return;
        }
        be.updateMirroredFluidPressure();
    }

    /**
     * A port only needs the costly cross-dimension pressure lookup when it is mapped to a
     * fluid-capable factory face and a Create fluid pipe can actually receive that pressure
     * on the room side. Item-only and unconnected ports must remain passive.
     */
    private void refreshRoomSideFluidBridge() {
        roomSideFluidBridgeDirty = false;
        roomSideFluidBridge = false;
        if (mappedFaceMode == PortMode.NONE || level == null || level.isClientSide()) {
            return;
        }
        for (Direction side : Direction.values()) {
            BlockPos adjacentPos = worldPosition.relative(side);
            BlockState adjacentState = level.getBlockState(adjacentPos);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, adjacentPos);
            if (pipe != null && pipe.canHaveFlowToward(adjacentState, side.getOpposite())) {
                roomSideFluidBridge = true;
                return;
            }
        }
    }

    /** Clears stale injected pressure when the mapped face or room-side fluid bridge disappears. */
    private void clearMirroredPressure() {
        if (mirroredPressure > PRESSURE_EPSILON || mirroredPressureApplied) {
            resetRoomPipePressure();
        }
        mirroredPressure = 0f;
        mirroredPull = false;
        mirroredPressureApplied = false;
        pressureRefreshRequested = false;
    }

    private void refreshMappedMode() {
        NestedFactoryBlockEntity factory = findFactory();
        PortMode mode = PortMode.NONE;
        if (factory != null) {
            Direction face = factory.getFaceForPortId(targetPortId);
            if (face != null) {
                mode = factory.getFaceMode(face);
            }
        }
        if (mode != mappedFaceMode) {
            mappedFaceMode = mode;
            roomSideFluidBridgeDirty = true;
            setChanged();
        }
    }

    /**
     * Reads the pressure on the corresponding exterior Create pipe connection, then applies
     * the same pressure to the room-side pipe graph. The external and room graphs remain
     * separate Create networks; this is the explicit bridge between them.
     */
    private void updateMirroredFluidPressure() {
        if (level == null || level.isClientSide()) {
            return;
        }

        NestedFactoryBlockEntity factory = findFactory();
        float nextPressure = 0f;
        boolean nextPull = false;
        if (factory != null) {
            NestedFactoryBlockEntity.FluidPortPressure exterior =
                    factory.getExternalFluidPortPressure(targetPortId);
            if (mappedFaceMode == PortMode.INPUT) {
                // Exterior pipe -> factory; expose the factory tank as a pressure-driven source in the room.
                nextPressure = exterior.towardFactory();
            } else if (mappedFaceMode == PortMode.OUTPUT) {
                // Factory -> exterior pipe; pull the room pipe graph into the shared factory tank.
                nextPressure = exterior.awayFromFactory();
                nextPull = true;
            }
        }

        boolean changed = Math.abs(nextPressure - mirroredPressure) > PRESSURE_EPSILON
                || nextPull != mirroredPull;
        boolean becameInactive = nextPressure <= PRESSURE_EPSILON && mirroredPressure > PRESSURE_EPSILON;
        if (changed || becameInactive) {
            if (mirroredPressure > PRESSURE_EPSILON) {
                resetRoomPipePressure();
            }
            mirroredPressure = nextPressure;
            mirroredPull = nextPull;
            mirroredPressureApplied = mirroredPressure > PRESSURE_EPSILON
                    && applyRoomPressure(mirroredPull, mirroredPressure);
            pressureRefreshRequested = false;
            return;
        }

        if (mirroredPressure <= PRESSURE_EPSILON) {
            pressureRefreshRequested = false;
            return;
        }

        // A nearby pipe changing, or another room-side pump updating, can wipe our injected pressure.
        // Reapply only after a detected wipe/topology update; do not add pressure every tick.
        if (pressureRefreshRequested || (mirroredPressureApplied && !isMirroredPressureStillPresent())) {
            if (pressureRefreshRequested) {
                resetRoomPipePressure();
            }
            mirroredPressureApplied = applyRoomPressure(mirroredPull, mirroredPressure);
            pressureRefreshRequested = false;
        }
    }

    private boolean isMirroredPressureStillPresent() {
        boolean foundPipe = false;
        for (Direction side : Direction.values()) {
            BlockPos adjacentPos = worldPosition.relative(side);
            BlockState adjacentState = level.getBlockState(adjacentPos);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, adjacentPos);
            Direction pipeSideFacingPort = side.getOpposite();
            if (pipe == null || !pipe.canHaveFlowToward(adjacentState, pipeSideFacingPort)) {
                continue;
            }
            foundPipe = true;
            PipeConnection connection = pipe.getConnection(pipeSideFacingPort);
            if (connection == null) {
                return false;
            }
            float pressure = mirroredPull
                    ? connection.getPressure().getSecond()
                    : connection.getPressure().getFirst();
            if (pressure <= PRESSURE_EPSILON) {
                return false;
            }
        }
        return !foundPipe || mirroredPressureApplied;
    }

    /** Clears pressure/flow state in every Create pipe network touching this port. */
    private void resetRoomPipePressure() {
        for (Direction side : Direction.values()) {
            BlockPos adjacentPos = worldPosition.relative(side);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, adjacentPos);
            if (pipe != null) {
                FluidPropagator.propagateChangedPipe(level, adjacentPos, level.getBlockState(adjacentPos));
            }
        }
    }

    /**
     * Equivalent to the relevant part of Create's mechanical-pump pressure propagation,
     * except the virtual pump begins at this port and can be fed from another dimension.
     */
    private boolean applyRoomPressure(boolean pull, float pressure) {
        boolean applied = false;
        for (Direction side : Direction.values()) {
            applied |= distributePressureTo(side, pull, pressure);
        }
        return applied;
    }

    private boolean distributePressureTo(Direction side, boolean pull, float pressure) {
        BlockFace start = new BlockFace(worldPosition, side);
        BlockPos firstPipePos = start.getConnectedPos();
        FluidTransportBehaviour firstPipe = FluidPropagator.getPipe(level, firstPipePos);
        if (firstPipe == null) {
            return false;
        }

        Set<BlockFace> targets = new HashSet<>();
        Map<BlockPos, PipeGraphNode> pipeGraph = new HashMap<>();
        if (!pull) {
            // The port is about to become a new source for this graph.
            FluidPropagator.resetAffectedFluidNetworks(level, firstPipePos, side.getOpposite());
        }

        if (!hasReachedValidEndpoint(level, start, pull)) {
            node(pipeGraph, worldPosition, 0).connections.put(side, pull);
            node(pipeGraph, firstPipePos, 1).connections.put(side.getOpposite(), !pull);

            List<PipePathNode> frontier = new ArrayList<>();
            Set<BlockPos> visited = new HashSet<>();
            int maxDistance = FluidPropagator.getPumpRange();
            frontier.add(new PipePathNode(1, firstPipePos));

            while (!frontier.isEmpty()) {
                PipePathNode entry = frontier.remove(0);
                int distance = entry.distance();
                BlockPos currentPos = entry.pos();
                if (!level.isLoaded(currentPos) || !visited.add(currentPos)) {
                    continue;
                }

                BlockState currentState = level.getBlockState(currentPos);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, currentPos);
                if (pipe == null) {
                    continue;
                }

                for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                    BlockFace blockFace = new BlockFace(currentPos, face);
                    BlockPos connectedPos = blockFace.getConnectedPos();
                    if (!level.isLoaded(connectedPos) || blockFace.isEquivalent(start)) {
                        continue;
                    }

                    if (hasReachedValidEndpoint(level, blockFace, pull)) {
                        node(pipeGraph, currentPos, distance).connections.put(face, pull);
                        targets.add(blockFace);
                        continue;
                    }

                    FluidTransportBehaviour connectedPipe = FluidPropagator.getPipe(level, connectedPos);
                    if (connectedPipe == null || level.getBlockEntity(connectedPos) instanceof PumpBlockEntity
                            || visited.contains(connectedPos)) {
                        continue;
                    }
                    if (distance + 1 >= maxDistance) {
                        node(pipeGraph, currentPos, distance).connections.put(face, pull);
                        targets.add(blockFace);
                        continue;
                    }

                    node(pipeGraph, currentPos, distance).connections.put(face, pull);
                    node(pipeGraph, connectedPos, distance + 1).connections.put(face.getOpposite(), !pull);
                    frontier.add(new PipePathNode(distance + 1, connectedPos));
                }
            }
        }

        Map<Integer, Set<BlockFace>> validFaces = new HashMap<>();
        searchForEndpointRecursively(pipeGraph, targets, validFaces,
                new BlockFace(start.getPos(), start.getOppositeFace()), pull);

        boolean applied = false;
        for (Set<BlockFace> faces : validFaces.values()) {
            int parallelBranches = Math.max(1, faces.size() - 1);
            for (BlockFace face : faces) {
                BlockPos pipePos = face.getPos();
                if (pipePos.equals(worldPosition)) {
                    continue;
                }
                PipeGraphNode graphNode = pipeGraph.get(pipePos);
                if (graphNode == null) {
                    continue;
                }
                Boolean inbound = graphNode.connections.get(face.getFace());
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
                if (inbound == null || pipe == null) {
                    continue;
                }
                pipe.addPressure(face.getFace(), inbound, pressure / parallelBranches);
                applied = true;
            }
        }
        return applied;
    }

    private static PipeGraphNode node(Map<BlockPos, PipeGraphNode> graph, BlockPos pos, int distance) {
        return graph.computeIfAbsent(pos, ignored -> new PipeGraphNode(distance));
    }

    private static boolean hasReachedValidEndpoint(Level world, BlockFace blockFace, boolean pull) {
        BlockPos connectedPos = blockFace.getConnectedPos();
        BlockState connectedState = world.getBlockState(connectedPos);
        BlockEntity blockEntity = world.getBlockEntity(connectedPos);
        Direction face = blockFace.getFace();

        if (PumpBlock.isPump(connectedState)
                && connectedState.getValue(PumpBlock.FACING).getAxis() == face.getAxis()
                && blockEntity instanceof PumpBlockEntity pump) {
            boolean pumpFrontFacesCurrentPipe = connectedState.getValue(PumpBlock.FACING) == face.getOpposite();
            return pump.isPullingOnSide(pumpFrontFacesCurrentPipe) != pull;
        }

        FluidTransportBehaviour pipe = FluidPropagator.getPipe(world, connectedPos);
        if (pipe != null && pipe.canHaveFlowToward(connectedState, face.getOpposite())) {
            return false;
        }

        if (blockEntity != null) {
            IFluidHandler capability = world.getCapability(Capabilities.FluidHandler.BLOCK, connectedPos,
                    face.getOpposite());
            if (capability != null) {
                return true;
            }
        }

        return FluidPropagator.isOpenEnd(world, blockFace.getPos(), face);
    }

    private static boolean searchForEndpointRecursively(Map<BlockPos, PipeGraphNode> pipeGraph,
                                                         Set<BlockFace> targets,
                                                         Map<Integer, Set<BlockFace>> validFaces,
                                                         BlockFace currentFace,
                                                         boolean pull) {
        PipeGraphNode current = pipeGraph.get(currentFace.getPos());
        if (current == null) {
            return false;
        }

        boolean successful = false;
        for (Direction nextFacing : Direction.values()) {
            if (nextFacing == currentFace.getFace()) {
                continue;
            }
            Boolean directionPull = current.connections.get(nextFacing);
            if (directionPull == null) {
                continue;
            }

            BlockFace localTarget = new BlockFace(currentFace.getPos(), nextFacing);
            if (targets.contains(localTarget)) {
                validFaces.computeIfAbsent(current.distance, ignored -> new HashSet<>()).add(localTarget);
                successful = true;
                continue;
            }
            if (directionPull != pull) {
                continue;
            }
            if (!searchForEndpointRecursively(pipeGraph, targets, validFaces,
                    new BlockFace(currentFace.getPos().relative(nextFacing), nextFacing.getOpposite()), pull)) {
                continue;
            }

            validFaces.computeIfAbsent(current.distance, ignored -> new HashSet<>()).add(localTarget);
            successful = true;
        }

        if (successful) {
            validFaces.computeIfAbsent(current.distance, ignored -> new HashSet<>()).add(currentFace);
        }
        return successful;
    }

    private static String modeKey(PortMode mode) {
        return switch (mode) {
            case INPUT -> "goggles.create_nested_factory.port_mode.input";
            case OUTPUT -> "goggles.create_nested_factory.port_mode.output";
            case NONE -> "goggles.create_nested_factory.port_mode.none";
        };
    }

    private static ChatFormatting modeColor(PortMode mode) {
        return switch (mode) {
            case INPUT -> ChatFormatting.GREEN;
            case OUTPUT -> ChatFormatting.AQUA;
            case NONE -> ChatFormatting.GRAY;
        };
    }

    private NestedFactoryBlockEntity findFactory() {
        if (level == null || level.isClientSide()) {
            return null;
        }
        return NestedFactoryBlock.findFactoryAt((ServerLevel) level, worldPosition);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            registerPort();
            PortMode previousMode = mappedFaceMode;
            refreshMappedMode();
            // A freshly placed port starts as NONE on the client. Synchronize the
            // server-resolved face mode now instead of waiting for a right click or reload.
            if (mappedFaceMode != previousMode) {
                sendData();
            }
            requestFluidPressureRefresh();
        }
    }

    public void onBlockDestroyed() {
        onBlockDestroyed(null);
    }

    public void onBlockDestroyed(Player player) {
        if (level == null || level.isClientSide()) {
            return;
        }
        NestedFactoryBlockEntity factory = findFactory();
        if (factory != null) {
            factory.onPortRoutingChanged(player);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            // During server shutdown this method runs for every loaded port. Do not invoke
            // Create's pipe propagator unless this port actually injected mirrored pressure.
            clearMirroredPressure();
            BlockPos roomOrigin = NestedFactoryBlock.findRoomOrigin((ServerLevel) level, worldPosition);
            if (roomOrigin != null) {
                PocketRegistry.unregisterPort(roomOrigin, targetPortId, worldPosition);
            }
        }
        super.setRemoved();
    }

    private void registerPort() {
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockPos roomOrigin = NestedFactoryBlock.findRoomOrigin((ServerLevel) level, worldPosition);
        if (roomOrigin != null) {
            PocketRegistry.registerPort(roomOrigin, targetPortId, worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("TargetPortId", targetPortId);
        tag.putString("MappedFaceMode", mappedFaceMode.getSerializedName());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        targetPortId = tag.getInt("TargetPortId");
        if (targetPortId < 1 || targetPortId > 6) {
            targetPortId = 1;
        }
        String mode = tag.getString("MappedFaceMode");
        mappedFaceMode = mode.isEmpty() ? PortMode.NONE : PortMode.valueOf(mode.toUpperCase(Locale.ROOT));
        requestFluidPressureRefresh();
    }

    private record PipePathNode(int distance, BlockPos pos) {
    }

    private static final class PipeGraphNode {
        private final int distance;
        private final Map<Direction, Boolean> connections = new HashMap<>();

        private PipeGraphNode(int distance) {
            this.distance = distance;
        }
    }
}

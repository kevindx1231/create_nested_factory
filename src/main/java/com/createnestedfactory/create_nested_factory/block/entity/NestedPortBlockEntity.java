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
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
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
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    /** Pressure mirrored outward when a room-side pump drives the external Create pipe. */
    private float outwardPressure = 0f;
    private boolean outwardPull = false;
    private boolean outwardPressureApplied = false;
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
        return factory.getRoomFluidHandler(targetPortId, worldPosition, side);
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
            // A downstream tank or machine can change the endpoint of an already existing
            // pipe without notifying this port. Refresh every room-side pipe whenever any
            // port face changes, so an older pipe cannot retain a stale FlowSource.
            refreshRoomFluidNetworks();
            NestedFactoryBlockEntity factory = findFactory();
            if (factory != null) {
                factory.onRoomPortLogisticsConnectionChanged();
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
        NestedFactoryBlockEntity factory = be.findFactory();
        if (!be.roomSideFluidBridge) {
            be.clearMirroredPressure();
            return;
        }
        if (factory != null && level.getGameTime() % 5 == 0) {
            factory.refreshRoomFluidNetworksIfSignatureChanged(be.targetPortId);
        }
        be.updateMirroredFluidPressure();
        be.updateOutwardFluidPressure();
    }

    /**
     * A port only needs the costly cross-dimension pressure lookup when it is mapped to a
     * fluid-capable factory face and a Create fluid pipe can actually receive that pressure
     * on the room side. Item-only and unconnected ports must remain passive.
     */
    private void refreshRoomSideFluidBridge() {
        boolean wasBridgeActive = roomSideFluidBridge;
        roomSideFluidBridgeDirty = false;
        roomSideFluidBridge = false;
        if (mappedFaceMode != PortMode.NONE && level != null && !level.isClientSide()) {
            for (Direction side : Direction.values()) {
                BlockPos adjacentPos = worldPosition.relative(side);
                BlockState adjacentState = level.getBlockState(adjacentPos);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, adjacentPos);
                if (pipe != null && pipe.canHaveFlowToward(adjacentState, side.getOpposite())) {
                    roomSideFluidBridge = true;
                    break;
                }
            }
        }
        if (wasBridgeActive != roomSideFluidBridge) {
            refreshRoomFluidNetworks();
        }
    }

    /**
     * Fully rebuilds every Create fluid network connected to this port. A pressure propagation
     * pass alone is insufficient when a downstream tank is added after the first pipe: Create
     * may still retain the old FluidNetwork target set. Reset every connection in the reachable
     * pipe graph before asking Create to propagate again.
     */
    void refreshRoomFluidNetworks() {
        if (level == null || level.isClientSide()) {
            return;
        }
        NestedFactoryBlockEntity factory = findFactory();
        Set<BlockPos> visitedPipes = new HashSet<>();
        Deque<BlockPos> pendingPipes = new ArrayDeque<>();
        for (Direction side : Direction.values()) {
            BlockPos adjacentPos = worldPosition.relative(side);
            if (FluidPropagator.getPipe(level, adjacentPos) != null) {
                pendingPipes.add(adjacentPos);
            }
        }

        while (!pendingPipes.isEmpty()) {
            BlockPos pipePos = pendingPipes.removeFirst();
            if (!level.isLoaded(pipePos) || !visitedPipes.add(pipePos)) {
                continue;
            }
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
            if (pipe == null) {
                continue;
            }
            // Clear the complete pipe state, including pressure, Flow and FlowSource. Merely
            // resetting FluidNetwork leaves a previously open/blocked connection reusable.
            pipe.wipePressure();
            BlockState pipeState = level.getBlockState(pipePos);
            for (Direction face : FluidPropagator.getPipeConnections(pipeState, pipe)) {
                PipeConnection connection = pipe.getConnection(face);
                if (connection != null) {
                    connection.resetNetwork();
                }
                BlockPos connectedPos = pipePos.relative(face);
                if (FluidPropagator.getPipe(level, connectedPos) != null) {
                    pendingPipes.addLast(connectedPos);
                }
            }
        }

        // Rebuild each port-adjacent root after all reachable connections have been reset.
        for (Direction side : Direction.values()) {
            BlockPos adjacentPos = worldPosition.relative(side);
            BlockState adjacentState = level.getBlockState(adjacentPos);
            if (FluidPropagator.getPipe(level, adjacentPos) != null) {
                FluidPropagator.propagateChangedPipe(level, adjacentPos, adjacentState);
            }
        }
        if (factory != null) {
            // Rebuild the external root after the room-side pump network is rebuilt. The
            // external chain itself did not change, but its virtual endpoint did.
            factory.refreshExternalFluidNetworks(targetPortId);
        }
    }

    /** Clears stale injected pressure when the mapped face or room-side fluid bridge disappears. */
    private void clearMirroredPressure() {
        if (mirroredPressure > PRESSURE_EPSILON || mirroredPressureApplied) {
            resetRoomPipePressure();
        }
        NestedFactoryBlockEntity factory = findFactory();
        if (factory != null) {
            clearOutwardPressure(factory);
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
            refreshRoomFluidNetworks();
            setChanged();
        }
    }

    /**
     * The pressure bridge writes into the same Create connection that it later reads. Ignore the
     * portion owned by this port, otherwise an internal pump is misclassified as an external pump
     * on the next tick and both pressure directions oscillate.
     */
    private NestedFactoryBlockEntity.FluidPortPressure externalPressureWithoutOwnDrive(
            NestedFactoryBlockEntity.FluidPortPressure raw) {
        if (!outwardPressureApplied || outwardPressure <= PRESSURE_EPSILON) {
            return raw;
        }
        float towardFactory = raw.towardFactory();
        float awayFromFactory = raw.awayFromFactory();
        if (outwardPull && towardFactory <= outwardPressure + PRESSURE_EPSILON) {
            towardFactory = 0f;
        }
        if (!outwardPull && awayFromFactory <= outwardPressure + PRESSURE_EPSILON) {
            awayFromFactory = 0f;
        }
        return new NestedFactoryBlockEntity.FluidPortPressure(towardFactory, awayFromFactory);
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
                    externalPressureWithoutOwnDrive(factory.getExternalFluidPortPressure(targetPortId));
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

    private void updateOutwardFluidPressure() {
        if (level == null || level.isClientSide() || mappedFaceMode == PortMode.NONE) {
            return;
        }
        NestedFactoryBlockEntity factory = findFactory();
        if (factory == null) {
            return;
        }

        NestedFactoryBlockEntity.FluidPortPressure rawExterior =
                factory.getExternalFluidPortPressure(targetPortId);
        NestedFactoryBlockEntity.FluidPortPressure exterior =
                externalPressureWithoutOwnDrive(rawExterior);
        boolean externalDrivesThisPort = mappedFaceMode == PortMode.INPUT
                ? exterior.towardFactory() > PRESSURE_EPSILON
                : exterior.awayFromFactory() > PRESSURE_EPSILON;
        if (externalDrivesThisPort) {
            clearOutwardPressure(factory);
            return;
        }

        float nextPressure = 0f;
        boolean nextPull = false;
        for (Direction side : Direction.values()) {
            BlockPos adjacentPos = worldPosition.relative(side);
            BlockState adjacentState = level.getBlockState(adjacentPos);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, adjacentPos);
            Direction pipeSideFacingPort = side.getOpposite();
            if (pipe == null || !pipe.canHaveFlowToward(adjacentState, pipeSideFacingPort)) {
                continue;
            }
            PipeConnection connection = pipe.getConnection(pipeSideFacingPort);
            if (connection == null) {
                continue;
            }
            if (mappedFaceMode == PortMode.OUTPUT) {
                float pressure = Math.max(0f, connection.getPressure().getSecond());
                if (pressure > nextPressure) {
                    nextPressure = pressure;
                    nextPull = false;
                }
            } else if (mappedFaceMode == PortMode.INPUT) {
                float pressure = Math.max(0f, connection.getPressure().getFirst());
                if (pressure > nextPressure) {
                    nextPressure = pressure;
                    nextPull = true;
                }
            }
        }

        boolean changed = Math.abs(nextPressure - outwardPressure) > PRESSURE_EPSILON
                || nextPull != outwardPull;
        boolean becameInactive = nextPressure <= PRESSURE_EPSILON && outwardPressure > PRESSURE_EPSILON;
        if (changed || becameInactive) {
            if (outwardPressureApplied) {
                factory.refreshExternalFluidNetworks();
            }
            outwardPressure = nextPressure;
            outwardPull = nextPull;
            outwardPressureApplied = outwardPressure > PRESSURE_EPSILON
                    && factory.applyExternalFluidPressure(targetPortId, outwardPull, outwardPressure);
            return;
        }

        if (outwardPressure <= PRESSURE_EPSILON) {
            return;
        }
        if (outwardPressureApplied && !factory.hasExternalFluidPressure(targetPortId, outwardPull)) {
            outwardPressureApplied = factory.applyExternalFluidPressure(targetPortId, outwardPull, outwardPressure);
        }
    }

    private void clearOutwardPressure(NestedFactoryBlockEntity factory) {
        if (outwardPressureApplied) {
            factory.refreshExternalFluidNetworks();
        }
        outwardPressure = 0f;
        outwardPull = false;
        outwardPressureApplied = false;
    }

    private boolean isMirroredPressureStillPresent() {
        boolean foundPipe = false;
        boolean foundPressure = false;
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
            if (pressure > PRESSURE_EPSILON) {
                foundPressure = true;
            }
        }
        return !foundPipe || foundPressure;
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
            applied |= FluidPressureBridge.apply(level, worldPosition, side, pull, pressure);
        }
        return applied;
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

}

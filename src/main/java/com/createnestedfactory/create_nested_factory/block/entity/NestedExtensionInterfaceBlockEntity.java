package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.block.NestedExtensionInterfaceBlock;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/** Identity-bound, bufferless proxy for one exterior face of a nested factory. */
public class NestedExtensionInterfaceBlockEntity extends SyncedBlockEntity implements IHaveGoggleInformation {
    private String boundFactoryId = "";

    private boolean lastBound;
    private boolean lastPowered;
    private PortMode lastMode = PortMode.NONE;

    private final IItemHandler[] itemProxies = new IItemHandler[Direction.values().length];
    private final IFluidHandler[] fluidProxies = new IFluidHandler[Direction.values().length];

    public NestedExtensionInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NESTED_EXTENSION_INTERFACE.get(), pos, state);
        for (Direction side : Direction.values()) {
            itemProxies[side.get3DDataValue()] = new ItemProxy(side);
            fluidProxies[side.get3DDataValue()] = new FluidProxy(side);
        }
    }

    public Direction getAttachmentDirection() {
        return getBlockState().getValue(NestedExtensionInterfaceBlock.FACING);
    }

    /** The factory face whose outward normal points toward this interface. */
    public Direction getHostFace() {
        return getAttachmentDirection().getOpposite();
    }

    public List<Direction> getExternalSides() {
        Direction attachedSide = getAttachmentDirection();
        List<Direction> result = new ArrayList<>(5);
        for (Direction side : Direction.values()) {
            if (side != attachedSide) {
                result.add(side);
            }
        }
        return result;
    }

    public NestedFactoryBlockEntity getHostFactory() {
        if (level == null || boundFactoryId.isBlank()
                || level.getBlockEntity(worldPosition) != this
                || !(level.getBlockState(worldPosition).getBlock() instanceof NestedExtensionInterfaceBlock)) {
            return null;
        }
        return adjacentBoundFactory();
    }

    private NestedFactoryBlockEntity adjacentBoundFactory() {
        if (level == null || boundFactoryId.isBlank()) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(getAttachmentDirection()));
        if (blockEntity instanceof NestedFactoryBlockEntity factory
                && boundFactoryId.equals(factory.getFactoryId())) {
            return factory;
        }
        return null;
    }

    public boolean isBoundTo(NestedFactoryBlockEntity factory, Direction hostFace) {
        return factory != null
                && hostFace == getHostFace()
                && worldPosition.relative(getAttachmentDirection()).equals(factory.getBlockPos())
                && !boundFactoryId.isBlank()
                && boundFactoryId.equals(factory.getFactoryId());
    }

    public boolean isPowered() {
        return getBlockState().getValue(NestedExtensionInterfaceBlock.POWERED);
    }

    public boolean isOperational() {
        return !isPowered() && resolveBoundPort() != null;
    }

    /**
     * Resolves the identity-bound logical factory port without applying the external-side or
     * redstone access policy. Callers that expose a capability must use {@link #resolvePort(Direction)}.
     */
    public FactoryLogicalPortEndpoint resolveBoundPort() {
        NestedFactoryBlockEntity factory = getHostFactory();
        if (factory == null) {
            return null;
        }
        return factory.resolveLogicalPort(getHostFace());
    }

    /**
     * The single seam for every bufferless extension capability. A cached proxy must resolve this
     * endpoint again on every operation so redstone, movement and identity replacement take effect.
     */
    public FactoryLogicalPortEndpoint resolvePort(Direction queriedSide) {
        if (queriedSide == null || queriedSide == getAttachmentDirection() || isPowered()) {
            return null;
        }
        return resolveBoundPort();
    }

    public void bindAtPlacement() {
        if (level == null || level.isClientSide() || !boundFactoryId.isBlank()) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(getAttachmentDirection()));
        if (blockEntity instanceof NestedFactoryBlockEntity factory) {
            boundFactoryId = factory.getFactoryId();
            setChanged();
            sendData();
            refreshBoundary(factory);
        }
        rememberState();
    }

    public void cycleHostMode(ServerPlayer player) {
        NestedFactoryBlockEntity factory = getHostFactory();
        if (factory == null) {
            PlayerMessagePayload.sendTo(player,
                    Component.translatable("message.create_nested_factory.extension.unbound")
                            .withStyle(ChatFormatting.YELLOW), false);
            return;
        }
        factory.cycleFaceMode(getHostFace(), player);
    }

    public IItemHandler getItemHandler(Direction side) {
        FactoryLogicalPortEndpoint endpoint = resolvePort(side);
        return endpoint != null && endpoint.extensionItemHandler() != null
                ? itemProxies[side.get3DDataValue()] : null;
    }

    public IFluidHandler getFluidHandler(Direction side) {
        FactoryLogicalPortEndpoint endpoint = resolvePort(side);
        return endpoint != null && endpoint.extensionFluidHandler() != null
                ? fluidProxies[side.get3DDataValue()] : null;
    }

    public boolean acceptUnpackedItems(Direction side, List<ItemStack> items, boolean simulate) {
        FactoryLogicalPortEndpoint endpoint = resolvePort(side);
        return endpoint != null && endpoint.acceptUnpackedItems(items, simulate);
    }

    public boolean isExternalSideAvailable(Direction side) {
        return resolvePort(side) != null;
    }

    private IItemHandler currentItemDelegate(Direction queriedSide) {
        FactoryLogicalPortEndpoint endpoint = resolvePort(queriedSide);
        return endpoint == null ? null : endpoint.extensionItemHandler();
    }

    private IFluidHandler currentFluidDelegate(Direction queriedSide) {
        FactoryLogicalPortEndpoint endpoint = resolvePort(queriedSide);
        return endpoint == null ? null : endpoint.extensionFluidHandler();
    }

    /** Returns every mechanically valid source on the five exposed sides. */
    public List<KineticBlockEntity> getStressInputs() {
        FactoryLogicalPortEndpoint endpoint = resolveBoundPort();
        if (level == null || isPowered() || endpoint == null) {
            return List.of();
        }
        List<KineticBlockEntity> result = new ArrayList<>();
        for (Direction side : getExternalSides()) {
            BlockPos inputPos = worldPosition.relative(side);
            BlockState inputState = level.getBlockState(inputPos);
            if (!(inputState.getBlock() instanceof IRotate rotate)
                    || !rotate.hasShaftTowards(level, inputPos, inputState, side.getOpposite())) {
                continue;
            }
            if (level.getBlockEntity(inputPos) instanceof KineticBlockEntity kinetic
                    && !(kinetic instanceof BeltBlockEntity)) {
                result.add(kinetic);
            }
        }
        return result;
    }

    public NestedFactoryBlockEntity.FluidPortPressure getExternalFluidPressure() {
        FactoryLogicalPortEndpoint endpoint = resolveBoundPort();
        if (level == null || level.isClientSide() || isPowered() || endpoint == null) {
            return NestedFactoryBlockEntity.FluidPortPressure.NONE;
        }
        float towardFactory = 0f;
        float awayFromFactory = 0f;
        for (Direction side : getExternalSides()) {
            BlockPos adjacentPos = worldPosition.relative(side);
            BlockState adjacentState = level.getBlockState(adjacentPos);
            FluidTransportBehaviour transport = FluidPropagator.getPipe(level, adjacentPos);
            Direction pipeSideFacingInterface = side.getOpposite();
            if (transport == null || !transport.canHaveFlowToward(adjacentState, pipeSideFacingInterface)) {
                continue;
            }
            PipeConnection connection = transport.getConnection(pipeSideFacingInterface);
            if (connection == null) {
                continue;
            }
            var pressure = connection.getPressure();
            towardFactory = Math.max(towardFactory, Math.max(0f, pressure.getSecond()));
            awayFromFactory = Math.max(awayFromFactory, Math.max(0f, pressure.getFirst()));
        }
        return new NestedFactoryBlockEntity.FluidPortPressure(towardFactory, awayFromFactory);
    }

    public boolean applyExternalFluidPressure(boolean pull, float pressure) {
        FactoryLogicalPortEndpoint endpoint = resolveBoundPort();
        if (level == null || level.isClientSide() || isPowered() || endpoint == null) {
            return false;
        }
        boolean applied = false;
        for (Direction side : getExternalSides()) {
            applied |= FluidPressureBridge.apply(level, worldPosition, side, pull, pressure);
        }
        return applied;
    }

    public void onNeighborChanged(BlockPos neighborPos) {
        if (level == null || level.isClientSide()) {
            return;
        }
        level.invalidateCapabilities(worldPosition);
        NestedFactoryBlockEntity factory = getHostFactory();
        if (factory != null) {
            refreshBoundary(factory);
        }
        if (worldPosition.relative(getAttachmentDirection()).equals(neighborPos)) {
            sendData();
        }
        rememberState();
    }

    public void onHostStateChanged() {
        if (level == null || level.isClientSide()) {
            return;
        }
        level.invalidateCapabilities(worldPosition);
        sendData();
        rememberState();
    }

    public void onRemovedFromWorld() {
        refreshAdjacentFluidPipes();
        NestedFactoryBlockEntity factory = adjacentBoundFactory();
        if (factory != null) {
            refreshBoundary(factory);
        }
    }

    private void refreshAdjacentFluidPipes() {
        if (level == null || level.isClientSide()) {
            return;
        }
        for (Direction side : getExternalSides()) {
            BlockPos adjacentPos = worldPosition.relative(side);
            if (FluidPropagator.getPipe(level, adjacentPos) != null) {
                FluidPropagator.propagateChangedPipe(level, adjacentPos, level.getBlockState(adjacentPos));
            }
        }
    }

    public void prepareForSimulatedMove() {
        onRemovedFromWorld();
    }

    public void finishSimulatedMove() {
        if (level == null || level.isClientSide()) {
            return;
        }
        level.invalidateCapabilities(worldPosition);
        rememberState();
        sendData();
        NestedFactoryBlockEntity factory = getHostFactory();
        if (factory != null) {
            refreshBoundary(factory);
        }
    }

    private void refreshBoundary(NestedFactoryBlockEntity factory) {
        if (level != null && !level.isClientSide()) {
            level.invalidateCapabilities(worldPosition);
            factory.onExternalNeighborChanged(worldPosition);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  NestedExtensionInterfaceBlockEntity extension) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        boolean actualPowered = level.hasNeighborSignal(pos);
        if (actualPowered != extension.isPowered()) {
            level.setBlock(pos, state.setValue(NestedExtensionInterfaceBlock.POWERED, actualPowered),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        FactoryLogicalPortEndpoint endpoint = extension.resolveBoundPort();
        boolean bound = endpoint != null;
        boolean powered = extension.isPowered();
        PortMode mode = bound ? endpoint.mode() : PortMode.NONE;
        if (bound != extension.lastBound || powered != extension.lastPowered || mode != extension.lastMode) {
            level.invalidateCapabilities(pos);
            extension.sendData();
            if (endpoint != null) {
                extension.refreshBoundary(endpoint.factory());
            }
            extension.rememberState(endpoint);
        }
    }

    private void rememberState() {
        rememberState(resolveBoundPort());
    }

    private void rememberState(FactoryLogicalPortEndpoint endpoint) {
        lastBound = endpoint != null;
        lastPowered = isPowered();
        lastMode = endpoint == null ? PortMode.NONE : endpoint.mode();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(GoggleTooltips.title(getBlockState().getBlock().getName()));
        NestedFactoryBlockEntity factory = getHostFactory();
        if (factory == null) {
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.extension.factory",
                    Component.translatable("goggles.create_nested_factory.extension.unbound")
                            .withStyle(ChatFormatting.GRAY)));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.extension.mode",
                    Component.literal("—").withStyle(ChatFormatting.GRAY)));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.extension.status",
                    Component.translatable("goggles.create_nested_factory.extension.unbound")
                            .withStyle(ChatFormatting.YELLOW)));
            return true;
        }

        PortMode mode = factory.getFaceMode(getHostFace());
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.extension.factory",
                factory.getDisplayName().copy().withStyle(ChatFormatting.AQUA)));
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.extension.mode",
                Component.translatable(modeKey(mode)).withStyle(modeColor(mode))));
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.extension.status",
                Component.translatable(isPowered()
                                ? "goggles.create_nested_factory.extension.disabled"
                                : "goggles.create_nested_factory.extension.enabled")
                        .withStyle(isPowered() ? ChatFormatting.RED : ChatFormatting.GREEN)));
        return true;
    }

    private static String modeKey(PortMode mode) {
        return "goggles.create_nested_factory.port_mode." + mode.getSerializedName();
    }

    private static ChatFormatting modeColor(PortMode mode) {
        return switch (mode) {
            case INPUT -> ChatFormatting.GREEN;
            case OUTPUT -> ChatFormatting.AQUA;
            case NONE -> ChatFormatting.GRAY;
        };
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!boundFactoryId.isBlank()) {
            tag.putString("BoundFactoryId", boundFactoryId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        boundFactoryId = tag.getString("BoundFactoryId");
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            level.invalidateCapabilities(worldPosition);
            rememberState();
            NestedFactoryBlockEntity factory = getHostFactory();
            if (factory != null) {
                refreshBoundary(factory);
            }
        }
    }

    private final class ItemProxy implements IItemHandler {
        private final Direction queriedSide;

        private ItemProxy(Direction queriedSide) {
            this.queriedSide = queriedSide;
        }

        @Override
        public int getSlots() {
            IItemHandler delegate = currentItemDelegate(queriedSide);
            return delegate == null ? 0 : delegate.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            IItemHandler delegate = currentItemDelegate(queriedSide);
            return delegate == null ? ItemStack.EMPTY : delegate.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            IItemHandler delegate = currentItemDelegate(queriedSide);
            return delegate == null ? stack : delegate.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            IItemHandler delegate = currentItemDelegate(queriedSide);
            return delegate == null ? ItemStack.EMPTY : delegate.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            IItemHandler delegate = currentItemDelegate(queriedSide);
            return delegate == null ? 0 : delegate.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            IItemHandler delegate = currentItemDelegate(queriedSide);
            return delegate != null && delegate.isItemValid(slot, stack);
        }
    }

    private final class FluidProxy implements IFluidHandler {
        private final Direction queriedSide;

        private FluidProxy(Direction queriedSide) {
            this.queriedSide = queriedSide;
        }

        @Override
        public int getTanks() {
            IFluidHandler delegate = currentFluidDelegate(queriedSide);
            return delegate == null ? 0 : delegate.getTanks();
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            IFluidHandler delegate = currentFluidDelegate(queriedSide);
            return delegate == null ? FluidStack.EMPTY : delegate.getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(int tank) {
            IFluidHandler delegate = currentFluidDelegate(queriedSide);
            return delegate == null ? 0 : delegate.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            IFluidHandler delegate = currentFluidDelegate(queriedSide);
            return delegate != null && delegate.isFluidValid(tank, stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            IFluidHandler delegate = currentFluidDelegate(queriedSide);
            return delegate == null ? 0 : delegate.fill(resource, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            IFluidHandler delegate = currentFluidDelegate(queriedSide);
            return delegate == null ? FluidStack.EMPTY : delegate.drain(resource, action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            IFluidHandler delegate = currentFluidDelegate(queriedSide);
            return delegate == null ? FluidStack.EMPTY : delegate.drain(maxDrain, action);
        }
    }
}

package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.NestedFactorySaveData;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.RoomMutationTaskManager;
import com.createnestedfactory.create_nested_factory.PocketChunkForceManager;
import com.createnestedfactory.create_nested_factory.Config;
import com.createnestedfactory.create_nested_factory.blueprint.FactoryRestoreSnapshot;
import com.createnestedfactory.create_nested_factory.blueprint.NestedFactoryBlueprint;
import com.createnestedfactory.create_nested_factory.block.FactoryFacePortBindings;
import com.createnestedfactory.create_nested_factory.block.FactoryPowerProfile;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.energy.FactoryEnergyStorage;
import com.createnestedfactory.create_nested_factory.menu.FactoryMenu;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;

public class NestedFactoryBlockEntity extends GeneratingKineticBlockEntity implements MenuProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int MAX_FE_PER_TICK = 10000;
    public static final int ENERGY_CAPACITY = 1_000_000;
    private static final int LEARNING_TICKS = 200;
    private static final int LEARNING_WARMUP_TICKS = 100;
    private static final int DRAIN_STABLE_TICKS = 60;
    private static final int DRAIN_TIMEOUT_TICKS = 1200;
    private static final float STRESS_EPSILON = 0.001f;

    private record ExternalStressCandidate(Direction face, KineticBlockEntity anchor,
                                           KineticNetwork network, float speed, float availableSU) {}

    private final PortMode[] faceModes = new PortMode[6];
    private final int[] portIds = new int[6];

    private final PocketBounds bounds = new PocketBounds();

    private OperationMode operationMode = OperationMode.CHUNK_LOADED;
    private final FactoryPowerProfile powerProfile = new FactoryPowerProfile();
    private final BlackboxData blackbox = new BlackboxData();
    private boolean blueprintApplied = false;
    private NestedFactoryBlueprint appliedBlueprint = null;
    private FactoryRestoreSnapshot preBlueprintSnapshot = null;
    private int energyStored = 0;
    private final FactoryEnergyStorage energyStorage = new FactoryEnergyStorage(this);

    private String customName = null;
    private final IItemHandler[] faceItemHandlers = new IItemHandler[6];
    private final IFluidHandler[] faceFluidHandlers = new IFluidHandler[6];

    /**
     * Create's Packager placement probes adjacent inventories with a null side before choosing its facing.
     * This probe advertises that the block participates in item logistics without exposing an unsided route.
     */
    private static final IItemHandler UNSIDED_ITEM_HANDLER_PROBE = new IItemHandler() {
        @Override
        public int getSlots() {
            return 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    };
    private final FactoryProductionBatch productionBatch = new FactoryProductionBatch();
    /** Real-time, no-fixed-capacity resource channels shared by all room ports with the same id. */
    private final FactoryPortChannels portChannels = new FactoryPortChannels();
    /** Runtime-only external consumers that queried an OUTPUT face. */
    private final Block[] externalItemOutputConsumers = new Block[6];
    private final Block[] externalFluidOutputConsumers = new Block[6];
    /** Runtime-only positions of power, energy and inventory participants in this Pocket room. */
    private final RoomParticipantIndex runtimeIndex = new RoomParticipantIndex();
    private int itemCycleCounter = 0;
    private int playersInside = 0;
    private final Map<String, Integer> chunkRefCounts = new HashMap<>();
    private boolean pocketChunksForced = false;
    private long pocketChunksReleaseAt = -1;

    /** Runtime-only virtual membership in the selected external Create network. */
    private KineticNetwork reservedExternalNetwork = null;
    private Long reservedExternalNetworkId = null;
    private Direction reservedExternalFace = null;
    private float reservedExternalSU = 0f;
    private float reservedStressImpact = 0f;
    /** Runtime-only source selected for room-side RPM mirroring; it does not reserve SU. */
    private KineticNetwork selectedExternalNetwork = null;
    private Direction selectedExternalFace = null;
    private float selectedExternalSpeed = 0f;
    private boolean externalStressSatisfied = false;
    /** Sum of the current tick's de-duplicated per-port live requests, captured for black-box learning. */
    private float liveExternalStressDemandSU = 0f;

    private String factoryId = UUID.randomUUID().toString();
    /** Persistent, world-level root-room allocation. Nested factories use nestedRoomOrigin instead. */
    private int rootSlotId = -1;
    private BlockPos rootRoomOrigin = BlockPos.ZERO;
    private boolean rootRoomAllocated = false;
    /** Set by NBT reads so only pre-slot saves are offered the legacy coordinate during migration. */
    private boolean loadedFromDisk = false;
    private boolean nested = false;
    private boolean enterable = true;
    private boolean invalidNested = false;
    private int nestingDepth = 0;
    private String parentFactoryId = "";
    private String rootFactoryId = factoryId;
    private BlockPos parentFactoryPos = null;
    private ResourceKey<Level> parentDimension = null;
    private int nestedSlotId = -1;
    private int nestedSlotX = 0;
    private int nestedSlotZ = 0;
    private BlockPos nestedRoomOrigin = BlockPos.ZERO;
    private String childFactoryId = "";
    private BlockPos childFactoryPos = null;
    private boolean factoryStateInitialized = false;
    private int boundsVersion = 0;

    private final Map<ItemVariant, Long> initialInventory = new HashMap<>();
    private final Map<ItemVariant, Long> staticInventory = new HashMap<>();
    private int drainLastCount = -1;
    private int drainStaticCount = 0;
    private int drainStableTicks = 0;
    private int drainingTicks = 0;
    private int learningTicksRemaining = 0;

    public NestedFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NESTED_FACTORY.get(), pos, state);
        for (int i = 0; i < 6; i++) {
            faceModes[i] = PortMode.NONE;
            faceItemHandlers[i] = new FactoryFaceItemHandler(i);
            faceFluidHandlers[i] = new FactoryFaceFluidHandler(i);
        }
    }

    @Override
    public Component getDisplayName() {
        return customName == null || customName.isBlank()
                ? Component.translatable("container.create_nested_factory.factory")
                : Component.literal(customName);
    }

    public String getCustomName() {
        return customName;
    }

    public void setCustomName(String name) {
        String trimmed = name == null ? "" : name.trim();
        this.customName = trimmed.isEmpty() ? null : trimmed;
        setChanged();
        if (level != null && !level.isClientSide()) {
            sendData();
        }
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new FactoryMenu(containerId, playerInventory, this);
    }

    public PortMode getFaceMode(Direction face) {
        return faceModes[face.get3DDataValue()];
    }

    public int getPortId(Direction face) {
        return portIds[face.get3DDataValue()];
    }

    /** Returns every configured external face belonging to a logical port group. */
    public List<Direction> getFacesForPortId(int portId) {
        if (portId < FactoryFacePortBindings.MIN_PORT_ID || portId > FactoryFacePortBindings.MAX_PORT_ID) {
            return List.of();
        }
        List<Direction> faces = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            if (faceModes[i] != PortMode.NONE && portIds[i] == portId) {
                faces.add(Direction.from3DDataValue(i));
            }
        }
        return List.copyOf(faces);
    }

    /** Compatibility helper for callers that only need one representative face. */
    public Direction getFaceForPortId(int portId) {
        List<Direction> faces = getFacesForPortId(portId);
        return faces.isEmpty() ? null : faces.get(0);
    }

    public PocketBounds getBounds() {
        return bounds;
    }

    public OperationMode getOperationMode() {
        return operationMode;
    }

    public FactoryPowerProfile getPowerProfile() {
        return effectivePowerProfile();
    }

    /**
     * Blueprint execution is defined by the source factory's captured profile, not by
     * mutable profile data on the target block entity.
     */
    private FactoryPowerProfile effectivePowerProfile() {
        if (operationMode == OperationMode.BLUEPRINT && appliedBlueprint != null) {
            return appliedBlueprint.powerProfile();
        }
        return powerProfile;
    }

    public BlackboxData getBlackbox() {
        return blackbox;
    }

    public boolean isBlueprintApplied() {
        return blueprintApplied;
    }

    public NestedFactoryBlueprint getAppliedBlueprint() {
        return appliedBlueprint;
    }

    public String getBlueprintSourceName() {
        return appliedBlueprint == null ? "" : appliedBlueprint.sourceFactoryName();
    }

    public String getBlueprintSourceDimension() {
        return appliedBlueprint == null ? "" : appliedBlueprint.sourceDimension();
    }

    public BlockPos getBlueprintSourcePos() {
        return appliedBlueprint == null ? BlockPos.ZERO : appliedBlueprint.sourcePos();
    }

    public int getBlueprintSourceDepth() {
        return appliedBlueprint == null ? 0 : appliedBlueprint.sourceDepth();
    }

    public float getBlueprintEfficiency() {
        return appliedBlueprint == null ? 1.0f : appliedBlueprint.productionEfficiency();
    }

    public String getFactoryId() {
        return factoryId;
    }

    public boolean isNested() {
        return nested;
    }

    public boolean isRoot() {
        return !nested;
    }

    public boolean isEnterable() {
        return enterable && (!nested || !invalidNested);
    }

    public boolean isInvalidNested() {
        return invalidNested;
    }

    public int getNestingDepth() {
        return nestingDepth;
    }

    public String getParentFactoryId() {
        return parentFactoryId;
    }

    public String getRootFactoryId() {
        return rootFactoryId;
    }

    public int getBoundsVersion() {
        return boundsVersion;
    }

    public BlockPos roomOrigin() {
        return nested ? nestedRoomOrigin : rootRoomOrigin;
    }

    public BlockPos getNestedRoomOrigin() {
        return nestedRoomOrigin;
    }

    public boolean hasEnterableChild() {
        NestedFactoryBlockEntity child = getChildFactoryEntity();
        return child != null && child.isEnterable();
    }

    public boolean hasRecordedChild() {
        return !childFactoryId.isEmpty();
    }

    public NestedFactoryBlockEntity getChildFactoryEntity() {
        if (childFactoryPos == null || childFactoryId.isEmpty() || level == null || level.isClientSide()) {
            return null;
        }
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return null;
        }
        if (pocket.getBlockEntity(childFactoryPos) instanceof NestedFactoryBlockEntity child
                && child.getFactoryId().equals(childFactoryId)) {
            return child;
        }
        return null;
    }

    public void setChildFactory(NestedFactoryBlockEntity child) {
        this.childFactoryId = child == null ? "" : child.getFactoryId();
        this.childFactoryPos = child == null ? null : child.getBlockPos().immutable();
        boundsVersion++;
        markRuntimeIndexDirty();
        setChanged();
    }

    public int getEnergyStored() {
        return energyStored;
    }

    public void setEnergyStored(int value) {
        this.energyStored = Math.max(0, Math.min(ENERGY_CAPACITY, value));
    }

    public int getEnergyCapacity() {
        return ENERGY_CAPACITY;
    }

    public boolean canExtractEnergy() {
        return effectivePowerProfile().netFE() > 0 && energyStored > 0;
    }

    public boolean canReceiveEnergy() {
        return effectivePowerProfile().netFE() < 0;
    }

    public int getMaxEnergyExtract() {
        return (int) Math.min(MAX_FE_PER_TICK, Math.max(0f, effectivePowerProfile().netFE()));
    }

    public IEnergyStorage getEnergyStorage(Direction side) {
        return energyStorage;
    }

    public void toggleBlackbox(Player player) {
        if (isRoomMutationLocked()) {
            if (player instanceof ServerPlayer serverPlayer) {
                PlayerMessagePayload.sendTo(serverPlayer, Component.translatable("message.create_nested_factory.room_mutation.active").withStyle(ChatFormatting.YELLOW), false);
            }
            return;
        }
        if (blueprintApplied) {
            cancelBlueprint(player, OperationMode.CHUNK_LOADED);
            return;
        }
        if (invalidNested) {
            if (player instanceof ServerPlayer sp) {
                PlayerMessagePayload.sendTo(sp, Component.translatable("message.create_nested_factory.factory.invalid_nested_mode_change").withStyle(ChatFormatting.RED), false);
            }
            return;
        }
        if (hasPlayersInside()) {
            if (player instanceof ServerPlayer sp) {
                PlayerMessagePayload.sendTo(sp, Component.translatable("message.create_nested_factory.factory.players_prevent_mode_change").withStyle(ChatFormatting.RED), false);
            }
            return;
        }
        if (operationMode == OperationMode.CHUNK_LOADED) {
            startBlackbox();
        } else {
            stopBlackbox(player);
        }
    }

    public void switchFromBlueprint(Player player, OperationMode targetMode) {
        if (!blueprintApplied) {
            return;
        }
        if (targetMode != OperationMode.CHUNK_LOADED && targetMode != OperationMode.BLACKBOX_ACTIVE) {
            return;
        }
        cancelBlueprint(player, targetMode);
    }

    public Component applyBlueprint(NestedFactoryBlueprint blueprint, ServerPlayer player) {
        if (isRoomMutationLocked()) {
            return Component.translatable("message.create_nested_factory.blueprint.apply.room_mutating");
        }
        if (!isRoot() && !isEnterable() && !invalidNested) {
            return Component.translatable("message.create_nested_factory.blueprint.apply.invalid_target");
        }
        if (blueprint == null || !blueprint.hasCompleteRunData()) {
            return Component.translatable("message.create_nested_factory.blueprint.apply.invalid_data");
        }
        if (factoryId.equals(blueprint.sourceFactoryId())) {
            return Component.translatable("message.create_nested_factory.blueprint.apply.source_target");
        }
        if (blueprintApplied) {
            return Component.translatable("message.create_nested_factory.blueprint.apply.already_applied");
        }
        if (isEnterable() && hasPlayersInside()) {
            return Component.translatable("message.create_nested_factory.blueprint.apply.players_inside");
        }
        invalidateProductionBatch(player, "apply_blueprint");
        portChannels.clear();
        preBlueprintSnapshot = captureRestoreSnapshot();
        operationMode = OperationMode.BLUEPRINT;
        blueprintApplied = true;
        invalidateResourceCapabilities();
        appliedBlueprint = blueprint.copy(level.registryAccess());
        blackbox.read(blueprint.blackbox().write(new CompoundTag(), level.registryAccess()), level.registryAccess());
        for (int i = 0; i < 6; i++) {
            faceModes[i] = blueprint.faceMode(i);
            portIds[i] = blueprint.portId(i);
        }
        normalizeFacePortBindings();

        if (!invalidNested) {
            removeChunkRef("load");
            addChunkRef("blueprint");
        }
        setChanged();
        sendSync();
        return null;
    }

    private void invalidateResourceCapabilities() {
        if (level == null || level.isClientSide()) {
            return;
        }
        level.invalidateCapabilities(worldPosition);
        refreshExternalFluidNetworks();
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        for (int portId = 1; portId <= 6; portId++) {
            for (BlockPos portPos : PocketRegistry.getPorts(roomOrigin(), portId)) {
                pocket.invalidateCapabilities(portPos);
            }
        }
    }

    /**
     * Rebuilds Create's external fluid endpoint search after the capability surface changes.
     * NeoForge capability invalidation alone does not make an existing Create pipe network
     * discover a newly connected or disconnected factory endpoint.
     */
    void refreshExternalFluidNetworks() {
        if (level == null || level.isClientSide()) {
            return;
        }
        refreshExternalFluidNetworks(Set.of(Direction.values()));
    }

    void refreshExternalFluidNetworks(int portId) {
        if (level == null || level.isClientSide()) {
            return;
        }
        refreshExternalFluidNetworks(new HashSet<>(getFacesForPortId(portId)));
    }

    /**
     * Rebuilds Create's own cached state for every pipe in the affected chain. Calling only the
     * factory-adjacent root is insufficient when a remote branch is added to an existing network.
     */
    private void refreshExternalFluidNetworks(Set<Direction> faces) {
        if (faces.isEmpty()) {
            return;
        }
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> queued = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        for (Direction face : faces) {
            BlockPos adjacentPos = worldPosition.relative(face);
            BlockState state = level.getBlockState(adjacentPos);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, adjacentPos);
            if (pipe != null && pipe.canHaveFlowToward(state, face.getOpposite())
                    && queued.add(adjacentPos)) {
                pending.addLast(adjacentPos);
            }
        }
        while (!pending.isEmpty()) {
            BlockPos pipePos = pending.removeFirst();
            if (!level.isLoaded(pipePos) || !visited.add(pipePos)) {
                continue;
            }
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
            if (pipe == null) {
                continue;
            }
            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pipePos), pipe)) {
                BlockPos connectedPos = pipePos.relative(face);
                if (level.isLoaded(connectedPos)
                        && FluidPropagator.getPipe(level, connectedPos) != null
                        && queued.add(connectedPos)) {
                    pending.addLast(connectedPos);
                }
            }
        }
        for (BlockPos pipePos : visited) {
            if (level.isLoaded(pipePos)) {
                FluidPropagator.propagateChangedPipe(level, pipePos, level.getBlockState(pipePos));
            }
        }
    }

    /** Polls external pipe topology and rebuilds Create state after a remote branch changes. */
    private void refreshExternalFluidNetworksIfSignatureChanged(int portId) {
        if (level == null || level.isClientSide() || getFacesForPortId(portId).isEmpty()) {
            return;
        }
        Set<FluidTopologyPoint> signature = externalFluidNetworkSignature(portId);
        Set<FluidTopologyPoint> previous = externalFluidNetworkSignatures.put(portId, signature);
        if (previous != null && previous.equals(signature)) {
            return;
        }
        refreshExternalFluidNetworks(portId);
        refreshRoomFluidNetworks(portId);
    }

    private Set<FluidTopologyPoint> externalFluidNetworkSignature(int portId) {
        Set<FluidTopologyPoint> signature = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> queued = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        for (Direction face : getFacesForPortId(portId)) {
            BlockPos adjacentPos = worldPosition.relative(face);
            BlockState state = level.getBlockState(adjacentPos);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, adjacentPos);
            if (pipe != null && pipe.canHaveFlowToward(state, face.getOpposite())
                    && queued.add(adjacentPos)) {
                pending.addLast(adjacentPos);
            }
        }
        while (!pending.isEmpty()) {
            BlockPos pipePos = pending.removeFirst();
            if (!level.isLoaded(pipePos) || !visited.add(pipePos)) {
                continue;
            }
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
            if (pipe == null) {
                continue;
            }
            signature.add(new FluidTopologyPoint(pipePos, -1));
            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pipePos), pipe)) {
                signature.add(new FluidTopologyPoint(pipePos, face.get3DDataValue()));
                BlockPos connectedPos = pipePos.relative(face);
                if (!level.isLoaded(connectedPos)) {
                    continue;
                }
                FluidTransportBehaviour connectedPipe = FluidPropagator.getPipe(level, connectedPos);
                if (connectedPipe != null) {
                    if (queued.add(connectedPos)) {
                        pending.addLast(connectedPos);
                    }
                    continue;
                }
                if (FluidPropagator.isOpenEnd(level, pipePos, face)
                        || level.getCapability(Capabilities.FluidHandler.BLOCK,
                        connectedPos, face.getOpposite()) != null) {
                    signature.add(new FluidTopologyPoint(connectedPos, face.getOpposite().get3DDataValue()));
                }
            }
        }
        return signature;
    }

    private void invalidateProductionBatch(Player player, String reason) {
        if (productionBatch.isEmpty() || level == null || level.isClientSide()) {
            return;
        }
        for (ItemStack stack : productionBatch.materializeItems()) {
            if (!stack.isEmpty()) {
                ItemEntity drop = new ItemEntity(level,
                        worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5,
                        stack.copy());
                level.addFreshEntity(drop);
            }
        }
        long destroyedFluid = productionBatch.destroyedFluidAmount();
        productionBatch.clear();
        itemCycleCounter = 0;
        setChanged();
        if (destroyedFluid > 0) {
            if (player instanceof ServerPlayer serverPlayer) {
                PlayerMessagePayload.sendTo(serverPlayer, Component.translatable(
                        "message.create_nested_factory.production_batch.destroyed_fluid", destroyedFluid)
                        .withStyle(ChatFormatting.RED), false);
            } else {
                LOGGER.warn("Destroyed {} mB of factory batch fluid at {} because {}",
                        destroyedFluid, worldPosition, reason);
            }
        }
    }

    /** Captures only the configuration that applyBlueprint() will overwrite. */
    private FactoryRestoreSnapshot captureRestoreSnapshot() {
        FactoryRestoreSnapshot snapshot = new FactoryRestoreSnapshot();
        snapshot.blackbox(blackbox.write(new CompoundTag(), level.registryAccess()));
        snapshot.powerProfile(powerProfile.write());
        for (int i = 0; i < 6; i++) {
            snapshot.faceMode(i, faceModes[i]);
            snapshot.portId(i, portIds[i]);
        }
        return snapshot;
    }

    private void cancelBlueprint(Player player, OperationMode targetMode) {
        invalidateProductionBatch(player, "cancel_blueprint");
        if (preBlueprintSnapshot == null) {
            blueprintApplied = false;
            appliedBlueprint = null;
            operationMode = invalidNested ? OperationMode.CHUNK_LOADED : targetMode;
            invalidateResourceCapabilities();
            setChanged();
            sendSync();
            return;
        }

        FactoryRestoreSnapshot snapshot = preBlueprintSnapshot;
        // Blueprint cancellation restores only the overwritten configuration. Energy, items and
        // fluids are committed runtime resources and must retain their current values.
        blackbox.read(snapshot.blackbox(), level.registryAccess());
        powerProfile.read(snapshot.powerProfile());
        for (int i = 0; i < 6; i++) {
            faceModes[i] = snapshot.faceMode(i);
            portIds[i] = snapshot.portId(i);
        }
        normalizeFacePortBindings();
        itemCycleCounter = 0;

        if (invalidNested) {
            operationMode = OperationMode.CHUNK_LOADED;
        } else if (targetMode == OperationMode.BLACKBOX_ACTIVE) {
            operationMode = OperationMode.BLACKBOX_ACTIVE;
            addChunkRef("load");
        } else {
            operationMode = OperationMode.CHUNK_LOADED;
            addChunkRef("load");
        }

        removeChunkRef("blueprint");
        blueprintApplied = false;
        appliedBlueprint = null;
        preBlueprintSnapshot = null;
        if (operationMode == OperationMode.CHUNK_LOADED) {
            rebuildRuntimeIndex(pocketLevel(), true);
        }
        invalidateResourceCapabilities();
        setChanged();
        sendSync();

        if (invalidNested && player instanceof ServerPlayer sp) {
            PlayerMessagePayload.sendTo(sp, Component.translatable("message.create_nested_factory.blueprint.cancel.invalid_nested").withStyle(ChatFormatting.YELLOW), false);
        }
    }

    private void startBlackbox() {
        rebuildRuntimeIndex(pocketLevel(), true);
        blackbox.setRecording(false);
        initialInventory.clear();
        staticInventory.clear();
        initialInventory.putAll(countItemsInFactorySpace());
        drainLastCount = totalCount(initialInventory);
        drainStaticCount = 0;
        drainStableTicks = 0;
        drainingTicks = 0;
        operationMode = OperationMode.BLACKBOX_DRAINING;
        invalidateResourceCapabilities();
        setChanged();
        sendSync();
    }

    private void stopBlackbox(Player player) {
        invalidateProductionBatch(player, "stop_blackbox");
        boolean wasActive = operationMode == OperationMode.BLACKBOX_ACTIVE;
        blackbox.setRecording(false);
        blackbox.beginSampling();
        initialInventory.clear();
        staticInventory.clear();
        drainingTicks = 0;
        drainStaticCount = 0;
        drainStableTicks = 0;
        learningTicksRemaining = 0;
        operationMode = OperationMode.CHUNK_LOADED;
        rebuildRuntimeIndex(pocketLevel(), true);
        invalidateResourceCapabilities();
        if (wasActive) {
            addChunkRef("load");
        }
        setChanged();
        sendSync();
    }

    public boolean hasPlayersInside() {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return false;
        }
        BlockPos origin = roomOrigin();
        AABB area = new AABB(
                bounds.minX(origin), bounds.minY(origin), bounds.minZ(origin),
                bounds.maxX(origin) + 1.0, bounds.maxY(origin) + 1.0, bounds.maxZ(origin) + 1.0);
        return !pocket.getEntitiesOfClass(ServerPlayer.class, area, p -> true).isEmpty();
    }

    private void sendSync() {
        if (level != null && !level.isClientSide()) {
            sendData();
        }
    }

    public void onPlayerEntered() {
        playersInside++;
        if (playersInside == 1) {
            setExternalAreaForced(true);
            addChunkRef("player");
        }
    }

    public void onPlayerExited() {
        if (playersInside > 0) {
            playersInside--;
            if (playersInside == 0) {
                setExternalAreaForced(false);
                removeChunkRef("player");
            }
        }
        setChanged();
    }

    public int getInputMbPerSec() {
        float perSec = 0f;
        for (float v : blackbox.getInputFluidRates().values()) {
            perSec += v;
        }
        return (int) perSec;
    }

    public int getOutputMbPerSec() {
        float perSec = 0f;
        for (float v : blackbox.getOutputFluidRates().values()) {
            perSec += v;
        }
        return (int) perSec;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(GoggleTooltips.title(getDisplayName()));

        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.mode",
                Component.translatable("gui.create_nested_factory.mode." + operationMode.getSerializedName())));

        if (nested) {
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.depth", String.valueOf(nestingDepth), ChatFormatting.LIGHT_PURPLE));
        }

        switch (operationMode) {
            case BLACKBOX_DRAINING -> tooltip.add(GoggleTooltips.stat(
                    "goggles.create_nested_factory.draining", String.valueOf(Math.max(0, drainLastCount - drainStaticCount)), ChatFormatting.YELLOW));
            case BLACKBOX_LEARNING -> tooltip.add(GoggleTooltips.stat(
                    "goggles.create_nested_factory.learning", ((learningTicksRemaining + 19) / 20) + "s", ChatFormatting.YELLOW));
            default -> { }
        }

        FactoryPowerProfile displayedPowerProfile = effectivePowerProfile();

        if (displayedPowerProfile.generatedSU() != 0f || displayedPowerProfile.consumedSU() != 0f) {
            tooltip.add(GoggleTooltips.section("goggles.create_nested_factory.stress"));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.generated", fmt(displayedPowerProfile.generatedSU()) + " su", ChatFormatting.AQUA));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.consumed", fmt(displayedPowerProfile.consumedSU()) + " su", ChatFormatting.AQUA));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.net", fmt(displayedPowerProfile.netSU()) + " su", ChatFormatting.AQUA));
        }

        if (displayedPowerProfile.generatedFE() != 0f || displayedPowerProfile.consumedFE() != 0f) {
            tooltip.add(GoggleTooltips.section("goggles.create_nested_factory.energy"));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.generated", fmt(displayedPowerProfile.generatedFE()) + " FE/t", ChatFormatting.GOLD));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.consumed", fmt(displayedPowerProfile.consumedFE()) + " FE/t", ChatFormatting.GOLD));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.net", fmt(displayedPowerProfile.netFE()) + " FE/t", ChatFormatting.GOLD));
        }

        addItemRates(tooltip, "goggles.create_nested_factory.input_items", blackbox.getInputRates());
        addItemRates(tooltip, "goggles.create_nested_factory.output_items", blackbox.getOutputRates());

        if (blueprintApplied && appliedBlueprint != null) {
            tooltip.add(GoggleTooltips.section("goggles.create_nested_factory.blueprint"));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.source_name",
                    Component.literal(appliedBlueprint.sourceFactoryName()).withStyle(ChatFormatting.AQUA)));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.efficiency",
                    String.format(Locale.ROOT, "%.0f%%", appliedBlueprint.productionEfficiency() * 100.0f), ChatFormatting.GOLD));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.source_dimension",
                    appliedBlueprint.sourceDimension(), ChatFormatting.GRAY));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.source_pos",
                    appliedBlueprint.sourcePos().getX() + " " + appliedBlueprint.sourcePos().getY() + " " + appliedBlueprint.sourcePos().getZ(),
                    ChatFormatting.GRAY));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.source_depth",
                    String.valueOf(appliedBlueprint.sourceDepth()), ChatFormatting.LIGHT_PURPLE));
        }

        addFluidRates(tooltip, "goggles.create_nested_factory.input_fluids", blackbox.getInputFluidRates());
        addFluidRates(tooltip, "goggles.create_nested_factory.output_fluids", blackbox.getOutputFluidRates());

        if (nested && !isEnterable()) {
            tooltip.add(Component.literal("    ")
                    .append(Component.translatable("goggles.create_nested_factory.child_factory_exists").withStyle(ChatFormatting.RED)));
        }
        return true;
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static void addItemRates(List<Component> tooltip, String key, Map<ItemVariant, Float> rates) {
        if (rates.isEmpty()) {
            return;
        }
        tooltip.add(GoggleTooltips.section(key));
        rates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> tooltip.add(Component.literal("     ")
                        .append(e.getKey().prototype().getHoverName().copy().withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("  " + String.format(Locale.ROOT, "%.0f/s", e.getValue()))
                                .withStyle(ChatFormatting.AQUA))));
    }

    private static void addFluidRates(List<Component> tooltip, String key, Map<Fluid, Float> rates) {
        if (rates.isEmpty()) {
            return;
        }
        tooltip.add(GoggleTooltips.section(key));
        rates.entrySet().stream()
                .sorted(Comparator.comparing(e -> BuiltInRegistries.FLUID.getKey(e.getKey()).toString()))
                .forEach(e -> tooltip.add(Component.literal("     ")
                        .append(new FluidStack(e.getKey(), 1).getHoverName().copy().withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("  " + String.format(Locale.ROOT, "%.0f mB/s", e.getValue()))
                                .withStyle(ChatFormatting.AQUA))));
    }

    private void addChunkRef(String reason) {
        int count = chunkRefCounts.merge(reason, 1, Integer::sum);
        if (count == 1 && !pocketChunksForced) {
            pocketChunksForced = true;
            applyPocketChunkForce(true);
        }
    }

    private void refreshChunkRefsForMode() {
        removeChunkRef("load");
        removeChunkRef("blueprint");
        if (operationMode == OperationMode.BLUEPRINT) {
            addChunkRef("blueprint");
        } else if (operationMode != OperationMode.BLACKBOX_ACTIVE) {
            addChunkRef("load");
        }
    }

    private void removeChunkRef(String reason) {
        int count = chunkRefCounts.getOrDefault(reason, 0);
        if (count <= 1) {
            chunkRefCounts.remove(reason);
        } else {
            chunkRefCounts.put(reason, count - 1);
        }
        if (chunkRefCounts.isEmpty() && pocketChunksForced) {
            pocketChunksReleaseAt = level.getGameTime() + 100;
        }
    }

    private void tickChunkRefs() {
        if (chunkRefCounts.isEmpty() && pocketChunksForced
                && level.getGameTime() >= pocketChunksReleaseAt) {
            pocketChunksForced = false;
            applyPocketChunkForce(false);
        }
    }

    private String roomChunkForceOwner() {
        return factoryId + ":room";
    }

    private String externalChunkForceOwner() {
        return factoryId + ":external";
    }

    private Set<ChunkPos> roomChunks() {
        BlockPos origin = roomOrigin();
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        Set<ChunkPos> chunks = new HashSet<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }

    private Set<ChunkPos> externalAreaChunks() {
        int cx = worldPosition.getX() >> 4;
        int cz = worldPosition.getZ() >> 4;
        Set<ChunkPos> chunks = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunks.add(new ChunkPos(cx + dx, cz + dz));
            }
        }
        return chunks;
    }

    /**
     * Acquires/releases this factory's own room ticket. The ticket is owner-aware because
     * parent rooms and nested rooms can share an X/Z chunk even though they use different Y.
     */
    private void applyPocketChunkForce(boolean forced) {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        if (forced) {
            PocketChunkForceManager.replace(pocket, roomChunkForceOwner(), roomChunks());
        } else {
            PocketChunkForceManager.releaseAll(pocket.getServer(), roomChunkForceOwner());
        }
    }

    /**
     * Keeps the factory block's surrounding area loaded while a player is inside. Nested
     * factories execute this in the pocket dimension, so this must share ownership with the
     * parent factory's room ticket instead of directly toggling ServerLevel#setChunkForced.
     */
    private void setExternalAreaForced(boolean forced) {
        if (level == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (forced) {
            PocketChunkForceManager.replace(serverLevel, externalChunkForceOwner(), externalAreaChunks());
        } else {
            PocketChunkForceManager.releaseAll(serverLevel.getServer(), externalChunkForceOwner());
        }
    }

    /**
     * The face tanks are only short-lived cross-dimension transit buffers. INPUT fluid is
     * discarded shortly after exterior filling stops; OUTPUT fluid is discarded after the
     * room/exterior link has been idle for a second. This prevents either side from using
     * the factory as a reservoir while preserving asynchronous Create capability transfer.
     */
    private void tickChunkLoaded() {
        blackbox.tickRates();
    }

    private void tickDraining() {
        drainingTicks++;
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        Map<ItemVariant, Long> currentInventory = countItemsInFactorySpace();
        int total = totalCount(currentInventory);
        int staticCount = 0;
        staticInventory.clear();
        for (Map.Entry<ItemVariant, Long> e : currentInventory.entrySet()) {
            long initial = initialInventory.getOrDefault(e.getKey(), 0L);
            if (initial == e.getValue()) {
                staticCount += Math.toIntExact(e.getValue());
                if (e.getValue() > 0) {
                    staticInventory.put(e.getKey(), e.getValue());
                }
            }
        }
        drainStaticCount = staticCount;
        boolean channelsEmpty = portChannels.isEmpty();
        if (channelsEmpty && total == drainLastCount) {
            drainStableTicks += 20;
        } else {
            drainStableTicks = 0;
        }
        drainLastCount = total;
        // A port channel is real transit state. Do not begin learning until every queued input
        // has been consumed and every queued output has left the factory; otherwise backlog is
        // mistaken for production during the sampling window.
        if (channelsEmpty && (drainStableTicks >= DRAIN_STABLE_TICKS || drainingTicks >= DRAIN_TIMEOUT_TICKS)) {
            enterLearning();
        }
    }

    private void enterLearning() {
        rebuildRuntimeIndex(pocketLevel(), true);
        blackbox.setIgnoredOutputs(staticInventory);
        blackbox.setRecording(true);
        operationMode = OperationMode.BLACKBOX_LEARNING;
        invalidateResourceCapabilities();
        learningTicksRemaining = LEARNING_TICKS;
        blackbox.beginSampling();
        setChanged();
        sendSync();
    }

    private void tickLearning() {
        if (learningTicksRemaining > LEARNING_WARMUP_TICKS) {
            // 鏆栨満闃舵锛氫笉閲囨牱锛岃宸ュ巶杈惧埌绋虫€?
            learningTicksRemaining--;
            if (learningTicksRemaining == LEARNING_WARMUP_TICKS) {
                blackbox.beginSampling();
            }
            return;
        }
        // 閲囨牱闃舵锛氶€愮獥鍙ｈ褰曟瘡绉掗€熺巼
        blackbox.tickRates();
        learningTicksRemaining--;
        if (learningTicksRemaining <= 0) {
            enterActive();
        }
    }

    private void enterActive() {
        blackbox.compileRecipe();
        blackbox.setRecording(false);
        scanPowerProfile(pocketLevel());
        operationMode = OperationMode.BLACKBOX_ACTIVE;
        invalidateResourceCapabilities();
        removeChunkRef("load");
        setChanged();
        sendSync();
    }

    private void tickBlackbox() {
        if (!productionBatch.matchesRecipe(blackbox)) {
            invalidateProductionBatch(null, "recipe_changed");
        }
        productionBatch.ensureRecipe(blackbox);

        int cycle = Math.max(BlackboxData.RATE_WINDOW, blackbox.getRecipeCycleTicks());
        if (itemCycleCounter < cycle) {
            itemCycleCounter++;
        }
        if (productionBatch.isDeliveringOutputs() || !productionBatch.inputsComplete(blackbox)
                || itemCycleCounter < cycle) {
            return;
        }

        FactoryPowerProfile activePowerProfile = effectivePowerProfile();
        boolean stressSatisfied = activePowerProfile.externalStressDemandSU() <= STRESS_EPSILON
                || externalStressSatisfied;
        if (!stressSatisfied) {
            return;
        }

        float boundedNetFE = Math.max(-MAX_FE_PER_TICK, Math.min(MAX_FE_PER_TICK, activePowerProfile.netFE()));
        long batchEnergy = Math.round(Math.abs(boundedNetFE) * cycle);
        if (boundedNetFE < 0 && energyStored < batchEnergy) {
            return;
        }
        if (boundedNetFE < 0) {
            energyStored -= (int) Math.min(batchEnergy, Integer.MAX_VALUE);
        } else if (boundedNetFE > 0) {
            energyStored = (int) Math.min(ENERGY_CAPACITY, energyStored + batchEnergy);
        }

        productionBatch.commitOutputs(blackbox);
        itemCycleCounter = 0;
        setChanged();
    }

    private void tickBlueprint() {
        // Blueprint mode must use the source factory's captured profile. Scanning this
        // target's own room would overwrite it (often with an empty room's zero demand)
        // and let a blueprint run without the source machine's stress requirement.
        tickBlackbox();
    }

    /**
     * Finds all six eligible kinetic neighbours, deduplicates shared Create networks, and
     * retains the source with the largest remaining capacity. The current reservation is added
     * back while comparing its own network so a factory does not abandon a source merely because
     * of the load it already registered there.
     */
    private ExternalStressCandidate selectExternalStressSource() {
        if (level == null) {
            return null;
        }
        Map<KineticNetwork, ExternalStressCandidate> candidates = new IdentityHashMap<>();
        for (Direction face : Direction.values()) {
            KineticBlockEntity anchor = adjacentStressInput(face);
            if (anchor == null) {
                continue;
            }
            float speed = anchor.getTheoreticalSpeed();
            if (!Float.isFinite(speed) || Math.abs(speed) <= STRESS_EPSILON) {
                continue;
            }
            KineticNetwork network = anchor.getOrCreateNetwork();
            float available = network.calculateCapacity() - network.calculateStress();
            if (network == reservedExternalNetwork) {
                available += reservedExternalSU;
            }
            ExternalStressCandidate candidate = new ExternalStressCandidate(face, anchor, network, speed, available);
            ExternalStressCandidate existing = candidates.get(network);
            if (existing == null
                    || candidate.availableSU() > existing.availableSU() + STRESS_EPSILON
                    || (Math.abs(candidate.availableSU() - existing.availableSU()) <= STRESS_EPSILON
                    && candidate.face().get3DDataValue() < existing.face().get3DDataValue())) {
                candidates.put(network, candidate);
            }
        }

        ExternalStressCandidate best = null;
        for (ExternalStressCandidate candidate : candidates.values()) {
            if (isBetterExternalStressCandidate(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isBetterExternalStressCandidate(ExternalStressCandidate candidate,
                                                     ExternalStressCandidate best) {
        if (best == null) {
            return true;
        }
        if (candidate.availableSU() > best.availableSU() + STRESS_EPSILON) {
            return true;
        }
        if (best.availableSU() > candidate.availableSU() + STRESS_EPSILON) {
            return false;
        }

        boolean candidateIsSelected = candidate.network() == selectedExternalNetwork;
        boolean bestIsSelected = best.network() == selectedExternalNetwork;
        if (candidateIsSelected != bestIsSelected) {
            return candidateIsSelected;
        }
        return candidate.face().get3DDataValue() < best.face().get3DDataValue();
    }

    private void selectExternalStressSource(ExternalStressCandidate candidate) {
        selectedExternalNetwork = candidate.network();
        selectedExternalFace = candidate.face();
        selectedExternalSpeed = candidate.speed();
    }

    private void clearExternalStressSelection() {
        selectedExternalNetwork = null;
        selectedExternalFace = null;
        selectedExternalSpeed = 0f;
    }

    private void clearExternalStressState() {
        releaseExternalStressReservation();
        clearExternalStressSelection();
    }

    /**
     * A factory accepts only mechanically valid kinetic neighbours. It remains a virtual consumer
     * of the selected network rather than creating a physical shaft connection that would merge
     * all six adjacent networks.
     */
    private KineticBlockEntity adjacentStressInput(Direction face) {
        BlockPos inputPos = worldPosition.relative(face);
        BlockState inputState = level.getBlockState(inputPos);
        if (!(inputState.getBlock() instanceof IRotate rotate)
                || !rotate.hasShaftTowards(level, inputPos, inputState, face.getOpposite())) {
            return null;
        }
        if (!(level.getBlockEntity(inputPos) instanceof KineticBlockEntity kbe)
                || kbe instanceof BeltBlockEntity) {
            return null;
        }
        return kbe;
    }

    private List<NestedStressPortBlockEntity> roomStressPorts(ServerLevel pocket) {
        List<NestedStressPortBlockEntity> ports = new ArrayList<>();
        for (BlockPos stressPortPos : PocketRegistry.getStressPorts(roomOrigin())) {
            if (pocket.getBlockEntity(stressPortPos) instanceof NestedStressPortBlockEntity stressPort) {
                ports.add(stressPort);
            }
        }
        ports.sort(Comparator.comparingLong(port -> port.getBlockPos().asLong()));
        return ports;
    }

    /**
     * Applies the one factory-wide reservation to the selected external network. The factory block
     * entity is inserted only as a virtual member of that one network; it never physically joins
     * or bridges the six adjacent networks.
     */
    private boolean reserveExternalStress(ExternalStressCandidate candidate, float requestedSU) {
        float demand = Math.max(0f, Float.isFinite(requestedSU) ? requestedSU : 0f);
        if (demand <= STRESS_EPSILON) {
            releaseExternalStressReservation();
            externalStressSatisfied = true;
            return true;
        }
        if (candidate == null) {
            clearExternalStressState();
            return false;
        }

        if (reservedExternalNetwork != null && reservedExternalNetwork != candidate.network()) {
            releaseExternalStressReservation();
        }

        float speed = candidate.speed();
        float impact = demand / Math.abs(speed);
        setSpeed(speed);
        candidate.network().updateStressFor(this, impact);
        reservedExternalNetwork = candidate.network();
        reservedExternalNetworkId = candidate.network().id;
        reservedExternalFace = candidate.face();
        reservedExternalSU = demand;
        reservedStressImpact = impact;

        externalStressSatisfied = candidate.network().calculateCapacity() + STRESS_EPSILON
                >= candidate.network().calculateStress()
                && !candidate.anchor().isOverStressed();
        return externalStressSatisfied;
    }

    private void releaseExternalStressReservation() {
        if (reservedExternalNetwork != null) {
            reservedExternalNetwork.remove(this);
        }
        reservedExternalNetwork = null;
        reservedExternalNetworkId = null;
        reservedExternalFace = null;
        reservedExternalSU = 0f;
        reservedStressImpact = 0f;
        externalStressSatisfied = false;
        setSpeed(0f);
    }

    /** Computes one non-duplicated external deficit for every distinct internal kinetic network. */
    private Map<NestedStressPortBlockEntity, Float> calculatePortStressRequests(
            List<NestedStressPortBlockEntity> ports) {
        Map<NestedStressPortBlockEntity, Float> requests = new HashMap<>();
        Map<KineticNetwork, List<NestedStressPortBlockEntity>> groups = new IdentityHashMap<>();
        for (NestedStressPortBlockEntity port : ports) {
            requests.put(port, 0f);
            if (!port.hasNetwork()) {
                continue;
            }
            KineticNetwork network = port.getOrCreateNetwork();
            groups.computeIfAbsent(network, ignored -> new ArrayList<>()).add(port);
        }

        for (Map.Entry<KineticNetwork, List<NestedStressPortBlockEntity>> entry : groups.entrySet()) {
            KineticNetwork network = entry.getKey();
            List<NestedStressPortBlockEntity> groupPorts = entry.getValue();
            float relayCapacity = 0f;
            for (NestedStressPortBlockEntity port : groupPorts) {
                if (network.sources.containsKey(port)) {
                    relayCapacity += network.getActualCapacityOf(port);
                }
            }
            float nativeCapacity = Math.max(0f, network.calculateCapacity() - relayCapacity);
            float groupDemand = Math.max(0f, network.calculateStress() - nativeCapacity);
            float remaining = groupDemand;
            for (int i = 0; i < groupPorts.size(); i++) {
                NestedStressPortBlockEntity port = groupPorts.get(i);
                float share = i == groupPorts.size() - 1
                        ? remaining
                        : groupDemand / groupPorts.size();
                requests.put(port, share);
                remaining -= share;
            }
        }
        return requests;
    }

    /** Live modes prepare detached ports, total distinct internal deficits, then settle the shared budget. */
    private void settleLiveStressRelay() {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            liveExternalStressDemandSU = 0f;
            clearExternalStressState();
            return;
        }
        List<NestedStressPortBlockEntity> ports = roomStressPorts(pocket);
        ExternalStressCandidate candidate = selectExternalStressSource();
        if (candidate == null) {
            liveExternalStressDemandSU = 0f;
            clearExternalStressState();
            for (NestedStressPortBlockEntity port : ports) {
                port.clearStressAllocation();
            }
            return;
        }

        selectExternalStressSource(candidate);
        // Keep the external network's sign: a source reversal must also reverse the
        // room-side relay instead of being reduced to an unsigned RPM magnitude.
        float selectedSpeed = candidate.speed();

        // Only a detached/new port needs a zero-capacity seed to create its internal network.
        // Existing ports already belong to a Create network; clearing their capacity here and
        // restoring it below would make that network alternate between overstressed and healthy
        // every tick. Create counts each transition as kinetic flicker and can eventually destroy
        // a port or any machine during a later propagation/update.
        for (NestedStressPortBlockEntity port : ports) {
            if (!port.hasNetwork()) {
                port.setStressAllocation(0f, 0f, selectedSpeed, false);
            } else {
                port.setStressAllocation(port.getRequestedSU(), port.getAllocatedSU(), selectedSpeed,
                        port.isSourceSatisfied());
            }
        }

        Map<NestedStressPortBlockEntity, Float> requests = calculatePortStressRequests(ports);
        float totalDemand = 0f;
        for (float requested : requests.values()) {
            totalDemand += requested;
        }
        liveExternalStressDemandSU = totalDemand;

        if (totalDemand <= STRESS_EPSILON) {
            releaseExternalStressReservation();
            externalStressSatisfied = true;
            for (NestedStressPortBlockEntity port : ports) {
                port.setStressAllocation(0f, 0f, selectedSpeed, true);
            }
            return;
        }

        boolean satisfied = reserveExternalStress(candidate, totalDemand);
        for (NestedStressPortBlockEntity port : ports) {
            float requested = requests.getOrDefault(port, 0f);
            port.setStressAllocation(requested, satisfied ? requested : 0f,
                    selectedSpeed, satisfied);
        }
    }

    /** Black-box and blueprint modes reserve their frozen demand but never power real room ports. */
    private void settleSimulatedStress() {
        clearStressRelay();
        FactoryPowerProfile profile = effectivePowerProfile();
        ExternalStressCandidate candidate = selectExternalStressSource();
        if (candidate == null) {
            clearExternalStressState();
            return;
        }
        selectExternalStressSource(candidate);
        reserveExternalStress(candidate, profile.externalStressDemandSU());
    }

    public void scanPowerProfile(ServerLevel pocketLevel) {
        ensureRuntimeIndex(pocketLevel);
        refreshPowerSnapshot(pocketLevel, new HashSet<>());
    }

    public void markRuntimeIndexDirty() {
        runtimeIndex.markDirty();
    }

    public void onRoomMutationTaskFinished() {
        markRuntimeIndexDirty();
        if (usesRuntimeIndex()) {
            rebuildRuntimeIndex(pocketLevel(), true);
        }
        setChanged();
        sendSync();
    }

    private RoomMutationTaskManager.FactoryRef roomTaskReference() {
        return new RoomMutationTaskManager.FactoryRef(level.dimension(), worldPosition.immutable(),
                factoryId, rootFactoryId);
    }

    public boolean isRoomMutationLocked() {
        if (level == null || level.isClientSide()) {
            return false;
        }
        MinecraftServer server = level.getServer();
        return server != null
                && RoomMutationTaskManager.get(server).isRoomLocked(NestedFactoryBlock.POCKET_DIMENSION, roomOrigin());
    }

    public boolean isTaskManagerRemovingThisFactory() {
        if (level == null || level.isClientSide()) {
            return false;
        }
        MinecraftServer server = level.getServer();
        return server != null
                && RoomMutationTaskManager.get(server).isRemovingFactory(roomTaskReference());
    }

    private int[] roomBounds(BlockPos origin) {
        return new int[]{bounds.minX(origin), bounds.minY(origin), bounds.minZ(origin),
                bounds.maxX(origin), bounds.maxY(origin), bounds.maxZ(origin)};
    }

    private int[] expandedInteriorBounds(BlockPos origin, PocketBounds old, Direction direction) {
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minY = bounds.minY(origin), maxY = bounds.maxY(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        return switch (direction) {
            case EAST -> new int[]{old.maxX(origin), minY + 1, minZ + 1, maxX - 1, maxY - 1, maxZ - 1};
            case WEST -> new int[]{minX + 1, minY + 1, minZ + 1, old.minX(origin), maxY - 1, maxZ - 1};
            case UP -> new int[]{minX + 1, old.maxY(origin), minZ + 1, maxX - 1, maxY - 1, maxZ - 1};
            case DOWN -> new int[]{minX + 1, minY + 1, minZ + 1, maxX - 1, old.minY(origin), maxZ - 1};
            case SOUTH -> new int[]{minX + 1, minY + 1, old.maxZ(origin), maxX - 1, maxY - 1, maxZ - 1};
            case NORTH -> new int[]{minX + 1, minY + 1, minZ + 1, maxX - 1, maxY - 1, old.minZ(origin)};
        };
    }

    private int[] removedSlabBounds(BlockPos origin, PocketBounds old, Direction direction) {
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minY = bounds.minY(origin), maxY = bounds.maxY(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        return switch (direction) {
            case EAST -> new int[]{maxX + 1, minY, minZ, old.maxX(origin), maxY, maxZ};
            case WEST -> new int[]{old.minX(origin), minY, minZ, minX - 1, maxY, maxZ};
            case UP -> new int[]{minX, maxY + 1, minZ, maxX, old.maxY(origin), maxZ};
            case DOWN -> new int[]{minX, old.minY(origin), minZ, maxX, minY - 1, maxZ};
            case SOUTH -> new int[]{minX, minY, maxZ + 1, maxX, maxY, old.maxZ(origin)};
            case NORTH -> new int[]{minX, minY, old.minZ(origin), maxX, maxY, minZ - 1};
        };
    }

    private boolean usesRuntimeIndex() {
        return operationMode == OperationMode.CHUNK_LOADED
                || operationMode == OperationMode.BLACKBOX_DRAINING
                || operationMode == OperationMode.BLACKBOX_LEARNING;
    }

    private void ensureRuntimeIndex(ServerLevel pocketLevel) {
        if (pocketLevel != null && runtimeIndex.needsRebuild()) {
            rebuildRuntimeIndex(pocketLevel, true);
        }
    }

    private void rebuildRuntimeIndex(ServerLevel pocketLevel, boolean propagateToParents) {
        if (pocketLevel == null) {
            return;
        }
        runtimeIndex.beginRebuild();
        BlockPos origin = roomOrigin();
        for (int x = bounds.minX(origin); x <= bounds.maxX(origin); x++) {
            for (int y = bounds.minY(origin); y <= bounds.maxY(origin); y++) {
                for (int z = bounds.minZ(origin); z <= bounds.maxZ(origin); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = pocketLevel.getBlockState(pos);
                    if (state.isAir() || NestedFactoryBlock.isWallBlock(state)) {
                        continue;
                    }
                    BlockEntity blockEntity = pocketLevel.getBlockEntity(pos);
                    if (blockEntity instanceof NestedFactoryBlockEntity) {
                        runtimeIndex.childFactoryPositions().add(pos.immutable());
                    } else if (blockEntity instanceof KineticBlockEntity) {
                        runtimeIndex.kineticPositions().add(pos.immutable());
                    }
                    if (findEnergyStorage(pocketLevel, pos) != null) {
                        runtimeIndex.energyPositions().add(pos.immutable());
                    }
                    if (findItemHandler(pocketLevel, pos) != null) {
                        runtimeIndex.inventoryPositions().add(pos.immutable());
                    }
                }
            }
        }
        runtimeIndex.completeRebuild();
        refreshPowerSnapshot(pocketLevel, new HashSet<>());
        if (propagateToParents) {
            propagatePowerSnapshotToParents(new HashSet<>());
        }
    }

    private void refreshPowerSnapshot(ServerLevel pocketLevel, Set<String> visitingFactories) {
        if (pocketLevel == null || !visitingFactories.add(factoryId)) {
            return;
        }
        try {
            float genSU = 0f, conSU = 0f, genFE = 0f, conFE = 0f;
            for (BlockPos pos : runtimeIndex.childFactoryPositions()) {
                if (pocketLevel.getBlockEntity(pos) instanceof NestedFactoryBlockEntity childFactory) {
                    conSU += childFactory.stressDemandFromParent(visitingFactories);
                }
            }
            for (BlockPos pos : runtimeIndex.kineticPositions()) {
                if (!(pocketLevel.getBlockEntity(pos) instanceof KineticBlockEntity kbe)) {
                    continue;
                }
                float speed = Math.abs(kbe.getTheoreticalSpeed());
                conSU += Math.abs(kbe.calculateStressApplied()) * speed;
                if (!(kbe instanceof NestedStressPortBlockEntity)) {
                    genSU += kbe.calculateAddedStressCapacity() * speed;
                }
            }
            for (BlockPos pos : runtimeIndex.energyPositions()) {
                IEnergyStorage storage = findEnergyStorage(pocketLevel, pos);
                if (storage == null) {
                    continue;
                }
                float rating = Math.min(storage.getMaxEnergyStored(), MAX_FE_PER_TICK);
                if (storage.canExtract()) genFE += rating;
                if (storage.canReceive()) conFE += rating;
            }
            powerProfile.set(genSU, conSU, genFE, conFE, liveExternalStressDemandSU);
        } finally {
            visitingFactories.remove(factoryId);
        }
    }

    private void propagatePowerSnapshotToParents(Set<String> visitedFactories) {
        if (!visitedFactories.add(factoryId) || parentFactoryPos == null || parentDimension == null || level == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        ServerLevel parentLevel = server.getLevel(parentDimension);
        if (parentLevel == null || !(parentLevel.getBlockEntity(parentFactoryPos) instanceof NestedFactoryBlockEntity parent)) {
            return;
        }
        if (parent.runtimeIndex.needsRebuild()) {
            parent.rebuildRuntimeIndex(parent.pocketLevel(), false);
        }
        parent.refreshPowerSnapshot(parent.pocketLevel(), new HashSet<>());
        parent.propagatePowerSnapshotToParents(visitedFactories);
    }

    private float stressDemandFromParent(Set<String> visitingFactories) {
        if (invalidNested && !blueprintApplied) {
            return 0f;
        }
        if (operationMode != OperationMode.BLACKBOX_ACTIVE && operationMode != OperationMode.BLUEPRINT) {
            ensureRuntimeIndex(pocketLevel());
            refreshPowerSnapshot(pocketLevel(), visitingFactories);
        }
        return effectivePowerProfile().externalStressDemandSU();
    }
    private static IEnergyStorage findEnergyStorage(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, d);
            if (storage != null) {
                return storage;
            }
        }
        return null;
    }

    private static IItemHandler findItemHandler(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            return handler;
        }
        for (Direction d : Direction.values()) {
            handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, d);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    /** Counts only handlers discovered by the runtime room index. */
    private Map<ItemVariant, Long> countItemsInFactorySpace() {
        Map<ItemVariant, Long> counts = new HashMap<>();
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return counts;
        }
        ensureRuntimeIndex(pocket);
        var iterator = runtimeIndex.inventoryPositions().iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            IItemHandler handler = findItemHandler(pocket, pos);
            if (handler == null) {
                iterator.remove();
                continue;
            }
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    counts.merge(ItemVariant.of(stack), (long) stack.getCount(), Math::addExact);
                }
            }
        }
        return counts;
    }

    private static int totalCount(Map<ItemVariant, Long> counts) {
        long total = 0;
        for (long value : counts.values()) {
            total = Math.addExact(total, value);
        }
        return Math.toIntExact(total);
    }

    public boolean expandSpace(ServerLevel level, Direction direction) {
        if (nested || isRoomMutationLocked() || !bounds.canExpand(direction)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return false;
        }
        BlockPos origin = roomOrigin();
        PocketBounds old = bounds.copy();
        bounds.expand(direction);
        boolean scheduled = RoomMutationTaskManager.get(server).scheduleExpand(
                NestedFactoryBlock.POCKET_DIMENSION, origin, roomBounds(origin),
                expandedInteriorBounds(origin, old, direction), roomTaskReference());
        if (!scheduled) {
            bounds.collapse(direction);
            return false;
        }
        boundsVersion++;
        if (pocketChunksForced) applyPocketChunkForce(true);
        setChanged();
        return true;
    }

    public boolean collapseSpace(ServerLevel level, Direction direction) {
        return beginCollapseValidation(level, direction, net.minecraft.world.item.Items.AIR, null);
    }

    public boolean beginCollapseValidation(ServerLevel level, Direction direction, Item refundItem) {
        return beginCollapseValidation(level, direction, refundItem, null);
    }

    public boolean beginCollapseValidation(ServerLevel level, Direction direction, Item refundItem, UUID requesterId) {
        if (nested || isRoomMutationLocked() || !bounds.canCollapse(direction)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return false;
        }
        BlockPos origin = roomOrigin();
        PocketBounds old = bounds.copy();
        int[] removedBounds = slabBounds(origin, direction);
        int[] validateBounds = extendCollapseValidationTowardCenter(removedBounds, direction, 1);
        int[] playerValidateBounds = extendCollapseValidationTowardCenter(removedBounds, direction, 2);
        bounds.collapse(direction);
        boolean scheduled = RoomMutationTaskManager.get(server).scheduleCollapseValidation(
                NestedFactoryBlock.POCKET_DIMENSION, origin, roomBounds(origin),
                removedSlabBounds(origin, old, direction), validateBounds, playerValidateBounds, roomTaskReference(),
                direction.name(), refundItem, requesterId);
        if (!scheduled) {
            bounds.expand(direction);
            return false;
        }
        boundsVersion++;
        if (pocketChunksForced) applyPocketChunkForce(true);
        setChanged();
        return true;
    }

    public void onCollapseValidationTaskFailed(String directionName) {
        try {
            bounds.expand(Direction.valueOf(directionName));
            boundsVersion++;
            markRuntimeIndexDirty();
            setChanged();
            sendSync();
        } catch (IllegalArgumentException ignored) {
            // A malformed persisted task cannot safely mutate current bounds.
        }
    }

    private int[] slabBounds(BlockPos origin, Direction direction) {
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minY = bounds.minY(origin), maxY = bounds.maxY(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        return switch (direction) {
            case EAST -> new int[]{maxX - 15, minY, minZ, maxX, maxY, maxZ};
            case WEST -> new int[]{minX, minY, minZ, minX + 15, maxY, maxZ};
            case UP -> new int[]{minX, maxY - 15, minZ, maxX, maxY, maxZ};
            case DOWN -> new int[]{minX, minY, minZ, maxX, minY + 15, maxZ};
            case SOUTH -> new int[]{minX, minY, maxZ - 15, maxX, maxY, maxZ};
            case NORTH -> new int[]{minX, minY, minZ, maxX, maxY, minZ + 15};
        };
    }

    /** Extends a collapsing slab only inward, preserving the current room shell dimensions elsewhere. */
    private static int[] extendCollapseValidationTowardCenter(int[] bounds, Direction direction, int distance) {
        int[] extended = bounds.clone();
        switch (direction) {
            case EAST -> extended[0] -= distance;
            case WEST -> extended[3] += distance;
            case UP -> extended[1] -= distance;
            case DOWN -> extended[4] += distance;
            case SOUTH -> extended[2] -= distance;
            case NORTH -> extended[5] += distance;
        }
        return extended;
    }

    public void cycleFaceMode(Direction face, ServerPlayer player) {
        Component message = cycleFaceMode(face, player, true);
        if (message != null) {
            PlayerMessagePayload.sendTo(player, message.copy().withStyle(ChatFormatting.GREEN), true);
        }
    }

    /** Applies a GUI face-mode change and returns the server-authoritative feedback for client rendering. */
    public Component cycleFaceModeFromMenu(Direction face, ServerPlayer player) {
        return cycleFaceMode(face, player, true);
    }

    private Component cycleFaceMode(Direction face, ServerPlayer player, boolean reportLockFailure) {
        if (isRoomMutationLocked()) {
            if (reportLockFailure) {
                PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.room_mutation.active")
                        .withStyle(ChatFormatting.YELLOW), false);
            }
            return null;
        }
        int index = face.get3DDataValue();
        invalidateProductionBatch(player, "face_mode_changed");
        portChannels.clearFluids();
        roomFluidNetworkSignatures.clear();
        faceModes[index] = faceModes[index].next();
        if (faceModes[index] == PortMode.NONE) {
            portIds[index] = 0;
        } else if (portIds[index] == 0) {
            portIds[index] = allocateLowestUnusedPortId(index);
            if (portIds[index] == 0) {
                faceModes[index] = PortMode.NONE;
            }
        }
        normalizeFacePortBindings();
        invalidateResourceCapabilities();
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        Component faceName = Component.translatable("direction.create_nested_factory." + face.getSerializedName());
        Component modeName = Component.translatable("goggles.create_nested_factory.port_mode."
                + faceModes[index].getSerializedName());
        String key = faceModes[index] == PortMode.NONE
                ? "message.create_nested_factory.face_mode.updated_none"
                : "message.create_nested_factory.face_mode.updated";
        Component message = faceModes[index] == PortMode.NONE
                ? Component.translatable(key, faceName, modeName)
                : Component.translatable(key, faceName, modeName, portIds[index]);
        return message;
    }

    private int allocateLowestUnusedPortId(int excludedFaceIndex) {
        return FactoryFacePortBindings.allocateLowestUnused(faceModes, portIds, excludedFaceIndex);
    }

    private boolean normalizeFacePortBindings() {
        return FactoryFacePortBindings.normalize(faceModes, portIds);
    }

    public IItemHandler getItemHandler(Direction side) {
        if (side == null) {
            return UNSIDED_ITEM_HANDLER_PROBE;
        }
        if (faceModes[side.get3DDataValue()] == PortMode.NONE) {
            return null;
        }
        if (faceModes[side.get3DDataValue()] == PortMode.INPUT
                && operationMode == OperationMode.BLACKBOX_DRAINING) {
            return null;
        }
        if (!isSimulatedMode() && faceModes[side.get3DDataValue()] == PortMode.OUTPUT) {
            markExternalOutputConsumer(side, false);
        }
        return faceItemHandlers[side.get3DDataValue()];
    }

    /**
     * Create package-unpacking entry point. A package is one boundary handoff: it is either
     * accepted in full or left untouched for the packager to retry.
     */
    public boolean acceptUnpackedItems(Direction side, List<ItemStack> stacks, boolean simulate) {
        if (side == null || stacks == null || stacks.isEmpty()) {
            return false;
        }
        int faceIndex = side.get3DDataValue();
        if (faceModes[faceIndex] != PortMode.INPUT || operationMode == OperationMode.BLACKBOX_DRAINING) {
            return false;
        }

        if (isSimulatedMode()) {
            boolean accepted = productionBatch.acceptItemInputs(blackbox, stacks, simulate);
            if (accepted && !simulate) {
                setChanged();
            }
            return accepted;
        }

        int portId = portIds[faceIndex];
        if (!canAcceptInput(portId, false)) {
            return false;
        }
        FactoryPortChannels.PortResourceChannel resourceChannel = portChannel(portId);
        if (simulate) {
            return resourceChannel.canAcceptInputItemBatch(currentGameTime(), stacks);
        }

        boolean wasEmpty = resourceChannel.inputItems().isEmpty();
        if (!resourceChannel.insertInputItemBatch(currentGameTime(), stacks)) {
            return false;
        }
        setChanged();
        notifyChannelBecameAvailable(wasEmpty, resourceChannel.inputItems().isEmpty());
        return true;
    }

    /** Atomically accepts the contents of a package at an OUTPUT room port. */
    public boolean acceptRoomUnpackedItems(int portId, List<ItemStack> stacks, boolean simulate) {
        if (stacks == null || stacks.isEmpty() || isSimulatedMode()
                || !hasRoomPort(portId) || getFacesForPortId(portId).stream()
                .noneMatch(face -> faceModes[face.get3DDataValue()] == PortMode.OUTPUT)) {
            return false;
        }
        FactoryPortChannels.PortResourceChannel resourceChannel = portChannel(portId);
        if (simulate) {
            return resourceChannel.canAcceptOutputItemBatch(currentGameTime(), stacks);
        }
        if (!resourceChannel.insertOutputItemBatch(currentGameTime(), stacks)) {
            return false;
        }
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                recordItemTransfer(false, stack, stack.getCount());
            }
        }
        setChanged();
        invalidateResourceCapabilities();
        return true;
    }

    public IFluidHandler getFluidHandler(Direction side) {
        if (side == null || faceModes[side.get3DDataValue()] == PortMode.NONE) {
            return null;
        }
        if (faceModes[side.get3DDataValue()] == PortMode.INPUT
                && operationMode == OperationMode.BLACKBOX_DRAINING) {
            return null;
        }
        if (!isSimulatedMode() && faceModes[side.get3DDataValue()] == PortMode.OUTPUT) {
            markExternalOutputConsumer(side, true);
        }
        return faceFluidHandlers[side.get3DDataValue()];
    }

    public IItemHandler getRoomItemHandler(int portId, Direction side) {
        if (isSimulatedMode()) {
            return null;
        }
        Direction face = getFaceForPortId(portId);
        if (face == null || faceModes[face.get3DDataValue()] == PortMode.NONE) {
            return null;
        }
        return new RoomItemBridgeHandler(portId);
    }

    public IFluidHandler getRoomFluidHandler(int portId, BlockPos roomPortPos, Direction side) {
        if (isSimulatedMode() || roomPortPos == null || side == null) {
            return null;
        }
        Direction face = getFaceForPortId(portId);
        if (face == null || faceModes[face.get3DDataValue()] == PortMode.NONE) {
            return null;
        }
        return new RoomFluidBridgeHandler(portId);
    }

    private final Map<Integer, Set<FluidTopologyPoint>> roomFluidNetworkSignatures = new HashMap<>();
    /** External Create topology signatures are polled because no global pipe event is used. */
    private final Map<Integer, Set<FluidTopologyPoint>> externalFluidNetworkSignatures = new HashMap<>();

    private record FluidTopologyPoint(BlockPos pos, int side) {
        private FluidTopologyPoint {
            pos = pos.immutable();
        }
    }

    /** Refreshes every existing pipe face in one port group. */
    void refreshRoomFluidNetworks(int portId) {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        for (BlockPos portPos : PocketRegistry.getPorts(roomOrigin(), portId)) {
            pocket.invalidateCapabilities(portPos);
            if (pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port) {
                port.refreshRoomFluidNetworks();
                port.requestFluidPressureRefresh();
            }
        }
    }

    /**
     * Rebuilds a port group's room-side Create networks only when the structural pipe graph
     * changes. This catches a tank added at the far end of an existing pipe without repeatedly
     * wiping healthy networks.
     */
    boolean refreshRoomFluidNetworksIfSignatureChanged(int portId) {
        Set<FluidTopologyPoint> signature = roomFluidNetworkSignature(portId);
        Set<FluidTopologyPoint> previous = roomFluidNetworkSignatures.put(portId, signature);
        if (previous != null && previous.equals(signature)) {
            return false;
        }
        refreshRoomFluidNetworks(portId);
        return true;
    }

    private Set<FluidTopologyPoint> roomFluidNetworkSignature(int portId) {
        Set<FluidTopologyPoint> signature = new HashSet<>();
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return signature;
        }

        Set<BlockPos> visitedPipes = new HashSet<>();
        List<BlockPos> pendingPipes = new ArrayList<>();
        for (BlockPos portPos : PocketRegistry.getPorts(roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos adjacentPos = portPos.relative(side);
                if (FluidPropagator.getPipe(pocket, adjacentPos) != null) {
                    pendingPipes.add(adjacentPos);
                }
            }
        }

        int cursor = 0;
        while (cursor < pendingPipes.size()) {
            BlockPos pipePos = pendingPipes.get(cursor++);
            if (!pocket.isLoaded(pipePos) || !visitedPipes.add(pipePos)) {
                continue;
            }
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(pocket, pipePos);
            if (pipe == null) {
                continue;
            }
            BlockState pipeState = pocket.getBlockState(pipePos);
            signature.add(new FluidTopologyPoint(pipePos, -1));
            for (Direction face : FluidPropagator.getPipeConnections(pipeState, pipe)) {
                signature.add(new FluidTopologyPoint(pipePos, face.get3DDataValue()));
                BlockPos connectedPos = pipePos.relative(face);
                FluidTransportBehaviour connectedPipe = FluidPropagator.getPipe(pocket, connectedPos);
                if (connectedPipe != null) {
                    pendingPipes.add(connectedPos);
                    continue;
                }
                BlockState connectedState = pocket.getBlockState(connectedPos);
                if (PumpBlock.isPump(connectedState)
                        && pocket.getBlockEntity(connectedPos) instanceof PumpBlockEntity pump) {
                    signature.add(new FluidTopologyPoint(connectedPos,
                            1000 + System.identityHashCode(pump)));
                    signature.add(new FluidTopologyPoint(connectedPos,
                            1100 + connectedState.getValue(PumpBlock.FACING).get3DDataValue()));
                    for (Direction pumpSide : Direction.values()) {
                        signature.add(new FluidTopologyPoint(connectedPos,
                                1200 + pumpSide.get3DDataValue() * 2
                                        + (pump.isPullingOnSide(connectedState.getValue(PumpBlock.FACING)
                                        == pumpSide.getOpposite()) ? 1 : 0)));
                    }
                    continue;
                }
                if (FluidPropagator.isOpenEnd(pocket, pipePos, face)
                        || pocket.getCapability(Capabilities.FluidHandler.BLOCK,
                        connectedPos, face.getOpposite()) != null) {
                    signature.add(new FluidTopologyPoint(connectedPos, face.getOpposite().get3DDataValue()));
                }
            }
        }
        return signature;
    }

    public void onRoomPortLogisticsConnectionChanged() {
        invalidateRoomResourceCapabilities();
    }

    private void invalidateRoomResourceCapabilities() {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        for (int portId = 1; portId <= 6; portId++) {
            for (BlockPos portPos : PocketRegistry.getPorts(roomOrigin(), portId)) {
                pocket.invalidateCapabilities(portPos);
            }
        }
    }

    public boolean hasPendingPortResources() {
        return !portChannels.isEmpty();
    }

    /** Drops pending item transit resources at this factory and destroys pending fluid transit resources. */
    public void dropPendingPortItemsAndDiscardFluids() {
        if (level == null || level.isClientSide()) {
            return;
        }
        for (ItemStack stack : portChannels.drainItemsAndDiscardFluids()) {
            if (!stack.isEmpty()) {
                Block.popResource(level, worldPosition, stack);
            }
        }
        setChanged();
    }

    public void onExternalNeighborChanged(BlockPos neighborPos) {
        if (level == null || level.isClientSide()) {
            return;
        }
        boolean changed = false;
        for (Direction face : Direction.values()) {
            if (!worldPosition.relative(face).equals(neighborPos)) {
                continue;
            }
            int index = face.get3DDataValue();
            Block current = level.getBlockState(neighborPos).getBlock();
            if (externalItemOutputConsumers[index] != null && externalItemOutputConsumers[index] != current) {
                externalItemOutputConsumers[index] = null;
                changed = true;
            }
            if (externalFluidOutputConsumers[index] != null && externalFluidOutputConsumers[index] != current) {
                externalFluidOutputConsumers[index] = null;
                changed = true;
            }
            break;
        }
        if (changed) {
            invalidateResourceCapabilities();
        } else {
            refreshExternalFluidNetworks();
        }
    }

    private void markExternalOutputConsumer(Direction face, boolean fluid) {
        if (level == null || level.isClientSide()) {
            return;
        }
        int index = face.get3DDataValue();
        Block current = level.getBlockState(worldPosition.relative(face)).getBlock();
        Block[] consumers = fluid ? externalFluidOutputConsumers : externalItemOutputConsumers;
        if (consumers[index] == current) {
            return;
        }
        consumers[index] = current;
        invalidateResourceCapabilities();
    }

    private boolean hasExternalOutputConsumer(int portId, boolean fluid) {
        Direction face = getFaceForPortId(portId);
        if (face == null || level == null || level.isClientSide()) {
            return false;
        }
        int index = face.get3DDataValue();
        Block expected = (fluid ? externalFluidOutputConsumers : externalItemOutputConsumers)[index];
        return expected != null && level.getBlockState(worldPosition.relative(face)).getBlock() == expected;
    }

    private boolean hasRoomPort(int portId) {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return false;
        }
        for (BlockPos pos : PocketRegistry.getPorts(roomOrigin(), portId)) {
            if (pocket.getBlockEntity(pos) instanceof NestedPortBlockEntity port
                    && port.getTargetPortId() == portId) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRoomInputConsumer(int portId, boolean fluid) {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return false;
        }
        for (BlockPos pos : PocketRegistry.getPorts(roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(pos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            if (fluid ? port.hasFluidInputConsumer() : port.hasItemInputConsumer()) {
                return true;
            }
        }
        return false;
    }

    private boolean canAcceptInput(int portId, boolean fluid) {
        if (operationMode == OperationMode.BLACKBOX_DRAINING || isSimulatedMode()
                || !hasRoomPort(portId)) {
            return false;
        }
        if (fluid) {
            return true;
        }
        return hasRoomInputConsumer(portId, false)
                && portChannel(portId).canAcceptInputItems(currentGameTime());
    }

    private boolean canAcceptRoomOutput(int portId, boolean fluid) {
        if (isSimulatedMode() || !hasRoomPort(portId)) {
            return false;
        }
        if (fluid) {
            return true;
        }
        // OUTPUT is also a room-side source for Create's Packager. Do not require the
        // external face to have been queried first; the shared handoff channel is the source.
        return portChannel(portId).canAcceptOutputItems(currentGameTime());
    }

    private long currentGameTime() {
        return level == null ? 0L : level.getGameTime();
    }
    private FactoryPortChannels.PortResourceChannel portChannel(int portId) {
        return portChannels.channel(portId);
    }

    private int connectedExternalFluidFaceCount(int portId) {
        if (level == null || level.isClientSide()) {
            return 0;
        }
        int count = 0;
        for (Direction face : getFacesForPortId(portId)) {
            BlockPos adjacentPos = worldPosition.relative(face);
            BlockState state = level.getBlockState(adjacentPos);
            if (level.getCapability(Capabilities.FluidHandler.BLOCK, adjacentPos, face.getOpposite()) != null
                    || (FluidPropagator.getPipe(level, adjacentPos) != null
                    && FluidPropagator.getPipe(level, adjacentPos).canHaveFlowToward(state, face.getOpposite()))) {
                count++;
            }
        }
        return count;
    }

    private List<IFluidHandler> externalFluidHandlers(int portId) {
        List<IFluidHandler> handlers = new ArrayList<>();
        if (level == null || level.isClientSide()) {
            return handlers;
        }
        Set<IFluidHandler> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction face : getFacesForPortId(portId)) {
            BlockPos adjacentPos = worldPosition.relative(face);
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK,
                    adjacentPos, face.getOpposite());
            if (handler != null && seen.add(handler)) {
                handlers.add(handler);
            }
        }
        return handlers;
    }

    private IFluidHandler externalFluidHandler(int portId) {
        List<IFluidHandler> handlers = externalFluidHandlers(portId);
        return handlers.isEmpty() ? null : handlers.get(0);
    }

    private List<IFluidHandler> roomFluidHandlers(int portId) {
        List<IFluidHandler> handlers = new ArrayList<>();
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return handlers;
        }
        Set<IFluidHandler> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockPos portPos : PocketRegistry.getPorts(roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos adjacentPos = portPos.relative(side);
                IFluidHandler handler = pocket.getCapability(Capabilities.FluidHandler.BLOCK,
                        adjacentPos, side.getOpposite());
                if (handler != null && seen.add(handler)) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
    }

    private List<IFluidHandler> resolveExternalFluidHandlers(int portId, FluidStack request,
                                                               FluidNetworkEndpointResolver.Operation operation) {
        List<IFluidHandler> result = new ArrayList<>();
        Set<IFluidHandler> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction face : getFacesForPortId(portId)) {
            for (IFluidHandler handler : FluidNetworkEndpointResolver.find(level, worldPosition, face,
                    request, operation)) {
                if (seen.add(handler)) {
                    result.add(handler);
                }
            }
        }
        return result;
    }

    private List<IFluidHandler> resolveExternalFluidHandlers(int portId, int maxDrain) {
        List<IFluidHandler> result = new ArrayList<>();
        Set<IFluidHandler> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction face : getFacesForPortId(portId)) {
            for (IFluidHandler handler : FluidNetworkEndpointResolver.findDrain(level, worldPosition, face,
                    maxDrain)) {
                if (seen.add(handler)) {
                    result.add(handler);
                }
            }
        }
        return result;
    }

    private List<IFluidHandler> resolveRoomFluidHandlers(int portId, FluidStack request,
                                                          FluidNetworkEndpointResolver.Operation operation) {
        List<IFluidHandler> result = new ArrayList<>();
        Set<IFluidHandler> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return result;
        }
        for (BlockPos portPos : PocketRegistry.getPorts(roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            for (Direction side : Direction.values()) {
                for (IFluidHandler handler : FluidNetworkEndpointResolver.find(pocket, portPos, side,
                        request, operation)) {
                    if (seen.add(handler)) {
                        result.add(handler);
                    }
                }
            }
        }
        return result;
    }

    private List<IFluidHandler> resolveRoomFluidHandlers(int portId, int maxDrain) {
        List<IFluidHandler> result = new ArrayList<>();
        Set<IFluidHandler> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return result;
        }
        for (BlockPos portPos : PocketRegistry.getPorts(roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            for (Direction side : Direction.values()) {
                for (IFluidHandler handler : FluidNetworkEndpointResolver.findDrain(pocket, portPos, side,
                        maxDrain)) {
                    if (seen.add(handler)) {
                        result.add(handler);
                    }
                }
            }
        }
        return result;
    }

    private FluidStack drainExternalInput(int portId, FluidStack requested, IFluidHandler.FluidAction action) {
        if (requested == null || requested.isEmpty()) {
            return FluidStack.EMPTY;
        }
        return drainHandlers(resolveExternalFluidHandlers(portId, requested,
                FluidNetworkEndpointResolver.Operation.DRAIN), requested, action);
    }

    private FluidStack drainExternalInput(int portId, int maxDrain, IFluidHandler.FluidAction action) {
        if (maxDrain <= 0) {
            return FluidStack.EMPTY;
        }
        return drainHandlers(resolveExternalFluidHandlers(portId, maxDrain), maxDrain, action);
    }

    private int fillExternalOutput(int portId, FluidStack resource, IFluidHandler.FluidAction action) {
        if (resource == null || resource.isEmpty()) {
            return 0;
        }
        return fillHandlers(resolveExternalFluidHandlers(portId, resource,
                FluidNetworkEndpointResolver.Operation.FILL), resource, action);
    }

    private int fillRoomInput(int portId, FluidStack resource, IFluidHandler.FluidAction action) {
        if (resource == null || resource.isEmpty()) {
            return 0;
        }
        return fillHandlers(resolveRoomFluidHandlers(portId, resource,
                FluidNetworkEndpointResolver.Operation.FILL), resource, action);
    }

    private boolean canReachRoomFluidDestination(int portId, FluidStack resource) {
        return !resolveRoomFluidHandlers(portId, resource,
                FluidNetworkEndpointResolver.Operation.FILL).isEmpty();
    }

    private boolean canReachExternalFluidDestination(int portId, FluidStack resource) {
        return !resolveExternalFluidHandlers(portId, resource,
                FluidNetworkEndpointResolver.Operation.FILL).isEmpty();
    }

    private FluidStack drainRoomOutput(int portId, FluidStack requested, IFluidHandler.FluidAction action) {
        return requested == null || requested.isEmpty()
                ? FluidStack.EMPTY
                : drainHandlers(resolveRoomFluidHandlers(portId, requested,
                FluidNetworkEndpointResolver.Operation.DRAIN), requested, action);
    }

    private FluidStack drainRoomOutput(int portId, int maxDrain, IFluidHandler.FluidAction action) {
        return maxDrain <= 0 ? FluidStack.EMPTY
                : drainHandlers(resolveRoomFluidHandlers(portId, maxDrain), maxDrain, action);
    }

    private int fillHandlers(List<IFluidHandler> handlers, FluidStack resource,
                              IFluidHandler.FluidAction action) {
        if (handlers.isEmpty() || resource == null || resource.isEmpty()) {
            return 0;
        }
        int requested = resource.getAmount();
        List<Integer> capacities = new ArrayList<>(handlers.size());
        long totalCapacity = 0L;
        for (IFluidHandler handler : handlers) {
            int capacity = Math.max(0, handler.fill(resource, IFluidHandler.FluidAction.SIMULATE));
            capacities.add(capacity);
            totalCapacity = Math.min((long) requested, totalCapacity + capacity);
        }
        if (totalCapacity <= 0L) {
            return 0;
        }
        int remaining = (int) Math.min(totalCapacity, requested);
        int moved = 0;
        int active = handlers.size();
        while (remaining > 0 && active > 0) {
            int share = Math.max(1, (remaining + active - 1) / active);
            boolean progress = false;
            for (int index = 0; index < handlers.size() && remaining > 0; index++) {
                int capacity = capacities.get(index);
                if (capacity <= 0) {
                    continue;
                }
                int offered = Math.min(share, Math.min(capacity, remaining));
                int accepted = action.execute()
                        ? handlers.get(index).fill(resource.copyWithAmount(offered), action)
                        : offered;
                accepted = Math.max(0, Math.min(accepted, offered));
                capacities.set(index, capacity - accepted);
                remaining -= accepted;
                moved += accepted;
                progress |= accepted > 0;
                if (capacities.get(index) == 0) {
                    active--;
                }
            }
            if (!progress) {
                break;
            }
        }
        return moved;
    }

    /**
     * Selects one fluid variant before executing any drain, then drains only that variant from
     * all handlers. This prevents a mixed-fluid call from consuming a later fluid without
     * returning it to the caller.
     */
    private FluidStack drainHandlers(List<IFluidHandler> handlers, FluidStack requested,
                                      IFluidHandler.FluidAction action) {
        FluidStack selected = FluidStack.EMPTY;
        for (IFluidHandler handler : handlers) {
            FluidStack candidate = handler.drain(requested, IFluidHandler.FluidAction.SIMULATE);
            if (!candidate.isEmpty()) {
                selected = candidate;
                break;
            }
        }
        if (selected.isEmpty()) {
            return FluidStack.EMPTY;
        }
        return drainHandlersForSelected(handlers, selected, requested.getAmount(), action);
    }

    private FluidStack drainHandlers(List<IFluidHandler> handlers, int maxDrain,
                                      IFluidHandler.FluidAction action) {
        FluidStack selected = FluidStack.EMPTY;
        for (IFluidHandler handler : handlers) {
            FluidStack candidate = handler.drain(maxDrain, IFluidHandler.FluidAction.SIMULATE);
            if (!candidate.isEmpty()) {
                selected = candidate;
                break;
            }
        }
        if (selected.isEmpty()) {
            return FluidStack.EMPTY;
        }
        return drainHandlersForSelected(handlers, selected, maxDrain, action);
    }

    private FluidStack drainHandlersForSelected(List<IFluidHandler> handlers, FluidStack selected,
                                                int requested, IFluidHandler.FluidAction action) {
        int remaining = requested;
        int moved = 0;
        FluidStack result = selected.copyWithAmount(0);
        for (IFluidHandler handler : handlers) {
            if (remaining <= 0) {
                break;
            }
            FluidStack drained = handler.drain(selected.copyWithAmount(remaining), action);
            if (drained.isEmpty() || !FluidStack.isSameFluidSameComponents(selected, drained)) {
                continue;
            }
            int amount = Math.min(drained.getAmount(), remaining);
            if (moved == 0) {
                result = drained.copyWithAmount(amount);
            } else {
                result.grow(amount);
            }
            moved += amount;
            remaining -= amount;
        }
        return moved <= 0 ? FluidStack.EMPTY : result;
    }

    private void notifyChannelBecameAvailable(boolean wasEmpty, boolean isEmptyNow) {
        if (wasEmpty && !isEmptyNow) {
            invalidateResourceCapabilities();
        }
    }

    /**
     * Called when a room port changes its mapped id. Existing simulated resources belong to the
     * old routing configuration and must be terminated before the new group becomes visible.
     */
    public void onPortRoutingChanged(Player player) {
        invalidateProductionBatch(player, "port_routing_changed");
        portChannels.clearFluids();
        roomFluidNetworkSignatures.clear();
        externalFluidNetworkSignatures.clear();
        setChanged();
    }

    /**
     * Returns the Create pipe pressure currently present at the external side mapped to a room port.
     */
    /** Returns the strongest active Create pressure across every external face in the port group. */
    public FluidPortPressure getExternalFluidPortPressure(int portId) {
        if (level == null || level.isClientSide()) {
            return FluidPortPressure.NONE;
        }
        float towardFactory = 0f;
        float awayFromFactory = 0f;
        for (Direction face : getFacesForPortId(portId)) {
            BlockPos adjacentPos = worldPosition.relative(face);
            BlockState adjacentState = level.getBlockState(adjacentPos);
            FluidTransportBehaviour transport = FluidPropagator.getPipe(level, adjacentPos);
            Direction pipeSideFacingFactory = face.getOpposite();
            if (transport == null || !transport.canHaveFlowToward(adjacentState, pipeSideFacingFactory)) {
                continue;
            }
            PipeConnection connection = transport.getConnection(pipeSideFacingFactory);
            if (connection == null) {
                continue;
            }
            var pressure = connection.getPressure();
            towardFactory = Math.max(towardFactory, Math.max(0f, pressure.getSecond()));
            awayFromFactory = Math.max(awayFromFactory, Math.max(0f, pressure.getFirst()));
        }
        return new FluidPortPressure(towardFactory, awayFromFactory);
    }

    public boolean hasExternalFluidPressure(int portId, boolean pull) {
        FluidPortPressure pressure = getExternalFluidPortPressure(portId);
        return pull ? pressure.towardFactory() > 0.001f : pressure.awayFromFactory() > 0.001f;
    }

    /** Applies room-side pump pressure to every external Create pipe face in a port group. */
    public boolean applyExternalFluidPressure(int portId, boolean pull, float pressure) {
        if (level == null || level.isClientSide()) {
            return false;
        }
        boolean applied = false;
        for (Direction face : getFacesForPortId(portId)) {
            applied |= FluidPressureBridge.apply(level, worldPosition, face, pull, pressure);
        }
        return applied;
    }

    public record FluidPortPressure(float towardFactory, float awayFromFactory) {
        public static final FluidPortPressure NONE = new FluidPortPressure(0f, 0f);
    }

    private boolean isSimulatedMode() {
        return operationMode == OperationMode.BLACKBOX_ACTIVE || operationMode == OperationMode.BLUEPRINT;
    }

    private void recordItemTransfer(boolean inputFlow, ItemStack stack, int moved) {
        if (inputFlow) {
            blackbox.recordItemInput(stack, moved);
        } else {
            blackbox.recordItemOutput(stack, moved);
        }
    }

    private void recordFluidTransfer(boolean inputFlow, FluidStack stack, int moved) {
        if (inputFlow) {
            blackbox.recordFluidInput(stack, moved);
        } else {
            blackbox.recordFluidOutput(stack, moved);
        }
    }

    private final class FactoryFaceItemHandler implements IItemHandler {
        private final int faceIndex;

        private FactoryFaceItemHandler(int faceIndex) {
            this.faceIndex = faceIndex;
        }

        private PortMode mode() {
            return faceModes[faceIndex];
        }

        private int portId() {
            return portIds[faceIndex];
        }

        @Override
        public int getSlots() {
            if (mode() == PortMode.NONE) {
                return 0;
            }
            if (isSimulatedMode()) {
                return mode() == PortMode.INPUT
                        ? productionBatch.sortedInputItems(blackbox).size()
                        : productionBatch.sortedOutputItems(blackbox).size();
            }
            if (mode() == PortMode.INPUT) {
                return canAcceptInput(portId(), false) ? 1 : 0;
            }
            return hasRoomPort(portId()) ? portChannel(portId()).outputItems().slots() : 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (mode() == PortMode.OUTPUT && isSimulatedMode()) {
                List<ItemVariant> items = productionBatch.sortedOutputItems(blackbox);
                if (slot >= 0 && slot < items.size()) {
                    ItemVariant item = items.get(slot);
                    long remaining = productionBatch.remainingItemOutput(item);
                    return remaining <= 0 ? ItemStack.EMPTY
                            : item.createStack((int) Math.min(remaining, item.prototype().getMaxStackSize()));
                }
                return ItemStack.EMPTY;
            }
            if (isSimulatedMode() || mode() != PortMode.OUTPUT || !hasRoomPort(portId())) {
                return ItemStack.EMPTY;
            }
            return portChannel(portId()).outputItems().stackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (mode() != PortMode.INPUT || operationMode == OperationMode.BLACKBOX_DRAINING) {
                return stack;
            }
            if (isSimulatedMode()) {
                List<ItemVariant> items = productionBatch.sortedInputItems(blackbox);
                if (slot < 0 || slot >= items.size() || !items.get(slot).matches(stack)) {
                    return stack;
                }
                int accepted = productionBatch.acceptItemInput(blackbox, stack, simulate);
                if (!simulate && accepted > 0) {
                    setChanged();
                }
                ItemStack remainder = stack.copy();
                remainder.shrink(accepted);
                return remainder;
            }
            if (!canAcceptInput(portId(), false)) {
                return stack;
            }
            FactoryPortChannels.PortResourceChannel resourceChannel = portChannel(portId());
            int offerLimit = resourceChannel.inputItemOfferLimit(currentGameTime(), stack);
            if (offerLimit <= 0) {
                return stack;
            }
            FactoryPortChannels.ItemChannel channel = resourceChannel.inputItems();
            boolean wasEmpty = channel.isEmpty();
            ItemStack offered = stack.copyWithCount(offerLimit);
            ItemStack offeredRemaining = channel.insert(offered, simulate);
            int moved = offerLimit - offeredRemaining.getCount();
            ItemStack remaining = stack.copy();
            remaining.shrink(moved);
            if (!simulate && moved > 0) {
                resourceChannel.consumeInputItems(moved);
                setChanged();
                notifyChannelBecameAvailable(wasEmpty, channel.isEmpty());
            }
            return remaining;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (mode() != PortMode.OUTPUT) {
                return ItemStack.EMPTY;
            }
            if (isSimulatedMode()) {
                List<ItemVariant> items = productionBatch.sortedOutputItems(blackbox);
                if (slot < 0 || slot >= items.size()) {
                    return ItemStack.EMPTY;
                }
                ItemStack result = productionBatch.extractItemOutput(items.get(slot), amount, simulate);
                if (!simulate && !result.isEmpty()) {
                    setChanged();
                }
                return result;
            }
            if (!hasRoomPort(portId())) {
                return ItemStack.EMPTY;
            }
            FactoryPortChannels.PortResourceChannel resourceChannel = portChannel(portId());
            ItemStack result = resourceChannel.outputItems().extract(slot, amount, simulate);
            if (!simulate && !result.isEmpty()) {
                resourceChannel.markOutputItemExtracted(result.getCount());
                setChanged();
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (mode() != PortMode.INPUT || operationMode == OperationMode.BLACKBOX_DRAINING) {
                return false;
            }
            if (!isSimulatedMode()) {
                return canAcceptInput(portId(), false);
            }
            List<ItemVariant> items = productionBatch.sortedInputItems(blackbox);
            return slot >= 0 && slot < items.size() && items.get(slot).matches(stack);
        }
    }

    private final class RoomItemBridgeHandler implements IItemHandler {
        private final int portId;

        private RoomItemBridgeHandler(int portId) {
            this.portId = portId;
        }

        private PortMode mode() {
            Direction face = getFaceForPortId(portId);
            return face == null ? PortMode.NONE : faceModes[face.get3DDataValue()];
        }

        @Override
        public int getSlots() {
            if (isSimulatedMode() || mode() == PortMode.NONE) {
                return 0;
            }
            return mode() == PortMode.INPUT
                    ? portChannel(portId).inputItems().slots()
                    // Keep one stable virtual slot so brass funnels, chutes and other Create
                    // pullers continue polling before the first output item arrives.
                    : hasRoomPort(portId) ? 1 : 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (isSimulatedMode() || !hasRoomPort(portId)) {
                return ItemStack.EMPTY;
            }
            return mode() == PortMode.INPUT
                    ? portChannel(portId).inputItems().stackInSlot(slot)
                    : portChannel(portId).outputItems().stackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (isSimulatedMode() || mode() != PortMode.OUTPUT || !canAcceptRoomOutput(portId, false)) {
                return stack;
            }
            FactoryPortChannels.PortResourceChannel resourceChannel = portChannel(portId);
            int offerLimit = resourceChannel.outputItemOfferLimit(currentGameTime(), stack);
            if (offerLimit <= 0) {
                return stack;
            }
            FactoryPortChannels.ItemChannel channel = resourceChannel.outputItems();
            boolean wasEmpty = channel.isEmpty();
            ItemStack offered = stack.copyWithCount(offerLimit);
            ItemStack offeredRemaining = channel.insert(offered, simulate);
            int moved = offerLimit - offeredRemaining.getCount();
            ItemStack remaining = stack.copy();
            remaining.shrink(moved);
            if (!simulate && moved > 0) {
                resourceChannel.consumeOutputItems(moved);
                recordItemTransfer(false, stack, moved);
                setChanged();
                notifyChannelBecameAvailable(wasEmpty, channel.isEmpty());
            }
            return remaining;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (isSimulatedMode() || (mode() != PortMode.INPUT && mode() != PortMode.OUTPUT)) {
                return ItemStack.EMPTY;
            }
            FactoryPortChannels.PortResourceChannel resourceChannel = portChannel(portId);
            if (mode() == PortMode.INPUT) {
                ItemStack result = resourceChannel.inputItems().extract(slot, amount, simulate);
                if (!simulate && !result.isEmpty()) {
                    resourceChannel.markInputItemExtracted(result.getCount());
                    recordItemTransfer(true, result, result.getCount());
                    setChanged();
                }
                return result;
            }
            // Items inserted by room machines are already recorded as OUTPUT boundary handoff;
            // extracting them for a packager must only release the output credit.
            ItemStack result = resourceChannel.outputItems().extract(slot, amount, simulate);
            if (!simulate && !result.isEmpty()) {
                resourceChannel.markOutputItemExtracted(result.getCount());
                setChanged();
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return !isSimulatedMode() && mode() == PortMode.OUTPUT
                    && canAcceptRoomOutput(portId, false);
        }
    }

    private final class FactoryFaceFluidHandler implements IFluidHandler {
        private final int faceIndex;

        private FactoryFaceFluidHandler(int faceIndex) {
            this.faceIndex = faceIndex;
        }

        private PortMode mode() {
            return faceModes[faceIndex];
        }

        private int portId() {
            return portIds[faceIndex];
        }

        @Override
        public int getTanks() {
            if (mode() == PortMode.NONE) {
                return 0;
            }
            if (isSimulatedMode()) {
                return mode() == PortMode.INPUT
                        ? productionBatch.sortedInputFluids(blackbox).size()
                        : productionBatch.sortedOutputFluids(blackbox).size();
            }
            return hasRoomPort(portId()) ? (mode() == PortMode.INPUT
                    ? 1 : Math.max(1, portChannel(portId()).outputFluids().tanks())) : 0;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            if (isSimulatedMode()) {
                List<Fluid> fluids = mode() == PortMode.INPUT
                        ? productionBatch.sortedInputFluids(blackbox)
                        : productionBatch.sortedOutputFluids(blackbox);
                if (tank < 0 || tank >= fluids.size()) {
                    return FluidStack.EMPTY;
                }
                Fluid fluid = fluids.get(tank);
                long amount = mode() == PortMode.INPUT
                        ? productionBatch.committedFluid(fluid)
                        : productionBatch.remainingFluidOutput(fluid);
                return amount <= 0 ? FluidStack.EMPTY
                        : new FluidStack(fluid, (int) Math.min(amount, Integer.MAX_VALUE));
            }
            return mode() == PortMode.OUTPUT && hasRoomPort(portId()) && tank >= 0
                    ? portChannel(portId()).outputFluids().fluidInTank(tank) : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            if (isSimulatedMode()) {
                List<Fluid> fluids = mode() == PortMode.INPUT
                        ? productionBatch.sortedInputFluids(blackbox)
                        : productionBatch.sortedOutputFluids(blackbox);
                if (tank < 0 || tank >= fluids.size()) {
                    return 0;
                }
                Fluid fluid = fluids.get(tank);
                long capacity = mode() == PortMode.INPUT
                        ? blackbox.getRecipeInputFluids().getOrDefault(fluid, 0L)
                        : blackbox.getRecipeOutputFluids().getOrDefault(fluid, 0L);
                return (int) Math.min(capacity, Integer.MAX_VALUE);
            }
            return hasRoomPort(portId()) && tank == 0 ? Integer.MAX_VALUE : 0;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            if (mode() != PortMode.INPUT || operationMode == OperationMode.BLACKBOX_DRAINING) {
                return false;
            }
            if (!isSimulatedMode()) {
                return hasRoomPort(portId()) && stack != null && !stack.isEmpty();
            }
            List<Fluid> fluids = productionBatch.sortedInputFluids(blackbox);
            return tank >= 0 && tank < fluids.size() && stack.is(fluids.get(tank));
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (mode() != PortMode.INPUT || operationMode == OperationMode.BLACKBOX_DRAINING
                    || resource == null || resource.isEmpty()) {
                return 0;
            }
            if (isSimulatedMode()) {
                int accepted = productionBatch.acceptFluidInput(blackbox, resource, action.simulate());
                if (action.execute() && accepted > 0) {
                    setChanged();
                }
                return accepted;
            }
            if (!canAcceptInput(portId(), true)) {
                return 0;
            }
            int activeFaces = connectedExternalFluidFaceCount(portId());
            if (activeFaces == 0) {
                return 0;
            }
            int offeredAmount = activeFaces <= 1
                    ? resource.getAmount()
                    : (resource.getAmount() + activeFaces - 1) / activeFaces;
            FluidStack offered = resource.copyWithAmount(Math.max(1, offeredAmount));
            int direct = fillRoomInput(portId(), offered, action);
            int remaining = offered.getAmount() - direct;
            if (remaining > 0 && !canReachRoomFluidDestination(portId(), offered.copyWithAmount(remaining))) {
                return direct;
            }
            int buffered = remaining <= 0 ? 0
                    : portChannel(portId()).fillInputFluids(offered.copyWithAmount(remaining), action);
            int moved = direct + buffered;
            if (action.execute() && moved > 0) {
                recordFluidTransfer(true, offered, moved);
                setChanged();
            }
            return moved;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            if (mode() != PortMode.OUTPUT || resource == null || resource.isEmpty()) {
                return FluidStack.EMPTY;
            }
            if (isSimulatedMode()) {
                FluidStack result = productionBatch.drainFluidOutput(resource.getFluid(), resource.getAmount(), action.simulate());
                if (action.execute() && !result.isEmpty()) {
                    setChanged();
                }
                return result;
            }
            if (!hasRoomPort(portId())) {
                return FluidStack.EMPTY;
            }
            FluidStack result = portChannel(portId()).drainOutputFluids(resource, action);
            int remaining = resource.getAmount() - result.getAmount();
            FluidStack room = FluidStack.EMPTY;
            if (remaining > 0) {
                room = result.isEmpty()
                        ? drainRoomOutput(portId(), remaining, action)
                        : drainRoomOutput(portId(), result.copyWithAmount(remaining), action);
                result = mergeFluidResults(result, room);
            }
            if (action.execute() && !room.isEmpty()) {
                recordFluidTransfer(false, room, room.getAmount());
            }
            if (action.execute() && !result.isEmpty()) {
                setChanged();
            }
            return result;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            if (mode() != PortMode.OUTPUT || maxDrain <= 0) {
                return FluidStack.EMPTY;
            }
            if (isSimulatedMode()) {
                for (Fluid fluid : productionBatch.sortedOutputFluids(blackbox)) {
                    FluidStack result = productionBatch.drainFluidOutput(fluid, maxDrain, action.simulate());
                    if (!result.isEmpty()) {
                        if (action.execute()) {
                            setChanged();
                        }
                        return result;
                    }
                }
                return FluidStack.EMPTY;
            }
            if (!hasRoomPort(portId())) {
                return FluidStack.EMPTY;
            }
            FluidStack result = portChannel(portId()).drainOutputFluids(maxDrain, action);
            int remaining = maxDrain - result.getAmount();
            FluidStack room = FluidStack.EMPTY;
            if (remaining > 0) {
                room = result.isEmpty()
                        ? drainRoomOutput(portId(), remaining, action)
                        : drainRoomOutput(portId(), result.copyWithAmount(remaining), action);
                result = mergeFluidResults(result, room);
            }
            if (action.execute() && !room.isEmpty()) {
                recordFluidTransfer(false, room, room.getAmount());
            }
            if (action.execute() && !result.isEmpty()) {
                setChanged();
            }
            return result;
        }
    }

    private FluidStack mergeFluidResults(FluidStack first, FluidStack second) {
        if (first == null || first.isEmpty()) {
            return second == null ? FluidStack.EMPTY : second;
        }
        if (second == null || second.isEmpty()) {
            return first;
        }
        if (!FluidStack.isSameFluidSameComponents(first, second)) {
            return first;
        }
        FluidStack result = first.copy();
        result.grow(second.getAmount());
        return result;
    }

    private final class RoomFluidBridgeHandler implements IFluidHandler {
        private final int portId;
        private RoomFluidBridgeHandler(int portId) {
            this.portId = portId;
        }

        private PortMode mode() {
            List<Direction> faces = getFacesForPortId(portId);
            return faces.isEmpty() ? PortMode.NONE : faceModes[faces.get(0).get3DDataValue()];
        }

        @Override
        public int getTanks() {
            if (isSimulatedMode() || mode() == PortMode.NONE || !hasRoomPort(portId)) {
                return 0;
            }
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return !isSimulatedMode() && mode() == PortMode.INPUT
                    ? portChannel(portId).inputFluids().fluidInTank(tank) : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            if (isSimulatedMode() || !hasRoomPort(portId)) {
                return 0;
            }
            return tank >= 0 && (mode() == PortMode.INPUT || mode() == PortMode.OUTPUT)
                    ? Integer.MAX_VALUE : 0;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return !isSimulatedMode() && mode() == PortMode.OUTPUT
                    && stack != null && !stack.isEmpty();
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (isSimulatedMode() || mode() != PortMode.OUTPUT || resource == null || resource.isEmpty()
                    || !canAcceptRoomOutput(portId, true)) {
                return 0;
            }
            int direct = fillExternalOutput(portId, resource, action);
            int remaining = resource.getAmount() - direct;
            if (remaining > 0 && !canReachExternalFluidDestination(portId, resource.copyWithAmount(remaining))) {
                return direct;
            }
            int buffered = remaining <= 0 ? 0
                    : portChannel(portId).fillOutputFluids(resource.copyWithAmount(remaining), action);
            int moved = direct + buffered;
            if (action.execute() && moved > 0) {
                recordFluidTransfer(false, resource, moved);
                setChanged();
            }
            return moved;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            if (isSimulatedMode() || mode() != PortMode.INPUT || resource == null || resource.isEmpty()) {
                return FluidStack.EMPTY;
            }
            FluidStack result = portChannel(portId).drainInputFluid(resource, action);
            int remaining = resource.getAmount() - result.getAmount();
            FluidStack external = FluidStack.EMPTY;
            if (remaining > 0) {
                external = result.isEmpty()
                        ? drainExternalInput(portId, remaining, action)
                        : drainExternalInput(portId, result.copyWithAmount(remaining), action);
                result = mergeFluidResults(result, external);
            }
            if (action.execute() && !external.isEmpty()) {
                recordFluidTransfer(true, external, external.getAmount());
                setChanged();
            }
            return result;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            if (isSimulatedMode() || mode() != PortMode.INPUT || maxDrain <= 0) {
                return FluidStack.EMPTY;
            }
            FluidStack result = portChannel(portId).drainInputFluid(maxDrain, action);
            int remaining = maxDrain - result.getAmount();
            FluidStack external = FluidStack.EMPTY;
            if (remaining > 0) {
                external = result.isEmpty()
                        ? drainExternalInput(portId, remaining, action)
                        : drainExternalInput(portId, result.copyWithAmount(remaining), action);
                result = mergeFluidResults(result, external);
            }
            if (action.execute() && !external.isEmpty()) {
                recordFluidTransfer(true, external, external.getAmount());
                setChanged();
            }
            return result;
        }
    }

    private ServerLevel pocketLevel() {
        if (level == null || level.isClientSide()) {
            return null;
        }
        MinecraftServer server = level.getServer();
        return server == null ? null : server.getLevel(NestedFactoryBlock.POCKET_DIMENSION);
    }

    private void initializeFactoryState() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            initializeNestedState();
        } else {
            initializeRootState();
        }
        factoryStateInitialized = true;
        setChanged();
    }

    private void initializeRootState() {
        if (factoryId == null || factoryId.isEmpty()) {
            factoryId = UUID.randomUUID().toString();
        }
        nested = false;
        enterable = true;
        invalidNested = false;
        nestingDepth = 0;
        parentFactoryId = "";
        parentFactoryPos = null;
        parentDimension = null;
        rootFactoryId = factoryId;
        nestedSlotId = -1;
        nestedSlotX = 0;
        nestedSlotZ = 0;
        nestedRoomOrigin = BlockPos.ZERO;
    }

    /**
     * Claims a unique persistent room origin for this root factory. Existing owner reservations
     * and serialized allocations win; only old, pre-slot saves may claim the legacy X/Z-derived
     * location during migration.
     */
    private void ensureRootRoomAllocation() {
        if (level == null || level.isClientSide() || nested) {
            return;
        }
        if (factoryId == null || factoryId.isBlank()) {
            factoryId = UUID.randomUUID().toString();
        }

        NestedFactorySaveData.RootFactoryKey owner = new NestedFactorySaveData.RootFactoryKey(
                factoryId, level.dimension(), worldPosition);
        BlockPos persistedOrigin = rootRoomAllocated ? rootRoomOrigin : null;
        int persistedSlotId = rootRoomAllocated ? rootSlotId : -1;
        BlockPos legacyOrigin = loadedFromDisk && !rootRoomAllocated
                ? NestedFactoryBlock.getLegacyPocketOrigin(worldPosition)
                : null;

        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        NestedFactorySaveData.RootAllocation allocation = NestedFactorySaveData.get(server)
                .claimRootAllocation(owner, persistedSlotId, persistedOrigin, legacyOrigin);
        if (!rootRoomAllocated || rootSlotId != allocation.slotId()
                || !rootRoomOrigin.equals(allocation.roomOrigin())) {
            rootSlotId = allocation.slotId();
            rootRoomOrigin = allocation.roomOrigin();
            rootRoomAllocated = true;
            setChanged();
        }
    }

    private void initializeNestedState() {
        if (factoryId == null || factoryId.isEmpty()) {
            factoryId = UUID.randomUUID().toString();
        }
        nested = true;
        NestedFactoryBlockEntity parent = NestedFactoryBlock.findFactoryAt((ServerLevel) level, worldPosition);
        if (parent == null || parent == this) {
            enterable = false;
            invalidNested = true;
            nestingDepth = 0;
            parentFactoryId = "";
            parentFactoryPos = null;
            rootFactoryId = factoryId;
            return;
        }

        parentFactoryId = parent.getFactoryId();
        parentFactoryPos = parent.getBlockPos().immutable();
        parentDimension = parent.level.dimension();
        nestingDepth = parent.getNestingDepth() + 1;
        rootFactoryId = parent.isRoot() ? parent.getFactoryId() : parent.getRootFactoryId();

        boolean buildable = parent.getBounds().isBuildableAt(parent.roomOrigin(), worldPosition);
        boolean depthAllowed = nestingDepth <= Config.maxNestingDepth;
        boolean noSibling = !parent.hasRecordedChild();
        if (!buildable || !depthAllowed || !noSibling) {
            enterable = false;
            invalidNested = true;
            nestedSlotId = -1;
            nestedRoomOrigin = BlockPos.ZERO;
            return;
        }

        PocketRegistry.NestedSlot slot = PocketRegistry.allocateAndRegisterNestedSlot(
                new PocketRegistry.FactoryLocation(factoryId, level.dimension(), worldPosition), (ServerLevel) level);
        nestedSlotId = slot.id();
        nestedSlotX = slot.slotX();
        nestedSlotZ = slot.slotZ();
        nestedRoomOrigin = NestedFactoryBlock.getNestedRoomOrigin(nestedSlotX, nestedSlotZ);
        enterable = true;
        invalidNested = false;
        parent.setChildFactory(this);
    }

    private boolean registerFactoryState() {
        if (level == null || level.isClientSide()) {
            return false;
        }
        PocketRegistry.FactoryLocation location = new PocketRegistry.FactoryLocation(factoryId, level.dimension(), worldPosition);
        if (nested) {
            if (enterable && !invalidNested && nestedSlotId >= 0) {
                PocketRegistry.registerNestedSlot(nestedSlotId, location, (ServerLevel) level);
            }
            return true;
        }
        return rootRoomAllocated && PocketRegistry.registerRoot(roomOrigin(), location);
    }

    private void unregisterFactoryState() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (nested) {
            if (nestedSlotId >= 0) {
                PocketRegistry.unregisterNestedSlot(nestedSlotId);
            }
            clearChildFromParent();
        } else if (rootRoomAllocated) {
            PocketRegistry.unregisterRoot(roomOrigin(),
                    new PocketRegistry.FactoryLocation(factoryId, level.dimension(), worldPosition));
        }
    }

    private void clearChildFromParent() {
        if (parentFactoryPos == null || parentFactoryId.isEmpty() || level == null || level.isClientSide()) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        ServerLevel parentLevel = parentDimension == null ? null : server.getLevel(parentDimension);
        if (parentLevel == null) {
            return;
        }
        if (parentLevel.getBlockEntity(parentFactoryPos) instanceof NestedFactoryBlockEntity parent
                && factoryId.equals(parent.childFactoryId)) {
            parent.setChildFactory(null);
        }
    }

    public boolean requestRoomBuild() {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return false;
        }
        BlockPos origin = roomOrigin();
        if (!pocket.getBlockState(origin).isAir()) {
            return true;
        }
        if (isRoomMutationLocked()) {
            return false;
        }
        MinecraftServer server = pocket.getServer();
        if (server == null) {
            return false;
        }
        RoomMutationTaskManager.get(server).scheduleBuild(
                NestedFactoryBlock.POCKET_DIMENSION, origin, roomBounds(origin), roomTaskReference());
        return false;
    }

    private void ensureRoomGenerated() {
        requestRoomBuild();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            if (!factoryStateInitialized) {
                initializeFactoryState();
            }
            if (!nested) {
                ensureRootRoomAllocation();
            }
            blackbox.setRecording(false);
            if (!invalidNested && registerFactoryState()) {
                ensureRoomGenerated();
                rebuildRuntimeIndex(pocketLevel(), true);
                refreshChunkRefsForMode();
            } else if (blueprintApplied) {
                setChanged();
                sendSync();
            }
        }
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (invalidNested && !blueprintApplied) {
            clearExternalStressState();
            clearStressRelay();
            return;
        }
        if (operationMode == OperationMode.BLACKBOX_ACTIVE || operationMode == OperationMode.BLUEPRINT) {
            settleSimulatedStress();
        } else {
            settleLiveStressRelay();
        }
        if (usesRuntimeIndex()) {
            ensureRuntimeIndex(pocketLevel());
        }
        if (level.getGameTime() % 5 == 0 && !isSimulatedMode()) {
            for (int portId = 1; portId <= FactoryFacePortBindings.MAX_PORT_ID; portId++) {
                refreshExternalFluidNetworksIfSignatureChanged(portId);
            }
        }
        switch (operationMode) {
            case CHUNK_LOADED -> tickChunkLoaded();
            case BLACKBOX_DRAINING -> tickDraining();
            case BLACKBOX_LEARNING -> tickLearning();
            case BLACKBOX_ACTIVE -> tickBlackbox();
            case BLUEPRINT -> tickBlueprint();
        }
        tickChunkRefs();
        if (level.getGameTime() % 20 == 0) {
            sendData();
        }
    }

    private void clearStressRelay() {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        for (NestedStressPortBlockEntity stressPort : roomStressPorts(pocket)) {
            stressPort.clearStressAllocation();
        }
    }

    @Override
    public float getGeneratedSpeed() {
        return 0f;
    }

    @Override
    public float calculateAddedStressCapacity() {
        return 0f;
    }

    @Override
    public float calculateStressApplied() {
        return reservedStressImpact;
    }

    /**
     * Queues Pocket cleanup before a normal player break. The actual block break is deliberately
     * not cancelled: accepting the client-predicted removal avoids a remove -> restore -> remove
     * visual bounce while the persistent task clears the detached Pocket room.
     */
    public boolean prepareForPlayerBreak(Player player) {
        if (isRoomMutationLocked()) {
            return false;
        }
        invalidateProductionBatch(player, "player_break");
        if (!(nested ? isValidNestedFactory() : rootRoomAllocated)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return false;
        }
        evacuateFactorySpace();
        return RoomMutationTaskManager.get(server).scheduleDestroy(
                NestedFactoryBlock.POCKET_DIMENSION, roomOrigin(), roomBounds(roomOrigin()), roomTaskReference(), false);
    }

    /** Invoked by the block only when this factory block is actually replaced or destroyed. */
    public void onBlockDestroyed() {
        if (level == null || level.isClientSide() || isTaskManagerRemovingThisFactory() || isRoomMutationLocked()) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        invalidateProductionBatch(null, "factory_destroyed");
        if (nested ? isValidNestedFactory() : rootRoomAllocated) {
            evacuateFactorySpace();
            RoomMutationTaskManager.get(server).scheduleDestroy(
                    NestedFactoryBlock.POCKET_DIMENSION, roomOrigin(), roomBounds(roomOrigin()), roomTaskReference(), false);
        }
    }

    @Override
    public void remove() {
        if (level != null && !level.isClientSide()) {
            MinecraftServer server = level.getServer();
            if (server != null) {
                clearExternalStressState();
                clearStressRelay();
                PocketChunkForceManager.releaseAll(server, externalChunkForceOwner());
                PocketChunkForceManager.releaseAll(server, roomChunkForceOwner());
                chunkRefCounts.clear();
                pocketChunksForced = false;
                unregisterFactoryState();
            }
        }
        super.remove();
    }

    private boolean isValidNestedFactory() {
        return nested && enterable && !invalidNested && nestedSlotId >= 0;
    }

    /** Returns every player in this root factory's nested tree before the root room is cleared. */
    private void returnPlayersFromRootFactoryTree() {
        if (level == null || level.isClientSide()) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        int maxExits = Math.max(2, Config.maxNestingDepth + 2);
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
            if (!session.isActive() || !factoryId.equals(session.rootFactoryId())) {
                continue;
            }
            for (int exits = 0; exits < maxExits && session.isActive(); exits++) {
                NestedFactoryBlock.exitCurrentFactory(player);
                session = player.getData(ModAttachments.FACTORY_SESSION);
            }
            if (session.isActive()) {
                // A malformed return stack must not leave a player tied to a room being destroyed.
                NestedFactoryBlock.endSessionForPlayer(player);
            }
        }
    }

    /**
     * Removes the complete Pocket room of a destroyed factory without dropping its contents.
     * Players are returned to their previous factory first for non-player removal paths such as commands.
     * Root allocations remain reserved in SavedData, but their old room contents cannot leak to a new factory.
     */
    private void evacuateFactorySpace() {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        if (!nested) {
            returnPlayersFromRootFactoryTree();
        }
        BlockPos origin = roomOrigin();
        AABB room = new AABB(
                bounds.minX(origin), bounds.minY(origin), bounds.minZ(origin),
                bounds.maxX(origin) + 1.0, bounds.maxY(origin) + 1.0, bounds.maxZ(origin) + 1.0);
        for (ServerPlayer player : List.copyOf(pocket.getEntitiesOfClass(ServerPlayer.class, room, p -> true))) {
            NestedFactoryBlock.exitCurrentFactory(player);
        }
        for (Entity entity : List.copyOf(pocket.getEntitiesOfClass(Entity.class, room, entity -> !(entity instanceof ServerPlayer)))) {
            entity.discard();
        }
        PocketRegistry.clearRoomRegistrations(origin);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        for (int i = 0; i < 6; i++) {
            tag.putString("FaceMode" + i, faceModes[i].getSerializedName());
            tag.putInt("PortId" + i, portIds[i]);
        }
        tag.putIntArray("Bounds", bounds.toArray());
        tag.putString("OperationMode", operationMode.getSerializedName());
        tag.putInt("DrainLastCount", drainLastCount);
        tag.putInt("DrainStaticCount", drainStaticCount);
        tag.putInt("LearningTicksRemaining", learningTicksRemaining);
        tag.put("PowerProfile", powerProfile.write());
        blackbox.write(tag, registries);
        tag.putInt("EnergyStored", energyStored);
        tag.put("ProductionBatch", productionBatch.write(new CompoundTag(), registries));
        tag.put("PortChannels", portChannels.write(new CompoundTag(), registries));
        tag.putString("FactoryId", factoryId);
        tag.putBoolean("RootRoomAllocated", rootRoomAllocated);
        if (rootRoomAllocated) {
            tag.putInt("RootSlotId", rootSlotId);
            tag.putLong("RootRoomOrigin", rootRoomOrigin.asLong());
        }
        tag.putBoolean("Nested", nested);
        tag.putBoolean("Enterable", enterable);
        tag.putBoolean("InvalidNested", invalidNested);
        tag.putInt("NestingDepth", nestingDepth);
        tag.putString("ParentFactoryId", parentFactoryId);
        tag.putString("RootFactoryId", rootFactoryId);
        tag.putInt("NestedSlotId", nestedSlotId);
        tag.putInt("NestedSlotX", nestedSlotX);
        tag.putInt("NestedSlotZ", nestedSlotZ);
        tag.putLong("NestedRoomOrigin", nestedRoomOrigin.asLong());
        tag.putString("ChildFactoryId", childFactoryId);
        if (parentFactoryPos != null) {
            tag.putLong("ParentFactoryPos", parentFactoryPos.asLong());
        }
        if (parentDimension != null) {
            tag.putString("ParentDimension", parentDimension.location().toString());
        }
        if (childFactoryPos != null) {
            tag.putLong("ChildFactoryPos", childFactoryPos.asLong());
        }
        tag.putInt("BoundsVersion", boundsVersion);
        if (customName != null) {
            tag.putString("CustomName", customName);
        }
        tag.putBoolean("BlueprintApplied", blueprintApplied);
        if (appliedBlueprint != null) {
            tag.put("AppliedBlueprint", appliedBlueprint.write(new CompoundTag(), registries));
        }
        if (preBlueprintSnapshot != null) {
            tag.put("PreBlueprintSnapshot", preBlueprintSnapshot.write(new CompoundTag()));
        }
        super.write(tag, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        loadedFromDisk = true;
        for (int i = 0; i < 6; i++) {
            String mode = tag.getString("FaceMode" + i);
            faceModes[i] = readPortMode(mode);
            portIds[i] = tag.getInt("PortId" + i);
        }
        boolean repairedFaceBindings = normalizeFacePortBindings();
        if (repairedFaceBindings && !clientPacket) {
            setChanged();
        }
        bounds.fromArray(tag.getIntArray("Bounds"));
        String modeName = tag.getString("OperationMode");
        operationMode = readOperationMode(modeName, clientPacket);
        drainLastCount = tag.getInt("DrainLastCount");
        drainStaticCount = tag.getInt("DrainStaticCount");
        learningTicksRemaining = tag.getInt("LearningTicksRemaining");
        powerProfile.read(tag.getCompound("PowerProfile"));
        blackbox.read(tag, registries);
        energyStored = tag.getInt("EnergyStored");
        if (tag.contains("ProductionBatch")) {
            productionBatch.read(tag.getCompound("ProductionBatch"), registries);
        } else {
            productionBatch.clear();
        }
        if (tag.contains("PortChannels")) {
            portChannels.read(tag.getCompound("PortChannels"), registries);
        }
        if (tag.contains("FactoryId")) {
            factoryId = tag.getString("FactoryId");
            factoryStateInitialized = true;
        }
        rootRoomAllocated = tag.getBoolean("RootRoomAllocated") && tag.contains("RootRoomOrigin");
        rootSlotId = rootRoomAllocated && tag.contains("RootSlotId") ? tag.getInt("RootSlotId") : -1;
        rootRoomOrigin = rootRoomAllocated ? BlockPos.of(tag.getLong("RootRoomOrigin")) : BlockPos.ZERO;
        nested = tag.getBoolean("Nested");
        enterable = tag.getBoolean("Enterable");
        invalidNested = tag.getBoolean("InvalidNested");
        nestingDepth = tag.getInt("NestingDepth");
        parentFactoryId = tag.getString("ParentFactoryId");
        rootFactoryId = tag.getString("RootFactoryId");
        nestedSlotId = tag.getInt("NestedSlotId");
        nestedSlotX = tag.getInt("NestedSlotX");
        nestedSlotZ = tag.getInt("NestedSlotZ");
        nestedRoomOrigin = tag.contains("NestedRoomOrigin") ? BlockPos.of(tag.getLong("NestedRoomOrigin")) : BlockPos.ZERO;
        childFactoryId = tag.getString("ChildFactoryId");
        parentFactoryPos = tag.contains("ParentFactoryPos") ? BlockPos.of(tag.getLong("ParentFactoryPos")) : null;
        parentDimension = tag.contains("ParentDimension")
                ? ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(tag.getString("ParentDimension")))
                : null;
        childFactoryPos = tag.contains("ChildFactoryPos") ? BlockPos.of(tag.getLong("ChildFactoryPos")) : null;
        boundsVersion = tag.getInt("BoundsVersion");
        customName = tag.contains("CustomName") ? tag.getString("CustomName") : null;
        blueprintApplied = tag.getBoolean("BlueprintApplied");
        appliedBlueprint = tag.contains("AppliedBlueprint")
                ? NestedFactoryBlueprint.fromTag(tag.getCompound("AppliedBlueprint"), registries)
                : null;
        preBlueprintSnapshot = tag.contains("PreBlueprintSnapshot")
                ? readRestoreSnapshot(tag.getCompound("PreBlueprintSnapshot"))
                : null;
        // H3 intentionally has no legacy bare-Item compatibility. A v1 blackbox or blueprint
        // loads as empty data and is forced out of simulated execution instead of guessing
        // default components for historical items.
        if (!clientPacket && (operationMode == OperationMode.BLACKBOX_ACTIVE || operationMode == OperationMode.BLUEPRINT)
                && !blackbox.hasCompleteRecipe()) {
            operationMode = OperationMode.CHUNK_LOADED;
            blueprintApplied = false;
            appliedBlueprint = null;
            preBlueprintSnapshot = null;
        }
        if (blueprintApplied && appliedBlueprint == null) {
            blueprintApplied = false;
            if (!clientPacket && operationMode == OperationMode.BLUEPRINT) {
                operationMode = OperationMode.CHUNK_LOADED;
            }
            preBlueprintSnapshot = null;
        }
        super.read(tag, registries, clientPacket);
    }

    private static FactoryRestoreSnapshot readRestoreSnapshot(CompoundTag tag) {
        FactoryRestoreSnapshot snapshot = new FactoryRestoreSnapshot();
        snapshot.read(tag);
        return snapshot;
    }

    private static PortMode readPortMode(String name) {
        if (name == null || name.isEmpty()) {
            return PortMode.NONE;
        }
        try {
            return PortMode.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PortMode.NONE;
        }
    }

    private static OperationMode readOperationMode(String name, boolean clientPacket) {
        if (name == null || name.isEmpty()) {
            return OperationMode.CHUNK_LOADED;
        }
        if (name.equalsIgnoreCase("blackbox")) {
            return OperationMode.BLACKBOX_ACTIVE;
        }
        try {
            OperationMode mode = OperationMode.valueOf(name.toUpperCase(Locale.ROOT));
            // 鎺掔┖/瀛︿範鏄复鏃剁湡瀹炶繍琛屾€侊紝鏈嶅姟绔噸鍚笉鎭㈠锛涗絾瀹㈡埛绔悓姝ヨ淇濈暀鐪熷疄鐘舵€佺敤浜庢樉绀恒€?
            if (!clientPacket && (mode == OperationMode.BLACKBOX_DRAINING || mode == OperationMode.BLACKBOX_LEARNING)) {
                return OperationMode.CHUNK_LOADED;
            }
            return mode;
        } catch (IllegalArgumentException e) {
            return OperationMode.CHUNK_LOADED;
        }
    }
}

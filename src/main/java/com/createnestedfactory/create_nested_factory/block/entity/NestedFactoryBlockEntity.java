package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.NestedFactorySaveData;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.RoomMutationTaskManager;
import com.createnestedfactory.create_nested_factory.Config;
import com.createnestedfactory.create_nested_factory.blueprint.FactoryRestoreSnapshot;
import com.createnestedfactory.create_nested_factory.blueprint.NestedFactoryBlueprint;
import com.createnestedfactory.create_nested_factory.block.FactoryFacePortBindings;
import com.createnestedfactory.create_nested_factory.block.FactoryPowerProfile;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.OverclockTier;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.menu.FactoryMenu;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.createnestedfactory.create_nested_factory.registry.ModItems;
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
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
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
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;

public class NestedFactoryBlockEntity extends GeneratingKineticBlockEntity implements MenuProvider {
    private static final int SIMULATED_IO_FORMAT_VERSION = 2;
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String PORTABLE_BINDING_KEY = "PortableBinding";
    private static final float STRESS_EPSILON = 0.001f;

    private record ExternalStressCandidate(Direction face, long sourceOrder, KineticBlockEntity anchor,
                                           KineticNetwork network, float speed, float availableSU) {}

    /** A five-sided extension contributes one access point per exposed face. */
    public record ExternalAccessPoint(BlockPos origin, Direction face) {}

    private record RuntimeToolSlot(FactoryRuntime runtime, FactoryPlan route, ItemVariant signature) {}

    private final PortMode[] faceModes = new PortMode[6];
    private final int[] portIds = new int[6];

    private final PocketBounds bounds = new PocketBounds();

    private OperationMode operationMode = OperationMode.CHUNK_LOADED;
    private final FactoryPowerProfile powerProfile = new FactoryPowerProfile();
    private FactoryPlan plan = new FactoryPlan();
    private final FactoryLearningController learning = new FactoryLearningController();
    private final SimpleContainer overclockInventory = new SimpleContainer(4) {
        @Override
        public void setChanged() {
            super.setChanged();
            onOverclockInventoryChanged();
        }
    };
    private OverclockTier selectedOverclockTier = OverclockTier.NORMAL;
    private OverclockTier activeOverclockTier = OverclockTier.NORMAL;
    private boolean loadingOverclockInventory;
    private boolean overclockBatteriesDropped;
    private FactoryPlan cachedOverclockRecipe;
    private String cachedOverclockRecipeFingerprint = "";
    private OverclockTier cachedOverclockRecipeTier = OverclockTier.NORMAL;
    private boolean blueprintApplied = false;
    private NestedFactoryBlueprint appliedBlueprint = null;
    private FactoryRestoreSnapshot preBlueprintSnapshot = null;
    private String customName = null;
    private final IItemHandler[] faceItemHandlers = new IItemHandler[6];
    private final IFluidHandler[] faceFluidHandlers = new IFluidHandler[6];
    private final IItemHandler[] directFaceItemHandlers = new IItemHandler[6];
    private final IFluidHandler[] directFaceFluidHandlers = new IFluidHandler[6];

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
    private final FactoryRuntimeScheduler runtimeScheduler = new FactoryRuntimeScheduler();
    /** Runtime-only measurements for the GUI; never grants or persists resource ownership. */
    private final FactoryTransferTelemetry transferTelemetry = new FactoryTransferTelemetry();
    /** Real-time, no-fixed-capacity resource channels shared by all room ports with the same id. */
    private final FactoryTransit factoryTransit = new FactoryTransit();
    /** Runtime-only external consumers that queried an OUTPUT face. */
    private final Block[] externalItemOutputConsumers = new Block[6];
    private final Block[] externalFluidOutputConsumers = new Block[6];
    /** Runtime-only positions of mechanical and inventory participants in this Pocket room. */
    private final RoomParticipantIndex runtimeIndex = new RoomParticipantIndex();
    private final FactoryChunkLeaseController chunkLeases = new FactoryChunkLeaseController(this);
    private boolean simulatedMoveInProgress;
    /** Persisted on descendants so an incidentally loaded child cannot restart while its parent is virtual. */
    private boolean ancestorFrozen;

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
    private boolean nested = false;
    private boolean enterable = true;
    private boolean invalidNested = false;
    private boolean terminalBlueprintOnly = false;
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
    private boolean portableBinding = false;
    private boolean bindingConflict = false;
    private boolean factoryStateInitialized = false;
    private int boundsVersion = 0;

    private transient Boolean debugLastStressSatisfied;
    private transient String debugLastRuntimeWait = "";
    private transient boolean debugInterruptedLearningOnLoad;
    private transient String debugLoadRepair = "";

    public NestedFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NESTED_FACTORY.get(), pos, state);
        for (int i = 0; i < 6; i++) {
            faceModes[i] = PortMode.NONE;
            faceItemHandlers[i] = new FactoryFaceItemHandler(i);
            faceFluidHandlers[i] = new FactoryFaceFluidHandler(i);
            directFaceItemHandlers[i] = new DirectFactoryFaceItemProxy(i);
            directFaceFluidHandlers[i] = new DirectFactoryFaceFluidProxy(i);
        }
    }

    public void blackboxDebug(String event, Supplier<String> details) {
        if (!Config.blackboxDebugLogging || level == null || level.isClientSide()) return;
        String detail;
        try {
            detail = details == null ? "" : details.get();
        } catch (RuntimeException exception) {
            detail = "diagnostic_error=" + exception.getClass().getSimpleName();
        }
        LOGGER.info("[CNF-BLACKBOX] event={} factory={} dimension={} pos={} mode={} stage={} gameTime={} {}",
                event, factoryId, level.dimension().location(), worldPosition,
                operationMode.getSerializedName(), learning.stageName(), level.getGameTime(), detail);
    }

    private void blackboxTrace(String event, Supplier<String> details) {
        if (!Config.blackboxDebugLogging || level == null || level.isClientSide()) return;
        String detail;
        try {
            detail = details == null ? "" : details.get();
        } catch (RuntimeException exception) {
            detail = "diagnostic_error=" + exception.getClass().getSimpleName();
        }
        LOGGER.debug("[CNF-BLACKBOX] event={} factory={} dimension={} pos={} mode={} stage={} gameTime={} {}",
                event, factoryId, level.dimension().location(), worldPosition,
                operationMode.getSerializedName(), learning.stageName(), level.getGameTime(), detail);
    }

    private FactoryLifecycleTransitions.Transition transitionOperationMode(
            FactoryLifecycleTransitions.Event event) {
        FactoryLifecycleTransitions.Transition transition =
                FactoryLifecycleTransitions.require(operationMode, event);
        operationMode = transition.to();
        blackboxTrace("lifecycle_transition", () -> "event=" + transition.event()
                + ", from=" + transition.from().getSerializedName()
                + ", to=" + transition.to().getSerializedName());
        return transition;
    }

    /** Persisted and client-synchronized modes are snapshots, not runtime transition events. */
    private void restoreOperationMode(OperationMode restoredMode) {
        operationMode = restoredMode == null ? OperationMode.CHUNK_LOADED : restoredMode;
    }

    String learningTransitSummary() {
        return factoryTransit.debugSummary();
    }

    void learningStateChanged() {
        setChanged();
        sendSync();
    }

    private String debugPortContract() {
        List<String> ports = new ArrayList<>();
        for (Direction face : Direction.values()) {
            int index = face.get3DDataValue();
            if (faceModes[index] != PortMode.NONE) {
                ports.add(face.getSerializedName() + "=" + faceModes[index].getSerializedName()
                        + "@" + portIds[index]);
            }
        }
        return ports.isEmpty() ? "none" : String.join(",", ports);
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

    /** Resolves one face to the shared immutable logical-port Interface. */
    public FactoryLogicalPortEndpoint resolveLogicalPort(Direction face) {
        if (face == null) return null;
        return new FactoryLogicalPortEndpoint(this, face, getFaceMode(face), getPortId(face));
    }

    /** Resolves a room-side port id through the same Interface used by exterior adapters. */
    public FactoryLogicalPortEndpoint resolveLogicalPort(int portId) {
        Direction face = getFaceForPortId(portId);
        return face == null ? null : resolveLogicalPort(face);
    }

    public NestedExtensionInterfaceBlockEntity getExtensionForFace(Direction face) {
        if (level == null || face == null) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(face));
        return blockEntity instanceof NestedExtensionInterfaceBlockEntity extension
                && extension.isBoundTo(this, face) ? extension : null;
    }

    public boolean isFaceTakenOver(Direction face) {
        return getExtensionForFace(face) != null;
    }

    /** Resolves the physical exterior surfaces for direct or extension-backed access. */
    public List<ExternalAccessPoint> getExternalAccessPoints(Direction face) {
        NestedExtensionInterfaceBlockEntity extension = getExtensionForFace(face);
        if (extension == null) {
            return List.of(new ExternalAccessPoint(worldPosition, face));
        }
        if (!extension.isOperational()) {
            return List.of();
        }
        return extension.getExternalSides().stream()
                .map(side -> new ExternalAccessPoint(extension.getBlockPos(), side))
                .toList();
    }

    /** Includes disabled extension faces so stale Create pressure can still be wiped. */
    private List<ExternalAccessPoint> getExternalTopologyPoints(Direction face) {
        NestedExtensionInterfaceBlockEntity extension = getExtensionForFace(face);
        if (extension == null) {
            return List.of(new ExternalAccessPoint(worldPosition, face));
        }
        return extension.getExternalSides().stream()
                .map(side -> new ExternalAccessPoint(extension.getBlockPos(), side))
                .toList();
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
        return basePowerProfile();
    }

    private FactoryPowerProfile basePowerProfile() {
        if (isSimulatedMode()) {
            return simulatedPowerProfile(plan);
        }
        return powerProfile;
    }

    private FactoryPowerProfile simulatedPowerProfile(FactoryPlan recipe) {
        FactoryPowerProfile profile = new FactoryPowerProfile();
        profile.set(recipe.getLearnedPeakInternalGeneratedSU(),
                recipe.getLearnedPeakConsumedSU(), recipe.getLearnedPeakExternalStressSU());
        return profile;
    }

    /**
     * Blueprint execution is defined by the source factory's captured profile, while the
     * target factory's active overclock scales the complete simulated profile.
     */
    private FactoryPowerProfile effectivePowerProfile() {
        if (isSimulatedMode()) {
            return simulatedPowerProfile(effectiveRecipe());
        }
        FactoryPowerProfile base = basePowerProfile();
        float multiplier = effectiveOverclockMultiplier();
        if (!plan.allowsMechanicalOverclock()) multiplier = 1.0f;
        return multiplier == 1.0f ? base : base.scaled(multiplier);
    }

    public FactoryPlan getPlan() {
        return plan;
    }

    /** Immutable learned recipe used as the base for nominal goggle rates. */
    public FactoryPlan getGoggleDisplayPlan() {
        return isSimulatedMode() ? plan : learning.preview();
    }

    public Map<ItemVariant, Float> getGuiItemInputRates() {
        return transferTelemetry.itemRates(true, telemetryTick());
    }

    public Map<ItemVariant, Float> getGuiItemOutputRates() {
        return transferTelemetry.itemRates(false, telemetryTick());
    }

    public Map<Fluid, Float> getGuiFluidInputRates() {
        return transferTelemetry.fluidRates(true, telemetryTick());
    }

    public Map<Fluid, Float> getGuiFluidOutputRates() {
        return transferTelemetry.fluidRates(false, telemetryTick());
    }

    private long telemetryTick() {
        return level == null ? 0L : level.getGameTime();
    }

    private void recordItemTelemetry(boolean input, ItemStack stack, int amount) {
        if (level != null && !level.isClientSide() && amount > 0) {
            transferTelemetry.recordItem(input, stack, amount, level.getGameTime());
        }
    }

    private void recordFluidTelemetry(boolean input, FluidStack stack, int amount) {
        if (level != null && !level.isClientSide() && amount > 0) {
            transferTelemetry.recordFluid(input, stack, amount, level.getGameTime());
        }
    }

    public SimpleContainer getOverclockInventory() {
        return overclockInventory;
    }

    public int getOverclockBatteryCount() {
        int count = 0;
        for (int i = 0; i < overclockInventory.getContainerSize(); i++) {
            if (!overclockInventory.getItem(i).is(ModItems.BLAZE_BATTERY.get())) {
                break;
            }
            count++;
        }
        return count;
    }

    public OverclockTier getSelectedOverclockTier() {
        return selectedOverclockTier;
    }

    public OverclockTier getActiveOverclockTier() {
        return activeOverclockTier;
    }

    public boolean canPlaceOverclockBattery(int slot, ItemStack stack) {
        if (slot < 0 || slot >= overclockInventory.getContainerSize()
                || stack.isEmpty() || !stack.is(ModItems.BLAZE_BATTERY.get())
                || !overclockInventory.getItem(slot).isEmpty()) {
            return false;
        }
        for (int i = 0; i < slot; i++) {
            if (!overclockInventory.getItem(i).is(ModItems.BLAZE_BATTERY.get())) {
                return false;
            }
        }
        return true;
    }

    public boolean canRemoveOverclockBattery(int slot) {
        if (slot < 0 || slot >= overclockInventory.getContainerSize()) {
            return false;
        }
        for (int i = slot + 1; i < overclockInventory.getContainerSize(); i++) {
            if (!overclockInventory.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public boolean selectOverclockTier(OverclockTier tier) {
        if (!isSimulatedMode() || tier == null || !tier.unlockedBy(getOverclockBatteryCount())
                || tier == OverclockTier.NORMAL) {
            blackboxDebug("overclock_selection_rejected", () -> "requested=" + tier
                    + ", batteries=" + getOverclockBatteryCount()
                    + ", simulated=" + isSimulatedMode());
            return false;
        }
        if (selectedOverclockTier == tier) {
            blackboxDebug("overclock_selection_unchanged", () -> "tier=" + tier);
            return true;
        }
        OverclockTier previous = selectedOverclockTier;
        selectedOverclockTier = tier;
        applySelectedOverclock();
        blackboxDebug("overclock_selected", () -> "previous=" + previous + ", selected=" + selectedOverclockTier
                + ", active=" + activeOverclockTier
                + ", batteries=" + getOverclockBatteryCount());
        setChanged();
        sendSync();
        return true;
    }

    public float getEffectiveProductionEfficiency() {
        return goggleRateMultiplier();
    }

    public float getLearnedProductionEfficiency() {
        return plan.hasCompleteRecipe() ? 1.0f : 0.0f;
    }

    private float effectiveOverclockMultiplier() {
        return isSimulatedMode() ? activeOverclockTier.multiplier() : 1.0f;
    }

    private float goggleRateMultiplier() {
        return isSimulatedMode() && plan.allowsMechanicalOverclock()
                ? selectedOverclockTier.multiplier() : 1.0f;
    }

    private static <K> Map<K, Float> scaledDisplayRates(Map<K, Float> rates, float multiplier) {
        if (multiplier == 1.0f || rates.isEmpty()) return rates;
        Map<K, Float> scaled = new HashMap<>();
        rates.forEach((resource, rate) -> scaled.put(resource, rate * multiplier));
        return scaled;
    }

    private static <K> Map<K, Float> routeAwareDisplayRates(FactoryPlan plan, float multiplier,
                                                             boolean input,
                                                             Function<FactoryPlan, Map<K, Float>> rates) {
        if (plan == null || !plan.hasCompleteRecipe()) return plan == null ? Map.of() : rates.apply(plan);
        if (input) {
            FactoryPlan processing = plan.processingRoute();
            return scaledDisplayRates(rates.apply(processing),
                    processing.allowsMechanicalOverclock() ? multiplier : 1f);
        }
        Map<K, Float> result = new HashMap<>();
        for (FactoryRoute route : plan.routes()) {
            FactoryPlan contract = route.contract();
            Map<K, Float> routeRates = scaledDisplayRates(rates.apply(contract),
                    contract.allowsMechanicalOverclock() ? multiplier : 1f);
            routeRates.forEach((resource, value) -> result.merge(resource, value, Float::sum));
        }
        return result;
    }

    private void onOverclockInventoryChanged() {
        if (loadingOverclockInventory) {
            return;
        }
        OverclockTier previous = selectedOverclockTier;
        selectedOverclockTier = OverclockTier.normalizeSelection(
                selectedOverclockTier, getOverclockBatteryCount());
        applySelectedOverclock();
        blackboxDebug("overclock_inventory_changed", () -> "batteries=" + getOverclockBatteryCount()
                + ", previousSelected=" + previous + ", selected=" + selectedOverclockTier
                + ", active=" + activeOverclockTier);
        setChanged();
        sendSync();
    }

    private void applySelectedOverclock() {
        if (activeOverclockTier == selectedOverclockTier) {
            return;
        }
        FactoryPlan currentPlan = plan.scaledRecipe(activeOverclockTier.multiplier());
        FactoryPlan replacementPlan = plan.scaledRecipe(selectedOverclockTier.multiplier());
        String beforeRuntime = runtimeScheduler.debugSummary();
        if (!runtimeScheduler.retime(currentPlan, replacementPlan)) {
            return;
        }
        OverclockTier previous = activeOverclockTier;
        activeOverclockTier = selectedOverclockTier;
        invalidateOverclockRecipe();
        FactoryPlan scaledPlan = plan.scaledRecipe(effectiveOverclockMultiplier());
        blackboxDebug("overclock_applied", () -> "previous=" + previous + ", active=" + activeOverclockTier
                + ", effectivePlan={" + scaledPlan.debugSummary() + "}, runtimeBefore=" + beforeRuntime
                + ", runtimeAfter=" + runtimeScheduler.debugSummary());
        setChanged();
        sendSync();
    }

    private void invalidateOverclockRecipe() {
        cachedOverclockRecipe = null;
        cachedOverclockRecipeFingerprint = "";
        cachedOverclockRecipeTier = OverclockTier.NORMAL;
    }

    private FactoryPlan effectiveRecipe() {
        if (level == null || !level.isClientSide()) {
            applySelectedOverclock();
        }
        float multiplier = effectiveOverclockMultiplier();
        if (multiplier == 1.0f || !plan.allowsMechanicalOverclock()) {
            return plan;
        }
        String fingerprint = plan.fingerprint();
        if (cachedOverclockRecipe == null || cachedOverclockRecipeTier != activeOverclockTier
                || !cachedOverclockRecipeFingerprint.equals(fingerprint)) {
            cachedOverclockRecipe = plan.scaledRecipe(multiplier);
            cachedOverclockRecipeTier = activeOverclockTier;
            cachedOverclockRecipeFingerprint = fingerprint;
        }
        return cachedOverclockRecipe;
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
        return getLearnedProductionEfficiency();
    }

    public String getFactoryId() {
        return factoryId;
    }

    public static CompoundTag getFactoryItemData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag();
    }

    public ItemStack createPortableItem(HolderLookup.Provider registries) {
        if (terminalBlueprintOnly) {
            settleTerminalForPermanentRemoval(null, "terminal_factory_broken");
            return new ItemStack(ModItems.NESTED_FACTORY.get());
        }
        if (nested && invalidNested && nestedSlotId < 0) {
            return new ItemStack(ModItems.NESTED_FACTORY.get());
        }
        if (level != null && !level.isClientSide()
                && (operationMode == OperationMode.BLACKBOX_PREPARING
                || operationMode == OperationMode.BLACKBOX_LEARNING)) {
            abortLearning("portable_item_created");
        }
        blackboxDebug("portable_snapshot_created", () -> "plan={" + plan.debugSummary()
                + "}, runtime=" + runtimeScheduler.debugSummary()
                + ", transit=" + factoryTransit.debugSummary());
        CompoundTag data = saveWithFullMetadata(registries);
        data.remove("id");
        data.remove("x");
        data.remove("y");
        data.remove("z");
        data.putBoolean(PORTABLE_BINDING_KEY, true);

        ItemStack stack = new ItemStack(ModItems.NESTED_FACTORY.get());
        BlockItem.setBlockEntityData(stack, ModBlockEntities.NESTED_FACTORY.get(), data);
        if (customName != null && !customName.isBlank()) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(customName));
        }
        return stack;
    }

    public static void destroyPortableItem(ServerLevel sourceLevel, ItemEntity itemEntity) {
        if (sourceLevel == null || sourceLevel.isClientSide() || itemEntity == null) {
            return;
        }
        ItemStack stack = itemEntity.getItem();
        if (!stack.is(ModItems.NESTED_FACTORY.get())) {
            return;
        }
        CompoundTag data = getFactoryItemData(stack);
        if (!data.getBoolean(PORTABLE_BINDING_KEY)) {
            return;
        }
        if (data.getBoolean("TerminalBlueprintOnly")
                || (data.getBoolean("Nested") && data.getInt("NestedSlotId") < 0)) {
            return;
        }

        MinecraftServer server = sourceLevel.getServer();
        if (server == null) {
            return;
        }
        String factoryId = data.getString("FactoryId");
        if (factoryId.isBlank() || PocketRegistry.isFactoryRegistered(server, factoryId)) {
            return;
        }
        BlockPos origin = data.getBoolean("Nested")
                ? (data.contains("NestedRoomOrigin") ? BlockPos.of(data.getLong("NestedRoomOrigin")) : null)
                : (data.contains("RootRoomOrigin") ? BlockPos.of(data.getLong("RootRoomOrigin")) : null);
        int[] bounds = portableRoomBounds(data, origin);
        ServerLevel pocket = server.getLevel(NestedFactoryBlock.POCKET_DIMENSION);
        if (origin == null || bounds == null || pocket == null) {
            return;
        }

        RoomMutationTaskManager tasks = RoomMutationTaskManager.get(server);
        String rootFactoryId = data.getString("RootFactoryId");
        if (rootFactoryId.isBlank()) {
            rootFactoryId = factoryId;
        }
        RoomMutationTaskManager.FactoryRef reference = new RoomMutationTaskManager.FactoryRef(
                sourceLevel.dimension(), BlockPos.containing(itemEntity.position()), factoryId,
                rootFactoryId);
        if (!tasks.scheduleDestroy(NestedFactoryBlock.POCKET_DIMENSION, origin, bounds, reference, true)) {
            return;
        }

        NestedFactorySaveData.get(server).releaseFreezeLease(factoryId);
        evacuatePortableRoom(server, pocket, rootFactoryId, origin, bounds);
    }

    private static int[] portableRoomBounds(CompoundTag data, BlockPos origin) {
        if (origin == null) {
            return null;
        }
        int[] chunkBounds = data.getIntArray("Bounds");
        if (chunkBounds.length != 6) {
            return null;
        }
        PocketBounds bounds = new PocketBounds();
        bounds.fromArray(chunkBounds);
        return new int[]{bounds.minX(origin), bounds.minY(origin), bounds.minZ(origin),
                bounds.maxX(origin), bounds.maxY(origin), bounds.maxZ(origin)};
    }

    private static void evacuatePortableRoom(MinecraftServer server, ServerLevel pocket,
                                             String rootFactoryId, BlockPos origin, int[] bounds) {
        int maxExits = Math.max(2, Config.maxNestingDepth + 2);
        if (rootFactoryId != null && !rootFactoryId.isBlank()) {
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
                if (!NestedFactoryBlock.sessionReferencesRoot(session, rootFactoryId)) {
                    continue;
                }
                int exitsAllowed = Math.max(maxExits, session.stack().size() + 1);
                for (int exits = 0; exits < exitsAllowed && session.isActive(); exits++) {
                    NestedFactoryBlock.exitCurrentFactory(player);
                    session = player.getData(ModAttachments.FACTORY_SESSION);
                }
                if (session.isActive()) {
                    NestedFactoryBlock.endSessionForPlayer(player);
                }
            }
        }

        AABB room = new AABB(bounds[0], bounds[1], bounds[2],
                bounds[3] + 1.0, bounds[4] + 1.0, bounds[5] + 1.0);
        for (Entity entity : List.copyOf(pocket.getEntitiesOfClass(Entity.class, room,
                candidate -> !(candidate instanceof ServerPlayer)))) {
            entity.discard();
        }
        PocketRegistry.clearRoomRegistrations(server, origin);
    }

    public boolean isNested() {
        return nested;
    }

    public boolean isRoot() {
        return !nested;
    }

    public boolean isEnterable() {
        return !ancestorFrozen && enterable && !bindingConflict && (!nested || !invalidNested)
                && ancestorsAllowPlayerEntry();
    }

    /**
     * Produces a side-effect-free, structured explanation of every condition currently blocking
     * player entry. This is intentionally more verbose than {@link #isEnterable()} and should only
     * be evaluated from a rejected-entry debug log.
     */
    public String debugEntryBlockers() {
        List<String> blockers = new ArrayList<>();
        if (ancestorFrozen) blockers.add("self_ancestor_frozen");
        if (!enterable) blockers.add("self_enterable_flag_false");
        if (bindingConflict) blockers.add("self_binding_conflict");
        if (nested && invalidNested) blockers.add("self_invalid_nested");
        if (terminalBlueprintOnly) blockers.add("self_terminal_blueprint_only");

        List<String> ancestry = new ArrayList<>();
        NestedFactoryBlockEntity current = this;
        Set<String> visited = new HashSet<>();
        int depth = 0;
        while (current.nested) {
            depth++;
            String currentId = current.factoryId == null ? "<null>" : current.factoryId;
            if (!visited.add(currentId)) {
                blockers.add("ancestor_cycle(depth=" + depth + ",factory=" + currentId + ")");
                break;
            }
            NestedFactoryBlockEntity parent = current.resolveParentFactoryByIdentity(false);
            if (parent == null) {
                blockers.add("parent_factory_unresolved(depth=" + depth + ",factory="
                        + current.parentFactoryId + ",cachedDimension="
                        + (current.parentDimension == null ? "null" : current.parentDimension.location())
                        + ",cachedPos=" + current.parentFactoryPos + ")");
                break;
            }

            ancestry.add("depth=" + depth + ",id=" + parent.factoryId
                    + ",dimension=" + parent.level.dimension().location() + ",pos=" + parent.worldPosition
                    + ",mode=" + parent.operationMode.getSerializedName()
                    + ",ancestorFrozen=" + parent.ancestorFrozen
                    + ",bindingConflict=" + parent.bindingConflict
                    + ",invalidNested=" + parent.invalidNested);
            if (!current.parentFactoryId.equals(parent.factoryId)) {
                blockers.add("parent_factory_id_mismatch(depth=" + depth + ",expected="
                        + current.parentFactoryId + ",actual=" + parent.factoryId + ")");
                break;
            }
            if (parent.operationMode != OperationMode.CHUNK_LOADED) {
                blockers.add("parent_mode_not_chunk_loaded(depth=" + depth + ",factory="
                        + parent.factoryId + ",mode=" + parent.operationMode.getSerializedName() + ")");
            }
            if (parent.ancestorFrozen) {
                blockers.add("parent_ancestor_frozen(depth=" + depth + ",factory=" + parent.factoryId + ")");
            }
            if (parent.bindingConflict) {
                blockers.add("parent_binding_conflict(depth=" + depth + ",factory=" + parent.factoryId + ")");
            }
            if (parent.invalidNested) {
                blockers.add("parent_invalid_nested(depth=" + depth + ",factory=" + parent.factoryId + ")");
            }
            current = parent;
        }

        return "blockers=[" + (blockers.isEmpty() ? "none" : String.join("|", blockers))
                + "], self={nested=" + nested
                + ",ancestorFrozen=" + ancestorFrozen
                + ",enterableFlag=" + enterable
                + ",bindingConflict=" + bindingConflict
                + ",invalidNested=" + invalidNested
                + ",terminalBlueprintOnly=" + terminalBlueprintOnly
                + ",nestingDepth=" + nestingDepth
                + ",nestedSlotId=" + nestedSlotId
                + ",parentFactoryId=" + parentFactoryId
                + ",parentDimension=" + (parentDimension == null ? "null" : parentDimension.location())
                + ",parentPos=" + parentFactoryPos
                + "}, ancestry=[" + (ancestry.isEmpty() ? "none" : String.join(";", ancestry)) + "]";
    }

    private boolean ancestorsAllowPlayerEntry() {
        NestedFactoryBlockEntity current = this;
        Set<String> visited = new HashSet<>();
        while (current.nested) {
            if (!visited.add(current.factoryId) || current.parentFactoryId.isBlank()
                    || current.level == null) return false;
            NestedFactoryBlockEntity parent = current.resolveParentFactoryByIdentity(true);
            if (parent == null
                    || parent.operationMode != OperationMode.CHUNK_LOADED
                    || parent.ancestorFrozen || parent.bindingConflict || parent.invalidNested) return false;
            current = parent;
        }
        return true;
    }

    private NestedFactoryBlockEntity resolveParentFactoryByIdentity(boolean refreshCachedLocation) {
        if (parentFactoryId.isBlank() || level == null) return null;
        MinecraftServer server = level.getServer();
        if (server == null) return null;

        if (parentDimension != null && parentFactoryPos != null) {
            ServerLevel cachedLevel = server.getLevel(parentDimension);
            if (cachedLevel != null) {
                if (refreshCachedLocation) cachedLevel.getChunkAt(parentFactoryPos);
                if ((refreshCachedLocation || cachedLevel.hasChunkAt(parentFactoryPos))
                        && cachedLevel.getBlockEntity(parentFactoryPos) instanceof NestedFactoryBlockEntity cached
                        && parentFactoryId.equals(cached.factoryId)) {
                    return cached;
                }
            }
        }

        PocketRegistry.FactoryLocation location = PocketRegistry.findFactoryLocationById(server, parentFactoryId);
        ServerLevel relocatedLevel = location == null ? null : server.getLevel(location.dimension());
        if (relocatedLevel == null) return null;
        if (refreshCachedLocation) {
            relocatedLevel.getChunkAt(location.pos());
        } else if (!relocatedLevel.hasChunkAt(location.pos())) {
            return null;
        }
        if (!(relocatedLevel.getBlockEntity(location.pos()) instanceof NestedFactoryBlockEntity relocated)
                || !parentFactoryId.equals(relocated.factoryId)) {
            return null;
        }
        if (refreshCachedLocation && (!location.dimension().equals(parentDimension)
                || !location.pos().equals(parentFactoryPos))) {
            parentDimension = location.dimension();
            parentFactoryPos = location.pos().immutable();
            setChanged();
        }
        return relocated;
    }

    public boolean isInvalidNested() {
        return invalidNested;
    }

    public boolean isTerminalBlueprintOnly() {
        return terminalBlueprintOnly;
    }

    public boolean hasPhysicalRoom() {
        return nested ? !terminalBlueprintOnly && !invalidNested && nestedSlotId >= 0
                : rootRoomAllocated;
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

    /** Exposes the currently bound pocket level to optional integration modules. */
    public ServerLevel getPocketLevel() {
        return pocketLevel();
    }

    /** True only while the real room is loaded and live resource calls are allowed. */
    public boolean isLiveResourceTransferMode() {
        return (operationMode == OperationMode.CHUNK_LOADED || operationMode == OperationMode.BLACKBOX_LEARNING)
                && !terminalBlueprintOnly && !invalidNested && !bindingConflict
                && pocketLevel() != null;
    }

    /** Returns the shared channel for an optional resource integration. */
    public FactoryTransit.PortResourceChannel getPortChannel(int portId) {
        return portChannel(portId);
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

    public String getChildFactoryId() {
        return childFactoryId;
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

    List<NestedFactoryBlockEntity> loadedChildrenForFreeze() {
        if (!hasPhysicalRoom()) return List.of();
        ServerLevel pocket = pocketLevel();
        if (pocket == null) return List.of();
        ensureRuntimeIndex(pocket);
        List<NestedFactoryBlockEntity> children = new ArrayList<>();
        for (BlockPos pos : runtimeIndex.childFactoryPositions()) {
            if (pocket.getBlockEntity(pos) instanceof NestedFactoryBlockEntity child) children.add(child);
        }
        return children;
    }

    /**
     * A parent plan cannot absorb a simulated no-input route. Such routes must remain separate
     * factories and feed an ordinary material-backed factory through their external faces.
     */
    private NestedFactoryBlockEntity findNestedNoInputPlan() {
        return findNestedNoInputPlan(new HashSet<>());
    }

    private NestedFactoryBlockEntity findNestedNoInputPlan(Set<String> visited) {
        if (!visited.add(factoryId)) return null;
        for (NestedFactoryBlockEntity child : loadedChildrenForFreeze()) {
            if (child.hasNoInputRegenerativeRoute()) return child;
            NestedFactoryBlockEntity descendant = child.findNestedNoInputPlan(visited);
            if (descendant != null) return descendant;
        }
        return null;
    }

    private boolean hasNoInputRegenerativeRoute() {
        if (!isSimulatedMode()) return false;
        FactoryPlan regenerative = effectiveRecipe().regenerativeRoute();
        return regenerative.hasRegenerativeRoute()
                && regenerative.getRecipeInputs().isEmpty()
                && regenerative.getRecipeInputFluids().isEmpty();
    }

    void setAncestorFrozen(boolean frozen) {
        if (ancestorFrozen == frozen) return;
        ancestorFrozen = frozen;
        if (frozen) releasePocketChunksImmediately();
        setChanged();
    }

    void refreshChunkRefsAfterFreeze() {
        if (!ancestorFrozen) chunkLeases.refreshForMode();
    }

    boolean isChunkExecutionSuspended() {
        return ancestorFrozen || isSimulatedMode();
    }

    private boolean parentStillFreezesThisFactory() {
        if (!nested || parentFactoryPos == null || parentDimension == null || level == null) return false;
        MinecraftServer server = level.getServer();
        if (server == null) return true;
        ServerLevel parentLevel = server.getLevel(parentDimension);
        if (parentLevel == null
                || !(parentLevel.getBlockEntity(parentFactoryPos) instanceof NestedFactoryBlockEntity parent)) {
            return true;
        }
        return parent.ancestorFrozen || parent.isSimulatedMode();
    }

    void releasePocketChunksImmediately() {
        chunkLeases.releaseImmediately();
    }

    public void setChildFactory(NestedFactoryBlockEntity child) {
        this.childFactoryId = child == null ? "" : child.getFactoryId();
        this.childFactoryPos = child == null ? null : child.getBlockPos().immutable();
        boundsVersion++;
        markRuntimeIndexDirty();
        setChanged();
    }

    public void toggleBlackbox(Player player) {
        blackboxDebug("toggle_requested", () -> "player=" + (player == null ? "none" : player.getScoreboardName())
                + ", transit=" + factoryTransit.debugSummary());
        if (isRoomMutationLocked()) {
            blackboxDebug("toggle_rejected", () -> "reason=room_mutation_locked");
            if (player instanceof ServerPlayer serverPlayer) {
                PlayerMessagePayload.sendTo(serverPlayer, Component.translatable("message.create_nested_factory.room_mutation.active").withStyle(ChatFormatting.YELLOW), false);
            }
            return;
        }
        if (blueprintApplied) {
            blackboxDebug("toggle_blueprint_cancel", () -> "target=chunk_loaded");
            cancelBlueprint(player, OperationMode.CHUNK_LOADED);
            return;
        }
        if (terminalBlueprintOnly) {
            blackboxDebug("toggle_rejected", () -> "reason=terminal_blueprint_only");
            if (player instanceof ServerPlayer sp) {
                PlayerMessagePayload.sendTo(sp, Component.translatable(
                        "message.create_nested_factory.factory.terminal_blueprint_only").withStyle(ChatFormatting.YELLOW), false);
            }
            return;
        }
        if (invalidNested) {
            blackboxDebug("toggle_rejected", () -> "reason=invalid_nested");
            if (player instanceof ServerPlayer sp) {
                PlayerMessagePayload.sendTo(sp, Component.translatable("message.create_nested_factory.factory.invalid_nested_mode_change").withStyle(ChatFormatting.RED), false);
            }
            return;
        }
        if (PocketFreezeManager.hasPlayersInsideLoadedTree(this)) {
            blackboxDebug("toggle_rejected", () -> "reason=players_inside_tree");
            if (player instanceof ServerPlayer sp) {
                PlayerMessagePayload.sendTo(sp, Component.translatable("message.create_nested_factory.factory.players_prevent_mode_change").withStyle(ChatFormatting.RED), false);
            }
            return;
        }
        if (operationMode == OperationMode.CHUNK_LOADED) {
            startBlackbox(player);
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
        blackboxDebug("blueprint_apply_requested", () -> "source="
                + (blueprint == null ? "none" : blueprint.sourceFactoryId()));
        if (isRoomMutationLocked()) {
            return rejectBlueprint("room_mutating", "message.create_nested_factory.blueprint.apply.room_mutating");
        }
        if (!isRoot() && !isEnterable() && !terminalBlueprintOnly) {
            return rejectBlueprint("invalid_target", "message.create_nested_factory.blueprint.apply.invalid_target");
        }
        if (blueprint == null || !blueprint.hasCompleteRunData()) {
            return rejectBlueprint("invalid_data", "message.create_nested_factory.blueprint.apply.invalid_data");
        }
        if (factoryId.equals(blueprint.sourceFactoryId())) {
            return rejectBlueprint("source_equals_target", "message.create_nested_factory.blueprint.apply.source_target");
        }
        if (blueprintApplied) {
            return rejectBlueprint("already_applied", "message.create_nested_factory.blueprint.apply.already_applied");
        }
        if (!runtimeScheduler.isIdle()) {
            return rejectBlueprint("production_transaction_active", "message.create_nested_factory.production_transaction.active");
        }
        if (isEnterable() && hasPlayersInside()) {
            return rejectBlueprint("players_inside", "message.create_nested_factory.blueprint.apply.players_inside");
        }
        if (!PocketFreezeManager.freezeLoadedTree(this)) {
            return rejectBlueprint("freeze_tree_failed", "message.create_nested_factory.blueprint.apply.players_inside");
        }
        invalidateProductionBatch(player, "apply_blueprint");
        destroyTransitResources(player, "apply_blueprint");
        preBlueprintSnapshot = captureRestoreSnapshot();
        transitionOperationMode(FactoryLifecycleTransitions.Event.APPLY_BLUEPRINT);
        blueprintApplied = true;
        transferTelemetry.clear();
        invalidateResourceCapabilities();
        appliedBlueprint = blueprint.copy(level.registryAccess());
        plan.read(blueprint.plan().write(new CompoundTag(), level.registryAccess()), level.registryAccess());
        for (int i = 0; i < 6; i++) {
            faceModes[i] = blueprint.faceMode(i);
            portIds[i] = blueprint.portId(i);
        }
        normalizeFacePortBindings();

        if (!invalidNested) releasePocketChunksImmediately();
        debugLastStressSatisfied = null;
        blackboxDebug("blueprint_applied", () -> "plan={" + plan.debugSummary() + "}");
        setChanged();
        sendSync();
        return null;
    }

    private Component rejectBlueprint(String reason, String translationKey) {
        blackboxDebug("blueprint_apply_rejected", () -> "reason=" + reason);
        return Component.translatable(translationKey);
    }

    private void invalidateResourceCapabilities() {
        if (level == null || level.isClientSide()) {
            return;
        }
        level.invalidateCapabilities(worldPosition);
        refreshExternalFluidNetworks();
        for (Direction face : Direction.values()) {
            NestedExtensionInterfaceBlockEntity extension = getExtensionForFace(face);
            if (extension != null) {
                extension.onHostStateChanged();
            }
        }
        if (!hasPhysicalRoom()) return;
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        for (int portId = 1; portId <= 6; portId++) {
            for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), roomOrigin(), portId)) {
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
            for (ExternalAccessPoint access : getExternalTopologyPoints(face)) {
                BlockPos adjacentPos = access.origin().relative(access.face());
                BlockState state = level.getBlockState(adjacentPos);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, adjacentPos);
                if (pipe != null && pipe.canHaveFlowToward(state, access.face().getOpposite())
                        && queued.add(adjacentPos)) {
                    pending.addLast(adjacentPos);
                }
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
            for (ExternalAccessPoint access : getExternalAccessPoints(face)) {
                BlockPos adjacentPos = access.origin().relative(access.face());
                BlockState state = level.getBlockState(adjacentPos);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, adjacentPos);
                if (pipe != null && pipe.canHaveFlowToward(state, access.face().getOpposite())
                        && queued.add(adjacentPos)) {
                    pending.addLast(adjacentPos);
                }
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
        if (runtimeScheduler.isEmpty() || level == null || level.isClientSide()) {
            return;
        }
        String before = runtimeScheduler.debugSummary();
        long destroyedFluid = 0L;
        for (FactoryRuntime runtime : runtimeScheduler.allRuntimes()) {
            destroyedFluid = Math.addExact(destroyedFluid, invalidateRuntime(runtime));
        }
        for (ItemStack capital : runtimeScheduler.releaseCapital()) {
            if (!capital.isEmpty()) Block.popResource(level, worldPosition, capital);
        }
        setChanged();
        long destroyedFluidAmount = destroyedFluid;
        blackboxDebug("runtime_invalidated", () -> "reason=" + reason + ", before=" + before
                + ", destroyedFluid=" + destroyedFluidAmount);
        if (destroyedFluid > 0) {
            LOGGER.warn("Destroyed {} mB of factory batch fluid at {} because {}",
                    destroyedFluid, worldPosition, reason);
        }
    }

    private long invalidateRuntime(FactoryRuntime runtime) {
        for (ItemStack stack : runtime.materializeItems()) {
            if (!stack.isEmpty()) {
                ItemEntity drop = new ItemEntity(level,
                        worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5,
                        stack.copy());
                level.addFreshEntity(drop);
            }
        }
        for (ItemStack tool : runtime.releaseLeasedTools()) {
            ItemEntity drop = new ItemEntity(level,
                    worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5,
                    tool);
            level.addFreshEntity(drop);
        }
        long destroyedFluid = runtime.destroyedFluidAmount();
        runtime.clear();
        return destroyedFluid;
    }

    /** Captures only the configuration that applyBlueprint() will overwrite. */
    private FactoryRestoreSnapshot captureRestoreSnapshot() {
        FactoryRestoreSnapshot snapshot = new FactoryRestoreSnapshot();
        snapshot.plan(plan.write(new CompoundTag(), level.registryAccess()));
        for (int i = 0; i < 6; i++) {
            snapshot.faceMode(i, faceModes[i]);
            snapshot.portId(i, portIds[i]);
        }
        return snapshot;
    }

    private void cancelBlueprint(Player player, OperationMode targetMode) {
        blackboxDebug("blueprint_cancel_requested", () -> "target=" + targetMode.getSerializedName());
        transferTelemetry.clear();
        invalidateProductionBatch(player, "cancel_blueprint");
        destroyTransitResources(player, "cancel_blueprint");
        if (preBlueprintSnapshot == null) {
            blueprintApplied = false;
            appliedBlueprint = null;
            transitionOperationMode(!invalidNested && !terminalBlueprintOnly
                    && targetMode == OperationMode.BLACKBOX_ACTIVE
                    ? FactoryLifecycleTransitions.Event.CANCEL_BLUEPRINT_TO_ACTIVE
                    : FactoryLifecycleTransitions.Event.CANCEL_BLUEPRINT_TO_LOADED);
            if (terminalBlueprintOnly) {
                releasePocketChunksImmediately();
                PocketFreezeManager.thawLoadedTree(this);
            } else if (operationMode == OperationMode.CHUNK_LOADED) {
                chunkLeases.retainRoom("load");
                PocketFreezeManager.thawLoadedTree(this);
            } else {
                releasePocketChunksImmediately();
            }
            invalidateResourceCapabilities();
            setChanged();
            sendSync();
            return;
        }

        FactoryRestoreSnapshot snapshot = preBlueprintSnapshot;
        // Blueprint cancellation restores only the overwritten configuration. Items and fluids
        // are committed runtime resources and must retain their current values.
        plan.read(snapshot.plan(), level.registryAccess());
        for (int i = 0; i < 6; i++) {
            faceModes[i] = snapshot.faceMode(i);
            portIds[i] = snapshot.portId(i);
        }
        normalizeFacePortBindings();

        if (terminalBlueprintOnly) {
            transitionOperationMode(FactoryLifecycleTransitions.Event.CANCEL_BLUEPRINT_TO_LOADED);
            releasePocketChunksImmediately();
            PocketFreezeManager.thawLoadedTree(this);
        } else if (invalidNested) {
            transitionOperationMode(FactoryLifecycleTransitions.Event.CANCEL_BLUEPRINT_TO_LOADED);
        } else if (targetMode == OperationMode.BLACKBOX_ACTIVE) {
            transitionOperationMode(FactoryLifecycleTransitions.Event.CANCEL_BLUEPRINT_TO_ACTIVE);
            releasePocketChunksImmediately();
        } else {
            transitionOperationMode(FactoryLifecycleTransitions.Event.CANCEL_BLUEPRINT_TO_LOADED);
            chunkLeases.retainRoom("load");
            PocketFreezeManager.thawLoadedTree(this);
        }

        blueprintApplied = false;
        appliedBlueprint = null;
        preBlueprintSnapshot = null;
        if (operationMode == OperationMode.CHUNK_LOADED) {
            rebuildRuntimeIndex(pocketLevel(), true);
        }
        blackboxDebug("blueprint_cancelled", () -> "restoredMode=" + operationMode.getSerializedName()
                + ", plan={" + plan.debugSummary() + "}");
        invalidateResourceCapabilities();
        setChanged();
        sendSync();

        if (invalidNested && player instanceof ServerPlayer sp) {
            PlayerMessagePayload.sendTo(sp, Component.translatable("message.create_nested_factory.blueprint.cancel.invalid_nested").withStyle(ChatFormatting.YELLOW), false);
        }
    }

    private void startBlackbox(Player player) {
        if (factoryTransit.hasUnsupportedExtensionState()) {
            blackboxDebug("learning_start_rejected", () -> "reason=unsupported_transit, transit="
                    + factoryTransit.debugSummary());
            if (player instanceof ServerPlayer serverPlayer) {
                PlayerMessagePayload.sendTo(serverPlayer,
                        Component.translatable("message.create_nested_factory.blackbox.unsupported_transit")
                                .withStyle(ChatFormatting.RED), false);
            }
            return;
        }
        rebuildRuntimeIndex(pocketLevel(), true);
        NestedFactoryBlockEntity nestedNoInput = findNestedNoInputPlan();
        if (nestedNoInput != null) {
            blackboxDebug("learning_start_rejected", () -> "reason=nested_no_input_plan, child="
                    + nestedNoInput.getFactoryId() + ", childMode="
                    + nestedNoInput.getOperationMode().getSerializedName());
            if (player instanceof ServerPlayer serverPlayer) {
                PlayerMessagePayload.sendTo(serverPlayer,
                        Component.translatable("message.create_nested_factory.blackbox.nested_no_input_plan")
                                .withStyle(ChatFormatting.RED), false);
            }
            return;
        }
        learning.prepare(level == null ? 0L : level.getGameTime());
        invalidateProductionBatch(player, "start_blackbox_relearning");
        plan = new FactoryPlan();
        invalidateOverclockRecipe();
        factoryTransit.sealInputGeneration();
        transitionOperationMode(FactoryLifecycleTransitions.Event.START_PREPARING);
        blackboxDebug("preparing_started", () -> "ticks=" + learning.preparingTicks()
                + ", ports=" + debugPortContract()
                + ", transit=" + factoryTransit.debugSummary());
        invalidateResourceCapabilities();
        setChanged();
        sendSync();
    }

    private void stopBlackbox(Player player) {
        blackboxDebug("blackbox_stop_requested", () -> "runtime=" + runtimeScheduler.debugSummary()
                + ", transit=" + factoryTransit.debugSummary());
        invalidateProductionBatch(player, "stop_blackbox");
        destroyTransitResources(player, "stop_blackbox");
        boolean wasActive = operationMode == OperationMode.BLACKBOX_ACTIVE;
        learning.stop();
        PocketLearningObserver.unregister(this);
        transitionOperationMode(FactoryLifecycleTransitions.Event.STOP_BLACKBOX);
        transferTelemetry.clear();
        if (wasActive) {
            chunkLeases.retainRoom("load");
            PocketFreezeManager.thawLoadedTree(this);
        }
        rebuildRuntimeIndex(pocketLevel(), true);
        debugLastStressSatisfied = null;
        blackboxDebug("blackbox_stopped", () -> "restoredMode=chunk_loaded, transit="
                + factoryTransit.debugSummary());
        invalidateResourceCapabilities();
        setChanged();
        sendSync();
    }

    public boolean hasPlayersInside() {
        if (!hasPhysicalRoom()) return false;
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

    public void onPlayerEntered(ResourceKey<Level> sourceDimension, BlockPos sourcePosition) {
        chunkLeases.onPlayerEntered(sourceDimension, sourcePosition);
    }

    public void onPlayerExited() {
        chunkLeases.onPlayerExited();
        setChanged();
    }

    public int getInputMbPerSec() {
        float perSec = 0f;
        for (float v : getGuiFluidInputRates().values()) {
            perSec += v;
        }
        return (int) perSec;
    }

    public int getOutputMbPerSec() {
        float perSec = 0f;
        for (float v : getGuiFluidOutputRates().values()) {
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
            case BLACKBOX_PREPARING -> tooltip.add(GoggleTooltips.stat(
                    "goggles.create_nested_factory.learning",
                    Component.translatable("goggles.create_nested_factory.learning.preparing")
                            .withStyle(ChatFormatting.YELLOW)));
            case BLACKBOX_LEARNING -> {
                Component progress = Component.translatable(switch (learning.stage()) {
                    case WARMUP -> "goggles.create_nested_factory.learning.stage.warmup";
                    case OBSERVING -> "goggles.create_nested_factory.learning.stage.observing";
                });
                tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.learning",
                        progress.copy().withStyle(ChatFormatting.YELLOW)));
            }
            default -> { }
        }

        if (operationMode == OperationMode.BLACKBOX_ACTIVE) {
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.efficiency",
                    String.format(Locale.ROOT, "%.0f%%", getEffectiveProductionEfficiency() * 100.0f),
                    ChatFormatting.GOLD));
        }

        FactoryPowerProfile displayedPowerProfile = isSimulatedMode()
                ? simulatedPowerProfile(plan.scaledRecipe(goggleRateMultiplier())) : basePowerProfile();

        if (displayedPowerProfile.generatedSU() != 0f || displayedPowerProfile.consumedSU() != 0f) {
            tooltip.add(GoggleTooltips.section("goggles.create_nested_factory.stress"));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.generated", fmt(displayedPowerProfile.generatedSU()) + " su", ChatFormatting.AQUA));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.consumed", fmt(displayedPowerProfile.consumedSU()) + " su", ChatFormatting.AQUA));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.net", fmt(displayedPowerProfile.netSU()) + " su", ChatFormatting.AQUA));
        }


        FactoryPlan displayedRecipe = getGoggleDisplayPlan();
        float displayedRateMultiplier = goggleRateMultiplier();
        Map<ItemVariant, Float> displayedItemInputs = operationMode == OperationMode.CHUNK_LOADED
                ? transferTelemetry.itemRates(true, telemetryTick())
                : isSimulatedMode()
                ? routeAwareDisplayRates(displayedRecipe, displayedRateMultiplier, true, FactoryPlan::getInputRates)
                : displayedRecipe.getInputRates();
        Map<ItemVariant, Float> displayedItemOutputs = operationMode == OperationMode.CHUNK_LOADED
                ? transferTelemetry.itemRates(false, telemetryTick())
                : isSimulatedMode()
                ? routeAwareDisplayRates(displayedRecipe, displayedRateMultiplier, false, FactoryPlan::getOutputRates)
                : displayedRecipe.getOutputRates();
        addItemRates(tooltip, "goggles.create_nested_factory.input_items", displayedItemInputs);
        addItemRates(tooltip, "goggles.create_nested_factory.output_items", displayedItemOutputs);

        if (blueprintApplied && appliedBlueprint != null) {
            tooltip.add(GoggleTooltips.section("goggles.create_nested_factory.blueprint"));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.source_name",
                    Component.literal(appliedBlueprint.sourceFactoryName()).withStyle(ChatFormatting.AQUA)));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.efficiency",
                    String.format(Locale.ROOT, "%.0f%%", getEffectiveProductionEfficiency() * 100.0f), ChatFormatting.GOLD));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.source_dimension",
                    appliedBlueprint.sourceDimension(), ChatFormatting.GRAY));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.source_pos",
                    appliedBlueprint.sourcePos().getX() + " " + appliedBlueprint.sourcePos().getY() + " " + appliedBlueprint.sourcePos().getZ(),
                    ChatFormatting.GRAY));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.blueprint.source_depth",
                    String.valueOf(appliedBlueprint.sourceDepth()), ChatFormatting.LIGHT_PURPLE));
        }

        Map<Fluid, Float> displayedFluidInputs = operationMode == OperationMode.CHUNK_LOADED
                ? transferTelemetry.fluidRates(true, telemetryTick())
                : isSimulatedMode()
                ? routeAwareDisplayRates(displayedRecipe, displayedRateMultiplier, true,
                FactoryPlan::getInputFluidRates)
                : displayedRecipe.getInputFluidRates();
        Map<Fluid, Float> displayedFluidOutputs = operationMode == OperationMode.CHUNK_LOADED
                ? transferTelemetry.fluidRates(false, telemetryTick())
                : isSimulatedMode()
                ? routeAwareDisplayRates(displayedRecipe, displayedRateMultiplier, false,
                FactoryPlan::getOutputFluidRates)
                : displayedRecipe.getOutputFluidRates();
        addFluidRates(tooltip, "goggles.create_nested_factory.input_fluids", displayedFluidInputs);
        addFluidRates(tooltip, "goggles.create_nested_factory.output_fluids", displayedFluidOutputs);

        if (nested && !isEnterable()) {
            tooltip.add(Component.literal("    ")
                    .append(Component.translatable("goggles.create_nested_factory.child_factory_exists").withStyle(ChatFormatting.RED)));
        }
        return true;
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    public static String formatRate(float rate) {
        if (!Float.isFinite(rate)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.2f", rate)
                .replaceFirst("0+$", "").replaceFirst("\\.$", "");
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
                        .append(Component.literal("  " + formatRate(e.getValue()) + "/s")
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
                        .append(Component.literal("  " + formatRate(e.getValue()) + " mB/s")
                                .withStyle(ChatFormatting.AQUA))));
    }

    private void tickChunkLoaded() {
        learning.tickIdleCompiler();
    }

    private void tickPreparing() {
        if (learning.tickPreparing()) enterLearning();
    }

    private void enterLearning() {
        rebuildRuntimeIndex(pocketLevel(), true);
        transitionOperationMode(FactoryLifecycleTransitions.Event.ENTER_LEARNING);
        invalidateResourceCapabilities();
        learning.enterWarmup(this);
        setChanged();
        sendSync();
    }

    private void tickLearning() {
        FactoryLearningController.TickOutcome outcome = learning.tick(
                this, factoryTransit, powerProfile, liveExternalStressDemandSU, level.getGameTime());
        switch (outcome.kind()) {
            case NONE -> { }
            case ACTIVATE -> enterActive(outcome.plan());
            case ABORT -> abortLearning(outcome.reason());
        }
    }

    private void enterActive(FactoryPlan candidate) {
        learning.complete();
        PocketLearningObserver.unregister(this);
        if (!PocketFreezeManager.freezeLoadedTree(this)) {
            abortLearning("freeze_tree_failed");
            return;
        }
        destroyTransitResources(null, "blackbox_activation_hard_cut");
        plan = candidate;
        invalidateOverclockRecipe();
        transitionOperationMode(FactoryLifecycleTransitions.Event.ACTIVATE);
        transferTelemetry.clear();
        debugLastStressSatisfied = null;
        applySelectedOverclock();
        invalidateResourceCapabilities();
        releasePocketChunksImmediately();
        blackboxDebug("blackbox_activated", () -> "plan={" + plan.debugSummary()
                + "}, power={" + powerProfile.debugSummary() + "}, ports=" + debugPortContract()
                + ", transit=" + factoryTransit.debugSummary());
        setChanged();
        sendSync();
    }

    private void abortLearning(String reason) {
        blackboxDebug("learning_aborted", () -> "reason=" + reason
                + ", " + learning.abortDebugSummary()
                + ", transit=" + factoryTransit.debugSummary());
        learning.stop();
        PocketLearningObserver.unregister(this);
        factoryTransit.restoreLiveInputs();
        transitionOperationMode(FactoryLifecycleTransitions.Event.ABORT_LEARNING);
        rebuildRuntimeIndex(pocketLevel(), true);
        invalidateResourceCapabilities();
        setChanged();
        sendSync();
    }
    public void onLearningBlockChanged(ServerLevel pocket, BlockPos pos, BlockState oldState, BlockState newState) {
        Boolean accepted = learning.onBlockChanged(this, pocket, pos, oldState, newState);
        if (accepted == null) return;
        blackboxTrace("learning_block_mutation", () -> "block=" + pos + ", old=" + oldState
                + ", new=" + newState + ", sourceOwned=" + accepted);
    }

    public void onLearningDrillProduction(ServerLevel pocket, DrillProductionEvent event) {
        Boolean accepted = learning.onDrillProduction(this, pocket, event);
        if (accepted == null) return;
        blackboxTrace("learning_drill_production", () -> "target=" + event.targetPos()
                + ", block=" + event.brokenState() + ", path=" + event.path()
                + ", sourceAccepted=" + accepted);
    }

    private void tickBlackbox() {
        applySelectedOverclock();
        FactoryPlan plan = effectiveRecipe();
        if (!runtimeScheduler.validate(plan)) {
            FactoryPlan invalidPlan = plan;
            blackboxDebug("runtime_invalidated", () -> "reason=plan_or_ownership_mismatch, plan={"
                    + invalidPlan.debugSummary() + "}, runtime=" + runtimeScheduler.debugSummary());
            invalidateProductionBatch(null, "recipe_changed");
            applySelectedOverclock();
            plan = effectiveRecipe();
        }
        boolean changed = false;
        if (operationMode == OperationMode.BLUEPRINT && !runtimeScheduler.capitalReady(plan)) {
            FactoryPlan waitingPlan = plan;
            String wait = "startup_capital:" + runtimeScheduler.debugSummary();
            if (!wait.equals(debugLastRuntimeWait)) {
                debugLastRuntimeWait = wait;
                blackboxDebug("runtime_waiting", () -> "reason=missing_startup_capital, required="
                        + waitingPlan.getStartupCapitalItems() + ", runtime=" + runtimeScheduler.debugSummary());
            }
            if (changed) setChanged();
            return;
        }
        FactoryPowerProfile activePowerProfile = effectivePowerProfile();
        boolean stressSatisfied = activePowerProfile.externalStressDemandSU() <= STRESS_EPSILON
                || externalStressSatisfied;
        if (debugLastStressSatisfied == null || debugLastStressSatisfied != stressSatisfied) {
            debugLastStressSatisfied = stressSatisfied;
            blackboxDebug("stress_state", () -> "satisfied=" + stressSatisfied
                    + ", internalGeneratedSU=" + activePowerProfile.generatedSU()
                    + ", consumedSU=" + activePowerProfile.consumedSU()
                    + ", netSU=" + activePowerProfile.netSU()
                    + ", requiredSU=" + activePowerProfile.externalStressDemandSU()
                    + ", reservedSU=" + reservedExternalSU
                    + ", selectedFace=" + selectedExternalFace
                    + ", selectedSpeed=" + selectedExternalSpeed);
        }
        configureSimulatedOutputEscrow(plan);
        for (FactoryRuntimeScheduler.ScheduledRoute route : runtimeScheduler.scheduledRoutes(plan)) {
            FactoryRuntime.Phase beforePhase = route.runtime().phase();
            int beforeProgress = route.runtime().progressTicks();
            long beforeBatch = route.runtime().batchId();
            boolean advanced = route.runtime().advance(route.plan(), stressSatisfied);
            changed |= advanced;
            FactoryTransit.PortResourceChannel outputEscrow = simulatedOutputEscrowChannel();
            boolean escrowed = route.runtime().moveOutputsToTransit(route.plan(), outputEscrow);
            changed |= escrowed;
            if (escrowed) {
                blackboxTrace("runtime_output_escrowed", () -> "route=" + route.routeId()
                        + ", runtime={" + route.runtime().debugSummary() + "}, transit="
                        + factoryTransit.debugSummary());
            }
            if (beforePhase != route.runtime().phase() || beforeBatch != route.runtime().batchId()
                    || (beforeProgress > 0 && route.runtime().progressTicks() == 0)) {
                blackboxTrace("runtime_transition", () -> "route=" + route.routeId()
                        + ", beforePhase=" + beforePhase + ", beforeProgress=" + beforeProgress
                        + ", beforeBatch=" + beforeBatch + ", after={" + route.runtime().debugSummary() + "}");
            }
        }
        String waitReason = runtimeWaitReason(plan, stressSatisfied);
        if (!waitReason.equals(debugLastRuntimeWait)) {
            debugLastRuntimeWait = waitReason;
            if (!waitReason.isEmpty()) blackboxDebug("runtime_waiting", () -> "reason=" + waitReason
                    + ", runtime=" + runtimeScheduler.debugSummary());
        }
        if (changed) setChanged();
    }

    /** All simulated output faces expose one stable factory-owned escrow channel. */
    private FactoryTransit.PortResourceChannel simulatedOutputEscrowChannel() {
        return factoryTransit.simulatedOutput();
    }

    /** Two output handoff quanta provide backpressure without imposing a transfer-rate quota. */
    private void configureSimulatedOutputEscrow(FactoryPlan effectivePlan) {
        long itemCapacity = 0L;
        long fluidCapacity = 0L;
        for (FactoryRoute route : effectivePlan.routes()) {
            FactoryPlan contract = route.contract();
            long routeItems = 0L;
            for (long amount : contract.getRecipeOutputs().values()) {
                routeItems = saturatingRuntimeCapacityAdd(routeItems,
                        outputHandoffQuantum(contract, amount));
            }
            long routeFluids = 0L;
            for (long amount : contract.getRecipeOutputFluids().values()) {
                routeFluids = saturatingRuntimeCapacityAdd(routeFluids,
                        outputHandoffQuantum(contract, amount));
            }
            itemCapacity = saturatingRuntimeCapacityAdd(itemCapacity,
                    saturatingRuntimeCapacityAdd(routeItems, routeItems));
            fluidCapacity = saturatingRuntimeCapacityAdd(fluidCapacity,
                    saturatingRuntimeCapacityAdd(routeFluids, routeFluids));
        }
        simulatedOutputEscrowChannel().configureOutputCapacity(itemCapacity, fluidCapacity);
    }

    private static long outputHandoffQuantum(FactoryPlan contract, long total) {
        if (total <= 0L || !contract.streamsStatisticalOutputsPerSecond()) return Math.max(0L, total);
        int cycleTicks = Math.max(1, contract.getRecipeCycleTicks());
        int interval = Math.min(20, cycleTicks);
        long quotient = total / cycleTicks;
        long remainder = total % cycleTicks;
        long whole = quotient * interval;
        long fractional = (remainder * interval + cycleTicks - 1L) / cycleTicks;
        return saturatingRuntimeCapacityAdd(whole, fractional);
    }

    private static long saturatingRuntimeCapacityAdd(long first, long second) {
        if (first < 0L || second < 0L || Long.MAX_VALUE - first < second) return Long.MAX_VALUE;
        return first + second;
    }

    private static long simulatedOutputFluidCapacity(FactoryPlan effectivePlan, FluidVariant fluid) {
        long capacity = 0L;
        for (FactoryRoute route : effectivePlan.routes()) {
            FactoryPlan contract = route.contract();
            long quantum = outputHandoffQuantum(contract,
                    contract.getRecipeOutputFluids().getOrDefault(fluid, 0L));
            capacity = saturatingRuntimeCapacityAdd(capacity,
                    saturatingRuntimeCapacityAdd(quantum, quantum));
        }
        return capacity;
    }

    private String runtimeWaitReason(FactoryPlan effectivePlan, boolean stressSatisfied) {
        if (!stressSatisfied) return "insufficient_stress";
        for (FactoryRuntimeScheduler.ScheduledRoute route : runtimeScheduler.scheduledRoutes(effectivePlan)) {
            FactoryRuntime runtime = route.runtime();
            // A finished transaction owns its output escrow until that output is extracted.
            // Recipe deficits describe the next transaction and must not mask this phase.
            if (runtime.isDeliveringOutputs()) return "output_not_fully_extracted:" + route.routeId();
            if (!runtime.toolsReady(route.plan())) return "missing_tool:" + route.routeId();
            if (!runtime.inputsComplete(route.plan())) return "missing_input:" + route.routeId();
        }
        return "";
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
            for (KineticBlockEntity anchor : adjacentStressInputs(face)) {
                float speed = anchor.getTheoreticalSpeed();
                if (!Float.isFinite(speed) || Math.abs(speed) <= STRESS_EPSILON) {
                    continue;
                }
                KineticNetwork network = anchor.getOrCreateNetwork();
                float available = network.calculateCapacity() - network.calculateStress();
                if (network == reservedExternalNetwork) {
                    available += reservedExternalSU;
                }
                ExternalStressCandidate candidate = new ExternalStressCandidate(face,
                        anchor.getBlockPos().asLong(), anchor, network, speed, available);
                ExternalStressCandidate existing = candidates.get(network);
                if (existing == null
                        || candidate.availableSU() > existing.availableSU() + STRESS_EPSILON
                        || (Math.abs(candidate.availableSU() - existing.availableSU()) <= STRESS_EPSILON
                        && (candidate.face().get3DDataValue() < existing.face().get3DDataValue()
                        || (candidate.face() == existing.face()
                        && candidate.sourceOrder() < existing.sourceOrder())))) {
                    candidates.put(network, candidate);
                }
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
        if (candidate.face().get3DDataValue() != best.face().get3DDataValue()) {
            return candidate.face().get3DDataValue() < best.face().get3DDataValue();
        }
        return candidate.sourceOrder() < best.sourceOrder();
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
    private List<KineticBlockEntity> adjacentStressInputs(Direction face) {
        NestedExtensionInterfaceBlockEntity extension = getExtensionForFace(face);
        if (extension != null) {
            return extension.getStressInputs();
        }
        BlockPos inputPos = worldPosition.relative(face);
        BlockState inputState = level.getBlockState(inputPos);
        if (!(inputState.getBlock() instanceof IRotate rotate)
                || !rotate.hasShaftTowards(level, inputPos, inputState, face.getOpposite())) {
            return List.of();
        }
        if (!(level.getBlockEntity(inputPos) instanceof KineticBlockEntity kbe)
                || kbe instanceof BeltBlockEntity) {
            return List.of();
        }
        return List.of(kbe);
    }

    private List<NestedStressPortBlockEntity> roomStressPorts(ServerLevel pocket) {
        List<NestedStressPortBlockEntity> ports = new ArrayList<>();
        if (!hasPhysicalRoom()) return ports;
        for (BlockPos stressPortPos : PocketRegistry.getStressPorts(pocket.getServer(), roomOrigin())) {
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
        // Demand belongs to the internal room network and remains measurable when the external
        // shaft is disconnected or temporarily overstressed. Learning must not turn that absence
        // into a zero-cost recipe.
        Map<NestedStressPortBlockEntity, Float> requests = calculatePortStressRequests(ports);
        float totalDemand = 0f;
        for (float requested : requests.values()) {
            totalDemand += requested;
        }
        // Create may expose the relay network one tick before its stress map is fully rebuilt.
        // The physical room scan provides a conservative lower bound for that transient gap.
        float physicalNetDemand = Math.max(0f,
                powerProfile.consumedSU() - powerProfile.internalGeneratedSU());
        totalDemand = Math.max(totalDemand, physicalNetDemand);
        liveExternalStressDemandSU = totalDemand;

        ExternalStressCandidate candidate = selectExternalStressSource();
        if (candidate == null) {
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
        if (!hasPhysicalRoom() || level == null || level.isClientSide()) {
            return false;
        }
        MinecraftServer server = level.getServer();
        return server != null
                && RoomMutationTaskManager.get(server).isRoomLocked(NestedFactoryBlock.POCKET_DIMENSION, roomOrigin());
    }

    private boolean usesRuntimeIndex() {
        return hasPhysicalRoom() && (operationMode == OperationMode.CHUNK_LOADED
                || operationMode == OperationMode.BLACKBOX_PREPARING
                || operationMode == OperationMode.BLACKBOX_LEARNING);
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
                        continue;
                    } else if (blockEntity instanceof KineticBlockEntity) {
                        runtimeIndex.kineticPositions().add(pos.immutable());
                    }
                    if (findItemHandler(pocketLevel, pos) != null) {
                        runtimeIndex.inventoryPositions().add(pos.immutable());
                    }
                    if (findFluidHandler(pocketLevel, pos) != null) {
                        runtimeIndex.fluidHandlerPositions().add(pos.immutable());
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
            float genSU = 0f, conSU = 0f;
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
                if (kbe.hasNetwork()) {
                    KineticNetwork network = kbe.getOrCreateNetwork();
                    Float networkStress = network.members.get(kbe);
                    conSU += networkStress == null
                            ? Math.abs(kbe.calculateStressApplied()) * speed
                            : Math.abs(networkStress) * speed;
                    if (!(kbe instanceof NestedStressPortBlockEntity)) {
                        Float networkCapacity = network.sources.get(kbe);
                        genSU += networkCapacity == null
                                ? kbe.calculateAddedStressCapacity() * Math.abs(kbe.getGeneratedSpeed())
                                : Math.max(0f, networkCapacity) * Math.abs(kbe.getGeneratedSpeed());
                    }
                } else {
                    conSU += Math.abs(kbe.calculateStressApplied()) * speed;
                    if (!(kbe instanceof NestedStressPortBlockEntity)) {
                        genSU += kbe.calculateAddedStressCapacity() * Math.abs(kbe.getGeneratedSpeed());
                    }
                }
            }
            float physicalNetDemand = Math.max(0f, conSU - genSU);
            powerProfile.set(genSU, conSU, Math.max(liveExternalStressDemandSU, physicalNetDemand));
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
        if (invalidNested || (terminalBlueprintOnly && !blueprintApplied)) {
            return 0f;
        }
        if (operationMode != OperationMode.BLACKBOX_ACTIVE && operationMode != OperationMode.BLUEPRINT) {
            ensureRuntimeIndex(pocketLevel());
            refreshPowerSnapshot(pocketLevel(), visitingFactories);
        }
        return effectivePowerProfile().externalStressDemandSU();
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

    private static IFluidHandler findFluidHandler(ServerLevel level, BlockPos pos) {
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (handler != null) return handler;
        for (Direction direction : Direction.values()) {
            handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, direction);
            if (handler != null) return handler;
        }
        return null;
    }

    /** Counts only handlers discovered by the runtime room index. */
    Map<ItemVariant, Long> countItemsInFactorySpace() {
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
                    countRoomInventoryStack(counts, stack);
                }
            }
        }
        return counts;
    }

    /** Match Create's package boundary semantics: the contents are resources; the box is transport. */
    private static void countRoomInventoryStack(Map<ItemVariant, Long> counts, ItemStack stack) {
        if (PackageItem.isPackage(stack)) {
            IItemHandler contents = PackageItem.getContents(stack);
            for (int slot = 0; slot < contents.getSlots(); slot++) {
                ItemStack contained = contents.getStackInSlot(slot);
                if (!contained.isEmpty()) {
                    long amount = Math.multiplyExact((long) contained.getCount(), stack.getCount());
                    counts.merge(ItemVariant.of(contained), amount, Math::addExact);
                }
            }
            return;
        }
        counts.merge(ItemVariant.of(stack), (long) stack.getCount(), Math::addExact);
    }

    Map<FluidVariant, Long> countFluidsInFactorySpace() {
        Map<FluidVariant, Long> counts = new HashMap<>();
        ServerLevel pocket = pocketLevel();
        if (pocket == null) return counts;
        ensureRuntimeIndex(pocket);
        var iterator = runtimeIndex.fluidHandlerPositions().iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            IFluidHandler handler = findFluidHandler(pocket, pos);
            if (handler == null) {
                iterator.remove();
                continue;
            }
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                FluidStack stack = handler.getFluidInTank(tank);
                if (stack != null && !stack.isEmpty()) {
                    counts.merge(FluidVariant.of(stack), (long) stack.getAmount(), Math::addExact);
                }
            }
        }
        return counts;
    }

    Map<ItemVariant, Long> countToolDurabilityInFactorySpace() {
        Map<ItemVariant, Long> remainingDurability = new HashMap<>();
        ServerLevel pocket = pocketLevel();
        if (pocket == null) return remainingDurability;
        ensureRuntimeIndex(pocket);
        for (BlockPos pos : runtimeIndex.inventoryPositions()) {
            IItemHandler handler = findItemHandler(pocket, pos);
            if (handler == null) continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty() || !stack.isDamageableItem()) continue;
                long perTool = Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
                long total = Math.multiplyExact(perTool, stack.getCount());
                remainingDurability.merge(ItemVariant.toolSignature(stack), total, Math::addExact);
            }
        }
        return remainingDurability;
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
                NestedFactoryBlock.POCKET_DIMENSION, origin, FactoryRoomGeometry.room(bounds, origin),
                FactoryRoomGeometry.expandedInterior(bounds, old, origin, direction), roomTaskReference());
        if (!scheduled) {
            bounds.collapse(direction);
            return false;
        }
        boundsVersion++;
        chunkLeases.refreshAfterBoundsChange();
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
        bounds.collapse(direction);
        FactoryRoomGeometry.CollapseRegions regions = FactoryRoomGeometry.collapse(old, bounds, origin, direction);
        boolean scheduled = RoomMutationTaskManager.get(server).scheduleCollapseValidation(
                NestedFactoryBlock.POCKET_DIMENSION, origin, FactoryRoomGeometry.room(bounds, origin),
                regions.removed(), regions.validation(), regions.playerValidation(), roomTaskReference(),
                direction.name(), refundItem, requesterId);
        if (!scheduled) {
            bounds.expand(direction);
            return false;
        }
        boundsVersion++;
        chunkLeases.refreshAfterBoundsChange();
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
            blackboxDebug("face_mode_change_rejected", () -> "face=" + face + ", reason=room_mutation_locked");
            if (reportLockFailure) {
                PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.room_mutation.active")
                        .withStyle(ChatFormatting.YELLOW), false);
            }
            return null;
        }
        boolean sharedSimulatedIo = isSimulatedMode();
        if (!sharedSimulatedIo && !runtimeScheduler.isIdle()) {
            blackboxDebug("face_mode_change_rejected", () -> "face=" + face
                    + ", reason=production_transaction_active, runtime=" + runtimeScheduler.debugSummary());
            PlayerMessagePayload.sendTo(player,
                    Component.translatable("message.create_nested_factory.production_transaction.active")
                            .withStyle(ChatFormatting.RED), false);
            return null;
        }
        int index = face.get3DDataValue();
        PortMode previousMode = faceModes[index];
        int previousPortId = portIds[index];
        if (!sharedSimulatedIo) {
            invalidatePlanForPortContractChange(player);
            destroyTransitResources(player, "face_mode_changed");
        }
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
        blackboxDebug("face_mode_changed", () -> "face=" + face + ", previousMode=" + previousMode
                + ", previousPort=" + previousPortId + ", mode=" + faceModes[index]
                + ", port=" + portIds[index] + ", sharedSimulatedIo=" + sharedSimulatedIo);
        invalidateResourceCapabilities();
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        Component faceName = Component.translatable("direction.create_nested_factory." + face.getSerializedName());
        Component modeName = Component.translatable("goggles.create_nested_factory.port_mode."
                + faceModes[index].getSerializedName());
        String key = sharedSimulatedIo
                ? "message.create_nested_factory.face_mode.updated_shared"
                : faceModes[index] == PortMode.NONE
                ? "message.create_nested_factory.face_mode.updated_none"
                : "message.create_nested_factory.face_mode.updated";
        Component message = sharedSimulatedIo || faceModes[index] == PortMode.NONE
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
        if (side != null && isFaceTakenOver(side)) {
            return null;
        }
        IItemHandler handler = getItemHandlerForExtension(side);
        if (side == null || handler == null) {
            return handler;
        }
        return directFaceItemHandlers[side.get3DDataValue()];
    }

    /** Bypasses only the direct-face takeover guard for the bound extension proxy. */
    public IItemHandler getItemHandlerForExtension(Direction side) {
        if (bindingConflict || (terminalBlueprintOnly && !blueprintApplied)) {
            return null;
        }
        if (side == null) {
            return UNSIDED_ITEM_HANDLER_PROBE;
        }
        if (faceModes[side.get3DDataValue()] == PortMode.NONE) {
            return null;
        }
        if (faceModes[side.get3DDataValue()] == PortMode.INPUT
                && operationMode == OperationMode.BLACKBOX_PREPARING) {
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
        if (side != null && isFaceTakenOver(side)) {
            return false;
        }
        return acceptUnpackedItemsFromExtension(side, stacks, simulate);
    }

    /** Package entry point used by the identity-bound extension after its own guards pass. */
    public boolean acceptUnpackedItemsFromExtension(Direction side, List<ItemStack> stacks, boolean simulate) {
        if (bindingConflict || (terminalBlueprintOnly && !blueprintApplied)
                || side == null || stacks == null || stacks.isEmpty()) {
            return false;
        }
        int faceIndex = side.get3DDataValue();
        if (faceModes[faceIndex] != PortMode.INPUT || operationMode == OperationMode.BLACKBOX_PREPARING) {
            return false;
        }

        if (isSimulatedMode()) {
            FactoryPlan effectivePlan = effectiveRecipe();
            boolean accepted = runtimeScheduler.acceptItemInputs(effectivePlan, stacks, simulate);
            if (accepted && !simulate) {
                for (ItemStack stack : stacks) {
                    recordItemTelemetry(true, stack, stack == null ? 0 : stack.getCount());
                }
                setChanged();
                blackboxTrace("runtime_item_package_input", () -> "port=" + portIds[faceIndex]
                        + ", stacks=" + stacks + ", runtime=" + runtimeScheduler.debugSummary());
            }
            return accepted;
        }

        int portId = portIds[faceIndex];
        if (!canAcceptInput(portId, false)) {
            return false;
        }
        FactoryTransit.PortResourceChannel resourceChannel = portChannel(portId);
        if (simulate) {
            return resourceChannel.canAcceptInputItemBatch(stacks);
        }

        boolean wasEmpty = resourceChannel.inputItems().isEmpty();
        if (!resourceChannel.insertInputItemBatch(stacks)) {
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
        FactoryTransit.PortResourceChannel resourceChannel = portChannel(portId);
        if (simulate) {
            return resourceChannel.canAcceptOutputItemBatch(stacks);
        }
        if (!resourceChannel.insertOutputItemBatch(stacks)) {
            return false;
        }
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                recordItemTransfer(portId, false, stack, stack.getCount());
            }
        }
        setChanged();
        invalidateResourceCapabilities();
        return true;
    }

    public IFluidHandler getFluidHandler(Direction side) {
        if (side != null && isFaceTakenOver(side)) {
            return null;
        }
        IFluidHandler handler = getFluidHandlerForExtension(side);
        if (side == null || handler == null) {
            return handler;
        }
        return directFaceFluidHandlers[side.get3DDataValue()];
    }

    /** Bypasses only the direct-face takeover guard for the bound extension proxy. */
    public IFluidHandler getFluidHandlerForExtension(Direction side) {
        if (bindingConflict || (terminalBlueprintOnly && !blueprintApplied)
                || side == null || faceModes[side.get3DDataValue()] == PortMode.NONE) {
            return null;
        }
        if (faceModes[side.get3DDataValue()] == PortMode.INPUT
                && operationMode == OperationMode.BLACKBOX_PREPARING) {
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
        for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), roomOrigin(), portId)) {
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
        for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), roomOrigin(), portId)) {
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
        if (!hasPhysicalRoom()) return;
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        for (int portId = 1; portId <= 6; portId++) {
            for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), roomOrigin(), portId)) {
                pocket.invalidateCapabilities(portPos);
            }
        }
    }

    public boolean hasPendingPortResources() {
        return !factoryTransit.isEmpty();
    }

    /** Drops pending item transit resources at this factory and destroys pending fluid transit resources. */
    public void dropPendingPortItemsAndDiscardFluids() {
        if (level == null || level.isClientSide()) {
            return;
        }
        for (ItemStack stack : factoryTransit.drainItemsAndDiscardFluids()) {
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
        for (Direction face : Direction.values()) {
            if (!worldPosition.relative(face).equals(neighborPos)) {
                continue;
            }
            int index = face.get3DDataValue();
            Block current = level.getBlockState(neighborPos).getBlock();
            if (externalItemOutputConsumers[index] != null && externalItemOutputConsumers[index] != current) {
                externalItemOutputConsumers[index] = null;
            }
            if (externalFluidOutputConsumers[index] != null && externalFluidOutputConsumers[index] != current) {
                externalFluidOutputConsumers[index] = null;
            }
            break;
        }
        // Capability consumers cache both handlers and null results. Always invalidate the
        // boundary when an adjacent block changes so adding/removing an extension restores the
        // direct face immediately in every port mode.
        invalidateResourceCapabilities();
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
        for (BlockPos pos : PocketRegistry.getPorts(pocket.getServer(), roomOrigin(), portId)) {
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
        for (BlockPos pos : PocketRegistry.getPorts(pocket.getServer(), roomOrigin(), portId)) {
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
        if (operationMode == OperationMode.BLACKBOX_PREPARING || isSimulatedMode()
                || !hasRoomPort(portId)) {
            return false;
        }
        if (fluid) {
            return true;
        }
        return hasRoomInputConsumer(portId, false)
                && portChannel(portId).canAcceptInputItems();
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
        return portChannel(portId).canAcceptOutputItems();
    }

    private FactoryTransit.PortResourceChannel portChannel(int portId) {
        return factoryTransit.channel(portId);
    }

    private int connectedExternalFluidFaceCount(int portId) {
        if (level == null || level.isClientSide()) {
            return 0;
        }
        int count = 0;
        for (Direction face : getFacesForPortId(portId)) {
            for (ExternalAccessPoint access : getExternalAccessPoints(face)) {
                BlockPos adjacentPos = access.origin().relative(access.face());
                BlockState state = level.getBlockState(adjacentPos);
                if (level.getCapability(Capabilities.FluidHandler.BLOCK,
                        adjacentPos, access.face().getOpposite()) != null
                        || (FluidPropagator.getPipe(level, adjacentPos) != null
                        && FluidPropagator.getPipe(level, adjacentPos)
                        .canHaveFlowToward(state, access.face().getOpposite()))
                        || FluidNetworkEndpointResolver.hasEndpoint(level,
                        access.origin(), access.face(), portId)) {
                    count++;
                }
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
            for (ExternalAccessPoint access : getExternalAccessPoints(face)) {
                BlockPos adjacentPos = access.origin().relative(access.face());
                IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK,
                        adjacentPos, access.face().getOpposite());
                if (handler != null && seen.add(handler)) {
                    handlers.add(handler);
                }
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
        for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), roomOrigin(), portId)) {
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
            for (ExternalAccessPoint access : getExternalAccessPoints(face)) {
                for (IFluidHandler handler : FluidNetworkEndpointResolver.find(level,
                        access.origin(), access.face(), request, operation)) {
                    if (seen.add(handler)) {
                        result.add(handler);
                    }
                }
            }
        }
        return result;
    }

    private List<IFluidHandler> resolveExternalFluidHandlers(int portId, int maxDrain) {
        List<IFluidHandler> result = new ArrayList<>();
        Set<IFluidHandler> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction face : getFacesForPortId(portId)) {
            for (ExternalAccessPoint access : getExternalAccessPoints(face)) {
                for (IFluidHandler handler : FluidNetworkEndpointResolver.findDrain(level,
                        access.origin(), access.face(), maxDrain)) {
                    if (seen.add(handler)) {
                        result.add(handler);
                    }
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
        for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), roomOrigin(), portId)) {
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
        for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), roomOrigin(), portId)) {
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
        if (!resolveRoomFluidHandlers(portId, resource,
                FluidNetworkEndpointResolver.Operation.FILL).isEmpty()) {
            return true;
        }
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return false;
        }
        for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            for (Direction side : Direction.values()) {
                if (FluidNetworkEndpointResolver.hasEndpoint(pocket, portPos, side, portId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canReachExternalFluidDestination(int portId, FluidStack resource) {
        if (!resolveExternalFluidHandlers(portId, resource,
                FluidNetworkEndpointResolver.Operation.FILL).isEmpty()) {
            return true;
        }
        for (Direction face : getFacesForPortId(portId)) {
            for (ExternalAccessPoint access : getExternalAccessPoints(face)) {
                if (FluidNetworkEndpointResolver.hasEndpoint(level,
                        access.origin(), access.face(), portId)) {
                    return true;
                }
            }
        }
        return false;
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
        if (isSimulatedMode()) {
            blackboxDebug("port_contract_change_ignored", () -> "reason=shared_simulated_io, runtime="
                    + runtimeScheduler.debugSummary());
            invalidateResourceCapabilities();
            setChanged();
            return;
        }
        blackboxDebug("port_contract_change", () -> "runtime=" + runtimeScheduler.debugSummary()
                + ", transit=" + factoryTransit.debugSummary());
        invalidateProductionBatch(player, "port_routing_changed");
        invalidatePlanForPortContractChange(player);
        destroyTransitResources(player, "port_routing_changed");
        roomFluidNetworkSignatures.clear();
        externalFluidNetworkSignatures.clear();
        setChanged();
    }

    private void invalidatePlanForPortContractChange(Player player) {
        if (operationMode == OperationMode.BLACKBOX_LEARNING
                || operationMode == OperationMode.BLACKBOX_PREPARING) {
            abortLearning("port_contract_changed");
        } else if (operationMode == OperationMode.BLUEPRINT) {
            cancelBlueprint(player, OperationMode.CHUNK_LOADED);
        } else if (operationMode == OperationMode.BLACKBOX_ACTIVE) {
            stopBlackbox(player);
        }
        plan = new FactoryPlan();
        invalidateOverclockRecipe();
    }

    public boolean canChangePortRouting(Player player) {
        if (isSimulatedMode()) return true;
        if (runtimeScheduler.isIdle()) return true;
        if (player instanceof ServerPlayer serverPlayer) {
            PlayerMessagePayload.sendTo(serverPlayer,
                    Component.translatable("message.create_nested_factory.production_transaction.active")
                            .withStyle(ChatFormatting.RED), false);
        }
        return false;
    }

    private void destroyTransitResources(Player player, String reason) {
        String before = factoryTransit.debugSummary();
        FactoryTransit.DestructionReport report = factoryTransit.destroyAll();
        if (report.isEmpty()) return;
        blackboxDebug("transit_destroyed", () -> "reason=" + reason + ", before=" + before
                + ", items=" + report.itemCount() + ", fluids=" + report.fluidAmount()
                + ", extensions=" + report.extensionCount());
        setChanged();
        LOGGER.warn("Destroyed factory transit at {} because {}: {} items, {} mB fluid, {} extensions",
                worldPosition, reason, report.itemCount(), report.fluidAmount(), report.extensionCount());
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
            NestedExtensionInterfaceBlockEntity extension = getExtensionForFace(face);
            if (extension != null) {
                FluidPortPressure pressure = extension.getExternalFluidPressure();
                towardFactory = Math.max(towardFactory, pressure.towardFactory());
                awayFromFactory = Math.max(awayFromFactory, pressure.awayFromFactory());
                continue;
            }
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
            NestedExtensionInterfaceBlockEntity extension = getExtensionForFace(face);
            applied |= extension == null
                    ? FluidPressureBridge.apply(level, worldPosition, face, pull, pressure)
                    : extension.applyExternalFluidPressure(pull, pressure);
        }
        return applied;
    }

    public record FluidPortPressure(float towardFactory, float awayFromFactory) {
        public static final FluidPortPressure NONE = new FluidPortPressure(0f, 0f);
    }

    private boolean isSimulatedMode() {
        return operationMode == OperationMode.BLACKBOX_ACTIVE || operationMode == OperationMode.BLUEPRINT;
    }

    /**
     * Simulated execution without a persisted spatial manifest is an unsupported legacy state.
     * Fail back to the physical room instead of allowing virtual and physical production together.
     */
    private boolean ensureSimulatedFreezeManifest() {
        if (!isSimulatedMode()) return true;
        if (PocketFreezeManager.ensurePersistentLease(this)) return true;
        appendDebugLoadRepair("simulated_mode_cleared_missing_freeze_manifest");
        invalidateProductionBatch(null, "missing_freeze_manifest");
        destroyTransitResources(null, "missing_freeze_manifest");
        runtimeScheduler.clear();
        transitionOperationMode(FactoryLifecycleTransitions.Event.REPAIR_MISSING_FREEZE_MANIFEST);
        blueprintApplied = false;
        appliedBlueprint = null;
        preBlueprintSnapshot = null;
        MinecraftServer server = level == null ? null : level.getServer();
        if (server != null) NestedFactorySaveData.get(server).releaseFreezeLease(factoryId);
        setChanged();
        return false;
    }

    private void recordItemTransfer(int portId, boolean inputFlow, ItemStack stack, int moved) {
        recordItemTelemetry(inputFlow, stack, moved);
        learning.recordItemBoundary(inputFlow, stack, moved, level == null ? -1L : level.getGameTime());
        if (moved > 0) {
            if (operationMode == OperationMode.BLACKBOX_LEARNING) {
                blackboxTrace("learning_item_boundary", () -> "direction=" + (inputFlow ? "input" : "output")
                        + ", port=" + portId + ", item=" + ItemVariant.of(stack) + ", amount=" + moved);
            }
        }
    }

    private void recordFluidTransfer(int portId, boolean inputFlow, FluidStack stack, int moved) {
        recordFluidTelemetry(inputFlow, stack, moved);
        learning.recordFluidBoundary(inputFlow, stack, moved, level == null ? -1L : level.getGameTime());
        if (moved > 0) {
            if (operationMode == OperationMode.BLACKBOX_LEARNING) {
                blackboxTrace("learning_fluid_boundary", () -> "direction=" + (inputFlow ? "input" : "output")
                        + ", port=" + portId + ", fluid=" + FluidVariant.of(stack) + ", amount=" + moved);
            }
        }
    }

    private List<RuntimeToolSlot> blackboxToolSlots(FactoryPlan effectivePlan) {
        List<RuntimeToolSlot> slots = new ArrayList<>();
        for (FactoryRuntimeScheduler.ScheduledRoute route : runtimeScheduler.scheduledRoutes(effectivePlan)) {
            for (ItemVariant signature : route.runtime().sortedRequiredTools(route.plan())) {
                slots.add(new RuntimeToolSlot(route.runtime(), route.plan(), signature));
            }
        }
        return slots;
    }

    private List<ItemVariant> blackboxOutputItems(FactoryPlan effectivePlan) {
        Set<ItemVariant> variants = new java.util.TreeSet<>(effectivePlan.getRecipeOutputs().keySet());
        variants.addAll(factoryTransit.outputItemVariants());
        return List.copyOf(variants);
    }

    private long blackboxRemainingItemOutput(ItemVariant variant) {
        return Math.addExact(factoryTransit.outputItemAmount(variant),
                runtimeScheduler.remainingItemOutput(effectiveRecipe(), variant));
    }

    private ItemStack extractBlackboxItemOutput(ItemVariant variant, int amount, boolean simulate) {
        int requested = Math.min(Math.max(0, amount), variant.prototype().getMaxStackSize());
        ItemStack first = factoryTransit.extractOutputItem(variant, requested, simulate);
        int remaining = Math.max(0, requested - first.getCount());
        ItemStack second = remaining == 0 ? ItemStack.EMPTY
                : runtimeScheduler.extractItemOutput(effectiveRecipe(), variant, remaining, simulate);
        if (first.isEmpty()) first = second;
        else if (!second.isEmpty()) first.grow(second.getCount());
        return first;
    }

    private List<FluidVariant> blackboxOutputFluids(FactoryPlan effectivePlan) {
        Set<FluidVariant> variants = new java.util.TreeSet<>(effectivePlan.getRecipeOutputFluids().keySet());
        variants.addAll(factoryTransit.outputFluidVariants());
        return List.copyOf(variants);
    }

    private long blackboxRemainingFluidOutput(FluidVariant variant) {
        return Math.addExact(factoryTransit.outputFluidAmount(variant),
                runtimeScheduler.remainingFluidOutput(effectiveRecipe(), variant));
    }

    private FluidStack drainBlackboxFluidOutput(FluidVariant variant, int amount, boolean simulate) {
        FluidStack first = factoryTransit.extractOutputFluid(variant, amount,
                simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
        int remaining = Math.max(0, amount - first.getAmount());
        FluidStack second = remaining == 0 ? FluidStack.EMPTY
                : runtimeScheduler.extractFluidOutput(effectiveRecipe(), variant, remaining, simulate);
        return mergeFluidResults(first, second);
    }

    /** Keeps a previously cached direct-face item capability from bypassing a later takeover. */
    private final class DirectFactoryFaceItemProxy implements IItemHandler {
        private final int faceIndex;

        private DirectFactoryFaceItemProxy(int faceIndex) {
            this.faceIndex = faceIndex;
        }

        private IItemHandler delegate() {
            Direction face = Direction.from3DDataValue(faceIndex);
            return isFaceTakenOver(face) ? null : faceItemHandlers[faceIndex];
        }

        @Override
        public int getSlots() {
            IItemHandler delegate = delegate();
            return delegate == null ? 0 : delegate.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            IItemHandler delegate = delegate();
            return delegate == null ? ItemStack.EMPTY : delegate.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            IItemHandler delegate = delegate();
            return delegate == null ? stack : delegate.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            IItemHandler delegate = delegate();
            return delegate == null ? ItemStack.EMPTY : delegate.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            IItemHandler delegate = delegate();
            return delegate == null ? 0 : delegate.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            IItemHandler delegate = delegate();
            return delegate != null && delegate.isItemValid(slot, stack);
        }
    }

    /** Keeps a previously cached direct-face fluid capability from bypassing a later takeover. */
    private final class DirectFactoryFaceFluidProxy implements IFluidHandler {
        private final int faceIndex;

        private DirectFactoryFaceFluidProxy(int faceIndex) {
            this.faceIndex = faceIndex;
        }

        private IFluidHandler delegate() {
            Direction face = Direction.from3DDataValue(faceIndex);
            return isFaceTakenOver(face) ? null : faceFluidHandlers[faceIndex];
        }

        @Override
        public int getTanks() {
            IFluidHandler delegate = delegate();
            return delegate == null ? 0 : delegate.getTanks();
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            IFluidHandler delegate = delegate();
            return delegate == null ? FluidStack.EMPTY : delegate.getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(int tank) {
            IFluidHandler delegate = delegate();
            return delegate == null ? 0 : delegate.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            IFluidHandler delegate = delegate();
            return delegate != null && delegate.isFluidValid(tank, stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            IFluidHandler delegate = delegate();
            return delegate == null ? 0 : delegate.fill(resource, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            IFluidHandler delegate = delegate();
            return delegate == null ? FluidStack.EMPTY : delegate.drain(resource, action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            IFluidHandler delegate = delegate();
            return delegate == null ? FluidStack.EMPTY : delegate.drain(maxDrain, action);
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
                FactoryPlan effectivePlan = effectiveRecipe();
                return mode() == PortMode.INPUT
                        ? blueprintCapitalItems(effectivePlan).size()
                        + runtimeScheduler.sortedInputItems(effectivePlan).size()
                        + blackboxToolSlots(effectivePlan).size()
                        : blackboxOutputItems(effectivePlan).size();
            }
            if (mode() == PortMode.INPUT) {
                return canAcceptInput(portId(), false) ? 1 : 0;
            }
            return hasRoomPort(portId()) ? portChannel(portId()).outputItems().slots() : 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (mode() == PortMode.INPUT && isSimulatedMode()) {
                FactoryPlan effectivePlan = effectiveRecipe();
                List<ItemVariant> capital = blueprintCapitalItems(effectivePlan);
                if (slot >= 0 && slot < capital.size()) {
                    ItemVariant item = capital.get(slot);
                    long committed = runtimeScheduler.capitalAmount(item);
                    return committed <= 0L ? ItemStack.EMPTY : item.createStack((int) Math.min(committed,
                            item.prototype().getMaxStackSize()));
                }
                List<ItemVariant> inputs = runtimeScheduler.sortedInputItems(effectivePlan);
                int inputIndex = slot - capital.size();
                if (inputIndex >= 0 && inputIndex < inputs.size()) {
                    ItemVariant item = inputs.get(inputIndex);
                    long committed = runtimeScheduler.committedItem(effectivePlan, item);
                    return committed <= 0 ? ItemStack.EMPTY : item.createStack((int) Math.min(committed,
                            item.prototype().getMaxStackSize()));
                }
                List<RuntimeToolSlot> tools = blackboxToolSlots(effectivePlan);
                int toolIndex = inputIndex - inputs.size();
                return toolIndex < 0 || toolIndex >= tools.size() ? ItemStack.EMPTY
                        : tools.get(toolIndex).runtime().leasedTool(tools.get(toolIndex).signature());
            }
            if (mode() == PortMode.OUTPUT && isSimulatedMode()) {
                List<ItemVariant> items = blackboxOutputItems(effectiveRecipe());
                if (slot >= 0 && slot < items.size()) {
                    ItemVariant item = items.get(slot);
                    long remaining = blackboxRemainingItemOutput(item);
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
            if (mode() != PortMode.INPUT || operationMode == OperationMode.BLACKBOX_PREPARING) {
                return stack;
            }
            if (isSimulatedMode()) {
                FactoryPlan effectivePlan = effectiveRecipe();
                List<ItemVariant> capital = blueprintCapitalItems(effectivePlan);
                if (slot >= 0 && slot < capital.size()) {
                    if (!capital.get(slot).matches(stack)) return stack;
                    int accepted = runtimeScheduler.acceptCapital(effectivePlan, stack, simulate);
                    if (!simulate && accepted > 0) {
                        recordItemTelemetry(true, stack, accepted);
                        setChanged();
                        blackboxDebug("runtime_startup_capital", () -> "port=" + portId()
                                + ", item=" + ItemVariant.of(stack) + ", amount=" + accepted
                                + ", runtime=" + runtimeScheduler.debugSummary());
                    }
                    ItemStack remainder = stack.copy();
                    remainder.shrink(accepted);
                    return remainder;
                }
                List<ItemVariant> items = runtimeScheduler.sortedInputItems(effectivePlan);
                int inputIndex = slot - capital.size();
                if (inputIndex < 0) {
                    return stack;
                }
                int accepted;
                if (inputIndex < items.size()) {
                    if (!items.get(inputIndex).matches(stack)) return stack;
                    accepted = runtimeScheduler.acceptItemInput(effectivePlan, stack, simulate);
                } else {
                    List<RuntimeToolSlot> tools = blackboxToolSlots(effectivePlan);
                    int toolIndex = inputIndex - items.size();
                    if (toolIndex < 0 || toolIndex >= tools.size()
                            || !tools.get(toolIndex).signature().matchesTool(stack)) {
                        return stack;
                    }
                    RuntimeToolSlot tool = tools.get(toolIndex);
                    accepted = tool.runtime().acceptTool(tool.route(), stack, simulate);
                }
                if (!simulate && accepted > 0) {
                    recordItemTelemetry(true, stack, accepted);
                    setChanged();
                    blackboxTrace("runtime_item_input", () -> "port=" + portId()
                            + ", slot=" + slot + ", item=" + ItemVariant.of(stack)
                            + ", amount=" + accepted + ", runtime=" + runtimeScheduler.debugSummary());
                }
                ItemStack remainder = stack.copy();
                remainder.shrink(accepted);
                return remainder;
            }
            if (!canAcceptInput(portId(), false)) {
                return stack;
            }
            FactoryTransit.PortResourceChannel resourceChannel = portChannel(portId());
            int offerLimit = resourceChannel.inputItemOfferLimit(stack);
            if (offerLimit <= 0) {
                return stack;
            }
            FactoryTransit.ItemChannel channel = resourceChannel.inputItems();
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
                FactoryPlan effectivePlan = effectiveRecipe();
                List<ItemVariant> items = blackboxOutputItems(effectivePlan);
                if (slot < 0 || slot >= items.size()) {
                    return ItemStack.EMPTY;
                }
                ItemStack result = extractBlackboxItemOutput(items.get(slot), amount, simulate);
                if (!simulate && !result.isEmpty()) {
                    recordItemTelemetry(false, result, result.getCount());
                    setChanged();
                    blackboxTrace("runtime_item_output", () -> "port=" + portId()
                            + ", slot=" + slot + ", item=" + ItemVariant.of(result)
                            + ", amount=" + result.getCount() + ", runtime=" + runtimeScheduler.debugSummary());
                }
                return result;
            }
            if (!hasRoomPort(portId())) {
                return ItemStack.EMPTY;
            }
            FactoryTransit.PortResourceChannel resourceChannel = portChannel(portId());
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
            if (mode() != PortMode.INPUT || operationMode == OperationMode.BLACKBOX_PREPARING) {
                return false;
            }
            if (!isSimulatedMode()) {
                return canAcceptInput(portId(), false);
            }
            FactoryPlan effectivePlan = effectiveRecipe();
            List<ItemVariant> capital = blueprintCapitalItems(effectivePlan);
            if (slot >= 0 && slot < capital.size()) {
                return capital.get(slot).matches(stack);
            }
            List<ItemVariant> items = runtimeScheduler.sortedInputItems(effectivePlan);
            int inputIndex = slot - capital.size();
            if (inputIndex >= 0 && inputIndex < items.size()) {
                return items.get(inputIndex).matches(stack);
            }
            List<RuntimeToolSlot> tools = blackboxToolSlots(effectivePlan);
            int toolIndex = inputIndex - items.size();
            return toolIndex >= 0 && toolIndex < tools.size()
                    && tools.get(toolIndex).signature().matchesTool(stack);
        }
    }

    private List<ItemVariant> blueprintCapitalItems(FactoryPlan effectivePlan) {
        return operationMode == OperationMode.BLUEPRINT
                ? runtimeScheduler.sortedCapitalRequirements(effectivePlan) : List.of();
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
            FactoryTransit.PortResourceChannel resourceChannel = portChannel(portId);
            int offerLimit = resourceChannel.outputItemOfferLimit(stack);
            if (offerLimit <= 0) {
                return stack;
            }
            FactoryTransit.ItemChannel channel = resourceChannel.outputItems();
            boolean wasEmpty = channel.isEmpty();
            ItemStack offered = stack.copyWithCount(offerLimit);
            ItemStack offeredRemaining = channel.insert(offered, simulate);
            int moved = offerLimit - offeredRemaining.getCount();
            ItemStack remaining = stack.copy();
            remaining.shrink(moved);
            if (!simulate && moved > 0) {
                resourceChannel.consumeOutputItems(moved);
                recordItemTransfer(portId, false, stack, moved);
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
            FactoryTransit.PortResourceChannel resourceChannel = portChannel(portId);
            if (mode() == PortMode.INPUT) {
                ItemStack result = resourceChannel.inputItems().extract(slot, amount, simulate);
                if (!simulate && !result.isEmpty()) {
                    resourceChannel.markInputItemExtracted(result.getCount());
                    recordItemTransfer(portId, true, result, result.getCount());
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
                FactoryPlan effectivePlan = effectiveRecipe();
                return mode() == PortMode.INPUT
                        ? runtimeScheduler.sortedInputFluids(effectivePlan).size()
                        : blackboxOutputFluids(effectivePlan).size();
            }
            return hasRoomPort(portId()) ? (mode() == PortMode.INPUT
                    ? 1 : Math.max(1, portChannel(portId()).outputFluids().tanks())) : 0;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            if (isSimulatedMode()) {
                FactoryPlan effectivePlan = effectiveRecipe();
                List<FluidVariant> fluids = mode() == PortMode.INPUT
                        ? runtimeScheduler.sortedInputFluids(effectivePlan)
                        : blackboxOutputFluids(effectivePlan);
                if (tank < 0 || tank >= fluids.size()) {
                    return FluidStack.EMPTY;
                }
                FluidVariant fluid = fluids.get(tank);
                long amount = mode() == PortMode.INPUT
                        ? runtimeScheduler.committedFluid(effectivePlan, fluid)
                        : blackboxRemainingFluidOutput(fluid);
                return amount <= 0 ? FluidStack.EMPTY
                        : fluid.createStack((int) Math.min(amount, Integer.MAX_VALUE));
            }
            return mode() == PortMode.OUTPUT && hasRoomPort(portId()) && tank >= 0
                    ? portChannel(portId()).outputFluids().fluidInTank(tank) : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            if (isSimulatedMode()) {
                FactoryPlan effectivePlan = effectiveRecipe();
                List<FluidVariant> fluids = mode() == PortMode.INPUT
                        ? runtimeScheduler.sortedInputFluids(effectivePlan)
                        : blackboxOutputFluids(effectivePlan);
                if (tank < 0 || tank >= fluids.size()) {
                    return 0;
                }
                FluidVariant fluid = fluids.get(tank);
                long capacity = mode() == PortMode.INPUT
                        ? saturatingRuntimeCapacityAdd(
                        effectivePlan.getRecipeInputFluids().getOrDefault(fluid, 0L),
                        effectivePlan.getRecipeInputFluids().getOrDefault(fluid, 0L))
                        : simulatedOutputFluidCapacity(effectivePlan, fluid);
                return (int) Math.min(capacity, Integer.MAX_VALUE);
            }
            return hasRoomPort(portId()) && tank == 0 ? Integer.MAX_VALUE : 0;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            if (mode() != PortMode.INPUT || operationMode == OperationMode.BLACKBOX_PREPARING) {
                return false;
            }
            if (!isSimulatedMode()) {
                return hasRoomPort(portId()) && stack != null && !stack.isEmpty();
            }
            FactoryPlan effectivePlan = effectiveRecipe();
            List<FluidVariant> fluids = runtimeScheduler.sortedInputFluids(effectivePlan);
            return tank >= 0 && tank < fluids.size() && fluids.get(tank).matches(stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (mode() != PortMode.INPUT || operationMode == OperationMode.BLACKBOX_PREPARING
                    || resource == null || resource.isEmpty()) {
                return 0;
            }
            if (isSimulatedMode()) {
                FactoryPlan effectivePlan = effectiveRecipe();
                int accepted = runtimeScheduler.acceptFluidInput(effectivePlan, resource, action.simulate());
                if (action.execute() && accepted > 0) {
                    recordFluidTelemetry(true, resource, accepted);
                    setChanged();
                    blackboxTrace("runtime_fluid_input", () -> "port=" + portId()
                            + ", fluid=" + FluidVariant.of(resource) + ", amount=" + accepted
                            + ", runtime=" + runtimeScheduler.debugSummary());
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
            if (action.execute() && direct > 0) {
                // A direct push has already crossed into a real room consumer. Buffered fluid is
                // recorded later when the room drains it, so only the direct portion belongs here.
                recordFluidTransfer(portId(), true, offered, direct);
                setChanged();
            }
            int remaining = offered.getAmount() - direct;
            if (remaining > 0 && !canReachRoomFluidDestination(portId(), offered.copyWithAmount(remaining))) {
                return direct;
            }
            int buffered = remaining <= 0 ? 0
                    : portChannel(portId()).fillInputFluids(offered.copyWithAmount(remaining), action);
            int moved = direct + buffered;
            if (action.execute() && buffered > 0) setChanged();
            return moved;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            if (mode() != PortMode.OUTPUT || resource == null || resource.isEmpty()) {
                return FluidStack.EMPTY;
            }
            if (isSimulatedMode()) {
                FluidStack result = drainBlackboxFluidOutput(
                        FluidVariant.of(resource), resource.getAmount(), action.simulate());
                if (action.execute() && !result.isEmpty()) {
                    recordFluidTelemetry(false, result, result.getAmount());
                    setChanged();
                    blackboxTrace("runtime_fluid_output", () -> "port=" + portId()
                            + ", fluid=" + FluidVariant.of(result) + ", amount=" + result.getAmount()
                            + ", runtime=" + runtimeScheduler.debugSummary());
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
                recordFluidTransfer(portId(), false, room, room.getAmount());
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
                for (FluidVariant fluid : blackboxOutputFluids(effectiveRecipe())) {
                    FluidStack result = drainBlackboxFluidOutput(fluid, maxDrain, action.simulate());
                    if (!result.isEmpty()) {
                        if (action.execute()) {
                            recordFluidTelemetry(false, result, result.getAmount());
                            setChanged();
                            blackboxTrace("runtime_fluid_output", () -> "port=" + portId()
                                    + ", fluid=" + fluid + ", amount=" + result.getAmount()
                                    + ", runtime=" + runtimeScheduler.debugSummary());
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
                recordFluidTransfer(portId(), false, room, room.getAmount());
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
                recordFluidTransfer(portId, false, resource, moved);
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
            if (action.execute() && !result.isEmpty()) {
                recordFluidTransfer(portId, true, result, result.getAmount());
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
            if (action.execute() && !result.isEmpty()) {
                recordFluidTransfer(portId, true, result, result.getAmount());
                setChanged();
            }
            return result;
        }
    }

    private ServerLevel pocketLevel() {
        if (terminalBlueprintOnly || level == null || level.isClientSide()) {
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
        bindingConflict = false;
        nested = false;
        enterable = true;
        invalidNested = false;
        terminalBlueprintOnly = false;
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

        BlockPos persistedOrigin = rootRoomAllocated ? rootRoomOrigin : null;
        int persistedSlotId = rootRoomAllocated ? rootSlotId : -1;

        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        NestedFactorySaveData.RootAllocation allocation = NestedFactorySaveData.get(server)
                .claimRootAllocation(factoryId, persistedSlotId, persistedOrigin);
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
        bindingConflict = false;
        terminalBlueprintOnly = false;
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

        if (portableBinding && (parentFactoryId.isBlank() || !parentFactoryId.equals(parent.getFactoryId()))) {
            enterable = false;
            invalidNested = true;
            return;
        }

        parentFactoryId = parent.getFactoryId();
        parentFactoryPos = parent.getBlockPos().immutable();
        parentDimension = parent.level.dimension();
        nestingDepth = parent.getNestingDepth() + 1;
        rootFactoryId = parent.isRoot() ? parent.getFactoryId() : parent.getRootFactoryId();

        boolean buildable = parent.getBounds().isBuildableAt(parent.roomOrigin(), worldPosition);
        boolean noSibling = !parent.hasRecordedChild() || factoryId.equals(parent.getChildFactoryId());
        NestedFactoryPlacementRules.Kind placementKind = NestedFactoryPlacementRules.classify(
                nestingDepth, Config.maxNestingDepth, buildable, noSibling);
        if (placementKind == NestedFactoryPlacementRules.Kind.INVALID) {
            enterable = false;
            invalidNested = true;
            nestedSlotId = -1;
            nestedRoomOrigin = BlockPos.ZERO;
            return;
        }
        if (placementKind == NestedFactoryPlacementRules.Kind.TERMINAL_BLUEPRINT_ONLY) {
            enterable = false;
            invalidNested = false;
            terminalBlueprintOnly = true;
            nestedSlotId = -1;
            nestedSlotX = 0;
            nestedSlotZ = 0;
            nestedRoomOrigin = BlockPos.ZERO;
            parent.setChildFactory(this);
            return;
        }

        PocketRegistry.FactoryLocation location =
                new PocketRegistry.FactoryLocation(factoryId, level.dimension(), worldPosition);
        PocketRegistry.NestedSlot slot = portableBinding && nestedSlotId >= 0
                ? PocketRegistry.registerNestedSlot(nestedSlotId, location, (ServerLevel) level)
                : PocketRegistry.allocateAndRegisterNestedSlot(location, (ServerLevel) level);
        if (slot == null) {
            enterable = false;
            invalidNested = true;
            return;
        }
        nestedSlotId = slot.id();
        nestedSlotX = slot.slotX();
        nestedSlotZ = slot.slotZ();
        nestedRoomOrigin = NestedFactoryBlock.getNestedRoomOrigin(nestedSlotX, nestedSlotZ);
        enterable = true;
        invalidNested = false;
        terminalBlueprintOnly = false;
        parent.setChildFactory(this);
    }

    private boolean registerFactoryState() {
        if (level == null || level.isClientSide()) {
            return false;
        }
        PocketRegistry.FactoryLocation location = new PocketRegistry.FactoryLocation(factoryId, level.dimension(), worldPosition);
        if (nested) {
            if (enterable && !invalidNested && nestedSlotId >= 0) {
                if (PocketRegistry.registerNestedSlot(nestedSlotId, location, (ServerLevel) level) == null) {
                    bindingConflict = true;
                    return false;
                }
            }
            return true;
        }
        if (!rootRoomAllocated || PocketRegistry.registerRoot(level.getServer(), roomOrigin(), location)) {
            return rootRoomAllocated;
        }
        bindingConflict = true;
        return false;
    }

    private void unregisterFactoryState() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (nested) {
            if (nestedSlotId >= 0) {
                PocketRegistry.unregisterNestedSlot(level.getServer(), nestedSlotId,
                        new PocketRegistry.FactoryLocation(factoryId, level.dimension(), worldPosition));
            }
            clearChildFromParent();
        } else if (rootRoomAllocated) {
            PocketRegistry.unregisterRoot(level.getServer(), roomOrigin(),
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

    private void refreshChildFactoryBinding() {
        NestedFactoryBlockEntity child = getChildFactoryEntity();
        if (child != null) {
            child.rebindParent(this);
        }
    }

    private void rebindParent(NestedFactoryBlockEntity parent) {
        parentFactoryId = parent.getFactoryId();
        parentFactoryPos = parent.getBlockPos().immutable();
        parentDimension = parent.level == null ? null : parent.level.dimension();
        nestingDepth = parent.getNestingDepth() + 1;
        rootFactoryId = parent.isRoot() ? parent.getFactoryId() : parent.getRootFactoryId();
        if (level != null && !level.isClientSide() && level.getServer() != null) {
            NestedFactorySaveData.get(level.getServer()).observeFactoryParent(factoryId, parentFactoryId);
        }
        setChanged();
    }

    public boolean requestRoomBuild() {
        if (!hasPhysicalRoom()) return false;
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
                NestedFactoryBlock.POCKET_DIMENSION, origin,
                FactoryRoomGeometry.room(bounds, origin), roomTaskReference());
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
            blackboxDebug("factory_loaded", () -> "plan={" + plan.debugSummary() + "}, runtime="
                    + runtimeScheduler.debugSummary() + ", transit=" + factoryTransit.debugSummary()
                    + ", power={" + powerProfile.debugSummary() + "}, ports=" + debugPortContract() + ", nested=" + nested
                    + ", ancestorFrozen=" + ancestorFrozen);
            if (debugInterruptedLearningOnLoad) {
                blackboxDebug("learning_interrupted_on_load", () -> "action=reset_to_chunk_loaded_and_restore_live_inputs");
                debugInterruptedLearningOnLoad = false;
            }
            if (!debugLoadRepair.isEmpty()) {
                String repairs = debugLoadRepair;
                blackboxDebug("save_state_repaired", () -> "actions=" + repairs);
                debugLoadRepair = "";
            }
            MinecraftServer server = level.getServer();
            if (server != null) {
                NestedFactorySaveData saveData = NestedFactorySaveData.get(server);
                saveData.observeFactoryParent(factoryId, nested ? parentFactoryId : "");
                if (!isSimulatedMode()) saveData.releaseFreezeLease(factoryId);
            }
            ensureSimulatedFreezeManifest();
            if (nested && PocketFreezeManager.hasPersistentLease(this)) ancestorFrozen = true;
            // A physical parent may have been restored while this descendant's chunk was unloaded.
            if (ancestorFrozen && !PocketFreezeManager.hasPersistentLease(this)
                    && !parentStillFreezesThisFactory()) {
                ancestorFrozen = false;
                setChanged();
            }
            learning.stopCompiler();
            if (!invalidNested && registerFactoryState()) {
                if (ancestorFrozen) {
                    releasePocketChunksImmediately();
                    return;
                }
                if (terminalBlueprintOnly) {
                    releasePocketChunksImmediately();
                    setChanged();
                    sendSync();
                    return;
                }
                if (isSimulatedMode()) {
                    releasePocketChunksImmediately();
                    setChanged();
                    sendSync();
                    return;
                }
                ensureRoomGenerated();
                if (usesRuntimeIndex()) rebuildRuntimeIndex(pocketLevel(), true);
                refreshChildFactoryBinding();
                chunkLeases.refreshForMode();
            } else if (blueprintApplied) {
                setChanged();
                sendSync();
            }
        }
    }

    @Override
    public void destroy() {
        PocketLearningObserver.unregister(this);
        super.destroy();
    }

    /**
     * Called by the optional Sable integration immediately before this block entity is
     * transferred into or out of a physical SubLevel.
     */
    public void prepareForSimulatedMove() {
        simulatedMoveInProgress = true;
        if (level != null && !level.isClientSide()) {
            blackboxDebug("simulated_move_preparing", () -> "runtime=" + runtimeScheduler.debugSummary()
                    + ", transit=" + factoryTransit.debugSummary());
            if (operationMode == OperationMode.BLACKBOX_PREPARING
                    || operationMode == OperationMode.BLACKBOX_LEARNING) {
                abortLearning("simulated_move_started");
            }
            clearExternalStressState();
            if (!isSimulatedMode()) clearStressRelay();
        }
    }

    /**
     * Rebuilds only transient registrations after a physical transfer. Persistent room
     * identity and production state remain untouched.
     */
    public void finishSimulatedMove() {
        try {
            if (level != null && !level.isClientSide()) {
                bindingConflict = false;
                if (!registerFactoryState()) {
                    bindingConflict = true;
                }
                blackboxDebug("simulated_move_finishing", () -> "bindingConflict=" + bindingConflict
                        + ", simulated=" + isSimulatedMode() + ", runtime=" + runtimeScheduler.debugSummary());
                markRuntimeIndexDirty();
                if (isSimulatedMode()) {
                    if (!ensureSimulatedFreezeManifest()) {
                        refreshChildFactoryBinding();
                        chunkLeases.refreshForMode();
                        setChanged();
                        sendSync();
                        return;
                    }
                    releasePocketChunksImmediately();
                    setChanged();
                    sendSync();
                    return;
                }
                refreshChildFactoryBinding();
                chunkLeases.refreshForMode();
                setChanged();
                sendSync();
            }
        } finally {
            simulatedMoveInProgress = false;
            blackboxDebug("simulated_move_finished", () -> "bindingConflict=" + bindingConflict
                    + ", simulated=" + isSimulatedMode());
        }
    }

    public boolean isSimulatedMoveInProgress() {
        return simulatedMoveInProgress;
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
        if (ancestorFrozen && !PocketFreezeManager.hasPersistentLease(this)
                && !parentStillFreezesThisFactory()) {
            ancestorFrozen = false;
            setChanged();
        }
        if (ancestorFrozen) {
            clearExternalStressState();
            releasePocketChunksImmediately();
            return;
        }
        if (bindingConflict) {
            clearExternalStressState();
            releasePocketChunksImmediately();
            return;
        }
        if (terminalBlueprintOnly && !blueprintApplied) {
            clearExternalStressState();
            releasePocketChunksImmediately();
            return;
        }
        if (invalidNested && !blueprintApplied) {
            clearExternalStressState();
            clearStressRelay();
            return;
        }
        if (operationMode == OperationMode.BLACKBOX_ACTIVE || operationMode == OperationMode.BLUEPRINT) {
            applySelectedOverclock();
        }
        if (operationMode == OperationMode.BLACKBOX_ACTIVE || operationMode == OperationMode.BLUEPRINT) {
            settleSimulatedStress();
        } else {
            settleLiveStressRelay();
        }
        if (usesRuntimeIndex()) {
            ServerLevel pocket = pocketLevel();
            ensureRuntimeIndex(pocket);
        }
        if (usesRuntimeIndex() && level.getGameTime() % 20 == 0) {
            refreshPowerSnapshot(pocketLevel(), new HashSet<>());
        }
        if (level.getGameTime() % 5 == 0 && !isSimulatedMode()) {
            for (int portId = 1; portId <= FactoryFacePortBindings.MAX_PORT_ID; portId++) {
                refreshExternalFluidNetworksIfSignatureChanged(portId);
            }
        }
        switch (operationMode) {
            case CHUNK_LOADED -> tickChunkLoaded();
            case BLACKBOX_PREPARING -> tickPreparing();
            case BLACKBOX_LEARNING -> tickLearning();
            case BLACKBOX_ACTIVE -> tickBlackbox();
            case BLUEPRINT -> tickBlueprint();
        }
        chunkLeases.tick();
        if (level.getGameTime() % 20 == 0) {
            sendData();
        }
    }

    private void clearStressRelay() {
        if (!hasPhysicalRoom()) return;
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        for (NestedStressPortBlockEntity stressPort : roomStressPorts(pocket)) {
            stressPort.clearStressAllocation();
        }
    }

    void clearStressRelayBeforeFreeze() {
        clearStressRelay();
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

    public Component requestSpaceDestruction(ServerPlayer player) {
        if (level == null || level.isClientSide() || player == null) {
            return Component.translatable("message.create_nested_factory.factory.destroy_failed");
        }
        if (isRoomMutationLocked()) {
            blackboxDebug("space_destruction_rejected", () -> "reason=room_mutation_locked");
            return Component.translatable("message.create_nested_factory.room_mutation.active");
        }
        if (hasPlayersInside()) {
            blackboxDebug("space_destruction_rejected", () -> "reason=players_inside");
            return Component.translatable("message.create_nested_factory.factory.players_prevent_destroy");
        }
        if (hasChildFactoryInRoom()) {
            blackboxDebug("space_destruction_rejected", () -> "reason=child_factory_present");
            return Component.translatable("message.create_nested_factory.factory.child_factory_prevents_destroy");
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            blackboxDebug("space_destruction_rejected", () -> "reason=server_unavailable");
            return Component.translatable("message.create_nested_factory.factory.destroy_failed");
        }
        if (terminalBlueprintOnly) {
            blackboxDebug("terminal_destruction_started", () -> "runtime=" + runtimeScheduler.debugSummary()
                    + ", transit=" + factoryTransit.debugSummary());
            settleTerminalForPermanentRemoval(player, "terminal_gui_destroy");
            Block.popResource(level, worldPosition, new ItemStack(ModItems.NESTED_FACTORY.get()));
            level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
            return null;
        }
        if (!(nested ? isValidNestedFactory() : rootRoomAllocated)) {
            blackboxDebug("space_destruction_rejected", () -> "reason=invalid_room_binding");
            return Component.translatable("message.create_nested_factory.factory.destroy_failed");
        }

        RoomMutationTaskManager tasks = RoomMutationTaskManager.get(server);
        RoomMutationTaskManager.FactoryRef reference = roomTaskReference();
        if (!tasks.scheduleDestroy(NestedFactoryBlock.POCKET_DIMENSION, roomOrigin(),
                FactoryRoomGeometry.room(bounds, roomOrigin()),
                reference, true)) {
            blackboxDebug("space_destruction_rejected", () -> "reason=destroy_task_not_scheduled");
            return Component.translatable("message.create_nested_factory.room_mutation.active");
        }

        blackboxDebug("space_destruction_started", () -> "runtime=" + runtimeScheduler.debugSummary()
                + ", transit=" + factoryTransit.debugSummary());
        invalidateProductionBatch(player, "gui_destroy");
        dropPendingPortItemsAndDiscardFluids();
        dropInstalledOverclockBatteries();
        evacuateFactorySpace();
        NestedFactorySaveData.get(server).releaseFreezeLease(factoryId);
        Block.popResource(level, worldPosition, new ItemStack(ModItems.NESTED_FACTORY.get()));
        level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(),
                Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
        return null;
    }

    private void settleTerminalForPermanentRemoval(Player player, String reason) {
        if (!terminalBlueprintOnly || level == null || level.isClientSide()) return;
        invalidateProductionBatch(player, reason);
        dropPendingPortItemsAndDiscardFluids();
        dropInstalledOverclockBatteries();
        clearExternalStressState();
        releasePocketChunksImmediately();
        PocketFreezeManager.thawLoadedTree(this);
        MinecraftServer server = level.getServer();
        if (server != null) {
            NestedFactorySaveData saveData = NestedFactorySaveData.get(server);
            saveData.releaseFreezeLease(factoryId);
            saveData.forgetFactoryParent(factoryId);
        }
    }

    private boolean hasChildFactoryInRoom() {
        if (hasRecordedChild()) {
            return true;
        }
        if (!hasPhysicalRoom()) return false;
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return false;
        }
        BlockPos origin = roomOrigin();
        for (int x = bounds.minX(origin); x <= bounds.maxX(origin); x++) {
            for (int y = bounds.minY(origin); y <= bounds.maxY(origin); y++) {
                for (int z = bounds.minZ(origin); z <= bounds.maxZ(origin); z++) {
                    if (pocket.getBlockEntity(new BlockPos(x, y, z)) instanceof NestedFactoryBlockEntity) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void remove() {
        if (!simulatedMoveInProgress && level != null && !level.isClientSide()) {
            MinecraftServer server = level.getServer();
            if (server != null) {
                clearExternalStressState();
                if (!isSimulatedMode()) clearStressRelay();
                chunkLeases.close();
                unregisterFactoryState();
            }
        }
        super.remove();
    }

    private boolean isValidNestedFactory() {
        return nested && enterable && !invalidNested && nestedSlotId >= 0;
    }

    public void dropInstalledOverclockBatteries() {
        if (overclockBatteriesDropped || level == null || level.isClientSide()) {
            return;
        }
        overclockBatteriesDropped = true;
        loadingOverclockInventory = true;
        try {
            for (int i = 0; i < overclockInventory.getContainerSize(); i++) {
                ItemStack stack = overclockInventory.removeItemNoUpdate(i);
                if (!stack.isEmpty()) {
                    Block.popResource(level, worldPosition, stack);
                }
            }
        } finally {
            loadingOverclockInventory = false;
        }
        selectedOverclockTier = OverclockTier.NORMAL;
        if (runtimeScheduler.isIdle()) {
            activeOverclockTier = OverclockTier.NORMAL;
        }
        invalidateOverclockRecipe();
        setChanged();
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
            if (!NestedFactoryBlock.sessionReferencesRoot(session, factoryId)) {
                continue;
            }
            int exitsAllowed = Math.max(maxExits, session.stack().size() + 1);
            for (int exits = 0; exits < exitsAllowed && session.isActive(); exits++) {
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
        PocketRegistry.clearRoomRegistrations(pocket.getServer(), origin);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        for (int i = 0; i < 6; i++) {
            tag.putString("FaceMode" + i, faceModes[i].getSerializedName());
            tag.putInt("PortId" + i, portIds[i]);
        }
        tag.putIntArray("Bounds", bounds.toArray());
        tag.putString("OperationMode", operationMode.getSerializedName());
        tag.putString("LearningStage", learning.stageName());
        tag.put("PowerProfile", powerProfile.write());
        tag.put("FactoryPlan", plan.write(new CompoundTag(), registries));
        tag.put("BlackboxRuntimeScheduler", runtimeScheduler.write(new CompoundTag(), registries));
        tag.putInt("OverclockBatteryFormat", 2);
        tag.put("OverclockBatteries", writeOverclockBatteries(registries));
        tag.putInt("SelectedOverclockTier", selectedOverclockTier.id());
        tag.putInt("ActiveOverclockTier", activeOverclockTier.id());
        tag.put("FactoryTransit", factoryTransit.write(new CompoundTag(), registries));
        tag.putInt("SimulatedIoFormatVersion", SIMULATED_IO_FORMAT_VERSION);
        tag.putString("FactoryId", factoryId);
        tag.putBoolean("RootRoomAllocated", rootRoomAllocated);
        if (rootRoomAllocated) {
            tag.putInt("RootSlotId", rootSlotId);
            tag.putLong("RootRoomOrigin", rootRoomOrigin.asLong());
        }
        tag.putBoolean("Nested", nested);
        tag.putBoolean("AncestorFrozen", ancestorFrozen);
        tag.putBoolean("Enterable", enterable);
        tag.putBoolean("InvalidNested", invalidNested);
        tag.putBoolean("TerminalBlueprintOnly", terminalBlueprintOnly);
        tag.putInt("NestingDepth", nestingDepth);
        tag.putString("ParentFactoryId", parentFactoryId);
        tag.putString("RootFactoryId", rootFactoryId);
        tag.putInt("NestedSlotId", nestedSlotId);
        tag.putInt("NestedSlotX", nestedSlotX);
        tag.putInt("NestedSlotZ", nestedSlotZ);
        tag.putLong("NestedRoomOrigin", nestedRoomOrigin.asLong());
        tag.putString("ChildFactoryId", childFactoryId);
        tag.putBoolean(PORTABLE_BINDING_KEY, portableBinding);
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
        if (clientPacket && operationMode == OperationMode.CHUNK_LOADED) {
            tag.put("LiveTransferRates", transferTelemetry.writeSnapshot(registries, telemetryTick()));
        }
        super.write(tag, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
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
        restoreOperationMode(readOperationMode(modeName));
        boolean interruptedLearning = !clientPacket && (operationMode == OperationMode.BLACKBOX_PREPARING
                || operationMode == OperationMode.BLACKBOX_LEARNING);
        if (interruptedLearning) debugInterruptedLearningOnLoad = true;
        if (interruptedLearning) {
            transitionOperationMode(FactoryLifecycleTransitions.Event.REPAIR_INTERRUPTED_LOAD);
            learning.repairInterruptedLoad();
        }
        if (clientPacket) {
            learning.readClientStage(tag.getString("LearningStage"));
        } else {
            learning.useServerStage();
        }
        powerProfile.read(tag.getCompound("PowerProfile"));
        plan.read(tag.getCompound("FactoryPlan"), registries);
        if (tag.contains("BlackboxRuntimeScheduler")) {
            runtimeScheduler.read(tag.getCompound("BlackboxRuntimeScheduler"), registries);
        } else {
            runtimeScheduler.clear();
        }
        loadingOverclockInventory = true;
        int repairedCollapsedBatteryCount;
        try {
            repairedCollapsedBatteryCount = readOverclockBatteries(tag, registries);
        } finally {
            loadingOverclockInventory = false;
        }
        OverclockTier loadedSelectedTier = OverclockTier.byId(tag.getInt("SelectedOverclockTier"));
        if (!clientPacket && repairedCollapsedBatteryCount > 1
                && loadedSelectedTier == OverclockTier.DOUBLE) {
            loadedSelectedTier = OverclockTier.highestForBatteries(repairedCollapsedBatteryCount);
        }
        selectedOverclockTier = clientPacket ? loadedSelectedTier
                : OverclockTier.normalizeSelection(loadedSelectedTier, getOverclockBatteryCount());
        activeOverclockTier = tag.contains("ActiveOverclockTier")
                ? OverclockTier.byId(tag.getInt("ActiveOverclockTier")) : selectedOverclockTier;
        if (!clientPacket && runtimeScheduler.isIdle()) {
            activeOverclockTier = selectedOverclockTier;
        }
        FactoryPlan loadedRuntimePlan = plan;
        if (isSimulatedMode() && plan.allowsMechanicalOverclock()
                && activeOverclockTier.multiplier() != 1.0f) {
            loadedRuntimePlan = plan.scaledRecipe(activeOverclockTier.multiplier());
        }
        boolean loadedRuntimeValid = runtimeScheduler.validate(loadedRuntimePlan);
        if (!isSimulatedMode() || !loadedRuntimeValid) {
            if (!clientPacket && !runtimeScheduler.isIdle()) {
                appendDebugLoadRepair(!isSimulatedMode()
                        ? "runtime_cleared_outside_simulated_mode"
                        : "runtime_cleared_plan_or_ownership_mismatch");
            }
            runtimeScheduler.clear();
        }
        invalidateOverclockRecipe();
        if (tag.contains("FactoryTransit")) factoryTransit.read(tag.getCompound("FactoryTransit"), registries);
        if (!clientPacket && isSimulatedMode()
                && tag.getInt("SimulatedIoFormatVersion") != SIMULATED_IO_FORMAT_VERSION) {
            FactoryTransit.DestructionReport obsolete = factoryTransit.destroyAll();
            appendDebugLoadRepair("obsolete_simulated_port_cache_destroyed:items=" + obsolete.itemCount()
                    + ",fluids=" + obsolete.fluidAmount() + ",extensions=" + obsolete.extensionCount());
            setChanged();
        }
        if (interruptedLearning) factoryTransit.restoreLiveInputs();
        portableBinding = tag.getBoolean(PORTABLE_BINDING_KEY);
        if (tag.contains("FactoryId")) {
            factoryId = tag.getString("FactoryId");
            factoryStateInitialized = !portableBinding;
        }
        rootRoomAllocated = tag.getBoolean("RootRoomAllocated") && tag.contains("RootRoomOrigin");
        rootSlotId = rootRoomAllocated && tag.contains("RootSlotId") ? tag.getInt("RootSlotId") : -1;
        rootRoomOrigin = rootRoomAllocated ? BlockPos.of(tag.getLong("RootRoomOrigin")) : BlockPos.ZERO;
        nested = tag.getBoolean("Nested");
        ancestorFrozen = tag.getBoolean("AncestorFrozen");
        enterable = tag.getBoolean("Enterable");
        invalidNested = tag.getBoolean("InvalidNested");
        terminalBlueprintOnly = tag.getBoolean("TerminalBlueprintOnly");
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
        if (clientPacket) {
            if (operationMode == OperationMode.CHUNK_LOADED
                    && tag.contains("LiveTransferRates", Tag.TAG_COMPOUND)) {
                transferTelemetry.readSnapshot(tag.getCompound("LiveTransferRates"), registries);
            } else {
                transferTelemetry.clear();
            }
        }
        // Plan v9 intentionally has no legacy compatibility. Earlier black-box data lacks the
        // immutable contract fingerprint and persisted RUNNING transaction, so it loads empty and is
        // forced out of simulated execution instead of inventing missing production rights.
        if (!clientPacket && (operationMode == OperationMode.BLACKBOX_ACTIVE || operationMode == OperationMode.BLUEPRINT)
                && !plan.hasCompleteRecipe()) {
            appendDebugLoadRepair("simulated_mode_cleared_incomplete_plan");
            transitionOperationMode(FactoryLifecycleTransitions.Event.REPAIR_INCOMPLETE_PLAN);
            blueprintApplied = false;
            appliedBlueprint = null;
            preBlueprintSnapshot = null;
        }
        if (blueprintApplied && appliedBlueprint == null) {
            if (!clientPacket) appendDebugLoadRepair("blueprint_flag_cleared_missing_blueprint");
            blueprintApplied = false;
            if (!clientPacket && operationMode == OperationMode.BLUEPRINT) {
                transitionOperationMode(FactoryLifecycleTransitions.Event.REPAIR_MISSING_BLUEPRINT);
            }
            preBlueprintSnapshot = null;
        }
        super.read(tag, registries, clientPacket);
    }

    private void appendDebugLoadRepair(String action) {
        if (action == null || action.isEmpty()) return;
        debugLoadRepair = debugLoadRepair.isEmpty() ? action : debugLoadRepair + "," + action;
    }

    private ListTag writeOverclockBatteries(HolderLookup.Provider registries) {
        ListTag batteries = new ListTag();
        for (int slot = 0; slot < overclockInventory.getContainerSize(); slot++) {
            ItemStack stack = overclockInventory.getItem(slot);
            if (!stack.is(ModItems.BLAZE_BATTERY.get())) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putByte("Slot", (byte) slot);
            entry.put("Stack", stack.copyWithCount(1).save(registries));
            batteries.add(entry);
        }
        return batteries;
    }

    private int readOverclockBatteries(CompoundTag root, HolderLookup.Provider registries) {
        overclockInventory.clearContent();
        if (!root.contains("OverclockBatteries", Tag.TAG_LIST)) {
            return 0;
        }

        List<ItemStack> batteries = new ArrayList<>();
        int repairedCollapsedBatteryCount = 0;
        for (Tag raw : root.getList("OverclockBatteries", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            boolean currentFormat = entry.contains("Stack", Tag.TAG_COMPOUND);
            CompoundTag stackTag = currentFormat ? entry.getCompound("Stack") : entry;
            ItemStack stack = ItemStack.parseOptional(registries, stackTag);
            if (!stack.is(ModItems.BLAZE_BATTERY.get())) {
                continue;
            }
            if (!currentFormat && stack.getCount() > 1) {
                repairedCollapsedBatteryCount = Math.max(repairedCollapsedBatteryCount, stack.getCount());
            }
            int copies = Math.min(stack.getCount(), overclockInventory.getContainerSize() - batteries.size());
            for (int i = 0; i < copies; i++) {
                batteries.add(stack.copyWithCount(1));
            }
            if (batteries.size() >= overclockInventory.getContainerSize()) {
                break;
            }
        }

        for (int slot = 0; slot < batteries.size(); slot++) {
            overclockInventory.setItem(slot, batteries.get(slot));
        }
        return Math.min(repairedCollapsedBatteryCount, overclockInventory.getContainerSize());
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

    private static OperationMode readOperationMode(String name) {
        if (name == null || name.isEmpty()) {
            return OperationMode.CHUNK_LOADED;
        }
        try {
            return OperationMode.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OperationMode.CHUNK_LOADED;
        }
    }
}



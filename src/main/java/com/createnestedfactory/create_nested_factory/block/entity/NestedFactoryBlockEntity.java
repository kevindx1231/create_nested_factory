package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.PocketChunkForceManager;
import com.createnestedfactory.create_nested_factory.Config;
import com.createnestedfactory.create_nested_factory.blueprint.FactoryRestoreSnapshot;
import com.createnestedfactory.create_nested_factory.blueprint.NestedFactoryBlueprint;
import com.createnestedfactory.create_nested_factory.block.FactoryPowerProfile;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.energy.FactoryEnergyStorage;
import com.createnestedfactory.create_nested_factory.menu.FactoryMenu;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class NestedFactoryBlockEntity extends GeneratingKineticBlockEntity implements MenuProvider {
    public static final int MAX_FE_PER_TICK = 10000;
    public static final int ENERGY_CAPACITY = 1_000_000;
    private static final int FACE_TANK_CAPACITY = 1_000_000;

    private static final int LEARNING_TICKS = 200;
    private static final int LEARNING_WARMUP_TICKS = 100;
    private static final int DRAIN_STABLE_TICKS = 60;
    private static final int DRAIN_TIMEOUT_TICKS = 1200;

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
    private final IItemHandler[] rawItemHandlers = new IItemHandler[6];
    private final IItemHandler[] faceItemHandlers = new IItemHandler[6];
    private final IFluidHandler[] rawFluidHandlers = new IFluidHandler[6];
    private final IFluidHandler[] faceFluidHandlers = new IFluidHandler[6];
    private int itemCycleCounter = 0;
    private int playersInside = 0;
    private final Map<String, Integer> chunkRefCounts = new HashMap<>();
    private boolean pocketChunksForced = false;
    private long pocketChunksReleaseAt = -1;

    private String factoryId = UUID.randomUUID().toString();
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

    private final Map<Item, Integer> initialInventory = new HashMap<>();
    private final Map<Item, Integer> staticInventory = new HashMap<>();
    private int drainLastCount = -1;
    private int drainStaticCount = 0;
    private int drainStableTicks = 0;
    private int drainingTicks = 0;
    private int learningTicksRemaining = 0;

    public NestedFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NESTED_FACTORY.get(), pos, state);
        for (int i = 0; i < 6; i++) {
            faceModes[i] = PortMode.NONE;
            rawItemHandlers[i] = new ItemStackHandler(1);
            rawFluidHandlers[i] = new FluidTank(FACE_TANK_CAPACITY);
            faceItemHandlers[i] = blackbox.wrapRateItem(rawItemHandlers[i]);
            faceFluidHandlers[i] = blackbox.wrapRateFluid(rawFluidHandlers[i]);
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

    public Direction getFaceForPortId(int portId) {
        for (int i = 0; i < 6; i++) {
            if (faceModes[i] != PortMode.NONE && portIds[i] == portId) {
                return Direction.from3DDataValue(i);
            }
        }
        return null;
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
        return nested ? nestedRoomOrigin : NestedFactoryBlock.getPocketOrigin(worldPosition);
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
        if (blueprintApplied) {
            cancelBlueprint(player, OperationMode.CHUNK_LOADED);
            return;
        }
        if (invalidNested) {
            if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(Component.literal("§c无效嵌套方块没有独立工厂空间，无法切换模式"), false);
            }
            return;
        }
        if (hasPlayersInside()) {
            if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(Component.literal("§c工厂空间内仍有玩家，无法切换模式"), false);
            }
            return;
        }
        if (operationMode == OperationMode.CHUNK_LOADED) {
            startBlackbox();
        } else {
            stopBlackbox();
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

    public String applyBlueprint(NestedFactoryBlueprint blueprint, ServerPlayer player) {
        if (!isRoot() && !isEnterable() && !invalidNested) {
            return "仅可对工厂方块应用蓝图。";
        }
        if (blueprint == null || !blueprint.hasCompleteRunData()) {
            return "蓝图数据无效。";
        }
        if (factoryId.equals(blueprint.sourceFactoryId())) {
            return "无法将蓝图应用于其来源工厂。";
        }
        if (blueprintApplied) {
            return "该工厂已应用蓝图，请先取消当前蓝图模式。";
        }
        if (isEnterable() && hasPlayersInside()) {
            return "目标工厂空间内存在玩家，无法应用蓝图。";
        }

        preBlueprintSnapshot = captureRestoreSnapshot();
        operationMode = OperationMode.BLUEPRINT;
        blueprintApplied = true;
        appliedBlueprint = blueprint.copy();
        blackbox.read(blueprint.blackbox().write(new CompoundTag()));
        for (int i = 0; i < 6; i++) {
            faceModes[i] = blueprint.faceMode(i);
            portIds[i] = blueprint.portId(i);
        }

        if (!invalidNested) {
            removeChunkRef("load");
            addChunkRef("blueprint");
        }
        setChanged();
        sendSync();
        return null;
    }

    private FactoryRestoreSnapshot captureRestoreSnapshot() {
        FactoryRestoreSnapshot snapshot = new FactoryRestoreSnapshot();
        snapshot.operationMode(operationMode);
        snapshot.blackbox(blackbox.write(new CompoundTag()));
        snapshot.powerProfile(powerProfile.write());
        snapshot.energyStored(energyStored);
        snapshot.invalidNested(invalidNested);
        snapshot.customName(customName);
        for (int i = 0; i < 6; i++) {
            snapshot.faceMode(i, faceModes[i]);
            snapshot.portId(i, portIds[i]);
        }
        return snapshot;
    }

    private void cancelBlueprint(Player player, OperationMode targetMode) {
        if (preBlueprintSnapshot == null) {
            blueprintApplied = false;
            appliedBlueprint = null;
            operationMode = invalidNested ? OperationMode.CHUNK_LOADED : targetMode;
            setChanged();
            sendSync();
            return;
        }

        FactoryRestoreSnapshot snapshot = preBlueprintSnapshot;
        blackbox.read(snapshot.blackbox());
        powerProfile.read(snapshot.powerProfile());
        energyStored = snapshot.energyStored();
        customName = snapshot.customName();
        for (int i = 0; i < 6; i++) {
            faceModes[i] = snapshot.faceMode(i);
            portIds[i] = snapshot.portId(i);
        }

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
        setChanged();
        sendSync();

        if (invalidNested && player instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.literal("§e蓝图已取消。该方块没有独立工厂空间，无法切换为常加载或普通黑盒模式。"), false);
        }
    }

    private void startBlackbox() {
        blackbox.setRecording(false);
        initialInventory.clear();
        staticInventory.clear();
        initialInventory.putAll(countItemsInFactorySpace());
        drainLastCount = totalCount(initialInventory);
        drainStaticCount = 0;
        drainStableTicks = 0;
        drainingTicks = 0;
        operationMode = OperationMode.BLACKBOX_DRAINING;
        setChanged();
        sendSync();
    }

    private void stopBlackbox() {
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
                    .append(Component.literal("该工厂空间已有可进入的嵌套工厂").withStyle(ChatFormatting.RED)));
        }
        return true;
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static void addItemRates(List<Component> tooltip, String key, Map<Item, Float> rates) {
        if (rates.isEmpty()) {
            return;
        }
        tooltip.add(GoggleTooltips.section(key));
        rates.entrySet().stream()
                .sorted(Comparator.comparing(e -> BuiltInRegistries.ITEM.getKey(e.getKey()).toString()))
                .forEach(e -> tooltip.add(Component.literal("     ")
                        .append(new ItemStack(e.getKey()).getHoverName().copy().withStyle(ChatFormatting.GRAY))
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

    private void tickChunkLoaded() {
        blackbox.tickRates();
        if (level.getGameTime() % 20 == 0) {
            scanPowerProfile(pocketLevel());
        }
    }

    private void tickDraining() {
        drainingTicks++;
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        Map<Item, Integer> currentInventory = countItemsInFactorySpace();
        int total = totalCount(currentInventory);
        int staticCount = 0;
        staticInventory.clear();
        for (Map.Entry<Item, Integer> e : currentInventory.entrySet()) {
            int initial = initialInventory.getOrDefault(e.getKey(), 0);
            if (initial == e.getValue()) {
                staticCount += e.getValue();
                if (e.getValue() > 0) {
                    staticInventory.put(e.getKey(), e.getValue());
                }
            }
        }
        drainStaticCount = staticCount;
        if (total == drainLastCount) {
            drainStableTicks += 20;
        } else {
            drainStableTicks = 0;
        }
        drainLastCount = total;
        if (drainStableTicks >= DRAIN_STABLE_TICKS || drainingTicks >= DRAIN_TIMEOUT_TICKS) {
            enterLearning();
        }
    }

    private void enterLearning() {
        blackbox.setIgnoredOutputs(staticInventory);
        blackbox.setRecording(true);
        operationMode = OperationMode.BLACKBOX_LEARNING;
        learningTicksRemaining = LEARNING_TICKS;
        blackbox.beginSampling();
        setChanged();
        sendSync();
    }

    private void tickLearning() {
        if (learningTicksRemaining > LEARNING_WARMUP_TICKS) {
            // 暖机阶段：不采样，让工厂达到稳态
            learningTicksRemaining--;
            if (learningTicksRemaining == LEARNING_WARMUP_TICKS) {
                blackbox.beginSampling();
            }
            return;
        }
        // 采样阶段：逐窗口记录每秒速率
        blackbox.tickRates();
        if (level.getGameTime() % 20 == 0) {
            scanPowerProfile(pocketLevel());
        }
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
        removeChunkRef("load");
        updateGeneratedRotation();
        setChanged();
        sendSync();
    }

    private void tickBlackbox() {
        FactoryPowerProfile activePowerProfile = effectivePowerProfile();
        // 应力满足：内部产生 + 外部提供 >= 内部总消耗
        boolean stressSatisfied = activePowerProfile.internalGeneratedSU() + externalStressCapacity() >= activePowerProfile.consumedSU();
        float netFE = activePowerProfile.netFE();
        boolean energySatisfied;
        if (netFE >= 0) {
            energyStored = Math.min(ENERGY_CAPACITY, energyStored + (int) Math.min(netFE, MAX_FE_PER_TICK));
            energySatisfied = true;
        } else {
            float demand = Math.min(-netFE, MAX_FE_PER_TICK);
            if (energyStored >= demand) {
                energyStored -= (int) demand;
                energySatisfied = true;
            } else {
                energySatisfied = false;
            }
        }
        boolean powerAvailable = stressSatisfied && energySatisfied;

        // 原料是否就绪（输入够、输出有空间）
        boolean itemsReady = itemInputsAvailable() && itemOutputsHaveSpace();
        boolean fluidsReady = blackbox.hasFluidRecipe() && fluidInputsAvailable() && fluidOutputsHaveSpace();

        if (!powerAvailable) {
            itemCycleCounter = 0;
            return;
        }

        // 物品转换：按最小公倍数周期，周期到了且原料够就整批转换
        int cycle = Math.max(1, blackbox.getRecipeCycleTicks());
        itemCycleCounter++;
        if (itemCycleCounter >= cycle) {
            if (itemsReady) {
                doItemConversion();
                itemCycleCounter = 0;
            } else {
                itemCycleCounter = cycle; // 原料不够，保持到期状态，下一 tick 继续尝试
            }
        }

        // 流体转换：每秒一批
        if (level.getGameTime() % 20 == 0 && fluidsReady) {
            doFluidConversion();
        }
    }

    private void tickBlueprint() {
        // Blueprint mode must use the source factory's captured profile. Scanning this
        // target's own room would overwrite it (often with an empty room's zero demand)
        // and let a blueprint run without the source machine's stress requirement.
        tickBlackbox();
    }

    /** 外部相邻动能网络当前尚未被占用的应力容量（su，含速度缩放）。 */
    private float externalStressCapacity() {
        float best = 0f;
        for (Direction face : Direction.values()) {
            KineticBlockEntity kbe = adjacentStressInput(face);
            if (kbe == null || kbe.getSpeed() == 0f) {
                continue;
            }
            float available = availableStressCapacity(kbe);
            if (available > best) {
                best = available;
            }
        }
        return best;
    }

    /**
     * A factory only accepts a real kinetic shaft connection. Belts are kinetic block
     * entities too, but item transport next to the factory must never count as stress input.
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

    private static float availableStressCapacity(KineticBlockEntity kbe) {
        float capacity = kbe.getOrCreateNetwork().calculateCapacity();
        float applied = kbe.getOrCreateNetwork().calculateStress();
        return Math.max(0f, capacity - applied);
    }

    private boolean itemInputsAvailable() {
        for (Map.Entry<Item, Integer> e : blackbox.getRecipeInputs().entrySet()) {
            if (countItemInInputFaces(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private boolean itemOutputsHaveSpace() {
        for (Map.Entry<Item, Integer> e : blackbox.getRecipeOutputs().entrySet()) {
            if (itemSpaceInOutputFaces(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private boolean fluidInputsAvailable() {
        for (Map.Entry<Fluid, Long> e : blackbox.getRecipeInputFluids().entrySet()) {
            if (fluidInInputFaces(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private boolean fluidOutputsHaveSpace() {
        for (Map.Entry<Fluid, Long> e : blackbox.getRecipeOutputFluids().entrySet()) {
            if (fluidSpaceInOutputFaces(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void doItemConversion() {
        for (Map.Entry<Item, Integer> e : blackbox.getRecipeInputs().entrySet()) {
            extractItemFromInputFaces(e.getKey(), e.getValue());
        }
        for (Map.Entry<Item, Integer> e : blackbox.getRecipeOutputs().entrySet()) {
            insertItemIntoOutputFaces(e.getKey(), e.getValue());
        }
    }

    private void doFluidConversion() {
        for (Map.Entry<Fluid, Long> e : blackbox.getRecipeInputFluids().entrySet()) {
            drainFluidFromInputFaces(e.getKey(), e.getValue());
        }
        for (Map.Entry<Fluid, Long> e : blackbox.getRecipeOutputFluids().entrySet()) {
            fillFluidIntoOutputFaces(e.getKey(), e.getValue());
        }
    }

    private int countItemInInputFaces(Item item) {
        int total = 0;
        for (int i = 0; i < 6; i++) {
            if (faceModes[i] != PortMode.INPUT) {
                continue;
            }
            IItemHandler h = rawItemHandlers[i];
            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack stack = h.getStackInSlot(slot);
                if (stack.is(item)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private int itemSpaceInOutputFaces(Item item) {
        int total = 0;
        for (int i = 0; i < 6; i++) {
            if (faceModes[i] != PortMode.OUTPUT) {
                continue;
            }
            IItemHandler h = rawItemHandlers[i];
            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack stack = h.getStackInSlot(slot);
                int limit = h.getSlotLimit(slot);
                if (stack.isEmpty()) {
                    total += limit;
                } else if (stack.is(item)) {
                    total += limit - stack.getCount();
                }
            }
        }
        return total;
    }

    private long fluidInInputFaces(Fluid fluid) {
        long total = 0;
        for (int i = 0; i < 6; i++) {
            if (faceModes[i] != PortMode.INPUT) {
                continue;
            }
            FluidStack stack = rawFluidHandlers[i].getFluidInTank(0);
            if (stack.getFluid() == fluid) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private long fluidSpaceInOutputFaces(Fluid fluid) {
        long total = 0;
        for (int i = 0; i < 6; i++) {
            if (faceModes[i] != PortMode.OUTPUT) {
                continue;
            }
            IFluidHandler h = rawFluidHandlers[i];
            FluidStack stack = h.getFluidInTank(0);
            int capacity = h.getTankCapacity(0);
            if (stack.isEmpty()) {
                total += capacity;
            } else if (stack.getFluid() == fluid) {
                total += capacity - stack.getAmount();
            }
        }
        return total;
    }

    private void extractItemFromInputFaces(Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < 6 && remaining > 0; i++) {
            if (faceModes[i] != PortMode.INPUT) {
                continue;
            }
            IItemHandler h = rawItemHandlers[i];
            for (int slot = 0; slot < h.getSlots() && remaining > 0; slot++) {
                ItemStack stack = h.getStackInSlot(slot);
                if (!stack.is(item)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                remaining -= h.extractItem(slot, take, false).getCount();
            }
        }
    }

    private void insertItemIntoOutputFaces(Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < 6 && remaining > 0; i++) {
            if (faceModes[i] != PortMode.OUTPUT) {
                continue;
            }
            IItemHandler h = rawItemHandlers[i];
            for (int slot = 0; slot < h.getSlots() && remaining > 0; slot++) {
                ItemStack stack = h.getStackInSlot(slot);
                int limit = h.getSlotLimit(slot);
                int space;
                if (stack.isEmpty()) {
                    space = limit;
                } else if (stack.is(item)) {
                    space = limit - stack.getCount();
                } else {
                    continue;
                }
                if (space <= 0) {
                    continue;
                }
                int put = Math.min(remaining, space);
                int accepted = put - h.insertItem(slot, new ItemStack(item, put), false).getCount();
                remaining -= accepted;
            }
        }
    }

    private void drainFluidFromInputFaces(Fluid fluid, long amount) {
        long remaining = amount;
        for (int i = 0; i < 6 && remaining > 0; i++) {
            if (faceModes[i] != PortMode.INPUT) {
                continue;
            }
            IFluidHandler h = rawFluidHandlers[i];
            FluidStack stack = h.getFluidInTank(0);
            if (stack.getFluid() != fluid) {
                continue;
            }
            int take = (int) Math.min(remaining, stack.getAmount());
            remaining -= h.drain(new FluidStack(fluid, take), IFluidHandler.FluidAction.EXECUTE).getAmount();
        }
    }

    private void fillFluidIntoOutputFaces(Fluid fluid, long amount) {
        long remaining = amount;
        for (int i = 0; i < 6 && remaining > 0; i++) {
            if (faceModes[i] != PortMode.OUTPUT) {
                continue;
            }
            IFluidHandler h = rawFluidHandlers[i];
            int take = (int) Math.min(remaining, Integer.MAX_VALUE);
            remaining -= h.fill(new FluidStack(fluid, take), IFluidHandler.FluidAction.EXECUTE);
        }
    }

    public void scanPowerProfile(ServerLevel pocketLevel) {
        scanPowerProfile(pocketLevel, new HashSet<>());
    }

    /**
     * Recursively aggregates the stress deficit of child factories. The visited set
     * keeps malformed parent/child data from turning a scan into a cycle.
     */
    private void scanPowerProfile(ServerLevel pocketLevel, Set<String> visitingFactories) {
        if (pocketLevel == null || !visitingFactories.add(factoryId)) {
            return;
        }
        try {
            float genSU = 0, conSU = 0, genFE = 0, conFE = 0;
            BlockPos origin = roomOrigin();
            int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
            int minY = bounds.minY(origin), maxY = bounds.maxY(origin);
            int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState blockState = pocketLevel.getBlockState(p);
                        if (blockState.isAir() || NestedFactoryBlock.isWallBlock(blockState)) {
                            continue;
                        }
                        BlockEntity blockEntity = pocketLevel.getBlockEntity(p);
                        if (blockEntity instanceof NestedFactoryBlockEntity childFactory) {
                            // Child surplus stays inside the child. Only its remaining
                            // external demand becomes this room's additional consumption.
                            conSU += childFactory.stressDemandFromParent(visitingFactories);
                        } else if (blockEntity instanceof KineticBlockEntity kbe) {
                            float speed = Math.abs(kbe.getTheoreticalSpeed());
                            conSU += Math.abs(kbe.calculateStressApplied()) * speed;
                            // The nested stress port mirrors external input for the live factory.
                            // It must not be captured as internal generation for black-box/blueprint runs.
                            if (!(kbe instanceof NestedStressPortBlockEntity)) {
                                genSU += kbe.calculateAddedStressCapacity() * speed;
                            }
                        }
                        IEnergyStorage storage = findEnergyStorage(pocketLevel, p);
                        if (storage != null) {
                            // No standard "rated FE/t" API exists; approximate power using max storage.
                            float rating = Math.min(storage.getMaxEnergyStored(), MAX_FE_PER_TICK);
                            if (storage.canExtract()) {
                                genFE += rating;
                            }
                            if (storage.canReceive()) {
                                conFE += rating;
                            }
                        }
                    }
                }
            }
            powerProfile.set(genSU, conSU, genFE, conFE);
        } finally {
            visitingFactories.remove(factoryId);
        }
    }

    /**
     * Returns the stress a child must receive through its parent-room boundary.
     * Live modes are rescanned recursively; black-box and blueprint modes use their
     * frozen, already-aggregated profiles.
     */
    private float stressDemandFromParent(Set<String> visitingFactories) {
        if (invalidNested && !blueprintApplied) {
            return 0f;
        }
        if (operationMode != OperationMode.BLACKBOX_ACTIVE && operationMode != OperationMode.BLUEPRINT) {
            scanPowerProfile(pocketLevel(), visitingFactories);
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

    /** 统计工厂空间内所有物品库存（含内部端口映射的端口缓冲与各类容器）。 */
    private Map<Item, Integer> countItemsInFactorySpace() {
        Map<Item, Integer> counts = new HashMap<>();
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return counts;
        }
        BlockPos origin = roomOrigin();
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minY = bounds.minY(origin), maxY = bounds.maxY(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (NestedFactoryBlock.isWallBlock(pocket.getBlockState(p))) {
                        continue;
                    }
                    IItemHandler handler = findItemHandler(pocket, p);
                    if (handler == null) {
                        continue;
                    }
                    for (int slot = 0; slot < handler.getSlots(); slot++) {
                        ItemStack stack = handler.getStackInSlot(slot);
                        if (!stack.isEmpty()) {
                            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                        }
                    }
                }
            }
        }
        return counts;
    }

    private static int totalCount(Map<Item, Integer> counts) {
        int total = 0;
        for (int v : counts.values()) {
            total += v;
        }
        return total;
    }

    public boolean expandSpace(ServerLevel level, Direction direction) {
        if (nested) {
            return false;
        }
        if (!bounds.canExpand(direction)) {
            return false;
        }
        BlockPos origin = roomOrigin();
        PocketBounds old = bounds.copy();
        bounds.expand(direction);
        rebuildShell(level, origin);
        clearExpandedInterior(level, origin, old, direction);
        boundsVersion++;
        if (pocketChunksForced) {
            applyPocketChunkForce(true);
        }
        setChanged();
        return true;
    }

    public boolean collapseSpace(ServerLevel level, Direction direction) {
        if (nested) {
            return false;
        }
        if (!bounds.canCollapse(direction)) {
            return false;
        }
        BlockPos origin = roomOrigin();
        PocketBounds old = bounds.copy();
        bounds.collapse(direction);
        rebuildShell(level, origin);
        clearRemovedSlab(level, origin, old, direction);
        boundsVersion++;
        if (pocketChunksForced) {
            applyPocketChunkForce(true);
        }
        setChanged();
        return true;
    }

    private void rebuildShell(ServerLevel level, BlockPos origin) {
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minY = bounds.minY(origin), maxY = bounds.maxY(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), NestedFactoryBlock.wallState(x, y, z));
                    }
                }
            }
        }
    }

    private void clearExpandedInterior(ServerLevel level, BlockPos origin, PocketBounds old, Direction direction) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minY = bounds.minY(origin), maxY = bounds.maxY(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        switch (direction) {
            case EAST -> fill(level, old.maxX(origin), minY + 1, minZ + 1, maxX - 1, maxY - 1, maxZ - 1, air);
            case WEST -> fill(level, minX + 1, minY + 1, minZ + 1, old.minX(origin), maxY - 1, maxZ - 1, air);
            case UP -> fill(level, minX + 1, old.maxY(origin), minZ + 1, maxX - 1, maxY - 1, maxZ - 1, air);
            case DOWN -> fill(level, minX + 1, minY + 1, minZ + 1, maxX - 1, old.minY(origin), maxZ - 1, air);
            case SOUTH -> fill(level, minX + 1, minY + 1, old.maxZ(origin), maxX - 1, maxY - 1, maxZ - 1, air);
            case NORTH -> fill(level, minX + 1, minY + 1, minZ + 1, maxX - 1, maxY - 1, old.minZ(origin), air);
        }
    }

    private void clearRemovedSlab(ServerLevel level, BlockPos origin, PocketBounds old, Direction direction) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minY = bounds.minY(origin), maxY = bounds.maxY(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        switch (direction) {
            case EAST -> fill(level, maxX + 1, minY, minZ, old.maxX(origin), maxY, maxZ, air);
            case WEST -> fill(level, old.minX(origin), minY, minZ, minX - 1, maxY, maxZ, air);
            case UP -> fill(level, minX, maxY + 1, minZ, maxX, old.maxY(origin), maxZ, air);
            case DOWN -> fill(level, minX, old.minY(origin), minZ, maxX, minY - 1, maxZ, air);
            case SOUTH -> fill(level, minX, minY, maxZ + 1, maxX, maxY, old.maxZ(origin), air);
            case NORTH -> fill(level, minX, minY, old.minZ(origin), maxX, maxY, minZ - 1, air);
        }
    }

    public CollapseCheck checkSectionCollapsible(ServerLevel level, Direction direction) {
        BlockPos origin = roomOrigin();
        int[] b = slabBounds(origin, direction);
        int x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState blockState = level.getBlockState(p);
                    if (!blockState.isAir() && !NestedFactoryBlock.isWallBlock(blockState)) {
                        return CollapseCheck.failed("方块 (" + blockState.getBlock().getName().getString() + " 在 " + x + "," + y + "," + z + ")");
                    }
                    if (!level.getFluidState(p).isEmpty()) {
                        return CollapseCheck.failed("液体");
                    }
                }
            }
        }
        AABB slab = new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, slab, e -> true);
        if (!entities.isEmpty()) {
            Entity first = entities.get(0);
            if (first instanceof Player player) {
                return CollapseCheck.failed("玩家 (" + player.getName().getString() + ")");
            }
            if (first instanceof ItemEntity item) {
                return CollapseCheck.failed("掉落物 (" + item.getItem().getHoverName().getString() + ")");
            }
            return CollapseCheck.failed("实体 (" + first.getName().getString() + ")");
        }
        return CollapseCheck.success();
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

    private static void fill(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), state);
                }
            }
        }
    }

    public record CollapseCheck(boolean clear, String reason) {
        public static CollapseCheck success() {
            return new CollapseCheck(true, null);
        }

        public static CollapseCheck failed(String reason) {
            return new CollapseCheck(false, reason);
        }
    }

    public void cycleFaceMode(Direction face, ServerPlayer player) {
        int index = face.get3DDataValue();
        faceModes[index] = faceModes[index].next();
        if (faceModes[index] == PortMode.NONE) {
            portIds[index] = 0;
        } else if (portIds[index] == 0) {
            portIds[index] = nextPortId();
        }
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        String suffix = faceModes[index] == PortMode.NONE ? "" : " (port " + portIds[index] + ")";
        player.displayClientMessage(Component.literal(face.getName() + ": " + faceModes[index].getSerializedName() + suffix), true);
    }

    private int nextPortId() {
        int max = 0;
        for (int id : portIds) {
            max = Math.max(max, id);
        }
        return Math.min(max + 1, 6);
    }

    public IItemHandler getItemHandler(Direction side) {
        if (side == null || faceModes[side.get3DDataValue()] == PortMode.NONE) {
            return null;
        }
        if (faceModes[side.get3DDataValue()] == PortMode.INPUT
                && operationMode == OperationMode.BLACKBOX_DRAINING) {
            return null;
        }
        return faceItemHandlers[side.get3DDataValue()];
    }

    public IFluidHandler getFluidHandler(Direction side) {
        if (side == null || faceModes[side.get3DDataValue()] == PortMode.NONE) {
            return null;
        }
        if (faceModes[side.get3DDataValue()] == PortMode.INPUT
                && operationMode == OperationMode.BLACKBOX_DRAINING) {
            return null;
        }
        return faceFluidHandlers[side.get3DDataValue()];
    }

    /** 工厂空间内的 Port 通过这里拿到与对应面共享的直连 handler（同一份存储，无缝，不参与测速）。 */
    public IItemHandler getRoomItemHandler(int portId) {
        Direction face = getFaceForPortId(portId);
        return face == null ? null : rawItemHandlers[face.get3DDataValue()];
    }

    public IFluidHandler getRoomFluidHandler(int portId) {
        Direction face = getFaceForPortId(portId);
        return face == null ? null : rawFluidHandlers[face.get3DDataValue()];
    }

    private ServerLevel pocketLevel() {
        if (level == null || level.isClientSide()) {
            return null;
        }
        return level.getServer().getLevel(NestedFactoryBlock.POCKET_DIMENSION);
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
        nestedRoomOrigin = NestedFactoryBlock.getPocketOrigin(worldPosition);
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
                new PocketRegistry.FactoryLocation(level.dimension(), worldPosition), (ServerLevel) level);
        nestedSlotId = slot.id();
        nestedSlotX = slot.slotX();
        nestedSlotZ = slot.slotZ();
        nestedRoomOrigin = NestedFactoryBlock.getNestedRoomOrigin(nestedSlotX, nestedSlotZ);
        enterable = true;
        invalidNested = false;
        parent.setChildFactory(this);
    }

    private void registerFactoryState() {
        if (level == null || level.isClientSide()) {
            return;
        }
        PocketRegistry.FactoryLocation location = new PocketRegistry.FactoryLocation(level.dimension(), worldPosition);
        if (nested) {
            if (enterable && !invalidNested && nestedSlotId >= 0) {
                PocketRegistry.registerNestedSlot(nestedSlotId, location, (ServerLevel) level);
            }
        } else {
            PocketRegistry.registerRoot(roomOrigin(), location);
        }
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
        } else {
            PocketRegistry.unregisterRoot(roomOrigin());
        }
    }

    private void clearChildFromParent() {
        if (parentFactoryPos == null || parentFactoryId.isEmpty() || level == null || level.isClientSide()) {
            return;
        }
        ServerLevel parentLevel = parentDimension == null ? null : level.getServer().getLevel(parentDimension);
        if (parentLevel == null) {
            return;
        }
        if (parentLevel.getBlockEntity(parentFactoryPos) instanceof NestedFactoryBlockEntity parent
                && factoryId.equals(parent.childFactoryId)) {
            parent.setChildFactory(null);
        }
    }

    private void ensureRoomGenerated() {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        BlockPos origin = roomOrigin();
        if (pocket.getBlockState(origin).isAir()) {
            int size = nested ? NestedFactoryBlock.NESTED_ROOM_SIZE : NestedFactoryBlock.NESTED_ROOM_SIZE;
            NestedFactoryBlock.buildRoom(pocket, origin, size);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            if (!factoryStateInitialized) {
                initializeFactoryState();
            }
            blackbox.setRecording(false);
            if (!invalidNested) {
                registerFactoryState();
                ensureRoomGenerated();
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
            return;
        }
        switch (operationMode) {
            case CHUNK_LOADED -> tickChunkLoaded();
            case BLACKBOX_DRAINING -> tickDraining();
            case BLACKBOX_LEARNING -> tickLearning();
            case BLACKBOX_ACTIVE -> tickBlackbox();
            case BLUEPRINT -> tickBlueprint();
        }
        if (operationMode == OperationMode.BLACKBOX_ACTIVE || operationMode == OperationMode.BLUEPRINT) {
            // Simulated modes do not power real machines inside the pocket. Clear any
            // old relay state so a previously powered port cannot become stale input.
            clearStressRelay();
        } else {
            updateStressRelay();
        }
        tickChunkRefs();
        if (level.getGameTime() % 20 == 0) {
            sendData();
        }
    }

    private void updateStressRelay() {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        float bestSpeed = 0f;
        float bestCapacity = 0f;
        for (Direction face : Direction.values()) {
            KineticBlockEntity kbe = adjacentStressInput(face);
            if (kbe == null) {
                continue;
            }
            float speed = kbe.getSpeed();
            if (speed == 0f) {
                continue;
            }
            float available = availableStressCapacity(kbe);
            if (available > bestCapacity) {
                bestCapacity = available;
                bestSpeed = speed;
            }
        }
        float relayCapacity = bestSpeed != 0f ? bestCapacity / Math.abs(bestSpeed) : 0f;
        for (BlockPos stressPortPos : PocketRegistry.getStressPorts(roomOrigin())) {
            if (pocket.getBlockEntity(stressPortPos) instanceof NestedStressPortBlockEntity stressPort) {
                stressPort.setIncomingCapacity(relayCapacity);
                stressPort.setIncomingSpeed(bestSpeed);
            }
        }
    }

    private void clearStressRelay() {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        for (BlockPos stressPortPos : PocketRegistry.getStressPorts(roomOrigin())) {
            if (pocket.getBlockEntity(stressPortPos) instanceof NestedStressPortBlockEntity stressPort) {
                stressPort.setIncomingCapacity(0f);
                stressPort.setIncomingSpeed(0f);
            }
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
        return 0f;
    }

    @Override
    public void remove() {
        super.remove();
        if (level != null && !level.isClientSide()) {
            PocketChunkForceManager.releaseAll(level.getServer(), externalChunkForceOwner());
            PocketChunkForceManager.releaseAll(level.getServer(), roomChunkForceOwner());
            chunkRefCounts.clear();
            pocketChunksForced = false;
            unregisterFactoryState();
        }
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
        blackbox.write(tag);
        tag.putInt("EnergyStored", energyStored);
        tag.putString("FactoryId", factoryId);
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
            tag.put("AppliedBlueprint", appliedBlueprint.write(new CompoundTag()));
        }
        if (preBlueprintSnapshot != null) {
            tag.put("PreBlueprintSnapshot", preBlueprintSnapshot.write(new CompoundTag()));
        }
        super.write(tag, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        for (int i = 0; i < 6; i++) {
            String mode = tag.getString("FaceMode" + i);
            faceModes[i] = mode.isEmpty() ? PortMode.NONE : PortMode.valueOf(mode.toUpperCase(Locale.ROOT));
            portIds[i] = tag.getInt("PortId" + i);
        }
        bounds.fromArray(tag.getIntArray("Bounds"));
        String modeName = tag.getString("OperationMode");
        operationMode = readOperationMode(modeName, clientPacket);
        drainLastCount = tag.getInt("DrainLastCount");
        drainStaticCount = tag.getInt("DrainStaticCount");
        learningTicksRemaining = tag.getInt("LearningTicksRemaining");
        powerProfile.read(tag.getCompound("PowerProfile"));
        blackbox.read(tag);
        energyStored = tag.getInt("EnergyStored");
        if (tag.contains("FactoryId")) {
            factoryId = tag.getString("FactoryId");
            factoryStateInitialized = true;
        }
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
                ? NestedFactoryBlueprint.fromTag(tag.getCompound("AppliedBlueprint"))
                : null;
        preBlueprintSnapshot = tag.contains("PreBlueprintSnapshot")
                ? readRestoreSnapshot(tag.getCompound("PreBlueprintSnapshot"))
                : null;
        super.read(tag, registries, clientPacket);
    }

    private static FactoryRestoreSnapshot readRestoreSnapshot(CompoundTag tag) {
        FactoryRestoreSnapshot snapshot = new FactoryRestoreSnapshot();
        snapshot.read(tag);
        return snapshot;
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
            // 排空/学习是临时真实运行态，服务端重启不恢复；但客户端同步要保留真实状态用于显示。
            if (!clientPacket && (mode == OperationMode.BLACKBOX_DRAINING || mode == OperationMode.BLACKBOX_LEARNING)) {
                return OperationMode.CHUNK_LOADED;
            }
            return mode;
        } catch (IllegalArgumentException e) {
            return OperationMode.CHUNK_LOADED;
        }
    }
}

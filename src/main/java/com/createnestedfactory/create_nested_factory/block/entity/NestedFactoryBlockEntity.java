package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.FactoryPowerProfile;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.energy.FactoryEnergyStorage;
import com.createnestedfactory.create_nested_factory.menu.FactoryMenu;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private int energyStored = 0;
    private final FactoryEnergyStorage energyStorage = new FactoryEnergyStorage(this);

    private String customName = null;
    private final IItemHandler[] rawItemHandlers = new IItemHandler[6];
    private final IItemHandler[] faceItemHandlers = new IItemHandler[6];
    private final IFluidHandler[] rawFluidHandlers = new IFluidHandler[6];
    private final IFluidHandler[] faceFluidHandlers = new IFluidHandler[6];
    private int itemCycleCounter = 0;
    private int playersInside = 0;

    private final Map<Item, Integer> initialInventory = new HashMap<>();
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
        return powerProfile;
    }

    public BlackboxData getBlackbox() {
        return blackbox;
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
        return powerProfile.netFE() > 0 && energyStored > 0;
    }

    public boolean canReceiveEnergy() {
        return powerProfile.netFE() < 0;
    }

    public int getMaxEnergyExtract() {
        return (int) Math.min(MAX_FE_PER_TICK, Math.max(0f, powerProfile.netFE()));
    }

    public IEnergyStorage getEnergyStorage(Direction side) {
        return energyStorage;
    }

    public void toggleBlackbox(Player player) {
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

    private void startBlackbox() {
        initialInventory.clear();
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
        blackbox.beginSampling();
        initialInventory.clear();
        drainingTicks = 0;
        drainStaticCount = 0;
        drainStableTicks = 0;
        learningTicksRemaining = 0;
        operationMode = OperationMode.CHUNK_LOADED;
        setPocketChunksForced(true);
        setChanged();
        sendSync();
    }

    private boolean hasPlayersInside() {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return false;
        }
        BlockPos origin = NestedFactoryBlock.getPocketOrigin(worldPosition);
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
        }
    }

    public void onPlayerExited() {
        if (playersInside > 0) {
            playersInside--;
            if (playersInside == 0) {
                setExternalAreaForced(false);
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

        switch (operationMode) {
            case BLACKBOX_DRAINING -> tooltip.add(GoggleTooltips.stat(
                    "goggles.create_nested_factory.draining", String.valueOf(Math.max(0, drainLastCount - drainStaticCount)), ChatFormatting.YELLOW));
            case BLACKBOX_LEARNING -> tooltip.add(GoggleTooltips.stat(
                    "goggles.create_nested_factory.learning", ((learningTicksRemaining + 19) / 20) + "s", ChatFormatting.YELLOW));
            default -> { }
        }

        if (powerProfile.generatedSU() != 0f || powerProfile.consumedSU() != 0f) {
            tooltip.add(GoggleTooltips.section("goggles.create_nested_factory.stress"));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.generated", fmt(powerProfile.generatedSU()) + " su", ChatFormatting.AQUA));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.consumed", fmt(powerProfile.consumedSU()) + " su", ChatFormatting.AQUA));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.net", fmt(powerProfile.netSU()) + " su", ChatFormatting.AQUA));
        }

        if (powerProfile.generatedFE() != 0f || powerProfile.consumedFE() != 0f) {
            tooltip.add(GoggleTooltips.section("goggles.create_nested_factory.energy"));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.generated", fmt(powerProfile.generatedFE()) + " FE/t", ChatFormatting.GOLD));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.consumed", fmt(powerProfile.consumedFE()) + " FE/t", ChatFormatting.GOLD));
            tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.net", fmt(powerProfile.netFE()) + " FE/t", ChatFormatting.GOLD));
        }

        addItemRates(tooltip, "goggles.create_nested_factory.input_items", blackbox.getInputRates());
        addItemRates(tooltip, "goggles.create_nested_factory.output_items", blackbox.getOutputRates());
        addFluidRates(tooltip, "goggles.create_nested_factory.input_fluids", blackbox.getInputFluidRates());
        addFluidRates(tooltip, "goggles.create_nested_factory.output_fluids", blackbox.getOutputFluidRates());
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

    private void setPocketChunksForced(boolean forced) {
        ServerLevel pocket = pocketLevel();
        if (pocket == null) {
            return;
        }
        BlockPos origin = NestedFactoryBlock.getPocketOrigin(worldPosition);
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                pocket.setChunkForced(cx, cz, forced);
            }
        }
    }

    /** 强制加载 / 取消加载外部（主世界）以工厂方块所在区块为中心的 3×3 区块。 */
    private void setExternalAreaForced(boolean forced) {
        if (level == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = worldPosition.getX() >> 4;
        int cz = worldPosition.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                serverLevel.setChunkForced(cx + dx, cz + dz, forced);
            }
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
        for (Map.Entry<Item, Integer> e : currentInventory.entrySet()) {
            int initial = initialInventory.getOrDefault(e.getKey(), 0);
            if (initial == e.getValue()) {
                staticCount += e.getValue();
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
        scanPowerProfile(pocketLevel());
        operationMode = OperationMode.BLACKBOX_ACTIVE;
        setPocketChunksForced(false);
        updateGeneratedRotation();
        setChanged();
        sendSync();
    }

    private void tickBlackbox() {
        // 应力满足：内部产生 + 外部提供 >= 内部总消耗
        boolean stressSatisfied = powerProfile.generatedSU() + externalStressCapacity() >= powerProfile.consumedSU();
        float netFE = powerProfile.netFE();
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
        boolean itemsReady = blackbox.hasItemRecipe() && itemInputsAvailable() && itemOutputsHaveSpace();
        boolean fluidsReady = blackbox.hasFluidRecipe() && fluidInputsAvailable() && fluidOutputsHaveSpace();

        if (!powerAvailable) {
            itemCycleCounter = 0;
            return;
        }

        // 物品转换：按最小公倍数周期，周期到了且原料够就整批转换
        if (blackbox.hasItemRecipe()) {
            int cycle = blackbox.getRecipeCycleTicks();
            itemCycleCounter++;
            if (itemCycleCounter >= cycle) {
                if (itemsReady) {
                    doItemConversion();
                    itemCycleCounter = 0;
                } else {
                    itemCycleCounter = cycle; // 原料不够，保持到期状态，下一 tick 继续尝试
                }
            }
        }

        // 流体转换：每秒一批
        if (level.getGameTime() % 20 == 0 && fluidsReady) {
            doFluidConversion();
        }
    }

    /** 外部（主世界）通过相邻动能方块提供给工厂的应力容量（su，含速度缩放）。 */
    private float externalStressCapacity() {
        float best = 0f;
        for (Direction face : Direction.values()) {
            if (!(level.getBlockEntity(worldPosition.relative(face)) instanceof KineticBlockEntity kbe)) {
                continue;
            }
            if (kbe.getSpeed() == 0f) {
                continue;
            }
            float capacity = kbe.getOrCreateNetwork().calculateCapacity();
            if (capacity > best) {
                best = capacity;
            }
        }
        return best;
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
        if (pocketLevel == null) {
            return;
        }
        float genSU = 0, conSU = 0, genFE = 0, conFE = 0;
        BlockPos origin = NestedFactoryBlock.getPocketOrigin(worldPosition);
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
                    if (pocketLevel.getBlockEntity(p) instanceof KineticBlockEntity kbe) {
                        float speed = Math.abs(kbe.getTheoreticalSpeed());
                        conSU += Math.abs(kbe.calculateStressApplied()) * speed;
                        genSU += kbe.calculateAddedStressCapacity() * speed;
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
        BlockPos origin = NestedFactoryBlock.getPocketOrigin(worldPosition);
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
        if (!bounds.canExpand(direction)) {
            return false;
        }
        BlockPos origin = NestedFactoryBlock.getPocketOrigin(worldPosition);
        PocketBounds old = bounds.copy();
        bounds.expand(direction);
        rebuildShell(level, origin);
        clearExpandedInterior(level, origin, old, direction);
        setChanged();
        return true;
    }

    public boolean collapseSpace(ServerLevel level, Direction direction) {
        if (!bounds.canCollapse(direction)) {
            return false;
        }
        BlockPos origin = NestedFactoryBlock.getPocketOrigin(worldPosition);
        PocketBounds old = bounds.copy();
        bounds.collapse(direction);
        rebuildShell(level, origin);
        clearRemovedSlab(level, origin, old, direction);
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
        BlockPos origin = NestedFactoryBlock.getPocketOrigin(worldPosition);
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

    private BlockPos roomOrigin() {
        return NestedFactoryBlock.getPocketOrigin(worldPosition);
    }

    private ServerLevel pocketLevel() {
        if (level == null || level.isClientSide()) {
            return null;
        }
        return level.getServer().getLevel(NestedFactoryBlock.POCKET_DIMENSION);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            PocketRegistry.register(NestedFactoryBlock.getPocketOrigin(worldPosition),
                    new PocketRegistry.FactoryLocation(level.dimension(), worldPosition));
            if (operationMode != OperationMode.BLACKBOX_ACTIVE) {
                setPocketChunksForced(true);
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
        switch (operationMode) {
            case CHUNK_LOADED -> tickChunkLoaded();
            case BLACKBOX_DRAINING -> tickDraining();
            case BLACKBOX_LEARNING -> tickLearning();
            case BLACKBOX_ACTIVE -> tickBlackbox();
        }
        if (operationMode != OperationMode.BLACKBOX_ACTIVE) {
            updateStressRelay();
        }
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
            if (!(level.getBlockEntity(worldPosition.relative(face)) instanceof KineticBlockEntity kbe)) {
                continue;
            }
            float speed = kbe.getSpeed();
            if (speed == 0f) {
                continue;
            }
            float capacity = kbe.getOrCreateNetwork().calculateCapacity();
            if (capacity > bestCapacity) {
                bestCapacity = capacity;
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
            setPocketChunksForced(false);
            PocketRegistry.unregister(NestedFactoryBlock.getPocketOrigin(worldPosition));
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
        if (customName != null) {
            tag.putString("CustomName", customName);
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
        customName = tag.contains("CustomName") ? tag.getString("CustomName") : null;
        super.read(tag, registries, clientPacket);
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

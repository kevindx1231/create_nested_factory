package com.createnestedfactory.create_nested_factory.menu;

import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.OverclockTier;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.BlackboxData;
import com.createnestedfactory.create_nested_factory.block.entity.ItemVariant;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import com.createnestedfactory.create_nested_factory.registry.ModMenus;
import com.createnestedfactory.create_nested_factory.registry.ModItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import net.minecraft.core.Direction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The client never receives a factory coordinate. All operations are scoped to this server-side
 * menu instance, while 32-bit values are split into two data slots to avoid packet truncation.
 */
public class FactoryMenu extends AbstractContainerMenu {
    private static final int DATA_MODE = 0;
    private static final int DATA_FACES = 1;
    private static final int RESOURCE_ENTRY_SIZE = 5;
    private static final int RESOURCE_ROWS = 6;
    private static final int DATA_INPUTS_START = 7;
    private static final int DATA_OUTPUTS_START = DATA_INPUTS_START + RESOURCE_ENTRY_SIZE * RESOURCE_ROWS;
    private static final int DATA_BLUEPRINT = DATA_OUTPUTS_START + RESOURCE_ENTRY_SIZE * RESOURCE_ROWS;
    private static final int DATA_BATTERY_COUNT = DATA_BLUEPRINT + 1;
    private static final int DATA_SELECTED_OVERCLOCK = DATA_BATTERY_COUNT + 1;
    private static final int DATA_ACTIVE_OVERCLOCK = DATA_SELECTED_OVERCLOCK + 1;
    private static final int DATA_COUNT = DATA_ACTIVE_OVERCLOCK + 1;
    private static final int BATTERY_SLOTS = 4;
    private static final int PLAYER_INVENTORY_START = BATTERY_SLOTS;
    private static final int PLAYER_HOTBAR_START = PLAYER_INVENTORY_START + 27;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + 36;
    private static final int RENAME_MAX_LENGTH = 25;
    public static final int DESTROY_BUTTON_ID = 30;

    private final NestedFactoryBlockEntity factory;
    private final Container overclockInventory;
    private final ContainerData data = new SimpleContainerData(DATA_COUNT);

    public FactoryMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null);
    }

    public FactoryMenu(int containerId, Inventory playerInventory, NestedFactoryBlockEntity factory) {
        super(ModMenus.FACTORY.get(), containerId);
        this.factory = factory;
        this.overclockInventory = factory == null ? new SimpleContainer(BATTERY_SLOTS) : factory.getOverclockInventory();
        checkContainerSize(overclockInventory, BATTERY_SLOTS);
        addOverclockSlots();
        addPlayerInventory(playerInventory);
        addDataSlots(data);
    }

    private void addOverclockSlots() {
        int[][] positions = {
                {91, 69},
                {130, 69},
                {151, 69},
                {172, 69}
        };
        for (int i = 0; i < BATTERY_SLOTS; i++) {
            addSlot(new OverclockSlot(overclockInventory, i, positions[i][0], positions[i][1]));
        }
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        91 + column * 18, 137 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 91 + column * 18, 195));
        }
    }

    @Override
    public void broadcastChanges() {
        if (factory != null) {
            data.set(DATA_MODE, factory.getOperationMode().ordinal());
            Direction facing = factory.getBlockState().getValue(HorizontalKineticBlock.HORIZONTAL_FACING);
            for (int i = 0; i < 6; i++) {
                data.set(DATA_FACES + i, factory.getFaceMode(faceForButton(facing, i)).ordinal());
            }
            BlackboxData displayedRecipe = factory.getDisplayedBlackbox();
            syncRates(DATA_INPUTS_START, displayedRecipe.getInputRates(), displayedRecipe.getInputFluidRates());
            syncRates(DATA_OUTPUTS_START, displayedRecipe.getOutputRates(), displayedRecipe.getOutputFluidRates());
            data.set(DATA_BLUEPRINT, factory.isBlueprintApplied() ? 1 : 0);
            data.set(DATA_BATTERY_COUNT, factory.getOverclockBatteryCount());
            data.set(DATA_SELECTED_OVERCLOCK, factory.getSelectedOverclockTier().id());
            data.set(DATA_ACTIVE_OVERCLOCK, factory.getActiveOverclockTier().id());
        }
        super.broadcastChanges();
    }

    public OperationMode getMode() {
        int ordinal = data.get(DATA_MODE);
        return ordinal >= 0 && ordinal < OperationMode.values().length
                ? OperationMode.values()[ordinal] : OperationMode.CHUNK_LOADED;
    }

    public boolean isBlueprintApplied() {
        return data.get(DATA_BLUEPRINT) != 0;
    }

    public int getOverclockBatteryCount() {
        return Math.max(0, Math.min(BATTERY_SLOTS, data.get(DATA_BATTERY_COUNT)));
    }

    public OverclockTier getSelectedOverclockTier() {
        return OverclockTier.byId(data.get(DATA_SELECTED_OVERCLOCK));
    }

    public OverclockTier getActiveOverclockTier() {
        return OverclockTier.byId(data.get(DATA_ACTIVE_OVERCLOCK));
    }

    public boolean isOverclockEnabled() {
        OperationMode mode = getMode();
        return getOverclockBatteryCount() > 0
                && (mode == OperationMode.BLACKBOX_ACTIVE || mode == OperationMode.BLUEPRINT);
    }

    public PortMode getFaceMode(int buttonIndex) {
        int ordinal = data.get(DATA_FACES + buttonIndex);
        return ordinal >= 0 && ordinal < PortMode.values().length ? PortMode.values()[ordinal] : PortMode.NONE;
    }

    public int getInputType(int index) {
        return readResourceType(DATA_INPUTS_START, index);
    }

    public int getInputId(int index) {
        return readResourceInt(DATA_INPUTS_START, index);
    }

    public float getInputRate(int index) {
        return readResourceFloat(DATA_INPUTS_START, index);
    }

    public int getOutputType(int index) {
        return readResourceType(DATA_OUTPUTS_START, index);
    }

    public int getOutputId(int index) {
        return readResourceInt(DATA_OUTPUTS_START, index);
    }

    public float getOutputRate(int index) {
        return readResourceFloat(DATA_OUTPUTS_START, index);
    }

    private int resourceSlot(int start, int index) {
        if (index < 0 || index >= RESOURCE_ROWS) {
            return start;
        }
        return start + index * RESOURCE_ENTRY_SIZE;
    }

    private int readResourceType(int start, int index) {
        return data.get(resourceSlot(start, index));
    }

    private int readResourceInt(int start, int index) {
        return readInt(resourceSlot(start, index) + 1);
    }

    private float readResourceFloat(int start, int index) {
        return readFloat(resourceSlot(start, index) + 3);
    }

    private void syncRates(int start, Map<ItemVariant, Float> itemRates, Map<Fluid, Float> fluidRates) {
        List<ResourceRate> rates = new ArrayList<>();
        for (Map.Entry<ItemVariant, Float> entry : itemRates.entrySet()) {
            float rate = entry.getValue();
            if (Float.isFinite(rate) && rate > 0f) {
                rates.add(new ResourceRate(1, BuiltInRegistries.ITEM.getId(entry.getKey().item()), rate,
                        BuiltInRegistries.ITEM.getKey(entry.getKey().item()).toString()
                                + "|" + entry.getKey().prototype().getComponentsPatch()));
            }
        }
        for (Map.Entry<Fluid, Float> entry : fluidRates.entrySet()) {
            float rate = entry.getValue();
            if (Float.isFinite(rate) && rate > 0f) {
                rates.add(new ResourceRate(2, BuiltInRegistries.FLUID.getId(entry.getKey()), rate,
                        BuiltInRegistries.FLUID.getKey(entry.getKey()).toString()));
            }
        }

        rates.sort(Comparator.comparingDouble(ResourceRate::rate).reversed()
                .thenComparing(ResourceRate::sortKey));

        for (int i = 0; i < RESOURCE_ROWS; i++) {
            int slot = resourceSlot(start, i);
            if (i < rates.size()) {
                ResourceRate rate = rates.get(i);
                data.set(slot, rate.type());
                writeInt(slot + 1, rate.id());
                writeFloat(slot + 3, rate.rate());
            } else {
                data.set(slot, 0);
                writeInt(slot + 1, 0);
                writeFloat(slot + 3, 0f);
            }
        }
    }

    private record ResourceRate(int type, int id, float rate, String sortKey) {
    }

    private void writeInt(int slot, int value) {
        data.set(slot, value & 0xffff);
        data.set(slot + 1, (value >>> 16) & 0xffff);
    }

    private int readInt(int slot) {
        return (data.get(slot) & 0xffff) | ((data.get(slot + 1) & 0xffff) << 16);
    }

    private void writeFloat(int slot, float value) {
        writeInt(slot, Float.floatToIntBits(value));
    }

    private float readFloat(int slot) {
        return Float.intBitsToFloat(readInt(slot));
    }

    public boolean renameIfValid(ServerPlayer player, String rawName) {
        if (player.containerMenu != this || !stillValid(player) || rawName == null) {
            return false;
        }
        String name = rawName.trim();
        if (name.length() > RENAME_MAX_LENGTH) {
            return false;
        }
        factory.setCustomName(name);
        return true;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (factory != null && stillValid(player)) {
            if (id >= 0 && id < 6) {
                if (player instanceof ServerPlayer sp) {
                    Direction facing = factory.getBlockState().getValue(HorizontalKineticBlock.HORIZONTAL_FACING);
                    Component feedback = factory.cycleFaceModeFromMenu(faceForButton(facing, id), sp);
                    if (feedback != null) {
                        PlayerMessagePayload.sendTo(sp, feedback);
                    }
                    return true;
                }
            } else if (id == 6) {
                factory.toggleBlackbox(player);
                return true;
            } else if (id == 7) {
                factory.switchFromBlueprint(player, OperationMode.CHUNK_LOADED);
                return true;
            } else if (id >= 20 && id <= 25) {
                return factory.selectOverclockTier(OverclockTier.byId(id - 20));
            } else if (id == DESTROY_BUTTON_ID && player instanceof ServerPlayer serverPlayer) {
                Component failure = factory.requestSpaceDestruction(serverPlayer);
                if (failure == null) {
                    serverPlayer.closeContainer();
                } else {
                    PlayerMessagePayload.sendTo(serverPlayer, failure.copy().withStyle(ChatFormatting.RED), false);
                }
                return true;
            }
        }
        return super.clickMenuButton(player, id);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < BATTERY_SLOTS) {
            if (!moveItemStackTo(stack, PLAYER_INVENTORY_START, PLAYER_INVENTORY_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            int target = stack.is(ModItems.BLAZE_BATTERY.get()) ? nextBatterySlot() : BATTERY_SLOTS;
            if (target < BATTERY_SLOTS) {
                if (!moveItemStackTo(stack, target, target + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < PLAYER_HOTBAR_START) {
                if (!moveItemStackTo(stack, PLAYER_HOTBAR_START, PLAYER_INVENTORY_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, PLAYER_INVENTORY_START, PLAYER_HOTBAR_START, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (factory != null && slotId >= 0 && slotId < BATTERY_SLOTS
                && !overclockInventory.getItem(slotId).isEmpty()
                && !factory.canRemoveOverclockBattery(slotId)) {
            if (player instanceof ServerPlayer serverPlayer) {
                PlayerMessagePayload.sendTo(serverPlayer, Component.translatable(
                        "message.create_nested_factory.overclock.remove_later_first"), false);
            }
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    private int nextBatterySlot() {
        for (int i = 0; i < BATTERY_SLOTS; i++) {
            if (overclockInventory.getItem(i).isEmpty()) {
                return i;
            }
        }
        return BATTERY_SLOTS;
    }

    @Override
    public boolean stillValid(Player player) {
        if (factory == null || factory.getLevel() == null || player.level() != factory.getLevel()
                || factory.getLevel().getBlockEntity(factory.getBlockPos()) != factory) {
            return false;
        }
        double x = factory.getBlockPos().getX() + 0.5;
        double y = factory.getBlockPos().getY() + 0.5;
        double z = factory.getBlockPos().getZ() + 0.5;
        return player.distanceToSqr(x, y, z) <= 64.0;
    }

    // Button index -> world-facing direction: front, up, down, left, right, back.
    private static Direction faceForButton(Direction facing, int buttonIndex) {
        return switch (buttonIndex) {
            case 0 -> facing;
            case 1 -> Direction.UP;
            case 2 -> Direction.DOWN;
            case 3 -> facing.getClockWise();
            case 4 -> facing.getCounterClockWise();
            default -> facing.getOpposite();
        };
    }

    private class OverclockSlot extends Slot {
        OverclockSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (factory != null) {
                return factory.canPlaceOverclockBattery(getContainerSlot(), stack);
            }
            if (!stack.is(ModItems.BLAZE_BATTERY.get()) || !getItem().isEmpty()) {
                return false;
            }
            for (int i = 0; i < getContainerSlot(); i++) {
                if (!overclockInventory.getItem(i).is(ModItems.BLAZE_BATTERY.get())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean mayPickup(Player player) {
            if (factory != null) {
                return factory.canRemoveOverclockBattery(getContainerSlot());
            }
            for (int i = getContainerSlot() + 1; i < BATTERY_SLOTS; i++) {
                if (!overclockInventory.getItem(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}

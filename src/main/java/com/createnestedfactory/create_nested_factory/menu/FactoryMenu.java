package com.createnestedfactory.create_nested_factory.menu;

import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.ItemVariant;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import com.createnestedfactory.create_nested_factory.registry.ModMenus;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;


import java.util.Map;

/**
 * The client never receives a factory coordinate. All operations are scoped to this server-side
 * menu instance, while 32-bit values are split into two data slots to avoid packet truncation.
 */
public class FactoryMenu extends AbstractContainerMenu {
    private static final int DATA_MODE = 0;
    private static final int DATA_FACES = 1;
    private static final int DATA_INPUT_TYPE = 7;
    private static final int DATA_INPUT_ID = 8;
    private static final int DATA_INPUT_RATE = 10;
    private static final int DATA_OUTPUT_TYPE = 12;
    private static final int DATA_OUTPUT_ID = 13;
    private static final int DATA_OUTPUT_RATE = 15;
    private static final int DATA_BLUEPRINT = 17;
    private static final int DATA_COUNT = 18;
    private static final int RENAME_MAX_LENGTH = 25;

    private final NestedFactoryBlockEntity factory;
    private final ContainerData data = new SimpleContainerData(DATA_COUNT);

    public FactoryMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null);
    }

    public FactoryMenu(int containerId, Inventory playerInventory, NestedFactoryBlockEntity factory) {
        super(ModMenus.FACTORY.get(), containerId);
        this.factory = factory;
        addDataSlots(data);
    }

    @Override
    public void broadcastChanges() {
        if (factory != null) {
            data.set(DATA_MODE, factory.getOperationMode().ordinal());
            Direction facing = factory.getBlockState().getValue(HorizontalKineticBlock.HORIZONTAL_FACING);
            for (int i = 0; i < 6; i++) {
                data.set(DATA_FACES + i, factory.getFaceMode(faceForButton(facing, i)).ordinal());
            }
            syncRate(DATA_INPUT_TYPE, factory.getBlackbox().getInputRates(), factory.getBlackbox().getInputFluidRates());
            syncRate(DATA_OUTPUT_TYPE, factory.getBlackbox().getOutputRates(), factory.getBlackbox().getOutputFluidRates());
            data.set(DATA_BLUEPRINT, factory.isBlueprintApplied() ? 1 : 0);
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

    public PortMode getFaceMode(int buttonIndex) {
        int ordinal = data.get(DATA_FACES + buttonIndex);
        return ordinal >= 0 && ordinal < PortMode.values().length ? PortMode.values()[ordinal] : PortMode.NONE;
    }

    public int getInputType() {
        return data.get(DATA_INPUT_TYPE);
    }

    public int getInputId() {
        return readInt(DATA_INPUT_ID);
    }

    public float getInputRate() {
        return readFloat(DATA_INPUT_RATE);
    }

    public int getOutputType() {
        return data.get(DATA_OUTPUT_TYPE);
    }

    public int getOutputId() {
        return readInt(DATA_OUTPUT_ID);
    }

    public float getOutputRate() {
        return readFloat(DATA_OUTPUT_RATE);
    }

    private void syncRate(int typeSlot, Map<ItemVariant, Float> itemRates, Map<Fluid, Float> fluidRates) {
        ItemVariant bestItem = null;
        float bestItemRate = 0f;
        for (Map.Entry<ItemVariant, Float> entry : itemRates.entrySet()) {
            if (Float.isFinite(entry.getValue()) && entry.getValue() > bestItemRate) {
                bestItem = entry.getKey();
                bestItemRate = entry.getValue();
            }
        }
        Fluid bestFluid = null;
        float bestFluidRate = 0f;
        for (Map.Entry<Fluid, Float> entry : fluidRates.entrySet()) {
            if (Float.isFinite(entry.getValue()) && entry.getValue() > bestFluidRate) {
                bestFluid = entry.getKey();
                bestFluidRate = entry.getValue();
            }
        }

        if (bestItemRate > 0f && bestItemRate >= bestFluidRate) {
            data.set(typeSlot, 1);
            writeInt(typeSlot + 1, BuiltInRegistries.ITEM.getId(bestItem.item()));
            writeFloat(typeSlot + 3, bestItemRate);
        } else if (bestFluidRate > 0f) {
            data.set(typeSlot, 2);
            writeInt(typeSlot + 1, BuiltInRegistries.FLUID.getId(bestFluid));
            writeFloat(typeSlot + 3, bestFluidRate);
        } else {
            data.set(typeSlot, 0);
            writeInt(typeSlot + 1, 0);
            writeFloat(typeSlot + 3, 0f);
        }
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
            }
        }
        return super.clickMenuButton(player, id);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
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
}

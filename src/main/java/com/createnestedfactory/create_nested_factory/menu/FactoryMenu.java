package com.createnestedfactory.create_nested_factory.menu;

import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModMenus;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

import java.util.Map;

public class FactoryMenu extends AbstractContainerMenu {
    private final NestedFactoryBlockEntity factory;
    private final ContainerData data = new SimpleContainerData(17);

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
            data.set(0, factory.getOperationMode().ordinal());
            BlockPos pos = factory.getBlockPos();
            data.set(1, pos.getX());
            data.set(2, pos.getY());
            data.set(3, pos.getZ());
            Direction facing = factory.getBlockState().getValue(HorizontalKineticBlock.HORIZONTAL_FACING);
            for (int i = 0; i < 6; i++) {
                data.set(4 + i, factory.getFaceMode(faceForButton(facing, i)).ordinal());
            }
            syncRate(10, factory.getBlackbox().getInputRates(), factory.getBlackbox().getInputFluidRates());
            syncRate(13, factory.getBlackbox().getOutputRates(), factory.getBlackbox().getOutputFluidRates());
            data.set(16, factory.isBlueprintApplied() ? 1 : 0);
        }
        super.broadcastChanges();
    }

    public OperationMode getMode() {
        return OperationMode.values()[data.get(0)];
    }

    public boolean isBlueprintApplied() {
        return data.get(16) != 0;
    }

    public PortMode getFaceMode(int buttonIndex) {
        return PortMode.values()[data.get(4 + buttonIndex)];
    }

    public int getInputType() {
        return data.get(10);
    }

    public int getInputId() {
        return data.get(11);
    }

    public float getInputRate() {
        return data.get(12) / 100.0f;
    }

    public int getOutputType() {
        return data.get(13);
    }

    public int getOutputId() {
        return data.get(14);
    }

    public float getOutputRate() {
        return data.get(15) / 100.0f;
    }

    // 把某方向（输入/输出）速率最大的项同步到 data 的 slot 起 3 格：类型(0无/1物品/2流体)、ID、速率×100。
    private void syncRate(int slot, Map<Item, Float> itemRates, Map<Fluid, Float> fluidRates) {
        Item bestItem = null;
        float bestItemRate = 0;
        for (Map.Entry<Item, Float> e : itemRates.entrySet()) {
            if (e.getValue() > bestItemRate) {
                bestItem = e.getKey();
                bestItemRate = e.getValue();
            }
        }
        Fluid bestFluid = null;
        float bestFluidRate = 0;
        for (Map.Entry<Fluid, Float> e : fluidRates.entrySet()) {
            if (e.getValue() > bestFluidRate) {
                bestFluid = e.getKey();
                bestFluidRate = e.getValue();
            }
        }
        if (bestItemRate > 0 && bestItemRate >= bestFluidRate) {
            data.set(slot, 1);
            data.set(slot + 1, BuiltInRegistries.ITEM.getId(bestItem));
            data.set(slot + 2, Math.round(bestItemRate * 100));
        } else if (bestFluidRate > 0) {
            data.set(slot, 2);
            data.set(slot + 1, BuiltInRegistries.FLUID.getId(bestFluid));
            data.set(slot + 2, Math.round(bestFluidRate * 100));
        } else {
            data.set(slot, 0);
            data.set(slot + 1, 0);
            data.set(slot + 2, 0);
        }
    }

    public BlockPos getFactoryPos() {
        return new BlockPos(data.get(1), data.get(2), data.get(3));
    }

    // 面按钮索引 → 世界方向（相对方块朝向）：0=正面、1=顶、2=底、3=左、4=右、5=背面。
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

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (factory != null) {
            if (id >= 0 && id < 6) {
                if (player instanceof ServerPlayer sp) {
                    Direction facing = factory.getBlockState().getValue(HorizontalKineticBlock.HORIZONTAL_FACING);
                    factory.cycleFaceMode(faceForButton(facing, id), sp);
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
        return factory == null || (factory.getLevel() != null
                && factory.getLevel().getBlockEntity(factory.getBlockPos()) == factory);
    }
}

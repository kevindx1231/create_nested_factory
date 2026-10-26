package com.createnestedfactory.create_nested_factory.item;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.Config;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Carries a factory block entity's persistent state when the block is moved by hand.
 * The room survives item loss or destruction and is cleared only by explicit deletion.
 */
public final class FactoryBlockItem extends BlockItem {
    public FactoryBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean selected) {
        super.inventoryTick(stack, level, entity, slotId, selected);
        if (level instanceof ServerLevel serverLevel && entity.tickCount % 20 == 0) {
            NestedFactoryBlockEntity.observePortableItem(serverLevel.getServer(), stack);
        }
    }

    @Override
    public Component getName(net.minecraft.world.item.ItemStack stack) {
        String customName = NestedFactoryBlockEntity.getFactoryItemData(stack).getString("CustomName");
        return customName.isBlank() ? super.getName(stack) : Component.literal(customName);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        if (!com.createnestedfactory.create_nested_factory.command.FactoryCommandService.preparePlacement(context)) {
            return InteractionResult.FAIL;
        }
        if (!canPlaceBoundFactory(context.getLevel(), context.getClickedPos(), context.getItemInHand())) {
            return InteractionResult.FAIL;
        }
        InteractionResult result = super.place(context);
        if (result.consumesAction()) {
            com.createnestedfactory.create_nested_factory.command.FactoryCommandService.recordPlacement(context);
        }
        return result;
    }

    private static boolean canPlaceBoundFactory(Level level, BlockPos pos, net.minecraft.world.item.ItemStack stack) {
        CompoundTag data = NestedFactoryBlockEntity.getFactoryItemData(stack);
        if (!data.getBoolean(NestedFactoryBlockEntity.PORTABLE_BINDING_KEY)) {
            if (!level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)
                    || !(level instanceof ServerLevel serverLevel)) {
                return true;
            }
            NestedFactoryBlockEntity parent = NestedFactoryBlock.findFactoryAt(serverLevel, pos);
            return parent != null
                    && parent.getBounds().isBuildableAt(parent.roomOrigin(), pos)
                    && parent.getNestingDepth() + 1 <= Config.maxNestingDepth + 1;
        }

        boolean nested = data.getBoolean("Nested");
        if (!nested) {
            return !level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION);
        }
        if (!level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }

        NestedFactoryBlockEntity parent = NestedFactoryBlock.findFactoryAt(serverLevel, pos);
        if (parent == null || !parent.getBounds().isBuildableAt(parent.roomOrigin(), pos)) {
            return false;
        }
        String parentId = data.getString("ParentFactoryId");
        if (parentId.isBlank() || !parentId.equals(parent.getFactoryId())) {
            return false;
        }
        int slotId = data.contains("NestedSlotId") ? data.getInt("NestedSlotId") : -1;
        return !data.getString("FactoryId").isBlank() && data.contains("NestedRoomOrigin")
                && slotId >= 0 && PocketRegistry.canClaimNestedSlot(serverLevel.getServer(), slotId,
                new PocketRegistry.FactoryLocation(data.getString("FactoryId"),
                        level.dimension(), pos));
    }
}

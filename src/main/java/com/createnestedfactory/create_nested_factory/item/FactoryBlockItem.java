package com.createnestedfactory.create_nested_factory.item;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Carries a factory block entity's persistent state when the block is moved by hand.
 */
public final class FactoryBlockItem extends BlockItem {
    public FactoryBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(net.minecraft.world.item.ItemStack stack) {
        String customName = NestedFactoryBlockEntity.getFactoryItemData(stack).getString("CustomName");
        return customName.isBlank() ? super.getName(stack) : Component.literal(customName);
    }

    @Override
    public void onDestroyed(ItemEntity entity) {
        if (entity.level() instanceof ServerLevel level) {
            NestedFactoryBlockEntity.destroyPortableItem(level, entity);
        }
        super.onDestroyed(entity);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        if (!canPlaceBoundFactory(context.getLevel(), context.getClickedPos(), context.getItemInHand())) {
            return InteractionResult.FAIL;
        }
        return super.place(context);
    }

    private static boolean canPlaceBoundFactory(Level level, BlockPos pos, net.minecraft.world.item.ItemStack stack) {
        CompoundTag data = NestedFactoryBlockEntity.getFactoryItemData(stack);
        if (!data.getBoolean(NestedFactoryBlockEntity.PORTABLE_BINDING_KEY)) {
            return true;
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
        if (parent.hasRecordedChild()) {
            return false;
        }
        int slotId = data.getInt("NestedSlotId");
        return slotId >= 0 && PocketRegistry.canClaimNestedSlot(slotId,
                new PocketRegistry.FactoryLocation(data.getString("FactoryId"),
                        level.dimension(), pos));
    }
}

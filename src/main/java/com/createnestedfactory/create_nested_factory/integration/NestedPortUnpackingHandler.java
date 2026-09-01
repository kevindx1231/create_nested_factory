package com.createnestedfactory.create_nested_factory.integration;

import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.simibubi.create.api.packager.unpacking.UnpackingHandler;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Unpacks packages arriving at an OUTPUT NestedPort into its shared outward item channel. */
public enum NestedPortUnpackingHandler implements UnpackingHandler {
    INSTANCE;

    @Override
    public boolean unpack(Level level, BlockPos pos, BlockState state, Direction side, List<ItemStack> items,
                          @Nullable PackageOrderWithCrafts orderContext, boolean simulate) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof NestedPortBlockEntity port
                && port.acceptUnpackedItems(items, simulate);
    }
}

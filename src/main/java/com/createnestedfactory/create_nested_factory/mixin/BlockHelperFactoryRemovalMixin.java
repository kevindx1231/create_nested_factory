package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.simibubi.create.foundation.utility.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** Stationary drills and contraption breakers call this helper without a player BreakEvent. */
@Mixin(BlockHelper.class)
abstract class BlockHelperFactoryRemovalMixin {
    @Inject(method = "destroyBlockAs", at = @At("HEAD"), cancellable = true, remap = false)
    private static void createNestedFactory$protectRoom(Level level, BlockPos pos, Player player,
                                                       ItemStack tool, float effectChance,
                                                       Consumer<ItemStack> drops, CallbackInfo callback) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof NestedFactoryBlockEntity factory
                && factory.removalBlocker() != null) callback.cancel();
    }
}

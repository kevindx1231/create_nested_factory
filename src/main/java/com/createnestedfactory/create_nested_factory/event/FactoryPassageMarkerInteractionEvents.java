package com.createnestedfactory.create_nested_factory.event;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Handles marker assignment before held items and tools can consume the right-click. */
@EventBusSubscriber(modid = Create_nested_factory.MODID)
public final class FactoryPassageMarkerInteractionEvents {
    private FactoryPassageMarkerInteractionEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos upperPos = event.getPos();
        BlockState upperState = level.getBlockState(upperPos);
        if (event.getEntity().isShiftKeyDown()
                || !FactoryPassageBlock.isMarkerSlotHit(upperState, upperPos, event.getHitVec())) {
            return;
        }

        if (!level.isClientSide() && event.getHand() == InteractionHand.MAIN_HAND) {
            BlockPos basePos = FactoryPassageBlock.getBasePos(upperPos, upperState);
            ItemStack stack = event.getItemStack();
            if (level.getBlockEntity(basePos) instanceof FactoryPassageBlockEntity passage && passage.setMarker(stack)) {
                level.playSound(null, basePos, stack.isEmpty() ? SoundEvents.ITEM_FRAME_REMOVE_ITEM
                        : SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, .25F, .1F);
            }
        }

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}

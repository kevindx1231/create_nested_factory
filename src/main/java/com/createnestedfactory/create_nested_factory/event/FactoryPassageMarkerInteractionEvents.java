package com.createnestedfactory.create_nested_factory.event;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.FactoryPassageAvailability;
import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
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
        ItemStack stack = event.getItemStack();
        Level level = event.getLevel();
        BlockPos upperPos = event.getPos();
        BlockState upperState = level.getBlockState(upperPos);
        if (stack.isEmpty() || event.getEntity().isShiftKeyDown()
                || !upperState.is(ModBlocks.FACTORY_PASSAGE.get())
                || !FactoryPassageBlock.isMarkerSlotHit(upperState, upperPos, event.getHitVec())) {
            return;
        }

        if (!level.isClientSide()) {
            BlockPos basePos = FactoryPassageBlock.getBasePos(upperPos, upperState);
            if (!(level.getBlockEntity(basePos) instanceof FactoryPassageBlockEntity passage)) {
                return;
            }
            if (FactoryPassageAvailability.isPhysicalized(passage)) {
                if (event.getEntity() instanceof ServerPlayer player) {
                    PlayerMessagePayload.sendTo(player, Component.translatable(
                                    "message.create_nested_factory.passage.physicalized_passage")
                            .withStyle(ChatFormatting.RED), false);
                }
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }
            passage.setMarker(stack);
            level.playSound(null, basePos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, .25F, .1F);
        }

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}

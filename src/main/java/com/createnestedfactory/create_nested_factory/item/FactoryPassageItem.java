package com.createnestedfactory.create_nested_factory.item;

import com.createnestedfactory.create_nested_factory.FactoryPassageAvailability;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** Selects a factory without writing the selection into the passage ItemStack. */
public final class FactoryPassageItem extends BlockItem {
    public FactoryPassageItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (level.getBlockEntity(pos) instanceof NestedFactoryBlockEntity factory) {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                if (FactoryPassageAvailability.isPhysicalized(factory)) {
                    serverPlayer.setData(ModAttachments.PASSAGE_SELECTION,
                            ModAttachments.PassageSelection.empty());
                    PlayerMessagePayload.sendTo(serverPlayer,
                            net.minecraft.network.chat.Component.translatable(
                                            "message.create_nested_factory.passage.physicalized_target")
                                    .withStyle(ChatFormatting.RED), false);
                    return InteractionResult.SUCCESS;
                }
                ModAttachments.FactoryReference reference = new ModAttachments.FactoryReference(
                        level.dimension(), pos.immutable(), factory.getFactoryId(), factory.getRootFactoryId());
                serverPlayer.setData(ModAttachments.PASSAGE_SELECTION,
                        new ModAttachments.PassageSelection(reference));
                PlayerMessagePayload.sendTo(serverPlayer,
                        net.minecraft.network.chat.Component.translatable(
                                        "message.create_nested_factory.passage.selected", factory.getDisplayName())
                                .withStyle(ChatFormatting.AQUA), false);
            }
            return InteractionResult.SUCCESS;
        }
        return super.useOn(context);
    }
}

package com.createnestedfactory.create_nested_factory.item;

import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class SpaceExpanderItem extends Item {
    public SpaceExpanderItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            return InteractionResultHolder.pass(stack);
        }
        NestedFactoryBlockEntity factory = NestedFactoryBlock.findFactoryAt(serverLevel, player.blockPosition());
        if (factory == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (factory.isNested()) {
            PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.space_expand.nested_denied").withStyle(ChatFormatting.RED), false);
            return InteractionResultHolder.fail(stack);
        }
        Direction direction = Direction.orderedByNearest(player)[0];
        if (!factory.expandSpace(serverLevel, direction)) {
            return InteractionResultHolder.pass(stack);
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        serverLevel.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.5F, 1.5F);
        PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.space_expand.success", directionName(direction))
                .withStyle(ChatFormatting.GREEN), true);
        return InteractionResultHolder.success(stack);
    }

    private static Component directionName(Direction direction) {
        return Component.translatable("direction.create_nested_factory." + direction.getSerializedName());
    }
}

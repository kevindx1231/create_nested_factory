package com.createnestedfactory.create_nested_factory.blueprint;

import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.simibubi.create.AllItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public final class FactoryBlueprintInteractions {
    private FactoryBlueprintInteractions() {
    }

    public static boolean isBlueprint(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        return NestedFactoryBlueprint.fromItem(stack, registries) != null;
    }

    public static boolean tryCopy(ServerPlayer player, NestedFactoryBlockEntity factory, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!held.is(AllItems.EMPTY_SCHEMATIC.get())) {
            return false;
        }
        if (!factory.isEnterable() || factory.isInvalidNested()) {
            sendFailure(player, Component.translatable("message.create_nested_factory.blueprint.copy.not_enterable"));
            return true;
        }
        if (factory.getOperationMode() != OperationMode.BLACKBOX_ACTIVE) {
            sendFailure(player, Component.translatable("message.create_nested_factory.blueprint.copy.not_blackbox"));
            return true;
        }
        if (factory.hasPlayersInside()) {
            sendFailure(player, Component.translatable("message.create_nested_factory.blueprint.copy.players_inside"));
            return true;
        }

        NestedFactoryBlueprint blueprint = NestedFactoryBlueprint.fromFactory(factory, player.registryAccess());
        ItemStack result = AllItems.SCHEMATIC.asStack();
        blueprint.writeToItem(result, player.registryAccess());
        player.setItemInHand(hand, result);
        sendSuccess(player, Component.translatable("message.create_nested_factory.blueprint.copy.success"));
        return true;
    }

    public static boolean tryApply(ServerPlayer player, NestedFactoryBlockEntity factory, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        NestedFactoryBlueprint blueprint = NestedFactoryBlueprint.fromItem(held, player.registryAccess());
        if (blueprint == null) {
            return false;
        }
        Component failure = factory.applyBlueprint(blueprint, player);
        if (failure == null) {
            sendSuccess(player, Component.translatable("message.create_nested_factory.blueprint.apply.success"));
        } else {
            sendFailure(player, failure);
        }
        return true;
    }

    public static boolean isTarget(ServerPlayer player, ItemStack stack, NestedFactoryBlockEntity factory) {
        if (stack.is(AllItems.EMPTY_SCHEMATIC.get())) {
            return factory.isRoot() || factory.isEnterable() || factory.isInvalidNested();
        }
        return isBlueprint(stack, player.registryAccess());
    }

    private static void sendSuccess(ServerPlayer player, Component message) {
        PlayerMessagePayload.sendTo(player, message.copy().withStyle(net.minecraft.ChatFormatting.GREEN), false);
    }

    private static void sendFailure(ServerPlayer player, Component message) {
        PlayerMessagePayload.sendTo(player, message.copy().withStyle(net.minecraft.ChatFormatting.RED), false);
    }
}

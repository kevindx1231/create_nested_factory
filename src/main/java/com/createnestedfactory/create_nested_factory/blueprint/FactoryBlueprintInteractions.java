package com.createnestedfactory.create_nested_factory.blueprint;

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

    public static boolean isBlueprint(ItemStack stack) {
        return NestedFactoryBlueprint.fromItem(stack) != null;
    }

    public static boolean tryCopy(ServerPlayer player, NestedFactoryBlockEntity factory, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!held.is(AllItems.EMPTY_SCHEMATIC.get())) {
            return false;
        }
        if (!factory.isEnterable() || factory.isInvalidNested()) {
            sendFailure(player, "仅可复制可进入工厂的黑盒配置。");
            return true;
        }
        if (factory.getOperationMode() != OperationMode.BLACKBOX_ACTIVE) {
            sendFailure(player, "该工厂当前未处于黑盒模式。");
            return true;
        }
        if (factory.hasPlayersInside()) {
            sendFailure(player, "工厂空间内仍有玩家，无法复制蓝图。");
            return true;
        }

        NestedFactoryBlueprint blueprint = NestedFactoryBlueprint.fromFactory(factory);
        ItemStack result = AllItems.SCHEMATIC.asStack();
        blueprint.writeToItem(result);
        player.setItemInHand(hand, result);
        sendSuccess(player, "已复制嵌套工厂蓝图。");
        return true;
    }

    public static boolean tryApply(ServerPlayer player, NestedFactoryBlockEntity factory, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        NestedFactoryBlueprint blueprint = NestedFactoryBlueprint.fromItem(held);
        if (blueprint == null) {
            return false;
        }
        String failure = factory.applyBlueprint(blueprint, player);
        if (failure == null) {
            sendSuccess(player, "蓝图已应用，当前为蓝图模式。");
        } else {
            sendFailure(player, failure);
        }
        return true;
    }

    public static boolean isTarget(ServerPlayer player, ItemStack stack, NestedFactoryBlockEntity factory) {
        if (stack.is(AllItems.EMPTY_SCHEMATIC.get())) {
            return factory.isRoot() || factory.isEnterable() || factory.isInvalidNested();
        }
        return isBlueprint(stack);
    }

    private static void sendSuccess(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal("§a" + text), false);
    }

    private static void sendFailure(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal("§c" + text), false);
    }
}

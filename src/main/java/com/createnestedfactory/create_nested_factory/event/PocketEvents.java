package com.createnestedfactory.create_nested_factory.event;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.blueprint.FactoryBlueprintInteractions;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.NestedPortBlock;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import com.simibubi.create.AllItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = Create_nested_factory.MODID)
public class PocketEvents {
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        boolean emptyBlueprint = stack.is(AllItems.EMPTY_SCHEMATIC.get());
        boolean nestedFactoryBlueprint = FactoryBlueprintInteractions.isBlueprint(stack);
        if (event.getHand() == InteractionHand.MAIN_HAND
                && (emptyBlueprint || nestedFactoryBlueprint)
                && level.getBlockEntity(event.getPos()) instanceof NestedFactoryBlockEntity factory) {
            if (nestedFactoryBlueprint && player.isCrouching()) {
                FactoryBlueprintInteractions.tryApply(player, factory, event.getHand());
                event.setCanceled(true);
                return;
            }
            if (emptyBlueprint) {
                FactoryBlueprintInteractions.tryCopy(player, factory, event.getHand());
                event.setCanceled(true);
                return;
            }
        }

        boolean wrench = event.getItemStack().getItem() instanceof WrenchItem;
        boolean empty = event.getItemStack().isEmpty();

        if (level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            // Create 的扳手会把潜行右键交给自身的拆卸逻辑处理；嵌套子工厂需要优先消费该交互。
            if (wrench && player.isCrouching()
                    && level.getBlockEntity(event.getPos()) instanceof NestedFactoryBlockEntity factory
                    && factory.isNested()) {
                NestedFactoryBlock.enterFactory(player, factory);
                event.setCanceled(true);
                return;
            }

            if ((wrench || empty) && player.isCrouching()
                    && NestedFactoryBlock.isWallBlock(level.getBlockState(event.getPos()))) {
                NestedFactoryBlock.exitCurrentFactory(player);
                event.setCanceled(true);
            }
            return;
        }

        if (!wrench) {
            return;
        }
        if (!(level.getBlockState(event.getPos()).getBlock() instanceof NestedFactoryBlock)) {
            return;
        }
        if (level.getBlockEntity(event.getPos()) instanceof NestedFactoryBlockEntity be) {
            if (player.isCrouching()) {
                NestedFactoryBlock.enterFactory(player, be);
            } else {
                be.cycleFaceMode(event.getHitVec().getDirection(), player);
            }
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!(event.getEntity().getMainHandItem().getItem() instanceof WrenchItem)) {
            return;
        }
        if (event.getState().getBlock() instanceof NestedFactoryBlock
                || event.getState().getBlock() instanceof NestedPortBlock) {
            event.setNewSpeed(100F);
        }
    }

    @SubscribeEvent
    public static void onWallBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof Level level)
                || !level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            return;
        }
        if (NestedFactoryBlock.isWallBlock(event.getState())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onFactoryBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof Level level)
                || !(level.getBlockEntity(event.getPos()) instanceof NestedFactoryBlockEntity factory)) {
            return;
        }
        if (factory.hasRecordedChild()) {
            event.setCanceled(true);
            if (event.getPlayer() instanceof ServerPlayer player) {
                player.displayClientMessage(Component.literal("§c该嵌套工厂存在下级工厂，无法拆除"), false);
            }
        }
    }

    @SubscribeEvent
    public static void onWallBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (event.getEntity().level().dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)
                && NestedFactoryBlock.isWallBlock(event.getState())) {
            event.setNewSpeed(0.0F);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NestedFactoryBlock.resumeSessionForPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NestedFactoryBlock.suspendSessionForPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NestedFactoryBlock.endSessionForPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !player.serverLevel().dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            return;
        }
        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        if (session.isActive() && player.serverLevel().getGameTime() % 120 == 0
                && !player.hasEffect(MobEffects.NIGHT_VISION)) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        }
    }
}

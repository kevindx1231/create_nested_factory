package com.createnestedfactory.create_nested_factory.event;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.NestedPortBlock;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

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

        boolean wrench = event.getItemStack().getItem() instanceof WrenchItem;
        boolean empty = event.getItemStack().isEmpty();

        if (level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            if ((wrench || empty) && player.isCrouching()
                    && NestedFactoryBlock.isWallBlock(level.getBlockState(event.getPos()))) {
                exitPocket(player);
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
                NestedFactoryBlock.enterPocket(player, event.getPos());
            } else {
                be.cycleFaceMode(event.getHitVec().getDirection(), player);
            }
        }
        event.setCanceled(true);
    }

    private static void exitPocket(ServerPlayer player) {
        player.removeEffect(MobEffects.NIGHT_VISION);

        ModAttachments.ReturnData data = player.getData(ModAttachments.RETURN_DATA);
        player.getAbilities().mayfly = data.mayfly();
        player.getAbilities().flying = data.flying();
        player.onUpdateAbilities();

        NestedFactoryBlockEntity factory = NestedFactoryBlock.findFactoryAt(player.serverLevel(), player.blockPosition());
        if (factory != null) {
            factory.onPlayerExited();
        }

        MinecraftServer server = player.serverLevel().getServer();
        ServerLevel target = server.getLevel(data.dimension());
        if (target == null) {
            target = server.overworld();
        }
        player.teleportTo(target, data.pos().x, data.pos().y, data.pos().z, data.yRot(), data.xRot());
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
    public static void onWallBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (event.getEntity().level().dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)
                && NestedFactoryBlock.isWallBlock(event.getState())) {
            event.setNewSpeed(0.0F);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            handlePlayerLeft(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            handlePlayerLeft(player);
        }
    }

    /** 玩家以「非正常走 exitPocket」的方式离开工厂空间（断线 / 死亡）时，补一次计数递减。 */
    private static void handlePlayerLeft(ServerPlayer player) {
        if (!player.serverLevel().dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            return;
        }
        NestedFactoryBlockEntity factory = NestedFactoryBlock.findFactoryAt(player.serverLevel(), player.blockPosition());
        if (factory != null) {
            factory.onPlayerExited();
        }
    }
}

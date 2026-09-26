package com.createnestedfactory.create_nested_factory.client;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.FactoryPassageAvailability;
import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
import com.createnestedfactory.create_nested_factory.registry.ModItems;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Mirrors the Mechanical Arm input outline for held selections and wrench inspection. */
@EventBusSubscriber(modid = Create_nested_factory.MODID, value = Dist.CLIENT)
public final class FactoryPassageHighlightEvents {
    private static Selection selection;
    private static ItemStack selectionStack;

    private FactoryPassageHighlightEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide() || !event.getItemStack().is(ModItems.FACTORY_PASSAGE.get())) {
            return;
        }
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof NestedFactoryBlockEntity factory
                && !FactoryPassageAvailability.isPhysicalized(factory)) {
            selection = new Selection(event.getLevel().dimension(), event.getPos().immutable());
            selectionStack = event.getItemStack();
        } else {
            // Placement consumes the one-shot selection, matching Mechanical Arm behavior.
            selection = null;
            selectionStack = null;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            selection = null;
            selectionStack = null;
            return;
        }

        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        boolean holdingPassage = mainHand.is(ModItems.FACTORY_PASSAGE.get())
                || offHand.is(ModItems.FACTORY_PASSAGE.get());
        if (!holdingPassage || (selectionStack != mainHand && selectionStack != offHand)) {
            selection = null;
            selectionStack = null;
        } else if (selection != null && selection.dimension().equals(level.dimension())) {
            drawFactoryOutline(selection, selection.pos());
        }

        ItemStack held = mainHand.isEmpty() ? offHand : mainHand;
        if (!held.is(Tags.Items.TOOLS_WRENCH)) {
            return;
        }
        HitResult hitResult = minecraft.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHit)) {
            return;
        }
        BlockPos hitPos = blockHit.getBlockPos();
        BlockState hitState = level.getBlockState(hitPos);
        if (!hitState.is(ModBlocks.FACTORY_PASSAGE.get())) {
            return;
        }
        BlockPos basePos = FactoryPassageBlock.getBasePos(hitPos, hitState);
        BlockEntity blockEntity = level.getBlockEntity(basePos);
        if (!(blockEntity instanceof FactoryPassageBlockEntity passage)
                || FactoryPassageAvailability.isPhysicalized(passage)
                || !passage.isBound()
                || passage.getTargetDimension() == null
                || !passage.getTargetDimension().equals(level.dimension())
                || passage.getTargetPos() == null) {
            return;
        }
        drawFactoryOutline(passage, passage.getTargetPos());
    }

    private static void drawFactoryOutline(Object key, BlockPos pos) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.is(ModBlocks.NESTED_FACTORY.get())) {
            return;
        }
        VoxelShape shape = state.getShape(level, pos);
        if (!shape.isEmpty()) {
            Outliner.getInstance().showAABB(key, shape.bounds().move(pos))
                    .colored(ArmInteractionPoint.Mode.TAKE.getColor())
                    .lineWidth(1 / 16f);
        }
    }

    private record Selection(ResourceKey<Level> dimension, BlockPos pos) {
    }
}

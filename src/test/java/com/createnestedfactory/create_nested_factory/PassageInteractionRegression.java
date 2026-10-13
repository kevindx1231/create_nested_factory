package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.event.FactoryPassageMarkerInteractionEvents;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs the actual interaction handler and collision callback with world I/O mocked at their interfaces. */
@SuppressWarnings({"deprecation", "unchecked"})
public final class PassageInteractionRegression {
    private static FactoryPassageBlock block;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        // This headless regression has no FML discovery phase or mod-defined feature flags.
        {
            Bootstrap.bootStrap();
            // The mod loader normally registers this block before registry freeze. Reopen only
            // the block registry here to reproduce that registration in this standalone JVM.
            var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
            LegacyRegistryFixture.reopenWrapper(registry);
            ((net.minecraftforge.registries.ForgeRegistry<?>) net.minecraftforge.registries.ForgeRegistries.BLOCKS).unfreeze();
            Field frozen = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
            frozen.setAccessible(true);
            frozen.set(registry, false);
            Field holders = net.minecraft.core.MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
            holders.setAccessible(true);
            holders.set(registry, new java.util.IdentityHashMap<>());
            block = new FactoryPassageBlock(BlockBehaviour.Properties.of());
            net.minecraft.core.Registry.register(registry,
                    new net.minecraft.resources.ResourceLocation("passage_regression", "passage"), block);
            registry.freeze();
        }
        markerEvents();
        staticCollision();
        endpointIdentity();
        System.out.println("Static passage interactions and endpoint identity regressions passed.");
    }

    private static FactoryPassageBlockEntity passage() throws Exception {
        FactoryPassageBlockEntity passage = mock(FactoryPassageBlockEntity.class);
        Field marker = FactoryPassageBlockEntity.class.getDeclaredField("marker");
        marker.setAccessible(true);
        marker.set(passage, ItemStack.EMPTY);
        when(passage.setMarker(any())).thenCallRealMethod();
        when(passage.getMarker()).thenCallRealMethod();
        return passage;
    }

    private static void markerEvents() throws Exception {
        BlockPos base = new BlockPos(4, 64, 8);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            ServerLevel level = mock(ServerLevel.class);
            Player player = mock(Player.class);
            when(player.level()).thenReturn(level);
            FactoryPassageBlockEntity passage = passage();
            BlockState upper = block.defaultBlockState().setValue(FactoryPassageBlock.HALF, DoubleBlockHalf.UPPER)
                    .setValue(FactoryPassageBlock.FACING, facing);
            when(level.getBlockState(base.above())).thenReturn(upper);
            when(level.getBlockEntity(base)).thenReturn(passage);
            Vec3 localHit = switch (facing) {
                case NORTH -> new Vec3(3.0 / 16, 13.0 / 16, 0);
                case EAST -> new Vec3(1, 13.0 / 16, 3.0 / 16);
                case SOUTH -> new Vec3(13.0 / 16, 13.0 / 16, 1);
                case WEST -> new Vec3(0, 13.0 / 16, 13.0 / 16);
                default -> throw new AssertionError();
            };
            for (Direction reportedFace : Direction.Plane.HORIZONTAL) {
                BlockHitResult hit = new BlockHitResult(localHit.add(Vec3.atLowerCornerOf(base.above())),
                        reportedFace, base.above(), false);
                ItemStack held = new ItemStack(Items.APPLE, 8);
                when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(held);
                var set = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, base.above(), hit);
                FactoryPassageMarkerInteractionEvents.onRightClickBlock(set);
                require(set.isCanceled() && passage.getMarker().is(Items.APPLE), "marker assignment missed the slot");
                require(held.getCount() == 8 && passage.getMarker().getCount() == 1, "marker consumed or copied a full stack");

                when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
                when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(held);
                var clear = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, base.above(), hit);
                FactoryPassageMarkerInteractionEvents.onRightClickBlock(clear);
                require(clear.isCanceled() && passage.getMarker().isEmpty(), "empty-hand click did not clear and consume the marker");
                var offhand = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.OFF_HAND, base.above(), hit);
                FactoryPassageMarkerInteractionEvents.onRightClickBlock(offhand);
                require(offhand.isCanceled() && passage.getMarker().isEmpty(), "offhand restored a just-cleared marker");
                var clearAgain = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, base.above(), hit);
                FactoryPassageMarkerInteractionEvents.onRightClickBlock(clearAgain);
                require(clearAgain.isCanceled(), "empty marker click fell through to the door");

                when(player.isShiftKeyDown()).thenReturn(true);
                var sneak = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, base.above(), hit);
                FactoryPassageMarkerInteractionEvents.onRightClickBlock(sneak);
                require(!sneak.isCanceled(), "marker handler consumed sneaking travel");
                when(player.isShiftKeyDown()).thenReturn(false);
            }
            verify(level, never()).setBlock(any(), any(), anyInt());
            require(upper.rotate(Rotation.CLOCKWISE_90).getValue(FactoryPassageBlock.FACING)
                    == facing.getClockWise(), "block rotation lost the passage facing");
        }
    }

    private static void staticCollision() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        FactoryPassageBlockEntity passage = passage();
        BlockPos base = new BlockPos(10, 64, 12);
        BlockState lower = block.defaultBlockState().setValue(FactoryPassageBlock.OPEN, true);
        when(level.getBlockState(base)).thenReturn(lower);
        when(level.getBlockEntity(base)).thenReturn(passage);
        when(player.position()).thenReturn(new Vec3(10.5, 65, 12.5));
        block.entityInside(lower, level, base, player);
        verify(passage).tryTravel(player);
        when(player.position()).thenReturn(new Vec3(12, 65, 12.5));
        block.entityInside(lower, level, base, player);
        verify(passage, times(1)).tryTravel(player);
    }

    private static void endpointIdentity() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        FactoryPassageSavedData data = new FactoryPassageSavedData();
        BlockPos base = new BlockPos(1, 64, 1);
        data.register(net.minecraft.world.level.Level.OVERWORLD, base, "old", "factory", "root");
        var old = data.findByPassageId("old");
        data.register(net.minecraft.world.level.Level.OVERWORLD, base, "new", "factory", "root");
        FactoryPassageBlockEntity passage = passage();
        when(passage.getPassageId()).thenReturn("new");
        when(passage.getTargetFactoryId()).thenReturn("factory");
        when(server.getLevel(net.minecraft.world.level.Level.OVERWORLD)).thenReturn(level);
        when(level.getBlockState(base)).thenReturn(block.defaultBlockState());
        when(level.getBlockEntity(base)).thenReturn(passage);
        when(level.getBlockState(base.above())).thenReturn(block.defaultBlockState()
                .setValue(FactoryPassageBlock.HALF, DoubleBlockHalf.UPPER));
        try (var savedData = mockStatic(FactoryPassageSavedData.class)) {
            savedData.when(() -> FactoryPassageSavedData.get(server)).thenReturn(data);
            require(FactoryPassageNavigation.resolve(server, old).status() == FactoryPassageNavigation.Status.INVALID,
                    "stale endpoint accepted a replacement at the same coordinates");
            require(data.findByPassageId("new") != null, "stale cleanup unregistered the replacement endpoint");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

}

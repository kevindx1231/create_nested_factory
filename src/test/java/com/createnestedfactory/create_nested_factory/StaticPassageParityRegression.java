package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.IdentityHashMap;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** The identical source is also compiled/run against the read-only 1.21.1 baseline. */
public final class StaticPassageParityRegression {
    private static FactoryPassageBlock block;
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        MockedStatic<?> flags = null;
        try {
            try { flags = mockStatic(Class.forName("net.neoforged.neoforge.common.util.flag.FeatureFlagLoader")); }
            catch (ClassNotFoundException ignored) {}
            Bootstrap.bootStrap();
            var registry = BuiltInRegistries.BLOCK;
            for (Class<?> type = registry.getClass(); type != null; type = type.getSuperclass()) {
                for (String name : new String[]{"frozen", "locked"}) {
                    try { var field = type.getDeclaredField(name); field.setAccessible(true); field.set(registry, false); }
                    catch (NoSuchFieldException ignored) {}
                }
            }
            try {
                Object blocks = Class.forName("net.minecraftforge.registries.ForgeRegistries").getField("BLOCKS").get(null);
                blocks.getClass().getMethod("unfreeze").invoke(blocks);
            } catch (ClassNotFoundException ignored) {}
            var holders = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
            holders.setAccessible(true); holders.set(registry, new IdentityHashMap<>());
            block = new FactoryPassageBlock(BlockBehaviour.Properties.of());
            Registry.register(registry, new ResourceLocation("static_parity", "passage"), block);
            registry.freeze();
        } finally { if (flags != null) flags.close(); }
        // ResourceLocation's constructor is replaced only when compiling this harness for 1.21.1.
        for (Direction facing : Direction.Plane.HORIZONTAL) checkArrival(facing);
        System.out.println("PARITY:static-passage:orientation,original-return,collision-fallback,blocked-retry=PASS");
    }

    private static void checkArrival(Direction facing) throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        var serverField = ServerPlayer.class.getDeclaredField("server");
        serverField.setAccessible(true); serverField.set(player, server);
        when(player.position()).thenReturn(Vec3.ZERO);
        when(player.getBoundingBox()).thenReturn(new AABB(-.3, 0, -.3, .3, 1.8, .3));
        when(player.getXRot()).thenReturn(17f);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        BlockPos base = new BlockPos(10, 64, 10);
        var state = block.defaultBlockState().setValue(FactoryPassageBlock.FACING, facing);
        when(level.getBlockState(base)).thenReturn(state);
        when(level.getBlockState(base.above())).thenReturn(state.setValue(FactoryPassageBlock.HALF, DoubleBlockHalf.UPPER));
        FactoryPassageBlockEntity passage = mock(FactoryPassageBlockEntity.class);
        when(level.getBlockEntity(base)).thenReturn(passage);
        when(passage.getBlockPos()).thenReturn(base);
        when(passage.getLevel()).thenReturn(level);
        when(passage.getBlockState()).thenReturn(state);
        when(passage.getPassageId()).thenReturn("passage");
        when(passage.getTargetFactoryId()).thenReturn("factory");
        FactoryPassageSavedData data = new FactoryPassageSavedData();
        data.register(Level.OVERWORLD, base, "passage", "factory", "root");
        var endpoint = data.findByPassageId("passage");
        try (var saved = mockStatic(FactoryPassageSavedData.class)) {
            saved.when(() -> FactoryPassageSavedData.get(server)).thenReturn(data);
            when(level.noCollision(eq(player), any(AABB.class))).thenReturn(true);
            var ready = FactoryPassageNavigation.arrival(player, endpoint);
            require(ready.status() == FactoryPassageNavigation.Status.READY, "ordinary arrival failed");
            require(ready.destination().position().equals(new Vec3(10.5, 64.125, 10.5)), "ordinary arrival moved");
            Vec3 look = Vec3.atLowerCornerOf(facing.getNormal());
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            require(ready.destination().yRot() == yaw && ready.destination().xRot() == 17f, "orientation changed");
            require(ready.destination().velocity().equals(Vec3.ZERO), "static passage added velocity");
            Vec3 original = new Vec3(10.3, 64.25, 10.4);
            var frame = ModAttachments.ReturnFrame.externalPassage(Level.OVERWORLD, original, 72, -12, "factory", "passage");
            var back = FactoryPassageNavigation.returnArrival(player, frame);
            require(back.destination().position().equals(original)
                    && back.destination().yRot() == 72 && back.destination().xRot() == -12, "exact original return changed");
            when(level.noCollision(eq(player), any(AABB.class))).thenAnswer(call -> {
                AABB box = call.getArgument(1);
                return Math.abs(box.getCenter().x - 9.5) < 1e-8 && Math.abs(box.getCenter().z - 9.5) < 1e-8
                        && Math.abs(box.minY - 63.125) < 1e-8;
            });
            var fallback = FactoryPassageNavigation.arrival(player, endpoint);
            require(fallback.status() == FactoryPassageNavigation.Status.READY
                    && fallback.destination().position().distanceTo(new Vec3(9.5, 63.125, 9.5)) < 1e-8,
                    "safe supported fallback order changed");
            when(level.noCollision(eq(player), any(AABB.class))).thenReturn(false);
            require(FactoryPassageNavigation.arrival(player, endpoint).status() == FactoryPassageNavigation.Status.RETRY_LATER,
                    "blocked arrival did not wait");
            require(data.findByPassageId("passage") != null, "blocked arrival deleted the endpoint");
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

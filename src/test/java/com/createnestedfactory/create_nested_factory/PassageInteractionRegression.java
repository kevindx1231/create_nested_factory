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
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

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
        try (var flags = mockStatic(net.neoforged.neoforge.common.util.flag.FeatureFlagLoader.class)) {
            Bootstrap.bootStrap();
            // The mod loader normally registers this block before registry freeze. Reopen only
            // the block registry here to reproduce that registration in this standalone JVM.
            var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
            Field frozen = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
            frozen.setAccessible(true);
            frozen.set(registry, false);
            Field holders = net.minecraft.core.MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
            holders.setAccessible(true);
            holders.set(registry, new java.util.IdentityHashMap<>());
            block = new FactoryPassageBlock(BlockBehaviour.Properties.of());
            net.minecraft.core.Registry.register(registry,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("passage_regression", "passage"), block);
            registry.freeze();
        }
        markerEvents();
        movingCollision();
        endpointIdentity();
        movementScope();
        movingArrivalSafety();
        movingReturn();
        dynamicReturnCompletion();
        factoryRelocation();
        System.out.println("Passage interaction and moving-space regressions passed.");
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
                    == facing.getClockWise(), "carrier assembly lost the passage facing");
        }
    }

    private static void movingCollision() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        FactoryPassageBlockEntity passage = passage();
        BlockPos plot = new BlockPos(20_481_030, 129, 20_481_032);
        BlockState lower = block.defaultBlockState().setValue(FactoryPassageBlock.OPEN, true);
        when(level.getBlockState(plot)).thenReturn(lower);
        when(level.getBlockEntity(plot)).thenReturn(passage);
        when(passage.getPassageId()).thenReturn("moving");
        when(player.getData(ModAttachments.PASSAGE_TRAVEL_GUARD)).thenReturn(ModAttachments.PassageTravelGuard.empty());
        when(player.position()).thenReturn(new Vec3(10.5, 65, 12.5));
        FactoryPassageNavigation.register(new FactoryPassageNavigation.Adapter() {
            public String id() { return "test"; }
            public FactoryPassageNavigation.Space space(net.minecraft.world.level.Level candidate, BlockPos pos) {
                if (candidate != level) return null;
                return new FactoryPassageNavigation.Space(new FactoryPassageNavigation.Coordinates() {
                    public Vec3 toLocal(Vec3 world) { return Vec3.atLowerCornerOf(plot).add(world.subtract(10, 64, 12)); }
                    public Vec3 toWorld(Vec3 local) { return local.subtract(Vec3.atLowerCornerOf(plot)).add(10, 64, 12); }
                    public Vec3 normalToWorld(Vec3 local) { return local; }
                    public Vec3 velocity(Vec3 local) { return new Vec3(.1, 0, 0); }
                }, true);
            }
        });
        var inside = FactoryPassageBlock.class.getDeclaredMethod("entityInside", BlockState.class,
                net.minecraft.world.level.Level.class, BlockPos.class, net.minecraft.world.entity.Entity.class);
        inside.setAccessible(true);
        inside.invoke(block, lower, level, plot, player);
        verify(passage).tryTravel(player);
        when(player.position()).thenReturn(new Vec3(12, 65, 12.5));
        inside.invoke(block, lower, level, plot, player);
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

    private static void movementScope() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos old = BlockPos.ZERO;
        BlockPos moved = old.offset(100, 0, 0);
        try (var outer = FactoryPassageMovement.begin(level, java.util.List.of(old), level, java.util.List.of(moved))) {
            try (var inner = FactoryPassageMovement.begin(level, java.util.List.of(old), level, java.util.List.of(moved))) {
                require(FactoryPassageMovement.isMoving(level, old), "batch did not suppress source destruction");
                block.onRemove(block.defaultBlockState(), level, old, Blocks.AIR.defaultBlockState(), false);
                verify(level, never()).setBlock(any(), any(), anyInt());
            }
            require(FactoryPassageMovement.isMoving(level, moved), "nested batch released the outer suppression");
        }
        require(!FactoryPassageMovement.isMoving(level, old), "batch suppression leaked after completion");
    }

    private static void movingArrivalSafety() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel target = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        var serverField = ServerPlayer.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        when(server.getLevel(net.minecraft.world.level.Level.OVERWORLD)).thenReturn(target);
        when(target.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(player.position()).thenReturn(Vec3.ZERO);
        when(player.getBoundingBox()).thenReturn(new AABB(-.3, 0, -.3, .3, 1.8, .3));
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        BlockPos plot = new BlockPos(20481032, 131, 20481034);
        var state = block.defaultBlockState().setValue(FactoryPassageBlock.OPEN, true);
        when(target.getBlockState(plot)).thenReturn(state);
        when(target.getBlockState(plot.above())).thenReturn(state.setValue(FactoryPassageBlock.HALF, DoubleBlockHalf.UPPER));
        var passage = mock(FactoryPassageBlockEntity.class);
        when(passage.getPassageId()).thenReturn("source");
        when(passage.getTargetFactoryId()).thenReturn("factory");
        when(passage.getLevel()).thenReturn(target);
        when(passage.getBlockPos()).thenReturn(plot);
        when(passage.getBlockState()).thenReturn(state);
        when(target.getBlockEntity(plot)).thenReturn(passage);
        // Run Minecraft's real block-collision iterator and noCollision method.
        // Sable stores moving blocks in plots, not in the world chunks visited by this iterator.
        boolean[] deck = {true};
        boolean[] ready = {true};
        net.minecraft.world.level.BlockGetter chunk = mock(net.minecraft.world.level.BlockGetter.class);
        when(chunk.getBlockState(any())).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.equals(plot) ? state : pos.equals(plot.above())
                    ? state.setValue(FactoryPassageBlock.HALF, DoubleBlockHalf.UPPER)
                    : deck[0] && pos.getX() == plot.getX() && pos.getY() == plot.getY() - 1
                    && pos.getZ() >= plot.getZ() - 3 && pos.getZ() <= plot.getZ() ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        });
        when(target.getChunkForCollisions(anyInt(), anyInt())).thenReturn(chunk);
        when(target.getEntityCollisions(any(), any())).thenReturn(java.util.List.of());
        when(target.getBlockCollisions(any(), any())).thenCallRealMethod();
        when(target.noCollision(any(), any())).thenCallRealMethod();
        net.minecraft.world.level.border.WorldBorder border = mock(net.minecraft.world.level.border.WorldBorder.class);
        when(border.isWithinBounds(any(AABB.class))).thenReturn(true);
        when(target.getWorldBorder()).thenReturn(border);
        Vec3 center = Vec3.atLowerCornerOf(plot).add(.5, .125, .5);
        Vec3 worldCenter = new Vec3(100.5, 70.125, 200.5);
        double[] angles = {Math.PI / 4, 0};
        var coordinates = new FactoryPassageNavigation.Coordinates() {
            public Vec3 toWorld(Vec3 p) {
                return worldCenter.add(normalToWorld(p.subtract(center)));
            }
            public Vec3 toLocal(Vec3 p) {
                Vec3 d = p.subtract(worldCenter);
                double c = Math.cos(angles[0]), s = Math.sin(angles[0]);
                double ct = Math.cos(angles[1]), st = Math.sin(angles[1]);
                double z = -s*d.x+c*d.z;
                return center.add(c*d.x+s*d.z, ct*d.y+st*z, -st*d.y+ct*z);
            }
            public Vec3 normalToWorld(Vec3 p) {
                double c = Math.cos(angles[0]), s = Math.sin(angles[0]);
                double ct = Math.cos(angles[1]), st = Math.sin(angles[1]);
                double z = st*p.y+ct*p.z;
                return new Vec3(c*p.x-s*z, ct*p.y-st*p.z, s*p.x+c*z);
            }
            public Vec3 velocity(Vec3 p) { return new Vec3(0, 0, -.075); }
        };
        FactoryPassageNavigation.register(new FactoryPassageNavigation.Adapter() {
            public String id() { return "arrival_safety_test"; }
            public FactoryPassageNavigation.Space space(net.minecraft.world.level.Level level, BlockPos pos) {
                return level == target ? new FactoryPassageNavigation.Space(ready[0] ? coordinates : null, true) : null;
            }
        });
        var data = new FactoryPassageSavedData();
        data.register(net.minecraft.world.level.Level.OVERWORLD, plot, "source", "factory", "root");
        try (var saved = mockStatic(FactoryPassageSavedData.class)) {
            saved.when(() -> FactoryPassageSavedData.get(server)).thenReturn(data);
            // Both the wall exit and the interior passage use this arrival resolver.
            var arrival = FactoryPassageNavigation.arrival(player, data.findByPassageId("source"));
            Vec3 panel = coordinates.toWorld(Vec3.atLowerCornerOf(plot).add(.17, .8, .5));
            require(arrival.status() != FactoryPassageNavigation.Status.READY
                            || !player.getBoundingBox().move(arrival.destination().position()).contains(panel),
                    "moving arrival was approved while the player's body intersected the open door panel");

            require(arrival.status() == FactoryPassageNavigation.Status.READY,
                    "moving floor was omitted from support detection");
            require(arrival.localPosition().distanceTo(center.add(0, 0, -1)) < 1e-8,
                    "moving fallback did not choose the nearest supported point in front of the passage");
            angles[0] = 0;
            for (double tilt : new double[]{2e-5, -2e-5, 2e-8, -2e-8}) {
                angles[1] = tilt;
                var tiltedArrival = FactoryPassageNavigation.arrival(player, data.findByPassageId("source"));
                require(tiltedArrival.status() == FactoryPassageNavigation.Status.READY,
                        "tiny carrier tilt made a readable supported passage permanently unavailable: " + tilt);
                require(tiltedArrival.localPosition().distanceTo(center) < 1e-8,
                        "tiny carrier tilt displaced a safe arrival from the passage center");
            }
            angles[0] = Math.PI / 4;
            angles[1] = 0;
            deck[0] = false;
            require(FactoryPassageNavigation.arrival(player, data.findByPassageId("source")).status()
                            == FactoryPassageNavigation.Status.RETRY_LATER,
                    "moving fallback was approved without carrier support");
            arrivalFailureDiagnostics(server, player,
                    () -> FactoryPassageNavigation.arrival(player, data.findByPassageId("source")), () -> deck[0] = true);
            deck[0] = true;

            when(player.serverLevel()).thenReturn(target);
            when(player.position()).thenReturn(worldCenter);
            when(player.getBoundingBox()).thenReturn(new AABB(-.3, 0, -.3, .3, 1.8, .3).move(worldCenter));
            java.util.Map<Object, Object> attachments = new java.util.HashMap<>();
            attachments.put(ModAttachments.PASSAGE_TRAVEL_GUARD,
                    ModAttachments.PassageTravelGuard.arrival("source", net.minecraft.world.level.Level.OVERWORLD, plot));
            doAnswer(call -> attachments.get(call.getArgument(0))).when(player).getData(any(java.util.function.Supplier.class));
            doAnswer(call -> attachments.put(call.getArgument(0), call.getArgument(1)))
                    .when(player).setData(any(java.util.function.Supplier.class), any());
            var inside = FactoryPassageBlock.class.getDeclaredMethod("entityInside", BlockState.class,
                    net.minecraft.world.level.Level.class, BlockPos.class, net.minecraft.world.entity.Entity.class);
            inside.setAccessible(true);
            when(target.getGameTime()).thenReturn(100L);
            inside.invoke(block, state, target, plot, player);
            // No entityInside callbacks: moving geometry still contains the player.
            for (long tick = 101; tick < 165; tick++) {
                when(target.getGameTime()).thenReturn(tick);
                FactoryPassageNavigation.updateTravelGuard(player);
                require(((ModAttachments.PassageTravelGuard) attachments.get(ModAttachments.PASSAGE_TRAVEL_GUARD)).isActive(),
                        "missing collision callbacks released the guard while the player remained inside the moving passage");
            }
            inside.invoke(block, state, target, plot, player);
            verify(passage, never()).tryTravel(player);
            Vec3 outside = worldCenter.add(5, 0, 0);
            when(player.position()).thenReturn(outside);
            when(player.getBoundingBox()).thenReturn(new AABB(-.3, 0, -.3, .3, 1.8, .3).move(outside));
            FactoryPassageNavigation.updateTravelGuard(player);
            require(((ModAttachments.PassageTravelGuard) attachments.get(ModAttachments.PASSAGE_TRAVEL_GUARD)).isActive(),
                    "guard released before confirming the player had left");
            ready[0] = false;
            FactoryPassageNavigation.updateTravelGuard(player);
            FactoryPassageNavigation.updateTravelGuard(player);
            require(((ModAttachments.PassageTravelGuard) attachments.get(ModAttachments.PASSAGE_TRAVEL_GUARD)).isActive(),
                    "temporary carrier unavailability cleared the arrival guard");
            ready[0] = true;
            FactoryPassageNavigation.updateTravelGuard(player);
            require(((ModAttachments.PassageTravelGuard) attachments.get(ModAttachments.PASSAGE_TRAVEL_GUARD)).isActive(),
                    "loading ticks were counted as confirmed departure");
            FactoryPassageNavigation.updateTravelGuard(player);
            require(!((ModAttachments.PassageTravelGuard) attachments.get(ModAttachments.PASSAGE_TRAVEL_GUARD)).isActive(),
                    "guard did not release after the player actually left");
        }
    }

    private static void arrivalFailureDiagnostics(MinecraftServer server, ServerPlayer player,
            java.util.function.Supplier<FactoryPassageNavigation.Arrival> attempt, Runnable restoreSupport) {
        var logger = (org.apache.logging.log4j.core.Logger)
                org.apache.logging.log4j.LogManager.getLogger(FactoryPassageNavigation.class);
        var appender = mock(org.apache.logging.log4j.core.Appender.class);
        when(appender.getName()).thenReturn("passage_failure_regression");
        when(appender.isStarted()).thenReturn(true);
        var messages = new java.util.ArrayList<String>();
        doAnswer(call -> {
            String message = ((org.apache.logging.log4j.core.LogEvent) call.getArgument(0)).getMessage().getFormattedMessage();
            if (message.contains("event=arrival_center_rejected") || message.contains("event=arrival_waiting")) messages.add(message);
            return null;
        }).when(appender).append(any());
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "passage_test"));
        boolean previousDebug = Config.blackboxDebugLogging, previousAdditive = logger.isAdditive();
        var previousLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(org.apache.logging.log4j.Level.INFO);
        logger.setAdditive(false);
        try {
            Config.blackboxDebugLogging = true;
            for (int tick = 200; tick < 240; tick++) {
                when(server.getTickCount()).thenReturn(tick);
                require(attempt.get().status() == FactoryPassageNavigation.Status.RETRY_LATER,
                        "diagnostic throttling changed the unsafe arrival result");
            }
            require(messages.size() == 2, "continuous failure diagnostics were repeated within 40 ticks");
            when(server.getTickCount()).thenReturn(240);
            attempt.get();
            require(messages.size() == 4, "persistent failure diagnostics were not refreshed after 40 ticks");
            restoreSupport.run();
            when(server.getTickCount()).thenReturn(241);
            require(attempt.get().status() == FactoryPassageNavigation.Status.READY,
                    "diagnostic throttling prevented a recovered arrival");
            require(messages.size() == 5, "a changed arrival outcome was suppressed by the diagnostic cooldown");
            Config.blackboxDebugLogging = false;
            when(server.getTickCount()).thenReturn(281);
            attempt.get();
            require(messages.size() == 5, "disabled diagnostics still emitted arrival failures");
        } finally {
            Config.blackboxDebugLogging = previousDebug;
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            restoreSupport.run();
        }
    }

    private static void movingReturn() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerLevel pocket = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        Field serverField = ServerPlayer.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        when(server.getLevel(net.minecraft.world.level.Level.OVERWORLD)).thenReturn(level);
        when(server.getLevel(NestedFactoryBlock.POCKET_DIMENSION)).thenReturn(pocket);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(pocket.dimension()).thenReturn(NestedFactoryBlock.POCKET_DIMENSION);
        when(pocket.getServer()).thenReturn(server);
        when(player.serverLevel()).thenReturn(pocket);
        when(player.position()).thenReturn(Vec3.ZERO);
        when(player.getBoundingBox()).thenReturn(new AABB(-.3, 0, -.3, .3, 1.8, .3));
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        when(level.noCollision(eq(player), any(AABB.class))).thenReturn(true);
        when(level.getBlockCollisions(eq(player), any(AABB.class))).thenReturn(java.util.List.of());
        BlockPos old = new BlockPos(5, 64, 5);
        BlockPos plot = new BlockPos(20_481_030, 129, 20_481_032);
        FactoryPassageSavedData data = new FactoryPassageSavedData();
        data.register(net.minecraft.world.level.Level.OVERWORLD, old, "source", "factory", "root");
        var stale = data.findByPassageId("source");
        data.register(net.minecraft.world.level.Level.OVERWORLD, plot, "source", "factory", "root");
        FactoryPassageBlockEntity passage = passage();
        when(passage.getPassageId()).thenReturn("source");
        when(passage.getTargetFactoryId()).thenReturn("factory");
        when(passage.getLevel()).thenReturn(level);
        when(passage.getBlockPos()).thenReturn(plot);
        when(passage.getBlockState()).thenReturn(block.defaultBlockState());
        when(level.getBlockState(plot)).thenReturn(block.defaultBlockState());
        when(level.getBlockState(plot.above())).thenReturn(block.defaultBlockState()
                .setValue(FactoryPassageBlock.HALF, DoubleBlockHalf.UPPER));
        when(level.getBlockEntity(plot)).thenReturn(passage);
        when(level.getBlockCollisions(eq(player), any(AABB.class))).thenAnswer(call -> {
            AABB query = call.getArgument(1);
            return query.minY < plot.getY() + .0625 ? java.util.List.of(net.minecraft.world.phys.shapes.Shapes.create(
                    new AABB(plot.getX(), plot.getY(), plot.getZ(), plot.getX() + 1, plot.getY() + .0625, plot.getZ() + 1)))
                    : java.util.List.of();
        });
        boolean[] ready = {true};
        boolean[] factoryReady = {true};
        FactoryPassageNavigation.register(new FactoryPassageNavigation.Adapter() {
            public String id() { return "return_test"; }
            public FactoryPassageNavigation.Status prepareFactory(ServerLevel candidate, ModAttachments.FactoryReference reference) {
                return candidate != level || factoryReady[0] ? FactoryPassageNavigation.Status.READY
                        : FactoryPassageNavigation.Status.RETRY_LATER;
            }
            public FactoryPassageNavigation.Space space(net.minecraft.world.level.Level candidate, BlockPos pos) {
                if (candidate != level) return null;
                return new FactoryPassageNavigation.Space(!ready[0] ? null : new FactoryPassageNavigation.Coordinates() {
                    public Vec3 toLocal(Vec3 world) { return world.subtract(100, 70, 200).add(Vec3.atLowerCornerOf(plot)); }
                    public Vec3 toWorld(Vec3 local) { return local.subtract(Vec3.atLowerCornerOf(plot)).add(100, 70, 200); }
                    public Vec3 normalToWorld(Vec3 local) { return new Vec3(-1, .5, 0); }
                    public Vec3 velocity(Vec3 local) { return new Vec3(.2, 0, .1); }
                }, true);
            }
        });
        var reference = new ModAttachments.FactoryReference(net.minecraft.world.level.Level.OVERWORLD,
                BlockPos.ZERO, "factory", "root");
        var frame = ModAttachments.ReturnFrame.externalPassage(net.minecraft.world.level.Level.OVERWORLD,
                Vec3.atCenterOf(old), 0, 0, "factory", "source");
        var session = new ModAttachments.FactorySession("root", "factory", reference,
                false, false, false, false, false, java.util.List.of(frame));
        var originalGuard = ModAttachments.PassageTravelGuard.empty();
        java.util.Map<Object, Object> attachments = new java.util.HashMap<>();
        attachments.put(ModAttachments.FACTORY_SESSION, session);
        attachments.put(ModAttachments.PASSAGE_TRAVEL_GUARD, originalGuard);
        attachments.put(ModAttachments.PASSAGE_RESUME_PENDING, false);
        doAnswer(call -> attachments.get(call.getArgument(0))).when(player).getData(any(java.util.function.Supplier.class));
        doAnswer(call -> attachments.put(call.getArgument(0), call.getArgument(1)))
                .when(player).setData(any(java.util.function.Supplier.class), any());
        NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(factory);
        when(factory.getFactoryId()).thenReturn("factory");
        when(factory.getRootFactoryId()).thenReturn("root");
        when(factory.isEnterable()).thenReturn(true);
        when(factory.getOperationMode()).thenReturn(OperationMode.CHUNK_LOADED);
        when(factory.roomOrigin()).thenReturn(BlockPos.ZERO);
        when(factory.getLevel()).thenReturn(level);
        when(factory.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(factory.requestRoomBuild()).thenReturn(true);
        when(factory.getBounds()).thenReturn(new PocketBounds());
        when(player.getAbilities()).thenReturn(new net.minecraft.world.entity.player.Abilities());
        when(pocket.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.STONE.defaultBlockState());
        try (var savedData = mockStatic(FactoryPassageSavedData.class);
             var messages = mockStatic(com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload.class)) {
            savedData.when(() -> FactoryPassageSavedData.get(server)).thenReturn(data);
            require(FactoryPassageNavigation.resolve(server, stale).passage() == passage,
                    "moved passage was resolved at its old position");
            var arrival = FactoryPassageNavigation.returnArrival(player, frame);
            require(arrival.status() == FactoryPassageNavigation.Status.READY, "moving return did not resolve");
            require(arrival.destination().position().distanceTo(new Vec3(100.5, 70.125, 200.5)) < 1e-8,
                    "moving return used the entry-time world position");
            require(Math.abs(arrival.destination().yRot() - 90) < 1e-5 && arrival.destination().xRot() < -20,
                    "moving return did not rotate the view with the carrier");
            require(arrival.destination().velocity().equals(new Vec3(.2, 0, .1)), "carrier velocity was lost");
            factoryReady[0] = false;
            NestedFactoryBlock.resumeSessionForPlayer(player);
            require(Boolean.TRUE.equals(attachments.get(ModAttachments.PASSAGE_RESUME_PENDING)),
                    "login did not defer a loading physical factory");
            require(attachments.get(ModAttachments.FACTORY_SESSION) == session, "deferred login discarded its session");
            verify(factory, never()).onPlayerEntered(any(), any());
            factoryReady[0] = true;
            NestedFactoryBlock.resumeSessionForPlayer(player);
            require(Boolean.FALSE.equals(attachments.get(ModAttachments.PASSAGE_RESUME_PENDING)),
                    "ready login retained its pending flag");
            verify(factory).onPlayerEntered(frame.dimension(), BlockPos.containing(frame.pos()));
            ready[0] = false;
            require(!NestedFactoryBlock.exitCurrentFactoryViaPassage(player, null, null, 0),
                    "unloaded carrier allowed an exit");
            require(attachments.get(ModAttachments.FACTORY_SESSION) == session, "loading return consumed its session");
            require(data.findByPassageId("source") != null, "loading return discarded the passage index");
            ready[0] = true;
            when(level.noCollision(eq(player), any(AABB.class))).thenReturn(false);
            require(FactoryPassageNavigation.returnArrival(player, frame).status() == FactoryPassageNavigation.Status.RETRY_LATER,
                    "obstructed return used an unsafe arrival");
            when(level.noCollision(eq(player), any(AABB.class))).thenReturn(true);
            require(!NestedFactoryBlock.exitCurrentFactoryViaPassage(player, null, null, 0),
                    "cancelled teleport reported success");
            verify(factory, never()).onPlayerExited();
            require(attachments.get(ModAttachments.FACTORY_SESSION) == session, "failed teleport consumed its session");
            require(attachments.get(ModAttachments.PASSAGE_TRAVEL_GUARD) == originalGuard,
                    "failed teleport changed the arrival guard");
            require(!NestedFactoryBlock.enterFactoryViaPassage(player, factory, null, 0, "source"),
                    "cancelled passage entry reported success");
            require(attachments.get(ModAttachments.FACTORY_SESSION) == session, "cancelled entry changed the return stack");
            verify(factory).onPlayerExited();
            when(player.teleportTo(eq(level), anyDouble(), anyDouble(), anyDouble(), anySet(), anyFloat(), anyFloat()))
                    .thenReturn(true);
            require(!NestedFactoryBlock.exitCurrentFactoryViaPassage(player, null, null, 0),
                    "teleport true without a dimension change consumed the return session");
            require(attachments.get(ModAttachments.FACTORY_SESSION) == session, "dimension refusal consumed the session");
            require(attachments.get(ModAttachments.PASSAGE_TRAVEL_GUARD) == originalGuard,
                    "dimension refusal retained an arrival guard");
            when(player.teleportTo(eq(level), anyDouble(), anyDouble(), anyDouble(), anySet(), anyFloat(), anyFloat()))
                    .thenAnswer(call -> { when(player.serverLevel()).thenReturn(level); return true; });
            require(NestedFactoryBlock.exitCurrentFactoryViaPassage(player, null, null, 0), "ready return did not complete");
            verify(factory, times(2)).onPlayerExited();
            verify(player).setDeltaMovement(new Vec3(.2, 0, .1));
            require(!((ModAttachments.FactorySession) attachments.get(ModAttachments.FACTORY_SESSION)).isActive(),
                    "completed external return kept its session");
            require(((ModAttachments.PassageTravelGuard) attachments.get(ModAttachments.PASSAGE_TRAVEL_GUARD))
                    .blocks("source"), "return guard lost passage identity");
        }
    }

    private static void dynamicReturnCompletion() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerLevel pocket = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        Field serverField = ServerPlayer.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        when(server.getLevel(net.minecraft.world.level.Level.OVERWORLD)).thenReturn(level);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(pocket.dimension()).thenReturn(NestedFactoryBlock.POCKET_DIMENSION);
        when(pocket.getServer()).thenReturn(server);
        when(player.position()).thenReturn(Vec3.ZERO);
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "return_test"));
        NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(factory);
        when(factory.getFactoryId()).thenReturn("dynamic_factory");
        when(factory.getRootFactoryId()).thenReturn("dynamic_root");
        when(server.getLevel(NestedFactoryBlock.POCKET_DIMENSION)).thenReturn(pocket);
        when(pocket.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.STONE.defaultBlockState());
        when(factory.isEnterable()).thenReturn(true);
        when(factory.getOperationMode()).thenReturn(OperationMode.CHUNK_LOADED);
        when(factory.getLevel()).thenReturn(level);
        when(factory.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(factory.getBounds()).thenReturn(new PocketBounds());
        when(factory.roomOrigin()).thenReturn(BlockPos.ZERO);
        var anchor = new ModAttachments.DynamicReturnAnchor("dynamic_return_test", 1, "dynamic_factory",
                "carrier", Vec3.ZERO, new Vec3(0, 0, 1), null);
        var reference = new ModAttachments.FactoryReference(net.minecraft.world.level.Level.OVERWORLD,
                BlockPos.ZERO, "dynamic_factory", "dynamic_root");
        var frame = ModAttachments.ReturnFrame.external(net.minecraft.world.level.Level.OVERWORLD,
                new Vec3(1, 64, 1), 0, 0, "dynamic_factory", anchor);
        var session = new ModAttachments.FactorySession("dynamic_root", "dynamic_factory", reference,
                false, false, false, false, false, java.util.List.of(frame));
        java.util.Map<Object, Object> attachments = new java.util.HashMap<>();
        attachments.put(ModAttachments.PASSAGE_RESUME_PENDING, false);
        doAnswer(call -> attachments.get(call.getArgument(0))).when(player).getData(any(java.util.function.Supplier.class));
        doAnswer(call -> attachments.put(call.getArgument(0), call.getArgument(1)))
                .when(player).setData(any(java.util.function.Supplier.class), any());
        var calls = new java.util.ArrayList<String>();
        FactoryReturnAnchors.register(new FactoryReturnAnchors.Adapter() {
            public String id() { return "dynamic_return_test"; }
            public ModAttachments.DynamicReturnAnchor capture(ServerPlayer candidate, NestedFactoryBlockEntity target) { return null; }
            public FactoryReturnAnchors.Resolution resolve(ServerPlayer candidate, ModAttachments.DynamicReturnAnchor value,
                                                           NestedFactoryBlockEntity target) {
                require(target == factory, "dynamic return resolved against another factory");
                return FactoryReturnAnchors.Resolution.resolved(new FactoryReturnAnchors.Destination(
                        net.minecraft.world.level.Level.OVERWORLD, new Vec3(20, 70, 30), 0, 0, Vec3.ZERO));
            }
            public void arrived(ServerPlayer candidate, ModAttachments.DynamicReturnAnchor value,
                                NestedFactoryBlockEntity target) {
                require(candidate.serverLevel() == level, "arrival ran before changing dimension");
                require(attachments.get(ModAttachments.FACTORY_SESSION) == session,
                        "arrival ran after releasing its return frame");
                calls.add("arrived");
            }
            public void release(MinecraftServer candidate, java.util.UUID playerId, ModAttachments.DynamicReturnAnchor value) {
                calls.add("release");
            }
        });
        when(player.teleportTo(eq(level), anyDouble(), anyDouble(), anyDouble(), anySet(), anyFloat(), anyFloat()))
                .thenAnswer(call -> { when(player.serverLevel()).thenReturn(level); return true; });
        attachments.put(ModAttachments.FACTORY_SESSION, session);
        when(player.serverLevel()).thenReturn(pocket);
        require(NestedFactoryBlock.exitCurrentFactoryViaPassage(player, null, null, 0), "dynamic return failed");
        require(calls.equals(java.util.List.of("arrived", "release")), "arrival must precede session release");

        calls.clear();
        attachments.put(ModAttachments.FACTORY_SESSION, session);
        when(player.serverLevel()).thenReturn(pocket);
        when(player.teleportTo(eq(level), anyDouble(), anyDouble(), anyDouble(), anySet(), anyFloat(), anyFloat()))
                .thenReturn(false);
        doAnswer(call -> { when(player.serverLevel()).thenReturn(level); return null; })
                .when(player).teleportTo(eq(level), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());
        require(NestedFactoryBlock.exitCurrentFactoryViaPassage(player, null, null, 0), "static fallback failed");
        require(calls.equals(java.util.List.of("release")), "static fallback incorrectly initialized dynamic following");
        verify(player).teleportTo(level, 1, 64, 1, 0, 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void factoryRelocation() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        when(server.getLevel(net.minecraft.world.level.Level.OVERWORLD)).thenReturn(level);
        BlockPos relocated = new BlockPos(20_481_040, 130, 20_481_040);
        when(level.getBlockEntity(relocated)).thenReturn(factory);
        when(factory.getFactoryId()).thenReturn("relocated_factory");
        boolean[] loaded = {false};
        FactoryPassageNavigation.register(new FactoryPassageNavigation.Adapter() {
            public String id() { return "factory_test"; }
            public FactoryPassageNavigation.Space space(net.minecraft.world.level.Level candidate, BlockPos pos) { return null; }
            public FactoryPassageNavigation.Status prepareFactory(ServerLevel candidate, ModAttachments.FactoryReference reference) {
                if (candidate == level) loaded[0] = true;
                return FactoryPassageNavigation.Status.READY;
            }
        });
        var reference = new ModAttachments.FactoryReference(net.minecraft.world.level.Level.OVERWORLD,
                BlockPos.ZERO, "relocated_factory", "root");
        try (var registry = mockStatic(PocketRegistry.class)) {
            registry.when(() -> PocketRegistry.findFactoryLocationById(server, "relocated_factory"))
                    .thenAnswer(call -> new PocketRegistry.FactoryLocation("relocated_factory",
                            net.minecraft.world.level.Level.OVERWORLD, loaded[0] ? relocated : BlockPos.ZERO));
            require(FactoryPassageNavigation.resolveFactory(server, reference).factory() == factory,
                    "factory lookup used the plot position from before carrier loading");
            when(factory.isSimulatedMoveInProgress()).thenReturn(true);
            require(FactoryPassageNavigation.resolveFactory(server, reference).status() == FactoryPassageNavigation.Status.RETRY_LATER,
                    "in-progress factory migration was treated as a missing factory");
            verify(level, never()).getChunkAt(BlockPos.ZERO);
        }
    }
}

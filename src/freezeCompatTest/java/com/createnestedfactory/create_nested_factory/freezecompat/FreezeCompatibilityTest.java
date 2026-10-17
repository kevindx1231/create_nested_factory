package com.createnestedfactory.create_nested_factory.freezecompat;

import com.createnestedfactory.create_nested_factory.PocketFreezeHooks;
import com.createnestedfactory.create_nested_factory.PocketFreezeSavedData;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.authlib.GameProfile;
import com.createnestedfactory.create_nested_factory.mixin.accessor.ServerLevelBlockEventsAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the real transformed Level tick loop, including third-party profiler redirects. */
@Mod("cnf_freeze_test")
public final class FreezeCompatibilityTest {
    private MinecraftServer server;
    private int ticks;
    private static final String ROOT = "cnf-freeze-integration-test";
    private static final BlockPos POS = new BlockPos(8, 130, 8);

    public FreezeCompatibilityTest() {
        RandomTickProbe.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        if (Boolean.getBoolean("cnf.freezeCompatTest")) {
            MinecraftForge.EVENT_BUS.addListener(this::started);
            MinecraftForge.EVENT_BUS.addListener(this::tick);
        }
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
        for (ServerLevel level : List.of(server.overworld(), pocket())) {
            level.setChunkForced(0, 0, true);
            level.getChunk(0, 0);
        }
    }

    private ServerLevel pocket() {
        return java.util.Objects.requireNonNull(server.getLevel(NestedFactoryBlock.POCKET_DIMENSION));
    }

    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || ++ticks != 60) return;
        try {
            blockEntityLoop();
            randomTicks();
            scheduledTicksAndEvents();
            entitiesAndPlayers();
            System.out.println("[CNF-FREEZE-TEST] PASS: transformed block-entity tick, XYZ/dimension isolation, thaw, Observable samples/context, block/fluid random ticks, scheduled ticks/events/reload, entities/players");
            server.halt(false);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.err.println("[CNF-FREEZE-TEST] FAIL: " + failure);
            // System.exit on the server thread deadlocks with Minecraft's shutdown hook joining it.
            Runtime.getRuntime().halt(1);
        }
    }

    private void randomTicks() {
        ServerLevel level = pocket();
        var chunk = level.getChunk(0, 0);
        PocketFreezeSavedData data = PocketFreezeSavedData.get(server);
        BlockStateProbe.fill(chunk, RandomTickProbe.BLOCK.get().defaultBlockState());
        java.util.Arrays.fill(RandomTickProbe.blockTicks, 0);
        java.util.Arrays.fill(RandomTickProbe.fluidTicks, 0);
        data.beginFreeze(ROOT, List.of(new PocketFreezeSavedData.FrozenRoom(ROOT, "minecraft:overworld",
                BlockPos.ZERO, 0, 128, 0, 15, 143, 15, 1)));
        try {
            level.tickChunk(chunk, 64);
            check(RandomTickProbe.blockTicks[0] == 0 && RandomTickProbe.fluidTicks[0] == 0,
                    "frozen section must execute zero block/fluid random ticks");
            check(RandomTickProbe.blockTicks[1] == 64 && RandomTickProbe.fluidTicks[1] == 64,
                    "same chunk other height must execute its block/fluid random ticks");
            data.removeTree(ROOT);
            level.tickChunk(chunk, 64);
            check(RandomTickProbe.blockTicks[0] == 64 && RandomTickProbe.fluidTicks[0] == 64
                    && RandomTickProbe.blockTicks[1] == 128 && RandomTickProbe.fluidTicks[1] == 128,
                    "thaw must restore each random tick exactly once");
        } finally {
            data.removeTree(ROOT);
            BlockStateProbe.fill(chunk, Blocks.AIR.defaultBlockState());
            var area = new net.minecraft.world.level.levelgen.structure.BoundingBox(0, 128, 0, 15, 175, 15);
            level.getBlockTicks().clearArea(area);
            level.getFluidTicks().clearArea(area);
        }
    }

    private static final class BlockStateProbe {
        private static void fill(net.minecraft.world.level.chunk.LevelChunk chunk,
                                 net.minecraft.world.level.block.state.BlockState state) {
            for (int baseY : new int[]{128, 160}) {
                for (BlockPos pos : BlockPos.betweenClosed(0, baseY, 0, 15, baseY + 15, 15)) {
                    chunk.setBlockState(pos, state, false);
                }
            }
        }
    }

    private void scheduledTicksAndEvents() {
        ServerLevel level = pocket();
        PocketFreezeHooks.registerLevel(level);
        PocketFreezeSavedData data = PocketFreezeSavedData.get(server);
        var room = new PocketFreezeSavedData.FrozenRoom(ROOT, "minecraft:overworld", BlockPos.ZERO,
                8, 128, 8, 10, 143, 10, 1);
        long now = level.getGameTime();
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, POS, now + 17, TickPriority.HIGH, 4));
        level.blockEvent(POS, Blocks.STONE, 1, 2);
        data.beginFreeze(ROOT, List.of(room));
        try {
            PocketFreezeHooks.capturePhysicalQueues(level, ROOT, List.of(room));
            level.getFluidTicks().schedule(new ScheduledTick<>(Fluids.WATER, POS, now + 23, TickPriority.LOW, 5));
            level.blockEvent(POS, Blocks.STONE, 2, 3);
            var loaded = PocketFreezeSavedData.load(data.save(new CompoundTag(), null), null);
            var persisted = loaded.removeTree(ROOT);
            check(persisted.scheduledTicks().size() == 2 && persisted.blockEvents().size() == 2,
                    "captured physical/new scheduled ticks and events must survive reload");
            check(persisted.scheduledTicks().get(0).remainingDelay() == 17,
                    "remaining scheduled delay must survive reload");
            check(!level.getBlockTicks().hasScheduledTick(POS, Blocks.STONE)
                    && !level.getFluidTicks().hasScheduledTick(POS, Fluids.WATER), "frozen scheduled ticks must leave live queues");
            check(((ServerLevelBlockEventsAccessor) level).createNestedFactory$getBlockEvents().stream()
                    .noneMatch(event -> event.pos().equals(POS)), "frozen block events must leave the live queue");
            data.beginThaw(ROOT);
            var snapshot = data.removeTree(ROOT);
            PocketFreezeHooks.restorePhysicalQueues(level, snapshot);
            check(((ServerLevelBlockEventsAccessor) level).createNestedFactory$getBlockEvents().stream()
                    .filter(event -> event.pos().equals(POS)).count() == 2, "thaw must restore both events once");
            int[] executed = {0};
            level.getBlockTicks().tick(now + 16, 100, (pos, block) -> { if (pos.equals(POS)) executed[0]++; });
            check(executed[0] == 0, "restored tick must preserve remaining delay");
            level.getBlockTicks().tick(now + 17, 100, (pos, block) -> { if (pos.equals(POS)) executed[0]++; });
            level.getBlockTicks().tick(now + 100, 100, (pos, block) -> { if (pos.equals(POS)) executed[0]++; });
            check(executed[0] == 1, "restored block tick must execute exactly once");
            executed[0] = 0;
            level.getFluidTicks().tick(now + 23, 100, (pos, fluid) -> { if (pos.equals(POS)) executed[0]++; });
            level.getFluidTicks().tick(now + 100, 100, (pos, fluid) -> { if (pos.equals(POS)) executed[0]++; });
            check(executed[0] == 1, "restored fluid tick must execute exactly once");
        } finally {
            data.removeTree(ROOT);
            ((ServerLevelBlockEventsAccessor) level).createNestedFactory$getBlockEvents()
                    .removeIf(event -> event.pos().equals(POS));
        }
    }

    private void entitiesAndPlayers() {
        ServerLevel level = pocket();
        PocketFreezeSavedData data = PocketFreezeSavedData.get(server);
        data.beginFreeze(ROOT, List.of(new PocketFreezeSavedData.FrozenRoom(ROOT, "minecraft:overworld",
                BlockPos.ZERO, 8, 128, 8, 10, 143, 10, 1)));
        ItemEntity crossing = new ItemEntity(level, 7.99, 130, 8.5, new ItemStack(Items.STONE));
        ServerPlayer player = new ServerPlayer(server, level,
                new GameProfile(java.util.UUID.randomUUID(), "FreezeTest")) {
            @Override public void tick() { /* No connection is needed for this player-exclusion probe. */ }
        };
        player.setPos(8.5, 130, 8.5);
        try {
            level.tickNonPassenger(crossing);
            level.tickNonPassenger(player);
            check(crossing.tickCount == 0, "an entity crossing the room edge must freeze");
            check(player.tickCount == 1, "players inside frozen rooms must continue ticking");
            data.removeTree(ROOT);
            level.tickNonPassenger(crossing);
            check(crossing.tickCount == 1, "thawed entity must resume ticking once");
        } finally {
            data.removeTree(ROOT);
            crossing.discard();
        }
    }

    private void blockEntityLoop() throws Exception {
        ServerLevel pocket = pocket();
        ServerLevel overworld = server.overworld();
        check(pocket.shouldTickBlocksAt(POS) && overworld.shouldTickBlocksAt(POS), "test chunks must actually tick");
        Method tickLoop = ObfuscationReflectionHelper.findMethod(Level.class, "m_46463_");
        Counter frozen = new Counter(POS);
        Counter below = new Counter(POS.below(32));
        Counter outside = new Counter(POS.offset(5, 0, 0));
        Counter otherDimension = new Counter(POS);
        pocket.addBlockEntityTicker(frozen);
        pocket.addBlockEntityTicker(below);
        pocket.addBlockEntityTicker(outside);
        overworld.addBlockEntityTicker(otherDimension);
        PocketFreezeSavedData data = PocketFreezeSavedData.get(server);
        data.beginFreeze(ROOT, List.of(new PocketFreezeSavedData.FrozenRoom(ROOT, "minecraft:overworld",
                BlockPos.ZERO, 8, 128, 8, 10, 143, 10, 1)));
        Object profiler = null;
        boolean wasInactive = true;
        if (ModList.get().isLoaded("observable")) {
            Class<?> observable = Class.forName("observable.Observable");
            // Enumerating Observable's methods also resolves its client-only return types on a server.
            var delegate = observable.getDeclaredField("PROFILER$delegate");
            delegate.setAccessible(true);
            profiler = Class.forName("kotlin.Lazy").getMethod("getValue").invoke(delegate.get(null));
            wasInactive = (boolean) profiler.getClass().getMethod("getNotProcessing").invoke(profiler);
            profiler.getClass().getMethod("setNotProcessing", boolean.class).invoke(profiler, false);
            timings(profiler).clear();
        }
        try {
            for (int i = 0; i < 8; i++) {
                tickLoop.invoke(pocket);
                tickLoop.invoke(overworld);
            }
            check(frozen.count == 0, "frozen ticker must execute zero times");
            check(below.count == 8 && outside.count == 8 && otherDimension.count == 8,
                    "same chunk other heights, XYZ neighbors and other dimensions must execute once per tick");
            if (profiler != null) {
                check(samples(profiler, pocket, POS) == 0, "frozen ticker must not create Observable samples");
                check(samples(profiler, pocket, below.pos) == 8 && samples(profiler, pocket, outside.pos) == 8
                        && samples(profiler, overworld, POS) == 8, "Observable samples must equal real tick counts");
                assertProfilerContextCleared();
            }
            data.beginThaw(ROOT);
            tickLoop.invoke(pocket);
            check(frozen.count == 0, "THAWING transaction must remain frozen until removal");
            data.removeTree(ROOT);
            int neighborBefore = below.count;
            for (int i = 0; i < 8; i++) tickLoop.invoke(pocket);
            check(frozen.count == 8 && below.count - neighborBefore == 8, "thaw must resume exactly once per tick");
            if (profiler != null) {
                check(samples(profiler, pocket, POS) == 8, "thaw must produce exactly one sample per real tick");
                assertProfilerContextCleared();
            }
        } finally {
            data.removeTree(ROOT);
            frozen.removed = below.removed = outside.removed = otherDimension.removed = true;
            if (profiler != null) profiler.getClass().getMethod("setNotProcessing", boolean.class).invoke(profiler, wasInactive);
            tickLoop.invoke(pocket);
            tickLoop.invoke(overworld);
        }
    }

    private static Map<?, ?> timings(Object profiler) throws Exception {
        return (Map<?, ?>) profiler.getClass().getMethod("getBlockTimingsMap").invoke(profiler);
    }

    private static int samples(Object profiler, ServerLevel level, BlockPos pos) throws Exception {
        Map<?, ?> dimension = (Map<?, ?>) timings(profiler).get(level.dimension());
        Object timing = dimension == null ? null : dimension.get(pos);
        return timing == null ? 0 : (int) timing.getClass().getMethod("getTicks").invoke(timing);
    }

    private static void assertProfilerContextCleared() throws Exception {
        AtomicReference<?> target = (AtomicReference<?>) Class.forName("observable.Props").getField("currentTarget").get(null);
        check(target.get() == null, "Observable timing context leaked");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Counter implements TickingBlockEntity {
        private final BlockPos pos;
        private int count;
        private boolean removed;
        private Counter(BlockPos pos) { this.pos = pos; }
        public void tick() { count++; }
        public boolean isRemoved() { return removed; }
        public BlockPos getPos() { return pos; }
        public String getType() { return "cnf_freeze_test:counter"; }
    }
}

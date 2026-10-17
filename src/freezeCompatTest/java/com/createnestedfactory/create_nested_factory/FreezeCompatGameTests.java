package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;

/** Exercises the real transformed Level tick loop, including optional Observable profiling. */
@GameTestHolder("create_nested_factory")
@PrefixGameTestTemplate(false)
public final class FreezeCompatGameTests {
    private static final String ROOT = "freeze-compat-regression";

    private FreezeCompatGameTests() {
    }

    @GameTest(template = "freeze_compat_empty", timeoutTicks = 240)
    public static void blockEntityFreezePreservesOtherMods(GameTestHelper helper) throws Exception {
        ServerLevel pocket = helper.getLevel().getServer().getLevel(NestedFactoryBlock.POCKET_DIMENSION);
        helper.assertTrue(pocket != null, "Pocket dimension must exist");
        PocketFreezeSavedData data = PocketFreezeSavedData.get(pocket.getServer());
        CountingTicker frozen = new CountingTicker(new BlockPos(1, 130, 1));
        CountingTicker live = new CountingTicker(new BlockPos(1, 150, 1));
        CountingTicker overworld = new CountingTicker(helper.absolutePos(new BlockPos(1, 1, 1)));
        boolean alreadyForced = pocket.getForcedChunks().contains(0L);
        boolean observable = ModList.get().isLoaded("observable");
        boolean previousNotProcessing = !observable || observableProps().getField("notProcessing").getBoolean(null);
        if (observable) observableProps().getField("notProcessing").setBoolean(null, false);
        pocket.setChunkForced(0, 0, true);
        data.beginFreeze(ROOT, List.of(new PocketFreezeSavedData.FrozenRoom(
                ROOT, "minecraft:overworld", BlockPos.ZERO, 0, 128, 0, 15, 143, 15, 1)));
        pocket.addBlockEntityTicker(frozen);
        pocket.addBlockEntityTicker(live);
        helper.getLevel().addBlockEntityTicker(overworld);

        Runnable cleanup = () -> {
            frozen.removed = live.removed = overworld.removed = true;
            data.removeTree(ROOT);
            if (!alreadyForced) pocket.setChunkForced(0, 0, false);
            if (observable) {
                try {
                    observableProps().getField("notProcessing").setBoolean(null, previousNotProcessing);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("Could not restore Observable profiler state", failure);
                }
            }
        };

        helper.runAfterDelay(60, () -> {
            try {
                helper.assertTrue(frozen.ticks == 0, "Frozen block entity executed");
                helper.assertTrue(live.ticks > 0, "Live block entity in same chunk did not tick");
                helper.assertTrue(overworld.ticks > 0, "Overworld block entity did not tick");
                if (observable) {
                    assertProfile(helper, pocket, frozen, false);
                    assertProfile(helper, pocket, live, true);
                    assertProfile(helper, helper.getLevel(), overworld, true);
                }
                data.beginThaw(ROOT);
                helper.assertTrue(data.isFrozen(frozen.pos), "Thaw preparation released the freeze too early");
                data.removeTree(ROOT);
                int liveBefore = live.ticks;
                helper.runAfterDelay(10, () -> {
                    try {
                        helper.assertTrue(frozen.ticks > 0, "Thawed block entity did not resume");
                        helper.assertTrue(frozen.ticks == live.ticks - liveBefore,
                                "Thawed block entity ticked a different number of times from its live neighbor");
                        if (observable) {
                            assertProfile(helper, pocket, frozen, true);
                            assertProfile(helper, pocket, live, true);
                        }
                        helper.succeed();
                    } finally {
                        cleanup.run();
                    }
                });
            } catch (Throwable failure) {
                cleanup.run();
                throw failure;
            }
        });
    }

    private static Class<?> observableProps() throws ClassNotFoundException {
        return Class.forName("observable.Props");
    }

    private static void assertProfile(GameTestHelper helper, ServerLevel level,
                                      CountingTicker ticker, boolean expected) {
        try {
            Object profiler = observableProfiler();
            Map<?, ?> dimensions = (Map<?, ?>) profiler.getClass().getMethod("getBlockTimingsMap").invoke(profiler);
            Map<?, ?> positions = (Map<?, ?>) dimensions.get(level.dimension());
            Object timing = positions == null ? null : positions.get(ticker.pos);
            helper.assertTrue((timing != null) == expected, "Unexpected Observable sample at " + ticker.pos);
            if (timing != null) {
                int ticks = (Integer) timing.getClass().getMethod("getTicks").invoke(timing);
                helper.assertTrue(ticks == ticker.ticks, "Observable lost or duplicated block entity ticks");
                Object current = ((java.util.concurrent.atomic.AtomicReference<?>) observableProps()
                        .getField("currentTarget").get(null)).get();
                helper.assertTrue(current == null, "Observable profiling context leaked after a tick");
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect Observable profiling", failure);
        }
    }

    private static Object observableProfiler() {
        try {
            // Resolve only the server getter; reflection enumerates client-only method signatures too.
            Class<?> observableClass = Class.forName("observable.Observable");
            Class<?> profilerClass = Class.forName("observable.server.Profiler");
            Object instance = observableClass.getField("INSTANCE").get(null);
            return MethodHandles.publicLookup().findVirtual(observableClass, "getPROFILER",
                    MethodType.methodType(profilerClass)).invoke(instance);
        } catch (Throwable failure) {
            throw new AssertionError("Could not inspect Observable profiler", failure);
        }
    }

    private static final class CountingTicker implements TickingBlockEntity {
        private final BlockPos pos;
        private int ticks;
        private boolean removed;

        private CountingTicker(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public void tick() {
            ticks++;
        }

        @Override
        public boolean isRemoved() {
            return removed;
        }

        @Override
        public BlockPos getPos() {
            return pos;
        }

        @Override
        public String getType() {
            return "minecraft:furnace";
        }
    }
}

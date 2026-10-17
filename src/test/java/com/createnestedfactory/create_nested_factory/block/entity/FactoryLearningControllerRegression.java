package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.Config;
import com.createnestedfactory.create_nested_factory.block.FactoryPowerProfile;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import static org.mockito.Mockito.*;

/** Runs the real learning controller, source adapters and compiler with deterministic world events. */
public final class FactoryLearningControllerRegression {
    private static final BlockPos TREE = new BlockPos(1, 1, 1);
    private static final BlockPos OTHER = new BlockPos(2, 1, 1);
    private static RegenerativeSourceAdapter.Evidence thirdPartyEvidence;

    private FactoryLearningControllerRegression() { }

    public static void main(String[] args) throws Exception {
        protocolState();
        SharedConstants.tryDetectVersion();
        try (var flags = mockStatic(net.neoforged.neoforge.common.util.flag.FeatureFlagLoader.class)) {
            Bootstrap.bootStrap();
        }
        bindTags(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.OAK_LOG), List.of(BlockTags.LOGS));
        bindTags(BuiltInRegistries.ITEM.wrapAsHolder(Items.OAK_LOG), List.of(ItemTags.LOGS));
        bindTags(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.WHEAT), List.of(BlockTags.CROPS));
        bindTags(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.CARROTS), List.of(BlockTags.CROPS));
        RegenerativeSourceAdapters.register(new RegenerativeSourceAdapter() {
            @Override public String id() { return "regression:declared_source"; }
            @Override public Evidence begin(ServerLevel level, PocketBounds bounds, BlockPos origin) {
                return thirdPartyEvidence;
            }
        });
        int oldDeadline = Config.blackboxMaxLearningTicks;
        int oldTrees = Config.treeSourceVerificationCycles;
        int oldCrops = Config.cropSourceVerificationCycles;
        int oldCobble = Config.cobblestoneSourceVerificationCycles;
        int oldBasalt = Config.basaltSourceVerificationCycles;
        Config.blackboxMaxLearningTicks = 3600;
        Config.treeSourceVerificationCycles = 3;
        Config.cropSourceVerificationCycles = 3;
        Config.cobblestoneSourceVerificationCycles = 3;
        Config.basaltSourceVerificationCycles = 3;
        List<String> failures = new ArrayList<>();
        try {
            check(failures, "idle tree", () -> idlePlant(Blocks.OAK_SAPLING.defaultBlockState()));
            check(failures, "idle crop", () -> idlePlant(Blocks.WHEAT.defaultBlockState()));
            check(failures, "growth alone is not harvesting", FactoryLearningControllerRegression::growthWithoutHarvest);
            check(failures, "one fluid generation is not machine activity", FactoryLearningControllerRegression::generationWithoutHarvest);
            check(failures, "ordinary wood products", FactoryLearningControllerRegression::ordinaryWood);
            check(failures, "backlog is not production", FactoryLearningControllerRegression::backlog);
            check(failures, "idle species and continuous output", FactoryLearningControllerRegression::activeTree);
            check(failures, "independent crop groups", FactoryLearningControllerRegression::activeCrop);
            check(failures, "slow natural growth", FactoryLearningControllerRegression::slowTree);
            check(failures, "slow source inside unified deadline", FactoryLearningControllerRegression::adaptiveWait);
            check(failures, "transport delay preserves source intervals", FactoryLearningControllerRegression::delayedOutput);
            check(failures, "tree starts before publication", FactoryLearningControllerRegression::lateTree);
            check(failures, "internal tree processing remains protected", FactoryLearningControllerRegression::harvestWithoutRawOutput);
            check(failures, "stalled tree is not an ordinary recipe", FactoryLearningControllerRegression::stalledTree);
            check(failures, "no source events is not a free recipe", FactoryLearningControllerRegression::noFreeRecipe);
            check(failures, "idle generator", FactoryLearningControllerRegression::idleGenerator);
            check(failures, "stalled generator", FactoryLearningControllerRegression::stalledGenerator);
            check(failures, "cobblestone optimized path", () -> generator(Blocks.COBBLESTONE.defaultBlockState(), Items.COBBLESTONE));
            check(failures, "basalt optimized path", () -> generator(Blocks.BASALT.defaultBlockState(), Items.BASALT));
            check(failures, "ordinary drill does not prove regeneration", FactoryLearningControllerRegression::ordinaryDrill);
            check(failures, "raw cobblestone regeneration", () -> rawGenerator(false));
            check(failures, "raw basalt regeneration", () -> rawGenerator(true));
            check(failures, "learning deadline configuration snapshot", FactoryLearningControllerRegression::learningConfigSnapshot);
            check(failures, "declared third-party source", FactoryLearningControllerRegression::declaredSource);
            check(failures, "mixed source capital", FactoryLearningControllerRegression::mixedSourceCapital);
            check(failures, "final room ledger", FactoryLearningControllerRegression::finalLedger);
        } finally {
            Config.blackboxMaxLearningTicks = oldDeadline;
            Config.treeSourceVerificationCycles = oldTrees;
            Config.cropSourceVerificationCycles = oldCrops;
            Config.cobblestoneSourceVerificationCycles = oldCobble;
            Config.basaltSourceVerificationCycles = oldBasalt;
            thirdPartyEvidence = null;
        }
        require(failures.isEmpty(), String.join("\n", failures));
        System.out.println("Factory learning-controller regressions passed (26 production scenarios plus protocol state).");
    }

    private static void protocolState() {
        FactoryLearningController learning = new FactoryLearningController();
        require(learning.stage() == FactoryLearningController.Stage.WARMUP, "wrong initial stage");
        learning.prepare(1000);
        require(learning.preparingTicks() == 1 && learning.tickPreparing(), "preparing duration changed");
        learning.readClientStage("OBSERVING");
        require(learning.stage() == FactoryLearningController.Stage.OBSERVING, "client stage not restored");
        learning.readClientStage("unknown-future-stage");
        require(learning.stage() == FactoryLearningController.Stage.WARMUP, "unknown stage did not fail closed");
        learning.stop();
        require(learning.stage() == FactoryLearningController.Stage.WARMUP, "stop did not reset stage");
    }

    private static void idlePlant(BlockState plant) {
        try (Fixture f = new Fixture(Map.of(TREE, plant))) {
            f.run(400, tick -> { if (tick % 20 == 0) f.iron(); });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.outcome.plan().hasProcessingRoute() && !f.outcome.plan().hasRegenerativeRoute(),
                    "idle plant acquired production rights");
        }
    }

    private static void growthWithoutHarvest() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(400, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 150) f.growTree();
            });
            f.expect(FactoryLearningController.OutcomeKind.NONE);
            require(f.outcome.plan() == null, "growth must wait without certifying tree production");
        }
    }

    private static void generationWithoutHarvest() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.LAVA.defaultBlockState(),
                OTHER, Blocks.WATER.defaultBlockState(), TREE.west(), Blocks.LAVA.defaultBlockState()))) {
            f.run(400, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 150) f.change(TREE, Blocks.COBBLESTONE.defaultBlockState());
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(!f.outcome.plan().hasRegenerativeRoute(), "one fluid update proved a working generator");
            require(f.controller.abortDebugSummary().contains("generatedCredits=1"),
                    "the fixture did not generate a real source event");
        }
    }

    private static void ordinaryWood() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(400, tick -> {
                if (tick % 20 == 0) { f.input(Items.OAK_LOG, 1); f.output(Items.STICK, 4); }
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.outcome.plan().getRecipeOutputs().containsKey(item(Items.STICK)),
                    "idle sapling hid legitimate wood processing");
        }
    }

    private static void backlog() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.stock.put(item(Items.OAK_LOG), 6L);
            f.run(400, tick -> {
                if (tick == 150) { f.stock.clear(); f.input(Items.IRON_ORE, 1); f.output(Items.OAK_LOG, 6); }
            });
            f.expect(FactoryLearningController.OutcomeKind.ABORT);
        }
    }

    private static void activeTree() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState(),
                OTHER, Blocks.BIRCH_SAPLING.defaultBlockState()))) {
            f.run(450, tick -> {
                if (tick == 150 || tick == 250 || tick == 350) f.growTree();
                if (tick >= 150 && tick % 20 == 0) f.output(Items.OAK_LOG, 1);
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.outcome.plan().hasRegenerativeRoute(), "active tree was lost with idle birch group");
        }
    }

    private static void activeCrop() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.WHEAT.defaultBlockState(),
                OTHER, Blocks.CARROTS.defaultBlockState()))) {
            f.run(450, tick -> {
                if (tick == 150 || tick == 250 || tick == 350) {
                    f.change(TREE, Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));
                    f.change(TREE, Blocks.WHEAT.defaultBlockState());
                    f.output(Items.WHEAT, 1);
                }
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.outcome.plan().hasRegenerativeRoute(), "idle carrots blocked wheat");
        }
    }

    private static void slowTree() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(2400, tick -> {
                if (tick == 1000 || tick == 1500 || tick == 2000) { f.growTree(); f.output(Items.OAK_LOG, 1); }
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.now >= 2000, "natural source published before proof");
        }
    }

    private static void adaptiveWait() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(1300, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 150 || tick == 450 || tick == 1000) { f.growTree(); f.output(Items.OAK_LOG, 1); }
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.now >= 1000, "slow observed source was discarded");
            require(f.outcome.plan().processingRoute().getRecipeCycleTicks() == 20,
                    "source verification stretched the ordinary route's production batches");
        }
    }

    private static void lateTree() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(450, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 350) { f.growTree(); f.output(Items.OAK_LOG, 1); }
            });
            f.expect(FactoryLearningController.OutcomeKind.NONE);
            require(f.now == 450, "late source was silently omitted");
        }
    }

    private static void delayedOutput() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(1500, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 150 || tick == 550 || tick == 1300) f.growTree();
                if (tick == 350 || tick == 550 || tick == 1300) f.output(Items.OAK_LOG, 1);
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.now >= 1300, "transport delay shortened the observed source period");
        }
    }

    private static void harvestWithoutRawOutput() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(3600, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 150) f.growTree();
                if (tick == 160) { f.change(TREE, Blocks.AIR.defaultBlockState()); f.output(Items.OAK_PLANKS, 4); }
            });
            f.expect(FactoryLearningController.OutcomeKind.ABORT);
            require(f.outcome.reason().startsWith("source_event_timeout:"),
                    "internally processed tree output escaped source verification");
        }
    }

    private static void stalledTree() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(3600, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 150) { f.growTree(); f.output(Items.OAK_LOG, 1); }
            });
            f.expect(FactoryLearningController.OutcomeKind.ABORT);
            require(f.outcome.reason().startsWith("source_event_timeout:"), "wrong stalled-source diagnostic");
            require(f.now == 3600, "stalled source did not use the shared absolute deadline");
        }
    }

    private static void noFreeRecipe() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(3600, tick -> { if (tick == 150) f.output(Items.OAK_LOG, 1); });
            f.expect(FactoryLearningController.OutcomeKind.ABORT);
        }
    }

    private static void idleGenerator() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.COBBLESTONE.defaultBlockState()))) {
            f.run(400, tick -> { if (tick % 20 == 0) f.iron(); });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(!f.outcome.plan().hasRegenerativeRoute(), "stationary cobblestone became a source");
        }
    }

    private static void stalledGenerator() {
        try (Fixture f = new Fixture(Map.of())) {
            f.run(3600, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 150) { f.drill(Blocks.COBBLESTONE.defaultBlockState(), true); f.output(Items.COBBLESTONE, 1); }
            });
            f.expect(FactoryLearningController.OutcomeKind.ABORT);
            require(f.outcome.reason().startsWith("source_event_timeout:"), "generator still hangs");
        }
    }

    private static void generator(BlockState block, Item product) {
        try (Fixture f = new Fixture(Map.of())) {
            f.run(450, tick -> {
                if (tick == 150 || tick == 250 || tick == 350) { f.drill(block, true); f.output(product, 1); }
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.outcome.plan().hasRegenerativeRoute(), "optimized generation did not certify");
        }
    }

    private static void rawGenerator(boolean basalt) {
        Map<BlockPos, BlockState> initial = basalt
                ? Map.of(OTHER, Blocks.BLUE_ICE.defaultBlockState(), TREE.below(), Blocks.SOUL_SOIL.defaultBlockState())
                : Map.of(OTHER, Blocks.WATER.defaultBlockState());
        try (Fixture f = new Fixture(initial)) {
            f.blocks.put(TREE.west(), Blocks.LAVA.defaultBlockState());
            f.run(450, tick -> {
                if (tick == 150 || tick == 250 || tick == 350) {
                    f.change(TREE, Blocks.LAVA.defaultBlockState());
                    f.change(TREE, (basalt ? Blocks.BASALT : Blocks.COBBLESTONE).defaultBlockState());
                    f.output(basalt ? Items.BASALT : Items.COBBLESTONE, 1);
                }
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.outcome.plan().hasRegenerativeRoute(), "real fluid generation was not certified");
        }
    }

    private static void learningConfigSnapshot() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(3600, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 150) { f.growTree(); f.output(Items.OAK_LOG, 1); }
                if (tick == 450) Config.blackboxMaxLearningTicks = 20;
            });
            f.expect(FactoryLearningController.OutcomeKind.ABORT);
            require(f.now == 3600, "runtime configuration changed the active learning budget");
        } finally {
            Config.blackboxMaxLearningTicks = 3600;
        }
    }

    private static void ordinaryDrill() {
        try (Fixture f = new Fixture(Map.of())) {
            f.run(400, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 150) f.drill(Blocks.COBBLESTONE.defaultBlockState(), false);
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(!f.outcome.plan().hasRegenerativeRoute(), "ordinary mining granted source rights");
        }
    }

    private static void declaredSource() {
        thirdPartyEvidence = mock(RegenerativeSourceAdapter.Evidence.class, CALLS_REAL_METHODS);
        when(thirdPartyEvidence.isSupported()).thenReturn(true);
        when(thirdPartyEvidence.isReady()).thenReturn(true);
        when(thirdPartyEvidence.sourceKind()).thenReturn(FactoryPlan.SourceKind.COBBLESTONE);
        when(thirdPartyEvidence.allowsOutput(any(ItemStack.class))).thenAnswer(call ->
                ((ItemStack) call.getArgument(0)).is(Items.DIAMOND));
        try (Fixture f = new Fixture(Map.of())) {
            f.run(400, tick -> { if (tick % 20 == 0) f.output(Items.DIAMOND, 1); });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.outcome.plan().hasRegenerativeRoute(), "authorized source required artificial machine events");
        } finally {
            thirdPartyEvidence = null;
        }
    }

    private static void finalLedger() {
        try (Fixture f = new Fixture(Map.of(TREE, Blocks.OAK_SAPLING.defaultBlockState()))) {
            f.run(1000, tick -> {
                if (tick % 20 == 0) f.iron();
                if (tick == 150 || tick == 450 || tick == 650) { f.growTree(); f.output(Items.OAK_LOG, 1); }
                if (tick == 550) f.stock.put(item(Items.IRON_INGOT), 100L);
                if (tick == 600) f.output(Items.IRON_INGOT, 100);
                if (tick == 650) f.stock.clear();
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.outcome.plan().hasProcessingRoute(), "mixed processing route was lost");
            require(f.outcome.plan().routes().stream().noneMatch(route -> route.type() == FactoryRoute.Type.COUPLED),
                    "independent processing acquired source-coupled runtime semantics");
            // The whole sample contains the extra output; publishing an earlier locked route loses it.
            require(f.outcome.plan().getRecipeOutputs().getOrDefault(item(Items.IRON_INGOT), 0L) >
                    f.outcome.plan().getRecipeInputs().getOrDefault(item(Items.IRON_ORE), 0L),
                    "publication reused a stale processing candidate");
        }
    }

    private static void mixedSourceCapital() {
        thirdPartyEvidence = mock(RegenerativeSourceAdapter.Evidence.class, CALLS_REAL_METHODS);
        when(thirdPartyEvidence.isSupported()).thenReturn(true);
        when(thirdPartyEvidence.isReady()).thenReturn(true);
        when(thirdPartyEvidence.sourceKind()).thenReturn(FactoryPlan.SourceKind.COBBLESTONE);
        when(thirdPartyEvidence.allowsOutput(any(ItemStack.class))).thenAnswer(call ->
                ((ItemStack) call.getArgument(0)).is(Items.DIAMOND));
        doAnswer(call -> ((ItemStack) call.getArgument(0)).is(Items.DIAMOND) ? call.getArgument(1) : 0L)
                .when(thirdPartyEvidence).claimedOutputAmount(any(ItemStack.class), anyLong(), anyBoolean());
        when(thirdPartyEvidence.startupCapitalItems()).thenReturn(Map.of(item(Items.BLAZE_POWDER), 2L));
        try (Fixture f = new Fixture(Map.of())) {
            f.run(400, tick -> {
                if (tick % 20 == 0) { f.iron(); f.output(Items.DIAMOND, 1); }
            });
            f.expect(FactoryLearningController.OutcomeKind.ACTIVATE);
            require(f.outcome.plan().hasProcessingRoute() && f.outcome.plan().hasRegenerativeRoute(),
                    "mixed source did not preserve both independent routes");
            require(f.outcome.plan().getStartupCapitalItems().equals(Map.of(item(Items.BLAZE_POWDER), 2L)),
                    "splitting the final routes lost the authorized source capital contract");
            require(f.outcome.plan().copy().getStartupCapitalItems().equals(f.outcome.plan().getStartupCapitalItems()),
                    "copying the combined plan lost its source capital contract");
        } finally {
            thirdPartyEvidence = null;
        }
    }

    private static ItemVariant item(Item item) { return ItemVariant.of(new ItemStack(item)); }

    private static void bindTags(Object holder, Collection<?> tags) throws Exception {
        var method = holder.getClass().getDeclaredMethod("bindTags", Collection.class);
        method.setAccessible(true);
        method.invoke(holder, tags);
    }

    private static void check(List<String> failures, String name, Runnable check) {
        try { check.run(); }
        catch (Throwable failure) { failures.add(name + ": " + failure); }
    }

    private static final class Fixture implements AutoCloseable {
        final FactoryLearningController controller = new FactoryLearningController();
        final NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        final ServerLevel pocket = mock(ServerLevel.class);
        final FactoryTransit transit = new FactoryTransit();
        final FactoryPowerProfile power = new FactoryPowerProfile(0, 16);
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Map<ItemVariant, Long> stock = new HashMap<>();
        FactoryLearningController.TickOutcome outcome;
        int now;

        Fixture(Map<BlockPos, BlockState> initial) {
            blocks.putAll(initial);
            PocketBounds bounds = mock(PocketBounds.class);
            when(bounds.maxX(BlockPos.ZERO)).thenReturn(4);
            when(bounds.maxY(BlockPos.ZERO)).thenReturn(2);
            when(bounds.maxZ(BlockPos.ZERO)).thenReturn(2);
            when(bounds.isBuildableAt(eq(BlockPos.ZERO), any(BlockPos.class))).thenReturn(true);
            when(pocket.getBlockState(any(BlockPos.class))).thenAnswer(call ->
                    blocks.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState()));
            FluidState water = mock(FluidState.class);
            FluidState lava = mock(FluidState.class);
            when(water.is(FluidTags.WATER)).thenReturn(true);
            when(lava.is(FluidTags.LAVA)).thenReturn(true);
            when(pocket.getFluidState(any(BlockPos.class))).thenAnswer(call -> {
                BlockState state = blocks.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState());
                return state.is(Blocks.WATER) ? water : state.is(Blocks.LAVA) ? lava : Fluids.EMPTY.defaultFluidState();
            });
            when(pocket.getGameTime()).thenAnswer(call -> (long) now);
            when(factory.getPocketLevel()).thenReturn(pocket);
            when(factory.getBounds()).thenReturn(bounds);
            when(factory.roomOrigin()).thenReturn(BlockPos.ZERO);
            when(factory.getOperationMode()).thenReturn(OperationMode.BLACKBOX_LEARNING);
            when(factory.countItemsInFactorySpace()).thenAnswer(call -> Map.copyOf(stock));
            when(factory.countFluidsInFactorySpace()).thenReturn(Map.of());
            when(factory.countToolDurabilityInFactorySpace()).thenReturn(Map.of());
            controller.prepare(0);
            controller.enterWarmup(factory);
        }

        void run(int until, IntConsumer production) {
            for (now = 1; now <= until; now++) {
                if (now > 100) production.accept(now);
                outcome = controller.tick(factory, transit, power, 16, now);
                if (outcome.kind() != FactoryLearningController.OutcomeKind.NONE) return;
            }
            now = until;
        }
        void input(Item item, int count) { controller.recordItemBoundary(true, new ItemStack(item), count, now); }
        void output(Item item, int count) { controller.recordItemBoundary(false, new ItemStack(item), count, now); }
        void iron() { input(Items.IRON_ORE, 1); output(Items.IRON_INGOT, 1); }
        void change(BlockPos pos, BlockState state) {
            BlockState old = blocks.put(pos, state);
            controller.onBlockChanged(factory, pocket, pos, old == null ? Blocks.AIR.defaultBlockState() : old, state);
        }
        void growTree() {
            change(TREE, Blocks.OAK_SAPLING.defaultBlockState());
            change(TREE, Blocks.OAK_LOG.defaultBlockState());
        }
        void drill(BlockState state, boolean optimized) {
            controller.onDrillProduction(factory, pocket, new DrillProductionEvent(TREE, state, optimized
                    ? DrillProductionEvent.Path.CREATE_OPTIMIZED_GENERATOR : DrillProductionEvent.Path.STATIONARY));
        }
        void expect(FactoryLearningController.OutcomeKind kind) {
            require(outcome != null && outcome.kind() == kind, "expected " + kind + " at " + now + ", got " + outcome
                    + "; " + controller.abortDebugSummary());
        }
        @Override public void close() { PocketLearningObserver.unregister(factory); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

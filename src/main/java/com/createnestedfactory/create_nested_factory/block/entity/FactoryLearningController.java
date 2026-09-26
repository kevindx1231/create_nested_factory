package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.Config;
import com.createnestedfactory.create_nested_factory.block.FactoryPowerProfile;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owns the complete in-memory learning protocol for one factory.
 *
 * <p>World lifecycle effects (mode changes, freezing and resource destruction) remain in the
 * block entity adapter. This module owns learning time, windows, observations, source evidence
 * and candidate compilation, and reports only terminal activate/abort outcomes.</p>
 */
final class FactoryLearningController {
    private static final int WARMUP_TICKS = 100;
    private static final int[] WINDOW_TICKS = {100, 200, 400, 800, 1600};
    private static final int PREPARING_TICKS = 1;

    enum Stage {
        WARMUP,
        OBSERVING
    }

    enum OutcomeKind {
        NONE,
        ACTIVATE,
        ABORT
    }

    record TickOutcome(OutcomeKind kind, FactoryPlan plan, String reason) {
        private static final TickOutcome NONE = new TickOutcome(OutcomeKind.NONE, null, "");

        static TickOutcome activate(FactoryPlan plan) {
            return new TickOutcome(OutcomeKind.ACTIVATE, plan, "");
        }

        static TickOutcome abort(String reason) {
            return new TickOutcome(OutcomeKind.ABORT, null, reason);
        }
    }

    private record RoomCosts(Map<ItemVariant, Long> itemAdjustments,
                             Map<FluidVariant, Long> fluidAdjustments,
                             Map<ItemVariant, Long> toolDamage) {
    }

    private final FactoryPlanCompiler compiler = new FactoryPlanCompiler();
    private final RoomStateLedger roomLedger = new RoomStateLedger();
    private final List<FactoryPlanCompiler.Observation> observations = new ArrayList<>();
    private FactoryPlan lockedProcessingPlan = new FactoryPlan();
    private FactoryLearningSession session;
    private int preparingTicksRemaining;
    private Stage stage = Stage.WARMUP;
    private int stageTicks;
    private long deadlineTick;
    private int windowIndex;
    private int windowTargetTicks = WINDOW_TICKS[0];
    private int windowScaleIndex;

    FactoryPlan preview() {
        return compiler.preview();
    }

    Stage stage() {
        return stage;
    }

    String stageName() {
        return stage.name();
    }

    void readClientStage(String name) {
        try {
            stage = Stage.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            stage = Stage.WARMUP;
        }
        stageTicks = 0;
    }

    void useServerStage() {
        stage = Stage.WARMUP;
        stageTicks = 0;
    }

    void prepare(long now) {
        compiler.stopLearning();
        roomLedger.clear();
        session = null;
        observations.clear();
        lockedProcessingPlan = new FactoryPlan();
        windowIndex = 0;
        windowScaleIndex = 0;
        windowTargetTicks = WINDOW_TICKS[0];
        int configuredDeadline = Config.blackboxMaxLearningTicks > 1
                ? Config.blackboxMaxLearningTicks : 6000;
        deadlineTick = now + configuredDeadline;
        preparingTicksRemaining = PREPARING_TICKS;
    }

    int preparingTicks() {
        return PREPARING_TICKS;
    }

    boolean tickPreparing() {
        return --preparingTicksRemaining <= 0;
    }

    void enterWarmup(NestedFactoryBlockEntity factory) {
        stage = Stage.WARMUP;
        stageTicks = 0;
        session = null;
        observations.clear();
        windowIndex = 0;
        windowScaleIndex = 0;
        windowTargetTicks = WINDOW_TICKS[0];
        lockedProcessingPlan = new FactoryPlan();
        factory.blackboxDebug("learning_warmup_started", () -> "warmupTicks=" + WARMUP_TICKS
                + ", deadlineTick=" + deadlineTick + ", transit=" + factory.learningTransitSummary());
    }

    void tickIdleCompiler() {
        compiler.tick();
    }

    TickOutcome tick(NestedFactoryBlockEntity factory, FactoryTransit transit,
                     FactoryPowerProfile powerProfile, float liveExternalStressDemandSU, long now) {
        boolean deadlineReached = now >= deadlineTick;
        boolean canFinalizeAtDeadline = stage == Stage.OBSERVING
                && observations.size() >= 3 && compiler.currentLearningTicks() > 0
                && session != null && session.isSupported() && session.isReady();
        if (deadlineReached && !canFinalizeAtDeadline) {
            String reason = session != null && session.isSupported() && !session.isReady()
                    ? "source_event_timeout:" + session.debugSummary()
                    : "absolute_timeout";
            return TickOutcome.abort(reason);
        }
        if (transit.hasUnsupportedExtensionState()) {
            return TickOutcome.abort("unsupported_transit_participant");
        }
        compiler.tick();
        if (stage == Stage.WARMUP) {
            if (++stageTicks >= WARMUP_TICKS) {
                String failure = beginObservation(factory);
                if (failure != null) return TickOutcome.abort(failure);
            }
            return TickOutcome.NONE;
        }

        compiler.recordStressSupply(liveExternalStressDemandSU,
                powerProfile.internalGeneratedSU(), powerProfile.consumedSU());
        int previousCycles = session == null ? 0 : session.verifiedCycles();
        if (session != null) session.tick(factory.getPocketLevel());
        if (session != null && session.verifiedCycles() != previousCycles) {
            factory.blackboxDebug("source_evidence_changed", () -> "before=" + previousCycles + ", source={"
                    + session.debugSummary() + "}");
        }

        int elapsed = compiler.currentLearningTicks();
        boolean sourceCompletedEarly = observations.size() >= 3 && elapsed > 0
                && (elapsed % 20 == 0 || deadlineReached)
                && session != null && session.isSupported()
                && (session.isReadyToFinalize(now) || deadlineReached && session.isReady());
        if (elapsed < windowTargetTicks && !sourceCompletedEarly) return TickOutcome.NONE;

        boolean sourceSupported = session != null && session.isSupported();
        boolean firstWindowIncomplete = windowIndex == 0
                && !sourceSupported && !compiler.hasCurrentOutput();
        if (firstWindowIncomplete && windowScaleIndex + 1 < WINDOW_TICKS.length) {
            windowScaleIndex++;
            windowTargetTicks = WINDOW_TICKS[windowScaleIndex];
            factory.blackboxDebug("learning_window_extended", () -> "window=1, targetTicks="
                    + windowTargetTicks + ", observation={" + compiler.debugSummary()
                    + "}, source={" + (session == null ? "none" : session.debugSummary()) + "}");
            return TickOutcome.NONE;
        }
        if (firstWindowIncomplete) {
            return TickOutcome.abort("first_observation_never_became_valid");
        }

        FactoryPlanCompiler.Observation observation = compiler.finishObservation();
        observations.add(observation);
        int completedWindow = windowIndex + 1;
        factory.blackboxDebug("learning_window_completed", () -> "window=" + completedWindow
                + ", targetTicks=" + windowTargetTicks + ", observation=" + observation
                + ", source={" + (session == null ? "none" : session.debugSummary()) + "}");

        if (observations.size() >= 3 && !lockedProcessingPlan.hasProcessingRoute()) {
            RoomCosts costs = currentRoomCosts(factory);
            FactoryPlan processing = compiler.compileProcessingCandidate(observations, session,
                    costs.itemAdjustments(), costs.fluidAdjustments(), costs.toolDamage());
            if (processing.hasProcessingRoute()) {
                lockedProcessingPlan = processing.processingRoute();
                factory.blackboxDebug("processing_candidate_locked", () -> "plan={"
                        + lockedProcessingPlan.debugSummary() + "}, roomCosts=" + costs);
            }
        }

        boolean minimumSampleComplete = observations.size() >= 3;
        boolean sourceReady = sourceSupported && (session.isReadyToFinalize(now)
                || deadlineReached && session.isReady());
        boolean sourceStillLearning = sourceSupported && !sourceReady;
        boolean hasCandidate = lockedProcessingPlan.hasProcessingRoute() || canBuildCandidate();
        if (minimumSampleComplete && hasCandidate && !sourceStillLearning) {
            RoomCosts costs = currentRoomCosts(factory);
            FactoryPlan compiled = compiler.compile(observations, sourceSupported ? session : null,
                    costs.itemAdjustments(), costs.fluidAdjustments(), costs.toolDamage());
            if (lockedProcessingPlan.hasProcessingRoute() && compiled.hasRegenerativeRoute()) {
                compiled = FactoryPlan.combineIndependent(lockedProcessingPlan, compiled.regenerativeRoute());
            } else if (lockedProcessingPlan.hasProcessingRoute() && !compiled.hasRegenerativeRoute()) {
                compiled = lockedProcessingPlan;
            }
            FactoryPlan completedPlan = compiled;
            factory.blackboxDebug("learning_plan_compiled", () -> "complete=" + completedPlan.hasCompleteRecipe()
                    + ", observations={" + compiler.candidateSummary(observations, session)
                    + "}, roomCosts=" + costs + ", plan={" + completedPlan.debugSummary() + "}");
            if (!compiled.hasCompleteRecipe()) {
                return TickOutcome.abort("plan_incomplete_or_ambiguous");
            }
            return TickOutcome.activate(compiled);
        }

        windowIndex++;
        stageTicks = 0;
        if (session != null) session.beginWindow(windowIndex);
        compiler.beginLearning();
        factory.blackboxDebug("learning_window_started", () -> "window=" + (windowIndex + 1)
                + ", targetTicks=" + windowTargetTicks);
        return TickOutcome.NONE;
    }

    void complete() {
        compiler.stopLearning();
        session = null;
        observations.clear();
        lockedProcessingPlan = new FactoryPlan();
        stage = Stage.WARMUP;
        stageTicks = 0;
        deadlineTick = 0L;
        windowIndex = 0;
        roomLedger.clear();
    }

    void stop() {
        compiler.stopLearning();
        roomLedger.clear();
        preparingTicksRemaining = 0;
        stage = Stage.WARMUP;
        stageTicks = 0;
        deadlineTick = 0L;
        windowIndex = 0;
        windowScaleIndex = 0;
        windowTargetTicks = WINDOW_TICKS[0];
        observations.clear();
        lockedProcessingPlan = new FactoryPlan();
        session = null;
    }

    void stopCompiler() {
        compiler.stopLearning();
    }

    void repairInterruptedLoad() {
        session = null;
        lockedProcessingPlan = new FactoryPlan();
        observations.clear();
        preparingTicksRemaining = 0;
    }

    String abortDebugSummary() {
        return "completedWindows=" + observations.size()
                + ", observation={" + compiler.debugSummary() + "}, source={"
                + (session == null ? "none" : session.debugSummary()) + "}";
    }

    void recordItemBoundary(boolean inputFlow, ItemStack stack, int moved, long tick) {
        if (inputFlow) {
            compiler.recordItemInput(stack, moved);
        } else {
            compiler.recordItemOutput(stack, moved);
            if (session != null && tick >= 0L) session.recordBoundaryItemOutput(stack, moved, tick);
        }
    }

    void recordFluidBoundary(boolean inputFlow, FluidStack stack, int moved, long tick) {
        if (inputFlow) {
            compiler.recordFluidInput(stack, moved);
        } else {
            compiler.recordFluidOutput(stack, moved);
            if (session != null && tick >= 0L) session.recordBoundaryFluidOutput(stack, moved, tick);
        }
    }

    Boolean onBlockChanged(NestedFactoryBlockEntity factory, ServerLevel pocket, BlockPos pos,
                           BlockState oldState, BlockState newState) {
        if (factory.getOperationMode() != OperationMode.BLACKBOX_LEARNING || stage != Stage.OBSERVING
                || session == null || pocket == null
                || !factory.getBounds().isBuildableAt(factory.roomOrigin(), pos)) {
            return null;
        }
        return session.onBlockChanged(pocket, pos, oldState, newState);
    }

    Boolean onDrillProduction(NestedFactoryBlockEntity factory, ServerLevel pocket, DrillProductionEvent event) {
        if (factory.getOperationMode() != OperationMode.BLACKBOX_LEARNING || stage != Stage.OBSERVING
                || session == null || pocket == null || event == null
                || !factory.getBounds().isBuildableAt(factory.roomOrigin(), event.targetPos())) {
            return null;
        }
        return session.onDrillProduction(pocket, event);
    }

    private String beginObservation(NestedFactoryBlockEntity factory) {
        ServerLevel pocket = factory.getPocketLevel();
        if (pocket == null) return "pocket_level_missing";
        session = FactoryLearningSession.begin(pocket, factory.getBounds(), factory.roomOrigin());
        windowIndex = 0;
        windowScaleIndex = 0;
        windowTargetTicks = WINDOW_TICKS[0];
        observations.clear();
        lockedProcessingPlan = new FactoryPlan();
        roomLedger.capture(factory.countItemsInFactorySpace(), factory.countFluidsInFactorySpace(),
                factory.countToolDurabilityInFactorySpace());
        stage = Stage.OBSERVING;
        stageTicks = 0;
        session.beginWindow(0);
        compiler.beginLearning();
        PocketLearningObserver.register(pocket, factory, factory.getBounds(), factory.roomOrigin());
        factory.blackboxDebug("unified_learning_started", () -> "targetTicks=" + windowTargetTicks
                + ", source={" + session.debugSummary() + "}, room={" + roomLedger.debugSummary() + "}");
        factory.learningStateChanged();
        return null;
    }

    private RoomCosts currentRoomCosts(NestedFactoryBlockEntity factory) {
        Map<ItemVariant, Long> currentItems = factory.countItemsInFactorySpace();
        Map<FluidVariant, Long> currentFluids = factory.countFluidsInFactorySpace();
        Map<ItemVariant, Long> currentTools = factory.countToolDurabilityInFactorySpace();
        return new RoomCosts(roomLedger.itemBalanceAdjustments(currentItems),
                roomLedger.fluidBalanceAdjustments(currentFluids),
                roomLedger.consumedToolDurability(currentTools));
    }

    private boolean canBuildCandidate() {
        boolean hasOutput = observations.stream().anyMatch(FactoryPlanCompiler.Observation::hasOutput);
        boolean hasExternalInput = observations.stream()
                .anyMatch(FactoryPlanCompiler.Observation::hasExternalInput);
        boolean hasSource = session != null && session.hasPreliminaryEvidence();
        return hasOutput && (hasExternalInput || hasSource);
    }
}

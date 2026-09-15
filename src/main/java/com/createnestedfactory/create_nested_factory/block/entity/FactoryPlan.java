package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.material.Fluid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Versioned production contract executed while the Pocket room is frozen.
 *
 * <p>The plan contains no sampling counters and no mutable batch state. Material-backed and
 * regenerative production use the same contract; an empty material vector is legal only when
 * the compiler attached a regenerative source proof and a measured internal or external stress
 * supply.</p>
 */
public final class FactoryPlan {
    public static final int FORMAT_VERSION = 9;

    public enum SourceKind {
        NONE,
        COBBLESTONE,
        BASALT,
        TREE,
        CROP,
        COMPOSITE;

        public boolean isRegenerative() {
            return this != NONE;
        }
    }

    private final Map<ItemVariant, Long> itemInputs = new HashMap<>();
    private final Map<ItemVariant, Long> processingItemOutputs = new HashMap<>();
    private final Map<ItemVariant, Long> regenerativeItemOutputs = new HashMap<>();
    private final Map<FluidVariant, Long> fluidInputs = new HashMap<>();
    private final Map<FluidVariant, Long> processingFluidOutputs = new HashMap<>();
    private final Map<FluidVariant, Long> regenerativeFluidOutputs = new HashMap<>();
    private final Map<ItemVariant, Long> processingToolDamageCosts = new HashMap<>();
    private final Map<ItemVariant, Long> regenerativeToolDamageCosts = new HashMap<>();
    private final Map<ItemVariant, Long> startupCapitalItems = new HashMap<>();
    private final List<String> sourceProofs = new ArrayList<>();

    private int cycleTicks;
    private int naturalWaitTicks;
    private SourceKind sourceKind = SourceKind.NONE;
    private int verifiedCycles;
    private boolean statisticalOutput;
    /** Authoritative stress energy for one route cycle; average SU is derived from this value. */
    private double externalStressSUTicks;
    private float peakExternalStressSU;
    /** Internal generation observed while this route ran; frozen rooms replay it virtually. */
    private double internalGenerationSUTicks;
    private float peakInternalGeneratedSU;
    private float peakConsumedSU;
    private transient FactoryPlan processingRouteCache;
    private transient FactoryPlan regenerativeRouteCache;
    /** Composite plans retain each independently learned route's own time and stress contract. */
    private FactoryPlan independentProcessingRoute;
    private FactoryPlan independentRegenerativeRoute;
    private transient String fingerprintCache;

    private boolean hasIndependentRoutes() {
        return independentProcessingRoute != null || independentRegenerativeRoute != null;
    }

    public Map<ItemVariant, Long> getRecipeInputs() {
        if (hasIndependentRoutes()) return Collections.unmodifiableMap(merged(
                independentProcessingRoute == null ? Map.of() : independentProcessingRoute.getRecipeInputs(),
                independentRegenerativeRoute == null ? Map.of() : independentRegenerativeRoute.getRecipeInputs()));
        return Collections.unmodifiableMap(itemInputs);
    }

    public Map<ItemVariant, Long> getRecipeOutputs() {
        if (hasIndependentRoutes()) return Collections.unmodifiableMap(merged(
                independentProcessingRoute == null ? Map.of() : independentProcessingRoute.getRecipeOutputs(),
                independentRegenerativeRoute == null ? Map.of() : independentRegenerativeRoute.getRecipeOutputs()));
        return Collections.unmodifiableMap(merged(processingItemOutputs, regenerativeItemOutputs));
    }

    public Map<ItemVariant, Long> getRegenerativeItemOutputs() {
        if (hasIndependentRoutes()) return independentRegenerativeRoute == null ? Map.of()
                : independentRegenerativeRoute.getRecipeOutputs();
        return Collections.unmodifiableMap(regenerativeItemOutputs);
    }

    public Map<FluidVariant, Long> getRecipeInputFluids() {
        if (hasIndependentRoutes()) return Collections.unmodifiableMap(merged(
                independentProcessingRoute == null ? Map.of() : independentProcessingRoute.getRecipeInputFluids(),
                independentRegenerativeRoute == null ? Map.of()
                        : independentRegenerativeRoute.getRecipeInputFluids()));
        return Collections.unmodifiableMap(fluidInputs);
    }

    public Map<FluidVariant, Long> getRecipeOutputFluids() {
        if (hasIndependentRoutes()) return Collections.unmodifiableMap(merged(
                independentProcessingRoute == null ? Map.of() : independentProcessingRoute.getRecipeOutputFluids(),
                independentRegenerativeRoute == null ? Map.of() : independentRegenerativeRoute.getRecipeOutputFluids()));
        return Collections.unmodifiableMap(merged(processingFluidOutputs, regenerativeFluidOutputs));
    }

    public Map<FluidVariant, Long> getRegenerativeFluidOutputs() {
        if (hasIndependentRoutes()) return independentRegenerativeRoute == null ? Map.of()
                : independentRegenerativeRoute.getRecipeOutputFluids();
        return Collections.unmodifiableMap(regenerativeFluidOutputs);
    }

    public Map<ItemVariant, Long> getRecipeToolDamageCosts() {
        if (hasIndependentRoutes()) return Collections.unmodifiableMap(merged(
                independentProcessingRoute == null ? Map.of() : independentProcessingRoute.getRecipeToolDamageCosts(),
                independentRegenerativeRoute == null ? Map.of() : independentRegenerativeRoute.getRecipeToolDamageCosts()));
        return Collections.unmodifiableMap(merged(processingToolDamageCosts, regenerativeToolDamageCosts));
    }

    public Map<ItemVariant, Long> getStartupCapitalItems() {
        if (hasIndependentRoutes()) return independentRegenerativeRoute == null ? Map.of()
                : independentRegenerativeRoute.getStartupCapitalItems();
        return Collections.unmodifiableMap(startupCapitalItems);
    }

    public int getRecipeCycleTicks() {
        if (hasIndependentRoutes()) return Math.max(
                independentProcessingRoute == null ? 0 : independentProcessingRoute.getRecipeCycleTicks(),
                independentRegenerativeRoute == null ? 0 : independentRegenerativeRoute.getRecipeCycleTicks());
        return cycleTicks;
    }

    public int getNaturalWaitTicks() {
        if (hasIndependentRoutes()) return independentRegenerativeRoute == null ? 0
                : independentRegenerativeRoute.getNaturalWaitTicks();
        return naturalWaitTicks;
    }

    public SourceKind getSourceKind() {
        if (hasIndependentRoutes()) return independentRegenerativeRoute == null ? SourceKind.NONE
                : independentRegenerativeRoute.getSourceKind();
        return sourceKind;
    }

    public int getVerifiedCycles() {
        if (hasIndependentRoutes()) return independentRegenerativeRoute == null ? 0
                : independentRegenerativeRoute.getVerifiedCycles();
        return verifiedCycles;
    }

    public boolean hasStatisticalOutput() {
        if (hasIndependentRoutes()) return independentRegenerativeRoute != null
                && independentRegenerativeRoute.hasStatisticalOutput();
        return statisticalOutput;
    }

    public float getLearnedAverageExternalStressSU() {
        if (hasIndependentRoutes()) return Math.max(
                independentProcessingRoute == null ? 0f : independentProcessingRoute.getLearnedAverageExternalStressSU(),
                independentRegenerativeRoute == null ? 0f : independentRegenerativeRoute.getLearnedAverageExternalStressSU());
        if (cycleTicks <= 0 || !Double.isFinite(externalStressSUTicks)) return 0f;
        double average = externalStressSUTicks / cycleTicks;
        return average >= Float.MAX_VALUE ? Float.MAX_VALUE : Math.max(0f, (float) average);
    }

    public double getLearnedExternalStressSUTicks() {
        if (hasIndependentRoutes()) return Math.max(
                independentProcessingRoute == null ? 0d
                        : independentProcessingRoute.getLearnedExternalStressSUTicks(),
                independentRegenerativeRoute == null ? 0d
                        : independentRegenerativeRoute.getLearnedExternalStressSUTicks());
        return externalStressSUTicks;
    }

    public float getLearnedPeakExternalStressSU() {
        if (hasIndependentRoutes()) return Math.max(
                independentProcessingRoute == null ? 0f : independentProcessingRoute.getLearnedPeakExternalStressSU(),
                independentRegenerativeRoute == null ? 0f : independentRegenerativeRoute.getLearnedPeakExternalStressSU());
        return peakExternalStressSU;
    }

    public float getLearnedAverageInternalGeneratedSU() {
        if (hasIndependentRoutes()) return Math.max(
                independentProcessingRoute == null ? 0f
                        : independentProcessingRoute.getLearnedAverageInternalGeneratedSU(),
                independentRegenerativeRoute == null ? 0f
                        : independentRegenerativeRoute.getLearnedAverageInternalGeneratedSU());
        if (cycleTicks <= 0 || !Double.isFinite(internalGenerationSUTicks)) return 0f;
        double average = internalGenerationSUTicks / cycleTicks;
        return average >= Float.MAX_VALUE ? Float.MAX_VALUE : Math.max(0f, (float) average);
    }

    public double getLearnedInternalGenerationSUTicks() {
        if (hasIndependentRoutes()) return Math.max(
                independentProcessingRoute == null ? 0d
                        : independentProcessingRoute.getLearnedInternalGenerationSUTicks(),
                independentRegenerativeRoute == null ? 0d
                        : independentRegenerativeRoute.getLearnedInternalGenerationSUTicks());
        return internalGenerationSUTicks;
    }

    public float getLearnedPeakInternalGeneratedSU() {
        if (hasIndependentRoutes()) return Math.max(
                independentProcessingRoute == null ? 0f
                        : independentProcessingRoute.getLearnedPeakInternalGeneratedSU(),
                independentRegenerativeRoute == null ? 0f
                        : independentRegenerativeRoute.getLearnedPeakInternalGeneratedSU());
        return peakInternalGeneratedSU;
    }

    public float getLearnedPeakConsumedSU() {
        if (hasIndependentRoutes()) return Math.max(
                independentProcessingRoute == null ? 0f
                        : independentProcessingRoute.getLearnedPeakConsumedSU(),
                independentRegenerativeRoute == null ? 0f
                        : independentRegenerativeRoute.getLearnedPeakConsumedSU());
        if (peakConsumedSU > 0f || peakInternalGeneratedSU <= 0f || !hasRecipe()) {
            return peakConsumedSU;
        }
        // Plans learned before network-member stress was sampled can contain a proven internal
        // source and production while reporting zero load. Treat that malformed combination
        // conservatively as consuming the available internal supply plus its external deficit.
        double inferred = peakInternalGeneratedSU + (double) peakExternalStressSU;
        return inferred >= Float.MAX_VALUE ? Float.MAX_VALUE : (float) inferred;
    }

    public List<String> getSourceProofs() {
        if (hasIndependentRoutes()) return independentRegenerativeRoute == null ? List.of()
                : independentRegenerativeRoute.getSourceProofs();
        return List.copyOf(sourceProofs);
    }

    public boolean isRegenerativeRecipe() {
        return getSourceKind().isRegenerative();
    }

    /**
     * Statistical plant samples cover several irregular growth cycles, so replaying the whole
     * sample as one output batch creates long, bursty deliveries. A pure statistical route may
     * expose the integer part of its cumulatively earned output every second.  Routes that own
     * material inputs or tool damage stay atomic so early delivery can never avoid their costs.
     */
    public boolean streamsStatisticalOutputsPerSecond() {
        return !hasIndependentRoutes()
                && statisticalOutput
                && hasRegenerativeRoute()
                && itemInputs.isEmpty()
                && fluidInputs.isEmpty()
                && processingToolDamageCosts.isEmpty()
                && regenerativeToolDamageCosts.isEmpty();
    }

    public boolean allowsMechanicalOverclock() {
        if (hasIndependentRoutes()) return (independentProcessingRoute != null
                && independentProcessingRoute.allowsMechanicalOverclock())
                || (independentRegenerativeRoute != null
                && independentRegenerativeRoute.allowsMechanicalOverclock());
        // Once a regenerative source has been verified and frozen into a black-box contract,
        // its learned cycle is the unit of virtual work.  There is no live random-tick phase left
        // to preserve, so the complete regenerative cycle can use the configured throughput tier.
        return cycleTicks > naturalWaitTicks || cycleTicks > 0 && isRegenerativeRecipe();
    }

    public boolean hasFluidRecipe() {
        if (hasIndependentRoutes()) return !getRecipeInputFluids().isEmpty();
        return !fluidInputs.isEmpty();
    }

    public boolean hasRecipe() {
        if (hasIndependentRoutes()) return hasCompleteRecipe();
        return !itemInputs.isEmpty() || !processingItemOutputs.isEmpty() || !regenerativeItemOutputs.isEmpty()
                || !fluidInputs.isEmpty() || !processingFluidOutputs.isEmpty() || !regenerativeFluidOutputs.isEmpty();
    }

    public boolean hasCompleteRecipe() {
        if (hasIndependentRoutes()) return hasProcessingRoute() || hasRegenerativeRoute();
        return hasProcessingRoute() || hasRegenerativeRoute();
    }

    public boolean hasProcessingRoute() {
        if (hasIndependentRoutes()) return independentProcessingRoute != null
                && independentProcessingRoute.hasProcessingRoute();
        return cycleTicks > 0 && (!itemInputs.isEmpty() || !fluidInputs.isEmpty())
                && (!processingItemOutputs().isEmpty() || !processingFluidOutputs().isEmpty());
    }

    public boolean hasRegenerativeRoute() {
        if (hasIndependentRoutes()) return independentRegenerativeRoute != null
                && independentRegenerativeRoute.hasRegenerativeRoute();
        return cycleTicks > 0 && (!regenerativeItemOutputs.isEmpty() || !regenerativeFluidOutputs.isEmpty())
                && sourceKind.isRegenerative() && !sourceProofs.isEmpty()
                && sourceProofs.stream().allMatch(RegenerativeSourceAdapters::isCurrentProof)
                && hasMeasuredStressSupply();
    }

    private boolean hasMeasuredStressSupply() {
        return Float.isFinite(peakExternalStressSU) && peakExternalStressSU > 0f
                || Float.isFinite(peakInternalGeneratedSU) && peakInternalGeneratedSU > 0f;
    }

    public List<FactoryRoute> routes() {
        if (hasIndependentRoutes()) {
            List<FactoryRoute> routes = new ArrayList<>();
            if (hasProcessingRoute()) routes.add(new FactoryRoute(FactoryRuntimeScheduler.PROCESSING_ROUTE_ID,
                    FactoryRoute.Type.PROCESSING, independentProcessingRoute));
            if (hasRegenerativeRoute()) routes.add(new FactoryRoute(FactoryRuntimeScheduler.REGENERATIVE_ROUTE_ID,
                    FactoryRoute.Type.REGENERATIVE, independentRegenerativeRoute));
            return List.copyOf(routes);
        }
        List<FactoryRoute> routes = new ArrayList<>();
        if (hasProcessingRoute()) {
            routes.add(new FactoryRoute(FactoryRuntimeScheduler.PROCESSING_ROUTE_ID,
                    isRegenerativeRecipe() ? FactoryRoute.Type.COUPLED : FactoryRoute.Type.PROCESSING,
                    processingRoute()));
        }
        if (hasRegenerativeRoute()) {
            routes.add(new FactoryRoute(FactoryRuntimeScheduler.REGENERATIVE_ROUTE_ID,
                    FactoryRoute.Type.REGENERATIVE, regenerativeRoute()));
        }
        return List.copyOf(routes);
    }

    public FactoryPlan processingRoute() {
        if (hasIndependentRoutes()) return independentProcessingRoute == null
                ? new FactoryPlan() : independentProcessingRoute;
        if (processingRouteCache != null) return processingRouteCache;
        if (!hasProcessingRoute()) return processingRouteCache = new FactoryPlan();
        return processingRouteCache = compiled(itemInputs, processingItemOutputs(), fluidInputs, processingFluidOutputs(),
                Map.of(), Map.of(), processingToolDamageCosts(), Map.of(),
                cycleTicks, 0, SourceKind.NONE, 0, false,
                externalStressSUTicks, peakExternalStressSU,
                internalGenerationSUTicks, peakInternalGeneratedSU, peakConsumedSU, List.of());
    }

    public FactoryPlan regenerativeRoute() {
        if (hasIndependentRoutes()) return independentRegenerativeRoute == null
                ? new FactoryPlan() : independentRegenerativeRoute;
        if (regenerativeRouteCache != null) return regenerativeRouteCache;
        if (!hasRegenerativeRoute()) return regenerativeRouteCache = new FactoryPlan();
        boolean ownsInputs = processingItemOutputs.isEmpty() && processingFluidOutputs.isEmpty();
        return regenerativeRouteCache = compiled(
                ownsInputs ? itemInputs : Map.of(), regenerativeItemOutputs,
                ownsInputs ? fluidInputs : Map.of(), regenerativeFluidOutputs,
                regenerativeItemOutputs, regenerativeFluidOutputs,
                regenerativeToolDamageCosts, regenerativeToolDamageCosts,
                cycleTicks, naturalWaitTicks, sourceKind, verifiedCycles, statisticalOutput,
                externalStressSUTicks, peakExternalStressSU,
                internalGenerationSUTicks, peakInternalGeneratedSU, peakConsumedSU, sourceProofs);
    }

    private Map<ItemVariant, Long> processingItemOutputs() {
        return new HashMap<>(processingItemOutputs);
    }

    private Map<FluidVariant, Long> processingFluidOutputs() {
        return new HashMap<>(processingFluidOutputs);
    }

    private Map<ItemVariant, Long> processingToolDamageCosts() {
        return new HashMap<>(processingToolDamageCosts);
    }

    public Map<ItemVariant, Float> getInputRates() {
        if (hasIndependentRoutes()) return mergedRates(
                independentProcessingRoute == null ? Map.of() : independentProcessingRoute.getInputRates(),
                independentRegenerativeRoute == null ? Map.of() : independentRegenerativeRoute.getInputRates());
        return itemRates(itemInputs);
    }

    public Map<ItemVariant, Float> getOutputRates() {
        if (hasIndependentRoutes()) return mergedRates(
                independentProcessingRoute == null ? Map.of() : independentProcessingRoute.getOutputRates(),
                independentRegenerativeRoute == null ? Map.of() : independentRegenerativeRoute.getOutputRates());
        return itemRates(merged(processingItemOutputs, regenerativeItemOutputs));
    }

    public Map<Fluid, Float> getInputFluidRates() {
        if (hasIndependentRoutes()) return mergedRates(
                independentProcessingRoute == null ? Map.of() : independentProcessingRoute.getInputFluidRates(),
                independentRegenerativeRoute == null ? Map.of()
                        : independentRegenerativeRoute.getInputFluidRates());
        return fluidRates(fluidInputs);
    }

    public Map<Fluid, Float> getOutputFluidRates() {
        if (hasIndependentRoutes()) return mergedRates(
                independentProcessingRoute == null ? Map.of() : independentProcessingRoute.getOutputFluidRates(),
                independentRegenerativeRoute == null ? Map.of() : independentRegenerativeRoute.getOutputFluidRates());
        return fluidRates(merged(processingFluidOutputs, regenerativeFluidOutputs));
    }

    /**
     * Applies one overclock profile. Live mechanical work keeps any fixed natural
     * wait, while a verified regenerative black-box route scales its complete virtual cycle.
     * Every resource vector keeps its exact integer ratio.
     */
    public FactoryPlan scaledRecipe(float multiplier) {
        if (hasIndependentRoutes()) {
            return combineIndependent(
                    independentProcessingRoute == null ? null : independentProcessingRoute.scaledRecipe(multiplier),
                    independentRegenerativeRoute == null ? null : independentRegenerativeRoute.scaledRecipe(multiplier));
        }
        FactoryPlan scaled = copy();
        if (!Float.isFinite(multiplier) || multiplier <= 0f || multiplier == 1f || !allowsMechanicalOverclock()) {
            return scaled;
        }
        boolean virtualRegenerativeCycle = isRegenerativeRecipe() && naturalWaitTicks >= cycleTicks;
        int fixedNaturalTicks = virtualRegenerativeCycle ? 0 : naturalWaitTicks;
        int scalableTicks = virtualRegenerativeCycle ? cycleTicks : Math.max(1, cycleTicks - naturalWaitTicks);
        double adjusted = fixedNaturalTicks + Math.ceil(scalableTicks / (double) multiplier);
        scaled.cycleTicks = adjusted >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) adjusted);
        if (virtualRegenerativeCycle) {
            scaled.naturalWaitTicks = scaled.cycleTicks;
        }
        float baseConsumed = getLearnedPeakConsumedSU();
        float scaledConsumed = safeScaleStress(baseConsumed, multiplier);
        float connectedDeficit = Math.max(0f, baseConsumed - peakInternalGeneratedSU);
        float internalAvailable = Math.abs(connectedDeficit - peakExternalStressSU) <= 0.01f
                ? peakInternalGeneratedSU
                : Math.max(0f, baseConsumed - peakExternalStressSU);
        scaled.peakExternalStressSU = Math.max(0f, scaledConsumed - internalAvailable);
        // Internal generators belong to the frozen factory and retain their learned capacity.
        // Only the virtual production load changes with the selected throughput tier.
        scaled.externalStressSUTicks = safeStressEnergy(scaled.peakExternalStressSU, scaled.cycleTicks);
        scaled.peakInternalGeneratedSU = peakInternalGeneratedSU;
        scaled.internalGenerationSUTicks = safeStressEnergy(
                getLearnedAverageInternalGeneratedSU(), scaled.cycleTicks);
        scaled.peakConsumedSU = scaledConsumed;
        return scaled;
    }

    public FactoryPlan copy() {
        if (hasIndependentRoutes()) {
            return combineIndependent(
                    independentProcessingRoute == null ? null : independentProcessingRoute.copy(),
                    independentRegenerativeRoute == null ? null : independentRegenerativeRoute.copy());
        }
        FactoryPlan copy = new FactoryPlan();
        copy.itemInputs.putAll(itemInputs);
        copy.processingItemOutputs.putAll(processingItemOutputs);
        copy.regenerativeItemOutputs.putAll(regenerativeItemOutputs);
        copy.fluidInputs.putAll(fluidInputs);
        copy.processingFluidOutputs.putAll(processingFluidOutputs);
        copy.regenerativeFluidOutputs.putAll(regenerativeFluidOutputs);
        copy.processingToolDamageCosts.putAll(processingToolDamageCosts);
        copy.regenerativeToolDamageCosts.putAll(regenerativeToolDamageCosts);
        copy.startupCapitalItems.putAll(startupCapitalItems);
        copy.sourceProofs.addAll(sourceProofs);
        copy.cycleTicks = cycleTicks;
        copy.naturalWaitTicks = naturalWaitTicks;
        copy.sourceKind = sourceKind;
        copy.verifiedCycles = verifiedCycles;
        copy.statisticalOutput = statisticalOutput;
        copy.externalStressSUTicks = externalStressSUTicks;
        copy.peakExternalStressSU = peakExternalStressSU;
        copy.internalGenerationSUTicks = internalGenerationSUTicks;
        copy.peakInternalGeneratedSU = peakInternalGeneratedSU;
        copy.peakConsumedSU = peakConsumedSU;
        return copy;
    }

    /** Publishes independently measured processing and regenerative route contracts atomically. */
    public static FactoryPlan combineIndependent(FactoryPlan processing, FactoryPlan regenerative) {
        FactoryPlan combined = new FactoryPlan();
        if (processing != null && processing.hasProcessingRoute()) {
            combined.independentProcessingRoute = processing.processingRoute().copy();
        }
        if (regenerative != null && regenerative.hasRegenerativeRoute()) {
            combined.independentRegenerativeRoute = regenerative.regenerativeRoute().copy();
        }
        if (!combined.hasCompleteRecipe()) combined.clear();
        return combined;
    }

    /** Human-readable, stable diagnostics; never used as persisted or executable plan state. */
    public String debugSummary() {
        if (hasIndependentRoutes()) {
            return "independentRoutes=true, processing={"
                    + (independentProcessingRoute == null ? "none" : independentProcessingRoute.debugSummary())
                    + "}, regenerative={"
                    + (independentRegenerativeRoute == null ? "none" : independentRegenerativeRoute.debugSummary())
                    + "}, averageStressSU=" + getLearnedAverageExternalStressSU()
                    + ", peakStressSU=" + getLearnedPeakExternalStressSU()
                    + ", averageInternalGenerationSU=" + getLearnedAverageInternalGeneratedSU()
                    + ", peakInternalGenerationSU=" + getLearnedPeakInternalGeneratedSU()
                    + ", peakConsumedSU=" + getLearnedPeakConsumedSU();
        }
        return "cycleTicks=" + cycleTicks
                + ", naturalWaitTicks=" + naturalWaitTicks
                + ", source=" + sourceKind
                + ", verifiedCycles=" + verifiedCycles
                + ", itemInputs=" + itemInputs
                + ", processingItemOutputs=" + processingItemOutputs
                + ", regenerativeItemOutputs=" + regenerativeItemOutputs
                + ", fluidInputs=" + fluidInputs
                + ", processingFluidOutputs=" + processingFluidOutputs
                + ", regenerativeFluidOutputs=" + regenerativeFluidOutputs
                + ", processingToolDamage=" + processingToolDamageCosts
                + ", regenerativeToolDamage=" + regenerativeToolDamageCosts
                + ", startupCapital=" + startupCapitalItems
                + ", averageStressSU=" + getLearnedAverageExternalStressSU()
                + ", peakStressSU=" + peakExternalStressSU
                + ", averageInternalGenerationSU=" + getLearnedAverageInternalGeneratedSU()
                + ", peakInternalGenerationSU=" + peakInternalGeneratedSU
                + ", peakConsumedSU=" + getLearnedPeakConsumedSU()
                + ", proofs=" + sourceProofs;
    }

    FactoryPlan withStartupCapital(Map<ItemVariant, Long> capital) {
        startupCapitalItems.clear();
        copyPositive(startupCapitalItems, capital);
        fingerprintCache = null;
        return this;
    }

    static FactoryPlan compiled(Map<ItemVariant, Long> itemInputs,
                                Map<ItemVariant, Long> processingItemOutputs,
                                Map<FluidVariant, Long> fluidInputs,
                                Map<FluidVariant, Long> processingFluidOutputs,
                                Map<ItemVariant, Long> regenerativeItemOutputs,
                                Map<FluidVariant, Long> regenerativeFluidOutputs,
                                Map<ItemVariant, Long> processingToolDamageCosts,
                                Map<ItemVariant, Long> regenerativeToolDamageCosts,
                                int cycleTicks,
                                int naturalWaitTicks,
                                SourceKind sourceKind,
                                int verifiedCycles,
                                boolean statisticalOutput,
                                double externalStressSUTicks,
                                float peakExternalStressSU,
                                double internalGenerationSUTicks,
                                float peakInternalGeneratedSU,
                                float peakConsumedSU,
                                List<String> sourceProofs) {
        FactoryPlan plan = new FactoryPlan();
        copyPositive(plan.itemInputs, itemInputs);
        copyPositive(plan.processingItemOutputs, processingItemOutputs);
        copyPositive(plan.regenerativeItemOutputs, regenerativeItemOutputs);
        copyPositive(plan.fluidInputs, fluidInputs);
        copyPositive(plan.processingFluidOutputs, processingFluidOutputs);
        copyPositive(plan.regenerativeFluidOutputs, regenerativeFluidOutputs);
        copyPositive(plan.processingToolDamageCosts, processingToolDamageCosts);
        copyPositive(plan.regenerativeToolDamageCosts, regenerativeToolDamageCosts);
        plan.cycleTicks = Math.max(0, cycleTicks);
        plan.naturalWaitTicks = Math.clamp(naturalWaitTicks, 0, plan.cycleTicks);
        plan.sourceKind = sourceKind == null ? SourceKind.NONE : sourceKind;
        plan.verifiedCycles = Math.max(0, verifiedCycles);
        plan.statisticalOutput = statisticalOutput;
        plan.externalStressSUTicks = finitePositive(externalStressSUTicks);
        plan.peakExternalStressSU = Math.max(plan.getLearnedAverageExternalStressSU(), finitePositive(peakExternalStressSU));
        plan.internalGenerationSUTicks = finitePositive(internalGenerationSUTicks);
        plan.peakInternalGeneratedSU = Math.max(plan.getLearnedAverageInternalGeneratedSU(),
                finitePositive(peakInternalGeneratedSU));
        plan.peakConsumedSU = finitePositive(peakConsumedSU);
        if (sourceProofs != null) {
            sourceProofs.stream().filter(value -> value != null && !value.isBlank()).distinct().sorted()
                    .forEach(plan.sourceProofs::add);
        }
        if (!withinLimits(plan.regenerativeItemOutputs, plan.processingItemOutputs)
                || !withinLimits(plan.regenerativeFluidOutputs, plan.processingFluidOutputs)
                || !withinLimits(plan.regenerativeToolDamageCosts, plan.processingToolDamageCosts)) {
            plan.clear();
            return plan;
        }
        subtract(plan.processingItemOutputs, plan.regenerativeItemOutputs);
        subtract(plan.processingFluidOutputs, plan.regenerativeFluidOutputs);
        subtract(plan.processingToolDamageCosts, plan.regenerativeToolDamageCosts);
        if (plan.hasUnattributedSourceProof() || !plan.hasCompleteRecipe()) {
            plan.clear();
        }
        return plan;
    }

    /** Display-only window data used while the physical room is running. */
    static FactoryPlan preview(Map<ItemVariant, Long> itemInputs,
                               Map<ItemVariant, Long> processingItemOutputs,
                               Map<FluidVariant, Long> fluidInputs,
                               Map<FluidVariant, Long> processingFluidOutputs,
                               int windowTicks) {
        FactoryPlan plan = new FactoryPlan();
        copyPositive(plan.itemInputs, itemInputs);
        copyPositive(plan.processingItemOutputs, processingItemOutputs);
        copyPositive(plan.fluidInputs, fluidInputs);
        copyPositive(plan.processingFluidOutputs, processingFluidOutputs);
        plan.cycleTicks = Math.max(1, windowTicks);
        return plan;
    }

    public String fingerprint() {
        if (fingerprintCache != null) return fingerprintCache;
        if (hasIndependentRoutes()) {
            String value = "v=" + FORMAT_VERSION + "|independent=true|processing="
                    + (independentProcessingRoute == null ? "" : independentProcessingRoute.fingerprint())
                    + "|regenerative="
                    + (independentRegenerativeRoute == null ? "" : independentRegenerativeRoute.fingerprint());
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                return fingerprintCache = HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
        StringBuilder value = new StringBuilder("v=").append(FORMAT_VERSION)
                .append("|ticks=").append(cycleTicks)
                .append("|natural=").append(naturalWaitTicks)
                .append("|source=").append(sourceKind)
                .append("|cycles=").append(verifiedCycles)
                .append("|stat=").append(statisticalOutput)
                .append("|stressSUTicks=").append(Double.doubleToLongBits(externalStressSUTicks))
                .append("|peakStress=").append(Float.floatToIntBits(peakExternalStressSU))
                .append("|internalGenerationSUTicks=")
                .append(Double.doubleToLongBits(internalGenerationSUTicks))
                .append("|peakInternalGeneration=").append(Float.floatToIntBits(peakInternalGeneratedSU))
                .append("|peakConsumed=").append(Float.floatToIntBits(peakConsumedSU));
        sourceProofs.forEach(proof -> value.append("|proof=").append(proof));
        appendVariants(value, "|ii:", itemInputs);
        appendVariants(value, "|io:", processingItemOutputs);
        appendVariants(value, "|rio:", regenerativeItemOutputs);
        appendFluids(value, "|fi:", fluidInputs);
        appendFluids(value, "|fo:", processingFluidOutputs);
        appendFluids(value, "|rfo:", regenerativeFluidOutputs);
        appendVariants(value, "|tool:", processingToolDamageCosts);
        appendVariants(value, "|rtool:", regenerativeToolDamageCosts);
        appendVariants(value, "|capital:", startupCapitalItems);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            return fingerprintCache = HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("PlanFormatVersion", FORMAT_VERSION);
        if (hasIndependentRoutes()) {
            tag.putBoolean("IndependentRoutes", true);
            if (independentProcessingRoute != null) {
                tag.put("IndependentProcessingRoute",
                        independentProcessingRoute.write(new CompoundTag(), registries));
            }
            if (independentRegenerativeRoute != null) {
                tag.put("IndependentRegenerativeRoute",
                        independentRegenerativeRoute.write(new CompoundTag(), registries));
            }
            tag.putString("Fingerprint", fingerprint());
            return tag;
        }
        writeVariantLongMap(tag, "ItemInputs", itemInputs, registries);
        writeVariantLongMap(tag, "ProcessingItemOutputs", processingItemOutputs, registries);
        writeVariantLongMap(tag, "RegenerativeItemOutputs", regenerativeItemOutputs, registries);
        writeFluidLongMap(tag, "FluidInputs", fluidInputs, registries);
        writeFluidLongMap(tag, "ProcessingFluidOutputs", processingFluidOutputs, registries);
        writeFluidLongMap(tag, "RegenerativeFluidOutputs", regenerativeFluidOutputs, registries);
        writeVariantLongMap(tag, "ProcessingToolDamageCosts", processingToolDamageCosts, registries);
        writeVariantLongMap(tag, "RegenerativeToolDamageCosts", regenerativeToolDamageCosts, registries);
        writeVariantLongMap(tag, "StartupCapitalItems", startupCapitalItems, registries);
        tag.putInt("CycleTicks", cycleTicks);
        tag.putInt("NaturalWaitTicks", naturalWaitTicks);
        tag.putString("SourceKind", sourceKind.name());
        tag.putInt("VerifiedCycles", verifiedCycles);
        tag.putBoolean("StatisticalOutput", statisticalOutput);
        tag.putDouble("ExternalStressSUTicks", externalStressSUTicks);
        tag.putFloat("PeakExternalStressSU", peakExternalStressSU);
        tag.putDouble("InternalGenerationSUTicks", internalGenerationSUTicks);
        tag.putFloat("PeakInternalGeneratedSU", peakInternalGeneratedSU);
        tag.putFloat("PeakConsumedSU", peakConsumedSU);
        ListTag proofs = new ListTag();
        sourceProofs.forEach(proof -> proofs.add(StringTag.valueOf(proof)));
        tag.put("SourceProofs", proofs);
        tag.putString("Fingerprint", fingerprint());
        return tag;
    }

    /** Strict cutover: no earlier black-box format is inferred into a v9 plan. */
    public void read(CompoundTag tag, HolderLookup.Provider registries) {
        clear();
        if (tag.getInt("PlanFormatVersion") != FORMAT_VERSION) {
            return;
        }
        if (tag.getBoolean("IndependentRoutes")) {
            if (tag.contains("IndependentProcessingRoute", Tag.TAG_COMPOUND)) {
                FactoryPlan processing = new FactoryPlan();
                processing.read(tag.getCompound("IndependentProcessingRoute"), registries);
                if (processing.hasProcessingRoute()) independentProcessingRoute = processing.processingRoute().copy();
            }
            if (tag.contains("IndependentRegenerativeRoute", Tag.TAG_COMPOUND)) {
                FactoryPlan regenerative = new FactoryPlan();
                regenerative.read(tag.getCompound("IndependentRegenerativeRoute"), registries);
                if (regenerative.hasRegenerativeRoute()) {
                    independentRegenerativeRoute = regenerative.regenerativeRoute().copy();
                }
            }
            String storedFingerprint = tag.getString("Fingerprint");
            if (!hasCompleteRecipe() || storedFingerprint.isBlank()
                    || !storedFingerprint.equals(fingerprint())) clear();
            return;
        }
        readVariantLongMap(tag, "ItemInputs", itemInputs, registries);
        readVariantLongMap(tag, "ProcessingItemOutputs", processingItemOutputs, registries);
        readVariantLongMap(tag, "RegenerativeItemOutputs", regenerativeItemOutputs, registries);
        readFluidLongMap(tag, "FluidInputs", fluidInputs, registries);
        readFluidLongMap(tag, "ProcessingFluidOutputs", processingFluidOutputs, registries);
        readFluidLongMap(tag, "RegenerativeFluidOutputs", regenerativeFluidOutputs, registries);
        readVariantLongMap(tag, "ProcessingToolDamageCosts", processingToolDamageCosts, registries);
        readVariantLongMap(tag, "RegenerativeToolDamageCosts", regenerativeToolDamageCosts, registries);
        readVariantLongMap(tag, "StartupCapitalItems", startupCapitalItems, registries);
        cycleTicks = Math.max(0, tag.getInt("CycleTicks"));
        naturalWaitTicks = Math.clamp(tag.getInt("NaturalWaitTicks"), 0, cycleTicks);
        try {
            sourceKind = SourceKind.valueOf(tag.getString("SourceKind"));
        } catch (IllegalArgumentException ignored) {
            sourceKind = SourceKind.NONE;
        }
        verifiedCycles = Math.max(0, tag.getInt("VerifiedCycles"));
        statisticalOutput = tag.getBoolean("StatisticalOutput");
        externalStressSUTicks = finitePositive(tag.getDouble("ExternalStressSUTicks"));
        peakExternalStressSU = Math.max(getLearnedAverageExternalStressSU(), finitePositive(tag.getFloat("PeakExternalStressSU")));
        internalGenerationSUTicks = finitePositive(tag.getDouble("InternalGenerationSUTicks"));
        peakInternalGeneratedSU = Math.max(getLearnedAverageInternalGeneratedSU(),
                finitePositive(tag.getFloat("PeakInternalGeneratedSU")));
        peakConsumedSU = finitePositive(tag.getFloat("PeakConsumedSU"));
        for (Tag proof : tag.getList("SourceProofs", Tag.TAG_STRING)) {
            String value = proof.getAsString();
            if (!value.isBlank() && !sourceProofs.contains(value)) sourceProofs.add(value);
        }
        sourceProofs.sort(String::compareTo);
        String storedFingerprint = tag.getString("Fingerprint");
        boolean hasDeclaredRegenerativeRoute = !regenerativeItemOutputs.isEmpty()
                || !regenerativeFluidOutputs.isEmpty() || !regenerativeToolDamageCosts.isEmpty();
        boolean invalidSourceProof = (sourceKind.isRegenerative() || hasDeclaredRegenerativeRoute)
                && (!sourceKind.isRegenerative() || sourceProofs.isEmpty()
                || !sourceProofs.stream().allMatch(RegenerativeSourceAdapters::isCurrentProof));
        if (invalidSourceProof || hasUnattributedSourceProof() || !hasCompleteRecipe() || storedFingerprint.isBlank()
                || !storedFingerprint.equals(fingerprint())) {
            clear();
        }
    }

    /** A source proof grants production rights only when it actually attributes an output. */
    private boolean hasUnattributedSourceProof() {
        return sourceKind.isRegenerative()
                && regenerativeItemOutputs.isEmpty()
                && regenerativeFluidOutputs.isEmpty();
    }

    private void clear() {
        itemInputs.clear();
        processingItemOutputs.clear();
        regenerativeItemOutputs.clear();
        fluidInputs.clear();
        processingFluidOutputs.clear();
        regenerativeFluidOutputs.clear();
        processingToolDamageCosts.clear();
        regenerativeToolDamageCosts.clear();
        startupCapitalItems.clear();
        sourceProofs.clear();
        cycleTicks = 0;
        naturalWaitTicks = 0;
        sourceKind = SourceKind.NONE;
        verifiedCycles = 0;
        statisticalOutput = false;
        externalStressSUTicks = 0d;
        peakExternalStressSU = 0f;
        internalGenerationSUTicks = 0d;
        peakInternalGeneratedSU = 0f;
        peakConsumedSU = 0f;
        independentProcessingRoute = null;
        independentRegenerativeRoute = null;
        processingRouteCache = null;
        regenerativeRouteCache = null;
        fingerprintCache = null;
    }

    private Map<ItemVariant, Float> itemRates(Map<ItemVariant, Long> values) {
        Map<ItemVariant, Float> rates = new HashMap<>();
        if (cycleTicks > 0) values.forEach((key, count) -> rates.put(key, perSecond(count)));
        return rates;
    }

    private Map<Fluid, Float> fluidRates(Map<FluidVariant, Long> values) {
        Map<Fluid, Float> rates = new HashMap<>();
        if (cycleTicks > 0) values.forEach((key, count) -> rates.merge(key.fluid(), perSecond(count), Float::sum));
        return rates;
    }

    private float perSecond(long count) {
        return cycleTicks <= 0 ? 0f : (float) (count * 20.0d / cycleTicks);
    }

    private static float safeScaleStress(float value, float multiplier) {
        double scaled = value * (double) multiplier;
        return !Double.isFinite(scaled) || scaled >= Float.MAX_VALUE ? Float.MAX_VALUE
                : Math.max(0f, (float) scaled);
    }

    private static double safeStressEnergy(float stress, int ticks) {
        double value = stress * (double) Math.max(0, ticks);
        return Double.isFinite(value) && value > 0d ? value : 0d;
    }

    private static float finitePositive(float value) {
        return Float.isFinite(value) && value > 0f ? value : 0f;
    }

    private static double finitePositive(double value) {
        return Double.isFinite(value) && value > 0d ? value : 0d;
    }

    private static <K> void copyPositive(Map<K, Long> target, Map<K, Long> source) {
        if (source == null) return;
        source.forEach((key, value) -> {
            if (key != null && value != null && value > 0) target.put(key, value);
        });
    }

    private static <K> boolean withinLimits(Map<K, Long> subset, Map<K, Long> totals) {
        for (Map.Entry<K, Long> entry : subset.entrySet()) {
            if (entry.getValue() <= 0L || entry.getValue() > totals.getOrDefault(entry.getKey(), 0L)) {
                return false;
            }
        }
        return true;
    }

    private static <K> Map<K, Long> merged(Map<K, Long> first, Map<K, Long> second) {
        Map<K, Long> result = new HashMap<>(first);
        second.forEach((key, value) -> result.merge(key, value, Math::addExact));
        return result;
    }

    private static <K> Map<K, Float> mergedRates(Map<K, Float> first, Map<K, Float> second) {
        Map<K, Float> result = new HashMap<>(first);
        second.forEach((key, value) -> result.merge(key, value, Float::sum));
        return Collections.unmodifiableMap(result);
    }

    private static <K> void subtract(Map<K, Long> totals, Map<K, Long> subset) {
        subset.forEach((key, amount) -> {
            long remaining = totals.getOrDefault(key, 0L) - amount;
            if (remaining > 0L) totals.put(key, remaining); else totals.remove(key);
        });
    }

    private static void writeVariantLongMap(CompoundTag root, String key, Map<ItemVariant, Long> values,
                                            HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue() <= 0) return;
            CompoundTag value = new CompoundTag();
            value.put("Stack", entry.getKey().write(registries));
            value.putLong("Value", entry.getValue());
            list.add(value);
        });
        root.put(key, list);
    }

    private static void readVariantLongMap(CompoundTag root, String key, Map<ItemVariant, Long> target,
                                           HolderLookup.Provider registries) {
        for (Tag raw : root.getList(key, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            ItemVariant variant = ItemVariant.read(registries, entry.getCompound("Stack"));
            long value = entry.getLong("Value");
            if (variant != null && value > 0) target.put(variant, value);
        }
    }

    private static void writeFluidLongMap(CompoundTag tag, String key, Map<FluidVariant, Long> map,
                                          HolderLookup.Provider registries) {
        ListTag values = new ListTag();
        map.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue() <= 0) return;
            CompoundTag value = new CompoundTag();
            value.put("Stack", entry.getKey().write(registries));
            value.putLong("Value", entry.getValue());
            values.add(value);
        });
        tag.put(key, values);
    }

    private static void readFluidLongMap(CompoundTag tag, String key, Map<FluidVariant, Long> map,
                                         HolderLookup.Provider registries) {
        for (Tag raw : tag.getList(key, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            FluidVariant variant = FluidVariant.read(registries, entry.getCompound("Stack"));
            long value = entry.getLong("Value");
            if (variant != null && value > 0) map.put(variant, value);
        }
    }

    private static void appendVariants(StringBuilder builder, String prefix, Map<ItemVariant, Long> map) {
        map.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append(prefix).append(entry.getKey().canonicalKey())
                        .append('=').append(entry.getValue()));
    }

    private static void appendFluids(StringBuilder builder, String prefix, Map<FluidVariant, Long> map) {
        map.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append(prefix).append(entry.getKey().canonicalKey())
                        .append('=').append(entry.getValue()));
    }
}

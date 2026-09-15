package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Collects live telemetry and compiles one continuous learning run into an immutable v9 plan. */
public final class FactoryPlanCompiler {
    public static final int DISPLAY_WINDOW_TICKS = 20;

    public record Observation(int ticks,
                              Map<ItemVariant, Long> itemInputs,
                              Map<ItemVariant, Long> itemOutputs,
                              Map<FluidVariant, Long> fluidInputs,
                              Map<FluidVariant, Long> fluidOutputs,
                              double externalStressSUTicks,
                              float peakExternalStressSU,
                              double internalGenerationSUTicks,
                              float peakInternalGeneratedSU,
                              float peakConsumedSU) {
        public Observation {
            itemInputs = Map.copyOf(itemInputs);
            itemOutputs = Map.copyOf(itemOutputs);
            fluidInputs = Map.copyOf(fluidInputs);
            fluidOutputs = Map.copyOf(fluidOutputs);
        }

        public boolean hasOutput() {
            return !itemOutputs.isEmpty() || !fluidOutputs.isEmpty();
        }

        public boolean hasExternalInput() {
            return !itemInputs.isEmpty() || !fluidInputs.isEmpty();
        }
    }

    private final Map<ItemVariant, Long> liveItemInputs = new HashMap<>();
    private final Map<ItemVariant, Long> liveItemOutputs = new HashMap<>();
    private final Map<FluidVariant, Long> liveFluidInputs = new HashMap<>();
    private final Map<FluidVariant, Long> liveFluidOutputs = new HashMap<>();
    private int liveTicks;
    private FactoryPlan preview = new FactoryPlan();

    private final Map<ItemVariant, Long> learningItemInputs = new HashMap<>();
    private final Map<ItemVariant, Long> learningItemOutputs = new HashMap<>();
    private final Map<FluidVariant, Long> learningFluidInputs = new HashMap<>();
    private final Map<FluidVariant, Long> learningFluidOutputs = new HashMap<>();
    private int learningTicks;
    private boolean recording;
    private double externalStressSUTicks;
    private float peakExternalStressSU;
    private double internalGenerationSUTicks;
    private float peakInternalGeneratedSU;
    private float peakConsumedSU;

    public FactoryPlan preview() {
        return preview;
    }

    public void tick() {
        liveTicks++;
        if (recording) learningTicks++;
        if (liveTicks >= DISPLAY_WINDOW_TICKS) commitPreview();
    }

    public void beginLearning() {
        recording = true;
        clearLearningWindow();
    }

    public void stopLearning() {
        recording = false;
        clearLearningWindow();
    }

    public boolean hasCurrentOutput() {
        return !learningItemOutputs.isEmpty() || !learningFluidOutputs.isEmpty();
    }

    public int currentLearningTicks() {
        return learningTicks;
    }

    public void recordStressSupply(float externalDemandSU, float internalGeneratedSU, float consumedSU) {
        if (!recording) return;
        float external = finitePositive(externalDemandSU);
        float internal = finitePositive(internalGeneratedSU);
        float consumed = finitePositive(consumedSU);
        externalStressSUTicks += external;
        peakExternalStressSU = Math.max(peakExternalStressSU, external);
        internalGenerationSUTicks += internal;
        peakInternalGeneratedSU = Math.max(peakInternalGeneratedSU, internal);
        peakConsumedSU = Math.max(peakConsumedSU, consumed);
    }

    public void recordItemInput(ItemStack stack, long moved) {
        if (stack == null || stack.isEmpty() || moved <= 0) return;
        ItemVariant variant = ItemVariant.of(stack);
        liveItemInputs.merge(variant, moved, FactoryPlanCompiler::safeAdd);
        if (recording) learningItemInputs.merge(variant, moved, FactoryPlanCompiler::safeAdd);
    }

    public void recordItemOutput(ItemStack stack, long moved) {
        if (stack == null || stack.isEmpty() || moved <= 0) return;
        ItemVariant variant = ItemVariant.of(stack);
        liveItemOutputs.merge(variant, moved, FactoryPlanCompiler::safeAdd);
        if (recording) learningItemOutputs.merge(variant, moved, FactoryPlanCompiler::safeAdd);
    }

    public void recordFluidInput(FluidStack stack, long moved) {
        if (stack == null || stack.isEmpty() || moved <= 0) return;
        FluidVariant variant = FluidVariant.of(stack);
        liveFluidInputs.merge(variant, moved, FactoryPlanCompiler::safeAdd);
        if (recording) learningFluidInputs.merge(variant, moved, FactoryPlanCompiler::safeAdd);
    }

    public void recordFluidOutput(FluidStack stack, long moved) {
        if (stack == null || stack.isEmpty() || moved <= 0) return;
        FluidVariant variant = FluidVariant.of(stack);
        liveFluidOutputs.merge(variant, moved, FactoryPlanCompiler::safeAdd);
        if (recording) learningFluidOutputs.merge(variant, moved, FactoryPlanCompiler::safeAdd);
    }

    public Observation finishObservation() {
        Observation result = new Observation(learningTicks,
                positiveCopy(learningItemInputs), positiveCopy(learningItemOutputs),
                positiveCopy(learningFluidInputs), positiveCopy(learningFluidOutputs),
                externalStressSUTicks, peakExternalStressSU, internalGenerationSUTicks,
                peakInternalGeneratedSU, peakConsumedSU);
        clearLearningWindow();
        return result;
    }

    public FactoryPlan compile(List<Observation> observations, FactoryLearningSession evidence) {
        return compile(observations, evidence, Map.of(), Map.of(), Map.of());
    }

    public FactoryPlan compile(List<Observation> observations, FactoryLearningSession evidence,
                               Map<ItemVariant, Long> roomItemAdjustments,
                               Map<FluidVariant, Long> roomFluidAdjustments,
                               Map<ItemVariant, Long> finalToolDamageCosts) {
        if (observations == null || observations.isEmpty()) return new FactoryPlan();

        Map<ItemVariant, Long> itemInputs = new HashMap<>();
        Map<ItemVariant, Long> itemOutputs = new HashMap<>();
        Map<FluidVariant, Long> fluidInputs = new HashMap<>();
        Map<FluidVariant, Long> fluidOutputs = new HashMap<>();
        Map<ItemVariant, Long> roomItemCosts = new HashMap<>();
        Map<FluidVariant, Long> roomFluidCosts = new HashMap<>();
        Map<ItemVariant, Long> toolDamage = new HashMap<>();
        double externalStress = 0;
        double internalGeneration = 0;
        float peakExternal = 0;
        float peakInternal = 0;
        float peakConsumed = 0;
        int totalTicks = 0;
        for (Observation observation : observations) {
            if (observation == null || observation.ticks() <= 0) return new FactoryPlan();
            totalTicks = Math.addExact(totalTicks, observation.ticks());
            merge(itemInputs, observation.itemInputs());
            merge(itemOutputs, observation.itemOutputs());
            merge(fluidInputs, observation.fluidInputs());
            merge(fluidOutputs, observation.fluidOutputs());
            externalStress += observation.externalStressSUTicks();
            internalGeneration += observation.internalGenerationSUTicks();
            peakExternal = Math.max(peakExternal, observation.peakExternalStressSU());
            peakInternal = Math.max(peakInternal, observation.peakInternalGeneratedSU());
            peakConsumed = Math.max(peakConsumed, observation.peakConsumedSU());
        }
        addSigned(roomItemCosts, roomItemAdjustments);
        addSigned(roomFluidCosts, roomFluidAdjustments);
        merge(toolDamage, finalToolDamageCosts);

        boolean hasBoundaryInput = !itemInputs.isEmpty() || !fluidInputs.isEmpty();
        boolean sourceSupported = evidence != null && evidence.isSupported();
        if (sourceSupported && !evidence.isReady()) return new FactoryPlan();
        roomItemCosts.entrySet().removeIf(entry -> entry.getKey().prototype().isDamageableItem());
        if (sourceSupported) {
            Map<ItemVariant, Long> observedItemOutputs = itemOutputs;
            Map<ItemVariant, Long> observedItemInputs = itemInputs;
            Map<FluidVariant, Long> observedFluidOutputs = fluidOutputs;
            Map<FluidVariant, Long> observedFluidInputs = fluidInputs;
            roomItemCosts.entrySet().removeIf(entry -> {
                long observed = observedItemOutputs.getOrDefault(entry.getKey(), 0L);
                return observed > 0L && !observedItemInputs.containsKey(entry.getKey())
                        && evidence.certifiesOutput(entry.getKey(), observed);
            });
            roomFluidCosts.entrySet().removeIf(entry -> {
                long observed = observedFluidOutputs.getOrDefault(entry.getKey(), 0L);
                return observed > 0L && !observedFluidInputs.containsKey(entry.getKey())
                        && evidence.certifiesOutput(entry.getKey(), observed);
            });
        }
        roomItemCosts.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() == 0L);
        roomFluidCosts.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() == 0L);
        boolean hasRoomCost = hasPositive(roomItemCosts) || hasPositive(roomFluidCosts);
        if (!hasBoundaryInput && sourceSupported && hasRoomCost) return new FactoryPlan();
        // Finite preloaded room stock can correct a boundary sample, but cannot establish a
        // reusable ordinary recipe by itself.
        if (!hasBoundaryInput && !sourceSupported) return new FactoryPlan();
        RoomBalanceReconciler.reconcile(itemInputs, itemOutputs, roomItemCosts);
        RoomBalanceReconciler.reconcile(fluidInputs, fluidOutputs, roomFluidCosts);

        Map<ItemVariant, Long> regenerativeItems = new HashMap<>();
        Map<FluidVariant, Long> regenerativeFluids = new HashMap<>();
        boolean hasMaterialInputs = !itemInputs.isEmpty() || !fluidInputs.isEmpty();
        if (sourceSupported) {
            if (!hasMaterialInputs) {
                for (Map.Entry<ItemVariant, Long> entry : itemOutputs.entrySet()) {
                    long claimed = evidence.claimedAmount(entry.getKey(), entry.getValue(), false);
                    if (claimed > 0) regenerativeItems.put(entry.getKey(), claimed);
                }
                for (Map.Entry<FluidVariant, Long> entry : fluidOutputs.entrySet()) {
                    long claimed = evidence.claimedAmount(entry.getKey(), entry.getValue(), false);
                    if (claimed > 0) regenerativeFluids.put(entry.getKey(), claimed);
                }
                itemOutputs = new HashMap<>(regenerativeItems);
                fluidOutputs = new HashMap<>(regenerativeFluids);
            } else {
                for (Map.Entry<ItemVariant, Long> entry : itemOutputs.entrySet()) {
                    if (evidence.hasAmbiguousMixedOutput(entry.getKey(), itemInputs.keySet())) {
                        return new FactoryPlan();
                    }
                    long claimed = evidence.claimedAmount(entry.getKey(), entry.getValue(), true);
                    if (claimed > 0) regenerativeItems.put(entry.getKey(), claimed);
                    if (claimed == 0 && evidence.isPotentialRegenerativeOutput(entry.getKey())) return new FactoryPlan();
                }
                for (Map.Entry<FluidVariant, Long> entry : fluidOutputs.entrySet()) {
                    if (evidence.hasAmbiguousMixedOutput(entry.getKey(), fluidInputs.keySet())) {
                        return new FactoryPlan();
                    }
                    long claimed = evidence.claimedAmount(entry.getKey(), entry.getValue(), true);
                    if (claimed > 0) regenerativeFluids.put(entry.getKey(), claimed);
                    if (claimed == 0 && evidence.isPotentialRegenerativeOutput(entry.getKey())) return new FactoryPlan();
                }
            }
        }

        itemInputs = snapNearIntegers(itemInputs, totalTicks);
        itemOutputs = snapNearIntegers(itemOutputs, totalTicks);
        fluidInputs = snapNearIntegers(fluidInputs, totalTicks);
        fluidOutputs = snapNearIntegers(fluidOutputs, totalTicks);
        regenerativeItems = snapNearIntegers(regenerativeItems, totalTicks);
        regenerativeFluids = snapNearIntegers(regenerativeFluids, totalTicks);

        Map<ItemVariant, Long> evidenceToolDamage = sourceSupported
                ? positiveCopy(evidence.toolDamageTotals()) : Map.of();
        evidenceToolDamage.forEach((variant, count) -> toolDamage.merge(variant, count, Math::max));
        Map<ItemVariant, Long> regenerativeToolDamage = sourceSupported && !regenerativeItems.isEmpty()
                ? (hasMaterialInputs ? cappedCosts(evidenceToolDamage, toolDamage) : new HashMap<>(toolDamage))
                : Map.of();

        long divisor = totalTicks;
        divisor = gcdMap(divisor, itemInputs);
        divisor = gcdMap(divisor, itemOutputs);
        divisor = gcdMap(divisor, fluidInputs);
        divisor = gcdMap(divisor, fluidOutputs);
        divisor = gcdMap(divisor, regenerativeItems);
        divisor = gcdMap(divisor, regenerativeFluids);
        divisor = gcdMap(divisor, toolDamage);
        divisor = Math.max(1, divisor);
        int cycleTicks = Math.max(1, (int) Math.min(Integer.MAX_VALUE, totalTicks / divisor));

        FactoryPlan.SourceKind sourceKind = sourceSupported ? evidence.sourceKind() : FactoryPlan.SourceKind.NONE;
        int naturalTicks = sourceSupported && evidence.hasNaturalWait() ? cycleTicks : 0;
        FactoryPlan compiled = FactoryPlan.compiled(
                divided(itemInputs, divisor), divided(itemOutputs, divisor),
                divided(fluidInputs, divisor), divided(fluidOutputs, divisor),
                divided(regenerativeItems, divisor), divided(regenerativeFluids, divisor),
                divided(toolDamage, divisor), divided(regenerativeToolDamage, divisor),
                cycleTicks, naturalTicks, sourceKind, sourceSupported ? evidence.verifiedCycles() : 0,
                sourceSupported && evidence.hasStatisticalOutput(), externalStress / divisor, peakExternal,
                internalGeneration / divisor, peakInternal, peakConsumed,
                sourceSupported ? evidence.proofIds() : List.of());
        return compiled.withStartupCapital(sourceSupported ? evidence.startupCapitalItems() : Map.of());
    }

    /**
     * Locks the ordinary route while regenerative source groups continue collecting events.
     * Potential source outputs are omitted so a mixed farm cannot charge its renewable output
     * to the material-driven route merely because both routes share one output port.
     */
    public FactoryPlan compileProcessingCandidate(List<Observation> observations, FactoryLearningSession evidence,
                                                  Map<ItemVariant, Long> roomItemAdjustments,
                                                  Map<FluidVariant, Long> roomFluidAdjustments,
                                                  Map<ItemVariant, Long> toolDamageCosts) {
        if (observations == null || observations.isEmpty()) return new FactoryPlan();
        List<Observation> processing = observations.stream().map(observation -> new Observation(
                observation.ticks(), observation.itemInputs(),
                withoutPotentialSourceItems(observation.itemOutputs(), evidence), observation.fluidInputs(),
                withoutPotentialSourceFluids(observation.fluidOutputs(), evidence),
                observation.externalStressSUTicks(), observation.peakExternalStressSU(),
                observation.internalGenerationSUTicks(), observation.peakInternalGeneratedSU(),
                observation.peakConsumedSU())).toList();
        return compile(processing, null,
                withoutPotentialSourceItems(roomItemAdjustments, evidence),
                withoutPotentialSourceFluids(roomFluidAdjustments, evidence), toolDamageCosts);
    }

    public String candidateSummary(List<Observation> observations, FactoryLearningSession evidence) {
        if (observations == null || observations.isEmpty()) return "windows=0";
        long itemsIn = 0, itemsOut = 0, fluidsIn = 0, fluidsOut = 0;
        int ticks = 0;
        for (Observation observation : observations) {
            itemsIn = safeAdd(itemsIn, sum(observation.itemInputs()));
            itemsOut = safeAdd(itemsOut, sum(observation.itemOutputs()));
            fluidsIn = safeAdd(fluidsIn, sum(observation.fluidInputs()));
            fluidsOut = safeAdd(fluidsOut, sum(observation.fluidOutputs()));
            ticks = Math.addExact(ticks, observation.ticks());
        }
        return "windows=" + observations.size() + ", ticks=" + ticks + ", itemsIn=" + itemsIn
                + ", itemsOut=" + itemsOut + ", fluidsIn=" + fluidsIn + ", fluidsOut=" + fluidsOut
                + ", source={" + (evidence == null ? "none" : evidence.debugSummary()) + "}";
    }

    public String debugSummary() {
        return "recording=" + recording + ", learningTicks=" + learningTicks
                + ", itemInputs=" + learningItemInputs + ", itemOutputs=" + learningItemOutputs
                + ", fluidInputs=" + learningFluidInputs + ", fluidOutputs=" + learningFluidOutputs;
    }

    private void commitPreview() {
        preview = FactoryPlan.preview(liveItemInputs, liveItemOutputs, liveFluidInputs, liveFluidOutputs,
                Math.max(1, liveTicks));
        liveItemInputs.clear();
        liveItemOutputs.clear();
        liveFluidInputs.clear();
        liveFluidOutputs.clear();
        liveTicks = 0;
    }

    private void clearLearningWindow() {
        learningItemInputs.clear();
        learningItemOutputs.clear();
        learningFluidInputs.clear();
        learningFluidOutputs.clear();
        learningTicks = 0;
        externalStressSUTicks = 0;
        peakExternalStressSU = 0;
        internalGenerationSUTicks = 0;
        peakInternalGeneratedSU = 0;
        peakConsumedSU = 0;
    }

    private static <K> Map<K, Long> snapNearIntegers(Map<K, Long> values, int totalTicks) {
        Map<K, Long> result = new HashMap<>();
        if (totalTicks <= 0) return result;
        long seconds = totalTicks / 20L;
        values.forEach((key, amount) -> {
            if (key == null || amount == null || amount <= 0) return;
            double rate = amount * 20.0d / totalTicks;
            long nearest = Math.round(rate);
            if (nearest >= 1 && Math.abs(rate - nearest) <= 0.2d && totalTicks % 20 == 0 && seconds > 0
                    && nearest <= Long.MAX_VALUE / seconds) {
                result.put(key, nearest * seconds);
            } else {
                result.put(key, amount);
            }
        });
        return result;
    }

    private static <K> Map<K, Long> positiveCopy(Map<K, Long> source) {
        Map<K, Long> result = new HashMap<>();
        if (source != null) source.forEach((key, value) -> {
            if (key != null && value != null && value > 0) result.put(key, value);
        });
        return result;
    }

    private static Map<ItemVariant, Long> cappedCosts(Map<ItemVariant, Long> requested,
                                                       Map<ItemVariant, Long> total) {
        Map<ItemVariant, Long> result = new HashMap<>();
        requested.forEach((key, value) -> {
            long capped = Math.min(value, total.getOrDefault(key, 0L));
            if (capped > 0) result.put(key, capped);
        });
        return result;
    }

    private static <K> Map<K, Long> divided(Map<K, Long> source, long divisor) {
        Map<K, Long> result = new HashMap<>();
        source.forEach((key, value) -> {
            long reduced = value / divisor;
            if (reduced > 0) result.put(key, reduced);
        });
        return result;
    }

    private static <K> long gcdMap(long value, Map<K, Long> values) {
        for (long candidate : values.values()) if (candidate > 0) value = gcd(value, candidate);
        return value;
    }

    private static long gcd(long left, long right) {
        while (right != 0) {
            long next = left % right;
            left = right;
            right = next;
        }
        return Math.abs(left);
    }

    private static <K> void merge(Map<K, Long> target, Map<K, Long> source) {
        source.forEach((key, value) -> {
            if (key != null && value != null && value > 0) target.merge(key, value, FactoryPlanCompiler::safeAdd);
        });
    }

    private static <K> void addSigned(Map<K, Long> target, Map<K, Long> source) {
        if (source == null) return;
        source.forEach((key, value) -> {
            if (key != null && value != null && value != 0L) target.merge(key, value, Math::addExact);
        });
    }

    private static boolean hasPositive(Map<?, Long> values) {
        return values.values().stream().anyMatch(value -> value != null && value > 0L);
    }

    private static Map<ItemVariant, Long> withoutPotentialSourceItems(Map<ItemVariant, Long> source,
                                                                       FactoryLearningSession evidence) {
        if (evidence == null) return source;
        Map<ItemVariant, Long> result = new HashMap<>();
        source.forEach((variant, amount) -> {
            if (!evidence.isPotentialRegenerativeOutput(variant)) result.put(variant, amount);
        });
        return result;
    }

    private static Map<FluidVariant, Long> withoutPotentialSourceFluids(Map<FluidVariant, Long> source,
                                                                         FactoryLearningSession evidence) {
        if (evidence == null) return source;
        Map<FluidVariant, Long> result = new HashMap<>();
        source.forEach((variant, amount) -> {
            if (!evidence.isPotentialRegenerativeOutput(variant)) result.put(variant, amount);
        });
        return result;
    }

    private static long sum(Map<?, Long> values) {
        long total = 0;
        for (long value : values.values()) total = safeAdd(total, value);
        return total;
    }

    private static float finitePositive(float value) {
        return Float.isFinite(value) ? Math.max(0, value) : 0;
    }

    private static long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}

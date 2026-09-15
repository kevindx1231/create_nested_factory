package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One inaccessible route with persisted active and one-batch look-ahead ownership. */
public final class FactoryRuntime {
    public enum Phase {
        IDLE,
        COLLECTING_INPUT,
        INPUT_COMPLETE_WAITING_FOR_POWER,
        RUNNING,
        DELIVERING_OUTPUT
    }

    private static final int RUNTIME_FORMAT_VERSION = 8;
    private static final int STREAM_OUTPUT_INTERVAL_TICKS = 20;

    private Phase phase = Phase.IDLE;
    private String recipeFingerprint = "";
    private long batchId;
    private int progressTicks;
    /** Inputs owned by the active or currently assembling production batch. */
    private final Map<ItemVariant, Long> committedItems = new HashMap<>();
    private final Map<FluidVariant, Long> committedFluids = new HashMap<>();
    /** Exactly one look-ahead batch, filled while the active batch is running or delivering. */
    private final Map<ItemVariant, Long> readyItems = new HashMap<>();
    private final Map<FluidVariant, Long> readyFluids = new HashMap<>();
    private final Map<ItemVariant, Long> remainingItemOutputs = new HashMap<>();
    private final Map<FluidVariant, Long> remainingFluidOutputs = new HashMap<>();
    /** Cumulative output rights already minted during the current streamed tree batch. */
    private final Map<ItemVariant, Long> streamedItemOutputs = new HashMap<>();
    private final Map<FluidVariant, Long> streamedFluidOutputs = new HashMap<>();
    /** One private, damageable tool per learned tool signature.  These are not recipe outputs. */
    private final Map<ItemVariant, ItemStack> leasedTools = new HashMap<>();

    public Phase phase() {
        return phase;
    }

    public boolean isEmpty() {
        return isTransactionIdle() && leasedTools.isEmpty();
    }

    public boolean isTransactionIdle() {
        return phase == Phase.IDLE && committedItems.isEmpty() && committedFluids.isEmpty()
                && readyItems.isEmpty() && readyFluids.isEmpty()
                && remainingItemOutputs.isEmpty() && remainingFluidOutputs.isEmpty()
                && streamedItemOutputs.isEmpty() && streamedFluidOutputs.isEmpty() && progressTicks == 0;
    }

    public boolean isDeliveringOutputs() {
        return phase == Phase.DELIVERING_OUTPUT;
    }

    public boolean isAtBatchBoundary() {
        return phase != Phase.RUNNING && phase != Phase.DELIVERING_OUTPUT;
    }

    public List<ItemVariant> sortedRequiredTools(FactoryPlan recipe) {
        return sortedVariants(recipe.getRecipeToolDamageCosts());
    }

    public ItemStack leasedTool(ItemVariant signature) {
        ItemStack tool = leasedTools.get(signature);
        return tool == null ? ItemStack.EMPTY : tool.copy();
    }

    public int acceptTool(FactoryPlan recipe, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return 0;
        }
        ItemVariant signature = ItemVariant.toolSignature(stack);
        if (!recipe.getRecipeToolDamageCosts().containsKey(signature) || leasedTools.containsKey(signature)) {
            return 0;
        }
        if (!simulate) {
            ensureRecipe(recipe);
            leasedTools.put(signature, stack.copyWithCount(1));
        }
        return 1;
    }

    public boolean toolsReady(FactoryPlan recipe) {
        for (Map.Entry<ItemVariant, Long> entry : recipe.getRecipeToolDamageCosts().entrySet()) {
            ItemStack tool = leasedTools.get(entry.getKey());
            if (tool == null || tool.isEmpty() || !entry.getKey().matchesTool(tool)
                    || tool.getMaxDamage() - tool.getDamageValue() < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** Called immediately before output commit, after {@link #toolsReady(FactoryPlan)} succeeds. */
    public boolean consumeToolCosts(FactoryPlan recipe) {
        if (!toolsReady(recipe)) {
            return false;
        }
        for (Map.Entry<ItemVariant, Long> entry : recipe.getRecipeToolDamageCosts().entrySet()) {
            ItemStack tool = leasedTools.get(entry.getKey());
            tool.setDamageValue(Math.toIntExact(tool.getDamageValue() + entry.getValue()));
            if (tool.getDamageValue() >= tool.getMaxDamage()) {
                leasedTools.remove(entry.getKey());
            }
        }
        return true;
    }

    public List<ItemStack> releaseLeasedTools() {
        List<ItemStack> released = leasedTools.values().stream().filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy).toList();
        leasedTools.clear();
        return released;
    }

    public boolean hasLeasedTools() {
        return !leasedTools.isEmpty();
    }

    public void ensureRecipe(FactoryPlan recipe) {
        if (recipeFingerprint.isEmpty()) {
            recipeFingerprint = recipe.fingerprint();
        }
    }

    public boolean matchesRecipe(FactoryPlan recipe) {
        return recipeFingerprint.isEmpty() || recipeFingerprint.equals(recipe.fingerprint());
    }

    /** Validates persisted escrow against the immutable plan before it may tick or be exposed. */
    public boolean isValidFor(FactoryPlan recipe) {
        if (recipe == null) return false;
        if (!recipe.hasCompleteRecipe()) return isEmpty() && !hasLeasedTools();
        if (!matchesRecipe(recipe)) return false;
        if ((!isTransactionIdle() || hasLeasedTools()) && recipeFingerprint.isBlank()) return false;
        boolean streamsStatisticalOutputs = recipe.streamsStatisticalOutputsPerSecond();
        if (!withinLimits(committedItems, recipe.getRecipeInputs())
                || !withinLimits(committedFluids, recipe.getRecipeInputFluids())
                || !withinLimits(readyItems, recipe.getRecipeInputs())
                || !withinLimits(readyFluids, recipe.getRecipeInputFluids())
                || !withinLimits(remainingItemOutputs, recipe.getRecipeOutputs())
                || !withinLimits(remainingFluidOutputs, recipe.getRecipeOutputFluids())
                || !withinLimits(streamedItemOutputs, recipe.getRecipeOutputs())
                || !withinLimits(streamedFluidOutputs, recipe.getRecipeOutputFluids())
                || !streamsStatisticalOutputs && (!streamedItemOutputs.isEmpty() || !streamedFluidOutputs.isEmpty())
                || phase == Phase.RUNNING && streamsStatisticalOutputs
                && (!withinLimits(remainingItemOutputs, streamedItemOutputs)
                || !withinLimits(remainingFluidOutputs, streamedFluidOutputs))) {
            return false;
        }
        for (Map.Entry<ItemVariant, ItemStack> entry : leasedTools.entrySet()) {
            if (!recipe.getRecipeToolDamageCosts().containsKey(entry.getKey())
                    || entry.getValue().isEmpty() || !entry.getKey().matchesTool(entry.getValue())) {
                return false;
            }
        }
        return switch (phase) {
            case IDLE -> isTransactionIdle();
            case COLLECTING_INPUT -> progressTicks == 0
                    && (!committedItems.isEmpty() || !committedFluids.isEmpty())
                    && remainingItemOutputs.isEmpty() && remainingFluidOutputs.isEmpty()
                    && streamedItemOutputs.isEmpty() && streamedFluidOutputs.isEmpty()
                    && !inputsComplete(recipe);
            case INPUT_COMPLETE_WAITING_FOR_POWER -> progressTicks == 0
                    && remainingItemOutputs.isEmpty() && remainingFluidOutputs.isEmpty()
                    && streamedItemOutputs.isEmpty() && streamedFluidOutputs.isEmpty()
                    && inputsComplete(recipe);
            // A running zero-tick state is the safe representation for an in-flight batch retimed
            // to a one-tick recipe. It still requires the replacement stress contract before finish.
            case RUNNING -> progressTicks < recipe.getRecipeCycleTicks()
                    && (streamsStatisticalOutputs
                    || remainingItemOutputs.isEmpty() && remainingFluidOutputs.isEmpty())
                    && (streamsStatisticalOutputs
                    || streamedItemOutputs.isEmpty() && streamedFluidOutputs.isEmpty())
                    && inputsComplete(recipe) && toolsReady(recipe);
            case DELIVERING_OUTPUT -> progressTicks == 0 && committedItems.isEmpty()
                    && committedFluids.isEmpty()
                    && streamedItemOutputs.isEmpty() && streamedFluidOutputs.isEmpty()
                    && (!remainingItemOutputs.isEmpty() || !remainingFluidOutputs.isEmpty());
        };
    }

    /** Keeps leased tools while rebinding an idle runtime to the next batch's overclocked plan. */
    public boolean rebindIdlePlan(FactoryPlan recipe) {
        if (!isTransactionIdle()) return false;
        if (!recipe.getRecipeToolDamageCosts().keySet().containsAll(leasedTools.keySet())) return false;
        recipeFingerprint = recipe.fingerprint();
        return true;
    }

    /**
     * Rebinds an unchanged resource transaction to a new timing/stress contract. Running work keeps
     * its completed fraction. Streamed output rights are cumulative and are deliberately retained:
     * the new clock may briefly be ahead of or behind an old one-second settlement boundary, but
     * future settlement can only mint the still-unearned remainder of the same batch.
     */
    public boolean canRetime(FactoryPlan current, FactoryPlan replacement) {
        return current != null && replacement != null
                && isValidFor(current) && sameResourceContract(current, replacement);
    }

    public void retime(FactoryPlan current, FactoryPlan replacement) {
        if (!canRetime(current, replacement)) {
            throw new IllegalStateException("Runtime cannot be rebound to this overclock contract");
        }
        if (phase == Phase.RUNNING) {
            int oldCycleTicks = Math.max(1, current.getRecipeCycleTicks());
            int newCycleTicks = Math.max(1, replacement.getRecipeCycleTicks());
            if (newCycleTicks == 1) {
                // No positive in-progress tick exists for a one-tick recipe. Keep the active batch
                // and its streamed rights at zero progress; the next powered tick finishes it.
                progressTicks = 0;
            } else {
                progressTicks = rescaleProgress(progressTicks, oldCycleTicks, newCycleTicks);
            }
        }
        recipeFingerprint = replacement.fingerprint();
        updateInputPhase(replacement);
    }

    public long committedItem(ItemVariant variant) {
        return committedItems.getOrDefault(variant, 0L);
    }

    public long committedFluid(FluidVariant fluid) {
        return committedFluids.getOrDefault(fluid, 0L);
    }

    public long stagedItem(ItemVariant variant) {
        return Math.addExact(committedItem(variant), readyItems.getOrDefault(variant, 0L));
    }

    public long stagedFluid(FluidVariant fluid) {
        return Math.addExact(committedFluid(fluid), readyFluids.getOrDefault(fluid, 0L));
    }

    public long remainingItemOutput(ItemVariant variant) {
        return remainingItemOutputs.getOrDefault(variant, 0L);
    }

    public long remainingFluidOutput(FluidVariant fluid) {
        return remainingFluidOutputs.getOrDefault(fluid, 0L);
    }

    public long remainingItemInput(FactoryPlan recipe, ItemVariant variant) {
        return Math.max(0L, recipe.getRecipeInputs().getOrDefault(variant, 0L) - committedItem(variant));
    }

    public long remainingFluidInput(FactoryPlan recipe, FluidVariant fluid) {
        return Math.max(0L, recipe.getRecipeInputFluids().getOrDefault(fluid, 0L) - committedFluid(fluid));
    }

    /** Remaining ownership capacity across the active and one look-ahead batch. */
    public long remainingItemInputCapacity(FactoryPlan recipe, ItemVariant variant) {
        long perBatch = recipe.getRecipeInputs().getOrDefault(variant, 0L);
        long activeCapacity = phase == Phase.RUNNING || phase == Phase.DELIVERING_OUTPUT
                ? 0L : Math.max(0L, perBatch - committedItem(variant));
        long readyCapacity = Math.max(0L, perBatch - readyItems.getOrDefault(variant, 0L));
        return Math.addExact(activeCapacity, readyCapacity);
    }

    public long remainingFluidInputCapacity(FactoryPlan recipe, FluidVariant fluid) {
        long perBatch = recipe.getRecipeInputFluids().getOrDefault(fluid, 0L);
        long activeCapacity = phase == Phase.RUNNING || phase == Phase.DELIVERING_OUTPUT
                ? 0L : Math.max(0L, perBatch - committedFluid(fluid));
        long readyCapacity = Math.max(0L, perBatch - readyFluids.getOrDefault(fluid, 0L));
        return Math.addExact(activeCapacity, readyCapacity);
    }

    public int acceptItemInput(FactoryPlan recipe, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !matchesRecipe(recipe)) {
            return 0;
        }
        ItemVariant variant = ItemVariant.of(stack);
        int accepted = (int) Math.min(stack.getCount(), remainingItemInputCapacity(recipe, variant));
        if (accepted <= 0 || simulate) {
            return Math.max(0, accepted);
        }
        ensureRecipe(recipe);
        int remaining = accepted;
        if (phase != Phase.RUNNING && phase != Phase.DELIVERING_OUTPUT) {
            int active = (int) Math.min(remaining, remainingItemInput(recipe, variant));
            if (active > 0) committedItems.merge(variant, (long) active, FactoryRuntime::safeAdd);
            remaining -= active;
        }
        if (remaining > 0) readyItems.merge(variant, (long) remaining, FactoryRuntime::safeAdd);
        updateInputPhase(recipe);
        return accepted;
    }

    /**
     * Accepts all non-empty package stacks as one production-batch transaction.
     * Item identity includes stack components through {@link ItemVariant}.
     */
    public boolean acceptItemInputs(FactoryPlan recipe, List<ItemStack> stacks, boolean simulate) {
        if (stacks == null || !matchesRecipe(recipe)) {
            return false;
        }

        Map<ItemVariant, Long> requested = new HashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemVariant variant = ItemVariant.of(stack);
            long previous = requested.getOrDefault(variant, 0L);
            if (Long.MAX_VALUE - previous < stack.getCount()) {
                return false;
            }
            requested.put(variant, previous + stack.getCount());
        }
        if (requested.isEmpty()) {
            return false;
        }
        for (Map.Entry<ItemVariant, Long> entry : requested.entrySet()) {
            if (entry.getValue() > remainingItemInputCapacity(recipe, entry.getKey())) {
                return false;
            }
        }
        if (simulate) {
            return true;
        }

        ensureRecipe(recipe);
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            int accepted = acceptItemInput(recipe, stack, false);
            if (accepted != stack.getCount()) {
                throw new IllegalStateException("Validated look-ahead package input could not be committed");
            }
        }
        return true;
    }

    public int acceptFluidInput(FactoryPlan recipe, FluidStack stack, boolean simulate) {
        if (stack.isEmpty() || !matchesRecipe(recipe)) {
            return 0;
        }
        FluidVariant variant = FluidVariant.of(stack);
        int accepted = (int) Math.min(stack.getAmount(),
                Math.min(remainingFluidInputCapacity(recipe, variant), Integer.MAX_VALUE));
        if (accepted <= 0 || simulate) {
            return Math.max(0, accepted);
        }
        ensureRecipe(recipe);
        int remaining = accepted;
        if (phase != Phase.RUNNING && phase != Phase.DELIVERING_OUTPUT) {
            int active = (int) Math.min(remaining, Math.min(remainingFluidInput(recipe, variant), Integer.MAX_VALUE));
            if (active > 0) committedFluids.merge(variant, (long) active, FactoryRuntime::safeAdd);
            remaining -= active;
        }
        if (remaining > 0) readyFluids.merge(variant, (long) remaining, FactoryRuntime::safeAdd);
        updateInputPhase(recipe);
        return accepted;
    }

    public boolean inputsComplete(FactoryPlan recipe) {
        if (!recipe.hasCompleteRecipe()) {
            return false;
        }
        for (Map.Entry<ItemVariant, Long> entry : recipe.getRecipeInputs().entrySet()) {
            if (committedItem(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        for (Map.Entry<FluidVariant, Long> entry : recipe.getRecipeInputFluids().entrySet()) {
            if (committedFluid(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void updateInputPhase(FactoryPlan recipe) {
        if (phase != Phase.RUNNING && phase != Phase.DELIVERING_OUTPUT) {
            phase = inputsComplete(recipe) ? Phase.INPUT_COMPLETE_WAITING_FOR_POWER
                    : (committedItems.isEmpty() && committedFluids.isEmpty() ? Phase.IDLE : Phase.COLLECTING_INPUT);
        }
    }

    /**
     * Advances the authoritative transaction by one powered tick. A material-free plan starts a
     * real RUNNING transaction before any time is accumulated, so overclock changes and reloads
     * cannot treat its progress as an empty batch.
     */
    public boolean advance(FactoryPlan recipe, boolean stressSatisfied) {
        if (!recipe.hasCompleteRecipe() || phase == Phase.DELIVERING_OUTPUT || !matchesRecipe(recipe)) return false;
        ensureRecipe(recipe);
        updateInputPhase(recipe);
        if (!inputsComplete(recipe) || !toolsReady(recipe)) return false;
        if (phase == Phase.INPUT_COMPLETE_WAITING_FOR_POWER) {
            if (!stressSatisfied) return false;
            phase = Phase.RUNNING;
            progressTicks = 0;
            batchId = batchId == Long.MAX_VALUE ? 1L : batchId + 1L;
        }
        if (phase != Phase.RUNNING || !stressSatisfied) return false;
        progressTicks = Math.addExact(progressTicks, 1);
        if (recipe.streamsStatisticalOutputsPerSecond()
                && (progressTicks % STREAM_OUTPUT_INTERVAL_TICKS == 0
                || progressTicks >= recipe.getRecipeCycleTicks())) {
            accrueStreamedOutputs(recipe);
        }
        if (progressTicks < recipe.getRecipeCycleTicks()) return true;
        if (!consumeToolCosts(recipe)) {
            phase = Phase.INPUT_COMPLETE_WAITING_FOR_POWER;
            progressTicks = 0;
            return true;
        }
        if (recipe.streamsStatisticalOutputsPerSecond()) {
            finishStreamedCycle(recipe);
        } else {
            commitOutputs(recipe);
        }
        return true;
    }

    public int progressTicks() {
        return progressTicks;
    }

    public long batchId() {
        return batchId;
    }

    /** Runtime ownership snapshot used only by structured diagnostics. */
    public String debugSummary() {
        String fingerprint = recipeFingerprint.length() <= 12
                ? recipeFingerprint : recipeFingerprint.substring(0, 12);
        return "phase=" + phase
                + ", batchId=" + batchId
                + ", progress=" + progressTicks
                + ", fingerprint=" + fingerprint
                + ", committedItems=" + committedItems
                + ", committedFluids=" + committedFluids
                + ", readyItems=" + readyItems
                + ", readyFluids=" + readyFluids
                + ", outputsItems=" + remainingItemOutputs
                + ", outputsFluids=" + remainingFluidOutputs
                + ", streamedItems=" + streamedItemOutputs
                + ", streamedFluids=" + streamedFluidOutputs
                + ", leasedTools=" + leasedTools.keySet();
    }

    /** Adds only newly earned whole units; fractional entitlement remains in the persisted totals. */
    private void accrueStreamedOutputs(FactoryPlan recipe) {
        int cycleTicks = recipe.getRecipeCycleTicks();
        recipe.getRecipeOutputs().forEach((variant, total) -> {
            long target = proportionalFloor(total, progressTicks, cycleTicks);
            long alreadyStreamed = streamedItemOutputs.getOrDefault(variant, 0L);
            long delta = target - alreadyStreamed;
            if (delta > 0L) {
                remainingItemOutputs.merge(variant, delta, FactoryRuntime::safeAdd);
                streamedItemOutputs.put(variant, target);
            }
        });
        recipe.getRecipeOutputFluids().forEach((fluid, total) -> {
            long target = proportionalFloor(total, progressTicks, cycleTicks);
            long alreadyStreamed = streamedFluidOutputs.getOrDefault(fluid, 0L);
            long delta = target - alreadyStreamed;
            if (delta > 0L) {
                remainingFluidOutputs.merge(fluid, delta, FactoryRuntime::safeAdd);
                streamedFluidOutputs.put(fluid, target);
            }
        });
    }

    private void finishStreamedCycle(FactoryPlan recipe) {
        committedItems.clear();
        committedFluids.clear();
        streamedItemOutputs.clear();
        streamedFluidOutputs.clear();
        progressTicks = 0;
        phase = outputsComplete() ? Phase.IDLE : Phase.DELIVERING_OUTPUT;
        if (phase == Phase.IDLE) promoteReadyInputs(recipe);
    }

    private void commitOutputs(FactoryPlan recipe) {
        if (!inputsComplete(recipe) || phase != Phase.RUNNING) return;
        committedItems.clear();
        committedFluids.clear();
        remainingItemOutputs.clear();
        remainingFluidOutputs.clear();
        recipe.getRecipeOutputs().forEach((variant, count) -> {
            if (count > 0) {
                remainingItemOutputs.put(variant, count);
            }
        });
        recipe.getRecipeOutputFluids().forEach((fluid, count) -> {
            if (count > 0) {
                remainingFluidOutputs.put(fluid, count);
            }
        });
        phase = outputsComplete() ? Phase.IDLE : Phase.DELIVERING_OUTPUT;
        progressTicks = 0;
        if (phase == Phase.IDLE) promoteReadyInputs(recipe);
    }

    public ItemStack extractItemOutput(FactoryPlan recipe, ItemVariant variant, int amount, boolean simulate) {
        if ((phase != Phase.RUNNING && phase != Phase.DELIVERING_OUTPUT) || amount <= 0) {
            return ItemStack.EMPTY;
        }
        long remaining = remainingItemOutput(variant);
        if (remaining <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = variant.createStack((int) Math.min(Math.min(remaining, amount), variant.prototype().getMaxStackSize()));
        if (!simulate) {
            reduceItemOutput(recipe, variant, result.getCount());
        }
        return result;
    }

    public FluidStack drainFluidOutput(FactoryPlan recipe, FluidVariant fluid, int amount, boolean simulate) {
        if ((phase != Phase.RUNNING && phase != Phase.DELIVERING_OUTPUT) || amount <= 0) {
            return FluidStack.EMPTY;
        }
        long remaining = remainingFluidOutput(fluid);
        if (remaining <= 0) {
            return FluidStack.EMPTY;
        }
        FluidStack result = fluid.createStack((int) Math.min(Math.min(remaining, amount), Integer.MAX_VALUE));
        if (!simulate) {
            reduceFluidOutput(recipe, fluid, result.getAmount());
        }
        return result;
    }

    /**
     * Moves currently deliverable output into the bounded transit buffer as one ownership
     * transfer. A completed batch is then cleared; a running streamed tree batch retains its
     * progress and cumulative settlement counters.
     */
    public boolean moveOutputsToTransit(FactoryPlan recipe, FactoryTransit.PortResourceChannel channel) {
        boolean completedBatch = phase == Phase.DELIVERING_OUTPUT;
        boolean runningStream = phase == Phase.RUNNING && !outputsComplete();
        if (channel == null || (!completedBatch && !runningStream)) return false;
        List<ItemStack> items = materializeOutputItems();
        if (!items.isEmpty() && !channel.canAcceptOutputItemBatch(items)) return false;
        if (!channel.canAcceptOutputFluidBatch(remainingFluidOutputs)) return false;
        for (Map.Entry<FluidVariant, Long> entry : remainingFluidOutputs.entrySet()) {
            for (long remaining = entry.getValue(); remaining > 0L;) {
                int amount = (int) Math.min(remaining, Integer.MAX_VALUE);
                int accepted = channel.fillOutputFluids(entry.getKey().createStack(amount),
                        IFluidHandler.FluidAction.SIMULATE);
                if (accepted != amount) return false;
                remaining -= amount;
            }
        }
        if (!items.isEmpty() && !channel.insertOutputItemBatch(items)) {
            throw new IllegalStateException("Runtime item outputs could not be escrowed atomically");
        }
        for (Map.Entry<FluidVariant, Long> entry : remainingFluidOutputs.entrySet()) {
            for (long remaining = entry.getValue(); remaining > 0L;) {
                int amount = (int) Math.min(remaining, Integer.MAX_VALUE);
                int accepted = channel.fillOutputFluids(entry.getKey().createStack(amount),
                        IFluidHandler.FluidAction.EXECUTE);
                if (accepted != amount) {
                    throw new IllegalStateException("Runtime fluid outputs could not be escrowed atomically");
                }
                remaining -= amount;
            }
        }
        remainingItemOutputs.clear();
        remainingFluidOutputs.clear();
        if (completedBatch) clearCompletedBatch(recipe);
        return true;
    }

    private List<ItemStack> materializeOutputItems() {
        List<ItemStack> result = new ArrayList<>();
        remainingItemOutputs.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendSplitStacks(result, entry.getKey(), entry.getValue()));
        return result;
    }

    public List<ItemStack> materializeItems() {
        List<ItemStack> result = new ArrayList<>();
        committedItems.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendSplitStacks(result, entry.getKey(), entry.getValue()));
        readyItems.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendSplitStacks(result, entry.getKey(), entry.getValue()));
        remainingItemOutputs.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendSplitStacks(result, entry.getKey(), entry.getValue()));
        return result;
    }

    public long destroyedFluidAmount() {
        long total = 0L;
        for (long amount : committedFluids.values()) total = safeAdd(total, amount);
        for (long amount : readyFluids.values()) total = safeAdd(total, amount);
        for (long amount : remainingFluidOutputs.values()) total = safeAdd(total, amount);
        return total;
    }

    public void clear() {
        leasedTools.clear();
        batchId = 0L;
        clearBatch();
    }

    public CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("RuntimeFormatVersion", RUNTIME_FORMAT_VERSION);
        tag.putString("Phase", phase.name());
        tag.putString("RecipeFingerprint", recipeFingerprint);
        tag.putLong("BatchId", batchId);
        tag.putInt("ProgressTicks", progressTicks);
        writeVariantLongMap(tag, "CommittedItems", committedItems, registries);
        writeFluidLongMap(tag, "CommittedFluids", committedFluids, registries);
        writeVariantLongMap(tag, "ReadyItems", readyItems, registries);
        writeFluidLongMap(tag, "ReadyFluids", readyFluids, registries);
        writeVariantLongMap(tag, "RemainingItemOutputs", remainingItemOutputs, registries);
        writeFluidLongMap(tag, "RemainingFluidOutputs", remainingFluidOutputs, registries);
        writeVariantLongMap(tag, "StreamedItemOutputs", streamedItemOutputs, registries);
        writeFluidLongMap(tag, "StreamedFluidOutputs", streamedFluidOutputs, registries);
        writeLeasedTools(tag, registries);
        return tag;
    }

    public void read(CompoundTag tag, HolderLookup.Provider registries) {
        clear();
        if (tag.getInt("RuntimeFormatVersion") != RUNTIME_FORMAT_VERSION) {
            return;
        }
        try {
            phase = Phase.valueOf(tag.getString("Phase"));
        } catch (IllegalArgumentException ignored) {
            return;
        }
        recipeFingerprint = tag.getString("RecipeFingerprint");
        batchId = Math.max(0L, tag.getLong("BatchId"));
        progressTicks = Math.max(0, tag.getInt("ProgressTicks"));
        readVariantLongMap(tag, "CommittedItems", committedItems, registries);
        readFluidLongMap(tag, "CommittedFluids", committedFluids, registries);
        readVariantLongMap(tag, "ReadyItems", readyItems, registries);
        readFluidLongMap(tag, "ReadyFluids", readyFluids, registries);
        readVariantLongMap(tag, "RemainingItemOutputs", remainingItemOutputs, registries);
        readFluidLongMap(tag, "RemainingFluidOutputs", remainingFluidOutputs, registries);
        readVariantLongMap(tag, "StreamedItemOutputs", streamedItemOutputs, registries);
        readFluidLongMap(tag, "StreamedFluidOutputs", streamedFluidOutputs, registries);
        readLeasedTools(tag, registries);
        if ((phase == Phase.IDLE && (!committedItems.isEmpty() || !committedFluids.isEmpty()
                || !readyItems.isEmpty() || !readyFluids.isEmpty()
                || !remainingItemOutputs.isEmpty() || !remainingFluidOutputs.isEmpty()
                || !streamedItemOutputs.isEmpty() || !streamedFluidOutputs.isEmpty() || progressTicks != 0))
                || (phase != Phase.RUNNING && progressTicks != 0)) {
            clear();
        }
    }

    public List<ItemVariant> sortedInputItems(FactoryPlan recipe) {
        return sortedVariants(recipe.getRecipeInputs());
    }

    public List<ItemVariant> sortedOutputItems(FactoryPlan recipe) {
        return sortedVariants(recipe.getRecipeOutputs());
    }

    public List<FluidVariant> sortedInputFluids(FactoryPlan recipe) {
        return sortedFluids(recipe.getRecipeInputFluids());
    }

    public List<FluidVariant> sortedOutputFluids(FactoryPlan recipe) {
        return sortedFluids(recipe.getRecipeOutputFluids());
    }

    private boolean outputsComplete() {
        return remainingItemOutputs.isEmpty() && remainingFluidOutputs.isEmpty();
    }

    private void reduceItemOutput(FactoryPlan recipe, ItemVariant variant, long amount) {
        long next = Math.max(0L, remainingItemOutput(variant) - amount);
        if (next == 0L) remainingItemOutputs.remove(variant); else remainingItemOutputs.put(variant, next);
        finishOutputsIfEmpty(recipe);
    }

    private void reduceFluidOutput(FactoryPlan recipe, FluidVariant fluid, long amount) {
        long next = Math.max(0L, remainingFluidOutput(fluid) - amount);
        if (next == 0L) remainingFluidOutputs.remove(fluid); else remainingFluidOutputs.put(fluid, next);
        finishOutputsIfEmpty(recipe);
    }

    private void finishOutputsIfEmpty(FactoryPlan recipe) {
        if (phase == Phase.DELIVERING_OUTPUT && outputsComplete()) clearCompletedBatch(recipe);
    }

    private void clearCompletedBatch(FactoryPlan recipe) {
        progressTicks = 0;
        committedItems.clear();
        committedFluids.clear();
        remainingItemOutputs.clear();
        remainingFluidOutputs.clear();
        streamedItemOutputs.clear();
        streamedFluidOutputs.clear();
        promoteReadyInputs(recipe);
    }

    /** Moves the one-batch look-ahead reservation into active ownership without copying it. */
    private void promoteReadyInputs(FactoryPlan recipe) {
        committedItems.clear();
        committedItems.putAll(readyItems);
        readyItems.clear();
        committedFluids.clear();
        committedFluids.putAll(readyFluids);
        readyFluids.clear();
        phase = inputsComplete(recipe) ? Phase.INPUT_COMPLETE_WAITING_FOR_POWER
                : committedItems.isEmpty() && committedFluids.isEmpty()
                ? Phase.IDLE : Phase.COLLECTING_INPUT;
        if (phase == Phase.IDLE && leasedTools.isEmpty()) recipeFingerprint = "";
    }

    private void clearBatch() {
        phase = Phase.IDLE;
        progressTicks = 0;
        if (leasedTools.isEmpty()) {
            recipeFingerprint = "";
        }
        committedItems.clear();
        committedFluids.clear();
        readyItems.clear();
        readyFluids.clear();
        remainingItemOutputs.clear();
        remainingFluidOutputs.clear();
        streamedItemOutputs.clear();
        streamedFluidOutputs.clear();
    }

    private static long proportionalFloor(long total, int elapsedTicks, int cycleTicks) {
        if (total <= 0L || elapsedTicks <= 0 || cycleTicks <= 0) return 0L;
        int boundedElapsed = Math.min(elapsedTicks, cycleTicks);
        long quotient = total / cycleTicks;
        long remainder = total % cycleTicks;
        return Math.addExact(Math.multiplyExact(quotient, boundedElapsed),
                Math.multiplyExact(remainder, boundedElapsed) / cycleTicks);
    }

    private static int rescaleProgress(int progress, int oldCycleTicks, int newCycleTicks) {
        long numerator = Math.multiplyExact((long) progress, newCycleTicks);
        long rounded = Math.addExact(numerator, oldCycleTicks / 2L) / oldCycleTicks;
        return (int) Math.clamp(rounded, 1L, newCycleTicks - 1L);
    }

    private static void appendSplitStacks(List<ItemStack> output, ItemVariant variant, long count) {
        int max = Math.max(1, variant.prototype().getMaxStackSize());
        for (long remaining = count; remaining > 0; remaining -= Math.min(max, remaining)) {
            output.add(variant.createStack((int) Math.min(max, remaining)));
        }
    }

    private static long safeAdd(long left, long right) {
        return Math.addExact(left, right);
    }

    private static <K> boolean withinLimits(Map<K, Long> actual, Map<K, Long> limits) {
        for (Map.Entry<K, Long> entry : actual.entrySet()) {
            long limit = limits.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() <= 0L || entry.getValue() > limit) return false;
        }
        return true;
    }

    private static boolean sameResourceContract(FactoryPlan first, FactoryPlan second) {
        return first.hasCompleteRecipe() && second.hasCompleteRecipe()
                && first.getRecipeInputs().equals(second.getRecipeInputs())
                && first.getRecipeInputFluids().equals(second.getRecipeInputFluids())
                && first.getRecipeOutputs().equals(second.getRecipeOutputs())
                && first.getRecipeOutputFluids().equals(second.getRecipeOutputFluids())
                && first.getRecipeToolDamageCosts().equals(second.getRecipeToolDamageCosts())
                && first.getStartupCapitalItems().equals(second.getStartupCapitalItems())
                && first.getSourceKind() == second.getSourceKind()
                && first.getSourceProofs().equals(second.getSourceProofs());
    }


    private static void writeVariantLongMap(CompoundTag root, String key, Map<ItemVariant, Long> map,
                                            HolderLookup.Provider registries) {
        ListTag values = new ListTag();
        map.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue() > 0) {
                CompoundTag value = new CompoundTag();
                value.put("Stack", entry.getKey().write(registries));
                value.putLong("Value", entry.getValue());
                values.add(value);
            }
        });
        root.put(key, values);
    }

    private void writeLeasedTools(CompoundTag root, HolderLookup.Provider registries) {
        ListTag values = new ListTag();
        leasedTools.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue().isEmpty()) return;
            CompoundTag value = new CompoundTag();
            value.put("Signature", entry.getKey().write(registries));
            Tag tool = entry.getValue().saveOptional(registries);
            if (tool instanceof CompoundTag toolTag) {
                value.put("Tool", toolTag);
                values.add(value);
            }
        });
        root.put("LeasedTools", values);
    }

    private void readLeasedTools(CompoundTag root, HolderLookup.Provider registries) {
        for (Tag raw : root.getList("LeasedTools", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            ItemVariant signature = ItemVariant.read(registries, entry.getCompound("Signature"));
            ItemStack tool = ItemStack.parseOptional(registries, entry.getCompound("Tool"));
            if (signature != null && !tool.isEmpty() && signature.matchesTool(tool)
                    && !leasedTools.containsKey(signature)) {
                leasedTools.put(signature, tool.copyWithCount(1));
            }
        }
    }

    private static void readVariantLongMap(CompoundTag root, String key, Map<ItemVariant, Long> map,
                                           HolderLookup.Provider registries) {
        for (Tag raw : root.getList(key, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            ItemVariant variant = ItemVariant.read(registries, entry.getCompound("Stack"));
            long value = entry.getLong("Value");
            if (variant != null && value > 0) map.merge(variant, value, FactoryRuntime::safeAdd);
        }
    }

    private static void writeFluidLongMap(CompoundTag root, String key, Map<FluidVariant, Long> map,
                                          HolderLookup.Provider registries) {
        ListTag values = new ListTag();
        map.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue() <= 0) return;
            CompoundTag value = new CompoundTag();
            value.put("Stack", entry.getKey().write(registries));
            value.putLong("Value", entry.getValue());
            values.add(value);
        });
        root.put(key, values);
    }

    private static void readFluidLongMap(CompoundTag root, String key, Map<FluidVariant, Long> map,
                                         HolderLookup.Provider registries) {
        for (Tag raw : root.getList(key, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            FluidVariant variant = FluidVariant.read(registries, entry.getCompound("Stack"));
            long value = entry.getLong("Value");
            if (variant != null && value > 0) map.put(variant, value);
        }
    }

    private static List<ItemVariant> sortedVariants(Map<ItemVariant, Long> map) {
        return map.keySet().stream().sorted().toList();
    }

    private static List<FluidVariant> sortedFluids(Map<FluidVariant, Long> map) {
        return map.keySet().stream().sorted().toList();
    }
}

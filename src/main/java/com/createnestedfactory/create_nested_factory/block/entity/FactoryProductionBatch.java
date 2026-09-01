package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One inaccessible, recipe-bound production batch with full ItemStack identity. */
public final class FactoryProductionBatch {
    public enum Phase {
        IDLE,
        COLLECTING_INPUT,
        INPUT_COMPLETE_WAITING_FOR_POWER,
        DELIVERING_OUTPUT
    }

    private static final int ITEM_IDENTITY_VERSION = 2;

    private Phase phase = Phase.IDLE;
    private String recipeFingerprint = "";
    private final Map<ItemVariant, Long> committedItems = new HashMap<>();
    private final Map<Fluid, Long> committedFluids = new HashMap<>();
    private final Map<ItemVariant, Long> remainingItemOutputs = new HashMap<>();
    private final Map<Fluid, Long> remainingFluidOutputs = new HashMap<>();

    public Phase phase() {
        return phase;
    }

    public boolean isEmpty() {
        return phase == Phase.IDLE && committedItems.isEmpty() && committedFluids.isEmpty()
                && remainingItemOutputs.isEmpty() && remainingFluidOutputs.isEmpty();
    }

    public boolean isDeliveringOutputs() {
        return phase == Phase.DELIVERING_OUTPUT;
    }

    public void ensureRecipe(BlackboxData recipe) {
        if (isEmpty() || recipeFingerprint.isEmpty()) {
            recipeFingerprint = fingerprint(recipe);
        }
    }

    public boolean matchesRecipe(BlackboxData recipe) {
        return isEmpty() || recipeFingerprint.isEmpty() || recipeFingerprint.equals(fingerprint(recipe));
    }

    public long committedItem(ItemVariant variant) {
        return committedItems.getOrDefault(variant, 0L);
    }

    public long committedFluid(Fluid fluid) {
        return committedFluids.getOrDefault(fluid, 0L);
    }

    public long remainingItemOutput(ItemVariant variant) {
        return remainingItemOutputs.getOrDefault(variant, 0L);
    }

    public long remainingFluidOutput(Fluid fluid) {
        return remainingFluidOutputs.getOrDefault(fluid, 0L);
    }

    public long remainingItemInput(BlackboxData recipe, ItemVariant variant) {
        return Math.max(0L, recipe.getRecipeInputs().getOrDefault(variant, 0L) - committedItem(variant));
    }

    public long remainingFluidInput(BlackboxData recipe, Fluid fluid) {
        return Math.max(0L, recipe.getRecipeInputFluids().getOrDefault(fluid, 0L) - committedFluid(fluid));
    }

    public int acceptItemInput(BlackboxData recipe, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || phase == Phase.DELIVERING_OUTPUT || !matchesRecipe(recipe)) {
            return 0;
        }
        ItemVariant variant = ItemVariant.of(stack);
        int accepted = (int) Math.min(stack.getCount(), remainingItemInput(recipe, variant));
        if (accepted <= 0 || simulate) {
            return Math.max(0, accepted);
        }
        ensureRecipe(recipe);
        phase = Phase.COLLECTING_INPUT;
        committedItems.merge(variant, (long) accepted, FactoryProductionBatch::safeAdd);
        updateInputPhase(recipe);
        return accepted;
    }

    /**
     * Accepts all non-empty package stacks as one production-batch transaction.
     * Item identity includes stack components through {@link ItemVariant}.
     */
    public boolean acceptItemInputs(BlackboxData recipe, List<ItemStack> stacks, boolean simulate) {
        if (stacks == null || phase == Phase.DELIVERING_OUTPUT || !matchesRecipe(recipe)) {
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
            if (entry.getValue() > remainingItemInput(recipe, entry.getKey())) {
                return false;
            }
        }
        if (simulate) {
            return true;
        }

        ensureRecipe(recipe);
        phase = Phase.COLLECTING_INPUT;
        requested.forEach((variant, amount) -> committedItems.merge(variant, amount, FactoryProductionBatch::safeAdd));
        updateInputPhase(recipe);
        return true;
    }

    public int acceptFluidInput(BlackboxData recipe, FluidStack stack, boolean simulate) {
        if (stack.isEmpty() || phase == Phase.DELIVERING_OUTPUT || !matchesRecipe(recipe)) {
            return 0;
        }
        int accepted = (int) Math.min(stack.getAmount(), Math.min(remainingFluidInput(recipe, stack.getFluid()), Integer.MAX_VALUE));
        if (accepted <= 0 || simulate) {
            return Math.max(0, accepted);
        }
        ensureRecipe(recipe);
        phase = Phase.COLLECTING_INPUT;
        committedFluids.merge(stack.getFluid(), (long) accepted, FactoryProductionBatch::safeAdd);
        updateInputPhase(recipe);
        return accepted;
    }

    public boolean inputsComplete(BlackboxData recipe) {
        if (!recipe.hasCompleteRecipe()) {
            return false;
        }
        for (Map.Entry<ItemVariant, Long> entry : recipe.getRecipeInputs().entrySet()) {
            if (committedItem(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        for (Map.Entry<Fluid, Long> entry : recipe.getRecipeInputFluids().entrySet()) {
            if (committedFluid(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void updateInputPhase(BlackboxData recipe) {
        if (phase != Phase.DELIVERING_OUTPUT) {
            phase = inputsComplete(recipe) ? Phase.INPUT_COMPLETE_WAITING_FOR_POWER
                    : (committedItems.isEmpty() && committedFluids.isEmpty() ? Phase.IDLE : Phase.COLLECTING_INPUT);
        }
    }

    public void commitOutputs(BlackboxData recipe) {
        if (!inputsComplete(recipe) || phase == Phase.DELIVERING_OUTPUT) {
            return;
        }
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
        if (phase == Phase.IDLE) {
            recipeFingerprint = "";
        }
    }

    public ItemStack extractItemOutput(ItemVariant variant, int amount, boolean simulate) {
        if (phase != Phase.DELIVERING_OUTPUT || amount <= 0) {
            return ItemStack.EMPTY;
        }
        long remaining = remainingItemOutput(variant);
        if (remaining <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = variant.createStack((int) Math.min(Math.min(remaining, amount), variant.prototype().getMaxStackSize()));
        if (!simulate) {
            reduceItemOutput(variant, result.getCount());
        }
        return result;
    }

    public FluidStack drainFluidOutput(Fluid fluid, int amount, boolean simulate) {
        if (phase != Phase.DELIVERING_OUTPUT || amount <= 0) {
            return FluidStack.EMPTY;
        }
        long remaining = remainingFluidOutput(fluid);
        if (remaining <= 0) {
            return FluidStack.EMPTY;
        }
        FluidStack result = new FluidStack(fluid, (int) Math.min(Math.min(remaining, amount), Integer.MAX_VALUE));
        if (!simulate) {
            reduceFluidOutput(fluid, result.getAmount());
        }
        return result;
    }

    public List<ItemStack> materializeItems() {
        List<ItemStack> result = new ArrayList<>();
        Map<ItemVariant, Long> source = phase == Phase.DELIVERING_OUTPUT ? remainingItemOutputs : committedItems;
        source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendSplitStacks(result, entry.getKey(), entry.getValue()));
        return result;
    }

    public long destroyedFluidAmount() {
        long total = 0L;
        for (long amount : committedFluids.values()) total = safeAdd(total, amount);
        for (long amount : remainingFluidOutputs.values()) total = safeAdd(total, amount);
        return total;
    }

    public void clear() {
        phase = Phase.IDLE;
        recipeFingerprint = "";
        committedItems.clear();
        committedFluids.clear();
        remainingItemOutputs.clear();
        remainingFluidOutputs.clear();
    }

    public CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("ItemIdentityVersion", ITEM_IDENTITY_VERSION);
        tag.putString("Phase", phase.name());
        tag.putString("RecipeFingerprint", recipeFingerprint);
        writeVariantLongMap(tag, "CommittedItems", committedItems, registries);
        writeFluidLongMap(tag, "CommittedFluids", committedFluids);
        writeVariantLongMap(tag, "RemainingItemOutputs", remainingItemOutputs, registries);
        writeFluidLongMap(tag, "RemainingFluidOutputs", remainingFluidOutputs);
        return tag;
    }

    public void read(CompoundTag tag, HolderLookup.Provider registries) {
        clear();
        if (tag.getInt("ItemIdentityVersion") != ITEM_IDENTITY_VERSION) {
            return;
        }
        try {
            phase = Phase.valueOf(tag.getString("Phase"));
        } catch (IllegalArgumentException ignored) {
            return;
        }
        recipeFingerprint = tag.getString("RecipeFingerprint");
        readVariantLongMap(tag, "CommittedItems", committedItems, registries);
        readFluidLongMap(tag, "CommittedFluids", committedFluids);
        readVariantLongMap(tag, "RemainingItemOutputs", remainingItemOutputs, registries);
        readFluidLongMap(tag, "RemainingFluidOutputs", remainingFluidOutputs);
        if (phase == Phase.IDLE && (!committedItems.isEmpty() || !committedFluids.isEmpty()
                || !remainingItemOutputs.isEmpty() || !remainingFluidOutputs.isEmpty())) {
            clear();
        }
    }

    public static String fingerprint(BlackboxData recipe) {
        StringBuilder builder = new StringBuilder("cycle=")
                .append(Math.max(BlackboxData.RATE_WINDOW, recipe.getRecipeCycleTicks()));
        appendVariants(builder, "|ii:", recipe.getRecipeInputs());
        appendVariants(builder, "|io:", recipe.getRecipeOutputs());
        appendFluids(builder, "|fi:", recipe.getRecipeInputFluids());
        appendFluids(builder, "|fo:", recipe.getRecipeOutputFluids());
        return builder.toString();
    }

    public List<ItemVariant> sortedInputItems(BlackboxData recipe) {
        return sortedVariants(recipe.getRecipeInputs());
    }

    public List<ItemVariant> sortedOutputItems(BlackboxData recipe) {
        return sortedVariants(recipe.getRecipeOutputs());
    }

    public List<Fluid> sortedInputFluids(BlackboxData recipe) {
        return sortedFluids(recipe.getRecipeInputFluids());
    }

    public List<Fluid> sortedOutputFluids(BlackboxData recipe) {
        return sortedFluids(recipe.getRecipeOutputFluids());
    }

    private boolean outputsComplete() {
        return remainingItemOutputs.isEmpty() && remainingFluidOutputs.isEmpty();
    }

    private void reduceItemOutput(ItemVariant variant, long amount) {
        long next = Math.max(0L, remainingItemOutput(variant) - amount);
        if (next == 0L) remainingItemOutputs.remove(variant); else remainingItemOutputs.put(variant, next);
        finishOutputsIfEmpty();
    }

    private void reduceFluidOutput(Fluid fluid, long amount) {
        long next = Math.max(0L, remainingFluidOutput(fluid) - amount);
        if (next == 0L) remainingFluidOutputs.remove(fluid); else remainingFluidOutputs.put(fluid, next);
        finishOutputsIfEmpty();
    }

    private void finishOutputsIfEmpty() {
        if (outputsComplete()) clear();
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

    private static void readVariantLongMap(CompoundTag root, String key, Map<ItemVariant, Long> map,
                                           HolderLookup.Provider registries) {
        for (Tag raw : root.getList(key, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            ItemVariant variant = ItemVariant.read(registries, entry.getCompound("Stack"));
            long value = entry.getLong("Value");
            if (variant != null && value > 0) map.merge(variant, value, FactoryProductionBatch::safeAdd);
        }
    }

    private static void writeFluidLongMap(CompoundTag root, String key, Map<Fluid, Long> map) {
        CompoundTag values = new CompoundTag();
        map.forEach((fluid, value) -> {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
            if (id != null && value > 0) values.putLong(id.toString(), value);
        });
        root.put(key, values);
    }

    private static void readFluidLongMap(CompoundTag root, String key, Map<Fluid, Long> map) {
        CompoundTag values = root.getCompound(key);
        for (String idText : values.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(idText);
            long value = values.getLong(idText);
            if (id != null && value > 0 && BuiltInRegistries.FLUID.containsKey(id)) {
                map.put(BuiltInRegistries.FLUID.get(id), value);
            }
        }
    }

    private static void appendVariants(StringBuilder builder, String prefix, Map<ItemVariant, Long> map) {
        map.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append(prefix).append(entry.getKey()).append('=').append(entry.getValue()));
    }

    private static void appendFluids(StringBuilder builder, String prefix, Map<Fluid, Long> map) {
        map.entrySet().stream().sorted(Comparator.comparing(entry -> BuiltInRegistries.FLUID.getKey(entry.getKey()).toString()))
                .forEach(entry -> builder.append(prefix).append(BuiltInRegistries.FLUID.getKey(entry.getKey()))
                        .append('=').append(entry.getValue()));
    }

    private static List<ItemVariant> sortedVariants(Map<ItemVariant, Long> map) {
        return map.keySet().stream().sorted().toList();
    }

    private static List<Fluid> sortedFluids(Map<Fluid, Long> map) {
        return map.keySet().stream()
                .sorted(Comparator.comparing(fluid -> BuiltInRegistries.FLUID.getKey(fluid).toString()))
                .toList();
    }
}

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

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Throughput sampling and component-aware black-box recipe data. */
public final class BlackboxData {
    public static final int RATE_WINDOW = 20;
    private static final int ITEM_IDENTITY_VERSION = 2;

    private final Map<ItemVariant, Float> inputRates = new HashMap<>();
    private final Map<ItemVariant, Float> outputRates = new HashMap<>();
    private final Map<Fluid, Float> inputFluidRates = new HashMap<>();
    private final Map<Fluid, Float> outputFluidRates = new HashMap<>();

    /** Whole learning-period totals avoid phase-dependent one-second windows changing learned recipes. */
    private final Map<ItemVariant, Long> learningInputTotals = new HashMap<>();
    private final Map<ItemVariant, Long> learningOutputTotals = new HashMap<>();
    private final Map<Fluid, Long> learningInputFluidTotals = new HashMap<>();
    private final Map<Fluid, Long> learningOutputFluidTotals = new HashMap<>();
    private int learningSampleTicks;

    private final Map<ItemVariant, Long> inputCount = new HashMap<>();
    private final Map<ItemVariant, Long> outputCount = new HashMap<>();
    private final Map<ItemVariant, Long> learningOutputCount = new HashMap<>();
    private final Map<Fluid, Long> inputFluidCount = new HashMap<>();
    private final Map<Fluid, Long> outputFluidCount = new HashMap<>();
    private int windowTicks;
    private boolean recording;
    private final Map<ItemVariant, Long> ignoredOutputBudget = new HashMap<>();

    private final Map<ItemVariant, Long> recipeInputs = new HashMap<>();
    private final Map<ItemVariant, Long> recipeOutputs = new HashMap<>();
    private int recipeCycleTicks;
    private final Map<Fluid, Long> recipeInputFluids = new HashMap<>();
    private final Map<Fluid, Long> recipeOutputFluids = new HashMap<>();

    public Map<ItemVariant, Float> getInputRates() {
        return inputRates;
    }

    public Map<ItemVariant, Float> getOutputRates() {
        return outputRates;
    }

    public Map<Fluid, Float> getInputFluidRates() {
        return inputFluidRates;
    }

    public Map<Fluid, Float> getOutputFluidRates() {
        return outputFluidRates;
    }

    public Map<ItemVariant, Long> getRecipeInputs() {
        return recipeInputs;
    }

    public Map<ItemVariant, Long> getRecipeOutputs() {
        return recipeOutputs;
    }

    public int getRecipeCycleTicks() {
        return recipeCycleTicks;
    }

    public Map<Fluid, Long> getRecipeInputFluids() {
        return recipeInputFluids;
    }

    public Map<Fluid, Long> getRecipeOutputFluids() {
        return recipeOutputFluids;
    }

    public boolean hasFluidRecipe() {
        return !recipeInputFluids.isEmpty();
    }

    public boolean hasRecipe() {
        return !recipeInputs.isEmpty() || !recipeOutputs.isEmpty()
                || !recipeInputFluids.isEmpty() || !recipeOutputFluids.isEmpty();
    }

    public boolean hasCompleteRecipe() {
        return (!recipeInputs.isEmpty() || !recipeInputFluids.isEmpty())
                && (!recipeOutputs.isEmpty() || !recipeOutputFluids.isEmpty());
    }

    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    public void setIgnoredOutputs(Map<ItemVariant, Long> staticInventory) {
        ignoredOutputBudget.clear();
        staticInventory.forEach((variant, count) -> {
            if (count > 0) {
                ignoredOutputBudget.put(variant, count);
            }
        });
    }

    public void tickRates() {
        windowTicks++;
        if (windowTicks >= RATE_WINDOW) {
            commitWindow();
        }
    }

    public void beginSampling() {
        clearWindowCounters();
        clearLearningTotals();
    }

    public void commitWindow() {
        if (windowTicks <= 0) {
            clearWindowCounters();
            return;
        }
        float seconds = windowTicks / 20.0f;
        inputRates.clear();
        outputRates.clear();
        inputFluidRates.clear();
        outputFluidRates.clear();

        inputCount.forEach((variant, count) -> inputRates.put(variant, count / seconds));
        outputCount.forEach((variant, count) -> outputRates.put(variant, count / seconds));
        inputFluidCount.forEach((fluid, count) -> inputFluidRates.put(fluid, count / seconds));
        outputFluidCount.forEach((fluid, count) -> outputFluidRates.put(fluid, count / seconds));
        if (recording) {
            learningSampleTicks += windowTicks;
            mergeTotals(learningInputTotals, inputCount);
            mergeTotals(learningOutputTotals, learningOutputCount);
            mergeTotals(learningInputFluidTotals, inputFluidCount);
            mergeTotals(learningOutputFluidTotals, outputFluidCount);
        }
        clearWindowCounters();
    }

    public void compileRecipe() {
        commitWindow();
        boolean hasInput = !learningInputTotals.isEmpty() || !learningInputFluidTotals.isEmpty();
        buildIntegerRecipe();
        if (!hasInput) {
            clearIntegerRecipe();
        }
        syncDisplayRates();
    }

    private void syncDisplayRates() {
        inputRates.clear();
        recipeInputs.forEach((variant, count) -> inputRates.put(variant, count.floatValue()));
        outputRates.clear();
        recipeOutputs.forEach((variant, count) -> outputRates.put(variant, count.floatValue()));
        inputFluidRates.clear();
        recipeInputFluids.forEach((fluid, count) -> inputFluidRates.put(fluid, count.floatValue()));
        outputFluidRates.clear();
        recipeOutputFluids.forEach((fluid, count) -> outputFluidRates.put(fluid, count.floatValue()));
    }

    private void buildIntegerRecipe() {
        recipeInputs.clear();
        recipeOutputs.clear();
        recipeCycleTicks = 0;
        recipeInputFluids.clear();
        recipeOutputFluids.clear();

        if (learningSampleTicks <= 0) {
            return;
        }

        recipeCycleTicks = RATE_WINDOW;
        learningInputTotals.forEach((variant, total) -> putRoundedRate(recipeInputs, variant, total));
        learningOutputTotals.forEach((variant, total) -> putRoundedRate(recipeOutputs, variant, total));
        learningInputFluidTotals.forEach((fluid, total) -> putRoundedRate(recipeInputFluids, fluid, total));
        learningOutputFluidTotals.forEach((fluid, total) -> putRoundedRate(recipeOutputFluids, fluid, total));
    }

    private void clearIntegerRecipe() {
        recipeInputs.clear();
        recipeOutputs.clear();
        recipeCycleTicks = 0;
        recipeInputFluids.clear();
        recipeOutputFluids.clear();
    }

    private void putRoundedRate(Map<ItemVariant, Long> target, ItemVariant variant, long total) {
        long rate = roundedPerSecond(total);
        if (rate > 0) {
            target.put(variant, rate);
        }
    }

    private void putRoundedRate(Map<Fluid, Long> target, Fluid fluid, long total) {
        long rate = roundedPerSecond(total);
        if (rate > 0) {
            target.put(fluid, rate);
        }
    }

    private long roundedPerSecond(long total) {
        return Math.round((double) total * 20.0d / learningSampleTicks);
    }

    private static <K> void mergeTotals(Map<K, Long> target, Map<K, Long> source) {
        source.forEach((key, value) -> {
            if (value > 0) {
                target.merge(key, value, BlackboxData::safeAddLong);
            }
        });
    }

    private void clearLearningTotals() {
        learningInputTotals.clear();
        learningOutputTotals.clear();
        learningInputFluidTotals.clear();
        learningOutputFluidTotals.clear();
        learningSampleTicks = 0;
    }

    private void clearWindowCounters() {
        inputCount.clear();
        outputCount.clear();
        learningOutputCount.clear();
        inputFluidCount.clear();
        outputFluidCount.clear();
        windowTicks = 0;
    }

    public void recordItemInput(ItemStack stack, long moved) {
        if (stack.isEmpty() || moved <= 0) {
            return;
        }
        inputCount.merge(ItemVariant.of(stack), moved, BlackboxData::safeAddLong);
    }

    public void recordItemOutput(ItemStack stack, long moved) {
        if (stack.isEmpty() || moved <= 0) {
            return;
        }
        ItemVariant variant = ItemVariant.of(stack);
        outputCount.merge(variant, moved, BlackboxData::safeAddLong);
        if (!recording) {
            return;
        }
        long counted = moved;
        long ignored = ignoredOutputBudget.getOrDefault(variant, 0L);
        if (ignored > 0) {
            long skipped = Math.min(ignored, counted);
            counted -= skipped;
            ignoredOutputBudget.put(variant, ignored - skipped);
        }
        if (counted > 0) {
            learningOutputCount.merge(variant, counted, BlackboxData::safeAddLong);
        }
    }

    public void recordFluidInput(FluidStack stack, long moved) {
        if (!stack.isEmpty() && moved > 0) {
            inputFluidCount.merge(stack.getFluid(), moved, BlackboxData::safeAddLong);
        }
    }

    public void recordFluidOutput(FluidStack stack, long moved) {
        if (!stack.isEmpty() && moved > 0) {
            outputFluidCount.merge(stack.getFluid(), moved, BlackboxData::safeAddLong);
        }
    }

    public CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("ItemIdentityVersion", ITEM_IDENTITY_VERSION);
        writeVariantFloatMap(tag, "InputRates", inputRates, registries);
        writeVariantFloatMap(tag, "OutputRates", outputRates, registries);
        writeFluidRateMap(tag, "InputFluidRates", inputFluidRates);
        writeFluidRateMap(tag, "OutputFluidRates", outputFluidRates);
        writeVariantLongMap(tag, "RecipeInputs", recipeInputs, registries);
        writeVariantLongMap(tag, "RecipeOutputs", recipeOutputs, registries);
        tag.putInt("RecipeCycleTicks", recipeCycleTicks);
        writeFluidLongMap(tag, "RecipeInputFluids", recipeInputFluids);
        writeFluidLongMap(tag, "RecipeOutputFluids", recipeOutputFluids);
        return tag;
    }

    /** Strict format cutover: any legacy item-identity data is discarded instead of migrated. */
    public void read(CompoundTag tag, HolderLookup.Provider registries) {
        clearAll();
        if (tag.getInt("ItemIdentityVersion") != ITEM_IDENTITY_VERSION) {
            return;
        }
        readVariantFloatMap(tag, "InputRates", inputRates, registries);
        readVariantFloatMap(tag, "OutputRates", outputRates, registries);
        readFluidRateMap(tag, "InputFluidRates", inputFluidRates);
        readFluidRateMap(tag, "OutputFluidRates", outputFluidRates);
        readVariantLongMap(tag, "RecipeInputs", recipeInputs, registries);
        readVariantLongMap(tag, "RecipeOutputs", recipeOutputs, registries);
        recipeCycleTicks = Math.max(0, tag.getInt("RecipeCycleTicks"));
        readFluidLongMap(tag, "RecipeInputFluids", recipeInputFluids);
        readFluidLongMap(tag, "RecipeOutputFluids", recipeOutputFluids);
    }

    private void clearAll() {
        inputRates.clear();
        outputRates.clear();
        inputFluidRates.clear();
        outputFluidRates.clear();
        clearIntegerRecipe();
        ignoredOutputBudget.clear();
        recording = false;
        clearWindowCounters();
        clearLearningTotals();
    }

    private static long safeAddLong(long left, long right) {
        return Math.addExact(left, right);
    }

    private static void writeVariantFloatMap(CompoundTag root, String key, Map<ItemVariant, Float> values,
                                             HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag value = new CompoundTag();
            value.put("Stack", entry.getKey().write(registries));
            value.putFloat("Value", entry.getValue());
            list.add(value);
        });
        root.put(key, list);
    }

    private static void writeVariantLongMap(CompoundTag root, String key, Map<ItemVariant, Long> values,
                                            HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue() <= 0) {
                return;
            }
            CompoundTag value = new CompoundTag();
            value.put("Stack", entry.getKey().write(registries));
            value.putLong("Value", entry.getValue());
            list.add(value);
        });
        root.put(key, list);
    }

    private static void readVariantFloatMap(CompoundTag root, String key, Map<ItemVariant, Float> target,
                                            HolderLookup.Provider registries) {
        for (Tag raw : root.getList(key, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            ItemVariant variant = ItemVariant.read(registries, entry.getCompound("Stack"));
            float value = entry.getFloat("Value");
            if (variant != null && Float.isFinite(value) && value > 0) {
                target.put(variant, value);
            }
        }
    }

    private static void readVariantLongMap(CompoundTag root, String key, Map<ItemVariant, Long> target,
                                           HolderLookup.Provider registries) {
        for (Tag raw : root.getList(key, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            ItemVariant variant = ItemVariant.read(registries, entry.getCompound("Stack"));
            long value = entry.getLong("Value");
            if (variant != null && value > 0) {
                target.merge(variant, value, BlackboxData::safeAddLong);
            }
        }
    }

    private static void writeFluidRateMap(CompoundTag tag, String key, Map<Fluid, Float> map) {
        CompoundTag sub = new CompoundTag();
        map.forEach((fluid, value) -> {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
            if (id != null && Float.isFinite(value) && value > 0) {
                sub.putFloat(id.toString(), value);
            }
        });
        tag.put(key, sub);
    }

    private static void readFluidRateMap(CompoundTag tag, String key, Map<Fluid, Float> map) {
        CompoundTag sub = tag.getCompound(key);
        for (String valueKey : sub.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(valueKey);
            float value = sub.getFloat(valueKey);
            if (id != null && BuiltInRegistries.FLUID.containsKey(id) && Float.isFinite(value) && value > 0) {
                map.put(BuiltInRegistries.FLUID.get(id), value);
            }
        }
    }

    private static void writeFluidLongMap(CompoundTag tag, String key, Map<Fluid, Long> map) {
        CompoundTag sub = new CompoundTag();
        map.forEach((fluid, value) -> {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
            if (id != null && value > 0) {
                sub.putLong(id.toString(), value);
            }
        });
        tag.put(key, sub);
    }

    private static void readFluidLongMap(CompoundTag tag, String key, Map<Fluid, Long> map) {
        CompoundTag sub = tag.getCompound(key);
        for (String valueKey : sub.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(valueKey);
            long value = sub.getLong(valueKey);
            if (id != null && value > 0 && BuiltInRegistries.FLUID.containsKey(id)) {
                map.put(BuiltInRegistries.FLUID.get(id), value);
            }
        }
    }
}

package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * 工厂方块的「吞吐测量 + 黑盒配方」数据。
 * 不再承担任何物品 / 流体缓存（缓存已移除，工厂与 Port 是直连）。
 *
 * 黑盒模式下的配方是「整数」的：
 *  - 学习前 5 秒暖机，后 5 秒逐窗口记录每秒速率；
 *  - 物品：取后 5 秒每秒速率（四舍五入到整数）的众数，每秒消耗 / 产出整数个物品；
 *  - 流体：取后 5 秒每秒 mB 的众数。
 */
public final class BlackboxData {
    public static final int RATE_WINDOW = 20;

    // 每个面的实时速率（每秒），供护目镜信息显示。
    private final Map<Item, Float> inputRates = new HashMap<>();
    private final Map<Item, Float> outputRates = new HashMap<>();
    private final Map<Fluid, Float> inputFluidRates = new HashMap<>();
    private final Map<Fluid, Float> outputFluidRates = new HashMap<>();

    // 学习采样阶段记录的每秒速率频率表（整数速率 -> 出现次数），用于编译配方众数。
    private final Map<Item, Map<Integer, Integer>> inputRateCounts = new HashMap<>();
    private final Map<Item, Map<Integer, Integer>> outputRateCounts = new HashMap<>();
    private final Map<Fluid, Map<Long, Integer>> inputFluidRateCounts = new HashMap<>();
    private final Map<Fluid, Map<Long, Integer>> outputFluidRateCounts = new HashMap<>();

    // 实时测量窗口计数。
    private final Map<Item, Integer> inputCount = new HashMap<>();
    private final Map<Item, Integer> outputCount = new HashMap<>();
    private final Map<Item, Integer> learningOutputCount = new HashMap<>();
    private final Map<Fluid, Long> inputFluidCount = new HashMap<>();
    private final Map<Fluid, Long> outputFluidCount = new HashMap<>();
    private int windowTicks = 0;
    private boolean recording = false;
    private final Map<Item, Integer> ignoredOutputBudget = new HashMap<>();

    // 整数物品配方：每 recipeCycleTicks 消耗 recipeInputs、产出 recipeOutputs（全是整数）。
    private final Map<Item, Integer> recipeInputs = new HashMap<>();
    private final Map<Item, Integer> recipeOutputs = new HashMap<>();
    private int recipeCycleTicks = 0;

    // 整数流体配方：每秒消耗 / 产出的 mB。
    private final Map<Fluid, Long> recipeInputFluids = new HashMap<>();
    private final Map<Fluid, Long> recipeOutputFluids = new HashMap<>();

    public Map<Item, Float> getInputRates() {
        return inputRates;
    }

    public Map<Item, Float> getOutputRates() {
        return outputRates;
    }

    public Map<Fluid, Float> getInputFluidRates() {
        return inputFluidRates;
    }

    public Map<Fluid, Float> getOutputFluidRates() {
        return outputFluidRates;
    }

    public Map<Item, Integer> getRecipeInputs() {
        return recipeInputs;
    }

    public Map<Item, Integer> getRecipeOutputs() {
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
        return !recipeInputs.isEmpty()
                || !recipeOutputs.isEmpty()
                || !recipeInputFluids.isEmpty()
                || !recipeOutputFluids.isEmpty();
    }

    public boolean hasCompleteRecipe() {
        boolean hasInput = !recipeInputs.isEmpty() || !recipeInputFluids.isEmpty();
        boolean hasOutput = !recipeOutputs.isEmpty() || !recipeOutputFluids.isEmpty();
        return hasInput && hasOutput;
    }

    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    public void setIgnoredOutputs(Map<Item, Integer> staticInventory) {
        ignoredOutputBudget.clear();
        for (Map.Entry<Item, Integer> entry : staticInventory.entrySet()) {
            if (entry.getValue() > 0) {
                ignoredOutputBudget.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 推进实时测量窗口；每 RATE_WINDOW 帧提交一次实时速率。 */
    public void tickRates() {
        windowTicks++;
        if (windowTicks >= RATE_WINDOW) {
            commitWindow();
        }
    }

    /** 开始一段新的采样（进入采样阶段时调用）。 */
    public void beginSampling() {
        clearWindowCounters();
        clearRateCounts();
    }

    /** 把当前窗口计数换算成每秒速率，并按整数速率累计频率（用于众数统计）。 */
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
        for (Map.Entry<Item, Integer> e : inputCount.entrySet()) {
            float rate = e.getValue() / seconds;
            inputRates.put(e.getKey(), rate);
            int n = Math.round(rate);
            if (recording && n > 0) {
                inputRateCounts.computeIfAbsent(e.getKey(), k -> new HashMap<>()).merge(n, 1, Integer::sum);
            }
        }
        for (Map.Entry<Item, Integer> e : outputCount.entrySet()) {
            float rate = e.getValue() / seconds;
            outputRates.put(e.getKey(), rate);
        }
        for (Map.Entry<Item, Integer> e : learningOutputCount.entrySet()) {
            float rate = e.getValue() / seconds;
            int n = Math.round(rate);
            if (recording && n > 0) {
                outputRateCounts.computeIfAbsent(e.getKey(), k -> new HashMap<>()).merge(n, 1, Integer::sum);
            }
        }
        for (Map.Entry<Fluid, Long> e : inputFluidCount.entrySet()) {
            float rate = e.getValue() / seconds;
            inputFluidRates.put(e.getKey(), rate);
            long mbs = Math.round((double) rate);
            if (mbs > 0) {
                inputFluidRateCounts.computeIfAbsent(e.getKey(), k -> new HashMap<>()).merge(mbs, 1, Integer::sum);
            }
        }
        for (Map.Entry<Fluid, Long> e : outputFluidCount.entrySet()) {
            float rate = e.getValue() / seconds;
            outputFluidRates.put(e.getKey(), rate);
            long mbs = Math.round((double) rate);
            if (mbs > 0) {
                outputFluidRateCounts.computeIfAbsent(e.getKey(), k -> new HashMap<>()).merge(mbs, 1, Integer::sum);
            }
        }
        clearWindowCounters();
    }

    /** 用学习采样阶段的速率众数编译出整数黑盒配方，并让显示速率与配方一致。 */
    public void compileRecipe() {
        commitWindow();
        boolean hasInput = !inputRateCounts.isEmpty() || !inputFluidRateCounts.isEmpty();
        buildIntegerRecipe();
        if (!hasInput) {
            clearIntegerRecipe();
        }
        syncDisplayRates();
        clearRateCounts();
    }

    /** 让显示速率直接反映整数配方，保证护目镜 / 界面显示为整数。 */
    private void syncDisplayRates() {
        inputRates.clear();
        for (Map.Entry<Item, Integer> e : recipeInputs.entrySet()) {
            inputRates.put(e.getKey(), (float) e.getValue());
        }
        outputRates.clear();
        for (Map.Entry<Item, Integer> e : recipeOutputs.entrySet()) {
            outputRates.put(e.getKey(), (float) e.getValue());
        }
        inputFluidRates.clear();
        for (Map.Entry<Fluid, Long> e : recipeInputFluids.entrySet()) {
            inputFluidRates.put(e.getKey(), (float) e.getValue());
        }
        outputFluidRates.clear();
        for (Map.Entry<Fluid, Long> e : recipeOutputFluids.entrySet()) {
            outputFluidRates.put(e.getKey(), (float) e.getValue());
        }
    }

    /**
     * 编译整数黑盒配方：
     *  - 物品：取采样阶段每秒速率（四舍五入到整数）的众数，固定每秒一个周期；
     *  - 流体：取采样阶段每秒 mB 的众数。
     */
    private void buildIntegerRecipe() {
        recipeInputs.clear();
        recipeOutputs.clear();
        recipeCycleTicks = 0;
        recipeInputFluids.clear();
        recipeOutputFluids.clear();

        // 物品配方：速率众数（仅当有物品输入时）
        if (!inputRateCounts.isEmpty()) {
            recipeCycleTicks = RATE_WINDOW;
            for (Map.Entry<Item, Map<Integer, Integer>> e : inputRateCounts.entrySet()) {
                int mode = modeOf(e.getValue());
                if (mode > 0) {
                    recipeInputs.put(e.getKey(), mode);
                }
            }
            for (Map.Entry<Item, Map<Integer, Integer>> e : outputRateCounts.entrySet()) {
                int mode = modeOf(e.getValue());
                if (mode > 0) {
                    recipeOutputs.put(e.getKey(), mode);
                }
            }
        }

        // 流体配方：速率众数 mB/s
        for (Map.Entry<Fluid, Map<Long, Integer>> e : inputFluidRateCounts.entrySet()) {
            long mode = modeOfLong(e.getValue());
            if (mode > 0) {
                recipeInputFluids.put(e.getKey(), mode);
            }
        }
        for (Map.Entry<Fluid, Map<Long, Integer>> e : outputFluidRateCounts.entrySet()) {
            long mode = modeOfLong(e.getValue());
            if (mode > 0) {
                recipeOutputFluids.put(e.getKey(), mode);
            }
        }
    }

    private void clearIntegerRecipe() {
        recipeInputs.clear();
        recipeOutputs.clear();
        recipeCycleTicks = 0;
        recipeInputFluids.clear();
        recipeOutputFluids.clear();
    }

    private void clearRateCounts() {
        inputRateCounts.clear();
        outputRateCounts.clear();
        inputFluidRateCounts.clear();
        outputFluidRateCounts.clear();
    }

    private void clearWindowCounters() {
        inputCount.clear();
        outputCount.clear();
        learningOutputCount.clear();
        inputFluidCount.clear();
        outputFluidCount.clear();
        windowTicks = 0;
    }

    private static int modeOf(Map<Integer, Integer> counts) {
        int bestKey = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() > bestKey)) {
                bestCount = e.getValue();
                bestKey = e.getKey();
            }
        }
        return bestKey;
    }

    private static long modeOfLong(Map<Long, Integer> counts) {
        long bestKey = 0;
        int bestCount = -1;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() > bestKey)) {
                bestCount = e.getValue();
                bestKey = e.getKey();
            }
        }
        return bestKey;
    }

    /** 包一层物品 handler：记录流过的输入 / 输出数量（用于测速）。 */
    public IItemHandler wrapRateItem(IItemHandler base) {
        return new IItemHandler() {
            @Override
            public int getSlots() {
                return base.getSlots();
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return base.getStackInSlot(slot);
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                ItemStack result = base.insertItem(slot, stack, simulate);
                if (!simulate) {
                    int moved = stack.getCount() - result.getCount();
                    if (moved > 0) {
                        inputCount.merge(stack.getItem(), moved, Integer::sum);
                    }
                }
                return result;
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                ItemStack result = base.extractItem(slot, amount, simulate);
                if (!simulate && !result.isEmpty()) {
                    Item item = result.getItem();
                    int extracted = result.getCount();
                    outputCount.merge(item, extracted, Integer::sum);
                    if (recording) {
                        int counted = extracted;
                        int ignored = ignoredOutputBudget.getOrDefault(item, 0);
                        if (ignored > 0) {
                            int skip = Math.min(ignored, counted);
                            counted -= skip;
                            ignoredOutputBudget.put(item, ignored - skip);
                        }
                        if (counted > 0) {
                            learningOutputCount.merge(item, counted, Integer::sum);
                        }
                    }
                }
                return result;
            }

            @Override
            public int getSlotLimit(int slot) {
                return base.getSlotLimit(slot);
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return base.isItemValid(slot, stack);
            }
        };
    }

    /** 包一层流体 handler：记录流过的输入 / 输出数量（用于测速）。 */
    public IFluidHandler wrapRateFluid(IFluidHandler base) {
        return new IFluidHandler() {
            @Override
            public int getTanks() {
                return base.getTanks();
            }

            @Override
            public FluidStack getFluidInTank(int tank) {
                return base.getFluidInTank(tank);
            }

            @Override
            public int getTankCapacity(int tank) {
                return base.getTankCapacity(tank);
            }

            @Override
            public boolean isFluidValid(int tank, FluidStack stack) {
                return base.isFluidValid(tank, stack);
            }

            @Override
            public int fill(FluidStack resource, FluidAction action) {
                int filled = base.fill(resource, action);
                if (action.execute() && filled > 0) {
                    inputFluidCount.merge(resource.getFluid(), (long) filled, Long::sum);
                }
                return filled;
            }

            @Override
            public FluidStack drain(FluidStack resource, FluidAction action) {
                FluidStack drained = base.drain(resource, action);
                if (action.execute() && !drained.isEmpty()) {
                    outputFluidCount.merge(drained.getFluid(), (long) drained.getAmount(), Long::sum);
                }
                return drained;
            }

            @Override
            public FluidStack drain(int maxDrain, FluidAction action) {
                FluidStack drained = base.drain(maxDrain, action);
                if (action.execute() && !drained.isEmpty()) {
                    outputFluidCount.merge(drained.getFluid(), (long) drained.getAmount(), Long::sum);
                }
                return drained;
            }
        };
    }

    public CompoundTag write(CompoundTag tag) {
        writeRateMap(tag, "InputRates", inputRates);
        writeRateMap(tag, "OutputRates", outputRates);
        writeFluidRateMap(tag, "InputFluidRates", inputFluidRates);
        writeFluidRateMap(tag, "OutputFluidRates", outputFluidRates);
        writeIntMap(tag, "RecipeInputs", recipeInputs);
        writeIntMap(tag, "RecipeOutputs", recipeOutputs);
        tag.putInt("RecipeCycleTicks", recipeCycleTicks);
        writeFluidLongMap(tag, "RecipeInputFluids", recipeInputFluids);
        writeFluidLongMap(tag, "RecipeOutputFluids", recipeOutputFluids);
        return tag;
    }

    public void read(CompoundTag tag) {
        inputRates.clear();
        readRateMap(tag, "InputRates", inputRates);
        outputRates.clear();
        readRateMap(tag, "OutputRates", outputRates);
        inputFluidRates.clear();
        readFluidRateMap(tag, "InputFluidRates", inputFluidRates);
        outputFluidRates.clear();
        readFluidRateMap(tag, "OutputFluidRates", outputFluidRates);
        recipeInputs.clear();
        readIntMap(tag, "RecipeInputs", recipeInputs);
        recipeOutputs.clear();
        readIntMap(tag, "RecipeOutputs", recipeOutputs);
        recipeCycleTicks = tag.getInt("RecipeCycleTicks");
        recipeInputFluids.clear();
        readFluidLongMap(tag, "RecipeInputFluids", recipeInputFluids);
        recipeOutputFluids.clear();
        readFluidLongMap(tag, "RecipeOutputFluids", recipeOutputFluids);
        clearWindowCounters();
        clearRateCounts();
    }

    private static void writeRateMap(CompoundTag tag, String key, Map<Item, Float> map) {
        CompoundTag sub = new CompoundTag();
        for (Map.Entry<Item, Float> e : map.entrySet()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(e.getKey());
            if (rl != null) {
                sub.putFloat(rl.toString(), e.getValue());
            }
        }
        tag.put(key, sub);
    }

    private static void writeFluidRateMap(CompoundTag tag, String key, Map<Fluid, Float> map) {
        CompoundTag sub = new CompoundTag();
        for (Map.Entry<Fluid, Float> e : map.entrySet()) {
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(e.getKey());
            if (rl != null) {
                sub.putFloat(rl.toString(), e.getValue());
            }
        }
        tag.put(key, sub);
    }

    private static void readRateMap(CompoundTag tag, String key, Map<Item, Float> map) {
        CompoundTag sub = tag.getCompound(key);
        for (String k : sub.getAllKeys()) {
            ResourceLocation rl = ResourceLocation.tryParse(k);
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                map.put(BuiltInRegistries.ITEM.get(rl), sub.getFloat(k));
            }
        }
    }

    private static void readFluidRateMap(CompoundTag tag, String key, Map<Fluid, Float> map) {
        CompoundTag sub = tag.getCompound(key);
        for (String k : sub.getAllKeys()) {
            ResourceLocation rl = ResourceLocation.tryParse(k);
            if (rl != null && BuiltInRegistries.FLUID.containsKey(rl)) {
                map.put(BuiltInRegistries.FLUID.get(rl), sub.getFloat(k));
            }
        }
    }

    private static void writeIntMap(CompoundTag tag, String key, Map<Item, Integer> map) {
        CompoundTag sub = new CompoundTag();
        for (Map.Entry<Item, Integer> e : map.entrySet()) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(e.getKey());
            if (rl != null) {
                sub.putInt(rl.toString(), e.getValue());
            }
        }
        tag.put(key, sub);
    }

    private static void writeFluidLongMap(CompoundTag tag, String key, Map<Fluid, Long> map) {
        CompoundTag sub = new CompoundTag();
        for (Map.Entry<Fluid, Long> e : map.entrySet()) {
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(e.getKey());
            if (rl != null) {
                sub.putLong(rl.toString(), e.getValue());
            }
        }
        tag.put(key, sub);
    }

    private static void readIntMap(CompoundTag tag, String key, Map<Item, Integer> map) {
        CompoundTag sub = tag.getCompound(key);
        for (String k : sub.getAllKeys()) {
            ResourceLocation rl = ResourceLocation.tryParse(k);
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                map.put(BuiltInRegistries.ITEM.get(rl), sub.getInt(k));
            }
        }
    }

    private static void readFluidLongMap(CompoundTag tag, String key, Map<Fluid, Long> map) {
        CompoundTag sub = tag.getCompound(key);
        for (String k : sub.getAllKeys()) {
            ResourceLocation rl = ResourceLocation.tryParse(k);
            if (rl != null && BuiltInRegistries.FLUID.containsKey(rl)) {
                map.put(BuiltInRegistries.FLUID.get(rl), sub.getLong(k));
            }
        }
    }
}

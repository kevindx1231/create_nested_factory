package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Transient one-second measurements of resources that actually crossed the external boundary. */
final class FactoryTransferTelemetry {
    private static final long WINDOW_TICKS = 20L;

    private final Meter<ItemVariant> itemInputs = new Meter<>();
    private final Meter<ItemVariant> itemOutputs = new Meter<>();
    private final Meter<FluidVariant> fluidInputs = new Meter<>();
    private final Meter<FluidVariant> fluidOutputs = new Meter<>();
    /** Last server snapshot used only by the client-side goggle tooltip. */
    private Map<ItemVariant, Float> syncedItemInputs = Map.of();
    private Map<ItemVariant, Float> syncedItemOutputs = Map.of();
    private Map<FluidVariant, Float> syncedFluidInputs = Map.of();
    private Map<FluidVariant, Float> syncedFluidOutputs = Map.of();
    private boolean useSyncedSnapshot;

    void recordItem(boolean input, ItemStack stack, int amount, long tick) {
        if (stack == null || stack.isEmpty() || amount <= 0) return;
        useSyncedSnapshot = false;
        (input ? itemInputs : itemOutputs).record(ItemVariant.of(stack), amount, tick);
    }

    void recordFluid(boolean input, FluidStack stack, int amount, long tick) {
        if (stack == null || stack.isEmpty() || amount <= 0) return;
        useSyncedSnapshot = false;
        (input ? fluidInputs : fluidOutputs).record(FluidVariant.of(stack), amount, tick);
    }

    Map<ItemVariant, Float> itemRates(boolean input, long tick) {
        if (useSyncedSnapshot) return input ? syncedItemInputs : syncedItemOutputs;
        return (input ? itemInputs : itemOutputs).rates(tick);
    }

    Map<Fluid, Float> fluidRates(boolean input, long tick) {
        Map<Fluid, Float> result = new HashMap<>();
        Map<FluidVariant, Float> rates = useSyncedSnapshot
                ? (input ? syncedFluidInputs : syncedFluidOutputs)
                : (input ? fluidInputs : fluidOutputs).rates(tick);
        rates
                .forEach((variant, rate) -> result.merge(variant.fluid(), rate, Float::sum));
        return Map.copyOf(result);
    }

    CompoundTag writeSnapshot(HolderLookup.Provider registries, long tick) {
        CompoundTag tag = new CompoundTag();
        tag.put("ItemInputs", writeItemRates(itemInputs.rates(tick), registries));
        tag.put("ItemOutputs", writeItemRates(itemOutputs.rates(tick), registries));
        tag.put("FluidInputs", writeFluidRates(fluidInputs.rates(tick), registries));
        tag.put("FluidOutputs", writeFluidRates(fluidOutputs.rates(tick), registries));
        return tag;
    }

    void readSnapshot(CompoundTag tag, HolderLookup.Provider registries) {
        syncedItemInputs = readItemRates(tag.getList("ItemInputs", Tag.TAG_COMPOUND), registries);
        syncedItemOutputs = readItemRates(tag.getList("ItemOutputs", Tag.TAG_COMPOUND), registries);
        syncedFluidInputs = readFluidRates(tag.getList("FluidInputs", Tag.TAG_COMPOUND), registries);
        syncedFluidOutputs = readFluidRates(tag.getList("FluidOutputs", Tag.TAG_COMPOUND), registries);
        useSyncedSnapshot = true;
    }

    void clear() {
        itemInputs.clear();
        itemOutputs.clear();
        fluidInputs.clear();
        fluidOutputs.clear();
        syncedItemInputs = Map.of();
        syncedItemOutputs = Map.of();
        syncedFluidInputs = Map.of();
        syncedFluidOutputs = Map.of();
        useSyncedSnapshot = false;
    }

    private static ListTag writeItemRates(Map<ItemVariant, Float> rates, HolderLookup.Provider registries) {
        ListTag values = new ListTag();
        rates.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!Float.isFinite(entry.getValue()) || entry.getValue() <= 0f) return;
            CompoundTag value = new CompoundTag();
            value.put("Stack", entry.getKey().write(registries));
            value.putFloat("Rate", entry.getValue());
            values.add(value);
        });
        return values;
    }

    private static ListTag writeFluidRates(Map<FluidVariant, Float> rates, HolderLookup.Provider registries) {
        ListTag values = new ListTag();
        rates.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!Float.isFinite(entry.getValue()) || entry.getValue() <= 0f) return;
            CompoundTag value = new CompoundTag();
            value.put("Stack", entry.getKey().write(registries));
            value.putFloat("Rate", entry.getValue());
            values.add(value);
        });
        return values;
    }

    private static Map<ItemVariant, Float> readItemRates(ListTag values, HolderLookup.Provider registries) {
        Map<ItemVariant, Float> rates = new HashMap<>();
        for (Tag raw : values) {
            CompoundTag value = (CompoundTag) raw;
            ItemVariant variant = ItemVariant.read(registries, value.getCompound("Stack"));
            float rate = value.getFloat("Rate");
            if (variant != null && Float.isFinite(rate) && rate > 0f) rates.merge(variant, rate, Float::sum);
        }
        return Map.copyOf(rates);
    }

    private static Map<FluidVariant, Float> readFluidRates(ListTag values, HolderLookup.Provider registries) {
        Map<FluidVariant, Float> rates = new HashMap<>();
        for (Tag raw : values) {
            CompoundTag value = (CompoundTag) raw;
            FluidVariant variant = FluidVariant.read(registries, value.getCompound("Stack"));
            float rate = value.getFloat("Rate");
            if (variant != null && Float.isFinite(rate) && rate > 0f) rates.merge(variant, rate, Float::sum);
        }
        return Map.copyOf(rates);
    }

    private record Sample(long tick, long amount) {
    }

    private static final class Meter<K> {
        private final Map<K, ArrayDeque<Sample>> samples = new HashMap<>();

        void record(K key, long amount, long tick) {
            if (key == null || amount <= 0L) return;
            ArrayDeque<Sample> values = samples.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            Sample last = values.peekLast();
            if (last != null && last.tick() == tick) {
                values.removeLast();
                amount = saturatingAdd(last.amount(), amount);
            }
            values.addLast(new Sample(tick, amount));
            prune(tick);
        }

        Map<K, Float> rates(long tick) {
            prune(tick);
            Map<K, Float> result = new HashMap<>();
            for (Map.Entry<K, ArrayDeque<Sample>> entry : samples.entrySet()) {
                long total = 0L;
                for (Sample sample : entry.getValue()) total = saturatingAdd(total, sample.amount());
                if (total > 0L) result.put(entry.getKey(), (float) total);
            }
            return Map.copyOf(result);
        }

        void clear() {
            samples.clear();
        }

        private void prune(long tick) {
            long cutoff = tick - WINDOW_TICKS;
            Iterator<Map.Entry<K, ArrayDeque<Sample>>> iterator = samples.entrySet().iterator();
            while (iterator.hasNext()) {
                ArrayDeque<Sample> values = iterator.next().getValue();
                while (!values.isEmpty() && values.peekFirst().tick() <= cutoff) values.removeFirst();
                if (values.isEmpty()) iterator.remove();
            }
        }

        private static long saturatingAdd(long left, long right) {
            return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }
    }
}

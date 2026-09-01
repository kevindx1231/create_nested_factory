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
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent, unbounded-in-gameplay transit channels for a factory's six logical ports.
 * Resources move only when an external or room-side capability caller performs an operation;
 * this class never ticks or pushes into adjacent blocks.
 */
public final class FactoryPortChannels {
    private static final int CHANNEL_COUNT = 6;
    /** One small offer starts an otherwise empty direction; later offers are paid for by real downstream extraction. */
    private static final long INITIAL_ITEM_PRIME_CREDITS = 64L;
    private static final long INITIAL_FLUID_PRIME_CREDITS = 1000L;
    private final PortResourceChannel[] channels = new PortResourceChannel[CHANNEL_COUNT];

    public FactoryPortChannels() {
        for (int index = 0; index < CHANNEL_COUNT; index++) {
            channels[index] = new PortResourceChannel();
        }
    }

    public PortResourceChannel channel(int portId) {
        if (portId < 1 || portId > CHANNEL_COUNT) {
            throw new IllegalArgumentException("Port id must be in 1..6: " + portId);
        }
        return channels[portId - 1];
    }

    public boolean isEmpty() {
        for (PortResourceChannel channel : channels) {
            if (!channel.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Discards every pending transit resource, used by destructive configuration replacement. */
    public void clear() {
        for (PortResourceChannel channel : channels) {
            channel.clear();
        }
    }

    /** Materializes every pending item and discards every pending fluid. */
    public List<ItemStack> drainItemsAndDiscardFluids() {
        List<ItemStack> dropped = new ArrayList<>();
        for (PortResourceChannel channel : channels) {
            channel.appendItemsAndDiscardFluids(dropped);
        }
        return dropped;
    }

    public boolean isEmpty(int portId) {
        return channel(portId).isEmpty();
    }

    public CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
        for (int index = 0; index < CHANNEL_COUNT; index++) {
            tag.put("Channel" + index, channels[index].write(new CompoundTag(), registries));
        }
        return tag;
    }

    public void read(CompoundTag tag, HolderLookup.Provider registries) {
        for (int index = 0; index < CHANNEL_COUNT; index++) {
            channels[index].read(tag.getCompound("Channel" + index), registries);
        }
    }

    public static final class PortResourceChannel {
        private final ItemChannel inputItems = new ItemChannel();
        private final ItemChannel outputItems = new ItemChannel();
        private final FluidChannel inputFluids = new FluidChannel();
        private final FluidChannel outputFluids = new FluidChannel();
        private long inputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
        private long outputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
        private long inputFluidCredits = INITIAL_FLUID_PRIME_CREDITS;
        private long outputFluidCredits = INITIAL_FLUID_PRIME_CREDITS;

        public ItemChannel inputItems() {
            return inputItems;
        }

        public ItemChannel outputItems() {
            return outputItems;
        }

        public FluidChannel inputFluids() {
            return inputFluids;
        }

        public FluidChannel outputFluids() {
            return outputFluids;
        }

        public boolean isEmpty() {
            return inputItems.isEmpty() && outputItems.isEmpty()
                    && inputFluids.isEmpty() && outputFluids.isEmpty();
        }

        private void clear() {
            inputItems.clear();
            outputItems.clear();
            inputFluids.clear();
            outputFluids.clear();
            inputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
            outputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
            inputFluidCredits = INITIAL_FLUID_PRIME_CREDITS;
            outputFluidCredits = INITIAL_FLUID_PRIME_CREDITS;
        }

        private void appendItemsAndDiscardFluids(List<ItemStack> dropped) {
            inputItems.appendAndClear(dropped);
            outputItems.appendAndClear(dropped);
            inputFluids.clear();
            outputFluids.clear();
            inputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
            outputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
            inputFluidCredits = INITIAL_FLUID_PRIME_CREDITS;
            outputFluidCredits = INITIAL_FLUID_PRIME_CREDITS;
        }

        public boolean canAcceptInputItems(long ignoredGameTime) {
            return inputItemCredits > 0;
        }

        /**
         * Checks whether one package-sized item batch can cross this INPUT boundary as one commit.
         * This remains bounded by the existing handoff credits and does not create general storage.
         */
        public boolean canAcceptInputItemBatch(long ignoredGameTime, List<ItemStack> stacks) {
            long total = totalItemCount(stacks);
            return total > 0 && total <= inputItemCredits && inputItems.canInsertAll(stacks);
        }

        /**
         * Commits a previously validated input batch. A failure leaves the channel unchanged.
         */
        public boolean insertInputItemBatch(long ignoredGameTime, List<ItemStack> stacks) {
            if (!canAcceptInputItemBatch(ignoredGameTime, stacks)) {
                return false;
            }
            long total = totalItemCount(stacks);
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    inputItems.insert(stack, false);
                }
            }
            consumeInputItems((int) total);
            return true;
        }

        public boolean canAcceptOutputItems(long ignoredGameTime) {
            return outputItemCredits > 0;
        }

        public boolean canAcceptInputFluids(long ignoredGameTime) {
            return inputFluidCredits > 0;
        }

        public boolean canAcceptOutputFluids(long ignoredGameTime) {
            return outputFluidCredits > 0;
        }

        public int inputItemOfferLimit(long ignoredGameTime, ItemStack stack) {
            return offerLimit(inputItemCredits, stack.isEmpty() ? 0 : stack.getCount());
        }

        public int outputItemOfferLimit(long ignoredGameTime, ItemStack stack) {
            return offerLimit(outputItemCredits, stack.isEmpty() ? 0 : stack.getCount());
        }

        public int inputFluidOfferLimit(long ignoredGameTime, FluidStack stack) {
            return offerLimit(inputFluidCredits, stack.isEmpty() ? 0 : stack.getAmount());
        }

        public int outputFluidOfferLimit(long ignoredGameTime, FluidStack stack) {
            return offerLimit(outputFluidCredits, stack.isEmpty() ? 0 : stack.getAmount());
        }

        public void markInputItemExtracted(int amount) {
            inputItemCredits = safeAdd(inputItemCredits, Math.max(0, amount));
        }

        public void markOutputItemExtracted(int amount) {
            outputItemCredits = safeAdd(outputItemCredits, Math.max(0, amount));
        }

        public void markInputFluidDrained(int amount) {
            inputFluidCredits = safeAdd(inputFluidCredits, Math.max(0, amount));
        }

        public void markOutputFluidDrained(int amount) {
            outputFluidCredits = safeAdd(outputFluidCredits, Math.max(0, amount));
        }

        private static int offerLimit(long credits, int requested) {
            if (credits <= 0 || requested <= 0) {
                return 0;
            }
            return (int) Math.min(credits, requested);
        }

        private static long totalItemCount(List<ItemStack> stacks) {
            if (stacks == null) {
                return -1L;
            }
            long total = 0L;
            for (ItemStack stack : stacks) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (Long.MAX_VALUE - total < stack.getCount()) {
                    return -1L;
                }
                total += stack.getCount();
            }
            return total;
        }

        public void consumeInputItems(int amount) {
            inputItemCredits = Math.max(0L, inputItemCredits - Math.max(0, amount));
        }

        public void consumeOutputItems(int amount) {
            outputItemCredits = Math.max(0L, outputItemCredits - Math.max(0, amount));
        }

        public void consumeInputFluids(int amount) {
            inputFluidCredits = Math.max(0L, inputFluidCredits - Math.max(0, amount));
        }

        public void consumeOutputFluids(int amount) {
            outputFluidCredits = Math.max(0L, outputFluidCredits - Math.max(0, amount));
        }

        private CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
            tag.put("InputItems", inputItems.write(new CompoundTag(), registries));
            tag.put("OutputItems", outputItems.write(new CompoundTag(), registries));
            tag.put("InputFluids", inputFluids.write(new CompoundTag()));
            tag.put("OutputFluids", outputFluids.write(new CompoundTag()));
            return tag;
        }

        private void read(CompoundTag tag, HolderLookup.Provider registries) {
            inputItems.read(tag.getCompound("InputItems"), registries);
            outputItems.read(tag.getCompound("OutputItems"), registries);
            inputFluids.read(tag.getCompound("InputFluids"));
            outputFluids.read(tag.getCompound("OutputFluids"));
            inputItemCredits = inputItems.isEmpty() ? INITIAL_ITEM_PRIME_CREDITS : 0L;
            outputItemCredits = outputItems.isEmpty() ? INITIAL_ITEM_PRIME_CREDITS : 0L;
            inputFluidCredits = inputFluids.isEmpty() ? INITIAL_FLUID_PRIME_CREDITS : 0L;
            outputFluidCredits = outputFluids.isEmpty() ? INITIAL_FLUID_PRIME_CREDITS : 0L;
        }
    }

    /** Dynamic virtual-slot item channel. There is no fixed inventory capacity. */
    public static final class ItemChannel {
        private final Map<ItemVariant, Long> values = new HashMap<>();

        public int slots() {
            return sortedVariants().size();
        }

        public ItemStack stackInSlot(int slot) {
            ItemVariant variant = variantAt(slot);
            if (variant == null) {
                return ItemStack.EMPTY;
            }
            long count = values.getOrDefault(variant, 0L);
            int max = Math.max(1, variant.prototype().getMaxStackSize());
            return count <= 0 ? ItemStack.EMPTY : variant.createStack((int) Math.min(count, max));
        }

        public ItemStack insert(ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemVariant variant = ItemVariant.of(stack);
            long current = values.getOrDefault(variant, 0L);
            if (Long.MAX_VALUE - current < stack.getCount()) {
                return stack;
            }
            if (!simulate) {
                values.put(variant, current + stack.getCount());
            }
            return ItemStack.EMPTY;
        }

        /** Verifies every stack as one batch, including repeated variants and long overflow. */
        public boolean canInsertAll(List<ItemStack> stacks) {
            if (stacks == null) {
                return false;
            }
            Map<ItemVariant, Long> additions = new HashMap<>();
            for (ItemStack stack : stacks) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                ItemVariant variant = ItemVariant.of(stack);
                long previous = additions.getOrDefault(variant, 0L);
                if (Long.MAX_VALUE - previous < stack.getCount()) {
                    return false;
                }
                additions.put(variant, previous + stack.getCount());
            }
            for (Map.Entry<ItemVariant, Long> entry : additions.entrySet()) {
                long current = values.getOrDefault(entry.getKey(), 0L);
                if (Long.MAX_VALUE - current < entry.getValue()) {
                    return false;
                }
            }
            return true;
        }

        public ItemStack extract(int slot, int amount, boolean simulate) {
            if (amount <= 0) {
                return ItemStack.EMPTY;
            }
            ItemVariant variant = variantAt(slot);
            if (variant == null) {
                return ItemStack.EMPTY;
            }
            long current = values.getOrDefault(variant, 0L);
            if (current <= 0) {
                return ItemStack.EMPTY;
            }
            int max = Math.max(1, variant.prototype().getMaxStackSize());
            int extracted = (int) Math.min(current, Math.min((long) amount, max));
            if (!simulate) {
                long remaining = current - extracted;
                if (remaining == 0) {
                    values.remove(variant);
                } else {
                    values.put(variant, remaining);
                }
            }
            return variant.createStack(extracted);
        }

        public boolean isEmpty() {
            return values.isEmpty();
        }

        private void clear() {
            values.clear();
        }

        private void appendAndClear(List<ItemStack> dropped) {
            values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                long remaining = entry.getValue();
                int max = Math.max(1, entry.getKey().prototype().getMaxStackSize());
                while (remaining > 0) {
                    int count = (int) Math.min(remaining, max);
                    dropped.add(entry.getKey().createStack(count));
                    remaining -= count;
                }
            });
            values.clear();
        }

        private CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                if (entry.getValue() <= 0) {
                    return;
                }
                CompoundTag value = new CompoundTag();
                value.put("Stack", entry.getKey().write(registries));
                value.putLong("Count", entry.getValue());
                list.add(value);
            });
            tag.put("Values", list);
            return tag;
        }

        private void read(CompoundTag tag, HolderLookup.Provider registries) {
            values.clear();
            for (Tag raw : tag.getList("Values", Tag.TAG_COMPOUND)) {
                CompoundTag value = (CompoundTag) raw;
                ItemVariant variant = ItemVariant.read(registries, value.getCompound("Stack"));
                long count = value.getLong("Count");
                if (variant != null && count > 0) {
                    values.merge(variant, count, FactoryPortChannels::safeAdd);
                }
            }
        }

        private ItemVariant variantAt(int slot) {
            if (slot < 0) {
                return null;
            }
            List<ItemVariant> variants = sortedVariants();
            return slot >= variants.size() ? null : variants.get(slot);
        }

        private List<ItemVariant> sortedVariants() {
            return values.keySet().stream().sorted().toList();
        }
    }

    /** Dynamic virtual-tank fluid channel. Fluid components are not distinguished by the NeoForge fluid API here. */
    public static final class FluidChannel {
        private final Map<Fluid, Long> values = new HashMap<>();

        public int tanks() {
            return sortedFluids().size();
        }

        public FluidStack fluidInTank(int tank) {
            Fluid fluid = fluidAt(tank);
            if (fluid == null) {
                return FluidStack.EMPTY;
            }
            long amount = values.getOrDefault(fluid, 0L);
            return amount <= 0 ? FluidStack.EMPTY
                    : new FluidStack(fluid, (int) Math.min(amount, Integer.MAX_VALUE));
        }

        public int fill(FluidStack resource, IFluidHandler.FluidAction action) {
            if (resource.isEmpty()) {
                return 0;
            }
            Fluid fluid = resource.getFluid();
            long current = values.getOrDefault(fluid, 0L);
            if (Long.MAX_VALUE - current < resource.getAmount()) {
                return 0;
            }
            if (action.execute()) {
                values.put(fluid, current + resource.getAmount());
            }
            return resource.getAmount();
        }

        public FluidStack drain(FluidStack requested, IFluidHandler.FluidAction action) {
            if (requested.isEmpty()) {
                return FluidStack.EMPTY;
            }
            return drain(requested.getFluid(), requested.getAmount(), action);
        }

        public FluidStack drain(int maxDrain, IFluidHandler.FluidAction action) {
            if (maxDrain <= 0 || values.isEmpty()) {
                return FluidStack.EMPTY;
            }
            Fluid fluid = fluidAt(0);
            return fluid == null ? FluidStack.EMPTY : drain(fluid, maxDrain, action);
        }

        public boolean isEmpty() {
            return values.isEmpty();
        }

        private void clear() {
            values.clear();
        }

        private FluidStack drain(Fluid fluid, int requested, IFluidHandler.FluidAction action) {
            long current = values.getOrDefault(fluid, 0L);
            if (current <= 0) {
                return FluidStack.EMPTY;
            }
            int drained = (int) Math.min(current, requested);
            if (action.execute()) {
                long remaining = current - drained;
                if (remaining == 0) {
                    values.remove(fluid);
                } else {
                    values.put(fluid, remaining);
                }
            }
            return new FluidStack(fluid, drained);
        }

        private CompoundTag write(CompoundTag tag) {
            CompoundTag valuesTag = new CompoundTag();
            values.forEach((fluid, count) -> {
                ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
                if (id != null && count > 0) {
                    valuesTag.putLong(id.toString(), count);
                }
            });
            tag.put("Values", valuesTag);
            return tag;
        }

        private void read(CompoundTag tag) {
            values.clear();
            CompoundTag valuesTag = tag.getCompound("Values");
            for (String idText : valuesTag.getAllKeys()) {
                ResourceLocation id = ResourceLocation.tryParse(idText);
                long count = valuesTag.getLong(idText);
                if (id != null && count > 0 && BuiltInRegistries.FLUID.containsKey(id)) {
                    values.put(BuiltInRegistries.FLUID.get(id), count);
                }
            }
        }

        private Fluid fluidAt(int tank) {
            if (tank < 0) {
                return null;
            }
            List<Fluid> fluids = sortedFluids();
            return tank >= fluids.size() ? null : fluids.get(tank);
        }

        private List<Fluid> sortedFluids() {
            return values.keySet().stream()
                    .sorted(Comparator.comparing(fluid -> BuiltInRegistries.FLUID.getKey(fluid).toString()))
                    .toList();
        }
    }

    private static long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}

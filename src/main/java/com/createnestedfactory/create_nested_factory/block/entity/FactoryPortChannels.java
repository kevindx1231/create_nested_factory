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

/**
 * Persistent, unbounded-in-gameplay transit channels for a factory's six logical ports.
 * Resources move only when an external or room-side capability caller performs an operation;
 * this class never ticks or pushes into adjacent blocks.
 */
public final class FactoryPortChannels {
    private static final int CHANNEL_COUNT = 6;

    /** One small offer starts an otherwise empty item direction; later offers are paid for by real downstream extraction. */
    private static final long INITIAL_ITEM_PRIME_CREDITS = 64L;
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

    /** Clears only fluid handoff state while preserving the established item handoff state. */
    public void clearFluids() {
        for (PortResourceChannel channel : channels) {
            channel.clearFluids();
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
        /** Shared, unbounded-in-gameplay INPUT handoff state for the complete port group. */
        private final FluidLedger inputFluids = new FluidLedger();
        /** Shared, unbounded-in-gameplay OUTPUT handoff state for the complete port group. */
        private final FluidLedger outputFluids = new FluidLedger();
        private long inputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
        private long outputItemCredits = INITIAL_ITEM_PRIME_CREDITS;

        public ItemChannel inputItems() {
            return inputItems;
        }

        public ItemChannel outputItems() {
            return outputItems;
        }

        public FluidLedger inputFluids() {
            return inputFluids;
        }

        public FluidLedger outputFluids() {
            return outputFluids;
        }

        public int fillInputFluids(FluidStack resource, IFluidHandler.FluidAction action) {
            return inputFluids.fill(resource, action);
        }

        public FluidStack drainInputFluid(FluidStack requested, IFluidHandler.FluidAction action) {
            return inputFluids.drain(requested, action);
        }

        public FluidStack drainInputFluid(int maxDrain, IFluidHandler.FluidAction action) {
            return inputFluids.drain(maxDrain, action);
        }

        public int fillOutputFluids(FluidStack resource, IFluidHandler.FluidAction action) {
            return outputFluids.fill(resource, action);
        }

        public FluidStack drainOutputFluids(FluidStack requested, IFluidHandler.FluidAction action) {
            return outputFluids.drain(requested, action);
        }

        public FluidStack drainOutputFluids(int maxDrain, IFluidHandler.FluidAction action) {
            return outputFluids.drain(maxDrain, action);
        }

        public boolean isEmpty() {
            return inputItems.isEmpty() && outputItems.isEmpty()
                    && inputFluids.isEmpty() && outputFluids.isEmpty();
        }

        private void clear() {
            inputItems.clear();
            outputItems.clear();
            clearFluids();
            inputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
            outputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
        }

        private void clearFluids() {
            inputFluids.clear();
            outputFluids.clear();
        }

        private void appendItemsAndDiscardFluids(List<ItemStack> dropped) {
            inputItems.appendAndClear(dropped);
            outputItems.appendAndClear(dropped);
            inputFluids.clear();
            outputFluids.clear();
            inputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
            outputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
        }

        public boolean canAcceptInputItems(long ignoredGameTime) {
            return inputItemCredits > 0;
        }

        public boolean canAcceptInputItemBatch(long ignoredGameTime, List<ItemStack> stacks) {
            long total = totalItemCount(stacks);
            return total > 0 && total <= inputItemCredits && inputItems.canInsertAll(stacks);
        }

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

        public boolean canAcceptOutputItemBatch(long ignoredGameTime, List<ItemStack> stacks) {
            long total = totalItemCount(stacks);
            return total > 0 && total <= outputItemCredits && outputItems.canInsertAll(stacks);
        }

        public boolean insertOutputItemBatch(long ignoredGameTime, List<ItemStack> stacks) {
            if (!canAcceptOutputItemBatch(ignoredGameTime, stacks)) {
                return false;
            }
            long total = totalItemCount(stacks);
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    outputItems.insert(stack, false);
                }
            }
            consumeOutputItems((int) total);
            return true;
        }

        public int inputItemOfferLimit(long ignoredGameTime, ItemStack stack) {
            return offerLimit(inputItemCredits, stack.isEmpty() ? 0 : stack.getCount());
        }

        public int outputItemOfferLimit(long ignoredGameTime, ItemStack stack) {
            return offerLimit(outputItemCredits, stack.isEmpty() ? 0 : stack.getCount());
        }

        public void markInputItemExtracted(int amount) {
            inputItemCredits = safeAdd(inputItemCredits, Math.max(0, amount));
        }

        public void markOutputItemExtracted(int amount) {
            outputItemCredits = safeAdd(outputItemCredits, Math.max(0, amount));
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

        private CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
            tag.put("InputItems", inputItems.write(new CompoundTag(), registries));
            tag.put("OutputItems", outputItems.write(new CompoundTag(), registries));
            tag.put("InputFluids", inputFluids.write(new CompoundTag(), registries));
            tag.put("OutputFluids", outputFluids.write(new CompoundTag(), registries));
            return tag;
        }

        private void read(CompoundTag tag, HolderLookup.Provider registries) {
            inputItems.read(tag.getCompound("InputItems"), registries);
            outputItems.read(tag.getCompound("OutputItems"), registries);
            // Restore the new complete-identity ledgers. Old development fields do not contain
            // an Entries list and are deliberately discarded by the migration.
            CompoundTag inputTag = tag.getCompound("InputFluids");
            CompoundTag outputTag = tag.getCompound("OutputFluids");
            if (inputTag.contains("Entries", Tag.TAG_LIST)) {
                inputFluids.read(inputTag, registries);
            } else {
                inputFluids.clear();
            }
            if (outputTag.contains("Entries", Tag.TAG_LIST)) {
                outputFluids.read(outputTag, registries);
            } else {
                outputFluids.clear();
            }
            inputItemCredits = inputItems.isEmpty() ? INITIAL_ITEM_PRIME_CREDITS : 0L;
            outputItemCredits = outputItems.isEmpty() ? INITIAL_ITEM_PRIME_CREDITS : 0L;
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

    /** Ordered port-group fluid ledger preserving complete FluidStack identity. */
    public static final class FluidLedger {
        private final List<FluidEntry> entries = new ArrayList<>();

        public int tanks() {
            return entries.size();
        }

        public FluidStack fluidInTank(int tank) {
            return tank >= 0 && tank < entries.size() ? entries.get(tank).snapshot() : FluidStack.EMPTY;
        }

        public int fill(FluidStack resource, IFluidHandler.FluidAction action) {
            if (resource == null || resource.isEmpty()) {
                return 0;
            }
            FluidEntry entry = find(resource);
            if (entry == null && entries.size() == Integer.MAX_VALUE) {
                return 0;
            }
            if (entry == null) {
                entry = new FluidEntry(resource.copyWithAmount(0));
                if (action.execute()) {
                    entries.add(entry);
                }
            }
            long current = entry.amount;
            long requested = resource.getAmount();
            if (Long.MAX_VALUE - current < requested) {
                requested = Long.MAX_VALUE - current;
            }
            int accepted = (int) Math.min(requested, Integer.MAX_VALUE);
            if (action.execute() && accepted > 0) {
                entry.amount = current + accepted;
            }
            return accepted;
        }

        public FluidStack drain(FluidStack requested, IFluidHandler.FluidAction action) {
            if (requested == null || requested.isEmpty()) {
                return FluidStack.EMPTY;
            }
            FluidEntry entry = find(requested);
            return entry == null ? FluidStack.EMPTY : drainEntry(entry, requested.getAmount(), action);
        }

        public FluidStack drain(int maxDrain, IFluidHandler.FluidAction action) {
            if (maxDrain <= 0) {
                return FluidStack.EMPTY;
            }
            for (FluidEntry entry : entries) {
                if (entry.amount > 0) {
                    return drainEntry(entry, maxDrain, action);
                }
            }
            return FluidStack.EMPTY;
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        private void clear() {
            entries.clear();
        }

        private FluidEntry find(FluidStack stack) {
            for (FluidEntry entry : entries) {
                if (FluidStack.isSameFluidSameComponents(entry.prototype, stack)) {
                    return entry;
                }
            }
            return null;
        }

        private FluidStack drainEntry(FluidEntry entry, int requested, IFluidHandler.FluidAction action) {
            int drained = (int) Math.min(entry.amount, Math.max(0, requested));
            FluidStack result = entry.prototype.copyWithAmount(drained);
            if (action.execute() && drained > 0) {
                entry.amount -= drained;
                if (entry.amount == 0) {
                    entries.remove(entry);
                }
            }
            return result;
        }

        private CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            for (FluidEntry entry : entries) {
                if (entry.amount <= 0 || entry.prototype.isEmpty()) {
                    continue;
                }
                CompoundTag value = new CompoundTag();
                value.put("Stack", entry.prototype.copyWithAmount(1).saveOptional(registries));
                value.putLong("Amount", entry.amount);
                list.add(value);
            }
            tag.put("Entries", list);
            return tag;
        }

        private void read(CompoundTag tag, HolderLookup.Provider registries) {
            entries.clear();
            for (Tag raw : tag.getList("Entries", Tag.TAG_COMPOUND)) {
                CompoundTag value = (CompoundTag) raw;
                FluidStack prototype = FluidStack.parseOptional(registries, value.getCompound("Stack"));
                long amount = value.getLong("Amount");
                if (!prototype.isEmpty() && amount > 0) {
                    entries.add(new FluidEntry(prototype.copyWithAmount(1), amount));
                }
            }
        }

        private static final class FluidEntry {
            private final FluidStack prototype;
            private long amount;

            private FluidEntry(FluidStack prototype) {
                this.prototype = prototype;
            }

            private FluidEntry(FluidStack prototype, long amount) {
                this.prototype = prototype;
                this.amount = amount;
            }

            private FluidStack snapshot() {
                return prototype.copyWithAmount((int) Math.min(amount, Integer.MAX_VALUE));
            }
        }
    }

    private static long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}

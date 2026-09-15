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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Owns every resource that has crossed one side of a factory boundary but not the other. */
public final class FactoryTransit {
    public static final int FORMAT_VERSION = 5;
    private static final int CHANNEL_COUNT = 6;

    /** One small offer starts an otherwise empty item direction; later offers are paid for by real downstream extraction. */
    private static final long INITIAL_ITEM_PRIME_CREDITS = 64L;
    private static final Map<String, Supplier<? extends TransitParticipant>> PARTICIPANT_FACTORIES =
            new ConcurrentHashMap<>();
    private final PortResourceChannel[] channels = new PortResourceChannel[CHANNEL_COUNT];

    public record DestructionReport(long itemCount, long fluidAmount, long extensionCount) {
        public boolean isEmpty() {
            return itemCount == 0L && fluidAmount == 0L && extensionCount == 0;
        }
    }

    public FactoryTransit() {
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

    /** Stable factory-owned output escrow used by every simulated OUTPUT face. */
    public PortResourceChannel simulatedOutput() {
        return channels[0];
    }

    public boolean isEmpty() {
        for (PortResourceChannel channel : channels) {
            if (!channel.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static void registerParticipant(String id, Supplier<? extends TransitParticipant> factory) {
        if (id == null || id.isBlank() || factory == null) {
            throw new IllegalArgumentException("Transit participant id and factory are required");
        }
        PARTICIPANT_FACTORIES.compute(id, (key, previous) -> {
            if (previous == null) return factory;
            TransitParticipant existing = previous.get();
            TransitParticipant replacement = factory.get();
            if (existing == null || replacement == null || existing.getClass() != replacement.getClass()) {
                throw new IllegalStateException("Conflicting transit participant registration: " + id);
            }
            return previous;
        });
    }

    /** Starts a new learning generation without waiting for already accepted input to drain. */
    public void sealInputGeneration() {
        for (PortResourceChannel channel : channels) channel.sealInputGeneration();
    }

    /** Ends learning without a plan and makes all real input visible to the physical room again. */
    public void restoreLiveInputs() {
        for (PortResourceChannel channel : channels) channel.restoreLiveInputs();
    }

    public boolean hasUnsupportedExtensionState() {
        for (PortResourceChannel channel : channels) {
            if (channel.hasExtensionState()) return true;
        }
        return false;
    }

    /** Compact ownership snapshot for diagnostics; it never mutates or exposes channel state. */
    public String debugSummary() {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < channels.length; index++) {
            PortResourceChannel channel = channels[index];
            if (!channel.isEmpty()) values.add("port" + (index + 1) + "=" + channel.debugSummary());
        }
        return values.isEmpty() ? "empty" : String.join(";", values);
    }

    public List<ItemVariant> outputItemVariants() {
        TreeSet<ItemVariant> variants = new TreeSet<>();
        for (PortResourceChannel channel : channels) variants.addAll(channel.outputItems.variants());
        return List.copyOf(variants);
    }

    public long outputItemAmount(ItemVariant variant) {
        long total = 0L;
        for (PortResourceChannel channel : channels) total = safeAdd(total, channel.outputItems.amount(variant));
        return total;
    }

    public ItemStack extractOutputItem(ItemVariant variant, int amount, boolean simulate) {
        int remaining = Math.max(0, amount);
        ItemStack result = ItemStack.EMPTY;
        for (PortResourceChannel channel : channels) {
            if (remaining <= 0) break;
            ItemStack extracted = channel.outputItems.extract(variant, remaining, simulate);
            if (extracted.isEmpty()) continue;
            if (result.isEmpty()) result = extracted;
            else result.grow(extracted.getCount());
            remaining -= extracted.getCount();
            if (!simulate) channel.markOutputItemExtracted(extracted.getCount());
        }
        return result;
    }

    public List<FluidVariant> outputFluidVariants() {
        TreeSet<FluidVariant> variants = new TreeSet<>();
        for (PortResourceChannel channel : channels) variants.addAll(channel.outputFluids.variants());
        return List.copyOf(variants);
    }

    public long outputFluidAmount(FluidVariant variant) {
        long total = 0L;
        for (PortResourceChannel channel : channels) total = safeAdd(total, channel.outputFluids.amount(variant));
        return total;
    }

    public FluidStack extractOutputFluid(FluidVariant variant, int amount, IFluidHandler.FluidAction action) {
        int remaining = Math.max(0, amount);
        FluidStack result = FluidStack.EMPTY;
        for (PortResourceChannel channel : channels) {
            if (remaining <= 0) break;
            FluidStack request = variant.createStack(remaining);
            FluidStack extracted = channel.outputFluids.drain(request, action);
            if (extracted.isEmpty()) continue;
            if (result.isEmpty()) result = extracted;
            else result.grow(extracted.getAmount());
            remaining -= extracted.getAmount();
        }
        return result;
    }

    /** Discards every pending transit resource, used by destructive configuration replacement. */
    private void clear() {
        for (PortResourceChannel channel : channels) {
            channel.clear();
        }
    }

    /** Atomically counts and destroys all transit ownership for a semantic contract replacement. */
    public DestructionReport destroyAll() {
        long items = 0L;
        long fluids = 0L;
        long extensions = 0L;
        for (PortResourceChannel channel : channels) {
            items = safeAdd(items, channel.inputItems.totalCount());
            items = safeAdd(items, channel.sealedInputItems.totalCount());
            items = safeAdd(items, channel.outputItems.totalCount());
            for (FluidVariant variant : channel.inputFluids.variants()) {
                fluids = safeAdd(fluids, channel.inputFluids.amount(variant));
            }
            for (FluidVariant variant : channel.sealedInputFluids.variants()) {
                fluids = safeAdd(fluids, channel.sealedInputFluids.amount(variant));
            }
            for (FluidVariant variant : channel.outputFluids.variants()) {
                fluids = safeAdd(fluids, channel.outputFluids.amount(variant));
            }
            for (TransitParticipant participant : channel.participants.values()) {
                extensions = safeAdd(extensions, participant.destroyAndCount());
            }
            channel.clear();
        }
        return new DestructionReport(items, fluids, extensions);
    }

    /** Materializes every pending item and discards every pending fluid. */
    public List<ItemStack> drainItemsAndDiscardFluids() {
        List<ItemStack> dropped = new ArrayList<>();
        for (PortResourceChannel channel : channels) {
            channel.appendItemsAndDiscardFluids(dropped);
        }
        return dropped;
    }

    public CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("TransitFormatVersion", FORMAT_VERSION);
        for (int index = 0; index < CHANNEL_COUNT; index++) {
            tag.put("Channel" + index, channels[index].write(new CompoundTag(), registries));
        }
        return tag;
    }

    public void read(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("TransitFormatVersion") != FORMAT_VERSION) {
            clear();
            return;
        }
        for (int index = 0; index < CHANNEL_COUNT; index++) {
            channels[index].read(tag.getCompound("Channel" + index), registries);
        }
    }

    public static final class PortResourceChannel {
        private final ItemChannel inputItems = new ItemChannel();
        /** Input owned before the current learning generation; hidden from the physical room. */
        private final ItemChannel sealedInputItems = new ItemChannel();
        private final ItemChannel outputItems = new ItemChannel();
        /** Shared, unbounded-in-gameplay INPUT handoff state for the complete port group. */
        private final FluidLedger inputFluids = new FluidLedger();
        private final FluidLedger sealedInputFluids = new FluidLedger();
        /** Shared, unbounded-in-gameplay OUTPUT handoff state for the complete port group. */
        private final FluidLedger outputFluids = new FluidLedger();
        private final Map<String, TransitParticipant> participants = new HashMap<>();
        private long inputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
        private long outputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
        private long outputItemCapacity = INITIAL_ITEM_PRIME_CREDITS;
        private long outputFluidCapacity = Long.MAX_VALUE;

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

        public <T extends TransitParticipant> T participant(String id, Class<T> type) {
            if (id == null || id.isBlank() || type == null) {
                throw new IllegalArgumentException("Transit participant id and type are required");
            }
            TransitParticipant participant = participants.get(id);
            if (participant instanceof OpaqueTransitParticipant opaque) {
                TransitParticipant upgraded = createParticipant(id);
                if (!(upgraded instanceof OpaqueTransitParticipant)) {
                    upgraded.read(opaque.writePayload(), opaque.registries());
                    participants.put(id, upgraded);
                    participant = upgraded;
                }
            }
            if (participant == null) {
                participant = createParticipant(id);
                participants.put(id, participant);
            }
            if (!type.isInstance(participant)) {
                throw new IllegalStateException("Transit participant " + id + " is not " + type.getName());
            }
            return type.cast(participant);
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
            if (resource == null || resource.isEmpty()) return 0;
            long available = Math.max(0L, outputFluidCapacity - outputFluids.totalAmount());
            int accepted = (int) Math.min(resource.getAmount(), Math.min(available, Integer.MAX_VALUE));
            return accepted <= 0 ? 0 : outputFluids.fill(resource.copyWithAmount(accepted), action);
        }

        /** Replaces fixed simulated escrow credits with recipe-sized storage, never a per-tick quota. */
        public void configureOutputCapacity(long itemCapacity, long fluidCapacity) {
            outputItemCapacity = Math.max(0L, itemCapacity);
            outputFluidCapacity = Math.max(0L, fluidCapacity);
            outputItemCredits = Math.max(0L, outputItemCapacity - outputItems.totalCount());
        }

        public boolean canAcceptOutputFluidBatch(Map<FluidVariant, Long> fluids) {
            long requested = 0L;
            for (long amount : fluids.values()) {
                if (amount <= 0L) continue;
                requested = safeAdd(requested, amount);
            }
            return requested <= Math.max(0L, outputFluidCapacity - outputFluids.totalAmount());
        }

        public FluidStack drainOutputFluids(FluidStack requested, IFluidHandler.FluidAction action) {
            return outputFluids.drain(requested, action);
        }

        public FluidStack drainOutputFluids(int maxDrain, IFluidHandler.FluidAction action) {
            return outputFluids.drain(maxDrain, action);
        }

        public boolean isEmpty() {
            return inputItems.isEmpty() && sealedInputItems.isEmpty() && outputItems.isEmpty()
                    && inputFluids.isEmpty() && sealedInputFluids.isEmpty() && outputFluids.isEmpty()
                    && participants.values().stream().allMatch(TransitParticipant::isEmpty);
        }

        private boolean hasExtensionState() {
            return participants.values().stream()
                    .anyMatch(TransitParticipant::blocksLearning);
        }

        private String debugSummary() {
            return "inputItems=" + inputItems.values
                    + ", sealedItems=" + sealedInputItems.values
                    + ", outputItems=" + outputItems.values
                    + ", inputFluids=" + inputFluids.debugValues()
                    + ", sealedFluids=" + sealedInputFluids.debugValues()
                    + ", outputFluids=" + outputFluids.debugValues()
                    + ", inputCredits=" + inputItemCredits
                    + ", outputCredits=" + outputItemCredits
                    + ", outputCapacity=" + outputItemCapacity
                    + ", outputFluidCapacity=" + outputFluidCapacity
                    + ", participants=" + participants.keySet();
        }

        private void sealInputGeneration() {
            sealedInputItems.mergeFrom(inputItems);
            sealedInputFluids.mergeFrom(inputFluids);
            participants.values().forEach(TransitParticipant::sealInputGeneration);
            inputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
        }

        private void restoreLiveInputs() {
            inputItems.mergeFrom(sealedInputItems);
            inputFluids.mergeFrom(sealedInputFluids);
            participants.values().forEach(TransitParticipant::restoreLiveInputs);
            inputItemCredits = Math.max(0L, INITIAL_ITEM_PRIME_CREDITS - inputItems.totalCount());
        }

        private void clear() {
            inputItems.clear();
            sealedInputItems.clear();
            outputItems.clear();
            inputFluids.clear();
            sealedInputFluids.clear();
            outputFluids.clear();
            participants.clear();
            inputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
            outputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
            outputItemCapacity = INITIAL_ITEM_PRIME_CREDITS;
            outputFluidCapacity = Long.MAX_VALUE;
        }

        private void appendItemsAndDiscardFluids(List<ItemStack> dropped) {
            inputItems.appendAndClear(dropped);
            sealedInputItems.appendAndClear(dropped);
            outputItems.appendAndClear(dropped);
            inputFluids.clear();
            sealedInputFluids.clear();
            outputFluids.clear();
            participants.values().forEach(TransitParticipant::destroyAndCount);
            participants.clear();
            inputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
            outputItemCredits = INITIAL_ITEM_PRIME_CREDITS;
            outputItemCapacity = INITIAL_ITEM_PRIME_CREDITS;
            outputFluidCapacity = Long.MAX_VALUE;
        }

        public boolean canAcceptInputItems() {
            return inputItemCredits > 0;
        }

        public boolean canAcceptInputItemBatch(List<ItemStack> stacks) {
            long total = totalItemCount(stacks);
            return total > 0 && total <= inputItemCredits && inputItems.canInsertAll(stacks);
        }

        public boolean insertInputItemBatch(List<ItemStack> stacks) {
            if (!canAcceptInputItemBatch(stacks)) {
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

        public boolean canAcceptOutputItems() {
            return outputItemCredits > 0;
        }

        public boolean canAcceptOutputItemBatch(List<ItemStack> stacks) {
            long total = totalItemCount(stacks);
            return total > 0 && total <= outputItemCredits && outputItems.canInsertAll(stacks);
        }

        public boolean insertOutputItemBatch(List<ItemStack> stacks) {
            if (!canAcceptOutputItemBatch(stacks)) {
                return false;
            }
            long total = totalItemCount(stacks);
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    outputItems.insert(stack, false);
                }
            }
            consumeOutputItems(total);
            return true;
        }

        public int inputItemOfferLimit(ItemStack stack) {
            return offerLimit(inputItemCredits, stack.isEmpty() ? 0 : stack.getCount());
        }

        public int outputItemOfferLimit(ItemStack stack) {
            return offerLimit(outputItemCredits, stack.isEmpty() ? 0 : stack.getCount());
        }

        public void markInputItemExtracted(int amount) {
            if (amount > 0) {
                inputItemCredits = Math.max(0L, INITIAL_ITEM_PRIME_CREDITS - inputItems.totalCount());
            }
        }

        public void markOutputItemExtracted(int amount) {
            if (amount > 0) {
                outputItemCredits = Math.max(0L, outputItemCapacity - outputItems.totalCount());
            }
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

        public void consumeOutputItems(long amount) {
            outputItemCredits = Math.max(0L, outputItemCredits - Math.max(0, amount));
        }

        private CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
            tag.put("InputItems", inputItems.write(new CompoundTag(), registries));
            tag.put("SealedInputItems", sealedInputItems.write(new CompoundTag(), registries));
            tag.put("OutputItems", outputItems.write(new CompoundTag(), registries));
            tag.put("InputFluids", inputFluids.write(new CompoundTag(), registries));
            tag.put("SealedInputFluids", sealedInputFluids.write(new CompoundTag(), registries));
            tag.put("OutputFluids", outputFluids.write(new CompoundTag(), registries));
            if (!participants.isEmpty()) {
                CompoundTag extensions = new CompoundTag();
                for (Map.Entry<String, TransitParticipant> entry : participants.entrySet()) {
                    if (!entry.getValue().isEmpty()) {
                        extensions.put(entry.getKey(), entry.getValue().write(registries));
                    }
                }
                if (!extensions.isEmpty()) {
                    tag.put("Extensions", extensions);
                }
            }
            tag.putLong("InputItemCredits", inputItemCredits);
            tag.putLong("OutputItemCredits", outputItemCredits);
            tag.putLong("OutputItemCapacity", outputItemCapacity);
            tag.putLong("OutputFluidCapacity", outputFluidCapacity);
            return tag;
        }

        private void read(CompoundTag tag, HolderLookup.Provider registries) {
            inputItems.read(tag.getCompound("InputItems"), registries);
            sealedInputItems.read(tag.getCompound("SealedInputItems"), registries);
            outputItems.read(tag.getCompound("OutputItems"), registries);
            inputFluids.read(tag.getCompound("InputFluids"), registries);
            sealedInputFluids.read(tag.getCompound("SealedInputFluids"), registries);
            outputFluids.read(tag.getCompound("OutputFluids"), registries);
            participants.clear();
            CompoundTag extensions = tag.getCompound("Extensions");
            for (String key : extensions.getAllKeys()) {
                TransitParticipant participant = createParticipant(key);
                participant.read(extensions.getCompound(key), registries);
                participants.put(key, participant);
            }
            inputItemCredits = Math.clamp(tag.getLong("InputItemCredits"), 0L, INITIAL_ITEM_PRIME_CREDITS);
            outputItemCapacity = tag.contains("OutputItemCapacity")
                    ? Math.max(0L, tag.getLong("OutputItemCapacity")) : INITIAL_ITEM_PRIME_CREDITS;
            outputFluidCapacity = tag.contains("OutputFluidCapacity")
                    ? Math.max(0L, tag.getLong("OutputFluidCapacity")) : Long.MAX_VALUE;
            outputItemCredits = Math.max(0L, outputItemCapacity - outputItems.totalCount());
        }
    }

    private static TransitParticipant createParticipant(String id) {
        Supplier<? extends TransitParticipant> factory = PARTICIPANT_FACTORIES.get(id);
        if (factory == null) return new OpaqueTransitParticipant();
        TransitParticipant participant = factory.get();
        if (participant == null) throw new IllegalStateException("Transit participant factory returned null: " + id);
        return participant;
    }

    /** Retains data for a temporarily unavailable optional integration without exposing mutable NBT. */
    private static final class OpaqueTransitParticipant implements TransitParticipant {
        private CompoundTag payload = new CompoundTag();
        private HolderLookup.Provider registries;

        @Override
        public boolean isEmpty() {
            return payload.isEmpty();
        }

        @Override
        public long destroyAndCount() {
            long count = payload.isEmpty() ? 0L : 1L;
            payload = new CompoundTag();
            return count;
        }

        @Override
        public CompoundTag write(HolderLookup.Provider registries) {
            return payload.copy();
        }

        CompoundTag writePayload() {
            return payload.copy();
        }

        HolderLookup.Provider registries() {
            return registries;
        }

        @Override
        public void read(CompoundTag tag, HolderLookup.Provider registries) {
            payload = tag == null ? new CompoundTag() : tag.copy();
            this.registries = registries;
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

        public long amount(ItemVariant variant) {
            return values.getOrDefault(variant, 0L);
        }

        public long totalCount() {
            long total = 0L;
            for (long value : values.values()) total = safeAdd(total, value);
            return total;
        }

        public List<ItemVariant> variants() {
            return sortedVariants();
        }

        public ItemStack extract(ItemVariant variant, int amount, boolean simulate) {
            if (variant == null || amount <= 0) return ItemStack.EMPTY;
            long current = values.getOrDefault(variant, 0L);
            if (current <= 0) return ItemStack.EMPTY;
            int extracted = (int) Math.min(current, Math.min((long) amount,
                    Math.max(1, variant.prototype().getMaxStackSize())));
            if (!simulate) {
                long remaining = current - extracted;
                if (remaining == 0) values.remove(variant); else values.put(variant, remaining);
            }
            return variant.createStack(extracted);
        }

        private void mergeFrom(ItemChannel source) {
            if (source == this || source.values.isEmpty()) return;
            source.values.forEach((variant, amount) ->
                    values.merge(variant, amount, FactoryTransit::safeAdd));
            source.values.clear();
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
                    values.merge(variant, count, FactoryTransit::safeAdd);
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
                entry = new FluidEntry(resource.copyWithAmount(1));
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

        public List<FluidVariant> variants() {
            List<FluidVariant> result = new ArrayList<>();
            for (FluidEntry entry : entries) {
                if (entry.amount > 0) result.add(FluidVariant.of(entry.prototype));
            }
            result.sort(FluidVariant::compareTo);
            return List.copyOf(result);
        }

        private Map<FluidVariant, Long> debugValues() {
            Map<FluidVariant, Long> result = new HashMap<>();
            for (FluidEntry entry : entries) result.put(FluidVariant.of(entry.prototype), entry.amount);
            return result;
        }

        public long amount(FluidVariant variant) {
            if (variant == null) return 0L;
            for (FluidEntry entry : entries) {
                if (variant.matches(entry.prototype)) return entry.amount;
            }
            return 0L;
        }

        public long totalAmount() {
            long total = 0L;
            for (FluidEntry entry : entries) total = safeAdd(total, entry.amount);
            return total;
        }

        private void mergeFrom(FluidLedger source) {
            if (source == this || source.entries.isEmpty()) return;
            for (FluidEntry entry : List.copyOf(source.entries)) {
                FluidEntry target = find(entry.prototype);
                if (target == null) entries.add(new FluidEntry(entry.prototype.copyWithAmount(1), entry.amount));
                else target.amount = safeAdd(target.amount, entry.amount);
            }
            source.entries.clear();
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



package com.createnestedfactory.create_nested_factory_logistics.chemical;

import com.createnestedfactory.create_nested_factory.block.entity.TransitParticipant;
import mekanism.api.Action;
import mekanism.api.chemical.infuse.InfusionStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Typed transit owner for Mekanism chemical handoff state. */
public final class InfusionLedger implements TransitParticipant {
    public static final String PARTICIPANT_ID = "create_nested_factory_logistics:mekanism_infuse";
    private static final String INPUT_KEY = "InputChemicals";
    private static final String SEALED_INPUTS_KEY = "_SealedInputChemicals";
    private static final String LEARNING_GENERATION_KEY = "_LearningGeneration";
    private static final String LEARNING_TOUCHED_KEY = "_LearningTouched";

    private final Map<String, List<InfusionStack>> values = new HashMap<>();
    private final List<InfusionStack> sealedInputs = new ArrayList<>();
    private boolean learningGeneration;
    private boolean learningTouched;

    InfusionStack peek(String key) {
        List<InfusionStack> entries = values.getOrDefault(key, List.of());
        return entries.isEmpty() ? InfusionStack.EMPTY : entries.get(0).copy();
    }

    InfusionStack insert(String key, InfusionStack stack, Action action) {
        if (stack.isEmpty()) return InfusionStack.EMPTY;
        List<InfusionStack> entries = values.get(key);
        if (entries == null) {
            if (action.execute()) {
                entries = new ArrayList<>();
                entries.add(stack.copy());
                values.put(key, entries);
            }
            return InfusionStack.EMPTY;
        }
        for (int index = 0; index < entries.size(); index++) {
            InfusionStack current = entries.get(index);
            if (current.isTypeEqual(stack)) {
                if (Long.MAX_VALUE - current.getAmount() < stack.getAmount()) return stack;
                if (action.execute()) {
                    entries.set(index, new InfusionStack(current, current.getAmount() + stack.getAmount()));
                }
                return InfusionStack.EMPTY;
            }
        }
        if (action.execute()) entries.add(stack.copy());
        return InfusionStack.EMPTY;
    }

    InfusionStack extract(String key, long amount, Action action) {
        if (amount <= 0) return InfusionStack.EMPTY;
        List<InfusionStack> entries = values.get(key);
        if (entries == null || entries.isEmpty()) return InfusionStack.EMPTY;

        InfusionStack current = entries.get(0);
        long extracted = Math.min(amount, current.getAmount());
        InfusionStack result = new InfusionStack(current, extracted);
        if (action.execute()) {
            long remaining = current.getAmount() - extracted;
            if (remaining <= 0) entries.remove(0);
            else entries.set(0, new InfusionStack(current, remaining));
            if (entries.isEmpty()) values.remove(key);
        }
        return result;
    }

    void markBoundaryTransfer() {
        if (learningGeneration) learningTouched = true;
    }

    @Override
    public boolean isEmpty() {
        return sealedInputs.isEmpty() && values.values().stream().allMatch(List::isEmpty);
    }

    @Override
    public boolean blocksLearning() {
        return learningTouched;
    }

    @Override
    public void sealInputGeneration() {
        mergeInto(sealedInputs, values.remove(INPUT_KEY));
        learningGeneration = true;
        learningTouched = false;
    }

    @Override
    public void restoreLiveInputs() {
        if (!sealedInputs.isEmpty()) {
            List<InfusionStack> inputs = values.computeIfAbsent(INPUT_KEY, ignored -> new ArrayList<>());
            mergeInto(inputs, sealedInputs);
            sealedInputs.clear();
        }
        learningGeneration = false;
        learningTouched = false;
    }

    @Override
    public long destroyAndCount() {
        long count = values.values().stream().filter(entries -> !entries.isEmpty()).count();
        if (!sealedInputs.isEmpty()) count++;
        values.clear();
        sealedInputs.clear();
        learningGeneration = false;
        learningTouched = false;
        return count;
    }

    @Override
    public CompoundTag write(HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        values.forEach((key, entries) -> {
            ListTag list = new ListTag();
            for (InfusionStack stack : entries) {
                if (!stack.isEmpty()) list.add(stack.write(new CompoundTag()));
            }
            if (!list.isEmpty()) {
                CompoundTag section = new CompoundTag();
                section.put("Entries", list);
                root.put(key, section);
            }
        });
        if (!sealedInputs.isEmpty()) {
            CompoundTag section = new CompoundTag();
            section.put("Entries", writeEntries(sealedInputs, registries));
            root.put(SEALED_INPUTS_KEY, section);
        }
        root.putBoolean(LEARNING_GENERATION_KEY, learningGeneration);
        root.putBoolean(LEARNING_TOUCHED_KEY, learningTouched);
        return root;
    }

    @Override
    public void read(CompoundTag root, HolderLookup.Provider registries) {
        values.clear();
        sealedInputs.clear();
        learningGeneration = false;
        learningTouched = false;
        if (root == null) return;
        for (String key : root.getAllKeys()) {
            if (SEALED_INPUTS_KEY.equals(key)) {
                readEntries(root.getCompound(key), registries, sealedInputs);
                continue;
            }
            if (LEARNING_GENERATION_KEY.equals(key) || LEARNING_TOUCHED_KEY.equals(key)) continue;
            CompoundTag section = root.getCompound(key);
            if (!section.contains("Entries", Tag.TAG_LIST)) continue;
            List<InfusionStack> entries = new ArrayList<>();
            readEntries(section, registries, entries);
            if (!entries.isEmpty()) values.put(key, entries);
        }
        learningGeneration = root.getBoolean(LEARNING_GENERATION_KEY);
        learningTouched = learningGeneration && root.getBoolean(LEARNING_TOUCHED_KEY);
    }

    private static ListTag writeEntries(List<InfusionStack> entries, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (InfusionStack stack : entries) if (!stack.isEmpty()) list.add(stack.write(new CompoundTag()));
        return list;
    }

    private static void readEntries(CompoundTag section, HolderLookup.Provider registries,
                                    List<InfusionStack> target) {
        for (Tag raw : section.getList("Entries", Tag.TAG_COMPOUND)) {
            InfusionStack stack = InfusionStack.readFromNBT( (CompoundTag) raw);
            if (!stack.isEmpty()) target.add(stack);
        }
    }

    private static void mergeInto(List<InfusionStack> target, List<InfusionStack> source) {
        if (source == null || source.isEmpty()) return;
        for (InfusionStack incoming : source) {
            if (incoming.isEmpty()) continue;
            boolean merged = false;
            for (int index = 0; index < target.size(); index++) {
                InfusionStack current = target.get(index);
                if (!current.isTypeEqual(incoming)) continue;
                long amount = Math.addExact(current.getAmount(), incoming.getAmount());
                target.set(index, new InfusionStack(current, amount));
                merged = true;
                break;
            }
            if (!merged) target.add(incoming.copy());
        }
    }
}

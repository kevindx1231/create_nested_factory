package com.createnestedfactory.create_nested_factory_logistics.chemical;

import mekanism.api.chemical.ChemicalStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;

import java.util.ArrayList;
import java.util.List;

/** Persistent, shared chemical handoff state backed by the core channel extension tag. */
final class ChemicalLedger {
    private final CompoundTag root;
    private final NestedFactoryBlockEntity factory;

    ChemicalLedger(CompoundTag root, NestedFactoryBlockEntity factory) {
        this.root = root;
        this.factory = factory;
    }

    ChemicalStack peek(String key) {
        List<ChemicalStack> entries = read(key);
        return entries.isEmpty() ? ChemicalStack.EMPTY : entries.getFirst();
    }

    ChemicalStack insert(String key, ChemicalStack stack, mekanism.api.Action action) {
        if (stack.isEmpty()) {
            return ChemicalStack.EMPTY;
        }
        List<ChemicalStack> entries = read(key);
        if (action.execute()) {
            for (int i = 0; i < entries.size(); i++) {
                ChemicalStack current = entries.get(i);
                if (ChemicalStack.isSameChemical(current, stack)) {
                    entries.set(i, current.copyWithAmount(current.getAmount() + stack.getAmount()));
                    write(key, entries);
                    return ChemicalStack.EMPTY;
                }
            }
            entries.add(stack.copy());
            write(key, entries);
        }
        return ChemicalStack.EMPTY;
    }

    ChemicalStack extract(String key, long amount, mekanism.api.Action action) {
        if (amount <= 0) {
            return ChemicalStack.EMPTY;
        }
        List<ChemicalStack> entries = read(key);
        if (entries.isEmpty()) {
            return ChemicalStack.EMPTY;
        }
        ChemicalStack current = entries.getFirst();
        long extracted = Math.min(amount, current.getAmount());
        ChemicalStack result = current.copyWithAmount(extracted);
        if (action.execute()) {
            long remaining = current.getAmount() - extracted;
            if (remaining <= 0) {
                entries.removeFirst();
            } else {
                entries.set(0, current.copyWithAmount(remaining));
            }
            write(key, entries);
        }
        return result;
    }

    private List<ChemicalStack> read(String key) {
        List<ChemicalStack> entries = new ArrayList<>();
        CompoundTag section = root.getCompound(key);
        if (!section.contains("Entries", Tag.TAG_LIST) || factory.getLevel() == null) {
            return entries;
        }
        for (Tag raw : section.getList("Entries", Tag.TAG_COMPOUND)) {
            ChemicalStack stack = ChemicalStack.parseOptional(factory.getLevel().registryAccess(), (CompoundTag) raw);
            if (!stack.isEmpty()) {
                entries.add(stack);
            }
        }
        return entries;
    }

    private void write(String key, List<ChemicalStack> entries) {
        CompoundTag section = new CompoundTag();
        ListTag list = new ListTag();
        if (factory.getLevel() != null) {
            for (ChemicalStack stack : entries) {
                if (!stack.isEmpty()) {
                    list.add(stack.save(factory.getLevel().registryAccess()));
                }
            }
        }
        if (!list.isEmpty()) {
            section.put("Entries", list);
            root.put(key, section);
        } else {
            root.remove(key);
        }
    }
}

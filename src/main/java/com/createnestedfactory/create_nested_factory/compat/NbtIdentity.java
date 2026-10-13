package com.createnestedfactory.create_nested_factory.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.fluids.FluidStack;

/** Deterministic resource keys preserve complete NBT, independent of compound key insertion order. */
public final class NbtIdentity {
    private NbtIdentity() {}
    public static String canonical(Tag tag) {
        if (tag == null) return "{}";
        if (tag instanceof CompoundTag compound) {
            StringBuilder result = new StringBuilder("{");
            compound.getAllKeys().stream().sorted().forEach(key -> result.append(key.length()).append(':')
                    .append(key).append('=').append(canonical(compound.get(key))).append(';'));
            return result.append('}').toString();
        }
        if (tag instanceof ListTag list) {
            StringBuilder result = new StringBuilder("[");
            for (Tag value : list) result.append(canonical(value)).append(';');
            return result.append(']').toString();
        }
        return tag.getId() + ":" + tag;
    }
    public static FluidStack withAmount(FluidStack stack, int amount) {
        FluidStack copy = stack.copy();
        copy.setAmount(amount);
        return copy;
    }
}

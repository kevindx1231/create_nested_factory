package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;

/** Immutable fluid identity: registry fluid plus the complete NBT state. */
public final class FluidVariant implements Comparable<FluidVariant> {
    private final FluidStack prototype;
    private final String canonicalKey;
    private final int hashCode;

    private FluidVariant(FluidStack stack) {
        prototype = com.createnestedfactory.create_nested_factory.compat.NbtIdentity.withAmount(stack, 1);
        canonicalKey = BuiltInRegistries.FLUID.getKey(prototype.getFluid()) + "|" + com.createnestedfactory.create_nested_factory.compat.NbtIdentity.canonical(prototype.getTag());
        hashCode = java.util.Objects.hash(prototype.getFluid(), prototype.getTag());
    }

    public static FluidVariant of(FluidStack stack) {
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Empty FluidStack has no identity");
        return new FluidVariant(stack);
    }

    public static FluidVariant read(HolderLookup.Provider registries, CompoundTag tag) {
        FluidStack stack = FluidStack.loadFluidStackFromNBT(tag);
        return stack.isEmpty() ? null : of(stack);
    }

    public CompoundTag write(HolderLookup.Provider registries) {
        Tag saved = prototype.writeToNBT(new CompoundTag());
        return saved instanceof CompoundTag tag ? tag : new CompoundTag();
    }

    public Fluid fluid() {
        return prototype.getFluid();
    }

    public FluidStack prototype() {
        return prototype.copy();
    }

    public FluidStack createStack(int amount) {
        return com.createnestedfactory.create_nested_factory.compat.NbtIdentity.withAmount(prototype, amount);
    }

    public boolean matches(FluidStack stack) {
        return stack != null && !stack.isEmpty() && prototype.isFluidEqual(stack);
    }

    public String canonicalKey() {
        return canonicalKey;
    }

    @Override
    public int compareTo(FluidVariant other) {
        return canonicalKey.compareTo(other.canonicalKey);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof FluidVariant other
                && prototype.isFluidEqual(other.prototype);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return canonicalKey;
    }
}

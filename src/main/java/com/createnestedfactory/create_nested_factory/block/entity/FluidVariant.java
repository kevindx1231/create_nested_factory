package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/** Immutable fluid identity: registry fluid plus the complete Data Component state. */
public final class FluidVariant implements Comparable<FluidVariant> {
    private final FluidStack prototype;
    private final String canonicalKey;
    private final int hashCode;

    private FluidVariant(FluidStack stack) {
        prototype = stack.copyWithAmount(1);
        canonicalKey = BuiltInRegistries.FLUID.getKey(prototype.getFluid()) + "|" + prototype.getComponentsPatch();
        hashCode = FluidStack.hashFluidAndComponents(prototype);
    }

    public static FluidVariant of(FluidStack stack) {
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Empty FluidStack has no identity");
        return new FluidVariant(stack);
    }

    public static FluidVariant read(HolderLookup.Provider registries, CompoundTag tag) {
        FluidStack stack = FluidStack.parseOptional(registries, tag);
        return stack.isEmpty() ? null : of(stack);
    }

    public CompoundTag write(HolderLookup.Provider registries) {
        Tag saved = prototype.saveOptional(registries);
        return saved instanceof CompoundTag tag ? tag : new CompoundTag();
    }

    public Fluid fluid() {
        return prototype.getFluid();
    }

    public FluidStack prototype() {
        return prototype.copy();
    }

    public FluidStack createStack(int amount) {
        return prototype.copyWithAmount(amount);
    }

    public boolean matches(FluidStack stack) {
        return stack != null && !stack.isEmpty() && FluidStack.isSameFluidSameComponents(prototype, stack);
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
                && FluidStack.isSameFluidSameComponents(prototype, other.prototype);
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

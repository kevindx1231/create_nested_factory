package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Immutable item identity: registry item plus the complete Data Component state. */
public final class ItemVariant implements Comparable<ItemVariant> {
    private final ItemStack prototype;
    private final String canonicalKey;
    private final int hashCode;

    private ItemVariant(ItemStack stack) {
        prototype = stack.copyWithCount(1);
        canonicalKey = BuiltInRegistries.ITEM.getKey(prototype.getItem()) + "|" + prototype.getComponentsPatch();
        hashCode = ItemStack.hashItemAndComponents(prototype);
    }

    public static ItemVariant of(ItemStack stack) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Empty ItemStack cannot be an ItemVariant");
        }
        return new ItemVariant(stack);
    }

    public static ItemVariant read(HolderLookup.Provider registries, CompoundTag tag) {
        ItemStack stack = ItemStack.parseOptional(registries, tag);
        return stack.isEmpty() ? null : of(stack);
    }

    public CompoundTag write(HolderLookup.Provider registries) {
        Tag saved = prototype.saveOptional(registries);
        return saved instanceof CompoundTag tag ? tag : new CompoundTag();
    }

    public Item item() {
        return prototype.getItem();
    }

    public ItemStack prototype() {
        return prototype.copy();
    }

    public ItemStack createStack(int count) {
        return prototype.copyWithCount(count);
    }

    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(prototype, stack);
    }

    @Override
    public int compareTo(ItemVariant other) {
        return canonicalKey.compareTo(other.canonicalKey);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof ItemVariant other
                && ItemStack.isSameItemSameComponents(prototype, other.prototype);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}

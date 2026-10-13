package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Immutable item identity: registry item plus the complete NBT and capability state. */
public final class ItemVariant implements Comparable<ItemVariant> {
    private final ItemStack prototype;
    private final String canonicalKey;
    private final int hashCode;

    private ItemVariant(ItemStack stack) {
        prototype = stack.copyWithCount(1);
        canonicalKey = BuiltInRegistries.ITEM.getKey(prototype.getItem()) + "|" + com.createnestedfactory.create_nested_factory.compat.NbtIdentity.canonical(identityTag(prototype));
        hashCode = java.util.Objects.hash(prototype.getItem(), prototype.getTag());
    }

    private static CompoundTag identityTag(ItemStack stack) {
        CompoundTag data = stack.save(new CompoundTag());
        data.remove("Count");
        return data;
    }

    public static ItemVariant of(ItemStack stack) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Empty ItemStack cannot be an ItemVariant");
        }
        return new ItemVariant(stack);
    }

    /** A tool identity deliberately ignores its mutable damage component. */
    public static ItemVariant toolSignature(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            throw new IllegalArgumentException("A tool signature requires a damageable item");
        }
        ItemStack signature = stack.copyWithCount(1);
        if (signature.getTag() != null) signature.getTag().remove("Damage");
        return new ItemVariant(signature);
    }

    public static ItemVariant read(HolderLookup.Provider registries, CompoundTag tag) {
        ItemStack stack = ItemStack.of(tag);
        return stack.isEmpty() ? null : of(stack);
    }

    public CompoundTag write(HolderLookup.Provider registries) {
        Tag saved = prototype.save(new CompoundTag());
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

    public String canonicalKey() {
        return canonicalKey;
    }

    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameTags(prototype, stack);
    }

    public boolean matchesTool(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamageableItem() && equals(toolSignature(stack));
    }

    @Override
    public int compareTo(ItemVariant other) {
        return canonicalKey.compareTo(other.canonicalKey);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof ItemVariant other
                && ItemStack.isSameItemSameTags(prototype, other.prototype);
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

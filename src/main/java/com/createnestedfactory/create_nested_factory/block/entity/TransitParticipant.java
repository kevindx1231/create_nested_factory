package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Typed owner for an optional resource held by {@link FactoryTransit}.
 *
 * <p>A participant owns its complete lifecycle. Core code never edits its serialized payload,
 * and a resource that has no black-box plan semantics must keep {@link #supportsLearning()}
 * false so learning cannot silently ignore it.</p>
 */
public interface TransitParticipant {
    boolean isEmpty();

    default boolean supportsLearning() {
        return false;
    }

    /**
     * True when this participant currently makes the active learning generation unsafe.
     * Implementations without generation tracking retain the conservative non-empty rule.
     */
    default boolean blocksLearning() {
        return !supportsLearning() && !isEmpty();
    }

    default void sealInputGeneration() {
    }

    default void restoreLiveInputs() {
    }

    /** Clears owned state and returns a user-facing count of destroyed resource buffers. */
    long destroyAndCount();

    CompoundTag write(HolderLookup.Provider registries);

    void read(CompoundTag tag, HolderLookup.Provider registries);
}

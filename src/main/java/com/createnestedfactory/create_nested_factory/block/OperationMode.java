package com.createnestedfactory.create_nested_factory.block;

import net.minecraft.util.StringRepresentable;

import java.util.Locale;

public enum OperationMode implements StringRepresentable {
    CHUNK_LOADED,
    BLACKBOX_DRAINING,
    BLACKBOX_LEARNING,
    BLACKBOX_ACTIVE,
    BLUEPRINT;

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isBlackbox() {
        return this != CHUNK_LOADED;
    }

    public boolean isBlueprint() {
        return this == BLUEPRINT;
    }
}

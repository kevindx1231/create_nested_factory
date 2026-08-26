package com.createnestedfactory.create_nested_factory.block;

import net.minecraft.util.StringRepresentable;

public enum PortMode implements StringRepresentable {
    NONE("none"),
    INPUT("input"),
    OUTPUT("output");

    private final String name;

    PortMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    public PortMode next() {
        return values()[(this.ordinal() + 1) % values().length];
    }
}

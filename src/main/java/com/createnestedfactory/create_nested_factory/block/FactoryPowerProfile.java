package com.createnestedfactory.create_nested_factory.block;

import net.minecraft.nbt.CompoundTag;

public final class FactoryPowerProfile {
    private float generatedSU;
    private float consumedSU;
    private float generatedFE;
    private float consumedFE;

    public FactoryPowerProfile() {
    }

    public FactoryPowerProfile(float generatedSU, float consumedSU, float generatedFE, float consumedFE) {
        this.generatedSU = generatedSU;
        this.consumedSU = consumedSU;
        this.generatedFE = generatedFE;
        this.consumedFE = consumedFE;
    }

    public void set(float generatedSU, float consumedSU, float generatedFE, float consumedFE) {
        this.generatedSU = generatedSU;
        this.consumedSU = consumedSU;
        this.generatedFE = generatedFE;
        this.consumedFE = consumedFE;
    }

    public float generatedSU() {
        return generatedSU;
    }

    public float consumedSU() {
        return consumedSU;
    }

    public float generatedFE() {
        return generatedFE;
    }

    public float consumedFE() {
        return consumedFE;
    }

    public float netSU() {
        return generatedSU - consumedSU;
    }

    public float netFE() {
        return generatedFE - consumedFE;
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("GeneratedSU", generatedSU);
        tag.putFloat("ConsumedSU", consumedSU);
        tag.putFloat("GeneratedFE", generatedFE);
        tag.putFloat("ConsumedFE", consumedFE);
        return tag;
    }

    public void read(CompoundTag tag) {
        generatedSU = tag.getFloat("GeneratedSU");
        consumedSU = tag.getFloat("ConsumedSU");
        generatedFE = tag.getFloat("GeneratedFE");
        consumedFE = tag.getFloat("ConsumedFE");
    }
}

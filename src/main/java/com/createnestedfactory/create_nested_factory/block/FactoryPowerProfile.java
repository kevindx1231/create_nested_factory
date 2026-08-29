package com.createnestedfactory.create_nested_factory.block;

import net.minecraft.nbt.CompoundTag;

public final class FactoryPowerProfile {
    private float generatedSU;
    private float consumedSU;
    private float generatedFE;
    private float consumedFE;
    // True only for profiles scanned after relay stress ports were excluded from generation.
    private boolean generatedSUExcludesRelayStress;

    public FactoryPowerProfile() {
    }

    public FactoryPowerProfile(float generatedSU, float consumedSU, float generatedFE, float consumedFE) {
        this.generatedSU = generatedSU;
        this.consumedSU = consumedSU;
        this.generatedFE = generatedFE;
        this.consumedFE = consumedFE;
        this.generatedSUExcludesRelayStress = true;
    }

    public void set(float generatedSU, float consumedSU, float generatedFE, float consumedFE) {
        this.generatedSU = generatedSU;
        this.consumedSU = consumedSU;
        this.generatedFE = generatedFE;
        this.consumedFE = consumedFE;
        this.generatedSUExcludesRelayStress = true;
    }

    public float generatedSU() {
        return generatedSU;
    }

    public float consumedSU() {
        return consumedSU;
    }

    /**
     * Generation that is safe to use in black-box simulation. Profiles saved before
     * relay stress ports were excluded are treated conservatively as having no known
     * internal generation, preventing historical external input from becoming free power.
     */
    public float internalGeneratedSU() {
        return generatedSUExcludesRelayStress ? generatedSU : 0f;
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

    /**
     * Stress that must cross this factory boundary. Internal surplus is deliberately
     * not exportable: a nested factory can offset its own load, but cannot create
     * stress capacity for its parent without an explicit output port.
     */
    public float externalStressDemandSU() {
        return Math.max(0f, consumedSU - internalGeneratedSU());
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
        tag.putBoolean("GeneratedSUExcludesRelayStress", generatedSUExcludesRelayStress);
        return tag;
    }

    public void read(CompoundTag tag) {
        generatedSU = tag.getFloat("GeneratedSU");
        consumedSU = tag.getFloat("ConsumedSU");
        generatedFE = tag.getFloat("GeneratedFE");
        consumedFE = tag.getFloat("ConsumedFE");
        generatedSUExcludesRelayStress = tag.contains("GeneratedSUExcludesRelayStress")
                && tag.getBoolean("GeneratedSUExcludesRelayStress");
    }
}

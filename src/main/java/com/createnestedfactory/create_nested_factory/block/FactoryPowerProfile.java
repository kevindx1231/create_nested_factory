package com.createnestedfactory.create_nested_factory.block;

import net.minecraft.nbt.CompoundTag;

/** Captured mechanical power profile for live, black-box, and blueprint operation. */
public final class FactoryPowerProfile {
    private float generatedSU;
    private float consumedSU;
    /** Exact external stress demand sampled from the room's de-duplicated stress-port groups. */
    private float measuredExternalStressDemandSU;
    private boolean hasMeasuredExternalStressDemand;
    // True only for profiles scanned after relay stress ports were excluded from generation.
    private boolean generatedSUExcludesRelayStress;

    public FactoryPowerProfile() {
    }

    public FactoryPowerProfile(float generatedSU, float consumedSU) {
        this.generatedSU = generatedSU;
        this.consumedSU = consumedSU;
        this.measuredExternalStressDemandSU = Math.max(0f, consumedSU - generatedSU);
        this.hasMeasuredExternalStressDemand = true;
        this.generatedSUExcludesRelayStress = true;
    }

    public void set(float generatedSU, float consumedSU) {
        set(generatedSU, consumedSU, Math.max(0f, consumedSU - generatedSU));
    }

    public void set(float generatedSU, float consumedSU, float measuredExternalStressDemandSU) {
        this.generatedSU = generatedSU;
        this.consumedSU = consumedSU;
        this.measuredExternalStressDemandSU = Math.max(0f,
                Float.isFinite(measuredExternalStressDemandSU) ? measuredExternalStressDemandSU : 0f);
        this.hasMeasuredExternalStressDemand = true;
        this.generatedSUExcludesRelayStress = true;
    }

    public float generatedSU() {
        return generatedSU;
    }

    public float consumedSU() {
        return consumedSU;
    }

    /**
     * Generation that is safe to use in black-box simulation. Historical profiles that did not
     * exclude relay stress are treated conservatively as having no known internal generation.
     */
    public float internalGeneratedSU() {
        return generatedSUExcludesRelayStress ? generatedSU : 0f;
    }

    public float netSU() {
        return generatedSU - consumedSU;
    }

    /** Stress that must cross this factory boundary. */
    public float externalStressDemandSU() {
        if (hasMeasuredExternalStressDemand) {
            return measuredExternalStressDemandSU;
        }
        return Math.max(0f, consumedSU - internalGeneratedSU());
    }

    public FactoryPowerProfile scaled(float multiplier) {
        float scale = Float.isFinite(multiplier) && multiplier >= 0f ? multiplier : 1.0f;
        FactoryPowerProfile scaled = new FactoryPowerProfile();
        scaled.generatedSU = generatedSU * scale;
        scaled.consumedSU = consumedSU * scale;
        scaled.measuredExternalStressDemandSU = measuredExternalStressDemandSU * scale;
        scaled.hasMeasuredExternalStressDemand = hasMeasuredExternalStressDemand;
        scaled.generatedSUExcludesRelayStress = generatedSUExcludesRelayStress;
        return scaled;
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("GeneratedSU", generatedSU);
        tag.putFloat("ConsumedSU", consumedSU);
        tag.putFloat("MeasuredExternalStressDemandSU", measuredExternalStressDemandSU);
        tag.putBoolean("HasMeasuredExternalStressDemand", hasMeasuredExternalStressDemand);
        tag.putBoolean("GeneratedSUExcludesRelayStress", generatedSUExcludesRelayStress);
        return tag;
    }

    public void read(CompoundTag tag) {
        generatedSU = tag.getFloat("GeneratedSU");
        consumedSU = tag.getFloat("ConsumedSU");
        hasMeasuredExternalStressDemand = tag.contains("HasMeasuredExternalStressDemand")
                && tag.getBoolean("HasMeasuredExternalStressDemand");
        measuredExternalStressDemandSU = hasMeasuredExternalStressDemand
                ? Math.max(0f, tag.getFloat("MeasuredExternalStressDemandSU"))
                : 0f;
        generatedSUExcludesRelayStress = tag.contains("GeneratedSUExcludesRelayStress")
                && tag.getBoolean("GeneratedSUExcludesRelayStress");
    }
}

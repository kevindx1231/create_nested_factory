package com.createnestedfactory.create_nested_factory.blueprint;

import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public final class FactoryRestoreSnapshot {
    private OperationMode operationMode = OperationMode.CHUNK_LOADED;
    private final PortMode[] faceModes = new PortMode[6];
    private final int[] portIds = new int[6];
    private CompoundTag blackbox = new CompoundTag();
    private CompoundTag powerProfile = new CompoundTag();
    private String customName;
    private int energyStored;
    private boolean invalidNested;

    public FactoryRestoreSnapshot() {
        for (int i = 0; i < 6; i++) {
            faceModes[i] = PortMode.NONE;
        }
    }

    public CompoundTag write(CompoundTag tag) {
        tag.putString("OperationMode", operationMode.getSerializedName());
        tag.put("Blackbox", blackbox);
        tag.put("PowerProfile", powerProfile);
        tag.putInt("EnergyStored", energyStored);
        tag.putBoolean("InvalidNested", invalidNested);
        if (customName != null) {
            tag.putString("CustomName", customName);
        }
        for (int i = 0; i < 6; i++) {
            tag.putString("FaceMode" + i, faceModes[i].getSerializedName());
            tag.putInt("PortId" + i, portIds[i]);
        }
        return tag;
    }

    public void read(CompoundTag tag) {
        operationMode = readOperationMode(tag.getString("OperationMode"));
        blackbox = tag.getCompound("Blackbox");
        powerProfile = tag.getCompound("PowerProfile");
        energyStored = tag.getInt("EnergyStored");
        invalidNested = tag.getBoolean("InvalidNested");
        customName = tag.contains("CustomName") ? tag.getString("CustomName") : null;
        for (int i = 0; i < 6; i++) {
            faceModes[i] = readPortMode(tag.getString("FaceMode" + i));
            portIds[i] = tag.getInt("PortId" + i);
        }
    }

    private static OperationMode readOperationMode(String value) {
        try {
            return OperationMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return OperationMode.CHUNK_LOADED;
        }
    }

    private static PortMode readPortMode(String value) {
        try {
            return PortMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PortMode.NONE;
        }
    }

    public OperationMode operationMode() {
        return operationMode;
    }

    public CompoundTag blackbox() {
        return blackbox;
    }

    public CompoundTag powerProfile() {
        return powerProfile;
    }

    public String customName() {
        return customName;
    }

    public int energyStored() {
        return energyStored;
    }

    public boolean invalidNested() {
        return invalidNested;
    }

    public PortMode faceMode(int index) {
        return faceModes[index];
    }

    public int portId(int index) {
        return portIds[index];
    }

    public void operationMode(OperationMode operationMode) {
        this.operationMode = operationMode;
    }

    public void blackbox(CompoundTag blackbox) {
        this.blackbox = blackbox;
    }

    public void powerProfile(CompoundTag powerProfile) {
        this.powerProfile = powerProfile;
    }

    public void customName(String customName) {
        this.customName = customName;
    }

    public void energyStored(int energyStored) {
        this.energyStored = energyStored;
    }

    public void invalidNested(boolean invalidNested) {
        this.invalidNested = invalidNested;
    }

    public void faceMode(int index, PortMode mode) {
        faceModes[index] = mode;
    }

    public void portId(int index, int id) {
        portIds[index] = id;
    }
}

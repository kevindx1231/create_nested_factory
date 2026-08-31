package com.createnestedfactory.create_nested_factory.blueprint;

import com.createnestedfactory.create_nested_factory.block.FactoryFacePortBindings;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * The configuration overwritten while a blueprint is applied.
 *
 * <p>This deliberately excludes energy, item/fluid contents, progress and all other runtime
 * resources. Those values may have been consumed, produced or transferred while the blueprint
 * was active, so restoring them would duplicate resources.</p>
 */
public final class FactoryRestoreSnapshot {
    private final PortMode[] faceModes = new PortMode[6];
    private final int[] portIds = new int[6];
    private CompoundTag blackbox = new CompoundTag();
    private CompoundTag powerProfile = new CompoundTag();

    public FactoryRestoreSnapshot() {
        for (int i = 0; i < 6; i++) {
            faceModes[i] = PortMode.NONE;
        }
    }

    public CompoundTag write(CompoundTag tag) {
        tag.put("Blackbox", blackbox.copy());
        tag.put("PowerProfile", powerProfile.copy());
        for (int i = 0; i < 6; i++) {
            tag.putString("FaceMode" + i, faceModes[i].getSerializedName());
            tag.putInt("PortId" + i, portIds[i]);
        }
        return tag;
    }

    /**
     * Extra fields from older snapshots, including EnergyStored, are intentionally ignored.
     */
    public void read(CompoundTag tag) {
        blackbox = tag.getCompound("Blackbox").copy();
        powerProfile = tag.getCompound("PowerProfile").copy();
        for (int i = 0; i < 6; i++) {
            faceModes[i] = readPortMode(tag.getString("FaceMode" + i));
            portIds[i] = tag.getInt("PortId" + i);
        }
        FactoryFacePortBindings.normalize(faceModes, portIds);
    }

    private static PortMode readPortMode(String value) {
        try {
            return PortMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PortMode.NONE;
        }
    }

    public CompoundTag blackbox() {
        return blackbox.copy();
    }

    public CompoundTag powerProfile() {
        return powerProfile.copy();
    }

    public PortMode faceMode(int index) {
        return faceModes[index];
    }

    public int portId(int index) {
        return portIds[index];
    }

    public void blackbox(CompoundTag blackbox) {
        this.blackbox = blackbox.copy();
    }

    public void powerProfile(CompoundTag powerProfile) {
        this.powerProfile = powerProfile.copy();
    }

    public void faceMode(int index, PortMode mode) {
        faceModes[index] = mode;
    }

    public void portId(int index, int id) {
        portIds[index] = id;
    }
}

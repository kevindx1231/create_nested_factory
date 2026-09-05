package com.createnestedfactory.create_nested_factory.energy;

import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Directional views of the factory's stateless energy boundary.
 *
 * <p>The factory view forwards external input directly into room-side consumers. The port
 * view pulls external input synchronously from one factory-face source. Neither view stores
 * energy or writes an energy buffer.</p>
 */
public final class FactoryEnergyStorage implements IEnergyStorage {
    private final NestedFactoryBlockEntity factory;
    private final boolean inputView;

    private FactoryEnergyStorage(NestedFactoryBlockEntity factory, boolean inputView) {
        this.factory = factory;
        this.inputView = inputView;
    }

    public static FactoryEnergyStorage input(NestedFactoryBlockEntity factory) {
        return new FactoryEnergyStorage(factory, true);
    }

    public static FactoryEnergyStorage output(NestedFactoryBlockEntity factory) {
        return new FactoryEnergyStorage(factory, false);
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        return inputView ? factory.pushEnergyIntoRoom(maxReceive, simulate) : 0;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        return inputView ? 0 : factory.pullEnergyFromExternalFaces(maxExtract, simulate);
    }

    @Override
    public int getEnergyStored() {
        return inputView ? 0 : factory.getAvailableExternalEnergy(NestedFactoryBlockEntity.MAX_FE_PER_TICK);
    }

    @Override
    public int getMaxEnergyStored() {
        return NestedFactoryBlockEntity.MAX_FE_PER_TICK;
    }

    @Override
    public boolean canExtract() {
        return !inputView && factory.canProvideEnergy();
    }

    @Override
    public boolean canReceive() {
        return inputView && factory.canAcceptRoomEnergy();
    }
}

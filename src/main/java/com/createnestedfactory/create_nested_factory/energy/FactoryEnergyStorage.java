package com.createnestedfactory.create_nested_factory.energy;

import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import net.neoforged.neoforge.energy.IEnergyStorage;

public class FactoryEnergyStorage implements IEnergyStorage {
    private final NestedFactoryBlockEntity factory;

    public FactoryEnergyStorage(NestedFactoryBlockEntity factory) {
        this.factory = factory;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (!canReceive()) {
            return 0;
        }
        int space = factory.getEnergyCapacity() - factory.getEnergyStored();
        int received = Math.max(0, Math.min(maxReceive, space));
        if (!simulate) {
            factory.setEnergyStored(factory.getEnergyStored() + received);
        }
        return received;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        if (!canExtract()) {
            return 0;
        }
        int extracted = Math.max(0, Math.min(maxExtract, Math.min(factory.getMaxEnergyExtract(), factory.getEnergyStored())));
        if (!simulate) {
            factory.setEnergyStored(factory.getEnergyStored() - extracted);
        }
        return extracted;
    }

    @Override
    public int getEnergyStored() {
        return factory.getEnergyStored();
    }

    @Override
    public int getMaxEnergyStored() {
        return factory.getEnergyCapacity();
    }

    @Override
    public boolean canExtract() {
        return factory.canExtractEnergy();
    }

    @Override
    public boolean canReceive() {
        return factory.canReceiveEnergy();
    }
}

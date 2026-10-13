package com.createnestedfactory.create_nested_factory_logistics.chemical;

import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import com.createnestedfactory.create_nested_factory_logistics.MekanismExtensionDelegate;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;

/** A cache-safe chemical proxy that rechecks extension binding and redstone on every call. */
public final class MekanismExtensionChemicalHandler implements IChemicalHandler {
    private final MekanismExtensionDelegate<IChemicalHandler> resolver;

    private MekanismExtensionChemicalHandler(NestedExtensionInterfaceBlockEntity extension, Direction queriedSide) {
        resolver = new MekanismExtensionDelegate<>(extension, queriedSide, MekanismChemicalHandler::forExtension);
    }

    public static IChemicalHandler create(NestedExtensionInterfaceBlockEntity extension, Direction side) {
        if (extension == null || !extension.isExternalSideAvailable(side)) {
            return null;
        }
        MekanismExtensionChemicalHandler proxy = new MekanismExtensionChemicalHandler(extension, side);
        return proxy.delegate() == null ? null : proxy;
    }

    private IChemicalHandler delegate() {
        return resolver.get();
    }

    @Override
    public int getChemicalTanks() {
        IChemicalHandler delegate = delegate();
        return delegate == null ? 0 : delegate.getChemicalTanks();
    }

    @Override
    public ChemicalStack getChemicalInTank(int tank) {
        IChemicalHandler delegate = delegate();
        return delegate == null ? ChemicalStack.EMPTY : delegate.getChemicalInTank(tank);
    }

    @Override
    public void setChemicalInTank(int tank, ChemicalStack stack) {
        IChemicalHandler delegate = delegate();
        if (delegate != null) {
            delegate.setChemicalInTank(tank, stack);
        }
    }

    @Override
    public long getChemicalTankCapacity(int tank) {
        IChemicalHandler delegate = delegate();
        return delegate == null ? 0L : delegate.getChemicalTankCapacity(tank);
    }

    @Override
    public boolean isValid(int tank, ChemicalStack stack) {
        IChemicalHandler delegate = delegate();
        return delegate != null && delegate.isValid(tank, stack);
    }

    @Override
    public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {
        IChemicalHandler delegate = delegate();
        return delegate == null ? stack : delegate.insertChemical(tank, stack, action);
    }

    @Override
    public ChemicalStack extractChemical(int tank, long amount, Action action) {
        IChemicalHandler delegate = delegate();
        return delegate == null ? ChemicalStack.EMPTY : delegate.extractChemical(tank, amount, action);
    }

    @Override
    public ChemicalStack insertChemical(ChemicalStack stack, Action action) {
        IChemicalHandler delegate = delegate();
        return delegate == null ? stack : delegate.insertChemical(stack, action);
    }

    @Override
    public ChemicalStack extractChemical(long amount, Action action) {
        IChemicalHandler delegate = delegate();
        return delegate == null ? ChemicalStack.EMPTY : delegate.extractChemical(amount, action);
    }

    @Override
    public ChemicalStack extractChemical(ChemicalStack stack, Action action) {
        IChemicalHandler delegate = delegate();
        return delegate == null ? ChemicalStack.EMPTY : delegate.extractChemical(stack, action);
    }
}

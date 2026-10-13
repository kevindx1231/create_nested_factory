package com.createnestedfactory.create_nested_factory_logistics.chemical;

import com.createnestedfactory.create_nested_factory_logistics.MekanismExtensionDelegate;
import com.createnestedfactory.create_nested_factory_logistics.LiveMekanismEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import mekanism.api.Action;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasHandler;
import net.minecraft.core.Direction;

/** A cache-safe chemical proxy that rechecks extension binding and redstone on every call. */
public final class MekanismExtensionGasHandler implements IGasHandler, LiveMekanismEndpoint {
    private final MekanismExtensionDelegate<MekanismGasHandler> cachedDelegate;

    private MekanismExtensionGasHandler(NestedExtensionInterfaceBlockEntity extension, Direction queriedSide) {
        cachedDelegate = new MekanismExtensionDelegate<>(
                extension, queriedSide, MekanismGasHandler::forExtension);
    }

    public static IGasHandler create(NestedExtensionInterfaceBlockEntity extension, Direction side) {
        if (extension == null || !extension.isExternalSideAvailable(side)) {
            return null;
        }
        MekanismExtensionGasHandler proxy = new MekanismExtensionGasHandler(extension, side);
        return proxy.delegate() == null ? null : proxy;
    }

    private IGasHandler delegate() {
        return cachedDelegate.get();
    }

    @Override
    public boolean isAvailable() {
        var delegate = cachedDelegate.get();
        return delegate != null && delegate.isAvailable();
    }

    @Override
    public int getTanks() {
        IGasHandler delegate = delegate();
        return delegate == null ? 0 : delegate.getTanks();
    }

    @Override
    public GasStack getChemicalInTank(int tank) {
        IGasHandler delegate = delegate();
        return delegate == null ? GasStack.EMPTY : delegate.getChemicalInTank(tank);
    }

    @Override
    public void setChemicalInTank(int tank, GasStack stack) {
        IGasHandler delegate = delegate();
        if (delegate != null) {
            delegate.setChemicalInTank(tank, stack);
        }
    }

    @Override
    public long getTankCapacity(int tank) {
        IGasHandler delegate = delegate();
        return delegate == null ? 0L : delegate.getTankCapacity(tank);
    }

    @Override
    public boolean isValid(int tank, GasStack stack) {
        IGasHandler delegate = delegate();
        return delegate != null && delegate.isValid(tank, stack);
    }

    @Override
    public GasStack insertChemical(int tank, GasStack stack, Action action) {
        IGasHandler delegate = delegate();
        return delegate == null ? stack : delegate.insertChemical(tank, stack, action);
    }

    @Override
    public GasStack extractChemical(int tank, long amount, Action action) {
        IGasHandler delegate = delegate();
        return delegate == null ? GasStack.EMPTY : delegate.extractChemical(tank, amount, action);
    }

    @Override
    public GasStack insertChemical(GasStack stack, Action action) {
        IGasHandler delegate = delegate();
        return delegate == null ? stack : delegate.insertChemical(stack, action);
    }

    @Override
    public GasStack extractChemical(long amount, Action action) {
        IGasHandler delegate = delegate();
        return delegate == null ? GasStack.EMPTY : delegate.extractChemical(amount, action);
    }

    @Override
    public GasStack extractChemical(GasStack stack, Action action) {
        IGasHandler delegate = delegate();
        return delegate == null ? GasStack.EMPTY : delegate.extractChemical(stack, action);
    }
}

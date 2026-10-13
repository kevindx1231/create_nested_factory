package com.createnestedfactory.create_nested_factory_logistics.chemical;

import com.createnestedfactory.create_nested_factory_logistics.MekanismExtensionDelegate;
import com.createnestedfactory.create_nested_factory_logistics.LiveMekanismEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import mekanism.api.Action;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.infuse.IInfusionHandler;
import net.minecraft.core.Direction;

/** A cache-safe chemical proxy that rechecks extension binding and redstone on every call. */
public final class MekanismExtensionInfusionHandler implements IInfusionHandler, LiveMekanismEndpoint {
    private final MekanismExtensionDelegate<MekanismInfusionHandler> cachedDelegate;

    private MekanismExtensionInfusionHandler(NestedExtensionInterfaceBlockEntity extension, Direction queriedSide) {
        cachedDelegate = new MekanismExtensionDelegate<>(
                extension, queriedSide, MekanismInfusionHandler::forExtension);
    }

    public static IInfusionHandler create(NestedExtensionInterfaceBlockEntity extension, Direction side) {
        if (extension == null || !extension.isExternalSideAvailable(side)) {
            return null;
        }
        MekanismExtensionInfusionHandler proxy = new MekanismExtensionInfusionHandler(extension, side);
        return proxy.delegate() == null ? null : proxy;
    }

    private IInfusionHandler delegate() {
        return cachedDelegate.get();
    }

    @Override
    public boolean isAvailable() {
        var delegate = cachedDelegate.get();
        return delegate != null && delegate.isAvailable();
    }

    @Override
    public int getTanks() {
        IInfusionHandler delegate = delegate();
        return delegate == null ? 0 : delegate.getTanks();
    }

    @Override
    public InfusionStack getChemicalInTank(int tank) {
        IInfusionHandler delegate = delegate();
        return delegate == null ? InfusionStack.EMPTY : delegate.getChemicalInTank(tank);
    }

    @Override
    public void setChemicalInTank(int tank, InfusionStack stack) {
        IInfusionHandler delegate = delegate();
        if (delegate != null) {
            delegate.setChemicalInTank(tank, stack);
        }
    }

    @Override
    public long getTankCapacity(int tank) {
        IInfusionHandler delegate = delegate();
        return delegate == null ? 0L : delegate.getTankCapacity(tank);
    }

    @Override
    public boolean isValid(int tank, InfusionStack stack) {
        IInfusionHandler delegate = delegate();
        return delegate != null && delegate.isValid(tank, stack);
    }

    @Override
    public InfusionStack insertChemical(int tank, InfusionStack stack, Action action) {
        IInfusionHandler delegate = delegate();
        return delegate == null ? stack : delegate.insertChemical(tank, stack, action);
    }

    @Override
    public InfusionStack extractChemical(int tank, long amount, Action action) {
        IInfusionHandler delegate = delegate();
        return delegate == null ? InfusionStack.EMPTY : delegate.extractChemical(tank, amount, action);
    }

    @Override
    public InfusionStack insertChemical(InfusionStack stack, Action action) {
        IInfusionHandler delegate = delegate();
        return delegate == null ? stack : delegate.insertChemical(stack, action);
    }

    @Override
    public InfusionStack extractChemical(long amount, Action action) {
        IInfusionHandler delegate = delegate();
        return delegate == null ? InfusionStack.EMPTY : delegate.extractChemical(amount, action);
    }

    @Override
    public InfusionStack extractChemical(InfusionStack stack, Action action) {
        IInfusionHandler delegate = delegate();
        return delegate == null ? InfusionStack.EMPTY : delegate.extractChemical(stack, action);
    }
}

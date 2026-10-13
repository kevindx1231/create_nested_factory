package com.createnestedfactory.create_nested_factory_logistics.chemical;

import com.createnestedfactory.create_nested_factory_logistics.MekanismExtensionDelegate;
import com.createnestedfactory.create_nested_factory_logistics.LiveMekanismEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import mekanism.api.Action;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.pigment.IPigmentHandler;
import net.minecraft.core.Direction;

/** A cache-safe chemical proxy that rechecks extension binding and redstone on every call. */
public final class MekanismExtensionPigmentHandler implements IPigmentHandler, LiveMekanismEndpoint {
    private final MekanismExtensionDelegate<MekanismPigmentHandler> cachedDelegate;

    private MekanismExtensionPigmentHandler(NestedExtensionInterfaceBlockEntity extension, Direction queriedSide) {
        cachedDelegate = new MekanismExtensionDelegate<>(
                extension, queriedSide, MekanismPigmentHandler::forExtension);
    }

    public static IPigmentHandler create(NestedExtensionInterfaceBlockEntity extension, Direction side) {
        if (extension == null || !extension.isExternalSideAvailable(side)) {
            return null;
        }
        MekanismExtensionPigmentHandler proxy = new MekanismExtensionPigmentHandler(extension, side);
        return proxy.delegate() == null ? null : proxy;
    }

    private IPigmentHandler delegate() {
        return cachedDelegate.get();
    }

    @Override
    public boolean isAvailable() {
        var delegate = cachedDelegate.get();
        return delegate != null && delegate.isAvailable();
    }

    @Override
    public int getTanks() {
        IPigmentHandler delegate = delegate();
        return delegate == null ? 0 : delegate.getTanks();
    }

    @Override
    public PigmentStack getChemicalInTank(int tank) {
        IPigmentHandler delegate = delegate();
        return delegate == null ? PigmentStack.EMPTY : delegate.getChemicalInTank(tank);
    }

    @Override
    public void setChemicalInTank(int tank, PigmentStack stack) {
        IPigmentHandler delegate = delegate();
        if (delegate != null) {
            delegate.setChemicalInTank(tank, stack);
        }
    }

    @Override
    public long getTankCapacity(int tank) {
        IPigmentHandler delegate = delegate();
        return delegate == null ? 0L : delegate.getTankCapacity(tank);
    }

    @Override
    public boolean isValid(int tank, PigmentStack stack) {
        IPigmentHandler delegate = delegate();
        return delegate != null && delegate.isValid(tank, stack);
    }

    @Override
    public PigmentStack insertChemical(int tank, PigmentStack stack, Action action) {
        IPigmentHandler delegate = delegate();
        return delegate == null ? stack : delegate.insertChemical(tank, stack, action);
    }

    @Override
    public PigmentStack extractChemical(int tank, long amount, Action action) {
        IPigmentHandler delegate = delegate();
        return delegate == null ? PigmentStack.EMPTY : delegate.extractChemical(tank, amount, action);
    }

    @Override
    public PigmentStack insertChemical(PigmentStack stack, Action action) {
        IPigmentHandler delegate = delegate();
        return delegate == null ? stack : delegate.insertChemical(stack, action);
    }

    @Override
    public PigmentStack extractChemical(long amount, Action action) {
        IPigmentHandler delegate = delegate();
        return delegate == null ? PigmentStack.EMPTY : delegate.extractChemical(amount, action);
    }

    @Override
    public PigmentStack extractChemical(PigmentStack stack, Action action) {
        IPigmentHandler delegate = delegate();
        return delegate == null ? PigmentStack.EMPTY : delegate.extractChemical(stack, action);
    }
}

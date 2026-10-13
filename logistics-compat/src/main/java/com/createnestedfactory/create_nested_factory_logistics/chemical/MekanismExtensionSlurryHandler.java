package com.createnestedfactory.create_nested_factory_logistics.chemical;

import com.createnestedfactory.create_nested_factory_logistics.MekanismExtensionDelegate;
import com.createnestedfactory.create_nested_factory_logistics.LiveMekanismEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import mekanism.api.Action;
import mekanism.api.chemical.slurry.SlurryStack;
import mekanism.api.chemical.slurry.ISlurryHandler;
import net.minecraft.core.Direction;

/** A cache-safe chemical proxy that rechecks extension binding and redstone on every call. */
public final class MekanismExtensionSlurryHandler implements ISlurryHandler, LiveMekanismEndpoint {
    private final MekanismExtensionDelegate<MekanismSlurryHandler> cachedDelegate;

    private MekanismExtensionSlurryHandler(NestedExtensionInterfaceBlockEntity extension, Direction queriedSide) {
        cachedDelegate = new MekanismExtensionDelegate<>(
                extension, queriedSide, MekanismSlurryHandler::forExtension);
    }

    public static ISlurryHandler create(NestedExtensionInterfaceBlockEntity extension, Direction side) {
        if (extension == null || !extension.isExternalSideAvailable(side)) {
            return null;
        }
        MekanismExtensionSlurryHandler proxy = new MekanismExtensionSlurryHandler(extension, side);
        return proxy.delegate() == null ? null : proxy;
    }

    private ISlurryHandler delegate() {
        return cachedDelegate.get();
    }

    @Override
    public boolean isAvailable() {
        var delegate = cachedDelegate.get();
        return delegate != null && delegate.isAvailable();
    }

    @Override
    public int getTanks() {
        ISlurryHandler delegate = delegate();
        return delegate == null ? 0 : delegate.getTanks();
    }

    @Override
    public SlurryStack getChemicalInTank(int tank) {
        ISlurryHandler delegate = delegate();
        return delegate == null ? SlurryStack.EMPTY : delegate.getChemicalInTank(tank);
    }

    @Override
    public void setChemicalInTank(int tank, SlurryStack stack) {
        ISlurryHandler delegate = delegate();
        if (delegate != null) {
            delegate.setChemicalInTank(tank, stack);
        }
    }

    @Override
    public long getTankCapacity(int tank) {
        ISlurryHandler delegate = delegate();
        return delegate == null ? 0L : delegate.getTankCapacity(tank);
    }

    @Override
    public boolean isValid(int tank, SlurryStack stack) {
        ISlurryHandler delegate = delegate();
        return delegate != null && delegate.isValid(tank, stack);
    }

    @Override
    public SlurryStack insertChemical(int tank, SlurryStack stack, Action action) {
        ISlurryHandler delegate = delegate();
        return delegate == null ? stack : delegate.insertChemical(tank, stack, action);
    }

    @Override
    public SlurryStack extractChemical(int tank, long amount, Action action) {
        ISlurryHandler delegate = delegate();
        return delegate == null ? SlurryStack.EMPTY : delegate.extractChemical(tank, amount, action);
    }

    @Override
    public SlurryStack insertChemical(SlurryStack stack, Action action) {
        ISlurryHandler delegate = delegate();
        return delegate == null ? stack : delegate.insertChemical(stack, action);
    }

    @Override
    public SlurryStack extractChemical(long amount, Action action) {
        ISlurryHandler delegate = delegate();
        return delegate == null ? SlurryStack.EMPTY : delegate.extractChemical(amount, action);
    }

    @Override
    public SlurryStack extractChemical(SlurryStack stack, Action action) {
        ISlurryHandler delegate = delegate();
        return delegate == null ? SlurryStack.EMPTY : delegate.extractChemical(stack, action);
    }
}

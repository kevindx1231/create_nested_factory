package com.createnestedfactory.create_nested_factory_logistics.heat;

import com.createnestedfactory.create_nested_factory_logistics.MekanismExtensionDelegate;
import com.createnestedfactory.create_nested_factory_logistics.LiveMekanismEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import mekanism.api.heat.HeatAPI;
import mekanism.api.heat.IHeatHandler;
import net.minecraft.core.Direction;

/** A cache-safe heat proxy that rechecks extension binding and redstone on every call. */
public final class MekanismExtensionHeatHandler implements IHeatHandler, LiveMekanismEndpoint {
    private final MekanismExtensionDelegate<MekanismHeatHandler> cachedDelegate;

    private MekanismExtensionHeatHandler(NestedExtensionInterfaceBlockEntity extension, Direction queriedSide) {
        cachedDelegate = new MekanismExtensionDelegate<>(
                extension, queriedSide, MekanismHeatHandler::forExtension);
    }

    public static IHeatHandler create(NestedExtensionInterfaceBlockEntity extension, Direction side) {
        if (extension == null || !extension.isExternalSideAvailable(side)) {
            return null;
        }
        MekanismExtensionHeatHandler proxy = new MekanismExtensionHeatHandler(extension, side);
        return proxy.delegate() == null ? null : proxy;
    }

    private IHeatHandler delegate() {
        return cachedDelegate.get();
    }

    @Override
    public boolean isAvailable() {
        var delegate = cachedDelegate.get();
        return delegate != null && delegate.isAvailable();
    }

    @Override
    public int getHeatCapacitorCount() {
        IHeatHandler delegate = delegate();
        return delegate == null ? 0 : delegate.getHeatCapacitorCount();
    }

    @Override
    public double getTemperature(int capacitor) {
        IHeatHandler delegate = delegate();
        return delegate == null ? HeatAPI.AMBIENT_TEMP : delegate.getTemperature(capacitor);
    }

    @Override
    public double getInverseConduction(int capacitor) {
        IHeatHandler delegate = delegate();
        return delegate == null ? HeatAPI.DEFAULT_INVERSE_CONDUCTION
                : delegate.getInverseConduction(capacitor);
    }

    @Override
    public double getHeatCapacity(int capacitor) {
        IHeatHandler delegate = delegate();
        return delegate == null ? HeatAPI.DEFAULT_HEAT_CAPACITY : delegate.getHeatCapacity(capacitor);
    }

    @Override
    public void handleHeat(int capacitor, double transfer) {
        IHeatHandler delegate = delegate();
        if (delegate != null) {
            delegate.handleHeat(capacitor, transfer);
        }
    }
}

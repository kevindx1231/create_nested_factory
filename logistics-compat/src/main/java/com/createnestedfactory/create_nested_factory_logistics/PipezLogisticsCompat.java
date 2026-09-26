package com.createnestedfactory.create_nested_factory_logistics;

import com.createnestedfactory.create_nested_factory.block.entity.FluidNetworkEndpointResolver;
import com.createnestedfactory.create_nested_factory_logistics.pipez.PipezFluidNetworkAdapter;

/** Pipez initialization kept independent from Mekanism's API and class loader. */
public final class PipezLogisticsCompat {
    private PipezLogisticsCompat() {
    }

    public static void initialize() {
        FluidNetworkEndpointResolver.registerAdapter(PipezFluidNetworkAdapter.INSTANCE);
    }
}

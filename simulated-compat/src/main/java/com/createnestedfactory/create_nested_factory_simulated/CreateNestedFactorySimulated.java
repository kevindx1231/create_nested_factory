package com.createnestedfactory.create_nested_factory_simulated;

import com.createnestedfactory.create_nested_factory.FactoryReturnAnchors;
import com.createnestedfactory.create_nested_factory.FactoryPassageNavigation;

/** Entry point for the bundled Simulated/Sable compatibility implementation. */
public final class CreateNestedFactorySimulated {
    private CreateNestedFactorySimulated() {
    }

    public static void initialize() {
        SableFactoryReturnAnchorAdapter.registerEvents();
        FactoryReturnAnchors.register(SableFactoryReturnAnchorAdapter.INSTANCE);
        SableFactoryPassageAdapter.initialize();
        FactoryPassageNavigation.register(SableFactoryPassageAdapter.INSTANCE);
    }
}

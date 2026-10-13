package com.createnestedfactory.create_nested_factory_logistics;

import com.createnestedfactory.create_nested_factory.block.entity.FactoryLogicalPortEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import net.minecraft.core.Direction;

import java.util.function.Function;

/** Reuses a delegate while checking the identity, routing and redstone policy on every operation. */
public final class MekanismExtensionDelegate<T extends LiveMekanismEndpoint> {
    private final NestedExtensionInterfaceBlockEntity extension;
    private final Direction side;
    private final Function<FactoryLogicalPortEndpoint, T> create;
    private FactoryLogicalPortEndpoint binding;
    private T delegate;

    public MekanismExtensionDelegate(NestedExtensionInterfaceBlockEntity extension, Direction side,
                                    Function<FactoryLogicalPortEndpoint, T> create) {
        this.extension = extension;
        this.side = side;
        this.create = create;
    }

    public T get() {
        FactoryLogicalPortEndpoint endpoint = extension.resolvePort(side);
        if (endpoint == null || !endpoint.isConfigured() || endpoint.factory().isRemoved()
                || !endpoint.factory().isLiveResourceTransferMode()) {
            binding = null;
            delegate = null;
            return null;
        }
        if (delegate == null || !endpoint.equals(binding)) {
            binding = endpoint;
            delegate = create.apply(endpoint);
        }
        return delegate;
    }
}

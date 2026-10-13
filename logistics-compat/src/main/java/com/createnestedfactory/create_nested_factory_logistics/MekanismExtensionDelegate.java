package com.createnestedfactory.create_nested_factory_logistics;

import com.createnestedfactory.create_nested_factory.block.entity.FactoryLogicalPortEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import net.minecraft.core.Direction;

import java.util.function.Function;

/** Reuses a delegate without retaining authorization across binding or redstone changes. */
public final class MekanismExtensionDelegate<T> {
    private final NestedExtensionInterfaceBlockEntity extension;
    private final Direction side;
    private final Function<FactoryLogicalPortEndpoint, T> create;
    private FactoryLogicalPortEndpoint endpoint;
    private T handler;

    public MekanismExtensionDelegate(NestedExtensionInterfaceBlockEntity extension, Direction side,
                                     Function<FactoryLogicalPortEndpoint, T> create) {
        this.extension = extension;
        this.side = side;
        this.create = create;
    }

    public T get() {
        FactoryLogicalPortEndpoint current = extension.isRemoved() ? null : extension.resolvePort(side);
        if (current == null || !current.isConfigured() || current.factory().isRemoved()
                || !current.factory().isLiveResourceTransferMode()) {
            endpoint = null;
            handler = null;
            return null;
        }
        if (!current.equals(endpoint) || handler == null) {
            endpoint = current;
            handler = create.apply(current);
        }
        return handler;
    }
}

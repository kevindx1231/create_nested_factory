package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.block.PortMode;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;
import java.util.Objects;

/** Immutable identity and routing snapshot for one logical exterior factory port. */
public record FactoryLogicalPortEndpoint(NestedFactoryBlockEntity factory, Direction face,
                                         PortMode mode, int portId) {
    public FactoryLogicalPortEndpoint {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(mode, "mode");
    }

    public boolean isConfigured() {
        return mode != PortMode.NONE;
    }

    public boolean stillMatches() {
        return factory.getFaceMode(face) == mode && factory.getPortId(face) == portId;
    }

    public IItemHandler extensionItemHandler() {
        return factory.getItemHandlerForExtension(face);
    }

    public IFluidHandler extensionFluidHandler() {
        return factory.getFluidHandlerForExtension(face);
    }

    public boolean acceptUnpackedItems(List<ItemStack> items, boolean simulate) {
        return factory.acceptUnpackedItemsFromExtension(face, items, simulate);
    }
}

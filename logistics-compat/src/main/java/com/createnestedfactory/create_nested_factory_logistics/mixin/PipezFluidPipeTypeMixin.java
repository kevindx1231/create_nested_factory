package com.createnestedfactory.create_nested_factory_logistics.mixin;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes Pipez compatible with demand-driven fluid sources such as nested factory INPUT ports.
 * Pipez normally requires getFluidInTank() to return a non-empty sample before it calls drain;
 * the fallback asks the source for a simulated sample by amount and leaves the actual transfer
 * path, filtering and rate limiting untouched.
 */
@Mixin(targets = "de.maxhenkel.pipez.blocks.tileentity.types.FluidPipeType")
public abstract class PipezFluidPipeTypeMixin {
    @Redirect(
            method = {"insertEqually", "insertOrdered"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/fluids/capability/IFluidHandler;getFluidInTank(I)Lnet/neoforged/neoforge/fluids/FluidStack;"
            )
    )
    private FluidStack createNestedFactoryLogistics$discoverDemandDrivenFluid(
            IFluidHandler source, int tank) {
        FluidStack visible = source.getFluidInTank(tank);
        if (!visible.isEmpty()) {
            return visible;
        }
        // SIMULATE is essential: this call only discovers the fluid type. Pipez later adjusts
        // the amount to its configured rate and performs its normal transfer operation.
        FluidStack discovered = source.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
        return discovered == null ? FluidStack.EMPTY : discovered;
    }
}

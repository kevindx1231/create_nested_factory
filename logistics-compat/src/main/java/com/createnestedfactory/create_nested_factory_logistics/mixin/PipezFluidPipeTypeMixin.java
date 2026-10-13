package com.createnestedfactory.create_nested_factory_logistics.mixin;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes Pipez compatible with demand-driven fluid sources such as nested factory INPUT ports.
 * Pipez normally requires getFluidInTank() to return a non-empty sample before it calls drain;
 * the fallback asks the source for a simulated sample by amount and leaves the actual transfer
 * path, filtering and rate limiting untouched.
 */
@Pseudo
@Mixin(targets = "de.maxhenkel.pipez.blocks.tileentity.types.FluidPipeType", remap = false)
public abstract class PipezFluidPipeTypeMixin {
    @Redirect(
            method = {"insertEqually", "insertOrdered"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/fluids/capability/IFluidHandler;getFluidInTank(I)Lnet/minecraftforge/fluids/FluidStack;"
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

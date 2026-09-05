package com.createnestedfactory.create_nested_factory_simulated.mixin;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SubLevelAssemblyHelper.class)
public abstract class SubLevelAssemblyHelperMixin {
    @Inject(method = "assembleBlocks", at = @At("HEAD"), cancellable = true)
    private static void createNestedFactorySimulated$disableFactorySpaceAssembly(
            final ServerLevel level,
            final BlockPos anchor,
            final Iterable<BlockPos> blocks,
            final BoundingBox3ic bounds,
            final CallbackInfoReturnable<ServerSubLevel> cir) {
        if (level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            cir.setReturnValue(null);
        }
    }
}

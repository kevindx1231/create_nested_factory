package com.createnestedfactory.create_nested_factory_simulated.mixin;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity")
public abstract class SimulatedPhysicsAssemblerBlockEntityMixin {
    @Inject(method = "assembleOrDisassemble", at = @At("HEAD"), cancellable = true)
    private void createNestedFactorySimulated$disableFactorySpaceAssembly(final CallbackInfo ci) {
        Object self = this;
        try {
            ServerLevel level = ((net.minecraft.world.level.block.entity.BlockEntity) self).getLevel()
                    instanceof ServerLevel serverLevel ? serverLevel : null;
            if (level != null && level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
                ci.cancel();
            }
        } catch (RuntimeException ignored) {
            // A missing or incompatible optional block entity must not affect normal loading.
        }
    }
}

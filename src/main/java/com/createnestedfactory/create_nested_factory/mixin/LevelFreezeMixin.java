package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.PocketFreezeHooks;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Wrap the complete redirected operation, including third-party profiling, after default-priority mixins. */
@Mixin(value = Level.class, priority = 900)
abstract class LevelFreezeMixin {
    @WrapWithCondition(method = "tickBlockEntities()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private boolean createNestedFactory$allowBlockEntityTick(TickingBlockEntity ticker) {
        return !PocketFreezeHooks.isFrozen((Level) (Object) this, ticker.getPos());
    }
}

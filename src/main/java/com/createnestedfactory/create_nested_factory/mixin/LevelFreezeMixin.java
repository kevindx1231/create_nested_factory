package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.PocketFreezeHooks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Level.class)
abstract class LevelFreezeMixin {
    @Redirect(method = "tickBlockEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void createNestedFactory$freezeBlockEntity(TickingBlockEntity ticker) {
        Level level = (Level) (Object) this;
        if (!PocketFreezeHooks.isFrozen(level, ticker.getPos())) {
            ticker.tick();
        }
    }
}

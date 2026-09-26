package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.PocketFreezeHooks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelTicks.class)
abstract class LevelTicksFreezeMixin<T> {
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void createNestedFactory$captureFrozenTick(ScheduledTick<T> tick, CallbackInfo callback) {
        if (PocketFreezeHooks.captureScheduledTick((LevelTicks<?>) (Object) this, tick)) {
            callback.cancel();
        }
    }
}

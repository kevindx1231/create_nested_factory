package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.PocketFreezeHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerLevelFreezeMixin {
    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void createNestedFactory$captureBlockEvent(BlockPos pos, Block block, int paramA, int paramB,
                                                       CallbackInfo callback) {
        if (PocketFreezeHooks.captureBlockEvent((ServerLevel) (Object) this,
                pos, block, paramA, paramB)) {
            callback.cancel();
        }
    }

    @Inject(method = "tickPrecipitation", at = @At("HEAD"), cancellable = true)
    private void createNestedFactory$freezePrecipitation(BlockPos pos, CallbackInfo callback) {
        if (PocketFreezeHooks.isFrozen((ServerLevel) (Object) this, pos)) {
            callback.cancel();
        }
    }

    @Inject(method = "lambda$tick$2", at = @At("HEAD"), cancellable = true)
    private void createNestedFactory$freezeEntity(TickRateManager tickRateManager, ProfilerFiller profiler,
                                                   Entity entity, CallbackInfo callback) {
        if (!(entity instanceof ServerPlayer)
                && PocketFreezeHooks.intersectsFrozenRoom((ServerLevel) (Object) this, entity.getBoundingBox())) {
            callback.cancel();
        }
    }
}

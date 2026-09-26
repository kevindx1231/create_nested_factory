package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.PocketFreezeHooks;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps random ticks out of frozen factory rooms while allowing optimization mods to wrap the
 * same calls. The lower priority lets Lithium install its redirect first; MixinExtras then wraps
 * that redirected operation instead of competing for ownership of the invocation.
 */
@Mixin(value = ServerLevel.class, priority = 900)
abstract class ServerLevelRandomTickFreezeMixin {
    @WrapWithCondition(
            method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"
            )
    )
    private boolean createNestedFactory$allowBlockRandomTick(BlockState state, ServerLevel level,
                                                             BlockPos pos, RandomSource random) {
        return !PocketFreezeHooks.isFrozen(level, pos);
    }

    @WrapWithCondition(
            method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;randomTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"
            )
    )
    private boolean createNestedFactory$allowFluidRandomTick(FluidState state, Level level,
                                                             BlockPos pos, RandomSource random) {
        return !PocketFreezeHooks.isFrozen(level, pos);
    }
}

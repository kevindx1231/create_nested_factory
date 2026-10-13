package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.PocketFreezeHooks;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** In 1.20.1 weather updates are embedded in tickChunk rather than tickPrecipitation. */
@Mixin(ServerLevel.class)
abstract class ServerLevelWeatherFreezeMixin {
    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean createNestedFactory$allowWeatherPlacement(ServerLevel level, BlockPos pos, BlockState state,
                                                               Operation<Boolean> original) {
        return !PocketFreezeHooks.isFrozen(level, pos) && original.call(level, pos, state);
    }

    @WrapWithCondition(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;handlePrecipitation(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/biome/Biome$Precipitation;)V"))
    private boolean createNestedFactory$allowPrecipitation(Block block, BlockState state, Level level,
                                                          BlockPos pos, Biome.Precipitation precipitation) {
        return !PocketFreezeHooks.isFrozen(level, pos);
    }
}

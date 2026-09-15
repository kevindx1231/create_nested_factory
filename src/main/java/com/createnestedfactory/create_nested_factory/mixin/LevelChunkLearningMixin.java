package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.block.entity.PocketLearningObserver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkLearningMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void createNestedFactory$observeLearningMutation(BlockPos pos, BlockState state, boolean moved,
                                                             CallbackInfoReturnable<BlockState> callback) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ServerLevel serverLevel) {
            BlockState previous = callback.getReturnValue();
            if (previous != null && previous != state) {
                PocketLearningObserver.onBlockChanged(serverLevel, pos, previous, state);
            }
        }
    }
}

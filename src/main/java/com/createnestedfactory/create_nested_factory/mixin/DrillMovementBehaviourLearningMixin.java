package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.block.entity.DrillProductionEvent;
import com.createnestedfactory.create_nested_factory.block.entity.PocketLearningObserver;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import com.simibubi.create.content.kinetics.drill.DrillMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes real block destruction performed by drills mounted on moving contraptions. */
@Mixin(BlockBreakingMovementBehaviour.class)
abstract class DrillMovementBehaviourLearningMixin {
    @Unique
    private BlockState createNestedFactory$capturedState = Blocks.AIR.defaultBlockState();

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void createNestedFactory$captureMovingDrillTarget(MovementContext context, BlockPos pos,
                                                              CallbackInfo callback) {
        createNestedFactory$capturedState = (Object) this instanceof DrillMovementBehaviour
                ? context.world.getBlockState(pos)
                : Blocks.AIR.defaultBlockState();
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void createNestedFactory$observeMovingDrill(MovementContext context, BlockPos pos,
                                                        CallbackInfo callback) {
        BlockState brokenState = createNestedFactory$capturedState;
        createNestedFactory$capturedState = Blocks.AIR.defaultBlockState();
        if (!((Object) this instanceof DrillMovementBehaviour)
                || brokenState.isAir()
                || !(context.world instanceof ServerLevel serverLevel)
                || serverLevel.getBlockState(pos).equals(brokenState)) {
            return;
        }
        PocketLearningObserver.onDrillProduction(serverLevel,
                new DrillProductionEvent(pos, brokenState, DrillProductionEvent.Path.CONTRAPTION));
    }
}

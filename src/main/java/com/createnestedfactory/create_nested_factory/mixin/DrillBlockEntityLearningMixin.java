package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.block.entity.DrillProductionEvent;
import com.createnestedfactory.create_nested_factory.block.entity.PocketLearningObserver;
import com.simibubi.create.content.kinetics.drill.DrillBlock;
import com.simibubi.create.content.kinetics.drill.DrillBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bridges both stationary Create drill paths into the source evidence system. */
@Mixin(DrillBlockEntity.class)
abstract class DrillBlockEntityLearningMixin {
    @Unique
    private boolean createNestedFactory$optimizedThisCycle;

    @Inject(method = "onBlockBroken", at = @At("HEAD"))
    private void createNestedFactory$beginDrillCycle(BlockState state, CallbackInfo callback) {
        createNestedFactory$optimizedThisCycle = false;
    }

    @Inject(method = "optimiseCobbleGen", at = @At("RETURN"))
    private void createNestedFactory$observeOptimizedGenerator(BlockState state,
                                                               CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ()) return;
        createNestedFactory$optimizedThisCycle = true;
        createNestedFactory$publish(state, DrillProductionEvent.Path.CREATE_OPTIMIZED_GENERATOR, false);
    }

    @Inject(method = "onBlockBroken", at = @At("RETURN"))
    private void createNestedFactory$observeOrdinaryDrill(BlockState state, CallbackInfo callback) {
        if (!createNestedFactory$optimizedThisCycle) {
            createNestedFactory$publish(state, DrillProductionEvent.Path.STATIONARY, true);
        }
    }

    @Unique
    private void createNestedFactory$publish(BlockState brokenState, DrillProductionEvent.Path path,
                                             boolean requireStateChange) {
        DrillBlockEntity drill = (DrillBlockEntity) (Object) this;
        if (!(drill.getLevel() instanceof ServerLevel serverLevel)) return;
        BlockPos target = drill.getBlockPos().relative(drill.getBlockState().getValue(DrillBlock.FACING));
        if (requireStateChange && serverLevel.getBlockState(target).equals(brokenState)) return;
        PocketLearningObserver.onDrillProduction(serverLevel,
                new DrillProductionEvent(target, brokenState, path));
    }
}

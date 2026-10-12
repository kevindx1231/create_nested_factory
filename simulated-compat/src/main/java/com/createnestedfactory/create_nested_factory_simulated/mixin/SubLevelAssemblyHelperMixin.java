package com.createnestedfactory.create_nested_factory_simulated.mixin;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.FactoryPassageMovement;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Set;

@Mixin(SubLevelAssemblyHelper.class)
public abstract class SubLevelAssemblyHelperMixin {
    @Inject(method = "assembleBlocks", at = @At("HEAD"), cancellable = true)
    private static void createNestedFactorySimulated$disableFactorySpaceAssembly(
            final ServerLevel level,
            final BlockPos anchor,
            final Iterable<BlockPos> blocks,
            final BoundingBox3ic bounds,
            final CallbackInfoReturnable<ServerSubLevel> cir) {
        if (level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION) || !completePassages(level, blocks)) {
            cir.setReturnValue(null);
        }
    }

    private static boolean completePassages(ServerLevel level, Iterable<BlockPos> blocks) {
        Set<BlockPos> selected = new HashSet<>();
        blocks.forEach(pos -> selected.add(pos.immutable()));
        for (BlockPos pos : selected) {
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof FactoryPassageBlock)) continue;
            BlockPos other = state.getValue(FactoryPassageBlock.HALF) == DoubleBlockHalf.LOWER
                    ? pos.above() : pos.below();
            if (!selected.contains(other)) return false;
        }
        return true;
    }

    @Inject(method = "moveOtherStuff", at = @At("HEAD"))
    private static void createNestedFactorySimulated$validatePassagesBeforeEntitiesMove(
            ServerLevel level, SubLevelAssemblyHelper.AssemblyTransform transform,
            Iterable<BlockPos> blocks, BoundingBox3ic bounds, CallbackInfo ci) {
        if (!completePassages(level, blocks)) throw new IllegalStateException("A factory passage must move both halves together");
    }

    @WrapMethod(method = "moveBlocks")
    private static void createNestedFactorySimulated$movePassagesAsBatch(
            ServerLevel level, SubLevelAssemblyHelper.AssemblyTransform transform,
            Iterable<BlockPos> blocks, Operation<Void> original) {
        if (!completePassages(level, blocks)) throw new IllegalStateException("A factory passage must move both halves together");
        var sources = new ArrayList<BlockPos>();
        var destinations = new ArrayList<BlockPos>();
        blocks.forEach(pos -> {
            if (level.getBlockState(pos).getBlock() instanceof FactoryPassageBlock) {
                sources.add(pos.immutable());
                destinations.add(transform.apply(pos));
            }
        });
        if (sources.isEmpty()) {
            original.call(level, transform, blocks);
            return;
        }
        try (var scope = FactoryPassageMovement.begin(level, sources, transform.getLevel(), destinations)) {
            original.call(level, transform, blocks);
        }
        for (BlockPos pos : destinations) {
            if (transform.getLevel().getBlockEntity(pos) instanceof FactoryPassageBlockEntity passage) {
                passage.registerEndpoint();
            }
        }
    }
}

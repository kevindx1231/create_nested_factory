package com.createnestedfactory.create_nested_factory_simulated.mixin;

import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.math.LevelReusedVectors;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class SubLevelEntityCollisionMixin {
    @WrapOperation(method = "tryStepUp", at = @At(value = "INVOKE",
            target = "Lorg/joml/Vector3d;add(DDD)Lorg/joml/Vector3d;"))
    private static Vector3d createNestedFactorySimulated$passageThresholdDimensions(
            Vector3d dimensions, double x, double y, double z, Operation<Vector3d> original,
            @Local(argsOnly = true) Entity entity,
            @Local(argsOnly = true) LevelAccelerator accel,
            @Local(argsOnly = true) LevelReusedVectors sink) {
        // These are the block and local-space box selected by collide for this step attempt.
        // Only the open passage's 1/16-high threshold needs the player's actual dimensions:
        // Sable's extra 0.1 otherwise makes the body wider/taller than the doorway.
        if (entity instanceof Player
                && Math.abs(sink.maxAABB.minY - sink.maxBlockPos.getY()) < 1.0e-7
                && Math.abs(sink.maxAABB.maxY - sink.maxBlockPos.getY() - 1.0 / 16) < 1.0e-7) {
            var state = accel.getBlockState(sink.maxBlockPos);
            if (state.getBlock() instanceof FactoryPassageBlock
                    && state.getValue(FactoryPassageBlock.OPEN)
                    && state.getValue(FactoryPassageBlock.HALF) == DoubleBlockHalf.LOWER) {
                return dimensions;
            }
        }
        return original.call(dimensions, x, y, z);
    }
}

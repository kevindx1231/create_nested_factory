package com.createnestedfactory.create_nested_factory.client;

import com.createnestedfactory.create_nested_factory.block.FactoryPassageMarkerGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Positions a standard Create value box over the passage model's 4x4 marker area. */
public final class FactoryPassageMarkerSlot extends ValueBoxTransform {
    public static final FactoryPassageMarkerSlot INSTANCE = new FactoryPassageMarkerSlot();

    private static final double CENTER_HORIZONTAL = 3.0 / 16.0;
    private static final double CENTER_Y = 29.0 / 16.0;
    private static final double FRONT = -1.01 / 16.0;

    private FactoryPassageMarkerSlot() {
    }

    @Override
    public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
        return switch (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            case NORTH -> new Vec3(CENTER_HORIZONTAL, CENTER_Y, FRONT);
            case EAST -> new Vec3(1.0 - FRONT, CENTER_Y, CENTER_HORIZONTAL);
            case SOUTH -> new Vec3(1.0 - CENTER_HORIZONTAL, CENTER_Y, 1.0 - FRONT);
            case WEST -> new Vec3(FRONT, CENTER_Y, 1.0 - CENTER_HORIZONTAL);
            default -> Vec3.ZERO;
        };
    }

    @Override
    public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack poseStack) {
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        TransformStack.of(poseStack).rotateYDegrees(FactoryPassageMarkerGeometry.renderRotationDegrees(facing));
    }
}

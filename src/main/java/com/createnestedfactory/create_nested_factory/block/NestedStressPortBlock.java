package com.createnestedfactory.create_nested_factory.block;

import com.createnestedfactory.create_nested_factory.block.entity.NestedStressPortBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class NestedStressPortBlock extends RotatedPillarKineticBlock implements IBE<NestedStressPortBlockEntity> {
    public NestedStressPortBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NestedStressPortBlockEntity(pos, state);
    }

    @Override
    public Class<NestedStressPortBlockEntity> getBlockEntityClass() {
        return NestedStressPortBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends NestedStressPortBlockEntity> getBlockEntityType() {
        return ModBlockEntities.NESTED_STRESS_PORT.get();
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }
}

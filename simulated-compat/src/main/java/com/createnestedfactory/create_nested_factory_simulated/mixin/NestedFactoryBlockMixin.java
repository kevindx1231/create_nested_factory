package com.createnestedfactory.create_nested_factory_simulated.mixin;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(NestedFactoryBlock.class)
public abstract class NestedFactoryBlockMixin implements BlockSubLevelAssemblyListener {
    @Override
    public void beforeMove(final ServerLevel originLevel,
                           final ServerLevel resultingLevel,
                           final BlockState newState,
                           final BlockPos oldPos,
                           final BlockPos newPos) {
        if (originLevel.getBlockEntity(oldPos) instanceof NestedFactoryBlockEntity factory) {
            factory.prepareForSimulatedMove();
        }
    }

    @Override
    public void afterMove(final ServerLevel originLevel,
                          final ServerLevel resultingLevel,
                          final BlockState newState,
                          final BlockPos oldPos,
                          final BlockPos newPos) {
        if (resultingLevel.getBlockEntity(newPos) instanceof NestedFactoryBlockEntity factory) {
            factory.finishSimulatedMove();
        }
    }
}

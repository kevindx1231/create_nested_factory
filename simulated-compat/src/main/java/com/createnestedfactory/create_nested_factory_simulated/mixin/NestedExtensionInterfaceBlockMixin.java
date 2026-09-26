package com.createnestedfactory.create_nested_factory_simulated.mixin;

import com.createnestedfactory.create_nested_factory.block.NestedExtensionInterfaceBlock;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(NestedExtensionInterfaceBlock.class)
public abstract class NestedExtensionInterfaceBlockMixin implements BlockSubLevelAssemblyListener {
    @Override
    public void beforeMove(ServerLevel originLevel, ServerLevel resultingLevel, BlockState newState,
                           BlockPos oldPos, BlockPos newPos) {
        if (originLevel.getBlockEntity(oldPos) instanceof NestedExtensionInterfaceBlockEntity extension) {
            extension.prepareForSimulatedMove();
        }
    }

    @Override
    public void afterMove(ServerLevel originLevel, ServerLevel resultingLevel, BlockState newState,
                          BlockPos oldPos, BlockPos newPos) {
        if (resultingLevel.getBlockEntity(newPos) instanceof NestedExtensionInterfaceBlockEntity extension) {
            extension.finishSimulatedMove();
        }
    }
}

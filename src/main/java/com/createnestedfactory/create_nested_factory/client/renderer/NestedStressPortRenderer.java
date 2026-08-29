package com.createnestedfactory.create_nested_factory.client.renderer;

import com.createnestedfactory.create_nested_factory.block.entity.NestedStressPortBlockEntity;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

public class NestedStressPortRenderer extends KineticBlockEntityRenderer<NestedStressPortBlockEntity> {
    public NestedStressPortRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(NestedStressPortBlockEntity blockEntity, BlockState state) {
        return CachedBuffers.partial(AllPartialModels.SHAFT, state);
    }
}

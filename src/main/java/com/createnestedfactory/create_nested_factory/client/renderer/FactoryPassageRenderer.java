package com.createnestedfactory.create_nested_factory.client.renderer;

import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.client.FactoryPassageMarkerSlot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

/** Renders the non-consumable passage marker in the model's upper 4x4 display area. */
public final class FactoryPassageRenderer extends SafeBlockEntityRenderer<FactoryPassageBlockEntity> {
    public FactoryPassageRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(FactoryPassageBlockEntity passage, float partialTicks, PoseStack poseStack,
                              MultiBufferSource buffer, int light, int overlay) {
        ItemStack marker = passage.getMarker();
        if (marker.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        FactoryPassageMarkerSlot.INSTANCE.transform(passage.getLevel(), passage.getBlockPos(),
                passage.getBlockState(), poseStack);
        ValueBoxRenderer.renderItemIntoValueBox(marker, poseStack, buffer, light, overlay);
        poseStack.popPose();
    }

    @Override
    public @NotNull AABB getRenderBoundingBox(@NotNull FactoryPassageBlockEntity passage) {
        return new AABB(passage.getBlockPos()).expandTowards(0, 1, 0).inflate(.125);
    }
}

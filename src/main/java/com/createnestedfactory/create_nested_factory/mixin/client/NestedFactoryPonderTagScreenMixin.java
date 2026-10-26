package com.createnestedfactory.create_nested_factory.mixin.client;

import com.createnestedfactory.create_nested_factory.ponder.generated.GeneratedPonderAttribution;
import net.createmod.ponder.foundation.PonderTag;
import net.createmod.ponder.foundation.ui.PonderTagScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Custom heading for the Nested Factory component category. */
@Mixin(value = PonderTagScreen.class, remap = false)
public abstract class NestedFactoryPonderTagScreenMixin {
    @Shadow
    @Final
    private PonderTag tag;

    @ModifyArg(
        method = "renderWindow",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"
        ),
        index = 1
    )
    private Component nestedFactory$componentHeading(Component original) {
        return GeneratedPonderAttribution.tag().equals(tag.getId())
            ? Component.translatable("gui.create_nested_factory.ponder.components_heading")
            : original;
    }
}

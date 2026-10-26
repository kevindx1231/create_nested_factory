package com.createnestedfactory.create_nested_factory.mixin.client;

import com.createnestedfactory.create_nested_factory.ponder.generated.GeneratedPonderAttribution;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.ponder.foundation.PonderTag;
import net.createmod.ponder.foundation.ui.PonderTagScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Matches the 1.21.1 category heading without targeting obfuscated Minecraft draw methods. */
@Mixin(value = PonderTagScreen.class, remap = false)
public abstract class NestedFactoryPonderTagScreenMixin {
    @Shadow
    @Final
    private PonderTag tag;

    @Redirect(method = "renderWindow", at = @At(value = "INVOKE",
        target = "Lnet/createmod/catnip/lang/LangBuilder;component()Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent nestedFactory$componentHeading(LangBuilder builder) {
        return GeneratedPonderAttribution.tag().equals(tag.getId())
            ? Component.translatable("gui.create_nested_factory.ponder.components_heading")
            : builder.component();
    }
}

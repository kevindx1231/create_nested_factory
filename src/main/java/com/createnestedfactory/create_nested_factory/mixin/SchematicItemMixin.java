package com.createnestedfactory.create_nested_factory.mixin;

import com.createnestedfactory.create_nested_factory.blueprint.NestedFactoryBlueprint;
import com.simibubi.create.content.schematics.SchematicItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shift use of a factory blueprint belongs to the factory interaction, not Create's structure editor. */
@Mixin(SchematicItem.class)
abstract class SchematicItemMixin {
    @Inject(method = "onItemUse", at = @At("HEAD"), cancellable = true, remap = false)
    private void createNestedFactory$skipFactoryEditor(Player player, InteractionHand hand,
                                                       CallbackInfoReturnable<Boolean> callback) {
        if (NestedFactoryBlueprint.hasFactoryBlueprintMarker(player.getItemInHand(hand))) {
            callback.setReturnValue(false);
        }
    }
}

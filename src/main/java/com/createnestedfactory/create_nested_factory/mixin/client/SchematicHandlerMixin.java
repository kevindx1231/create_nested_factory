package com.createnestedfactory.create_nested_factory.mixin.client;

import com.createnestedfactory.create_nested_factory.blueprint.NestedFactoryBlueprint;
import com.simibubi.create.content.schematics.client.SchematicHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Factory plan templates share the schematic item, but have no Create structure settings. */
@Mixin(SchematicHandler.class)
abstract class SchematicHandlerMixin {
    @Inject(method = "findBlueprintInHand", at = @At("HEAD"), cancellable = true, remap = false)
    private void createNestedFactory$skipFactoryBlueprint(Player player, CallbackInfoReturnable<ItemStack> callback) {
        if (NestedFactoryBlueprint.hasFactoryBlueprintMarker(player.getMainHandItem())) {
            callback.setReturnValue(null);
        }
    }
}

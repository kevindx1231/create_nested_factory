// Imported from the 1.21.1 Ponderer export; adapted for Minecraft 1.20.1.
// Re-import with tools/port_ponder_1_20_1.py; keep the source checkout read-only.
package com.createnestedfactory.create_nested_factory.ponder.generated;

import com.createnestedfactory.create_nested_factory.registry.ModItems;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public final class GeneratedPonderAttribution {
    private static final ResourceLocation TAG = new ResourceLocation("create_nested_factory", "ponderer_exported");

    private GeneratedPonderAttribution() {
    }

    public static ResourceLocation tag() {
        return TAG;
    }

    public static void registerTag(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(TAG)
            .title("Nested Factory Components")
            .description("Build production lines in multiple nested factory spaces.")
            .item(ModItems.NESTED_FACTORY.get())
            .register();
    }
}

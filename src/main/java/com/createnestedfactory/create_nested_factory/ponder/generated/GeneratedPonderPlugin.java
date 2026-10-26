// Imported from the 1.21.1 Ponderer export; adapted for Minecraft 1.20.1.
// Re-import with tools/port_ponder_1_20_1.py; keep the source checkout read-only.
package com.createnestedfactory.create_nested_factory.ponder.generated;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public final class GeneratedPonderPlugin implements PonderPlugin {
    @Override
    public String getModId() {
        return "create_nested_factory";
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        GeneratedPonderIndex.register(helper);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        GeneratedPonderAttribution.registerTag(helper);
        GeneratedPonderIndex.registerTags(helper);
    }
}

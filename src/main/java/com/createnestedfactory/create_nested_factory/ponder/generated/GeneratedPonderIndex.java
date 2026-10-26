// Imported from the 1.21.1 Ponderer export; adapted for Minecraft 1.20.1.
// Re-import with tools/port_ponder_1_20_1.py; keep the source checkout read-only.
package com.createnestedfactory.create_nested_factory.ponder.generated;

import com.createnestedfactory.create_nested_factory.ponder.generated.scenes.GeneratedFactoryPassage_f7c6ad50;
import com.createnestedfactory.create_nested_factory.ponder.generated.scenes.GeneratedNestedExtensionInterface_7248b99b;
import com.createnestedfactory.create_nested_factory.ponder.generated.scenes.GeneratedNestedFactory_6cfd9147;
import com.createnestedfactory.create_nested_factory.ponder.generated.scenes.GeneratedNestedPort_6095c83f;
import com.createnestedfactory.create_nested_factory.ponder.generated.scenes.GeneratedNestedStressPort_04c9d6cf;

import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public final class GeneratedPonderIndex {
    private GeneratedPonderIndex() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        GeneratedFactoryPassage_f7c6ad50.register(helper);
        GeneratedNestedExtensionInterface_7248b99b.register(helper);
        GeneratedNestedFactory_6cfd9147.register(helper);
        GeneratedNestedPort_6095c83f.register(helper);
        GeneratedNestedStressPort_04c9d6cf.register(helper);
    }

    public static void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        GeneratedFactoryPassage_f7c6ad50.registerTags(helper);
        GeneratedNestedExtensionInterface_7248b99b.registerTags(helper);
        GeneratedNestedFactory_6cfd9147.registerTags(helper);
        GeneratedNestedPort_6095c83f.registerTags(helper);
        GeneratedNestedStressPort_04c9d6cf.registerTags(helper);
    }
}

package com.createnestedfactory.create_nested_factory.client.ponder;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/** Loaded only by ModClientEvents; no server-side room or learning operations. */
public final class NestedFactoryPonderPlugin implements PonderPlugin {
    @Override
    public String getModId() {
        return Create_nested_factory.MODID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation factory = ResourceLocation.fromNamespaceAndPath(getModId(), "nested_factory");
        String schematic = "nested_factory/workshop";
        helper.addStoryBoard(factory, schematic, NestedFactoryScenes::space);
        helper.addStoryBoard(factory, schematic, NestedFactoryScenes::ports).orderAfter("factory_space");
        helper.addStoryBoard(factory, schematic, NestedFactoryScenes::power).orderAfter("factory_ports");
        helper.addStoryBoard(factory, schematic, NestedFactoryScenes::blackbox).orderAfter("factory_power");
        helper.addStoryBoard(factory, schematic, NestedFactoryScenes::blueprint).orderAfter("factory_blackbox");
        helper.addStoryBoard(factory, schematic, NestedFactoryScenes::nesting).orderAfter("factory_blueprint");
        helper.addStoryBoard(factory, schematic, NestedFactoryScenes::moving).orderAfter("factory_nesting");
    }
}

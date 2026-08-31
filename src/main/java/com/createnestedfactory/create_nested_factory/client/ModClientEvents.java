package com.createnestedfactory.create_nested_factory.client;

import com.createnestedfactory.create_nested_factory.client.renderer.NestedStressPortRenderer;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.createnestedfactory.create_nested_factory.registry.ModMenus;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public final class ModClientEvents {
    private ModClientEvents() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModClientEvents::registerScreens);
        modEventBus.addListener(ModClientEvents::registerBlockEntityRenderers);
        modEventBus.addListener(ModClientEvents::registerStressPortVisual);
    }

    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.FACTORY.get(), FactoryScreen::new);
    }

    public static void registerBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.NESTED_STRESS_PORT.get(), NestedStressPortRenderer::new);
    }

    public static void registerStressPortVisual(FMLClientSetupEvent event) {
        SimpleBlockEntityVisualizer.builder(ModBlockEntities.NESTED_STRESS_PORT.get())
                .factory(SingleAxisRotatingVisual.of(AllPartialModels.SHAFT))
                .skipVanillaRender(blockEntity -> true)
                .apply();
    }
}

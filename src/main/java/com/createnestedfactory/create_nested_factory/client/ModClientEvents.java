package com.createnestedfactory.create_nested_factory.client;

import com.createnestedfactory.create_nested_factory.client.renderer.NestedStressPortRenderer;
import com.createnestedfactory.create_nested_factory.client.renderer.FactoryPassageRenderer;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.createnestedfactory.create_nested_factory.registry.ModMenus;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;

public final class ModClientEvents {
    private ModClientEvents() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModClientEvents::registerScreens);
        modEventBus.addListener(ModClientEvents::registerBlockEntityRenderers);
        modEventBus.addListener(ModClientEvents::registerStressPortVisual);
        modEventBus.addListener(ModClientEvents::registerRenderLayers);
    }

    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ModMenus.FACTORY.get(), FactoryScreen::new));
    }

    public static void registerBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.NESTED_STRESS_PORT.get(), NestedStressPortRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.FACTORY_PASSAGE.get(), FactoryPassageRenderer::new);
    }

    public static void registerStressPortVisual(FMLClientSetupEvent event) {
        event.enqueueWork(() -> SimpleBlockEntityVisualizer.builder(ModBlockEntities.NESTED_STRESS_PORT.get())
                .factory(SingleAxisRotatingVisual.of(AllPartialModels.SHAFT))
                .skipVanillaRender(blockEntity -> true)
                .apply());
    }

    @SuppressWarnings("deprecation")
    public static void registerRenderLayers(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemBlockRenderTypes.setRenderLayer(ModBlocks.FACTORY_PASSAGE.get(), RenderType.cutout()));
    }
}

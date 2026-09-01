package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.client.ModClientEvents;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.integration.NestedFactoryUnpackingHandler;
import com.simibubi.create.api.packager.unpacking.UnpackingHandler;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import com.createnestedfactory.create_nested_factory.network.RenameFactoryPayload;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.createnestedfactory.create_nested_factory.registry.ModCreativeModeTabs;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
import com.createnestedfactory.create_nested_factory.registry.ModItems;
import com.createnestedfactory.create_nested_factory.registry.ModMenus;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(Create_nested_factory.MODID)
public class Create_nested_factory {
    public static final String MODID = "create_nested_factory";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Create_nested_factory(IEventBus modEventBus, ModContainer modContainer, Dist dist) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeModeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(Config::onLoad);

        if (dist.isClient()) {
            ModClientEvents.register(modEventBus);
        }

        NeoForge.EVENT_BUS.register(this);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> UnpackingHandler.REGISTRY.register(
                ModBlocks.NESTED_FACTORY.get(), NestedFactoryUnpackingHandler.INSTANCE));
        LOGGER.info("Nested Factory common setup");
    }

    private void registerPayloads(final RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(RenameFactoryPayload.TYPE, RenameFactoryPayload.STREAM_CODEC, RenameFactoryPayload::handle)
                .playToClient(PlayerMessagePayload.TYPE, PlayerMessagePayload.STREAM_CODEC,
                        PlayerMessagePayload::handle);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.NESTED_FACTORY.get(),
                (be, side) -> be instanceof NestedFactoryBlockEntity factory ? factory.getItemHandler(side) : null);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.NESTED_FACTORY.get(),
                (be, side) -> be instanceof NestedFactoryBlockEntity factory ? factory.getFluidHandler(side) : null);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.NESTED_FACTORY.get(),
                (be, side) -> be instanceof NestedFactoryBlockEntity factory ? factory.getEnergyStorage(side) : null);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.NESTED_PORT.get(),
                (be, side) -> be instanceof NestedPortBlockEntity port ? port.getItemHandler(side) : null);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.NESTED_PORT.get(),
                (be, side) -> be instanceof NestedPortBlockEntity port ? port.getFluidHandler(side) : null);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Nested Factory server starting");
    }

}

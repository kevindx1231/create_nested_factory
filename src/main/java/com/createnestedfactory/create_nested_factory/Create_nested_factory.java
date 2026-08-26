package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.network.RenameFactoryPayload;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
import com.createnestedfactory.create_nested_factory.registry.ModItems;
import com.createnestedfactory.create_nested_factory.registry.ModMenus;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(Create_nested_factory.MODID)
public class Create_nested_factory {
    public static final String MODID = "create_nested_factory";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Create_nested_factory(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::registerPayloads);

        NeoForge.EVENT_BUS.register(this);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Nested Factory common setup");
    }

    private void registerPayloads(final RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(RenameFactoryPayload.TYPE, RenameFactoryPayload.STREAM_CODEC,
                RenameFactoryPayload::handle);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.NESTED_FACTORY);
            event.accept(ModItems.NESTED_PORT);
            event.accept(ModItems.NESTED_STRESS_PORT);
            event.accept(ModItems.SPACE_EXPANDER);
            event.accept(ModItems.SPACE_COLLAPSER);
        }
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.NESTED_FACTORY.get(),
                (be, side) -> be instanceof NestedFactoryBlockEntity factory ? factory.getItemHandler(side) : null);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.NESTED_FACTORY.get(),
                (be, side) -> be instanceof NestedFactoryBlockEntity factory ? factory.getFluidHandler(side) : null);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.NESTED_FACTORY.get(),
                (be, side) -> be instanceof NestedFactoryBlockEntity factory ? factory.getEnergyStorage(side) : null);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.NESTED_PORT.get(),
                (be, side) -> be instanceof NestedPortBlockEntity port ? port.getItemHandler() : null);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.NESTED_PORT.get(),
                (be, side) -> be instanceof NestedPortBlockEntity port ? port.getFluidHandler() : null);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Nested Factory server starting");
    }

    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }
}

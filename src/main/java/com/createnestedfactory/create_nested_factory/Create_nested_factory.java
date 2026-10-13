package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import com.createnestedfactory.create_nested_factory.client.ModClientEvents;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.integration.NestedFactoryUnpackingHandler;
import com.createnestedfactory.create_nested_factory.integration.NestedExtensionInterfaceUnpackingHandler;
import com.createnestedfactory.create_nested_factory.integration.NestedPortUnpackingHandler;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(Create_nested_factory.MODID)
public class Create_nested_factory {
    public static final String MODID = "create_nested_factory";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Create_nested_factory() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        Dist dist = FMLEnvironment.dist;
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeModeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        com.createnestedfactory.create_nested_factory.network.ModNetwork.register();
        modEventBus.addListener(Config::onLoad);

        if (dist.isClient()) {
            ModClientEvents.register(modEventBus);
        }

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        OptionalCompatBootstrap.register(modEventBus);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            UnpackingHandler.REGISTRY.register(ModBlocks.NESTED_FACTORY.get(), NestedFactoryUnpackingHandler.INSTANCE);
            UnpackingHandler.REGISTRY.register(ModBlocks.NESTED_PORT.get(), NestedPortUnpackingHandler.INSTANCE);
            UnpackingHandler.REGISTRY.register(ModBlocks.NESTED_EXTENSION_INTERFACE.get(),
                    NestedExtensionInterfaceUnpackingHandler.INSTANCE);
        });
        LOGGER.info("Nested Factory common setup");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Nested Factory server starting");
    }

}

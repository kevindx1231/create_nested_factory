// Imported from the 1.21.1 Ponderer export; adapted for Minecraft 1.20.1.
// Re-import with tools/port_ponder_1_20_1.py; keep the source checkout read-only.
package com.createnestedfactory.create_nested_factory.ponder.generated;

import net.createmod.ponder.foundation.PonderIndex;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = "create_nested_factory", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GeneratedPonderForgeClient {
    private GeneratedPonderForgeClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> PonderIndex.addPlugin(new GeneratedPonderPlugin()));
    }
}

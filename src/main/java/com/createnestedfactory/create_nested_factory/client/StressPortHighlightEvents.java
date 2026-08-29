package com.createnestedfactory.create_nested_factory.client;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

@EventBusSubscriber(modid = Create_nested_factory.MODID, value = Dist.CLIENT)
public final class StressPortHighlightEvents {
    private StressPortHighlightEvents() {
    }

    @SubscribeEvent
    public static void hideStressPortOutline(RenderHighlightEvent.Block event) {
        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockState(event.getTarget().getBlockPos()).is(ModBlocks.NESTED_STRESS_PORT.get())) {
            event.setCanceled(true);
        }
    }
}

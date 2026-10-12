package com.createnestedfactory.create_nested_factory.event;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = Create_nested_factory.MODID)
public final class FactoryDirectoryEvents {
    private FactoryDirectoryEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 == 0) {
            com.createnestedfactory.create_nested_factory.command.FactoryCommandService.resumeDeletions(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onItemLoaded(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof ItemEntity item
                && item.getItem().is(ModItems.NESTED_FACTORY.get())) {
            NestedFactoryBlockEntity.observePortableItem(level.getServer(), item.getItem());
        }
    }
}

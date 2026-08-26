package com.createnestedfactory.create_nested_factory.registry;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.item.SpaceCollapserItem;
import com.createnestedfactory.create_nested_factory.item.SpaceExpanderItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Create_nested_factory.MODID);

    public static final DeferredItem<BlockItem> NESTED_FACTORY = ITEMS.registerSimpleBlockItem("nested_factory", ModBlocks.NESTED_FACTORY);
    public static final DeferredItem<BlockItem> NESTED_PORT = ITEMS.registerSimpleBlockItem("nested_port", ModBlocks.NESTED_PORT);
    public static final DeferredItem<BlockItem> NESTED_STRESS_PORT = ITEMS.registerSimpleBlockItem("nested_stress_port", ModBlocks.NESTED_STRESS_PORT);

    public static final DeferredItem<SpaceExpanderItem> SPACE_EXPANDER =
            ITEMS.register("space_expander", () -> new SpaceExpanderItem(new Item.Properties()));
    public static final DeferredItem<SpaceCollapserItem> SPACE_COLLAPSER =
            ITEMS.register("space_collapser", () -> new SpaceCollapserItem(new Item.Properties()));
}

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
    public static final DeferredItem<BlockItem> SNOW_WALL = ITEMS.registerSimpleBlockItem("snow_wall", ModBlocks.SNOW_WALL);
    public static final DeferredItem<BlockItem> WHITE_CONCRETE_WALL = ITEMS.registerSimpleBlockItem("white_concrete_wall", ModBlocks.WHITE_CONCRETE_WALL);

    // Keep the original item classes so the expansion/collapse behavior remains unchanged.
    public static final DeferredItem<SpaceExpanderItem> SPACE_EXPAND_MECHANISM =
            ITEMS.register("space_expand_mechanism", () -> new SpaceExpanderItem(new Item.Properties()));
    public static final DeferredItem<SpaceCollapserItem> SPACE_COLLAPSE_MECHANISM =
            ITEMS.register("space_collapse_mechanism", () -> new SpaceCollapserItem(new Item.Properties()));
    public static final DeferredItem<Item> STURDY_CASING =
            ITEMS.register("sturdy_casing", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> STURDY_ALLOY_INGOT =
            ITEMS.register("sturdy_alloy_ingot", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> BLAZE_BATTERY =
            ITEMS.register("blaze_battery", () -> new Item(new Item.Properties().stacksTo(64)));
    public static final DeferredItem<Item> INCOMPLETE_STURDY_CASING =
            ITEMS.register("incomplete_sturdy_casing", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> INCOMPLETE_SPACE_EXPAND_MECHANISM =
            ITEMS.register("incomplete_space_expand_mechanism", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> INCOMPLETE_SPACE_COLLAPSE_MECHANISM =
            ITEMS.register("incomplete_space_collapse_mechanism", () -> new Item(new Item.Properties()));
}

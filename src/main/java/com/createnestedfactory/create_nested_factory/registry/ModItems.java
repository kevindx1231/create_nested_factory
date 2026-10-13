package com.createnestedfactory.create_nested_factory.registry;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.item.SpaceCollapserItem;
import com.createnestedfactory.create_nested_factory.item.SpaceExpanderItem;
import com.createnestedfactory.create_nested_factory.item.FactoryBlockItem;
import com.createnestedfactory.create_nested_factory.item.FactoryPassageItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Create_nested_factory.MODID);

    public static final RegistryObject<FactoryBlockItem> NESTED_FACTORY = ITEMS.register("nested_factory",
            () -> new FactoryBlockItem(ModBlocks.NESTED_FACTORY.get(), new Item.Properties().stacksTo(1)));
    public static final RegistryObject<BlockItem> NESTED_PORT = ITEMS.register("nested_port", () -> new BlockItem(ModBlocks.NESTED_PORT.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> NESTED_EXTENSION_INTERFACE =
            ITEMS.register("nested_extension_interface", () -> new BlockItem(ModBlocks.NESTED_EXTENSION_INTERFACE.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> NESTED_STRESS_PORT = ITEMS.register("nested_stress_port", () -> new BlockItem(ModBlocks.NESTED_STRESS_PORT.get(), new Item.Properties()));
    public static final RegistryObject<FactoryPassageItem> FACTORY_PASSAGE = ITEMS.register("factory_passage",
            () -> new FactoryPassageItem(ModBlocks.FACTORY_PASSAGE.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> SNOW_WALL = ITEMS.register("snow_wall", () -> new BlockItem(ModBlocks.SNOW_WALL.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> WHITE_CONCRETE_WALL = ITEMS.register("white_concrete_wall", () -> new BlockItem(ModBlocks.WHITE_CONCRETE_WALL.get(), new Item.Properties()));

    // Keep the original item classes so the expansion/collapse behavior remains unchanged.
    public static final RegistryObject<SpaceExpanderItem> SPACE_EXPAND_MECHANISM =
            ITEMS.register("space_expand_mechanism", () -> new SpaceExpanderItem(new Item.Properties()));
    public static final RegistryObject<SpaceCollapserItem> SPACE_COLLAPSE_MECHANISM =
            ITEMS.register("space_collapse_mechanism", () -> new SpaceCollapserItem(new Item.Properties()));
    public static final RegistryObject<Item> STURDY_CASING =
            ITEMS.register("sturdy_casing", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> STURDY_ALLOY_INGOT =
            ITEMS.register("sturdy_alloy_ingot", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> STURDY_ALLOY_SHEET =
            ITEMS.register("sturdy_alloy_sheet", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> SOAKED_STURDY_ALLOY_INGOT =
            ITEMS.register("soaked_sturdy_alloy_ingot", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> SOAKED_STURDY_ALLOY_SHEET =
            ITEMS.register("soaked_sturdy_alloy_sheet", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INCOMPLETE_SOAKED_STURDY_ALLOY_SHEET =
            ITEMS.register("incomplete_soaked_sturdy_alloy_sheet", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> BATTERY_CASING =
            ITEMS.register("battery_casing", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INACTIVE_BLAZE_BATTERY =
            ITEMS.register("inactive_blaze_battery", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> BLAZE_BATTERY =
            ITEMS.register("blaze_battery", () -> new Item(new Item.Properties().stacksTo(64)));
    public static final RegistryObject<Item> INCOMPLETE_BLAZE_BATTERY =
            ITEMS.register("incomplete_blaze_battery", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INCOMPLETE_STURDY_CASING =
            ITEMS.register("incomplete_sturdy_casing", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INCOMPLETE_SPACE_EXPAND_MECHANISM =
            ITEMS.register("incomplete_space_expand_mechanism", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INCOMPLETE_SPACE_COLLAPSE_MECHANISM =
            ITEMS.register("incomplete_space_collapse_mechanism", () -> new Item(new Item.Properties()));
}

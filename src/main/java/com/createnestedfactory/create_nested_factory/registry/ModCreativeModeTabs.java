package com.createnestedfactory.create_nested_factory.registry;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Create_nested_factory.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> NESTED_FACTORY_TAB =
            CREATIVE_MODE_TABS.register("nested_factory", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.create_nested_factory.nested_factory"))
                    .icon(() -> new ItemStack(ModItems.NESTED_FACTORY.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.NESTED_FACTORY.get());
                        output.accept(ModItems.NESTED_PORT.get());
                        output.accept(ModItems.NESTED_STRESS_PORT.get());
                        output.accept(ModItems.SPACE_EXPAND_MECHANISM.get());
                        output.accept(ModItems.SPACE_COLLAPSE_MECHANISM.get());
                        output.accept(ModItems.STURDY_CASING.get());
                        output.accept(ModItems.STURDY_ALLOY_INGOT.get());
                        output.accept(ModItems.BLAZE_BATTERY.get());
                    })
                    .build());

    private ModCreativeModeTabs() {
    }
}

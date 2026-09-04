package com.createnestedfactory.create_nested_factory.client;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.block.FactoryPowerProfile;
import com.createnestedfactory.create_nested_factory.blueprint.NestedFactoryBlueprint;
import com.createnestedfactory.create_nested_factory.block.entity.ItemVariant;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.simibubi.create.foundation.utility.CreateLang;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@EventBusSubscriber(modid = Create_nested_factory.MODID, value = Dist.CLIENT)
public final class BlueprintTooltipEvents {
    private static final Style INFO_STYLE = Style.EMPTY.withColor(0xC7954B);

    private BlueprintTooltipEvents() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (Minecraft.getInstance().level == null) {
            return;
        }
        List<Component> tooltip = event.getToolTip();
        boolean showExtended = event.getEntity() != null && Screen.hasShiftDown();
        CompoundTag factoryData = NestedFactoryBlockEntity.getFactoryItemData(stack);
        if (factoryData.getBoolean(NestedFactoryBlockEntity.PORTABLE_BINDING_KEY)) {
            renderFactoryTooltip(tooltip, stack, factoryData, showExtended, Minecraft.getInstance());
            return;
        }

        NestedFactoryBlueprint blueprint = NestedFactoryBlueprint.fromItem(stack, Minecraft.getInstance().level.registryAccess());
        if (blueprint == null) {
            return;
        }

        tooltip.clear();
        if (!showExtended) {
            tooltip.add(Component.translatable("tooltip.create_nested_factory.nested_blueprint")
                    .withStyle(ChatFormatting.WHITE));
            tooltip.add(CreateLang.translateDirect("tooltip.holdForDescription",
                            CreateLang.translateDirect("tooltip.keyShift").withStyle(ChatFormatting.GRAY))
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        // Shift information follows the requested source -> recipe -> power -> location order.
        addStat(tooltip, "tooltip.create_nested_factory.blueprint.source_name", blueprint.sourceFactoryName());
        addRateSection(tooltip, "tooltip.create_nested_factory.blueprint.input_items",
                blueprint.blackbox().getInputRates(), blueprint.blackbox().getInputFluidRates());
        addRateSection(tooltip, "tooltip.create_nested_factory.blueprint.output_items",
                blueprint.blackbox().getOutputRates(), blueprint.blackbox().getOutputFluidRates());
        addStat(tooltip, "tooltip.create_nested_factory.blueprint.consumed_stress",
                formatNumber(blueprint.powerProfile().consumedSU()) + " su");
        addStat(tooltip, "tooltip.create_nested_factory.blueprint.machine_efficiency",
                String.format(Locale.ROOT, "%.0f%%", blueprint.productionEfficiency() * 100.0f));
        addStat(tooltip, "tooltip.create_nested_factory.blueprint.source_pos",
                blueprint.sourcePos().getX() + " " + blueprint.sourcePos().getY() + " "
                        + blueprint.sourcePos().getZ());
        addStat(tooltip, "tooltip.create_nested_factory.blueprint.source_dimension", blueprint.sourceDimension());
        addStat(tooltip, "tooltip.create_nested_factory.blueprint.source_depth",
                String.valueOf(blueprint.sourceDepth()));
        addUsageHint(tooltip);
    }

    private static void renderFactoryTooltip(List<Component> tooltip, ItemStack stack, CompoundTag data,
            boolean showExtended, Minecraft minecraft) {
        tooltip.clear();
        tooltip.add(stack.getHoverName().copy().withStyle(ChatFormatting.WHITE));
        if (!showExtended) {
            tooltip.add(CreateLang.translateDirect("tooltip.holdForDescription",
                            CreateLang.translateDirect("tooltip.keyShift").withStyle(ChatFormatting.GRAY))
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        addStat(tooltip, "tooltip.create_nested_factory.factory_id", data.getString("FactoryId"));
        addStat(tooltip, "tooltip.create_nested_factory.factory_mode",
                Component.translatable("gui.create_nested_factory.mode."
                        + data.getString("OperationMode")).getString());
        addStat(tooltip, "tooltip.create_nested_factory.factory_nesting",
                data.getBoolean("Nested") ? data.getInt("NestingDepth") + "" : "0");
        addStat(tooltip, "tooltip.create_nested_factory.factory_energy",
                data.getInt("EnergyStored") + " FE");

        if (data.getBoolean("Nested")) {
            addStat(tooltip, "tooltip.create_nested_factory.factory_parent",
                    data.getString("ParentFactoryId"));
        }

        FactoryPowerProfile profile = new FactoryPowerProfile();
        profile.read(data.getCompound("PowerProfile"));
        addStat(tooltip, "tooltip.create_nested_factory.blueprint.consumed_stress",
                formatNumber(profile.consumedSU()) + " su");
        addStat(tooltip, "tooltip.create_nested_factory.factory_energy_rate",
                formatNumber(profile.consumedFE()) + " FE/t");
    }

    private static void addStat(List<Component> tooltip, String key, String value) {
        tooltip.add(Component.empty()
                .append(Component.translatable(key).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value == null || value.isBlank() ? "-" : value).withStyle(INFO_STYLE)));
    }

    private static void addRateSection(List<Component> tooltip, String key, Map<ItemVariant, Float> itemRates,
            Map<Fluid, Float> fluidRates) {
        tooltip.add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
        itemRates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((left, right) ->
                        left.compareTo(right)))
                .forEach(entry -> tooltip.add(rateLine(entry.getKey().prototype().getHoverName(),
                        formatRate(entry.getValue()), "/s")));
        fluidRates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((left, right) ->
                        BuiltInRegistries.FLUID.getKey(left).toString()
                                .compareTo(BuiltInRegistries.FLUID.getKey(right).toString())))
                .forEach(entry -> tooltip.add(rateLine(new FluidStack(entry.getKey(), 1).getHoverName(),
                        formatRate(entry.getValue()), "mB/s")));
    }

    private static Component rateLine(Component resourceName, String rate, String unit) {
        return Component.literal("  ")
                .append(resourceName.copy().withStyle(INFO_STYLE))
                .append(Component.literal("  " + rate + " " + unit).withStyle(INFO_STYLE));
    }

    private static void addUsageHint(List<Component> tooltip) {
        tooltip.add(Component.translatable("tooltip.create_nested_factory.blueprint.use_method")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(":").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal(" ")
                .append(Component.translatable("tooltip.create_nested_factory.blueprint.apply")
                        .withStyle(INFO_STYLE)));
    }

    private static String formatRate(float rate) {
        return String.format(Locale.ROOT, "%.0f", rate);
    }

    private static String formatNumber(float value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}

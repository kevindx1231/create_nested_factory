package com.createnestedfactory.create_nested_factory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.fluids.transfer.FillingRecipe;
import com.simibubi.create.content.kinetics.deployer.DeployerApplicationRecipe;
import com.simibubi.create.content.kinetics.fan.processing.SplashingRecipe;
import com.simibubi.create.content.kinetics.mixer.MixingRecipe;
import com.simibubi.create.content.kinetics.press.PressingRecipe;
import com.simibubi.create.content.kinetics.saw.CuttingRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeSerializer;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipeSerializer;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.VanillaIngredientSerializer;
import net.minecraftforge.common.crafting.conditions.ICondition.IContext;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryObject;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Parses the shipped resources through Minecraft/Forge/Create, including nested assembly steps. */
public final class RecipeResourcesRegression {
    private static final List<String> RECIPES = List.of(
            "battery_casing", "factory_passage", "nested_extension_interface", "nested_factory",
            "nested_port", "nested_stress_port", "mixing/blaze_battery", "mixing/soaked_sturdy_alloy_ingot",
            "mixing/sturdy_alloy_ingot", "sequenced_assembly/blaze_battery",
            "sequenced_assembly/soaked_sturdy_alloy_sheet", "sequenced_assembly/space_collapse_mechanism",
            "sequenced_assembly/space_expand_mechanism", "sequenced_assembly/sturdy_casing",
            "splashing/soaked_sturdy_alloy_sheet");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Map<String, JsonObject> resources = new LinkedHashMap<>();
        for (String name : RECIPES) {
            String path = "data/create_nested_factory/recipes/" + name + ".json";
            try (var stream = RecipeResourcesRegression.class.getClassLoader().getResourceAsStream(path)) {
                require(stream != null, "Missing recipe resource: " + path);
                resources.put(name, JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                        .getAsJsonObject());
            }
        }
        registerFixture(resources);
        Map<String, Recipe<?>> parsed = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        resources.forEach((name, json) -> {
            try {
                Recipe<?> recipe = RecipeManager.fromJson(id(name), json, IContext.EMPTY);
                require(recipe != null, "Serializer skipped recipe");
                require(!recipe.getResultItem(RegistryAccess.EMPTY).isEmpty(), "Empty recipe result");
                parsed.put(name, recipe);
            } catch (Exception | AssertionError failure) {
                failures.add(name + ": " + failure.getMessage());
            }
        });
        require(failures.isEmpty(), "Recipe deserialization failed: " + failures);
        stressPort((ShapedRecipe) parsed.get("nested_stress_port"));
        for (String name : RECIPES) {
            if (!name.startsWith("sequenced_assembly/")) continue;
            SequencedAssemblyRecipe recipe = (SequencedAssemblyRecipe) parsed.get(name);
            JsonObject json = resources.get(name);
            require(!recipe.getTransitionalItem().isEmpty(), name + " has no transitional item");
            require(recipe.getSequence().size() == json.getAsJsonArray("sequence").size(),
                    name + " lost assembly steps");
            require(recipe.getLoops() == (name.endsWith("soaked_sturdy_alloy_sheet") ? 4 : 1),
                    name + " changed loop count");
            for (var step : recipe.getSequence()) {
                require(!step.getRecipe().getRollableResults().isEmpty(), name + " has an empty step result");
                require(step.getAsAssemblyRecipe().supportsAssembly(), name + " contains an unsupported step");
            }
        }
        var battery = (SequencedAssemblyRecipe) parsed.get("sequenced_assembly/blaze_battery");
        require(battery.getResultItem(RegistryAccess.EMPTY).is(item("create_nested_factory:inactive_blaze_battery")),
                "Battery assembly no longer produces the inactive battery");
        require(battery.getSequence().get(0).getRecipe().getProcessingDuration() == 100,
                "Battery cutting duration was ignored by the 1.20.1 serializer");
        require(((MixingRecipe) parsed.get("mixing/soaked_sturdy_alloy_ingot")).getRequiredHeat()
                        == HeatCondition.SUPERHEATED,
                "Sturdy alloy mixing lost its superheated requirement");
        System.out.println("All 15 recipe resources parsed, including five assemblies; stress-port alternatives, "
                + "assembly outputs/loops, cutting duration and superheated mixing passed.");
    }

    private static void registerFixture(Map<String, JsonObject> resources) throws Exception {
        // Only material identities are needed; the real serializers and recipe classes run below.
        Set<ResourceLocation> items = new LinkedHashSet<>();
        resources.values().forEach(json -> collectItems(json, items));
        LegacyRegistryFixture.reopenWrapper(BuiltInRegistries.ITEM);
        ((ForgeRegistry<?>) ForgeRegistries.ITEMS).unfreeze();
        for (ResourceLocation item : items) {
            if (!BuiltInRegistries.ITEM.containsKey(item))
                Registry.register(BuiltInRegistries.ITEM, item, new Item(new Item.Properties()));
        }
        BuiltInRegistries.ITEM.freeze();
        if (CraftingHelper.getID(VanillaIngredientSerializer.INSTANCE) == null)
            CraftingHelper.register(new ResourceLocation("minecraft:item"), VanillaIngredientSerializer.INSTANCE);
        if (CraftingHelper.getID(CompoundIngredient.Serializer.INSTANCE) == null)
            CraftingHelper.register(new ResourceLocation("forge:compound"), CompoundIngredient.Serializer.INSTANCE);
        // Registrate's initializer expects FML's name mapper in a plain, named JVM.
        try (var names = mockStatic(ObfuscationReflectionHelper.class, CALLS_REAL_METHODS)) {
            names.when(() -> ObfuscationReflectionHelper.remapName(any(), anyString()))
                    .thenAnswer(call -> call.getArgument(1));
            Class.forName(AllRecipeTypes.class.getName());
        }
        LegacyRegistryFixture.reopenWrapper(BuiltInRegistries.RECIPE_SERIALIZER);
        LegacyRegistryFixture.reopenWrapper(BuiltInRegistries.RECIPE_TYPE);
        ((ForgeRegistry<?>) ForgeRegistries.RECIPE_SERIALIZERS).unfreeze();
        ((ForgeRegistry<?>) ForgeRegistries.RECIPE_TYPES).unfreeze();
        register(AllRecipeTypes.SEQUENCED_ASSEMBLY, new SequencedAssemblyRecipeSerializer());
        register(AllRecipeTypes.CUTTING, new ProcessingRecipeSerializer<>(CuttingRecipe::new));
        register(AllRecipeTypes.DEPLOYING, new ProcessingRecipeSerializer<>(DeployerApplicationRecipe::new));
        register(AllRecipeTypes.PRESSING, new ProcessingRecipeSerializer<>(PressingRecipe::new));
        register(AllRecipeTypes.FILLING, new ProcessingRecipeSerializer<>(FillingRecipe::new));
        register(AllRecipeTypes.MIXING, new ProcessingRecipeSerializer<>(MixingRecipe::new));
        register(AllRecipeTypes.SPLASHING, new ProcessingRecipeSerializer<>(SplashingRecipe::new));
        BuiltInRegistries.RECIPE_SERIALIZER.freeze();
        BuiltInRegistries.RECIPE_TYPE.freeze();
    }

    private static void register(AllRecipeTypes type, RecipeSerializer<?> serializer) throws Exception {
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, type.getId(), serializer);
        Registry.register(BuiltInRegistries.RECIPE_TYPE, type.getId(), RecipeType.simple(type.getId()));
        var update = RegistryObject.class.getDeclaredMethod("updateReference", IForgeRegistry.class);
        update.setAccessible(true);
        for (String name : List.of("serializerObject", "typeObject")) {
            var field = AllRecipeTypes.class.getDeclaredField(name);
            field.setAccessible(true);
            update.invoke(field.get(type), name.equals("serializerObject")
                    ? ForgeRegistries.RECIPE_SERIALIZERS : ForgeRegistries.RECIPE_TYPES);
        }
    }

    private static void collectItems(JsonElement json, Set<ResourceLocation> items) {
        if (json.isJsonArray()) json.getAsJsonArray().forEach(child -> collectItems(child, items));
        if (!json.isJsonObject()) return;
        JsonObject object = json.getAsJsonObject();
        if (object.has("item")) items.add(new ResourceLocation(object.get("item").getAsString()));
        object.entrySet().forEach(entry -> collectItems(entry.getValue(), items));
    }

    private static void stressPort(ShapedRecipe recipe) {
        CraftingContainer grid = mock(CraftingContainer.class);
        when(grid.getWidth()).thenReturn(3);
        when(grid.getHeight()).thenReturn(3);
        when(grid.getContainerSize()).thenReturn(9);
        ItemStack[] slots = {
                new ItemStack(item("create:brass_sheet")), ItemStack.EMPTY, new ItemStack(item("create:brass_sheet")),
                new ItemStack(item("create:shaft")), new ItemStack(item("create:bound_cardboard_block")),
                new ItemStack(item("create:shaft")), new ItemStack(item("create:andesite_alloy")),
                new ItemStack(item("create:andesite_alloy")), new ItemStack(item("create:andesite_alloy"))};
        when(grid.getItem(anyInt())).thenAnswer(call -> slots[(int) call.getArgument(0)]);
        for (String gauge : List.of("create:speedometer", "create:stressometer")) {
            slots[1] = new ItemStack(item(gauge));
            require(recipe.matches(grid, null), "Stress port does not accept " + gauge);
        }
        slots[1] = new ItemStack(Items.STICK);
        require(!recipe.matches(grid, null), "Stress port accepts an unrelated item in the gauge slot");
        require(recipe.getResultItem(RegistryAccess.EMPTY).is(item("create_nested_factory:nested_stress_port"))
                        && recipe.getResultItem(RegistryAccess.EMPTY).getCount() == 1,
                "Stress port result changed");
    }

    private static Item item(String name) {
        return BuiltInRegistries.ITEM.get(new ResourceLocation(name));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("create_nested_factory", path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

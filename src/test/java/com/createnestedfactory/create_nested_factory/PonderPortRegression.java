package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.ponder.PonderNbt;
import com.createnestedfactory.create_nested_factory.ponder.generated.GeneratedPonderIndex;
import com.createnestedfactory.create_nested_factory.ponder.generated.GeneratedPonderPlugin;
import com.google.gson.JsonParser;
import net.createmod.ponder.api.registration.MultiSceneBuilder;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.scene.PonderStoryBoard;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Exercises exported data with Minecraft's real NBT/item readers and checks registration/resources. */
public final class PonderPortRegression {
    private static final String PREFIX = "assets/create_nested_factory/ponder/generated/ponderer/";
    private static final List<String> STRUCTURES = List.of("1", "2", "nested_factory_basic", "nested_logistic",
        "nested_logistic2", "nested_logistic_fozen", "nested_nested_space", "passage", "passage_open", "passage_signed");
    private static final Set<String> COMPONENTS = Set.of("factory_passage", "nested_extension_interface",
        "nested_factory", "nested_port", "nested_stress_port");
    private static int checkedStacks;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<CompoundTag> data = new ArrayList<>();
        for (String structure : STRUCTURES) {
            try (var input = PonderPortRegression.class.getClassLoader().getResourceAsStream(PREFIX + structure + ".nbt")) {
                require(input != null, "Missing structure: " + structure);
                CompoundTag root = NbtIo.readCompressed(input);
                require(root.getInt("DataVersion") == 3465, "Wrong data version: " + structure);
                require(!root.getList("palette", Tag.TAG_COMPOUND).isEmpty(), "Empty palette: " + structure);
                int states = root.getList("palette", Tag.TAG_COMPOUND).size();
                for (Tag entry : root.getList("blocks", Tag.TAG_COMPOUND)) {
                    CompoundTag block = (CompoundTag) entry;
                    require(block.getInt("state") >= 0 && block.getInt("state") < states,
                        "Invalid palette index: " + structure);
                }
                data.add(root);
            }
        }

        // Parse the actual embedded patches with Minecraft's SNBT parser, including every belt inventory.
        int inlinePatches = 0;
        try (var sources = Files.walk(Path.of("src/main/java/com/createnestedfactory/create_nested_factory/ponder/generated/scenes"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String value : javaStrings(Files.readString(source))) {
                    if (value.startsWith("{")) {
                        data.add(PonderNbt.parseTag(value));
                        inlinePatches++;
                    }
                }
            }
        }
        require(inlinePatches > 0, "No embedded patches checked");
        Set<ResourceLocation> items = new LinkedHashSet<>();
        for (CompoundTag root : data) collectItems(root, items);
        LegacyRegistryFixture.reopenWrapper(BuiltInRegistries.ITEM);
        ((ForgeRegistry<?>) ForgeRegistries.ITEMS).unfreeze();
        for (ResourceLocation item : items) {
            if (!BuiltInRegistries.ITEM.containsKey(item)) {
                Registry.register(BuiltInRegistries.ITEM, item, new Item(new Item.Properties()));
            }
        }
        BuiltInRegistries.ITEM.freeze();
        for (CompoundTag root : data) checkStacks(root);
        require(checkedStacks >= 19, "Exported item stacks were not exercised");
        CompoundTag unrelated = PonderNbt.parseTag("{count:7,Other:{id:\"example:counter\",Value:9},Item:{id:\"minecraft:iron_ingot\",Count:3b,tag:{CustomModelData:4}}}");
        require(unrelated.getInt("count") == 7 && !unrelated.contains("Count"), "Non-item count was changed");
        ItemStack legacy = ItemStack.of(unrelated.getCompound("Item"));
        require(legacy.getCount() == 3 && legacy.getTag().getInt("CustomModelData") == 4,
            "Existing 1.20.1 item data changed");

        registrations();
        for (String language : List.of("en_us", "zh_cn")) {
            try (var input = PonderPortRegression.class.getClassLoader().getResourceAsStream(
                "assets/create_nested_factory/lang/" + language + ".json")) {
                require(input != null, "Missing language " + language);
                var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                require(json.keySet().stream().filter(key -> key.contains(".ponder.")).count() == 89,
                    "Missing Ponder translations in " + language);
                require(json.has("gui.create_nested_factory.ponder.components_heading"), "Missing category heading");
            }
        }
        System.out.println("Ponder: 8 storyboards/5 components, 10 structures, " + inlinePatches
            + " inline patches, " + checkedStacks + " readable item stacks and both translations passed.");
    }

    private static List<String> javaStrings(String source) {
        List<String> strings = new ArrayList<>();
        for (int start = 0; start < source.length(); start++) {
            if (source.charAt(start) != '"') continue;
            int end = start + 1;
            while (end < source.length() && source.charAt(end) != '"') {
                end += source.charAt(end) == '\\' ? 2 : 1;
            }
            require(end < source.length(), "Unterminated exported string");
            strings.add(JsonParser.parseString(source.substring(start, end + 1)).getAsString());
            start = end;
        }
        return strings;
    }

    @SuppressWarnings("unchecked")
    private static void registrations() {
        PonderSceneRegistrationHelper<ResourceLocation> helper = mock(PonderSceneRegistrationHelper.class);
        Map<String, Integer> registered = new LinkedHashMap<>();
        doAnswer(call -> {
            List<ResourceLocation> components = new ArrayList<>();
            ((Iterable<ResourceLocation>) call.getArgument(0)).forEach(components::add);
            MultiSceneBuilder multi = mock(MultiSceneBuilder.class);
            doAnswer(story -> {
                ResourceLocation asset = story.getArgument(0);
                require(asset.getNamespace().equals("create_nested_factory"), "Wrong asset namespace");
                require(PonderPortRegression.class.getClassLoader().getResource(
                    "assets/" + asset.getNamespace() + "/ponder/" + asset.getPath() + ".nbt") != null,
                    "Missing storyboard asset " + asset);
                for (ResourceLocation component : components) {
                    registered.merge(component.getPath(), 1, Integer::sum);
                }
                return multi;
            }).when(multi).addStoryBoard(any(ResourceLocation.class), any(PonderStoryBoard.class), any(ResourceLocation[].class));
            return multi;
        }).when(helper).forComponents(any(Iterable.class));
        new GeneratedPonderPlugin().registerScenes(helper);
        require(registered.keySet().equals(COMPONENTS), "Component bindings changed: " + registered);
        require(registered.get("nested_factory") == 4 && registered.values().stream().mapToInt(Integer::intValue).sum() == 8,
            "Missing storyboards: " + registered);
        PonderTagRegistrationHelper<ResourceLocation> tags = mock(PonderTagRegistrationHelper.class);
        Set<String> tagged = new LinkedHashSet<>();
        doAnswer(call -> {
            tagged.add(((ResourceLocation) call.getArgument(0)).getPath());
            require(call.getArgument(1).equals(new ResourceLocation("create_nested_factory", "ponderer_exported")),
                "Wrong component tag");
            return null;
        }).when(tags).addTagToComponent(any(ResourceLocation.class), any(ResourceLocation.class));
        GeneratedPonderIndex.registerTags(tags);
        require(tagged.equals(COMPONENTS), "Missing category bindings");
    }

    private static void collectItems(Tag tag, Set<ResourceLocation> items) {
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("id", Tag.TAG_STRING) && compound.contains("Count", Tag.TAG_ANY_NUMERIC)) {
                items.add(new ResourceLocation(compound.getString("id")));
            }
            for (String key : compound.getAllKeys()) collectItems(compound.get(key), items);
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) collectItems(child, items);
        }
    }

    private static void checkStacks(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("id", Tag.TAG_STRING)) {
                require(!compound.contains("count"), "A modern item count survived conversion");
                if (compound.contains("Count")) {
                    require(compound.contains("Count", Tag.TAG_BYTE), "Item count has the wrong NBT type");
                    ItemStack stack = ItemStack.of(compound);
                    require(!stack.isEmpty() && stack.getCount() == compound.getByte("Count"), "Empty/misread item: " + compound);
                    checkedStacks++;
                }
            }
            for (String key : compound.getAllKeys()) checkStacks(compound.get(key));
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) checkStacks(child);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

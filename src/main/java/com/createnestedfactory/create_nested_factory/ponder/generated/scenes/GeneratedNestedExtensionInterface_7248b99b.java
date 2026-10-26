// Imported from the 1.21.1 Ponderer export; adapted for Minecraft 1.20.1.
// Re-import with tools/port_ponder_1_20_1.py; keep the source checkout read-only.
package com.createnestedfactory.create_nested_factory.ponder.generated.scenes;

import com.createnestedfactory.create_nested_factory.ponder.generated.GeneratedPonderAttribution;
import com.createnestedfactory.create_nested_factory.ponder.generated.GeneratedPonderSupport;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import java.util.Map;

public final class GeneratedNestedExtensionInterface_7248b99b {
    private GeneratedNestedExtensionInterface_7248b99b() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation[] tags = new ResourceLocation[]{new ResourceLocation("create_nested_factory", "ponderer_exported")};
        var multi = helper.forComponents(java.util.List.of(new ResourceLocation("create_nested_factory", "nested_extension_interface")));
        multi.addStoryBoard(new ResourceLocation("create_nested_factory", "generated/ponderer/nested_factory_basic"), GeneratedNestedExtensionInterface_7248b99b::storyboard$0, tags);
    }

    public static void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation tag = GeneratedPonderAttribution.tag();
        helper.addTagToComponent(new ResourceLocation("create_nested_factory", "nested_extension_interface"), tag);
    }

    private static void storyboard$0(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("nested_extension_interface", "Nested Extension Interface");
        GeneratedPonderSupport.Context context = new GeneratedPonderSupport.Context();
        GeneratedPonderSupport.preScanBounds(scene, new BlockPos(1, 1, 11), new BlockPos(2, 2, 13));
        GeneratedPonderSupport.showStructure(scene, context, null, null, 0.5f, 270.0f);
        GeneratedPonderSupport.zoomScene(scene, new Vec3(3.0, 1.5, 12.5), 1.5f, 1);
        GeneratedPonderSupport.showText(scene, "Aim at one face of the Nested Factory", new Vec3(3.0, 1.5, 12.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.showControls(scene, new Vec3(3.0, 1.5, 12.5), "right", 80, "right", null, null, true, false);
        GeneratedPonderSupport.showText(scene, "Sneak and right-click that face to place a Nested Interface", new Vec3(3.0, 1.5, 12.5), 80, null, true);
        scene.idle(90);
        GeneratedPonderSupport.setBlock(scene, context, "create_nested_factory:nested_extension_interface", null, new BlockPos(2, 1, 12), new BlockPos(2, 1, 12), null, false, false, "simultaneous", 20, 1, false, null, "east");
        scene.idle(20);
        GeneratedPonderSupport.showText(scene, "The Nested Extension Interface inherits that face's settings", new Vec3(3.0, 1.5, 12.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.showText(scene, "It can provide additional input and output ports for that face", new Vec3(3.0, 1.5, 12.5), 80, null, true);
        GeneratedPonderSupport.setBlock(scene, context, "create:brass_funnel", Map.ofEntries(Map.entry("waterlogged", "false"), Map.entry("powered", "false"), Map.entry("facing", "south"), Map.entry("extracting", "true")), new BlockPos(2, 1, 13), new BlockPos(2, 1, 13), "{Filter:{},FilterAmount:64,TransferCooldown:0,UpTo:1b}", false, false, "simultaneous", 20, 1, false, null, "down");
        scene.idle(10);
        GeneratedPonderSupport.setBlock(scene, context, "create:brass_funnel", Map.ofEntries(Map.entry("waterlogged", "false"), Map.entry("powered", "false"), Map.entry("facing", "west"), Map.entry("extracting", "true")), new BlockPos(1, 1, 12), new BlockPos(1, 1, 12), "{Filter:{},FilterAmount:64,TransferCooldown:0,UpTo:1b}", false, false, "simultaneous", 20, 1, false, null, "down");
        scene.idle(10);
        GeneratedPonderSupport.setBlock(scene, context, "create:brass_funnel", Map.ofEntries(Map.entry("waterlogged", "false"), Map.entry("powered", "false"), Map.entry("facing", "north"), Map.entry("extracting", "true")), new BlockPos(2, 1, 11), new BlockPos(2, 1, 11), "{Filter:{},FilterAmount:64,TransferCooldown:0,UpTo:1b}", false, false, "simultaneous", 20, 1, false, null, "down");
        scene.idle(60);
        GeneratedPonderSupport.destroyBlock(scene, context, new BlockPos(2, 1, 13), new BlockPos(2, 1, 13), null);
        GeneratedPonderSupport.destroyBlock(scene, context, new BlockPos(1, 1, 12), new BlockPos(1, 1, 12), null);
        GeneratedPonderSupport.destroyBlock(scene, context, new BlockPos(2, 1, 11), new BlockPos(2, 1, 11), null);
        scene.idle(20);
        GeneratedPonderSupport.setBlock(scene, context, "create:creative_motor", Map.ofEntries(Map.entry("facing", "down")), new BlockPos(2, 2, 12), new BlockPos(2, 2, 12), "{HasAttachedComputer:0b,Network:{AddedCapacity:16384.0f,Capacity:262144.0f,Id:21440476569541L,Size:1,Stress:0.0f},ScrollValue:16,Speed:-16.0f}", false, false, "simultaneous", 20, 1, false, null, "down");
        GeneratedPonderSupport.showText(scene, "You can also supply rotational power", new Vec3(2.0, 1.5, 12.5), 40, null, true);
        scene.idle(40);
        GeneratedPonderSupport.destroyBlock(scene, context, new BlockPos(2, 2, 12), new BlockPos(2, 2, 12), null);
        scene.idle(20);
        GeneratedPonderSupport.setBlock(scene, context, "minecraft:redstone_block", null, new BlockPos(2, 2, 12), new BlockPos(2, 2, 12), null, false, false, "simultaneous", 20, 1, false, null, "down");
        GeneratedPonderSupport.indicateRedstone(scene, new BlockPos(2, 1, 12));
        GeneratedPonderSupport.modifyBlockEntity(scene, Map.ofEntries(Map.entry("powered", "true"), Map.entry("facing", "down")), null, null, new BlockPos(2, 1, 12), new BlockPos(2, 1, 12));
        GeneratedPonderSupport.showText(scene, "Apply a redstone signal to disable transfers through the interface", new Vec3(2.0, 1.5, 12.5), 80, null, true);
        scene.idle(90);
        GeneratedPonderSupport.showControls(scene, new Vec3(2.0, 1.5, 12.5), "right", 80, null, "create:goggles", null, false, false);
        GeneratedPonderSupport.showText(scene, "Use Engineer's Goggles to view more information", new Vec3(2.0, 1.5, 12.5), 80, null, true);
        scene.idle(90);
    }

}

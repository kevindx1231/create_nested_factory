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

public final class GeneratedNestedStressPort_04c9d6cf {
    private GeneratedNestedStressPort_04c9d6cf() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation[] tags = new ResourceLocation[]{new ResourceLocation("create_nested_factory", "ponderer_exported")};
        var multi = helper.forComponents(java.util.List.of(new ResourceLocation("create_nested_factory", "nested_stress_port")));
        multi.addStoryBoard(new ResourceLocation("create_nested_factory", "generated/ponderer/nested_factory_basic"), GeneratedNestedStressPort_04c9d6cf::storyboard$0, tags);
    }

    public static void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation tag = GeneratedPonderAttribution.tag();
        helper.addTagToComponent(new ResourceLocation("create_nested_factory", "nested_stress_port"), tag);
    }

    private static void storyboard$0(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("nested_stress_port", "Nested Stress Port");
        GeneratedPonderSupport.Context context = new GeneratedPonderSupport.Context();
        GeneratedPonderSupport.preScanBounds(scene, new BlockPos(3, 1, 2), new BlockPos(3, 1, 13));
        scene.addKeyframe();
        GeneratedPonderSupport.showStructure(scene, context, null, null, 0.5f, 270.0f);
        GeneratedPonderSupport.zoomScene(scene, new Vec3(6.5, 0.0, 2.5), 1.5f, 0);
        scene.idle(20);
        GeneratedPonderSupport.setBlock(scene, context, "create_nested_factory:nested_stress_port", Map.ofEntries(Map.entry("facing", "south")), new BlockPos(3, 1, 4), new BlockPos(3, 1, 4), "{IncomingSpeed:0.0f,Speed:0.0f}", false, false, "simultaneous", 20, 1, false, null, "down");
        scene.idle(20);
        GeneratedPonderSupport.showText(scene, "Stress Ports can also transmit rotational power", new Vec3(3.0, 1.5, 4.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.showText(scene, "Rotational power can only be supplied from the parent factory to the interior", new Vec3(3.0, 1.5, 4.5), 80, null, true);
        scene.idle(90);
        GeneratedPonderSupport.zoomScene(scene, new Vec3(3.0, 1.5, 12.5), 1.5f, 20);
        GeneratedPonderSupport.setBlock(scene, context, "create:creative_motor", Map.ofEntries(Map.entry("facing", "north")), new BlockPos(3, 1, 13), new BlockPos(3, 1, 13), "{HasAttachedComputer:0b,Network:{AddedCapacity:16384.0f,Capacity:262144.0f,Id:21715354460101L,Size:2,Stress:0.0f},ScrollValue:16,Speed:-16.0f}", false, false, "simultaneous", 20, 1, false, null, "down");
        GeneratedPonderSupport.showText(scene, "Supply rotational power to any face of the Nested Factory", new Vec3(3.0, 1.5, 13.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.showText(scene, "There is no need to configure the face's input or output mode", new Vec3(3.0, 1.5, 13.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.zoomScene(scene, new Vec3(6.5, 0.0, 2.5), 1.5f, 20);
        GeneratedPonderSupport.setBlock(scene, context, "create:speedometer", Map.ofEntries(Map.entry("facing", "up"), Map.entry("axis_along_first", "false")), new BlockPos(3, 1, 5), new BlockPos(3, 1, 5), "{Color:5898073,ConnectedToExtraKinetics:0b,HasAttachedComputer:0b,Network:{Capacity:0.0f,Id:90159953502337L,Size:2,Stress:0.0f},Source:[I;328,129,6],Speed:-16.0f,Value:0.24000001f}", false, false, "simultaneous", 20, 1, false, null, "down");
        GeneratedPonderSupport.setBlock(scene, context, "create:speedometer", Map.ofEntries(Map.entry("facing", "up"), Map.entry("axis_along_first", "false")), new BlockPos(3, 1, 3), new BlockPos(3, 1, 3), "{Color:5898073,ConnectedToExtraKinetics:0b,HasAttachedComputer:0b,Network:{Capacity:0.0f,Id:90159953502337L,Size:3,Stress:0.0f},Source:[I;328,129,6],Speed:-16.0f,Value:0.24000001f}", false, false, "simultaneous", 20, 1, false, null, "down");
        scene.idle(10);
        GeneratedPonderSupport.setBlock(scene, context, "create:cogwheel", Map.ofEntries(Map.entry("waterlogged", "false"), Map.entry("axis", "z")), new BlockPos(3, 1, 6), new BlockPos(3, 1, 6), "{ConnectedToExtraKinetics:0b,Network:{Capacity:262144.0f,Id:21165598662597L,Size:2,Stress:0.0f},Source:[I;76,-59,-43],Speed:-16.0f}", false, false, "simultaneous", 20, 1, false, null, "down");
        GeneratedPonderSupport.setBlock(scene, context, "create:cogwheel", Map.ofEntries(Map.entry("waterlogged", "false"), Map.entry("axis", "z")), new BlockPos(3, 1, 2), new BlockPos(3, 1, 2), "{ConnectedToExtraKinetics:0b,Network:{Capacity:262144.0f,Id:21165598662597L,Size:2,Stress:0.0f},Source:[I;76,-59,-43],Speed:-16.0f}", false, false, "simultaneous", 20, 1, false, null, "down");
        scene.idle(20);
        GeneratedPonderSupport.highlightSection(scene, "input", new BlockPos(3, 1, 13), new BlockPos(3, 1, 13), 100);
        GeneratedPonderSupport.highlightSection(scene, "input", new BlockPos(3, 1, 4), new BlockPos(3, 1, 4), 100);
        GeneratedPonderSupport.showText(scene, "The Stress Port synchronizes the input's rotation direction, speed, and stress capacity", new Vec3(3.0, 1.5, 4.5), 100, null, true);
        scene.idle(110);
        GeneratedPonderSupport.showControls(scene, new Vec3(3.0, 1.5, 4.5), "right", 60, null, "create:goggles", null, false, false);
        GeneratedPonderSupport.showText(scene, "Use Engineer's Goggles to view more information", new Vec3(3.0, 1.5, 4.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.zoomScene(scene, new Vec3(3.0, 1.5, 12.5), 1.5f, 20);
        GeneratedPonderSupport.showText(scene, "Reverse the rotation direction", new Vec3(3.0, 1.5, 13.5), 60, null, true);
        GeneratedPonderSupport.modifyBlockEntity(scene, Map.ofEntries(Map.entry("facing", "up"), Map.entry("axis_along_first", "false")), "{Color:5898073,ConnectedToExtraKinetics:0b,HasAttachedComputer:0b,Network:{Capacity:0.0f,Id:90159953502337L,Size:2,Stress:0.0f},Source:[I;328,129,6],Speed:16.0f,Value:0.24000001f}", null, new BlockPos(3, 1, 5), new BlockPos(3, 1, 5));
        GeneratedPonderSupport.modifyBlockEntity(scene, Map.ofEntries(Map.entry("waterlogged", "false"), Map.entry("axis", "z")), "{ConnectedToExtraKinetics:0b,Network:{Capacity:262144.0f,Id:21165598662597L,Size:2,Stress:0.0f},Source:[I;76,-59,-43],Speed:16.0f}", null, new BlockPos(3, 1, 6), new BlockPos(3, 1, 6));
        GeneratedPonderSupport.modifyBlockEntity(scene, Map.ofEntries(Map.entry("waterlogged", "false"), Map.entry("axis", "z")), "{ConnectedToExtraKinetics:0b,Network:{Capacity:262144.0f,Id:21165598662597L,Size:2,Stress:0.0f},Source:[I;76,-59,-43],Speed:16.0f}", null, new BlockPos(3, 1, 2), new BlockPos(3, 1, 2));
        scene.idle(60);
        GeneratedPonderSupport.showText(scene, "256 RPM", new Vec3(3.0, 1.5, 13.5), 60, null, true);
        GeneratedPonderSupport.modifyBlockEntity(scene, Map.ofEntries(Map.entry("facing", "up"), Map.entry("axis_along_first", "false")), "{Color:16744447,ConnectedToExtraKinetics:0b,HasAttachedComputer:0b,Network:{Capacity:0.0f,Id:90159953502337L,Size:4,Stress:0.0f},Source:[I;328,129,6],Speed:-256.0f,Value:1.125f}", null, new BlockPos(3, 1, 5), new BlockPos(3, 1, 5));
        GeneratedPonderSupport.modifyBlockEntity(scene, Map.ofEntries(Map.entry("facing", "up"), Map.entry("axis_along_first", "false")), "{Color:16744447,ConnectedToExtraKinetics:0b,HasAttachedComputer:0b,Network:{Capacity:0.0f,Id:90159953502337L,Size:4,Stress:0.0f},Source:[I;328,129,6],Speed:-256.0f,Value:1.125f}", null, new BlockPos(3, 1, 3), new BlockPos(3, 1, 3));
        GeneratedPonderSupport.modifyBlockEntity(scene, Map.ofEntries(Map.entry("waterlogged", "false"), Map.entry("axis", "z")), "{ConnectedToExtraKinetics:0b,Network:{Capacity:4194304.0f,Id:21165598662597L,Size:2,Stress:0.0f},Source:[I;76,-59,-43],Speed:-256.0f}", null, new BlockPos(3, 1, 6), new BlockPos(3, 1, 6));
        GeneratedPonderSupport.modifyBlockEntity(scene, Map.ofEntries(Map.entry("waterlogged", "false"), Map.entry("axis", "z")), "{ConnectedToExtraKinetics:0b,Network:{Capacity:4194304.0f,Id:21165598662597L,Size:2,Stress:0.0f},Source:[I;76,-59,-43],Speed:-256.0f}", null, new BlockPos(3, 1, 2), new BlockPos(3, 1, 2));
        scene.idle(70);
        GeneratedPonderSupport.showText(scene, "In Black Box Mode, only the amount of input stress capacity is checked, not the rotation direction", new Vec3(3.0, 1.5, 12.5), 100, null, true);
        scene.idle(110);
    }

}

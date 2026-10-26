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

public final class GeneratedFactoryPassage_f7c6ad50 {
    private GeneratedFactoryPassage_f7c6ad50() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation[] tags = new ResourceLocation[]{new ResourceLocation("create_nested_factory", "ponderer_exported")};
        var multi = helper.forComponents(java.util.List.of(new ResourceLocation("create_nested_factory", "factory_passage")));
        multi.addStoryBoard(new ResourceLocation("create_nested_factory", "generated/ponderer/nested_factory_basic"), GeneratedFactoryPassage_f7c6ad50::storyboard$0, tags);
    }

    public static void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation tag = GeneratedPonderAttribution.tag();
        helper.addTagToComponent(new ResourceLocation("create_nested_factory", "factory_passage"), tag);
    }

    private static void storyboard$0(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("factory_passage", "Factory Passage");
        GeneratedPonderSupport.Context context = new GeneratedPonderSupport.Context();
        GeneratedPonderSupport.preScanBounds(scene, new BlockPos(0, 0, 4), new BlockPos(6, 6, 15));
        GeneratedPonderSupport.showStructure(scene, context, null, null, 0.5f, 270.0f);
        GeneratedPonderSupport.zoomScene(scene, new Vec3(3.0, 1.5, 12.5), 1.5f, 0);
        GeneratedPonderSupport.showText(scene, "Find the usual way of entering inconvenient?", new Vec3(3.0, 1.5, 12.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.showControls(scene, new Vec3(3.0, 1.5, 12.5), "right", 60, null, "create_nested_factory:factory_passage", null, false, false);
        GeneratedPonderSupport.showText(scene, "Aim at the Nested Factory while holding a Factory Passage", new Vec3(3.0, 1.5, 12.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.showControls(scene, new Vec3(3.0, 1.5, 12.5), "right", 40, "right", null, null, true, false);
        GeneratedPonderSupport.showText(scene, "Sneak and right-click to bind it.", new Vec3(3.0, 1.5, 12.5), 40, null, true);
        GeneratedPonderSupport.highlightSection(scene, "input", new BlockPos(3, 1, 12), new BlockPos(3, 1, 12), 50);
        scene.idle(50);
        GeneratedPonderSupport.showExtraStructure(scene, context, new ResourceLocation("create_nested_factory", "ponder/generated/ponderer/passage.nbt"), new BlockPos(2, 1, 10), 0, false, false, false, "simultaneous", 20, 1, false, null, "down");
        GeneratedPonderSupport.showText(scene, "Keep the bound Factory Passage in your main hand before placing it", new Vec3(2.0, 1.5, 10.5), 80, null, true);
        scene.idle(90);
        GeneratedPonderSupport.showControls(scene, new Vec3(2.0, 1.5, 10.5), "right", 60, "right", null, null, false, false);
        GeneratedPonderSupport.showText(scene, "Right-click to open or close the passage door", new Vec3(2.0, 1.5, 10.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.showExtraStructure(scene, context, new ResourceLocation("create_nested_factory", "ponder/generated/ponderer/passage_open.nbt"), new BlockPos(2, 1, 10), 0, false, true, false, "none", null, null, null, null, null);
        scene.idle(30);
        GeneratedPonderSupport.showText(scene, "This is for demonstration only; only players can actually be teleported", new Vec3(2.0, 1.5, 10.5), 60, null, true);
        scene.idle(70);
        scene.addKeyframe();
        GeneratedPonderSupport.createEntity(scene, context, "minecraft:creeper", new Vec3(2.5, 1.0, 10.5), new Vec3(5.5, 2.0, 10.5), null, null, null, null, "simultaneous", null, "east");
        scene.idle(10);
        GeneratedPonderSupport.clearEntities(scene, context, false, null, null, new BlockPos(2, 1, 10), new BlockPos(2, 2, 10), null, null, null);
        GeneratedPonderSupport.createEntity(scene, context, "minecraft:creeper", new Vec3(6.5, 1.0, 7.5), new Vec3(0.5, 2.0, 7.5), null, null, null, null, "simultaneous", null, "down");
        scene.idle(20);
        GeneratedPonderSupport.zoomScene(scene, new Vec3(6.5, 0.0, 2.5), 1.5f, 20);
        GeneratedPonderSupport.showText(scene, "Right-click to open or close the passage door", new Vec3(6.5, 0.0, 2.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.showControls(scene, new Vec3(6.5, 0.0, 2.5), "right", 60, "right", null, null, true, false);
        GeneratedPonderSupport.showText(scene, "Sneak and right-click a wall to return to the passage", new Vec3(6.5, 0.0, 2.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.clearEntities(scene, context, false, null, null, new BlockPos(6, 0, 7), new BlockPos(6, 2, 7), null, null, null);
        GeneratedPonderSupport.createEntity(scene, context, "minecraft:creeper", new Vec3(2.5, 1.0, 10.5), new Vec3(0.5, 2.0, 10.5), null, null, null, null, "simultaneous", null, "up");
        scene.idle(20);
        GeneratedPonderSupport.showExtraStructure(scene, context, new ResourceLocation("create_nested_factory", "ponder/generated/ponderer/passage_open.nbt"), new BlockPos(3, 1, 4), 0, false, false, false, "simultaneous", 20, 1, false, null, "down");
        GeneratedPonderSupport.showText(scene, "Factory Passages inside the space automatically bind to the external passage", new Vec3(3.0, 1.5, 4.5), 100, null, true);
        scene.idle(10);
        GeneratedPonderSupport.showExtraStructure(scene, context, new ResourceLocation("create_nested_factory", "ponder/generated/ponderer/passage.nbt"), new BlockPos(5, 1, 10), 0, false, false, false, "simultaneous", 20, 1, false, null, "down");
        scene.idle(20);
        GeneratedPonderSupport.highlightSection(scene, "input", new BlockPos(3, 1, 4), new BlockPos(3, 2, 4), 80);
        GeneratedPonderSupport.highlightSection(scene, "input", new BlockPos(2, 1, 10), new BlockPos(2, 2, 10), 80);
        GeneratedPonderSupport.highlightSection(scene, "input", new BlockPos(5, 1, 10), new BlockPos(5, 2, 10), 80);
        scene.idle(20);
        GeneratedPonderSupport.clearEntities(scene, context, false, null, null, new BlockPos(2, 1, 10), new BlockPos(2, 2, 10), null, null, null);
        GeneratedPonderSupport.createEntity(scene, context, "minecraft:creeper", new Vec3(3.5, 1.0, 4.5), new Vec3(0.5, 2.0, 4.5), null, null, null, null, "simultaneous", null, "up");
        scene.idle(40);
        GeneratedPonderSupport.clearEntities(scene, context, false, null, null, new BlockPos(3, 0, 4), new BlockPos(3, 2, 4), null, null, null);
        GeneratedPonderSupport.createEntity(scene, context, "minecraft:creeper", new Vec3(2.5, 1.0, 10.5), new Vec3(0.5, 2.0, 10.5), null, null, null, null, "simultaneous", null, "up");
        scene.idle(20);
        GeneratedPonderSupport.showText(scene, "Only one internal passage can be active in each space", new Vec3(3.0, 1.5, 4.5), 80, null, true);
        scene.idle(90);
        GeneratedPonderSupport.hideSection(scene, context, new BlockPos(0, 1, 9), new BlockPos(6, 6, 15), 40, "up");
        GeneratedPonderSupport.clearEntities(scene, context, false, null, null, new BlockPos(2, 0, 10), new BlockPos(2, 2, 10), "simultaneous", null, "up");
        scene.idle(50);
        scene.addKeyframe();
        GeneratedPonderSupport.setBlock(scene, context, "create_nested_factory:nested_factory", Map.ofEntries(Map.entry("facing", "west")), new BlockPos(3, 1, 6), new BlockPos(3, 1, 6), "{ActiveOverclockTier:0,AncestorFrozen:0b,BlackboxRuntimeScheduler:{RouteRuntimes:[],SchedulerFormatVersion:5,StartupCapital:[]},BlueprintApplied:0b,Bounds:[I;0,0,0,0,0,0],BoundsVersion:2,ChildFactoryId:\"\",Enterable:1b,FaceMode0:\"none\",FaceMode1:\"none\",FaceMode2:\"none\",FaceMode3:\"none\",FaceMode4:\"none\",FaceMode5:\"none\",FactoryId:\"1ee51c33-5961-45ff-a1e9-8febe124de9a\",FactoryPlan:{CycleTicks:0,ExternalStressSUTicks:0.0d,Fingerprint:\"1f75ad2782714d4fff24cb74ce2b0b9c6ef4b9442f7be0c37db165160d5f712e\",FluidInputs:[],InternalGenerationSUTicks:0.0d,ItemInputs:[],NaturalWaitTicks:0,PeakConsumedSU:0.0f,PeakExternalStressSU:0.0f,PeakInternalGeneratedSU:0.0f,PlanFormatVersion:9,ProcessingFluidOutputs:[],ProcessingItemOutputs:[],ProcessingToolDamageCosts:[],RegenerativeFluidOutputs:[],RegenerativeItemOutputs:[],RegenerativeToolDamageCosts:[],SourceKind:\"NONE\",SourceProofs:[],StartupCapitalItems:[],StatisticalOutput:0b,VerifiedCycles:0},FactoryTransit:{Channel0:{InputFluids:{Entries:[]},InputItemCredits:64L,InputItems:{Values:[]},OutputFluidCapacity:9223372036854775807L,OutputFluids:{Entries:[]},OutputItemCapacity:64L,OutputItemCredits:64L,OutputItems:{Values:[]},SealedInputFluids:{Entries:[]},SealedInputItems:{Values:[]}},Channel1:{InputFluids:{Entries:[]},InputItemCredits:64L,InputItems:{Values:[]},OutputFluidCapacity:9223372036854775807L,OutputFluids:{Entries:[]},OutputItemCapacity:64L,OutputItemCredits:64L,OutputItems:{Values:[]},SealedInputFluids:{Entries:[]},SealedInputItems:{Values:[]}},Channel2:{InputFluids:{Entries:[]},InputItemCredits:64L,InputItems:{Values:[]},OutputFluidCapacity:9223372036854775807L,OutputFluids:{Entries:[]},OutputItemCapacity:64L,OutputItemCredits:64L,OutputItems:{Values:[]},SealedInputFluids:{Entries:[]},SealedInputItems:{Values:[]}},Channel3:{InputFluids:{Entries:[]},InputItemCredits:64L,InputItems:{Values:[]},OutputFluidCapacity:9223372036854775807L,OutputFluids:{Entries:[]},OutputItemCapacity:64L,OutputItemCredits:64L,OutputItems:{Values:[]},SealedInputFluids:{Entries:[]},SealedInputItems:{Values:[]}},Channel4:{InputFluids:{Entries:[]},InputItemCredits:64L,InputItems:{Values:[]},OutputFluidCapacity:9223372036854775807L,OutputFluids:{Entries:[]},OutputItemCapacity:64L,OutputItemCredits:64L,OutputItems:{Values:[]},SealedInputFluids:{Entries:[]},SealedInputItems:{Values:[]}},Channel5:{InputFluids:{Entries:[]},InputItemCredits:64L,InputItems:{Values:[]},OutputFluidCapacity:9223372036854775807L,OutputFluids:{Entries:[]},OutputItemCapacity:64L,OutputItemCredits:64L,OutputItems:{Values:[]},SealedInputFluids:{Entries:[]},SealedInputItems:{Values:[]}},TransitFormatVersion:5},InvalidNested:0b,LearningStage:\"WARMUP\",Nested:0b,NestedRoomOrigin:0L,NestedSlotId:-1,NestedSlotX:0,NestedSlotZ:0,NestingDepth:0,OperationMode:\"chunk_loaded\",OverclockBatteries:[],OverclockBatteryFormat:2,ParentFactoryId:\"\",PortId0:0,PortId1:0,PortId2:0,PortId3:0,PortId4:0,PortId5:0,PortableBinding:0b,PowerProfile:{ConsumedSU:0.0f,GeneratedSU:0.0f,GeneratedSUExcludesRelayStress:1b,HasMeasuredExternalStressDemand:1b,MeasuredExternalStressDemandSU:0.0f},RootFactoryId:\"1ee51c33-5961-45ff-a1e9-8febe124de9a\",RootRoomAllocated:1b,RootRoomOrigin:87960930222208L,RootSlotId:5,SelectedOverclockTier:0,SiblingBlueprintOnly:0b,SimulatedIoFormatVersion:2,Speed:0.0f,TerminalBlueprintOnly:0b}", false, false, "simultaneous", 20, 1, false, null, "west");
        scene.idle(20);
        GeneratedPonderSupport.highlightSection(scene, "input", new BlockPos(3, 1, 4), new BlockPos(3, 2, 4), 60);
        GeneratedPonderSupport.highlightSection(scene, "input", new BlockPos(3, 1, 6), new BlockPos(3, 1, 6), 60);
        GeneratedPonderSupport.showText(scene, "Factory Passages support multi-level nesting", new Vec3(3.0, 1.5, 6.5), 60, null, true);
        scene.idle(70);
        GeneratedPonderSupport.showText(scene, "Right-click with an item to add a marker", new Vec3(3.0, 2.8, 4.8), 80, null, true);
        GeneratedPonderSupport.showExtraStructure(scene, context, new ResourceLocation("create_nested_factory", "ponder/generated/ponderer/passage_signed.nbt"), new BlockPos(3, 1, 4), 0, false, true, false, "none", null, null, null, null, null);
        scene.idle(90);
    }

}

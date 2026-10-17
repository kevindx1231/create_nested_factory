package com.createnestedfactory.create_nested_factory.client.ponder;

import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour.Mode;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Scripted cutaway: left = outside, right = Pocket (not a life-size room). */
public final class NestedFactoryScenes {
    private static final BlockPos FACTORY = new BlockPos(2, 1, 5);
    private static final BlockPos INPUT = new BlockPos(7, 1, 5);
    private static final BlockPos OUTPUT = new BlockPos(11, 1, 5);
    private static final BlockPos DEPOT = new BlockPos(9, 1, 5);
    private static final BlockPos PRESS = new BlockPos(9, 3, 5);
    private static final BlockPos STRESS = new BlockPos(9, 3, 3);
    private static final BlockPos CHILD = new BlockPos(10, 1, 7);
    private static final BlockPos TARGET = new BlockPos(2, 1, 8);

    private NestedFactoryScenes() {
    }

    public static void space(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = begin(builder, util, "factory_space", "One block, a whole factory");
        say(scene, FACTORY, "A Nested Factory connects to its own persistent interior space.");
        click(scene, FACTORY, false, ItemStack.EMPTY);
        say(scene, FACTORY, "Empty-hand right-click opens the control panel: name your factory and inspect its mode.");
        click(scene, FACTORY, true, ItemStack.EMPTY);
        room(scene, util);
        arrow(scene, center(FACTORY), center(INPUT), PonderPalette.INPUT, 100);
        say(scene, INPUT, "Sneak and right-click with an empty hand to enter. The right-hand cutaway represents the interior.");
        machines(scene, util);
        say(scene, PRESS, "Build a real production line here, just as you would in the outside world.");
        BlockPos wall = new BlockPos(8, 1, 2);
        click(scene, wall, true, ItemStack.EMPTY);
        highlight(scene, wall, PonderPalette.GREEN, 110);
        say(scene, wall, "To return one level, sneak and right-click an interior boundary wall with an empty hand.");
        scene.markAsFinished();
    }

    public static void ports(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = begin(builder, util, "factory_ports", "Moving items across the boundary");
        room(scene, util);
        externalLogistics(scene, util);
        say(scene, FACTORY, "Outside on the left, interior on the right: connect both sides of the factory boundary.");
        click(scene, FACTORY, false, ItemStack.EMPTY);
        face(scene, Direction.WEST, PonderPalette.INPUT);
        say(scene, FACTORY, "In the control panel, set the receiving face to INPUT and the delivery face to OUTPUT.");
        face(scene, Direction.EAST, PonderPalette.OUTPUT);
        scene.world().showSection(util.select().position(INPUT), Direction.DOWN);
        scene.world().showSection(util.select().position(OUTPUT), Direction.DOWN);
        highlight(scene, INPUT, PonderPalette.INPUT, 130);
        arrow(scene, center(FACTORY), center(INPUT), PonderPalette.INPUT, 130);
        say(scene, INPUT, "Match the interior Nested Port ID to the ID shown for the outside input face.");
        click(scene, OUTPUT, false, ItemStack.EMPTY);
        highlight(scene, OUTPUT, PonderPalette.OUTPUT, 130);
        say(scene, OUTPUT, "Empty-hand right-click cycles the port ID. Match the output port to the output face in the same way.");
        machines(scene, util);
        transfer(scene, center(new BlockPos(0, 1, 5)), center(FACTORY), new ItemStack(Items.IRON_INGOT));
        transfer(scene, center(INPUT), center(DEPOT).add(0, .6, 0), new ItemStack(Items.IRON_INGOT));
        say(scene, INPUT, "INPUT means into the factory. Connect the interior ports to your production line.");
        transfer(scene, center(DEPOT).add(0, .6, 0), center(OUTPUT), AllItems.IRON_SHEET.asStack());
        transfer(scene, center(FACTORY), center(new BlockPos(4, 1, 5)), AllItems.IRON_SHEET.asStack());
        say(scene, OUTPUT, "OUTPUT means out of the factory. The colored paths illustrate item transfer across the boundary.");
        scene.markAsFinished();
    }

    public static void power(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = begin(builder, util, "factory_power", "Powering the interior production line");
        room(scene, util);
        machines(scene, util);
        externalLogistics(scene, util);
        scene.world().showSection(util.select().fromTo(2, 1, 2, 2, 1, 4), Direction.DOWN);
        scene.world().setKineticSpeed(util.select().fromTo(2, 1, 2, 2, 1, 4), 32);
        say(scene, new BlockPos(2, 1, 4), "An outside shaft facing the factory supplies rotational power from its network.");
        scene.world().showSection(util.select().fromTo(9, 3, 3, 9, 3, 4), Direction.DOWN);
        highlight(scene, STRESS, PonderPalette.GREEN, 120);
        arrow(scene, center(new BlockPos(2, 1, 4)), center(STRESS), PonderPalette.GREEN, 120);
        say(scene, STRESS, "Inside, a Nested Stress Port relays power to your machinery. Connect its shaft to the press.");
        scene.world().setKineticSpeed(util.select().fromTo(9, 3, 3, 9, 3, 5), 32);
        scene.effects().rotationSpeedIndicator(STRESS);
        say(scene, PRESS, "The interior network needs enough stress capacity to run its machines.");
        production(scene);
        say(scene, DEPOT, "In Chunk-loaded mode, check the complete route: iron ingot in, pressing inside, iron sheet out.");
        say(scene, FACTORY, "Keep supplying materials and power, and leave room for the products.");
        scene.markAsFinished();
    }

    public static void blackbox(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = begin(builder, util, "factory_blackbox", "Learning a black-box production plan");
        workshop(scene, util);
        production(scene);
        say(scene, PRESS, "First, make sure the real production line runs correctly in Chunk-loaded mode.");
        click(scene, FACTORY, false, ItemStack.EMPTY);
        say(scene, FACTORY, "Everyone must leave the interior before you request Black-box mode in the control panel.");
        say(scene, FACTORY, "Preparation starts a new learning session. Continue supplying materials and power, and extracting products.");
        for (int i = 0; i < 3; i++) {
            BlockPos marker = new BlockPos(7 + i * 2, 1, 9);
            scene.world().setBlock(marker, Blocks.LIME_CONCRETE.defaultBlockState(), false);
            scene.world().showSection(util.select().position(marker), Direction.DOWN);
            production(scene);
            // One explicit caption per window; the animation compresses real learning time.
            if (i == 0) say(scene, marker, "Observation 1: record real inputs, outputs and power demand. Time is compressed in this demonstration.");
            if (i == 1) say(scene, marker, "Observation 2: continue collecting evidence for the candidate production plan.");
            if (i == 2) say(scene, marker, "Observation 3: confirm continued operation before activating the plan.");
        }
        scene.world().setKineticSpeed(util.select().fromTo(9, 3, 3, 9, 3, 5), 0);
        scene.overlay().showOutline(PonderPalette.BLUE, "frozen-room", util.select().fromTo(6, 0, 2, 12, 4, 8), 130);
        say(scene, PRESS, "Once learned, the real interior freezes. The verified plan takes over production.");
        transfer(scene, center(new BlockPos(0, 1, 5)), center(FACTORY), new ItemStack(Items.IRON_INGOT));
        transfer(scene, center(FACTORY), center(new BlockPos(4, 1, 5)), AllItems.IRON_SHEET.asStack());
        say(scene, FACTORY, "Black-box production still needs its materials and required power, plus space to deliver products.");
        say(scene, FACTORY, "If learning fails, the factory returns to Chunk-loaded mode. Check the message and production line before retrying.");
        scene.markAsFinished();
    }

    public static void blueprint(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = begin(builder, util, "factory_blueprint", "Reusing production with blueprints");
        room(scene, util);
        machines(scene, util);
        click(scene, FACTORY, false, AllItems.EMPTY_SCHEMATIC.asStack());
        say(scene, FACTORY, "Hold an Empty Schematic and right-click an active black-box factory with no players inside.");
        scene.overlay().showControls(center(FACTORY).add(0, 1, 0), Pointing.DOWN, 100).withItem(AllItems.SCHEMATIC.asStack());
        say(scene, FACTORY, "The resulting Nested Factory Blueprint records the verified production rules.");
        scene.world().showSection(util.select().position(TARGET), Direction.DOWN);
        click(scene, TARGET, true, AllItems.SCHEMATIC.asStack());
        arrow(scene, center(FACTORY), center(TARGET), PonderPalette.GREEN, 120);
        say(scene, TARGET, "Sneak and right-click another compatible factory with this blueprint to apply it.");
        transfer(scene, center(TARGET).add(-2, 0, 0), center(TARGET), new ItemStack(Items.IRON_INGOT));
        transfer(scene, center(TARGET), center(TARGET).add(2, 0, 0), AllItems.IRON_SHEET.asStack());
        say(scene, TARGET, "Blueprint mode runs the plan when its material and power requirements are met.");
        highlight(scene, FACTORY, PonderPalette.OUTPUT, 130);
        say(scene, FACTORY, "Blueprints do not duplicate stored materials, tools, work in progress or finished products.");
        scene.markAsFinished();
    }

    public static void nesting(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = begin(builder, util, "factory_nesting", "A factory inside a factory");
        room(scene, util);
        machines(scene, util);
        scene.world().showSection(util.select().position(CHILD), Direction.DOWN);
        highlight(scene, CHILD, PonderPalette.GREEN, 120);
        say(scene, CHILD, "Place a Nested Factory inside the parent room to create a child factory.");
        click(scene, CHILD, true, ItemStack.EMPTY);
        // A separate elevated miniature is a diagram of the child's room, not extra parent space.
        scene.world().showSection(util.select().fromTo(7, 5, 3, 11, 7, 7), Direction.DOWN);
        arrow(scene, center(CHILD), new Vec3(9, 5.5, 5), PonderPalette.GREEN, 120);
        say(scene, new BlockPos(9, 5, 5), "Sneak and right-click to enter the next level. The upper miniature represents its separate room.");
        arrow(scene, center(OUTPUT), center(CHILD), PonderPalette.OUTPUT, 120);
        say(scene, CHILD, "Connect the child through logistics to use it as a production step in the parent factory.");
        say(scene, CHILD, "Each room allows at most one enterable direct child factory.");
        say(scene, new BlockPos(9, 5, 5), "Nested rooms stay 16 x 16 x 16. The maximum enterable depth depends on the configuration.");
        scene.overlay().showOutline(PonderPalette.BLUE, "frozen-tree", util.select().fromTo(6, 0, 2, 12, 7, 8), 130);
        say(scene, FACTORY, "When the parent becomes a black box, the real rooms of its entire descendant tree freeze as well.");
        scene.markAsFinished();
    }

    public static void moving(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = begin(builder, util, "factory_moving", "Moving and preserving a factory");
        room(scene, util);
        machines(scene, util);
        say(scene, FACTORY, "The placed block and its persistent interior share the same factory identity.");
        scene.world().hideSection(util.select().position(FACTORY), Direction.UP);
        var item = scene.world().createItemEntity(center(FACTORY), Vec3.ZERO, new ItemStack(ModBlocks.NESTED_FACTORY.get()));
        scene.world().modifyEntity(item, entity -> entity.setNoGravity(true));
        highlight(scene, PRESS, PonderPalette.GREEN, 130);
        say(scene, FACTORY, "Ordinary breaking drops a bound factory item. The original room and its saved contents remain.");
        scene.world().modifyEntity(item, Entity::discard);
        scene.world().showSection(util.select().position(TARGET), Direction.DOWN);
        arrow(scene, center(TARGET), center(INPUT), PonderPalette.GREEN, 130);
        say(scene, TARGET, "Place that bound item at a valid location to reconnect to the same interior.");
        scene.world().showSection(util.select().position(CHILD), Direction.DOWN);
        say(scene, CHILD, "A child factory can only be restored in its original parent room. Moving it cannot change its parent or depth.");
        say(scene, TARGET, "Root factories must be restored outside the Pocket dimension.");
        scene.overlay().showOutline(PonderPalette.RED, "delete-room", util.select().fromTo(6, 0, 2, 12, 4, 8), 130);
        say(scene, TARGET, "Permanent clearing in the control panel deletes the interior. It is different from ordinary breaking.");
        scene.markAsFinished();
    }

    private static CreateSceneBuilder begin(SceneBuilder builder, SceneBuildingUtil util, String id, String title) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(id, title);
        scene.configureBasePlate(0, 0, 13);
        scene.scaleSceneView(.75f);
        scene.setSceneOffsetY(-1);
        scene.showBasePlate();
        scene.world().showSection(util.select().position(FACTORY), Direction.DOWN);
        scene.idle(20);
        return scene;
    }

    private static void room(CreateSceneBuilder scene, SceneBuildingUtil util) {
        scene.world().showSection(util.select().fromTo(6, 1, 2, 12, 2, 2), Direction.DOWN);
        scene.idle(15);
    }

    private static void machines(CreateSceneBuilder scene, SceneBuildingUtil util) {
        scene.world().showSection(util.select().fromTo(7, 1, 5, 11, 1, 5), Direction.DOWN);
        scene.world().showSection(util.select().position(PRESS), Direction.DOWN);
        scene.idle(15);
    }

    private static void externalLogistics(CreateSceneBuilder scene, SceneBuildingUtil util) {
        scene.world().showSection(util.select().fromTo(0, 1, 5, 1, 1, 5), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(3, 1, 5, 4, 1, 5), Direction.DOWN);
    }

    private static void workshop(CreateSceneBuilder scene, SceneBuildingUtil util) {
        room(scene, util);
        machines(scene, util);
        externalLogistics(scene, util);
        scene.world().showSection(util.select().fromTo(2, 1, 2, 2, 1, 4), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(9, 3, 3, 9, 3, 4), Direction.DOWN);
        scene.world().setKineticSpeed(util.select().fromTo(9, 3, 3, 9, 3, 5), 32);
        scene.world().setKineticSpeed(util.select().fromTo(2, 1, 2, 2, 1, 4), 32);
    }

    private static void production(CreateSceneBuilder scene) {
        transfer(scene, center(INPUT), center(DEPOT).add(0, .6, 0), new ItemStack(Items.IRON_INGOT));
        scene.world().createItemOnBeltLike(DEPOT, Direction.UP, new ItemStack(Items.IRON_INGOT));
        scene.world().modifyBlockEntity(PRESS, MechanicalPressBlockEntity.class,
                press -> press.getPressingBehaviour().start(Mode.BELT));
        scene.idle(35);
        scene.world().removeItemsFromBelt(DEPOT);
        scene.world().createItemOnBeltLike(DEPOT, Direction.UP, AllItems.IRON_SHEET.asStack());
        scene.idle(15);
        scene.world().removeItemsFromBelt(DEPOT);
        transfer(scene, center(DEPOT).add(0, .6, 0), center(OUTPUT), AllItems.IRON_SHEET.asStack());
    }

    private static void say(CreateSceneBuilder scene, BlockPos target, String text) {
        scene.overlay().showText(115).text(text).pointAt(center(target)).placeNearTarget().attachKeyFrame();
        scene.idle(125);
    }

    private static void click(CreateSceneBuilder scene, BlockPos pos, boolean sneak, ItemStack held) {
        var input = scene.overlay().showControls(center(pos).add(0, .7, 0), Pointing.DOWN, 70).rightClick();
        if (sneak) input.whileSneaking();
        if (!held.isEmpty()) input.withItem(held);
    }

    private static void highlight(CreateSceneBuilder scene, BlockPos pos, PonderPalette color, int ticks) {
        scene.overlay().chaseBoundingBoxOutline(color, pos, new AABB(pos), ticks);
    }

    private static void face(CreateSceneBuilder scene, Direction face, PonderPalette color) {
        Vec3 at = center(FACTORY).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.51));
        scene.overlay().chaseBoundingBoxOutline(color, face,
                new AABB(at.add(-.015, -.48, -.48), at.add(.015, .48, .48)), 120);
    }

    private static Vec3 center(BlockPos pos) {
        return Vec3.atCenterOf(pos);
    }

    private static void arrow(CreateSceneBuilder scene, Vec3 from, Vec3 to, PonderPalette color, int ticks) {
        scene.overlay().showLine(color, from, to, ticks);
        Vec3 back = from.subtract(to).normalize().scale(.35);
        Vec3 wing = back.cross(new Vec3(0, 1, 0)).normalize().scale(.18);
        scene.overlay().showLine(color, to, to.add(back).add(wing), ticks);
        scene.overlay().showLine(color, to, to.add(back).subtract(wing), ticks);
    }

    /** Fixed positions avoid gravity/drift and make timeline replay deterministic. */
    private static void transfer(CreateSceneBuilder scene, Vec3 from, Vec3 to, ItemStack stack) {
        arrow(scene, from, to, stack.is(Items.IRON_INGOT) ? PonderPalette.INPUT : PonderPalette.OUTPUT, 25);
        var item = scene.world().createItemEntity(from, Vec3.ZERO, stack);
        scene.world().modifyEntity(item, entity -> entity.setNoGravity(true));
        for (int i = 1; i <= 20; i++) {
            Vec3 at = from.lerp(to, i / 20.0);
            scene.world().modifyEntity(item, entity -> entity.setPos(at.x, at.y, at.z));
            scene.idle(1);
        }
        scene.world().modifyEntity(item, Entity::discard);
    }
}

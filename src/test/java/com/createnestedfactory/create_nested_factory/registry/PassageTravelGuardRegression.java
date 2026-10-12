package com.createnestedfactory.create_nested_factory.registry;

import com.createnestedfactory.create_nested_factory.block.FactoryPassageMarkerGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PassageTravelGuardRegression {
    private PassageTravelGuardRegression() {
    }

    public static void main(String[] args) {
        ResourceKey<Level> pocket = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("create_nested_factory", "nested_factory"));
        String passageId = "moving-passage";
        BlockPos originalPlotPos = BlockPos.ZERO;
        BlockPos reindexedPlotPos = new BlockPos(20_481_030, 129, 20_481_032);
        ModAttachments.PassageTravelGuard guard = ModAttachments.PassageTravelGuard.arrival(
                passageId, Level.OVERWORLD, originalPlotPos);

        require(guard.blocks(passageId), "arrival guard lost its passage identity");
        require(!guard.blocks("different-passage"), "arrival guard blocked another passage");
        for (int i = 0; i < ModAttachments.PassageTravelGuard.ARRIVAL_SETTLING_TICKS + 25; i++) {
            guard = guard.afterTick(Level.OVERWORLD, true, true, reindexedPlotPos);
            require(guard.isActive(), "guard expired while the player remained inside the passage");
        }
        require(guard.pos().equals(reindexedPlotPos), "guard did not follow the passage's new Plot position");
        guard = guard.afterTick(Level.OVERWORLD, false, true, reindexedPlotPos);
        guard = guard.afterTick(Level.OVERWORLD, null, true, reindexedPlotPos);
        guard = guard.afterTick(Level.OVERWORLD, false, true, reindexedPlotPos);
        require(guard.isActive(), "loading interruption was counted as confirmed departure");
        guard = guard.afterTick(Level.OVERWORLD, false, true, reindexedPlotPos);
        require(!guard.isActive(), "guard did not release after two confirmed outside ticks");

        guard = ModAttachments.PassageTravelGuard.arrival(passageId, Level.OVERWORLD, originalPlotPos);
        for (int i = 0; i < 80; i++) guard = guard.afterTick(pocket, false, true, originalPlotPos);
        require(guard.isActive(), "guard expired before arriving in its destination dimension");
        guard = guard.afterTick(Level.OVERWORLD, true, true, originalPlotPos);
        guard = guard.afterTick(Level.OVERWORLD, false, true, originalPlotPos);
        guard = guard.afterTick(Level.OVERWORLD, false, true, originalPlotPos);
        require(guard.isActive(), "first contact bypassed the moving arrival grace period");
        guard = guard.afterTick(pocket, null, true, originalPlotPos);
        require(!guard.isActive(), "guard survived a later departure to another dimension");

        guard = ModAttachments.PassageTravelGuard.arrival(passageId, Level.OVERWORLD, originalPlotPos);
        guard = guard.afterTick(Level.OVERWORLD, true, false, originalPlotPos);
        guard = guard.afterTick(Level.OVERWORLD, false, false, originalPlotPos);
        guard = guard.afterTick(Level.OVERWORLD, false, false, originalPlotPos);
        require(!guard.isActive(), "static passage lost its quick release after leaving");

        guard = ModAttachments.PassageTravelGuard.arrival(passageId, Level.OVERWORLD, originalPlotPos);
        for (int i = 0; i <= ModAttachments.PassageTravelGuard.ARRIVAL_SETTLING_TICKS; i++) {
            guard = guard.afterTick(Level.OVERWORLD, false, true, originalPlotPos);
        }
        require(!guard.isActive(), "guard never released for a safe arrival outside the door");

        ModAttachments.ReturnFrame passageFrame = ModAttachments.ReturnFrame.externalPassage(
                Level.OVERWORLD, Vec3.ZERO, 0, 0, "target-factory", passageId);
        require(passageId.equals(passageFrame.returnPassageId()),
                "passage return identity was lost from a nested return frame");

        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape slot = FactoryPassageMarkerGeometry.selectionShape(facing);
            for (Direction.Axis axis : Direction.Axis.values()) {
                require(slot.min(axis) >= 0 && slot.max(axis) <= 1,
                        "marker slot leaves its owning block for " + facing + " on " + axis);
            }
            Vec3 frontCenter = switch (facing) {
                case NORTH -> new Vec3(3.0 / 16.0, 13.0 / 16.0, 0);
                case EAST -> new Vec3(1, 13.0 / 16.0, 3.0 / 16.0);
                case SOUTH -> new Vec3(13.0 / 16.0, 13.0 / 16.0, 1);
                case WEST -> new Vec3(0, 13.0 / 16.0, 13.0 / 16.0);
                default -> throw new AssertionError("unexpected vertical marker facing");
            };
            for (Direction reportedFace : Direction.Plane.HORIZONTAL) {
                BlockHitResult hit = new BlockHitResult(frontCenter, reportedFace, BlockPos.ZERO, false);
                require(FactoryPassageMarkerGeometry.isHit(facing, BlockPos.ZERO, hit),
                        "marker rejected a valid " + facing + " hit reported as " + reportedFace);
            }
            Vec3 backCenter = switch (facing) {
                case NORTH -> new Vec3(3.0 / 16.0, 13.0 / 16.0, 1);
                case EAST -> new Vec3(0, 13.0 / 16.0, 3.0 / 16.0);
                case SOUTH -> new Vec3(13.0 / 16.0, 13.0 / 16.0, 0);
                case WEST -> new Vec3(1, 13.0 / 16.0, 13.0 / 16.0);
                default -> throw new AssertionError("unexpected vertical marker facing");
            };
            require(!FactoryPassageMarkerGeometry.isHit(facing, BlockPos.ZERO,
                            new BlockHitResult(backCenter, facing, BlockPos.ZERO, false)),
                    "marker accepted a hit on the back of a " + facing + " passage");
        }
        require(FactoryPassageMarkerGeometry.renderRotationDegrees(Direction.EAST) == -90,
                "east marker render rotation does not match Minecraft model rotation");
        require(FactoryPassageMarkerGeometry.renderRotationDegrees(Direction.WEST) == 90,
                "west marker render rotation does not match Minecraft model rotation");

        System.out.println("Factory passage travel-guard regression passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

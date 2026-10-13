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
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        ResourceKey<Level> pocket = ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation("create_nested_factory", "nested_factory"));
        String passageId = "moving-passage";
        BlockPos originalPlotPos = BlockPos.ZERO;
        BlockPos reindexedPlotPos = new BlockPos(20_481_030, 129, 20_481_032);
        ModAttachments.PassageTravelGuard guard = ModAttachments.PassageTravelGuard.arrival(
                passageId, Level.OVERWORLD, originalPlotPos);
        for (int i = 0; i < 80; i++) guard = guard.afterTick(pocket, false, originalPlotPos);
        require(guard.isActive(), "guard expired before arriving in the destination dimension");
        for (int i = 0; i < 80; i++) guard = guard.afterTick(Level.OVERWORLD, true, originalPlotPos);
        require(guard.isActive(), "guard expired while still inside the passage");
        guard = guard.afterTick(Level.OVERWORLD, false, originalPlotPos);
        require(guard.isActive(), "one outside tick released the guard");
        guard = guard.afterTick(Level.OVERWORLD, null, originalPlotPos);
        guard = guard.afterTick(Level.OVERWORLD, false, originalPlotPos);
        require(guard.isActive(), "unavailable endpoint counted as confirmed departure");
        guard = guard.afterTick(Level.OVERWORLD, false, originalPlotPos);
        require(!guard.isActive(), "two outside ticks did not release the guard");
        guard = ModAttachments.PassageTravelGuard.arrival(passageId, Level.OVERWORLD, originalPlotPos);
        guard = guard.afterTick(Level.OVERWORLD, true, originalPlotPos);
        require(!guard.afterTick(pocket, false, originalPlotPos).isActive(), "guard survived leaving its destination dimension");
        guard = ModAttachments.PassageTravelGuard.arrival(passageId, Level.OVERWORLD, originalPlotPos);
        for (int i = 0; i <= ModAttachments.PassageTravelGuard.ARRIVAL_SETTLING_TICKS; i++)
            guard = guard.afterTick(Level.OVERWORLD, false, originalPlotPos);
        require(!guard.isActive(), "guard never released for a safe arrival outside the passage");

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

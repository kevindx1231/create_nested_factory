package com.createnestedfactory.create_nested_factory.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/** Shared marker-slot geometry used by selection, hover and interaction checks. */
public final class FactoryPassageMarkerGeometry {
    private static final double HIT_EPSILON = 1.0E-5;
    private static final double SLOT_DEPTH = 1.0 / 16.0;
    // Keep selection geometry inside the owning block. Shapes protruding into the
    // neighbouring air block are skipped inconsistently by the block ray tracer.
    private static final VoxelShape NORTH = Shapes.box(0, 10.0 / 16.0, 0,
            6.0 / 16.0, 1, 1.0 / 16.0);
    private static final Map<Direction, VoxelShape> SHAPES = rotatedShapes(NORTH);

    private FactoryPassageMarkerGeometry() {
    }

    public static VoxelShape selectionShape(Direction facing) {
        return SHAPES.get(facing);
    }

    public static float renderRotationDegrees(Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> -90;
            case SOUTH -> 180;
            case WEST -> 90;
            default -> 0;
        };
    }

    public static boolean isHit(Direction facing, BlockPos pos, BlockHitResult hitResult) {
        double localX = hitResult.getLocation().x - pos.getX();
        double localY = hitResult.getLocation().y - pos.getY();
        double localZ = hitResult.getLocation().z - pos.getZ();
        double horizontal = switch (facing) {
            case NORTH -> localX;
            case EAST -> localZ;
            case SOUTH -> 1.0 - localX;
            case WEST -> 1.0 - localZ;
            default -> -1.0;
        };
        double depth = switch (facing) {
            case NORTH -> localZ;
            case EAST -> 1.0 - localX;
            case SOUTH -> 1.0 - localZ;
            case WEST -> localX;
            default -> Double.POSITIVE_INFINITY;
        };
        return horizontal >= 1.0 / 16.0 && horizontal <= 5.0 / 16.0
                && localY >= 11.0 / 16.0 && localY <= 15.0 / 16.0
                && depth >= -HIT_EPSILON && depth <= SLOT_DEPTH + HIT_EPSILON;
    }

    private static Map<Direction, VoxelShape> rotatedShapes(VoxelShape north) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.NORTH, north);
        shapes.put(Direction.EAST, rotateY(north));
        shapes.put(Direction.SOUTH, rotateY(shapes.get(Direction.EAST)));
        shapes.put(Direction.WEST, rotateY(shapes.get(Direction.SOUTH)));
        return shapes;
    }

    private static VoxelShape rotateY(VoxelShape source) {
        final VoxelShape[] result = {Shapes.empty()};
        source.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> result[0] = Shapes.or(result[0],
                Shapes.box(1.0 - maxZ, minY, minX, 1.0 - minZ, maxY, maxX)));
        return result[0];
    }
}

package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Pure coordinate rules shared by room build, expansion, collapse and destruction tasks. */
final class FactoryRoomGeometry {
    record CollapseRegions(int[] removed, int[] validation, int[] playerValidation) {
    }

    private FactoryRoomGeometry() {
    }

    static int[] room(PocketBounds bounds, BlockPos origin) {
        return new int[]{bounds.minX(origin), bounds.minY(origin), bounds.minZ(origin),
                bounds.maxX(origin), bounds.maxY(origin), bounds.maxZ(origin)};
    }

    static int[] expandedInterior(PocketBounds after, PocketBounds before,
                                  BlockPos origin, Direction direction) {
        int minX = after.minX(origin), maxX = after.maxX(origin);
        int minY = after.minY(origin), maxY = after.maxY(origin);
        int minZ = after.minZ(origin), maxZ = after.maxZ(origin);
        return switch (direction) {
            case EAST -> new int[]{before.maxX(origin), minY + 1, minZ + 1,
                    maxX - 1, maxY - 1, maxZ - 1};
            case WEST -> new int[]{minX + 1, minY + 1, minZ + 1,
                    before.minX(origin), maxY - 1, maxZ - 1};
            case UP -> new int[]{minX + 1, before.maxY(origin), minZ + 1,
                    maxX - 1, maxY - 1, maxZ - 1};
            case DOWN -> new int[]{minX + 1, minY + 1, minZ + 1,
                    maxX - 1, before.minY(origin), maxZ - 1};
            case SOUTH -> new int[]{minX + 1, minY + 1, before.maxZ(origin),
                    maxX - 1, maxY - 1, maxZ - 1};
            case NORTH -> new int[]{minX + 1, minY + 1, minZ + 1,
                    maxX - 1, maxY - 1, before.minZ(origin)};
        };
    }

    static CollapseRegions collapse(PocketBounds before, PocketBounds after,
                                    BlockPos origin, Direction direction) {
        int[] slab = slab(before, origin, direction);
        return new CollapseRegions(
                removedSlab(before, after, origin, direction),
                extendTowardCenter(slab, direction, 1),
                extendTowardCenter(slab, direction, 2));
    }

    private static int[] removedSlab(PocketBounds before, PocketBounds after,
                                     BlockPos origin, Direction direction) {
        int minX = after.minX(origin), maxX = after.maxX(origin);
        int minY = after.minY(origin), maxY = after.maxY(origin);
        int minZ = after.minZ(origin), maxZ = after.maxZ(origin);
        return switch (direction) {
            case EAST -> new int[]{maxX + 1, minY, minZ, before.maxX(origin), maxY, maxZ};
            case WEST -> new int[]{before.minX(origin), minY, minZ, minX - 1, maxY, maxZ};
            case UP -> new int[]{minX, maxY + 1, minZ, maxX, before.maxY(origin), maxZ};
            case DOWN -> new int[]{minX, before.minY(origin), minZ, maxX, minY - 1, maxZ};
            case SOUTH -> new int[]{minX, minY, maxZ + 1, maxX, maxY, before.maxZ(origin)};
            case NORTH -> new int[]{minX, minY, before.minZ(origin), maxX, maxY, minZ - 1};
        };
    }

    private static int[] slab(PocketBounds bounds, BlockPos origin, Direction direction) {
        int minX = bounds.minX(origin), maxX = bounds.maxX(origin);
        int minY = bounds.minY(origin), maxY = bounds.maxY(origin);
        int minZ = bounds.minZ(origin), maxZ = bounds.maxZ(origin);
        return switch (direction) {
            case EAST -> new int[]{maxX - 15, minY, minZ, maxX, maxY, maxZ};
            case WEST -> new int[]{minX, minY, minZ, minX + 15, maxY, maxZ};
            case UP -> new int[]{minX, maxY - 15, minZ, maxX, maxY, maxZ};
            case DOWN -> new int[]{minX, minY, minZ, maxX, minY + 15, maxZ};
            case SOUTH -> new int[]{minX, minY, maxZ - 15, maxX, maxY, maxZ};
            case NORTH -> new int[]{minX, minY, minZ, maxX, maxY, minZ + 15};
        };
    }

    /** Extends a collapsing slab only inward, preserving the room shell on every other axis. */
    private static int[] extendTowardCenter(int[] bounds, Direction direction, int distance) {
        int[] extended = bounds.clone();
        switch (direction) {
            case EAST -> extended[0] -= distance;
            case WEST -> extended[3] += distance;
            case UP -> extended[1] -= distance;
            case DOWN -> extended[4] += distance;
            case SOUTH -> extended[2] -= distance;
            case NORTH -> extended[5] += distance;
        }
        return extended;
    }
}

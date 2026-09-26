package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** Exact six-direction regression vectors for room mutation geometry. */
public final class FactoryRoomGeometryRegression {
    private static final BlockPos ORIGIN = new BlockPos(32, 64, -16);

    private FactoryRoomGeometryRegression() {
    }

    public static void main(String[] args) {
        Map<Direction, int[]> expandedInterior = new EnumMap<>(Direction.class);
        expandedInterior.put(Direction.EAST, new int[]{47, 65, -15, 62, 78, -2});
        expandedInterior.put(Direction.WEST, new int[]{17, 65, -15, 32, 78, -2});
        expandedInterior.put(Direction.UP, new int[]{33, 79, -15, 46, 94, -2});
        expandedInterior.put(Direction.DOWN, new int[]{33, 49, -15, 46, 64, -2});
        expandedInterior.put(Direction.SOUTH, new int[]{33, 65, -1, 46, 78, 14});
        expandedInterior.put(Direction.NORTH, new int[]{33, 65, -31, 46, 78, -16});

        Map<Direction, int[]> removed = new EnumMap<>(Direction.class);
        removed.put(Direction.EAST, new int[]{48, 64, -16, 63, 79, -1});
        removed.put(Direction.WEST, new int[]{16, 64, -16, 31, 79, -1});
        removed.put(Direction.UP, new int[]{32, 80, -16, 47, 95, -1});
        removed.put(Direction.DOWN, new int[]{32, 48, -16, 47, 63, -1});
        removed.put(Direction.SOUTH, new int[]{32, 64, 0, 47, 79, 15});
        removed.put(Direction.NORTH, new int[]{32, 64, -32, 47, 79, -17});

        for (Direction direction : Direction.values()) {
            PocketBounds before = new PocketBounds();
            PocketBounds afterExpansion = before.copy();
            afterExpansion.expand(direction);
            requireArray(expandedInterior.get(direction),
                    FactoryRoomGeometry.expandedInterior(afterExpansion, before, ORIGIN, direction),
                    direction + " expanded interior");

            PocketBounds afterCollapse = afterExpansion.copy();
            afterCollapse.collapse(direction);
            requireArray(new int[]{32, 64, -16, 47, 79, -1},
                    FactoryRoomGeometry.room(afterCollapse, ORIGIN), direction + " collapsed room");
            FactoryRoomGeometry.CollapseRegions regions = FactoryRoomGeometry.collapse(
                    afterExpansion, afterCollapse, ORIGIN, direction);
            requireArray(removed.get(direction), regions.removed(), direction + " removed slab");

            int inwardIndex = switch (direction) {
                case EAST, UP, SOUTH -> direction == Direction.EAST ? 0
                        : direction == Direction.UP ? 1 : 2;
                case WEST, DOWN, NORTH -> direction == Direction.WEST ? 3
                        : direction == Direction.DOWN ? 4 : 5;
            };
            int inwardSign = switch (direction) {
                case EAST, UP, SOUTH -> -1;
                case WEST, DOWN, NORTH -> 1;
            };
            int[] validation = removed.get(direction).clone();
            validation[inwardIndex] += inwardSign;
            int[] playerValidation = removed.get(direction).clone();
            playerValidation[inwardIndex] += inwardSign * 2;
            requireArray(validation, regions.validation(), direction + " validation slab");
            requireArray(playerValidation, regions.playerValidation(), direction + " player validation slab");
        }

        System.out.println("Factory room-geometry regression passed.");
    }

    private static void requireArray(int[] expected, int[] actual, String description) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + Arrays.toString(expected)
                    + ", actual=" + Arrays.toString(actual));
        }
    }
}

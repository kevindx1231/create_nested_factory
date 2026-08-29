package com.createnestedfactory.create_nested_factory.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class PocketBounds {
    private int minXChunks, maxXChunks, minYChunks, maxYChunks, minZChunks, maxZChunks;

    public int minX(BlockPos origin) {
        return origin.getX() + minXChunks * 16;
    }

    public int minY(BlockPos origin) {
        return origin.getY() + minYChunks * 16;
    }

    public int minZ(BlockPos origin) {
        return origin.getZ() + minZChunks * 16;
    }

    public int maxX(BlockPos origin) {
        return origin.getX() + (maxXChunks + 1) * 16 - 1;
    }

    public int maxY(BlockPos origin) {
        return origin.getY() + (maxYChunks + 1) * 16 - 1;
    }

    public int maxZ(BlockPos origin) {
        return origin.getZ() + (maxZChunks + 1) * 16 - 1;
    }

    public boolean contains(BlockPos origin, BlockPos pos) {
        return pos.getX() >= minX(origin) && pos.getX() <= maxX(origin)
                && pos.getY() >= minY(origin) && pos.getY() <= maxY(origin)
                && pos.getZ() >= minZ(origin) && pos.getZ() <= maxZ(origin);
    }

    public boolean isBuildableAt(BlockPos origin, BlockPos pos) {
        return pos.getX() > minX(origin) && pos.getX() < maxX(origin)
                && pos.getY() > minY(origin) && pos.getY() < maxY(origin)
                && pos.getZ() > minZ(origin) && pos.getZ() < maxZ(origin);
    }

    public boolean canExpand(Direction direction) {
        return switch (direction) {
            case EAST -> maxXChunks < 1;
            case WEST -> minXChunks > -1;
            case UP -> maxYChunks < 1;
            case DOWN -> false;
            case SOUTH -> maxZChunks < 1;
            case NORTH -> minZChunks > -1;
        };
    }

    public boolean canCollapse(Direction direction) {
        return switch (direction) {
            case EAST -> maxXChunks > 0;
            case WEST -> minXChunks < 0;
            case UP -> maxYChunks > 0;
            case DOWN -> false;
            case SOUTH -> maxZChunks > 0;
            case NORTH -> minZChunks < 0;
        };
    }

    public void expand(Direction direction) {
        switch (direction) {
            case EAST -> maxXChunks++;
            case WEST -> minXChunks--;
            case UP -> maxYChunks++;
            case DOWN -> minYChunks--;
            case SOUTH -> maxZChunks++;
            case NORTH -> minZChunks--;
        }
    }

    public void collapse(Direction direction) {
        switch (direction) {
            case EAST -> maxXChunks--;
            case WEST -> minXChunks++;
            case UP -> maxYChunks--;
            case DOWN -> minYChunks++;
            case SOUTH -> maxZChunks--;
            case NORTH -> minZChunks++;
        }
    }

    public PocketBounds copy() {
        PocketBounds b = new PocketBounds();
        b.minXChunks = minXChunks;
        b.maxXChunks = maxXChunks;
        b.minYChunks = minYChunks;
        b.maxYChunks = maxYChunks;
        b.minZChunks = minZChunks;
        b.maxZChunks = maxZChunks;
        return b;
    }

    public int[] toArray() {
        return new int[]{minXChunks, maxXChunks, minYChunks, maxYChunks, minZChunks, maxZChunks};
    }

    public void fromArray(int[] a) {
        if (a.length == 6) {
            minXChunks = a[0];
            maxXChunks = a[1];
            minYChunks = a[2];
            maxYChunks = a[3];
            minZChunks = a[4];
            maxZChunks = a[5];
        }
    }
}

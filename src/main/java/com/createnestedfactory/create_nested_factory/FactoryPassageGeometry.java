package com.createnestedfactory.create_nested_factory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Collision queries for world-aligned players against blocks in a moving local space. */
public final class FactoryPassageGeometry {
    private static final Vec3[] WORLD_AXES = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
    private static final double EPSILON = 1e-7;

    private FactoryPassageGeometry() {}

    public static boolean noCollision(Level level, ServerPlayer player, AABB world,
                                      FactoryPassageNavigation.Coordinates coordinates) {
        Vec3[] localCorners = corners(world);
        for (int i = 0; i < localCorners.length; i++) localCorners[i] = coordinates.toLocal(localCorners[i]);
        AABB localBounds = bounds(localCorners);
        // Invalid/degenerate transforms must not turn into an unbounded block scan.
        if (!Double.isFinite(localBounds.getSize()) || localBounds.getXsize() > 32
                || localBounds.getYsize() > 32 || localBounds.getZsize() > 32) return false;
        for (var shape : level.getBlockCollisions(player, localBounds)) {
            for (AABB block : shape.toAabbs()) {
                if (intersects(world, block, coordinates)) return false;
            }
        }
        return true;
    }

    /** Separating-axis test: using only an inverse AABB would falsely block rotated doorways. */
    public static boolean intersects(AABB world, AABB local, FactoryPassageNavigation.Coordinates coordinates) {
        Vec3[] points = corners(local);
        for (int i = 0; i < points.length; i++) points[i] = coordinates.toWorld(points[i]);
        Vec3[] edges = {points[1].subtract(points[0]), points[2].subtract(points[0]), points[4].subtract(points[0])};
        for (Vec3 axis : WORLD_AXES) if (separated(world, points, axis)) return false;
        for (int i = 0; i < 3; i++) {
            if (separated(world, points, edges[i].cross(edges[(i + 1) % 3]))) return false;
            for (Vec3 axis : WORLD_AXES) if (separated(world, points, edges[i].cross(axis))) return false;
        }
        return true;
    }

    private static boolean separated(AABB world, Vec3[] points, Vec3 axis) {
        double lengthSquared = axis.lengthSqr();
        if (lengthSquared < 1e-20) return false;
        // Vec3.normalize() returns ZERO below length 1e-4. Small but valid cross-product
        // axes occur on slightly tilted carriers; ZERO would falsely separate every box.
        axis = axis.scale(1.0 / Math.sqrt(lengthSquared));
        Vec3 center = world.getCenter();
        double radius = (Math.abs(axis.x) * world.getXsize() + Math.abs(axis.y) * world.getYsize()
                + Math.abs(axis.z) * world.getZsize()) * .5;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Vec3 point : points) {
            double projection = point.subtract(center).dot(axis);
            min = Math.min(min, projection);
            max = Math.max(max, projection);
        }
        return min >= radius - EPSILON || max <= -radius + EPSILON;
    }

    private static Vec3[] corners(AABB box) {
        Vec3[] corners = new Vec3[8];
        for (int i = 0; i < 8; i++) corners[i] = new Vec3((i & 1) == 0 ? box.minX : box.maxX,
                (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ);
        return corners;
    }

    private static AABB bounds(Vec3[] corners) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (Vec3 p : corners) {
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}

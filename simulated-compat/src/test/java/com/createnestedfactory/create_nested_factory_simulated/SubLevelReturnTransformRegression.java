package com.createnestedfactory.create_nested_factory_simulated;

/** Regression coverage for returning to a point fixed in a moving SubLevel frame. */
public final class SubLevelReturnTransformRegression {
    private static final double EPSILON = 1.0e-9;

    private SubLevelReturnTransformRegression() {
    }

    public static void main(String[] args) {
        SubLevelReturnTransform.Pose entryPose = pose(10, 4, -2, 0);
        SubLevelReturnTransform.Vector factoryAtEntry = vector(3, 2, 5);
        SubLevelReturnTransform.Vector playerAtEntry = entryPose.toWorld(vector(3.25, 3, 4.5));
        SubLevelReturnTransform.Vector lookAtEntry = entryPose.normalToWorld(vector(0, 0, 1));

        SubLevelReturnTransform.Anchor anchor = SubLevelReturnTransform.capture(
                entryPose, playerAtEntry, lookAtEntry, factoryAtEntry);

        SubLevelReturnTransform.Pose movedPose = pose(42, 8, 19, 90);
        SubLevelReturnTransform.Vector factoryAfterSplit = vector(-8, 2, 11);
        SubLevelReturnTransform.Resolved resolved = SubLevelReturnTransform.resolve(
                movedPose, factoryAfterSplit, anchor);

        assertVector(resolved.position(), movedPose.toWorld(vector(-7.75, 3, 10.5)),
                "return position did not follow the carrier's current translation and rotation");
        assertVector(resolved.lookDirection(), movedPose.normalToWorld(vector(0, 0, 1)),
                "return view direction did not follow the carrier's current rotation");

        System.out.println("SubLevel return-transform regression passed.");
    }

    private static SubLevelReturnTransform.Pose pose(double x, double y, double z, double yawDegrees) {
        double radians = Math.toRadians(yawDegrees);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return new SubLevelReturnTransform.Pose() {
            @Override
            public SubLevelReturnTransform.Vector toLocal(SubLevelReturnTransform.Vector global) {
                double dx = global.x() - x;
                double dz = global.z() - z;
                return vector(cos * dx + sin * dz, global.y() - y, -sin * dx + cos * dz);
            }

            @Override
            public SubLevelReturnTransform.Vector toWorld(SubLevelReturnTransform.Vector local) {
                return vector(x + cos * local.x() - sin * local.z(), y + local.y(),
                        z + sin * local.x() + cos * local.z());
            }

            @Override
            public SubLevelReturnTransform.Vector normalToLocal(SubLevelReturnTransform.Vector global) {
                return vector(cos * global.x() + sin * global.z(), global.y(),
                        -sin * global.x() + cos * global.z());
            }

            @Override
            public SubLevelReturnTransform.Vector normalToWorld(SubLevelReturnTransform.Vector local) {
                return vector(cos * local.x() - sin * local.z(), local.y(),
                        sin * local.x() + cos * local.z());
            }
        };
    }

    private static SubLevelReturnTransform.Vector vector(double x, double y, double z) {
        return new SubLevelReturnTransform.Vector(x, y, z);
    }

    private static void assertVector(SubLevelReturnTransform.Vector actual,
                                     SubLevelReturnTransform.Vector expected,
                                     String message) {
        if (Math.abs(actual.x() - expected.x()) > EPSILON
                || Math.abs(actual.y() - expected.y()) > EPSILON
                || Math.abs(actual.z() - expected.z()) > EPSILON) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

}

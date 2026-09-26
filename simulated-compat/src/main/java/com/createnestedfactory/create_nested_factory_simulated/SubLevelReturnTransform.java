package com.createnestedfactory.create_nested_factory_simulated;

/** Pure local/world transform used by the Sable return-anchor adapter and its regression test. */
final class SubLevelReturnTransform {
    private SubLevelReturnTransform() {
    }

    interface Pose {
        Vector toLocal(Vector global);

        Vector toWorld(Vector local);

        Vector normalToLocal(Vector global);

        Vector normalToWorld(Vector local);
    }

    record Vector(double x, double y, double z) {
        Vector add(Vector other) {
            return new Vector(x + other.x, y + other.y, z + other.z);
        }

        Vector subtract(Vector other) {
            return new Vector(x - other.x, y - other.y, z - other.z);
        }

        Vector normalize() {
            double length = Math.sqrt(x * x + y * y + z * z);
            return length < 1.0e-12 ? new Vector(0, 0, 1) : new Vector(x / length, y / length, z / length);
        }
    }

    record Anchor(Vector offsetFromFactory, Vector localLookDirection) {
    }

    record Resolved(Vector position, Vector lookDirection, Vector localPosition) {
    }

    static Anchor capture(Pose pose, Vector playerWorldPosition, Vector playerWorldLook,
                          Vector factoryLocalPosition) {
        return new Anchor(pose.toLocal(playerWorldPosition).subtract(factoryLocalPosition),
                pose.normalToLocal(playerWorldLook).normalize());
    }

    static Resolved resolve(Pose pose, Vector factoryLocalPosition, Anchor anchor) {
        Vector localPosition = factoryLocalPosition.add(anchor.offsetFromFactory());
        return new Resolved(pose.toWorld(localPosition),
                pose.normalToWorld(anchor.localLookDirection()).normalize(), localPosition);
    }

}

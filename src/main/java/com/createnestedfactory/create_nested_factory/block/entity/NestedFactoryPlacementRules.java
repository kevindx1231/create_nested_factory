package com.createnestedfactory.create_nested_factory.block.entity;

/** Pure placement classification shared by nested initialization and regression coverage. */
public final class NestedFactoryPlacementRules {
    public enum Kind {
        ROOM,
        SIBLING_BLUEPRINT_ONLY,
        TERMINAL_BLUEPRINT_ONLY,
        INVALID
    }

    private NestedFactoryPlacementRules() {
    }

    public static Kind classify(int nestingDepth, int maxRoomDepth, boolean buildable, boolean noSibling) {
        if (!buildable || nestingDepth < 1 || maxRoomDepth < 1) return Kind.INVALID;
        if (nestingDepth <= maxRoomDepth) return noSibling ? Kind.ROOM : Kind.SIBLING_BLUEPRINT_ONLY;
        return nestingDepth == maxRoomDepth + 1
                ? Kind.TERMINAL_BLUEPRINT_ONLY
                : Kind.INVALID;
    }
}

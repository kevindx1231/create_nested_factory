package com.createnestedfactory.create_nested_factory.block;

/**
 * Validates the six external factory-face bindings. Every enabled face may join the same
 * logical port group, while INPUT and OUTPUT faces remain separate directions.
 */
public final class FactoryFacePortBindings {
    public static final int FACE_COUNT = 6;
    public static final int MIN_PORT_ID = 1;
    public static final int MAX_PORT_ID = 6;

    private FactoryFacePortBindings() {
    }

    public static boolean isValid(PortMode[] modes, int[] ids) {
        if (modes == null || ids == null || modes.length != FACE_COUNT || ids.length != FACE_COUNT) {
            return false;
        }
        PortMode[] modesById = new PortMode[MAX_PORT_ID + 1];
        for (int index = 0; index < FACE_COUNT; index++) {
            PortMode mode = modes[index];
            int id = ids[index];
            if (mode == null || mode == PortMode.NONE) {
                if (id != 0) {
                    return false;
                }
                continue;
            }
            if (id < MIN_PORT_ID || id > MAX_PORT_ID) {
                return false;
            }
            if (modesById[id] != null && modesById[id] != mode) {
                return false;
            }
            modesById[id] = mode;
        }
        return true;
    }

    /**
     * Deterministically repairs legacy or malformed data in array order. Enabled faces keep a
     * valid unused id; all other enabled faces receive the lowest free id.
     */
    public static boolean normalize(PortMode[] modes, int[] ids) {
        if (modes == null || ids == null || modes.length != FACE_COUNT || ids.length != FACE_COUNT) {
            throw new IllegalArgumentException("Factory face bindings must contain exactly six entries");
        }
        boolean changed = false;
        PortMode[] modesById = new PortMode[MAX_PORT_ID + 1];
        for (int index = 0; index < FACE_COUNT; index++) {
            PortMode mode = modes[index];
            if (mode == null) {
                modes[index] = PortMode.NONE;
                mode = PortMode.NONE;
                changed = true;
            }
            if (mode == PortMode.NONE) {
                if (ids[index] != 0) {
                    ids[index] = 0;
                    changed = true;
                }
                continue;
            }

            int id = ids[index];
            if (id < MIN_PORT_ID || id > MAX_PORT_ID
                    || (modesById[id] != null && modesById[id] != mode)) {
                int replacement = lowestCompatibleUnused(modesById, mode);
                if (replacement == 0) {
                    modes[index] = PortMode.NONE;
                    ids[index] = 0;
                } else {
                    ids[index] = replacement;
                    modesById[replacement] = mode;
                }
                changed = true;
            } else {
                modesById[id] = mode;
            }
        }
        return changed;
    }

    public static int allocateLowestUnused(PortMode[] modes, int[] ids, int excludedFaceIndex) {
        if (modes == null || ids == null || modes.length != FACE_COUNT || ids.length != FACE_COUNT) {
            return 0;
        }
        boolean[] used = new boolean[MAX_PORT_ID + 1];
        for (int index = 0; index < FACE_COUNT; index++) {
            if (index == excludedFaceIndex || modes[index] == null || modes[index] == PortMode.NONE) {
                continue;
            }
            int id = ids[index];
            if (id >= MIN_PORT_ID && id <= MAX_PORT_ID) {
                used[id] = true;
            }
        }
        return lowestUnused(used);
    }

    private static int lowestCompatibleUnused(PortMode[] modesById, PortMode mode) {
        for (int id = MIN_PORT_ID; id <= MAX_PORT_ID; id++) {
            if (modesById[id] == null || modesById[id] == mode) {
                return id;
            }
        }
        return 0;
    }

    private static int lowestUnused(boolean[] used) {
        for (int id = MIN_PORT_ID; id <= MAX_PORT_ID; id++) {
            if (!used[id]) {
                return id;
            }
        }
        return 0;
    }
}

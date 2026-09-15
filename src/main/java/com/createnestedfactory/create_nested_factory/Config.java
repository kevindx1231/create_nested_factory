package com.createnestedfactory.create_nested_factory;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Runtime configuration for mechanics that are actually implemented. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.IntValue MAX_NESTING_DEPTH = BUILDER
            .comment("Maximum allowed nesting depth for nested factories")
            .defineInRange("maxNestingDepth", 8, 1, 16);

    private static final ModConfigSpec.IntValue ROOM_MUTATION_BLOCKS_PER_TICK = BUILDER
            .comment("Maximum block checks or writes processed by a Pocket room task each tick")
            .defineInRange("roomMutationBlocksPerTick", 16384, 64, 65536);

    private static final ModConfigSpec.IntValue BLACKBOX_MAX_LEARNING_TICKS = BUILDER
            .comment("Absolute maximum black-box learning time in ticks")
            .defineInRange("blackboxMaxLearningTicks", 6000, 2, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue COBBLESTONE_SOURCE_VERIFICATION_CYCLES = BUILDER
            .comment("Complete source events required for a cobblestone black-box source")
            .defineInRange("cobblestoneSourceVerificationCycles", 3, 3, 5);

    private static final ModConfigSpec.IntValue BASALT_SOURCE_VERIFICATION_CYCLES = BUILDER
            .comment("Complete source events required for a basalt black-box source")
            .defineInRange("basaltSourceVerificationCycles", 3, 3, 5);

    private static final ModConfigSpec.IntValue TREE_SOURCE_VERIFICATION_CYCLES = BUILDER
            .comment("Complete growth events required for a tree black-box source")
            .defineInRange("treeSourceVerificationCycles", 3, 3, 5);

    private static final ModConfigSpec.IntValue CROP_SOURCE_VERIFICATION_CYCLES = BUILDER
            .comment("Complete growth and harvest events required for each crop type in a black-box source")
            .defineInRange("cropSourceVerificationCycles", 3, 3, 5);

    private static final ModConfigSpec.BooleanValue BLACKBOX_DEBUG_LOGGING = BUILDER
            .comment("Write structured [CNF-BLACKBOX] diagnostics for learning, runtime, stress and ownership events")
            .define("blackboxDebugLogging", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static int maxNestingDepth;
    public static int roomMutationBlocksPerTick;
    public static int blackboxMaxLearningTicks;
    public static int cobblestoneSourceVerificationCycles;
    public static int basaltSourceVerificationCycles;
    public static int treeSourceVerificationCycles;
    public static int cropSourceVerificationCycles;
    public static boolean blackboxDebugLogging;

    private Config() {
    }

    static void onLoad(ModConfigEvent event) {
        maxNestingDepth = MAX_NESTING_DEPTH.get();
        roomMutationBlocksPerTick = ROOM_MUTATION_BLOCKS_PER_TICK.get();
        blackboxMaxLearningTicks = BLACKBOX_MAX_LEARNING_TICKS.get();
        cobblestoneSourceVerificationCycles = COBBLESTONE_SOURCE_VERIFICATION_CYCLES.get();
        basaltSourceVerificationCycles = BASALT_SOURCE_VERIFICATION_CYCLES.get();
        treeSourceVerificationCycles = TREE_SOURCE_VERIFICATION_CYCLES.get();
        cropSourceVerificationCycles = CROP_SOURCE_VERIFICATION_CYCLES.get();
        blackboxDebugLogging = BLACKBOX_DEBUG_LOGGING.get();
    }
}

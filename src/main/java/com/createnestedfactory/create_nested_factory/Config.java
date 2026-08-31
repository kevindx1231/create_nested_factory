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

    static final ModConfigSpec SPEC = BUILDER.build();

    public static int maxNestingDepth;
    public static int roomMutationBlocksPerTick;

    private Config() {
    }

    static void onLoad(ModConfigEvent event) {
        maxNestingDepth = MAX_NESTING_DEPTH.get();
        roomMutationBlocksPerTick = ROOM_MUTATION_BLOCKS_PER_TICK.get();
    }
}

package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One server-confirmed Create drill cycle. The event proves machine activity; only paths that
 * Create itself has verified as regenerative may directly grant regenerative production credit.
 */
public record DrillProductionEvent(BlockPos targetPos, BlockState brokenState, Path path) {
    public enum Path {
        STATIONARY,
        CREATE_OPTIMIZED_GENERATOR,
        CONTRAPTION
    }

    public DrillProductionEvent {
        targetPos = targetPos.immutable();
    }

    public boolean provesRegeneration() {
        return path == Path.CREATE_OPTIMIZED_GENERATOR;
    }
}

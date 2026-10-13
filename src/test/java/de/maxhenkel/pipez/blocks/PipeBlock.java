package de.maxhenkel.pipez.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;

/** Headless API fixture: signature verified against the installed Pipez 1.20.1-1.2.5 JAR. */
public class PipeBlock extends Block {
    public PipeBlock() {
        super(Properties.of());
    }

    public boolean isConnected(LevelAccessor level, BlockPos pos, Direction side) {
        return false;
    }
}

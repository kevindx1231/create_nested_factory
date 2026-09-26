package com.createnestedfactory.create_nested_factory_simulated;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.FactoryPassageAvailability;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Identifies the exact factory or passage block entity hosted by a Sable SubLevel. */
final class SableFactoryPassageAvailabilityAdapter implements FactoryPassageAvailability.Adapter {
    static final SableFactoryPassageAvailabilityAdapter INSTANCE =
            new SableFactoryPassageAvailabilityAdapter();
    private static final String ID = Create_nested_factory.MODID + ":sable_passage_availability";

    private SableFactoryPassageAvailabilityAdapter() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isPhysicalized(BlockEntity blockEntity) {
        if (Sable.HELPER.getContaining(blockEntity) != null) return true;
        if (!(blockEntity.getLevel() instanceof ServerLevel serverLevel)) return false;
        var pos = blockEntity.getBlockPos();
        SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        if (container == null || !container.inBounds(pos)) return false;
        var plot = container.getPlot(new ChunkPos(pos));
        return plot != null && plot.getSubLevel() != null;
    }
}

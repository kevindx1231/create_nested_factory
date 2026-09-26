package com.createnestedfactory.create_nested_factory;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/** Optional-compat seam that disables passages hosted by a physicalized structure. */
public final class FactoryPassageAvailability {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Adapter> ADAPTERS = new LinkedHashMap<>();

    private FactoryPassageAvailability() {
    }

    public interface Adapter {
        String id();

        boolean isPhysicalized(BlockEntity blockEntity);
    }

    public static synchronized void register(Adapter adapter) {
        Adapter previous = ADAPTERS.put(adapter.id(), adapter);
        if (previous != null && previous != adapter) {
            LOGGER.warn("Replaced factory passage-availability adapter {}", adapter.id());
        }
    }

    public static boolean isPhysicalized(BlockEntity blockEntity) {
        if (blockEntity == null || blockEntity.getLevel() == null) return false;
        for (Adapter adapter : snapshotAdapters()) {
            try {
                if (adapter.isPhysicalized(blockEntity)) return true;
            } catch (LinkageError | RuntimeException exception) {
                // Fail closed: a broken optional adapter must not re-enable unsafe passage travel.
                LOGGER.warn("Factory passage-availability adapter {} failed", adapter.id(), exception);
                return true;
            }
        }
        return false;
    }

    private static synchronized Adapter[] snapshotAdapters() {
        return ADAPTERS.values().toArray(Adapter[]::new);
    }
}

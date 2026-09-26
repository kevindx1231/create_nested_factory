package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.mixin.accessor.LevelTicksAccessor;
import com.createnestedfactory.create_nested_factory.mixin.accessor.ServerLevelBlockEventsAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Shared entry points used by vanilla tick mixins and the factory freeze transaction. */
public final class PocketFreezeHooks {
    private record TickQueueBinding(ServerLevel level, PocketFreezeSavedData.TickKind kind) {
    }

    private static final Map<LevelTicks<?>, TickQueueBinding> TICK_QUEUES =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ThreadLocal<Boolean> RESTORING = ThreadLocal.withInitial(() -> false);

    private PocketFreezeHooks() {
    }

    public static void registerLevel(ServerLevel level) {
        if (!level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) return;
        TICK_QUEUES.put(level.getBlockTicks(),
                new TickQueueBinding(level, PocketFreezeSavedData.TickKind.BLOCK));
        TICK_QUEUES.put(level.getFluidTicks(),
                new TickQueueBinding(level, PocketFreezeSavedData.TickKind.FLUID));
        PocketFreezeSavedData.get(level.getServer());
    }

    public static void unregisterLevel(ServerLevel level) {
        TICK_QUEUES.remove(level.getBlockTicks());
        TICK_QUEUES.remove(level.getFluidTicks());
    }

    public static boolean isFrozen(Level level, BlockPos pos) {
        return level instanceof ServerLevel serverLevel
                && serverLevel.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)
                && PocketFreezeSavedData.get(serverLevel.getServer()).isFrozen(pos);
    }

    public static boolean intersectsFrozenRoom(Level level, AABB box) {
        return level instanceof ServerLevel serverLevel
                && serverLevel.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)
                && PocketFreezeSavedData.get(serverLevel.getServer()).intersectsFrozenRoom(box);
    }

    public static boolean captureScheduledTick(LevelTicks<?> queue, ScheduledTick<?> tick) {
        if (RESTORING.get()) return false;
        TickQueueBinding binding = TICK_QUEUES.get(queue);
        if (binding == null) return false;
        String typeId = typeId(binding.kind(), tick.type());
        if (typeId == null) return false;
        long remainingDelay = Math.max(0L, tick.triggerTick() - binding.level().getGameTime());
        return PocketFreezeSavedData.get(binding.level().getServer()).captureTick(
                tick.pos(), binding.kind(), typeId, remainingDelay,
                tick.priority().name(), tick.subTickOrder());
    }

    public static boolean captureBlockEvent(ServerLevel level, BlockPos pos, Block block,
                                            int paramA, int paramB) {
        if (RESTORING.get() || !level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) return false;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return PocketFreezeSavedData.get(level.getServer())
                .captureBlockEvent(pos, id.toString(), paramA, paramB);
    }

    public static void capturePhysicalQueues(ServerLevel pocket, String rootFactoryId,
                                             List<PocketFreezeSavedData.FrozenRoom> rooms) {
        registerLevel(pocket);
        captureTicks(pocket, rootFactoryId, rooms, pocket.getBlockTicks(),
                PocketFreezeSavedData.TickKind.BLOCK);
        captureTicks(pocket, rootFactoryId, rooms, pocket.getFluidTicks(),
                PocketFreezeSavedData.TickKind.FLUID);

        PocketFreezeSavedData data = PocketFreezeSavedData.get(pocket.getServer());
        var blockEvents = ((ServerLevelBlockEventsAccessor) pocket).createNestedFactory$getBlockEvents();
        blockEvents.removeIf(event -> {
            if (!insideAny(rooms, event.pos())) return false;
            data.captureBlockEvent(event.pos(), BuiltInRegistries.BLOCK.getKey(event.block()).toString(),
                    event.paramA(), event.paramB());
            return true;
        });
    }

    private static <T> void captureTicks(ServerLevel pocket, String rootFactoryId,
                                         List<PocketFreezeSavedData.FrozenRoom> rooms,
                                         LevelTicks<T> levelTicks, PocketFreezeSavedData.TickKind kind) {
        PocketFreezeSavedData data = PocketFreezeSavedData.get(pocket.getServer());
        List<ScheduledTick<T>> captured = new ArrayList<>();
        @SuppressWarnings("unchecked")
        LevelTicksAccessor<T> accessor = (LevelTicksAccessor<T>) (Object) levelTicks;
        for (LevelChunkTicks<T> container : accessor.createNestedFactory$getAllContainers().values()) {
            container.getAll().filter(tick -> insideAny(rooms, tick.pos())).forEach(captured::add);
        }
        long now = pocket.getGameTime();
        for (ScheduledTick<T> tick : captured) {
            String id = typeId(kind, tick.type());
            if (id != null) {
                data.captureTick(tick.pos(), kind, id, Math.max(0L, tick.triggerTick() - now),
                        tick.priority().name(), tick.subTickOrder());
            }
        }
        for (PocketFreezeSavedData.FrozenRoom room : rooms) {
            levelTicks.clearArea(new BoundingBox(room.minX(), room.minY(), room.minZ(),
                    room.maxX(), room.maxY(), room.maxZ()));
        }
    }

    public static void restorePhysicalQueues(ServerLevel pocket, PocketFreezeSavedData.FrozenTree tree) {
        registerLevel(pocket);
        PocketFreezeSavedData data = PocketFreezeSavedData.get(pocket.getServer());
        RESTORING.set(true);
        try {
            long now = pocket.getGameTime();
            for (PocketFreezeSavedData.FrozenScheduledTick frozen : tree.scheduledTicks()) {
                if (data.isFrozen(frozen.pos())) {
                    data.captureTick(frozen.pos(), frozen.kind(), frozen.typeId(), frozen.remainingDelay(),
                            frozen.priority(), frozen.subTickOrder());
                    continue;
                }
                ResourceLocation typeId = ResourceLocation.tryParse(frozen.typeId());
                if (typeId == null) continue;
                TickPriority priority;
                try {
                    priority = TickPriority.valueOf(frozen.priority());
                } catch (IllegalArgumentException ignored) {
                    priority = TickPriority.NORMAL;
                }
                TickPriority restoredPriority = priority;
                if (frozen.kind() == PocketFreezeSavedData.TickKind.BLOCK) {
                    BuiltInRegistries.BLOCK.getOptional(typeId).ifPresent(block ->
                            pocket.getBlockTicks().schedule(new ScheduledTick<>(block, frozen.pos(),
                                    now + frozen.remainingDelay(), restoredPriority, frozen.subTickOrder())));
                } else {
                    BuiltInRegistries.FLUID.getOptional(typeId).ifPresent(fluid ->
                            pocket.getFluidTicks().schedule(new ScheduledTick<>(fluid, frozen.pos(),
                                    now + frozen.remainingDelay(), restoredPriority, frozen.subTickOrder())));
                }
            }
            for (PocketFreezeSavedData.FrozenBlockEvent frozen : tree.blockEvents()) {
                if (data.isFrozen(frozen.pos())) {
                    data.captureBlockEvent(frozen.pos(), frozen.blockId(), frozen.paramA(), frozen.paramB());
                    continue;
                }
                ResourceLocation blockId = ResourceLocation.tryParse(frozen.blockId());
                if (blockId == null) continue;
                BuiltInRegistries.BLOCK.getOptional(blockId).ifPresent(block ->
                        pocket.blockEvent(frozen.pos(), block, frozen.paramA(), frozen.paramB()));
            }
        } finally {
            RESTORING.remove();
        }
    }

    private static boolean insideAny(List<PocketFreezeSavedData.FrozenRoom> rooms, BlockPos pos) {
        for (PocketFreezeSavedData.FrozenRoom room : rooms) {
            if (room.contains(pos)) return true;
        }
        return false;
    }

    private static String typeId(PocketFreezeSavedData.TickKind kind, Object type) {
        if (kind == PocketFreezeSavedData.TickKind.BLOCK && type instanceof Block block) {
            return BuiltInRegistries.BLOCK.getKey(block).toString();
        }
        if (kind == PocketFreezeSavedData.TickKind.FLUID && type instanceof Fluid fluid) {
            return BuiltInRegistries.FLUID.getKey(fluid).toString();
        }
        return null;
    }
}

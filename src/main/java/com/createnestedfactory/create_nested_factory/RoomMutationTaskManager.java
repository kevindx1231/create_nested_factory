package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** World-level, restart-safe scheduler for Pocket room validation and mutation work. */
public final class RoomMutationTaskManager extends SavedData {
    private static final String DATA_NAME = "create_nested_factory_room_tasks";
    private static final int TASK_FORMAT = 1;
    /** Largest normal validation slab after its one-block inward safety margin: 17 x 32 x 48. */
    private static final long MAX_IMMEDIATE_COLLAPSE_VALIDATION_VOLUME = 26_112L;

    public enum Type { BUILD, EXPAND, VALIDATE_COLLAPSE, COLLAPSE, DESTROY }

    public record FactoryRef(ResourceKey<Level> dimension, BlockPos pos, String factoryId, String rootFactoryId) {
        boolean matches(NestedFactoryBlockEntity factory) {
            return factory.getFactoryId().equals(factoryId) && factory.getRootFactoryId().equals(rootFactoryId);
        }
    }

    private static final class Task {
        private Type type;
        private final ResourceKey<Level> pocketDimension;
        private final BlockPos origin;
        private final int[] shellBounds;
        private final int[] clearBounds;
        private final int[] validateBounds;
        private final int[] playerValidateBounds;
        private final FactoryRef factory;
        private final String direction;
        private final String refundItemId;
        private final String requesterId;
        private final boolean removeFactoryOnFinish;
        private String failureMessageKey = "";
        private int stage;
        private long cursor;

        private Task(Type type, ResourceKey<Level> pocketDimension, BlockPos origin, int[] shellBounds,
                     int[] clearBounds, int[] validateBounds, FactoryRef factory, boolean removeFactoryOnFinish,
                     String direction, String refundItemId) {
            this(type, pocketDimension, origin, shellBounds, clearBounds, validateBounds, factory,
                    removeFactoryOnFinish, direction, refundItemId, "", null);
        }

        private Task(Type type, ResourceKey<Level> pocketDimension, BlockPos origin, int[] shellBounds,
                     int[] clearBounds, int[] validateBounds, FactoryRef factory, boolean removeFactoryOnFinish,
                     String direction, String refundItemId, String requesterId) {
            this(type, pocketDimension, origin, shellBounds, clearBounds, validateBounds, factory,
                    removeFactoryOnFinish, direction, refundItemId, requesterId, null);
        }

        private Task(Type type, ResourceKey<Level> pocketDimension, BlockPos origin, int[] shellBounds,
                     int[] clearBounds, int[] validateBounds, FactoryRef factory, boolean removeFactoryOnFinish,
                     String direction, String refundItemId, String requesterId, int[] playerValidateBounds) {
            this.type = type;
            this.pocketDimension = pocketDimension;
            this.origin = origin.immutable();
            this.shellBounds = shellBounds;
            this.clearBounds = clearBounds;
            this.validateBounds = validateBounds;
            this.playerValidateBounds = playerValidateBounds;
            this.factory = factory;
            this.removeFactoryOnFinish = removeFactoryOnFinish;
            this.direction = direction;
            this.refundItemId = refundItemId;
            this.requesterId = requesterId == null ? "" : requesterId;
        }

        private String lockKey() {
            return pocketDimension.location() + ":" + origin.asLong();
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private final Set<String> removalInProgress = new HashSet<>();

    public static RoomMutationTaskManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RoomMutationTaskManager::new, RoomMutationTaskManager::load), DATA_NAME);
    }

    public synchronized boolean isRoomLocked(ResourceKey<Level> pocketDimension, BlockPos origin) {
        String key = pocketDimension.location() + ":" + origin.asLong();
        return tasks.stream().anyMatch(task -> task.lockKey().equals(key));
    }

    public synchronized boolean isRemovingFactory(FactoryRef reference) {
        return removalInProgress.contains(factoryKey(reference));
    }

    public synchronized boolean scheduleBuild(ResourceKey<Level> dimension, BlockPos origin, int[] bounds, FactoryRef factory) {
        return add(new Task(Type.BUILD, dimension, origin, bounds.clone(), null, null, factory, false, "", ""));
    }

    public synchronized boolean scheduleExpand(ResourceKey<Level> dimension, BlockPos origin, int[] newBounds,
                                                int[] clearBounds, FactoryRef factory) {
        return add(new Task(Type.EXPAND, dimension, origin, newBounds.clone(), clearBounds.clone(), null, factory, false, "", ""));
    }

    public synchronized boolean scheduleCollapse(ResourceKey<Level> dimension, BlockPos origin, int[] newBounds,
                                                  int[] clearBounds, FactoryRef factory) {
        return add(new Task(Type.COLLAPSE, dimension, origin, newBounds.clone(), clearBounds.clone(), null, factory, false, "", ""));
    }

    public synchronized boolean scheduleCollapseValidation(ResourceKey<Level> dimension, BlockPos origin,
                                                            int[] newBounds, int[] clearBounds, int[] validateBounds,
                                                            int[] playerValidateBounds, FactoryRef factory, String direction,
                                                            Item refundItem, UUID requesterId) {
        return add(new Task(Type.VALIDATE_COLLAPSE, dimension, origin, newBounds.clone(), clearBounds.clone(),
                validateBounds.clone(), factory, false, direction, BuiltInRegistries.ITEM.getKey(refundItem).toString(),
                requesterId == null ? "" : requesterId.toString(), playerValidateBounds.clone()));
    }

    public synchronized boolean scheduleDestroy(ResourceKey<Level> dimension, BlockPos origin, int[] bounds,
                                                 FactoryRef factory, boolean removeFactoryOnFinish) {
        return add(new Task(Type.DESTROY, dimension, origin, null, bounds.clone(), null, factory, removeFactoryOnFinish, "", ""));
    }

    private boolean add(Task task) {
        if (isRoomLocked(task.pocketDimension, task.origin)) {
            return false;
        }
        tasks.add(task);
        setDirty();
        return true;
    }

    public synchronized void tick(MinecraftServer server) {
        Iterator<Task> iterator = tasks.iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next();
            ServerLevel pocket = server.getLevel(task.pocketDimension);
            if (pocket == null) {
                continue;
            }
            forceTaskChunks(pocket, task);
            boolean complete = processTask(server, pocket, task, Math.max(1, Config.roomMutationBlocksPerTick));
            if (!complete) {
                continue;
            }
            finishTask(server, task);
            PocketChunkForceManager.releaseAll(server, taskOwner(task));
            iterator.remove();
            setDirty();
        }
    }

    private void forceTaskChunks(ServerLevel level, Task task) {
        Set<ChunkPos> chunks = new HashSet<>();
        addTaskChunks(chunks, task.shellBounds);
        addTaskChunks(chunks, task.clearBounds);
        PocketChunkForceManager.replace(level, taskOwner(task), chunks);
    }

    private static void addTaskChunks(Set<ChunkPos> chunks, int[] bounds) {
        if (bounds == null) return;
        int minChunkX = Math.floorDiv(bounds[0], 16);
        int maxChunkX = Math.floorDiv(bounds[3], 16);
        int minChunkZ = Math.floorDiv(bounds[2], 16);
        int maxChunkZ = Math.floorDiv(bounds[5], 16);
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
    }

    private static String taskOwner(Task task) {
        return "room_task:" + task.lockKey();
    }

    private boolean processTask(MinecraftServer server, ServerLevel level, Task task, int budget) {
        if (task.type == Type.VALIDATE_COLLAPSE) {
            // Legal collapse slabs are bounded, so validate them atomically to let a successful
            // collapse show its new boundary in the same tick as the item use. Corrupt or future
            // larger task data keeps the normal budgeted path.
            int validationBudget = volume(task.validateBounds) <= MAX_IMMEDIATE_COLLAPSE_VALIDATION_VOLUME
                    ? Integer.MAX_VALUE : budget;
            boolean validationFinished = processCollapseValidation(level, task, validationBudget);
            if (validationFinished) {
                return true;
            }
            // A successful validation converts the task to COLLAPSE. Continue in this tick so
            // the new boundary is visible immediately instead of waiting for another tick.
            if (task.type == Type.VALIDATE_COLLAPSE) {
                return false;
            }
        }
        if (task.type == Type.BUILD) {
            if (task.stage == 0) {
                if (!processBuild(level, task, budget)) return false;
                task.stage = 100;
                task.cursor = 0;
            }
            return processSyncRegion(level, task.shellBounds, task, budget);
        }
        if (task.type == Type.DESTROY) {
            if (task.stage == 0) {
                if (!processRegion(level, task, task.clearBounds, Blocks.AIR.defaultBlockState(), budget, false)) return false;
                task.stage = 100;
                task.cursor = 0;
            }
            return processSyncRegion(level, task.clearBounds, task, budget);
        }
        if (task.stage == 0) {
            // Show the target room boundary first. The old implementation scanned the whole
            // room volume before the client saw any result, even though only wall blocks matter.
            // A room surface is bounded to roughly ten thousand blocks at the configured
            // maximum size. Make that visual boundary atomic; only the interior cleanup remains
            // budgeted across ticks.
            if (!processPriorityShell(level, task, Integer.MAX_VALUE)) return false;
            task.stage = 1;
            task.cursor = 0;
        }
        if (task.stage == 1) {
            if (!processRegion(level, task, task.clearBounds, Blocks.AIR.defaultBlockState(), budget, false)) return false;
            // New tasks have already synchronized their shell incrementally. Stage 100 remains
            // supported below for tasks saved by versions that used a deferred full-room sync.
            task.stage = 101;
            task.cursor = 0;
        }
        if (task.stage == 100) {
            if (!processSyncRegion(level, task.shellBounds, task, budget)) return false;
            task.stage = 101;
            task.cursor = 0;
        }
        return processSyncRegion(level, task.clearBounds, task, budget);
    }

    private boolean processCollapseValidation(ServerLevel level, Task task, int budget) {
        long volume = volume(task.validateBounds);
        for (int processed = 0; processed < budget && task.cursor < volume; processed++, task.cursor++) {
            BlockPos pos = position(task.validateBounds, task.cursor);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !NestedFactoryBlock.isWallBlock(state)) {
                task.stage = -1;
                task.failureMessageKey = "message.create_nested_factory.space_collapse.block_obstructed";
                return true;
            }
            if (!level.getFluidState(pos).isEmpty()) {
                task.stage = -1;
                task.failureMessageKey = "message.create_nested_factory.space_collapse.fluid_obstructed";
                return true;
            }
        }
        if (task.cursor < volume) {
            return false;
        }
        int[] playerBounds = task.playerValidateBounds == null ? task.validateBounds : task.playerValidateBounds;
        List<ServerPlayer> players = level.getEntitiesOfClass(ServerPlayer.class,
                new net.minecraft.world.phys.AABB(playerBounds[0], playerBounds[1], playerBounds[2],
                        playerBounds[3] + 1.0, playerBounds[4] + 1.0, playerBounds[5] + 1.0),
                player -> true);
        if (!players.isEmpty()) {
            task.stage = -1;
            task.failureMessageKey = "message.create_nested_factory.space_collapse.player_obstructed";
            return true;
        }
        List<net.minecraft.world.entity.Entity> entities = level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                new net.minecraft.world.phys.AABB(task.validateBounds[0], task.validateBounds[1], task.validateBounds[2],
                        task.validateBounds[3] + 1.0, task.validateBounds[4] + 1.0, task.validateBounds[5] + 1.0),
                entity -> !(entity instanceof ServerPlayer));
        if (!entities.isEmpty()) {
            task.stage = -1;
            task.failureMessageKey = "message.create_nested_factory.space_collapse.entity_obstructed";
            return true;
        }
        task.type = Type.COLLAPSE;
        task.stage = 0;
        task.cursor = 0;
        return false;
    }

    private boolean processBuild(ServerLevel level, Task task, int budget) {
        long volume = volume(task.shellBounds);
        for (int processed = 0; processed < budget && task.cursor < volume; processed++, task.cursor++) {
            BlockPos pos = position(task.shellBounds, task.cursor);
            BlockState state = isBoundary(task.shellBounds, pos)
                    ? NestedFactoryBlock.wallState(pos.getX(), pos.getY(), pos.getZ())
                    : Blocks.AIR.defaultBlockState();
            writeSilent(level, pos, state);
        }
        return task.cursor >= volume;
    }

    /**
     * Writes and immediately syncs only the boundary surface, not every interior position in the
     * enclosing cuboid. This gives expand/collapse instant visible walls while preserving the
     * bounded per-tick mutation budget for the remaining clear work.
     */
    private boolean processPriorityShell(ServerLevel level, Task task, int budget) {
        long boundaryVolume = boundaryVolume(task.shellBounds);
        for (int processed = 0; processed < budget && task.cursor < boundaryVolume; processed++, task.cursor++) {
            BlockPos pos = boundaryPosition(task.shellBounds, task.cursor);
            BlockState state = NestedFactoryBlock.wallState(pos.getX(), pos.getY(), pos.getZ());
            writeSilent(level, pos, state);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
        return task.cursor >= boundaryVolume;
    }

    private boolean processSyncRegion(ServerLevel level, int[] region, Task task, int budget) {
        if (region == null) return true;
        long volume = volume(region);
        for (int processed = 0; processed < budget && task.cursor < volume; processed++, task.cursor++) {
            BlockPos pos = position(region, task.cursor);
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
        return task.cursor >= volume;
    }

    private boolean processRegion(ServerLevel level, Task task, int[] region, BlockState state,
                                  int budget, boolean boundaryOnly) {
        if (region == null) {
            return true;
        }
        long volume = volume(region);
        for (int processed = 0; processed < budget && task.cursor < volume; processed++, task.cursor++) {
            BlockPos pos = position(region, task.cursor);
            if (!boundaryOnly || isBoundary(region, pos)) {
                writeSilent(level, pos, state);
            }
        }
        return task.cursor >= volume;
    }

    private void finishTask(MinecraftServer server, Task task) {
        NestedFactoryBlockEntity factory = resolveFactory(server, task.factory);
        if (task.type == Type.VALIDATE_COLLAPSE && task.stage < 0) {
            if (factory != null) factory.onCollapseValidationTaskFailed(task.direction);
            sendCollapseFailureMessage(server, task);
            refundCollapseItem(server, task);
            return;
        }
        if (factory != null) {
            factory.onRoomMutationTaskFinished();
        }
        if (task.removeFactoryOnFinish && task.factory != null) {
            ServerLevel factoryLevel = server.getLevel(task.factory.dimension());
            if (factoryLevel != null && factoryLevel.getBlockEntity(task.factory.pos()) instanceof NestedFactoryBlockEntity current
                    && task.factory.matches(current)) {
                removalInProgress.add(factoryKey(task.factory));
                try {
                    factoryLevel.setBlock(task.factory.pos(), Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
                } finally {
                    removalInProgress.remove(factoryKey(task.factory));
                }
            }
        }
    }

    private static void sendCollapseFailureMessage(MinecraftServer server, Task task) {
        if (task.requesterId.isBlank() || task.failureMessageKey.isBlank()) {
            return;
        }
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(task.requesterId));
            if (player != null) {
                PlayerMessagePayload.sendTo(player, Component.translatable(task.failureMessageKey)
                        .withStyle(ChatFormatting.RED), false);
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed persisted requester id must not prevent task cleanup or item refund.
        }
    }

    private static void refundCollapseItem(MinecraftServer server, Task task) {
        ResourceLocation id = ResourceLocation.tryParse(task.refundItemId);
        ServerLevel level = server.getLevel(task.pocketDimension);
        if (id == null || level == null || !BuiltInRegistries.ITEM.containsKey(id)) return;
        Item item = BuiltInRegistries.ITEM.get(id);
        level.addFreshEntity(new ItemEntity(level, task.origin.getX() + 0.5, task.origin.getY() + 1.0,
                task.origin.getZ() + 0.5, new ItemStack(item)));
    }

    private static NestedFactoryBlockEntity resolveFactory(MinecraftServer server, FactoryRef reference) {
        if (reference == null) {
            return null;
        }
        ServerLevel level = server.getLevel(reference.dimension());
        if (level == null) {
            return null;
        }
        return level.getBlockEntity(reference.pos()) instanceof NestedFactoryBlockEntity factory && reference.matches(factory)
                ? factory : null;
    }

    private static String factoryKey(FactoryRef reference) {
        return reference.dimension().location() + ":" + reference.pos().asLong() + ":" + reference.factoryId();
    }

    private static void writeSilent(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
    }

    private static long volume(int[] bounds) {
        return (long) (bounds[3] - bounds[0] + 1) * (bounds[4] - bounds[1] + 1) * (bounds[5] - bounds[2] + 1);
    }

    private static BlockPos position(int[] bounds, long index) {
        int width = bounds[3] - bounds[0] + 1;
        int depth = bounds[5] - bounds[2] + 1;
        int x = bounds[0] + (int) (index % width);
        long yz = index / width;
        int z = bounds[2] + (int) (yz % depth);
        int y = bounds[1] + (int) (yz / depth);
        return new BlockPos(x, y, z);
    }

    private static long boundaryVolume(int[] bounds) {
        int width = bounds[3] - bounds[0] + 1;
        int height = bounds[4] - bounds[1] + 1;
        int depth = bounds[5] - bounds[2] + 1;
        if (width <= 2 || height <= 2 || depth <= 2) {
            return volume(bounds);
        }
        return 2L * width * depth + (long) (height - 2) * (2L * width + 2L * depth - 4L);
    }

    /** Maps a cursor to each cuboid surface block exactly once: bottom, top, X faces, then Z faces. */
    private static BlockPos boundaryPosition(int[] bounds, long index) {
        int minX = bounds[0], minY = bounds[1], minZ = bounds[2];
        int width = bounds[3] - minX + 1;
        int height = bounds[4] - minY + 1;
        int depth = bounds[5] - minZ + 1;
        if (width <= 2 || height <= 2 || depth <= 2) {
            return position(bounds, index);
        }

        long horizontalFace = (long) width * depth;
        if (index < horizontalFace) {
            return new BlockPos(minX + (int) (index % width), minY, minZ + (int) (index / width));
        }
        index -= horizontalFace;
        if (index < horizontalFace) {
            return new BlockPos(minX + (int) (index % width), minY + height - 1, minZ + (int) (index / width));
        }
        index -= horizontalFace;

        int innerHeight = height - 2;
        long xFace = (long) innerHeight * depth;
        if (index < xFace) {
            return new BlockPos(minX, minY + 1 + (int) (index / depth), minZ + (int) (index % depth));
        }
        index -= xFace;
        if (index < xFace) {
            return new BlockPos(minX + width - 1, minY + 1 + (int) (index / depth), minZ + (int) (index % depth));
        }
        index -= xFace;

        int innerWidth = width - 2;
        long zFace = (long) innerHeight * innerWidth;
        if (index < zFace) {
            return new BlockPos(minX + 1 + (int) (index % innerWidth), minY + 1 + (int) (index / innerWidth), minZ);
        }
        index -= zFace;
        return new BlockPos(minX + 1 + (int) (index % innerWidth), minY + 1 + (int) (index / innerWidth), minZ + depth - 1);
    }

    private static boolean isBoundary(int[] bounds, BlockPos pos) {
        return pos.getX() == bounds[0] || pos.getX() == bounds[3]
                || pos.getY() == bounds[1] || pos.getY() == bounds[4]
                || pos.getZ() == bounds[2] || pos.getZ() == bounds[5];
    }

    public static RoomMutationTaskManager load(CompoundTag tag, HolderLookup.Provider registries) {
        RoomMutationTaskManager manager = new RoomMutationTaskManager();
        if (tag.getInt("Format") != TASK_FORMAT) {
            return manager;
        }
        for (Tag raw : tag.getList("Tasks", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            try {
                Type type = Type.valueOf(entry.getString("Type"));
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(entry.getString("Dimension")));
                BlockPos origin = BlockPos.of(entry.getLong("Origin"));
                int[] shell = entry.contains("Shell") ? entry.getIntArray("Shell") : null;
                int[] clear = entry.contains("Clear") ? entry.getIntArray("Clear") : null;
                if (shell != null && shell.length != 6) shell = null;
                if (clear != null && clear.length != 6) clear = null;
                FactoryRef factory = null;
                if (entry.contains("Factory")) {
                    CompoundTag factoryTag = entry.getCompound("Factory");
                    factory = new FactoryRef(ResourceKey.create(Registries.DIMENSION,
                            ResourceLocation.parse(factoryTag.getString("Dimension"))),
                            BlockPos.of(factoryTag.getLong("Pos")), factoryTag.getString("FactoryId"),
                            factoryTag.getString("RootFactoryId"));
                }
                int[] validate = entry.contains("Validate") ? entry.getIntArray("Validate") : null;
                if (validate != null && validate.length != 6) validate = null;
                int[] playerValidate = entry.contains("PlayerValidate") ? entry.getIntArray("PlayerValidate") : null;
                if (playerValidate != null && playerValidate.length != 6) playerValidate = null;
                if (playerValidate == null && validate != null) playerValidate = validate.clone();
                Task task = new Task(type, dimension, origin, shell, clear, validate, factory,
                        entry.getBoolean("RemoveFactory"), entry.getString("Direction"), entry.getString("RefundItem"),
                        entry.getString("Requester"), playerValidate);
                task.stage = Math.max(0, entry.getInt("Stage"));
                task.cursor = Math.max(0L, entry.getLong("Cursor"));
                manager.tasks.add(task);
            } catch (IllegalArgumentException ignored) {
                // Malformed task entries are discarded; no partially parsed task is allowed to run.
            }
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Format", TASK_FORMAT);
        ListTag values = new ListTag();
        for (Task task : tasks) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Type", task.type.name());
            entry.putString("Dimension", task.pocketDimension.location().toString());
            entry.putLong("Origin", task.origin.asLong());
            if (task.shellBounds != null) entry.putIntArray("Shell", task.shellBounds);
            if (task.clearBounds != null) entry.putIntArray("Clear", task.clearBounds);
            if (task.validateBounds != null) entry.putIntArray("Validate", task.validateBounds);
            if (task.playerValidateBounds != null) entry.putIntArray("PlayerValidate", task.playerValidateBounds);
            if (task.factory != null) {
                CompoundTag factory = new CompoundTag();
                factory.putString("Dimension", task.factory.dimension().location().toString());
                factory.putLong("Pos", task.factory.pos().asLong());
                factory.putString("FactoryId", task.factory.factoryId());
                factory.putString("RootFactoryId", task.factory.rootFactoryId());
                entry.put("Factory", factory);
            }
            entry.putBoolean("RemoveFactory", task.removeFactoryOnFinish);
            entry.putString("Direction", task.direction);
            entry.putString("RefundItem", task.refundItemId);
            if (!task.requesterId.isBlank()) entry.putString("Requester", task.requesterId);
            entry.putInt("Stage", task.stage);
            entry.putLong("Cursor", task.cursor);
            values.add(entry);
        }
        tag.put("Tasks", values);
        return tag;
    }
}

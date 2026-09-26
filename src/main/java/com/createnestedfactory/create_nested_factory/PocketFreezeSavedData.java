package com.createnestedfactory.create_nested_factory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent, position-addressable freeze manifests for physical Pocket rooms.
 *
 * <p>Chunk tickets only control residency. These manifests are the authoritative guard which
 * prevents a black-box or blueprint room from resuming physical execution when another ticket
 * happens to load the same chunk column.</p>
 */
public final class PocketFreezeSavedData extends SavedData {
    private static final String DATA_NAME = "create_nested_factory_frozen_rooms";
    private static final int DATA_FORMAT = 1;

    public enum Phase {
        ACTIVE,
        THAWING
    }

    public enum TickKind {
        BLOCK,
        FLUID
    }

    public record FrozenRoom(String factoryId, String factoryDimension, BlockPos factoryPos,
                             int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                             int boundsVersion) {
        public FrozenRoom {
            factoryId = factoryId == null ? "" : factoryId;
            factoryDimension = factoryDimension == null ? "" : factoryDimension;
            factoryPos = factoryPos == null ? BlockPos.ZERO : factoryPos.immutable();
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        public boolean intersects(AABB box) {
            return box.maxX > minX && box.minX < maxX + 1.0
                    && box.maxY > minY && box.minY < maxY + 1.0
                    && box.maxZ > minZ && box.minZ < maxZ + 1.0;
        }

        public int minChunkX() {
            return minX >> 4;
        }

        public int maxChunkX() {
            return maxX >> 4;
        }

        public int minChunkZ() {
            return minZ >> 4;
        }

        public int maxChunkZ() {
            return maxZ >> 4;
        }
    }

    public record FrozenScheduledTick(TickKind kind, String typeId, BlockPos pos,
                                      long remainingDelay, String priority, long subTickOrder) {
        public FrozenScheduledTick {
            typeId = typeId == null ? "" : typeId;
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
            remainingDelay = Math.max(0L, remainingDelay);
            priority = priority == null ? "NORMAL" : priority;
        }
    }

    public record FrozenBlockEvent(String blockId, BlockPos pos, int paramA, int paramB) {
        public FrozenBlockEvent {
            blockId = blockId == null ? "" : blockId;
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
        }
    }

    public record FrozenTree(String rootFactoryId, Phase phase, List<FrozenRoom> rooms,
                             List<FrozenScheduledTick> scheduledTicks,
                             List<FrozenBlockEvent> blockEvents) {
        public FrozenTree {
            rooms = List.copyOf(rooms);
            scheduledTicks = List.copyOf(scheduledTicks);
            blockEvents = List.copyOf(blockEvents);
        }
    }

    private static final class MutableTree {
        private final String rootFactoryId;
        private Phase phase;
        private final List<FrozenRoom> rooms;
        private final List<FrozenScheduledTick> scheduledTicks;
        private final List<FrozenBlockEvent> blockEvents;
        private FrozenTree snapshot;

        private MutableTree(String rootFactoryId, Phase phase, List<FrozenRoom> rooms,
                            List<FrozenScheduledTick> scheduledTicks, List<FrozenBlockEvent> blockEvents) {
            this.rootFactoryId = rootFactoryId;
            this.phase = phase;
            this.rooms = new ArrayList<>(rooms);
            this.scheduledTicks = new ArrayList<>(scheduledTicks);
            this.blockEvents = new ArrayList<>(blockEvents);
        }

        private FrozenTree snapshot() {
            if (snapshot == null) {
                snapshot = new FrozenTree(rootFactoryId, phase, rooms, scheduledTicks, blockEvents);
            }
            return snapshot;
        }

        private void invalidateSnapshot() {
            snapshot = null;
        }
    }

    private final Map<String, MutableTree> treesByRoot = new LinkedHashMap<>();
    private volatile Map<Long, List<FrozenRoom>> roomsByChunk = Map.of();
    private List<FrozenTree> thawingSnapshot;

    public static PocketFreezeSavedData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PocketFreezeSavedData::new, PocketFreezeSavedData::load), DATA_NAME);
    }

    public boolean isFrozen(BlockPos pos) {
        for (FrozenRoom room : roomsByChunk.getOrDefault(ChunkPos.asLong(pos), List.of())) {
            if (room.contains(pos)) return true;
        }
        return false;
    }

    public boolean intersectsFrozenRoom(AABB box) {
        int minChunkX = ((int) Math.floor(box.minX)) >> 4;
        int maxChunkX = ((int) Math.floor(Math.nextDown(box.maxX))) >> 4;
        int minChunkZ = ((int) Math.floor(box.minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(Math.nextDown(box.maxZ))) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (FrozenRoom room : roomsByChunk.getOrDefault(ChunkPos.asLong(chunkX, chunkZ), List.of())) {
                    if (room.intersects(box)) return true;
                }
            }
        }
        return false;
    }

    public synchronized boolean hasTree(String rootFactoryId) {
        return treesByRoot.containsKey(rootFactoryId);
    }

    public synchronized void beginFreeze(String rootFactoryId, List<FrozenRoom> rooms) {
        if (rootFactoryId == null || rootFactoryId.isBlank()) return;
        MutableTree existing = treesByRoot.get(rootFactoryId);
        if (existing == null) {
            treesByRoot.put(rootFactoryId,
                    new MutableTree(rootFactoryId, Phase.ACTIVE, rooms, List.of(), List.of()));
        } else {
            // Re-entering a simulated mode (for example BLACKBOX_ACTIVE -> BLUEPRINT) must
            // retain the work that was already paused by the first freeze transaction.
            existing.phase = Phase.ACTIVE;
            existing.rooms.clear();
            existing.rooms.addAll(rooms);
            existing.invalidateSnapshot();
        }
        thawingSnapshot = null;
        rebuildIndex();
        setDirty();
    }

    public synchronized boolean beginThaw(String rootFactoryId) {
        MutableTree tree = treesByRoot.get(rootFactoryId);
        if (tree == null) return false;
        if (tree.phase != Phase.THAWING) {
            tree.phase = Phase.THAWING;
            tree.invalidateSnapshot();
            thawingSnapshot = null;
            setDirty();
        }
        return true;
    }

    public synchronized List<FrozenTree> thawingTrees() {
        if (thawingSnapshot == null) {
            thawingSnapshot = treesByRoot.values().stream()
                    .filter(tree -> tree.phase == Phase.THAWING)
                    .map(MutableTree::snapshot)
                    .toList();
        }
        return thawingSnapshot;
    }

    public synchronized FrozenTree removeTree(String rootFactoryId) {
        MutableTree removed = treesByRoot.remove(rootFactoryId);
        if (removed == null) return null;
        thawingSnapshot = null;
        rebuildIndex();
        setDirty();
        return removed.snapshot();
    }

    public synchronized boolean captureTick(BlockPos pos, TickKind kind, String typeId,
                                            long remainingDelay, String priority, long subTickOrder) {
        MutableTree tree = findTree(pos, null);
        if (tree == null) return false;
        boolean duplicate = tree.scheduledTicks.stream().anyMatch(tick -> tick.kind() == kind
                && tick.pos().equals(pos) && tick.typeId().equals(typeId));
        if (!duplicate) {
            tree.scheduledTicks.add(new FrozenScheduledTick(kind, typeId, pos,
                    remainingDelay, priority, subTickOrder));
            tree.invalidateSnapshot();
            thawingSnapshot = null;
            setDirty();
        }
        return true;
    }

    public synchronized boolean captureBlockEvent(BlockPos pos, String blockId, int paramA, int paramB) {
        MutableTree tree = findTree(pos, null);
        if (tree == null) return false;
        FrozenBlockEvent event = new FrozenBlockEvent(blockId, pos, paramA, paramB);
        if (!tree.blockEvents.contains(event)) {
            tree.blockEvents.add(event);
            tree.invalidateSnapshot();
            thawingSnapshot = null;
            setDirty();
        }
        return true;
    }

    private MutableTree findTree(BlockPos pos, String excludedRoot) {
        for (MutableTree tree : treesByRoot.values()) {
            if (tree.rootFactoryId.equals(excludedRoot)) continue;
            for (FrozenRoom room : tree.rooms) {
                if (room.contains(pos)) return tree;
            }
        }
        return null;
    }

    private synchronized void rebuildIndex() {
        Map<Long, List<FrozenRoom>> mutable = new HashMap<>();
        for (MutableTree tree : treesByRoot.values()) {
            for (FrozenRoom room : tree.rooms) {
                for (int chunkX = room.minChunkX(); chunkX <= room.maxChunkX(); chunkX++) {
                    for (int chunkZ = room.minChunkZ(); chunkZ <= room.maxChunkZ(); chunkZ++) {
                        mutable.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), ignored -> new ArrayList<>()).add(room);
                    }
                }
            }
        }
        Map<Long, List<FrozenRoom>> immutable = new HashMap<>();
        mutable.forEach((key, rooms) -> immutable.put(key, List.copyOf(rooms)));
        roomsByChunk = Map.copyOf(immutable);
    }

    public static PocketFreezeSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PocketFreezeSavedData data = new PocketFreezeSavedData();
        if (tag.getInt("Format") != DATA_FORMAT) return data;
        for (Tag rawTree : tag.getList("Trees", Tag.TAG_COMPOUND)) {
            CompoundTag treeTag = (CompoundTag) rawTree;
            String rootFactoryId = treeTag.getString("RootFactoryId");
            if (rootFactoryId.isBlank()) continue;
            Phase phase;
            try {
                phase = Phase.valueOf(treeTag.getString("Phase"));
            } catch (IllegalArgumentException ignored) {
                phase = Phase.ACTIVE;
            }
            List<FrozenRoom> rooms = new ArrayList<>();
            for (Tag rawRoom : treeTag.getList("Rooms", Tag.TAG_COMPOUND)) {
                CompoundTag room = (CompoundTag) rawRoom;
                rooms.add(new FrozenRoom(room.getString("FactoryId"), room.getString("FactoryDimension"),
                        BlockPos.of(room.getLong("FactoryPos")),
                        room.getInt("MinX"), room.getInt("MinY"), room.getInt("MinZ"),
                        room.getInt("MaxX"), room.getInt("MaxY"), room.getInt("MaxZ"),
                        room.getInt("BoundsVersion")));
            }
            if (rooms.isEmpty() && !treeTag.getBoolean("LogicalOnly")) continue;
            List<FrozenScheduledTick> ticks = new ArrayList<>();
            for (Tag rawTick : treeTag.getList("ScheduledTicks", Tag.TAG_COMPOUND)) {
                CompoundTag tick = (CompoundTag) rawTick;
                try {
                    ticks.add(new FrozenScheduledTick(TickKind.valueOf(tick.getString("Kind")),
                            tick.getString("Type"), BlockPos.of(tick.getLong("Pos")),
                            tick.getLong("RemainingDelay"), tick.getString("Priority"),
                            tick.getLong("SubTickOrder")));
                } catch (IllegalArgumentException ignored) {
                    // Unknown tick kinds are intentionally discarded instead of preventing world load.
                }
            }
            List<FrozenBlockEvent> events = new ArrayList<>();
            for (Tag rawEvent : treeTag.getList("BlockEvents", Tag.TAG_COMPOUND)) {
                CompoundTag event = (CompoundTag) rawEvent;
                events.add(new FrozenBlockEvent(event.getString("Block"), BlockPos.of(event.getLong("Pos")),
                        event.getInt("ParamA"), event.getInt("ParamB")));
            }
            data.treesByRoot.put(rootFactoryId,
                    new MutableTree(rootFactoryId, phase, rooms, ticks, events));
        }
        data.rebuildIndex();
        return data;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Format", DATA_FORMAT);
        ListTag trees = new ListTag();
        for (MutableTree tree : treesByRoot.values()) {
            CompoundTag treeTag = new CompoundTag();
            treeTag.putString("RootFactoryId", tree.rootFactoryId);
            treeTag.putString("Phase", tree.phase.name());
            treeTag.putBoolean("LogicalOnly", tree.rooms.isEmpty());
            ListTag rooms = new ListTag();
            for (FrozenRoom room : tree.rooms) {
                CompoundTag roomTag = new CompoundTag();
                roomTag.putString("FactoryId", room.factoryId());
                roomTag.putString("FactoryDimension", room.factoryDimension());
                roomTag.putLong("FactoryPos", room.factoryPos().asLong());
                roomTag.putInt("MinX", room.minX());
                roomTag.putInt("MinY", room.minY());
                roomTag.putInt("MinZ", room.minZ());
                roomTag.putInt("MaxX", room.maxX());
                roomTag.putInt("MaxY", room.maxY());
                roomTag.putInt("MaxZ", room.maxZ());
                roomTag.putInt("BoundsVersion", room.boundsVersion());
                rooms.add(roomTag);
            }
            treeTag.put("Rooms", rooms);
            ListTag ticks = new ListTag();
            for (FrozenScheduledTick tick : tree.scheduledTicks) {
                CompoundTag tickTag = new CompoundTag();
                tickTag.putString("Kind", tick.kind().name());
                tickTag.putString("Type", tick.typeId());
                tickTag.putLong("Pos", tick.pos().asLong());
                tickTag.putLong("RemainingDelay", tick.remainingDelay());
                tickTag.putString("Priority", tick.priority());
                tickTag.putLong("SubTickOrder", tick.subTickOrder());
                ticks.add(tickTag);
            }
            treeTag.put("ScheduledTicks", ticks);
            ListTag events = new ListTag();
            for (FrozenBlockEvent event : tree.blockEvents) {
                CompoundTag eventTag = new CompoundTag();
                eventTag.putString("Block", event.blockId());
                eventTag.putLong("Pos", event.pos().asLong());
                eventTag.putInt("ParamA", event.paramA());
                eventTag.putInt("ParamB", event.paramB());
                events.add(eventTag);
            }
            treeTag.put("BlockEvents", events);
            trees.add(treeTag);
        }
        tag.put("Trees", trees);
        return tag;
    }
}

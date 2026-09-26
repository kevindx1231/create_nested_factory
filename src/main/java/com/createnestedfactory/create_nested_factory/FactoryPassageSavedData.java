package com.createnestedfactory.create_nested_factory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

/** Persistent location index for passage endpoints, including endpoints in unloaded chunks. */
public final class FactoryPassageSavedData extends SavedData {
    private static final String DATA_NAME = "create_nested_factory_passages";
    private static final int DATA_FORMAT = 3;
    private static final ResourceKey<Level> POCKET_DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "nested_factory"));

    public record Endpoint(ResourceKey<Level> dimension, BlockPos pos, String passageId, String targetFactoryId,
                           String targetRootFactoryId,
                           long placementSequence) {
        public Endpoint {
            pos = pos.immutable();
            passageId = passageId == null ? "" : passageId;
            targetFactoryId = targetFactoryId == null ? "" : targetFactoryId;
            targetRootFactoryId = targetRootFactoryId == null ? "" : targetRootFactoryId;
            placementSequence = Math.max(1L, placementSequence);
        }

        public boolean isBound() {
            return !targetFactoryId.isBlank();
        }
    }

    private record EndpointKey(ResourceKey<Level> dimension, BlockPos pos) {
        private EndpointKey {
            pos = pos.immutable();
        }
    }

    private final Map<EndpointKey, Endpoint> endpoints = new HashMap<>();
    private final Map<String, EndpointKey> keysByPassageId = new HashMap<>();
    private final Map<String, Set<EndpointKey>> boundKeysByFactory = new HashMap<>();
    private final Map<Long, Set<EndpointKey>> unboundPocketKeysByChunk = new HashMap<>();
    private long nextPlacementSequence = 1L;

    private static final Comparator<Endpoint> PLACEMENT_ORDER = Comparator
            .comparingLong(Endpoint::placementSequence)
            .thenComparing(endpoint -> endpoint.dimension().location().toString())
            .thenComparingLong(endpoint -> endpoint.pos().asLong());

    public static FactoryPassageSavedData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FactoryPassageSavedData::new, FactoryPassageSavedData::load),
                DATA_NAME);
    }

    public synchronized void register(ResourceKey<Level> dimension, BlockPos pos, String targetFactoryId) {
        register(dimension, pos, "", targetFactoryId, "");
    }

    public synchronized void register(ResourceKey<Level> dimension, BlockPos pos, String targetFactoryId,
                                      String targetRootFactoryId) {
        register(dimension, pos, "", targetFactoryId, targetRootFactoryId);
    }

    public synchronized void register(ResourceKey<Level> dimension, BlockPos pos, String passageId,
                                      String targetFactoryId, String targetRootFactoryId) {
        EndpointKey key = new EndpointKey(dimension, pos);
        Endpoint previous = endpoints.get(key);
        EndpointKey oldKey = passageId == null || passageId.isBlank() ? null : keysByPassageId.get(passageId);
        Endpoint moved = oldKey == null || oldKey.equals(key) ? null : endpoints.get(oldKey);
        long sequence = previous != null ? previous.placementSequence()
                : moved != null ? moved.placementSequence() : allocatePlacementSequence();
        Endpoint endpoint = new Endpoint(dimension, pos, passageId, targetFactoryId, targetRootFactoryId, sequence);
        if (endpoint.equals(previous)) return;
        if (!endpoint.passageId().isBlank()) {
            if (oldKey != null && !oldKey.equals(key)) {
                Endpoint removed = endpoints.remove(oldKey);
                if (removed != null) unindex(oldKey, removed);
            }
        }
        if (previous != null) unindex(key, previous);
        endpoints.put(key, endpoint);
        index(key, endpoint);
        setDirty();
    }

    public synchronized void unregister(ResourceKey<Level> dimension, BlockPos pos) {
        EndpointKey key = new EndpointKey(dimension, pos);
        Endpoint removed = endpoints.remove(key);
        if (removed != null) {
            unindex(key, removed);
            setDirty();
        }
    }

    public synchronized List<Endpoint> boundTo(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return List.of();
        }
        Set<EndpointKey> keys = boundKeysByFactory.get(factoryId);
        if (keys == null || keys.isEmpty()) return List.of();
        List<Endpoint> matches = new ArrayList<>(keys.size());
        for (EndpointKey key : keys) {
            Endpoint endpoint = endpoints.get(key);
            if (endpoint != null) matches.add(endpoint);
        }
        matches.sort(PLACEMENT_ORDER);
        return List.copyOf(matches);
    }

    public synchronized Endpoint findByPassageId(String passageId) {
        if (passageId == null || passageId.isBlank()) return null;
        EndpointKey key = keysByPassageId.get(passageId);
        return key == null ? null : endpoints.get(key);
    }

    public synchronized List<Endpoint> unboundInPocket(BlockPos min, BlockPos max) {
        if (min == null || max == null || min.getX() > max.getX() || min.getY() > max.getY()
                || min.getZ() > max.getZ()) {
            return List.of();
        }
        List<Endpoint> matches = new ArrayList<>();
        int minChunkX = min.getX() >> 4;
        int maxChunkX = max.getX() >> 4;
        int minChunkZ = min.getZ() >> 4;
        int maxChunkZ = max.getZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (EndpointKey key : unboundPocketKeysByChunk.getOrDefault(
                        ChunkPos.asLong(chunkX, chunkZ), Set.of())) {
                    Endpoint endpoint = endpoints.get(key);
                    if (endpoint != null && contains(min, max, endpoint.pos())) matches.add(endpoint);
                }
            }
        }
        matches.sort(PLACEMENT_ORDER);
        return List.copyOf(matches);
    }

    private long allocatePlacementSequence() {
        long allocated = nextPlacementSequence;
        if (nextPlacementSequence < Long.MAX_VALUE) nextPlacementSequence++;
        return allocated;
    }

    private static boolean contains(BlockPos min, BlockPos max, BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    private void index(EndpointKey key, Endpoint endpoint) {
        if (!endpoint.passageId().isBlank()) keysByPassageId.put(endpoint.passageId(), key);
        if (endpoint.isBound()) {
            boundKeysByFactory.computeIfAbsent(endpoint.targetFactoryId(), ignored -> new LinkedHashSet<>())
                    .add(key);
        } else if (endpoint.dimension().equals(POCKET_DIMENSION)) {
            unboundPocketKeysByChunk.computeIfAbsent(ChunkPos.asLong(endpoint.pos()),
                    ignored -> new LinkedHashSet<>()).add(key);
        }
    }

    private void unindex(EndpointKey key, Endpoint endpoint) {
        if (!endpoint.passageId().isBlank()) keysByPassageId.remove(endpoint.passageId(), key);
        if (endpoint.isBound()) {
            removeKey(boundKeysByFactory, endpoint.targetFactoryId(), key);
        } else if (endpoint.dimension().equals(POCKET_DIMENSION)) {
            removeKey(unboundPocketKeysByChunk, ChunkPos.asLong(endpoint.pos()), key);
        }
    }

    private static <K> void removeKey(Map<K, Set<EndpointKey>> index, K indexKey, EndpointKey key) {
        Set<EndpointKey> keys = index.get(indexKey);
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) index.remove(indexKey);
    }

    public static FactoryPassageSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        FactoryPassageSavedData data = new FactoryPassageSavedData();
        int format = tag.getInt("Format");
        if (format < 1 || format > DATA_FORMAT) {
            return data;
        }
        List<CompoundTag> entries = new ArrayList<>();
        for (Tag raw : tag.getList("Endpoints", Tag.TAG_COMPOUND)) {
            entries.add((CompoundTag) raw);
        }
        if (format == 1) {
            entries.sort(Comparator.comparing((CompoundTag entry) -> entry.getString("Dimension"))
                    .thenComparingLong(entry -> entry.getLong("Pos")));
        }
        for (CompoundTag entry : entries) {
            ResourceLocation dimensionId = ResourceLocation.tryParse(entry.getString("Dimension"));
            if (dimensionId == null || !entry.contains("Pos")) {
                continue;
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            BlockPos pos = BlockPos.of(entry.getLong("Pos"));
            long sequence = format >= 2 && entry.contains("PlacementSequence")
                    ? Math.max(1L, entry.getLong("PlacementSequence"))
                    : data.allocatePlacementSequence();
            Endpoint endpoint = new Endpoint(dimension, pos, format >= 3 ? entry.getString("PassageId") : "",
                    entry.getString("TargetFactoryId"),
                    format >= 3 ? entry.getString("TargetRootFactoryId") : "", sequence);
            EndpointKey key = new EndpointKey(dimension, pos);
            Endpoint previous = data.endpoints.put(key, endpoint);
            if (previous != null) data.unindex(key, previous);
            data.index(key, endpoint);
            if (sequence >= data.nextPlacementSequence && sequence < Long.MAX_VALUE) {
                data.nextPlacementSequence = sequence + 1L;
            }
        }
        if (format >= 2 && tag.contains("NextPlacementSequence")) {
            data.nextPlacementSequence = Math.max(data.nextPlacementSequence,
                    Math.max(1L, tag.getLong("NextPlacementSequence")));
        }
        return data;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Format", DATA_FORMAT);
        tag.putLong("NextPlacementSequence", nextPlacementSequence);
        ListTag list = new ListTag();
        endpoints.values().stream()
                .sorted((left, right) -> {
                    int dimension = left.dimension().location().toString()
                            .compareTo(right.dimension().location().toString());
                    return dimension != 0 ? dimension : Long.compare(left.pos().asLong(), right.pos().asLong());
                })
                .forEach(endpoint -> {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("Dimension", endpoint.dimension().location().toString());
                    entry.putLong("Pos", endpoint.pos().asLong());
                    entry.putLong("PlacementSequence", endpoint.placementSequence());
                    if (!endpoint.passageId().isBlank()) entry.putString("PassageId", endpoint.passageId());
                    if (endpoint.isBound()) {
                        entry.putString("TargetFactoryId", endpoint.targetFactoryId());
                        if (!endpoint.targetRootFactoryId().isBlank()) {
                            entry.putString("TargetRootFactoryId", endpoint.targetRootFactoryId());
                        }
                    }
                    list.add(entry);
                });
        tag.put("Endpoints", list);
        return tag;
    }
}

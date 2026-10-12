package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.OperationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Query snapshots survive chunk unloading and factory-to-item transfers. */
public final class FactoryDirectorySavedData extends SavedData {
    private static final String DATA_NAME = "create_nested_factory_directory";

    public record Entry(String factoryId, String parentFactoryId, boolean nested, boolean hasRoom,
                        OperationMode mode, String customName, String dimension, BlockPos pos) {
        public Entry {
            parentFactoryId = parentFactoryId == null ? "" : parentFactoryId;
            customName = customName == null ? "" : customName;
            dimension = dimension == null ? "" : dimension;
            pos = pos == null ? null : pos.immutable();
        }

        public boolean isItem() {
            return pos == null;
        }
    }

    public record Descendant(Entry entry, int depth) {}

    /** Keeps command deletion recoverable until the room task reports actual completion. */
    public record Deletion(Entry entry, CompoundTag bindingData, String requesterId) {
        public Deletion {
            bindingData = bindingData.copy();
            requesterId = requesterId == null ? "" : requesterId;
        }

        @Override
        public CompoundTag bindingData() { return bindingData.copy(); }
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, CompoundTag> bindingSnapshots = new HashMap<>();
    private final Map<String, Deletion> deletions = new HashMap<>();
    private final Set<String> retiredIds = new HashSet<>();

    public static FactoryDirectorySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FactoryDirectorySavedData::new, FactoryDirectorySavedData::load), DATA_NAME);
    }

    public synchronized void observe(Entry entry) {
        if (entry.factoryId() == null || entry.factoryId().isBlank() || isUnavailable(entry.factoryId())) return;
        if (!entry.equals(entries.put(entry.factoryId(), entry))) setDirty();
    }

    public synchronized boolean isUnavailable(String factoryId) {
        return retiredIds.contains(factoryId) || deletions.containsKey(factoryId);
    }

    public synchronized boolean isDeleting(String factoryId) { return deletions.containsKey(factoryId); }

    public synchronized boolean isRetired(String factoryId) { return retiredIds.contains(factoryId); }

    public synchronized void observeBindingData(String factoryId, CompoundTag data) {
        if (!entries.containsKey(factoryId) || isUnavailable(factoryId)
                || !factoryId.equals(data.getString("FactoryId"))) return;
        if (!data.equals(bindingSnapshots.put(factoryId, data.copy()))) setDirty();
    }

    public synchronized CompoundTag bindingData(String factoryId) {
        CompoundTag data = bindingSnapshots.get(factoryId);
        return data == null ? null : data.copy();
    }

    public synchronized void beginDeletion(Entry entry, CompoundTag data, String requesterId) {
        if (isUnavailable(entry.factoryId())) throw new IllegalStateException("Factory is unavailable");
        deletions.put(entry.factoryId(), new Deletion(entry, data, requesterId));
        setDirty();
    }

    public synchronized void cancelDeletion(String factoryId) {
        if (deletions.remove(factoryId) != null) setDirty();
    }

    public synchronized List<Deletion> pendingDeletions() { return List.copyOf(deletions.values()); }

    public synchronized Deletion completeDeletion(String factoryId) {
        Deletion deletion = deletions.remove(factoryId);
        if (deletion == null) return null;
        entries.remove(factoryId);
        bindingSnapshots.remove(factoryId);
        retiredIds.add(factoryId);
        setDirty();
        return deletion;
    }

    public synchronized Entry find(String factoryId) {
        return entries.get(factoryId);
    }

    public synchronized List<String> factoryIds() {
        return entries.keySet().stream().filter(id -> !isUnavailable(id)).sorted().toList();
    }

    public synchronized List<Entry> rooms(boolean nested) {
        return entries.values().stream().filter(entry -> !isUnavailable(entry.factoryId())
                        && entry.hasRoom() && entry.nested() == nested)
                .sorted(Comparator.comparing(Entry::factoryId)).toList();
    }

    /** Traverses all levels, including through roomless terminals, without revisiting corrupt cycles. */
    public synchronized List<Descendant> descendants(String factoryId) {
        Map<String, List<Entry>> children = new HashMap<>();
        for (Entry entry : entries.values()) {
            children.computeIfAbsent(entry.parentFactoryId(), ignored -> new ArrayList<>()).add(entry);
        }
        children.values().forEach(list -> list.sort(Comparator.comparing(Entry::factoryId)));
        Set<String> visited = new HashSet<>();
        visited.add(factoryId);
        ArrayDeque<Descendant> pending = new ArrayDeque<>();
        for (Entry child : children.getOrDefault(factoryId, List.of())) {
            pending.addLast(new Descendant(child, 1));
        }
        List<Descendant> result = new ArrayList<>();
        while (!pending.isEmpty()) {
            Descendant next = pending.removeFirst();
            if (!visited.add(next.entry().factoryId())) continue;
            result.add(next);
            for (Entry child : children.getOrDefault(next.entry().factoryId(), List.of())) {
                pending.addLast(new Descendant(child, next.depth() + 1));
            }
        }
        return List.copyOf(result);
    }

    public synchronized void forget(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) return;
        // Pending commands still need their saved binding until the completion callback.
        if (isDeleting(factoryId)) return;
        entries.remove(factoryId);
        bindingSnapshots.remove(factoryId);
        if (retiredIds.add(factoryId)) setDirty();
    }

    public static FactoryDirectorySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        FactoryDirectorySavedData data = new FactoryDirectorySavedData();
        for (Tag entryTag : tag.getList("Factories", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) entryTag;
            String id = entry.getString("FactoryId");
            if (id.isBlank()) continue;
            OperationMode mode;
            try {
                mode = OperationMode.valueOf(entry.getString("Mode"));
            } catch (IllegalArgumentException ignored) {
                mode = OperationMode.CHUNK_LOADED;
            }
            data.entries.put(id, new Entry(id, entry.getString("ParentFactoryId"), entry.getBoolean("Nested"),
                    entry.getBoolean("HasRoom"), mode, entry.getString("CustomName"), entry.getString("Dimension"),
                    entry.contains("Pos", Tag.TAG_LONG) ? BlockPos.of(entry.getLong("Pos")) : null));
            if (entry.contains("BindingData", Tag.TAG_COMPOUND)) {
                data.bindingSnapshots.put(id, entry.getCompound("BindingData").copy());
            }
            if (entry.contains("Deletion", Tag.TAG_COMPOUND)) {
                CompoundTag deletion = entry.getCompound("Deletion");
                data.deletions.put(id, new Deletion(data.entries.get(id),
                        deletion.getCompound("BindingData"), deletion.getString("RequesterId")));
            }
        }
        for (Tag id : tag.getList("RetiredIds", Tag.TAG_STRING)) data.retiredIds.add(id.getAsString());
        return data;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag factories = new ListTag();
        for (String id : entries.keySet().stream().sorted().toList()) {
            Entry entry = entries.get(id);
            CompoundTag value = new CompoundTag();
            value.putString("FactoryId", id);
            value.putString("ParentFactoryId", entry.parentFactoryId());
            value.putBoolean("Nested", entry.nested());
            value.putBoolean("HasRoom", entry.hasRoom());
            value.putString("Mode", entry.mode().name());
            value.putString("CustomName", entry.customName());
            value.putString("Dimension", entry.dimension());
            if (entry.pos() != null) value.putLong("Pos", entry.pos().asLong());
            CompoundTag binding = bindingSnapshots.get(id);
            if (binding != null) value.put("BindingData", binding.copy());
            Deletion deletion = deletions.get(id);
            if (deletion != null) {
                CompoundTag pending = new CompoundTag();
                pending.put("BindingData", deletion.bindingData());
                pending.putString("RequesterId", deletion.requesterId());
                value.put("Deletion", pending);
            }
            factories.add(value);
        }
        tag.put("Factories", factories);
        ListTag retired = new ListTag();
        retiredIds.stream().sorted().forEach(id -> retired.add(net.minecraft.nbt.StringTag.valueOf(id)));
        tag.put("RetiredIds", retired);
        return tag;
    }
}

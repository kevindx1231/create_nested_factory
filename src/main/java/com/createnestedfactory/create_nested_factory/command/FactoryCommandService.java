package com.createnestedfactory.create_nested_factory.command;

import com.createnestedfactory.create_nested_factory.FactoryDirectorySavedData;
import com.createnestedfactory.create_nested_factory.FactoryDirectorySavedData.Entry;
import com.createnestedfactory.create_nested_factory.NestedFactorySaveData;
import com.createnestedfactory.create_nested_factory.PocketChunkForceManager;
import com.createnestedfactory.create_nested_factory.PocketFreezeSavedData;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.RoomMutationTaskManager;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.createnestedfactory.create_nested_factory.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.UUID;

/** Copy and deletion policies belong to commands; physical cleanup uses the existing room scheduler. */
public final class FactoryCommandService {
    public static final String COPY_KEY = "CnfBindingCopy";
    private static final String PREFIX = "command.create_nested_factory.";

    private FactoryCommandService() {}

    private static ResourceKey<Level> dimension(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
    }

    private static NestedFactoryBlockEntity resolve(MinecraftServer server, Entry entry) {
        if (entry.isItem()) return null;
        ResourceKey<Level> key = dimension(entry.dimension());
        ServerLevel level = key == null ? null : server.getLevel(key);
        if (level == null) return null;
        level.getChunkAt(entry.pos());
        return level.getBlockEntity(entry.pos()) instanceof NestedFactoryBlockEntity factory
                && entry.factoryId().equals(factory.getFactoryId()) ? factory : null;
    }

    /** A live binding is exported directly; portable bindings use the last authoritative move snapshot. */
    public static CompoundTag bindingData(MinecraftServer server, Entry entry) {
        FactoryDirectorySavedData directory = FactoryDirectorySavedData.get(server);
        if (directory.isUnavailable(entry.factoryId())) return null;
        CompoundTag data;
        if (entry.isItem()) {
            data = directory.bindingData(entry.factoryId());
        } else {
            NestedFactoryBlockEntity factory = resolve(server, entry);
            data = factory == null ? null : factory.exportCommandBindingData(server.registryAccess());
        }
        if (data == null || !entry.factoryId().equals(data.getString("FactoryId"))) return null;
        directory.observeBindingData(entry.factoryId(), data);
        return data;
    }

    public static ItemStack copyItem(CompoundTag bindingData) {
        CompoundTag data = bindingData.copy();
        data.putBoolean(NestedFactoryBlockEntity.PORTABLE_BINDING_KEY, true);
        data.putBoolean(COPY_KEY, true);
        ItemStack stack = new ItemStack(ModItems.NESTED_FACTORY.get());
        BlockItem.setBlockEntityData(stack, ModBlockEntities.NESTED_FACTORY.get(), data);
        if (!data.getString("CustomName").isBlank()) {
            stack.setHoverName( Component.literal(data.getString("CustomName")));
        }
        return stack;
    }

    /** Run before vanilla reads the item data, so an old copy cannot replay an obsolete runtime snapshot. */
    public static boolean preparePlacement(BlockPlaceContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) return true;
        ItemStack stack = context.getItemInHand();
        CompoundTag data = NestedFactoryBlockEntity.getFactoryItemData(stack);
        if (!data.getBoolean(NestedFactoryBlockEntity.PORTABLE_BINDING_KEY)) return true;
        FactoryDirectorySavedData directory = FactoryDirectorySavedData.get(level.getServer());
        String id = data.getString("FactoryId");
        NestedFactoryBlockEntity.observePortableItem(level.getServer(), stack);
        Component failure = placementFailure(directory, id, data.getBoolean(COPY_KEY));
        if (failure != null) {
            if (context.getPlayer() != null) context.getPlayer().displayClientMessage(failure, true);
            return false;
        }
        CompoundTag current = directory.bindingData(id);
        CompoundTag target = current == null ? data : current;
        BlockPos origin = roomOrigin(target);
        if (origin != null && RoomMutationTaskManager.get(level.getServer())
                .isRoomLocked(NestedFactoryBlock.POCKET_DIMENSION, origin)) {
            if (context.getPlayer() != null) context.getPlayer().displayClientMessage(
                    Component.translatable("message.create_nested_factory.room_mutation.active"), true);
            return false;
        }
        if (current != null) {
            current.putBoolean(COPY_KEY, data.getBoolean(COPY_KEY));
            BlockItem.setBlockEntityData(stack, ModBlockEntities.NESTED_FACTORY.get(), current);
            stack.resetHoverName();
            if (!current.getString("CustomName").isBlank()) {
                stack.setHoverName( Component.literal(current.getString("CustomName")));
            }
        }
        return true;
    }

    /** Block entity onLoad may be deferred; reserve the successful binding before another item can place. */
    public static void recordPlacement(BlockPlaceContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)
                || !(level.getBlockEntity(context.getClickedPos()) instanceof NestedFactoryBlockEntity factory)) return;
        factory.completePlacement();
        FactoryDirectorySavedData directory = FactoryDirectorySavedData.get(level.getServer());
        Entry entry = directory.find(factory.getFactoryId());
        if (entry == null || !entry.isItem()) return;
        directory.observe(new Entry(entry.factoryId(), entry.parentFactoryId(), entry.nested(), entry.hasRoom(),
                entry.mode(), entry.customName(), level.dimension().location().toString(), context.getClickedPos()));
    }

    static Component placementFailure(FactoryDirectorySavedData directory, String id, boolean copy) {
        if (directory.isUnavailable(id)) return Component.translatable(PREFIX + "binding_unavailable", id);
        Entry entry = directory.find(id);
        if (entry != null && !entry.isItem()) return Component.translatable(PREFIX + "binding_in_use", id);
        if (copy && (entry == null || directory.bindingData(id) == null)) {
            return Component.translatable(PREFIX + "binding_unavailable", id);
        }
        return null;
    }

    private static BlockPos roomOrigin(CompoundTag data) {
        String key = data.getBoolean("Nested") ? "NestedRoomOrigin" : "RootRoomOrigin";
        if (!data.contains(key, Tag.TAG_LONG)) return null;
        if (data.getBoolean("Nested") ? !data.contains("NestedSlotId") || data.getInt("NestedSlotId") < 0
                : !data.getBoolean("RootRoomAllocated")) {
            return null;
        }
        return BlockPos.of(data.getLong(key));
    }

    static int[] roomBounds(CompoundTag data) {
        BlockPos origin = roomOrigin(data);
        int[] chunks = data.getIntArray("Bounds");
        if (origin == null || chunks.length != 6) return null;
        for (int chunk : chunks) if (chunk < -1 || chunk > 1) return null;
        for (int axis = 0; axis < 3; axis++) if (chunks[axis * 2] > chunks[axis * 2 + 1]) return null;
        PocketBounds bounds = new PocketBounds();
        bounds.fromArray(chunks);
        return new int[]{bounds.minX(origin), bounds.minY(origin), bounds.minZ(origin),
                bounds.maxX(origin), bounds.maxY(origin), bounds.maxZ(origin)};
    }

    private static AABB area(int[] bounds) {
        return new AABB(bounds[0], bounds[1], bounds[2], bounds[3] + 1.0, bounds[4] + 1.0, bounds[5] + 1.0);
    }

    /** Inspect block entities by chunk, rather than walking every block in the room. */
    public static List<String> deletionBlockers(MinecraftServer server, String id, CompoundTag data) {
        TreeSet<String> children = new TreeSet<>();
        ServerLevel pocket = server.getLevel(NestedFactoryBlock.POCKET_DIMENSION);
        ArrayDeque<CompoundTag> pending = new ArrayDeque<>();
        HashSet<String> inspected = new HashSet<>();
        pending.add(data);
        while (!pending.isEmpty()) {
            CompoundTag current = pending.removeFirst();
            if (!inspected.add(current.getString("FactoryId"))) continue;
            String recordedChild = current.getString("ChildFactoryId");
            if (!recordedChild.isBlank()) {
                children.add(recordedChild);
                CompoundTag childData = FactoryDirectorySavedData.get(server).bindingData(recordedChild);
                if (childData != null) pending.add(childData);
                else if (pocket != null && current.contains("ChildFactoryPos", Tag.TAG_LONG)) {
                    BlockPos childPos = BlockPos.of(current.getLong("ChildFactoryPos"));
                    pocket.getChunkAt(childPos);
                    if (pocket.getBlockEntity(childPos) instanceof NestedFactoryBlockEntity child
                            && recordedChild.equals(child.getFactoryId())) {
                        pending.add(child.exportCommandBindingData(server.registryAccess()));
                    }
                }
            }
            int[] bounds = roomBounds(current);
            if (bounds == null || pocket == null) continue;
            AABB room = area(bounds);
            for (int x = Math.floorDiv(bounds[0], 16); x <= Math.floorDiv(bounds[3], 16); x++) {
                for (int z = Math.floorDiv(bounds[2], 16); z <= Math.floorDiv(bounds[5], 16); z++) {
                    for (var factory : pocket.getChunk(x, z).getBlockEntities().values()) {
                        if (factory instanceof NestedFactoryBlockEntity nested
                                && room.contains(net.minecraft.world.phys.Vec3.atCenterOf(nested.getBlockPos()))) {
                            children.add(nested.getFactoryId());
                            pending.add(nested.exportCommandBindingData(server.registryAccess()));
                        }
                    }
                }
            }
        }
        children.add(id);
        // Also follow saved parent links: portable children and unloaded descendants remain blockers.
        for (String parent : List.copyOf(children)) {
            FactoryDirectorySavedData.get(server).descendants(parent)
                    .forEach(child -> children.add(child.entry().factoryId()));
            children.addAll(NestedFactorySaveData.get(server).descendantFactoryIds(parent));
        }
        children.remove(id);
        children.removeIf(child -> FactoryDirectorySavedData.get(server).isRetired(child));
        return List.copyOf(children);
    }

    public static Component requestDeletion(MinecraftServer server, Entry entry, CompoundTag data, String requesterId) {
        int[] bounds = roomBounds(data);
        ServerLevel pocket = server.getLevel(NestedFactoryBlock.POCKET_DIMENSION);
        if (!entry.hasRoom() || bounds == null || pocket == null) return Component.translatable(PREFIX + "data_missing", entry.factoryId());
        RoomMutationTaskManager tasks = RoomMutationTaskManager.get(server);
        if (tasks.isRoomLocked(NestedFactoryBlock.POCKET_DIMENSION, roomOrigin(data))) {
            return Component.translatable("message.create_nested_factory.room_mutation.active");
        }
        if (!pocket.getEntitiesOfClass(ServerPlayer.class, area(bounds), player -> true).isEmpty()) {
            return Component.translatable("message.create_nested_factory.factory.players_prevent_destroy");
        }
        NestedFactoryBlockEntity factory = entry.isItem() ? null : resolve(server, entry);
        if (!entry.isItem() && factory == null) return Component.translatable(PREFIX + "data_missing", entry.factoryId());
        FactoryDirectorySavedData directory = FactoryDirectorySavedData.get(server);
        directory.beginDeletion(entry, data, requesterId);
        Component failure = factory == null ? schedulePortableDeletion(server, entry, data)
                : factory.requestSpaceDestructionForCommand();
        if (failure != null) directory.cancelDeletion(entry.factoryId());
        return failure;
    }

    private static Component schedulePortableDeletion(MinecraftServer server, Entry entry, CompoundTag data) {
        int[] bounds = roomBounds(data);
        ServerLevel pocket = server.getLevel(NestedFactoryBlock.POCKET_DIMENSION);
        if (bounds == null || pocket == null) return Component.translatable(PREFIX + "data_missing", entry.factoryId());
        if (!pocket.getEntitiesOfClass(ServerPlayer.class, area(bounds), player -> true).isEmpty()) {
            return Component.translatable("message.create_nested_factory.factory.players_prevent_destroy");
        }
        ResourceKey<Level> bindingDimension = entry.isItem() ? Level.OVERWORLD : dimension(entry.dimension());
        if (bindingDimension == null) return Component.translatable(PREFIX + "data_missing", entry.factoryId());
        String rootId = data.getString("RootFactoryId");
        RoomMutationTaskManager.FactoryRef reference = new RoomMutationTaskManager.FactoryRef(
                bindingDimension, entry.isItem() ? BlockPos.ZERO : entry.pos(), entry.factoryId(),
                rootId.isBlank() ? entry.factoryId() : rootId);
        if (!RoomMutationTaskManager.get(server).scheduleDestroy(NestedFactoryBlock.POCKET_DIMENSION,
                roomOrigin(data), bounds, reference, true)) {
            return Component.translatable("message.create_nested_factory.room_mutation.active");
        }
        for (Entity entity : List.copyOf(pocket.getEntitiesOfClass(Entity.class, area(bounds),
                candidate -> !(candidate instanceof ServerPlayer)))) entity.discard();
        PocketRegistry.clearRoomRegistrations(server, roomOrigin(data));
        NestedFactorySaveData.get(server).releaseFreezeLease(entry.factoryId());
        return null;
    }

    /** Recover the command side of a deletion if a restart saved it before saving its room task. */
    public static void resumeDeletions(MinecraftServer server) {
        FactoryDirectorySavedData directory = FactoryDirectorySavedData.get(server);
        for (var deletion : directory.pendingDeletions()) {
            CompoundTag data = deletion.bindingData();
            BlockPos origin = roomOrigin(data);
            if (origin == null || RoomMutationTaskManager.get(server)
                    .isRoomLocked(NestedFactoryBlock.POCKET_DIMENSION, origin)) continue;
            NestedFactoryBlockEntity factory = resolve(server, deletion.entry());
            if (factory != null) factory.requestSpaceDestructionForCommand();
            else schedulePortableDeletion(server, deletion.entry(), data);
        }
    }

    /** Called only after the scheduler has cleared and synchronized the entire room. */
    public static void onRoomDeleted(MinecraftServer server, String id) {
        FactoryDirectorySavedData directory = FactoryDirectorySavedData.get(server);
        var deletion = directory.completeDeletion(id);
        if (deletion == null) return;
        CompoundTag data = deletion.bindingData();
        NestedFactorySaveData slots = NestedFactorySaveData.get(server);
        slots.releaseRootAllocation(id);
        slots.releaseFreezeLease(id);
        slots.forgetFactoryParent(id);
        boolean nested = data.getBoolean("Nested");
        slots.recycleCommandSlot(nested, data.getInt(nested ? "NestedSlotId" : "RootSlotId"), roomOrigin(data));
        PocketFreezeSavedData.get(server).removeDeletedFactoryRoom(id);
        PocketChunkForceManager.releaseAll(server, id + ":room");
        PocketChunkForceManager.releaseAll(server, id + ":external");
        PocketChunkForceManager.releaseAll(server, id + ":thaw");
        if (nested) {
            String parentId = data.getString("ParentFactoryId");
            Entry parentEntry = directory.find(parentId);
            NestedFactoryBlockEntity parent = parentEntry == null ? null : resolve(server, parentEntry);
            if (parent != null && id.equals(parent.getChildFactoryId())) parent.setChildFactory(null);
            CompoundTag parentData = directory.bindingData(parentId);
            if (parentData != null && id.equals(parentData.getString("ChildFactoryId"))) {
                parentData.putString("ChildFactoryId", "");
                parentData.remove("ChildFactoryPos");
                directory.observeBindingData(parentId, parentData);
            }
        }
        Component completed = Component.translatable(PREFIX + "delete_complete", id);
        server.sendSystemMessage(completed);
        if (!deletion.requesterId().isBlank()) {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(deletion.requesterId()));
                if (player != null) player.sendSystemMessage(completed);
            } catch (IllegalArgumentException ignored) {
                // Invalid requester metadata must not stop slot recycling.
            }
        }
    }
}

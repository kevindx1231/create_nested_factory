package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.FactoryPassageSavedData;
import com.createnestedfactory.create_nested_factory.FactoryPassageNavigation;
import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Persistent passage binding and server-authoritative travel resolution. */
public final class FactoryPassageBlockEntity extends SyncedBlockEntity {
    private String passageId = UUID.randomUUID().toString();
    private ResourceKey<Level> targetDimension;
    private BlockPos targetPos;
    private String targetFactoryId = "";
    private String targetRootFactoryId = "";
    private ItemStack marker = ItemStack.EMPTY;
    private long lastFailureMessageTick = Long.MIN_VALUE;
    private long lastTravelTick = Long.MIN_VALUE;
    private final java.util.Set<UUID> travellingPlayers = new java.util.HashSet<>();
    private boolean endpointResolutionPending;

    private record ResolvedEndpoint(FactoryPassageSavedData.Endpoint endpoint,
                                    FactoryPassageBlockEntity passage) {
    }

    public enum BindingResult {
        BOUND,
        UNBOUND
    }

    public FactoryPassageBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FACTORY_PASSAGE.get(), pos, state);
    }

    public boolean isBound() {
        return targetDimension != null && targetPos != null && !targetFactoryId.isBlank();
    }

    public ResourceKey<Level> getTargetDimension() {
        return targetDimension;
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }

    public String getTargetFactoryId() {
        return targetFactoryId;
    }

    public String getTargetRootFactoryId() {
        return targetRootFactoryId;
    }

    public String getPassageId() {
        return passageId;
    }

    public ItemStack getMarker() {
        return marker;
    }

    public boolean setMarker(ItemStack stack) {
        ItemStack updated = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (ItemStack.matches(marker, updated)) return false;
        marker = updated;
        setChanged();
        if (level != null && !level.isClientSide()) {
            sendData();
        }
        return true;
    }

    public BindingResult setBinding(ModAttachments.FactoryReference reference) {
        if (reference == null || !reference.isComplete()) {
            targetDimension = null;
            targetPos = null;
            targetFactoryId = "";
            targetRootFactoryId = "";
        } else {
            targetDimension = reference.dimension();
            targetPos = reference.pos().immutable();
            targetFactoryId = reference.factoryId();
            targetRootFactoryId = reference.rootFactoryId();
        }
        setChanged();
        registerEndpoint();
        if (level != null && !level.isClientSide()) {
            sendData();
        }
        return isBound() ? BindingResult.BOUND : BindingResult.UNBOUND;
    }

    public void describeBinding(ServerPlayer player) {
        if (!isBound()) {
            PlayerMessagePayload.sendTo(player,
                    Component.translatable("message.create_nested_factory.passage.unbound")
                            .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        NestedFactoryBlockEntity factory = resolveTargetFactory();
        Component name = factory == null ? Component.literal(targetFactoryId) : factory.getDisplayName();
        PlayerMessagePayload.sendTo(player,
                Component.translatable("message.create_nested_factory.passage.bound_target", name)
                        .withStyle(ChatFormatting.AQUA), false);
    }

    public void tryTravel(ServerPlayer player) {
        if (level == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        long tick = level.getGameTime();
        if (lastTravelTick != tick) {
            travellingPlayers.clear();
            lastTravelTick = tick;
        }
        if (!travellingPlayers.add(player.getUUID())) return;
        endpointResolutionPending = false;
        if (isBound()) {
            travelIntoFactory(player);
        } else if (serverLevel.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            travelOutOfFactory(player, serverLevel);
        } else {
            notifyFailure(player, "message.create_nested_factory.passage.unbound");
        }
    }

    private void travelIntoFactory(ServerPlayer player) {
        NestedFactoryBlockEntity target = resolveTargetFactory();
        if (target == null) {
            notifyFailure(player, endpointResolutionPending ? "message.create_nested_factory.passage.loading"
                    : "message.create_nested_factory.passage.target_missing");
            return;
        }
        ResolvedEndpoint primaryInner = resolvePrimaryInner(target);
        if (endpointResolutionPending) {
            notifyFailure(player, "message.create_nested_factory.passage.loading");
            return;
        }
        FactoryPassagePairing.IntoStatus pairing = FactoryPassagePairing.classifyInto(
                isBound() && targetFactoryId.equals(target.getFactoryId()), primaryInner != null);
        if (pairing == FactoryPassagePairing.IntoStatus.UNPAIRED) {
            notifyFailure(player, "message.create_nested_factory.passage.unpaired");
            return;
        }
        Vec3 destination = null;
        float yRot = player.getYRot();
        ModAttachments.PassageTravelGuard previousGuard = ModAttachments.get(player, ModAttachments.PASSAGE_TRAVEL_GUARD);
        if (pairing == FactoryPassagePairing.IntoStatus.PRIMARY_INNER) {
            FactoryPassageSavedData.Endpoint endpoint = primaryInner.endpoint();
            var arrival = FactoryPassageNavigation.arrival(player, endpoint);
            if (arrival.status() != FactoryPassageNavigation.Status.READY) {
                notifyFailure(player, "message.create_nested_factory.passage.loading");
                return;
            }
            destination = arrival.destination().position();
            yRot = arrival.destination().yRot();
            FactoryPassageNavigation.guard(player, primaryInner.passage());
        }
        boolean travelled = NestedFactoryBlock.enterFactoryViaPassage(player, target, destination, yRot, passageId);
        if (!travelled) {
            ModAttachments.set(player, ModAttachments.PASSAGE_TRAVEL_GUARD, previousGuard);
        }
    }

    private void travelOutOfFactory(ServerPlayer player, ServerLevel pocket) {
        NestedFactoryBlockEntity owner = NestedFactoryBlock.findFactoryAt(pocket, worldPosition);
        if (owner == null) {
            notifyFailure(player, "message.create_nested_factory.passage.unpaired");
            return;
        }
        if (owner.getOperationMode() != OperationMode.CHUNK_LOADED) {
            notifyFailure(player, "message.create_nested_factory.passage.chunk_loaded_required");
            return;
        }

        ResolvedEndpoint primaryInner = resolvePrimaryInner(owner);
        if (endpointResolutionPending) {
            notifyFailure(player, "message.create_nested_factory.passage.loading");
            return;
        }
        if (primaryInner == null || !primaryInner.endpoint().pos().equals(worldPosition)) {
            notifyFailure(player, "message.create_nested_factory.passage.inactive_inner");
            return;
        }

        ModAttachments.FactorySession session = ModAttachments.get(player, ModAttachments.FACTORY_SESSION);
        ModAttachments.ReturnFrame frame = session.stack().isEmpty()
                ? null : session.stack().get(session.stack().size() - 1);
        if (frame != null && frame.passage() && owner.getFactoryId().equals(frame.targetFactoryId())) {
            NestedFactoryBlock.exitCurrentFactoryViaPassage(player, null, null, player.getYRot());
            return;
        }

        ResolvedEndpoint fallbackOuter = resolveFirstBoundOuter(owner.getFactoryId());
        if (fallbackOuter == null) {
            if (endpointResolutionPending) {
                notifyFailure(player, "message.create_nested_factory.passage.loading");
                return;
            }
            NestedFactoryBlock.exitCurrentFactoryViaPassage(player, null, null, player.getYRot());
            return;
        }
        FactoryPassageSavedData.Endpoint endpoint = fallbackOuter.endpoint();
        NestedFactoryBlock.exitCurrentFactoryViaPassage(player, endpoint);
    }

    private ResolvedEndpoint resolveFirstBoundOuter(String factoryId) {
        MinecraftServer server = level.getServer();
        if (server == null) return null;
        for (FactoryPassageSavedData.Endpoint endpoint : FactoryPassageSavedData.get(server).boundTo(factoryId)) {
            var resolution = FactoryPassageNavigation.resolve(server, endpoint);
            if (resolution.status() == FactoryPassageNavigation.Status.RETRY_LATER) {
                endpointResolutionPending = true;
                return null;
            }
            FactoryPassageBlockEntity passage = resolution.passage();
            if (passage != null && factoryId.equals(passage.targetFactoryId)) {
                return new ResolvedEndpoint(endpoint, passage);
            }
        }
        return null;
    }

    private ResolvedEndpoint resolvePrimaryInner(NestedFactoryBlockEntity factory) {
        MinecraftServer server = level.getServer();
        if (server == null) return null;
        BlockPos origin = factory.roomOrigin();
        BlockPos min = new BlockPos(factory.getBounds().minX(origin), factory.getBounds().minY(origin),
                factory.getBounds().minZ(origin));
        BlockPos max = new BlockPos(factory.getBounds().maxX(origin), factory.getBounds().maxY(origin),
                factory.getBounds().maxZ(origin));
        for (FactoryPassageSavedData.Endpoint endpoint : FactoryPassageSavedData.get(server)
                .unboundInPocket(min, max)) {
            var resolution = FactoryPassageNavigation.resolve(server, endpoint);
            if (resolution.status() == FactoryPassageNavigation.Status.RETRY_LATER) {
                endpointResolutionPending = true;
                return null;
            }
            FactoryPassageBlockEntity passage = resolution.passage();
            if (passage == null || passage.isBound()) continue;
            ServerLevel pocket = server.getLevel(NestedFactoryBlock.POCKET_DIMENSION);
            if (pocket != null && NestedFactoryBlock.findFactoryAt(pocket, endpoint.pos()) == factory) {
                return new ResolvedEndpoint(endpoint, passage);
            }
        }
        return null;
    }

    private NestedFactoryBlockEntity resolveTargetFactory() {
        if (!isBound() || level == null || level.getServer() == null) {
            return null;
        }
        ModAttachments.FactoryReference reference = new ModAttachments.FactoryReference(
                targetDimension, targetPos, targetFactoryId, targetRootFactoryId);
        NestedFactoryBlockEntity factory = resolveFactoryReference(reference);
        if (factory == null) return null;
        ResourceKey<Level> dimension = factory.getLevel().dimension();
        BlockPos pos = factory.getBlockPos();
        if (!dimension.equals(targetDimension) || !pos.equals(targetPos)
                || !factory.getRootFactoryId().equals(targetRootFactoryId)) {
            targetDimension = dimension;
            targetPos = pos.immutable();
            targetRootFactoryId = factory.getRootFactoryId();
            setChanged();
            registerEndpoint();
            sendData();
        }
        return factory;
    }

    private NestedFactoryBlockEntity resolveFactoryReference(ModAttachments.FactoryReference reference) {
        if (reference == null || !reference.isComplete() || level == null || level.getServer() == null) return null;
        var resolved = FactoryPassageNavigation.resolveFactory(level.getServer(), reference);
        endpointResolutionPending |= resolved.status() == FactoryPassageNavigation.Status.RETRY_LATER;
        return resolved.factory();
    }

    private void notifyFailure(ServerPlayer player, String key) {
        long now = player.serverLevel().getGameTime();
        if (lastFailureMessageTick != Long.MIN_VALUE && now - lastFailureMessageTick < 40) {
            return;
        }
        lastFailureMessageTick = now;
        PlayerMessagePayload.sendTo(player, Component.translatable(key).withStyle(ChatFormatting.RED), false);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        registerEndpoint();
    }

    public void onDestroyed() {
        if (level != null && !level.isClientSide() && level.getServer() != null) {
            FactoryPassageSavedData.get(level.getServer()).unregister(level.dimension(), worldPosition, passageId);
        }
    }

    public void registerEndpoint() {
        if (level != null && !level.isClientSide() && level.getServer() != null) {
            FactoryPassageSavedData.get(level.getServer())
                    .register(level.dimension(), worldPosition, passageId, targetFactoryId, targetRootFactoryId);
        }
    }

    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(getBlockPos()).expandTowards(0, 1, 0).inflate(.125);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        super.saveAdditional(tag);
        tag.putString("PassageId", passageId);
        if (isBound()) {
            tag.putString("TargetDimension", targetDimension.location().toString());
            tag.putLong("TargetPos", targetPos.asLong());
            tag.putString("TargetFactoryId", targetFactoryId);
            tag.putString("TargetRootFactoryId", targetRootFactoryId);
        }
        if (!marker.isEmpty()) {
            tag.put("Marker", marker.save(new CompoundTag()));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        super.load(tag);
        if (tag.contains("PassageId") && !tag.getString("PassageId").isBlank()) {
            passageId = tag.getString("PassageId");
        }
        ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString("TargetDimension"));
        targetDimension = dimensionId == null ? null : ResourceKey.create(Registries.DIMENSION, dimensionId);
        targetPos = tag.contains("TargetPos") ? BlockPos.of(tag.getLong("TargetPos")) : null;
        targetFactoryId = tag.getString("TargetFactoryId");
        targetRootFactoryId = tag.getString("TargetRootFactoryId");
        marker = ItemStack.of(tag.getCompound("Marker"));
    }
}

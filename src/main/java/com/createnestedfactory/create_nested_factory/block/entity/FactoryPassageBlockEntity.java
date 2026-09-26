package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.FactoryPassageSavedData;
import com.createnestedfactory.create_nested_factory.FactoryPassageAvailability;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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

    private record ResolvedEndpoint(FactoryPassageSavedData.Endpoint endpoint,
                                    FactoryPassageBlockEntity passage) {
    }

    public enum BindingResult {
        BOUND,
        UNBOUND,
        PHYSICALIZED_PASSAGE,
        PHYSICALIZED_TARGET
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

    public void setMarker(ItemStack stack) {
        marker = stack.copyWithCount(1);
        setChanged();
        if (level != null && !level.isClientSide()) {
            sendData();
        }
    }

    public BindingResult setBinding(ModAttachments.FactoryReference reference) {
        if (FactoryPassageAvailability.isPhysicalized(this)) {
            return BindingResult.PHYSICALIZED_PASSAGE;
        }
        if (reference != null && reference.isComplete()) {
            NestedFactoryBlockEntity target = resolveFactoryReference(reference);
            if (target != null && FactoryPassageAvailability.isPhysicalized(target)) {
                return BindingResult.PHYSICALIZED_TARGET;
            }
        }
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
        if (FactoryPassageAvailability.isPhysicalized(this)) {
            notifyFailure(player, "message.create_nested_factory.passage.physicalized_passage");
            return;
        }
        if (!isBound()) {
            PlayerMessagePayload.sendTo(player,
                    Component.translatable("message.create_nested_factory.passage.unbound")
                            .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        NestedFactoryBlockEntity factory = resolveTargetFactory();
        if (factory != null && FactoryPassageAvailability.isPhysicalized(factory)) {
            notifyFailure(player, "message.create_nested_factory.passage.physicalized_target");
            return;
        }
        Component name = factory == null ? Component.literal(targetFactoryId) : factory.getDisplayName();
        PlayerMessagePayload.sendTo(player,
                Component.translatable("message.create_nested_factory.passage.bound_target", name)
                        .withStyle(ChatFormatting.AQUA), false);
    }

    public void tryTravel(ServerPlayer player) {
        if (level == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (FactoryPassageAvailability.isPhysicalized(this)) {
            notifyFailure(player, "message.create_nested_factory.passage.physicalized_passage");
            return;
        }
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
            notifyFailure(player, "message.create_nested_factory.passage.target_missing");
            return;
        }
        if (FactoryPassageAvailability.isPhysicalized(target)) {
            notifyFailure(player, "message.create_nested_factory.passage.physicalized_target");
            return;
        }

        ResolvedEndpoint primaryInner = resolvePrimaryInner(target);
        FactoryPassagePairing.IntoStatus pairing = FactoryPassagePairing.classifyInto(
                isBound() && targetFactoryId.equals(target.getFactoryId()), primaryInner != null);
        if (pairing == FactoryPassagePairing.IntoStatus.UNPAIRED) {
            notifyFailure(player, "message.create_nested_factory.passage.unpaired");
            return;
        }
        Vec3 destination = null;
        float yRot = player.getYRot();
        if (pairing == FactoryPassagePairing.IntoStatus.PRIMARY_INNER) {
            FactoryPassageSavedData.Endpoint endpoint = primaryInner.endpoint();
            destination = arrivalPosition(endpoint.pos());
            yRot = primaryInner.passage().getBlockState().getValue(FactoryPassageBlock.FACING).toYRot();
            player.setData(ModAttachments.PASSAGE_TRAVEL_GUARD,
                    ModAttachments.PassageTravelGuard.arrival(primaryInner.passage().getPassageId(),
                            endpoint.dimension(), endpoint.pos()));
        }
        boolean travelled = NestedFactoryBlock.enterFactoryViaPassage(player, target, destination, yRot, passageId);
        if (!travelled) {
            player.setData(ModAttachments.PASSAGE_TRAVEL_GUARD, ModAttachments.PassageTravelGuard.empty());
        }
    }

    private void travelOutOfFactory(ServerPlayer player, ServerLevel pocket) {
        NestedFactoryBlockEntity owner = NestedFactoryBlock.findFactoryAt(pocket, worldPosition);
        if (owner == null) {
            notifyFailure(player, "message.create_nested_factory.passage.unpaired");
            return;
        }
        if (FactoryPassageAvailability.isPhysicalized(owner)) {
            notifyFailure(player, "message.create_nested_factory.passage.physicalized_owner");
            return;
        }
        if (owner.getOperationMode() != OperationMode.CHUNK_LOADED) {
            notifyFailure(player, "message.create_nested_factory.passage.chunk_loaded_required");
            return;
        }

        ResolvedEndpoint primaryInner = resolvePrimaryInner(owner);
        if (primaryInner == null || !primaryInner.endpoint().pos().equals(worldPosition)) {
            notifyFailure(player, "message.create_nested_factory.passage.inactive_inner");
            return;
        }

        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        ModAttachments.ReturnFrame frame = session.stack().isEmpty()
                ? null : session.stack().get(session.stack().size() - 1);
        if (frame != null && frame.passage() && owner.getFactoryId().equals(frame.targetFactoryId())) {
            NestedFactoryBlock.exitCurrentFactoryViaPassage(player, null, null, player.getYRot());
            return;
        }

        ResolvedEndpoint fallbackOuter = resolveFirstBoundOuter(owner.getFactoryId());
        if (fallbackOuter == null) {
            NestedFactoryBlock.exitCurrentFactoryViaPassage(player, null, null, player.getYRot());
            return;
        }
        FactoryPassageSavedData.Endpoint endpoint = fallbackOuter.endpoint();
        Vec3 destination = arrivalPosition(endpoint.pos());
        player.setData(ModAttachments.PASSAGE_TRAVEL_GUARD,
                ModAttachments.PassageTravelGuard.arrival(fallbackOuter.passage().getPassageId(),
                        endpoint.dimension(), endpoint.pos()));
        float yRot = fallbackOuter.passage().getBlockState().getValue(FactoryPassageBlock.FACING).toYRot();
        boolean travelled = NestedFactoryBlock.exitCurrentFactoryViaPassage(player, endpoint.dimension(),
                destination, yRot);
        if (!travelled) {
            player.setData(ModAttachments.PASSAGE_TRAVEL_GUARD, ModAttachments.PassageTravelGuard.empty());
        }
    }

    private ResolvedEndpoint resolveFirstBoundOuter(String factoryId) {
        MinecraftServer server = level.getServer();
        if (server == null) return null;
        for (FactoryPassageSavedData.Endpoint endpoint : FactoryPassageSavedData.get(server).boundTo(factoryId)) {
            if (!isIndexedEndpointValid(server, endpoint)) continue;
            FactoryPassageBlockEntity passage = resolveEndpoint(server, endpoint);
            if (passage != null && !FactoryPassageAvailability.isPhysicalized(passage)
                    && factoryId.equals(passage.targetFactoryId)) {
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
            if (!isIndexedEndpointValid(server, endpoint)) continue;
            FactoryPassageBlockEntity passage = resolveEndpoint(server, endpoint);
            if (passage == null || passage.isBound()
                    || FactoryPassageAvailability.isPhysicalized(passage)) continue;
            ServerLevel pocket = server.getLevel(NestedFactoryBlock.POCKET_DIMENSION);
            if (pocket != null && NestedFactoryBlock.findFactoryAt(pocket, endpoint.pos()) == factory) {
                return new ResolvedEndpoint(endpoint, passage);
            }
        }
        return null;
    }

    /** Validates loaded endpoints without loading every indexed candidate. */
    private boolean isIndexedEndpointValid(MinecraftServer server, FactoryPassageSavedData.Endpoint endpoint) {
        ServerLevel endpointLevel = server.getLevel(endpoint.dimension());
        if (endpointLevel == null) {
            return false;
        }
        if (!endpointLevel.hasChunkAt(endpoint.pos())) {
            return true;
        }
        BlockState state = endpointLevel.getBlockState(endpoint.pos());
        if (!state.is(ModBlocks.FACTORY_PASSAGE.get())
                || state.getValue(FactoryPassageBlock.HALF) != DoubleBlockHalf.LOWER
                || !(endpointLevel.getBlockEntity(endpoint.pos()) instanceof FactoryPassageBlockEntity passage)) {
            FactoryPassageSavedData.get(server).unregister(endpoint.dimension(), endpoint.pos());
            return false;
        }
        if (!endpoint.targetFactoryId().equals(passage.targetFactoryId)) {
            passage.registerEndpoint();
            return false;
        }
        return !FactoryPassageAvailability.isPhysicalized(passage);
    }

    private FactoryPassageBlockEntity resolveEndpoint(MinecraftServer server,
                                                       FactoryPassageSavedData.Endpoint endpoint) {
        ServerLevel endpointLevel = server.getLevel(endpoint.dimension());
        if (endpointLevel == null) {
            return null;
        }
        endpointLevel.getChunkAt(endpoint.pos());
        BlockState state = endpointLevel.getBlockState(endpoint.pos());
        if (!state.is(ModBlocks.FACTORY_PASSAGE.get())
                || state.getValue(FactoryPassageBlock.HALF) != DoubleBlockHalf.LOWER
                || !(endpointLevel.getBlockEntity(endpoint.pos()) instanceof FactoryPassageBlockEntity passage)) {
            FactoryPassageSavedData.get(server).unregister(endpoint.dimension(), endpoint.pos());
            return null;
        }
        if (!endpoint.targetFactoryId().equals(passage.targetFactoryId)) {
            passage.registerEndpoint();
            return null;
        }
        return FactoryPassageAvailability.isPhysicalized(passage) ? null : passage;
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
        MinecraftServer server = level.getServer();
        PocketRegistry.FactoryLocation current = PocketRegistry.findFactoryLocationById(server, reference.factoryId());
        ResourceKey<Level> dimension = current == null ? reference.dimension() : current.dimension();
        BlockPos pos = current == null ? reference.pos() : current.pos();
        ServerLevel targetLevel = server.getLevel(dimension);
        if (targetLevel == null) return null;
        targetLevel.getChunkAt(pos);
        return targetLevel.getBlockEntity(pos) instanceof NestedFactoryBlockEntity factory
                && reference.factoryId().equals(factory.getFactoryId()) ? factory : null;
    }

    private static Vec3 arrivalPosition(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.125, pos.getZ() + 0.5);
    }

    private void notifyFailure(ServerPlayer player, String key) {
        long now = player.serverLevel().getGameTime();
        if (now - lastFailureMessageTick < 40) {
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
            FactoryPassageSavedData.get(level.getServer()).unregister(level.dimension(), worldPosition);
        }
    }

    private void registerEndpoint() {
        if (level != null && !level.isClientSide() && level.getServer() != null) {
            FactoryPassageSavedData.get(level.getServer())
                    .register(level.dimension(), worldPosition, passageId, targetFactoryId, targetRootFactoryId);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("PassageId", passageId);
        if (isBound()) {
            tag.putString("TargetDimension", targetDimension.location().toString());
            tag.putLong("TargetPos", targetPos.asLong());
            tag.putString("TargetFactoryId", targetFactoryId);
            tag.putString("TargetRootFactoryId", targetRootFactoryId);
        }
        if (!marker.isEmpty()) {
            tag.put("Marker", marker.saveOptional(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("PassageId") && !tag.getString("PassageId").isBlank()) {
            passageId = tag.getString("PassageId");
        }
        ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString("TargetDimension"));
        targetDimension = dimensionId == null ? null : ResourceKey.create(Registries.DIMENSION, dimensionId);
        targetPos = tag.contains("TargetPos") ? BlockPos.of(tag.getLong("TargetPos")) : null;
        targetFactoryId = tag.getString("TargetFactoryId");
        targetRootFactoryId = tag.getString("TargetRootFactoryId");
        marker = ItemStack.parseOptional(registries, tag.getCompound("Marker"));
    }
}

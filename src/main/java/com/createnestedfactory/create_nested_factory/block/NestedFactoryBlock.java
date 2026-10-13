package com.createnestedfactory.create_nested_factory.block;

import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.Config;
import com.createnestedfactory.create_nested_factory.FactoryPassageNavigation;
import com.createnestedfactory.create_nested_factory.FactoryPassageSavedData;
import com.mojang.logging.LogUtils;
import com.createnestedfactory.create_nested_factory.NestedFactorySaveData;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
import com.simibubi.create.api.contraption.ContraptionMovementSetting;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;

public class NestedFactoryBlock extends HorizontalKineticBlock implements IBE<NestedFactoryBlockEntity>, ContraptionMovementSetting.MovementSettingProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceKey<Level> POCKET_DIMENSION = ResourceKey.create(Registries.DIMENSION,
            new ResourceLocation(Create_nested_factory.MODID, "nested_factory"));
    /** Persistent root slots live above all legacy root rooms, so lazy migration cannot claim an unloaded legacy room. */
    public static final int ROOT_AREA_Y = 128;
    public static final int LEGACY_ROOT_AREA_Y = 64;
    public static final int ROOT_GRID_SIZE = 64;
    public static final int ROOT_SLOT_GRID_WIDTH = 1024;
    public static final int NESTED_AREA_Y = 0;
    public static final int NESTED_SLOT_SIZE = 32;
    public static final int NESTED_ROOM_SIZE = 16;
    public static final int NESTED_SLOT_OFFSET = 8;

    public NestedFactoryBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NestedFactoryBlockEntity(pos, state);
    }

    @Override
    public Class<NestedFactoryBlockEntity> getBlockEntityClass() {
        return NestedFactoryBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends NestedFactoryBlockEntity> getBlockEntityType() {
        return ModBlockEntities.NESTED_FACTORY.get();
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(HORIZONTAL_FACING).getAxis();
    }

    @Override
    public ContraptionMovementSetting getContraptionMovementSetting() {
        return ContraptionMovementSetting.UNMOVABLE;
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.BLOCK;
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return false;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (level.getBlockEntity(pos) instanceof NestedFactoryBlockEntity be) {
            if (level.dimension().equals(POCKET_DIMENSION)) {
                if (!be.isNested()) {
                    return InteractionResult.PASS;
                }
                if (player.isShiftKeyDown()) {
                    if (!be.isEnterable()) {
                        be.blackboxDebug("factory_entry_rejected", () ->
                                "reason=not_enterable_direct_nested_interaction, player="
                                        + serverPlayer.getScoreboardName() + ", " + be.debugEntryBlockers());
                        String message = be.isTerminalBlueprintOnly()
                                ? "message.create_nested_factory.factory.terminal_blueprint_only"
                                : "message.create_nested_factory.factory.child_factory_exists";
                        PlayerMessagePayload.sendTo(serverPlayer,
                                Component.translatable(message).withStyle(ChatFormatting.RED), false);
                    } else {
                        enterFactory(serverPlayer, be);
                    }
                    return InteractionResult.SUCCESS;
                }
                serverPlayer.openMenu(be);
                return InteractionResult.SUCCESS;
            }
            if (player.isShiftKeyDown()) {
                enterFactory(serverPlayer, be);
            } else {
                serverPlayer.openMenu(be);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof NestedFactoryBlockEntity factory) {
            factory.onExternalNeighborChanged(neighborPos);
        }
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                               BlockEntity blockEntity, ItemStack tool) {
        if (!player.isCreative() && !level.isClientSide() && level instanceof ServerLevel server
                && blockEntity instanceof NestedFactoryBlockEntity factory) {
            Block.popResource(level, pos, factory.createPortableItem(server.registryAccess()));
            return;
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        boolean destroyed = super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        if (destroyed && player.isCreative() && !level.isClientSide()
                && level instanceof ServerLevel server
                && blockEntity instanceof NestedFactoryBlockEntity factory) {
            Block.popResource(level, pos, factory.createPortableItem(server.registryAccess()));
        }
        return destroyed;
    }

    public static void enterFactory(ServerPlayer player, NestedFactoryBlockEntity factory) {
        enterFactoryInternal(player, factory, null, player.getYRot(), false, "");
    }

    /** Passage entry keeps the existing factory safety checks but may cross unrelated factory trees. */
    public static boolean enterFactoryViaPassage(ServerPlayer player, NestedFactoryBlockEntity factory,
                                                 Vec3 destination, float yRot) {
        return enterFactoryViaPassage(player, factory, destination, yRot, "");
    }

    public static boolean enterFactoryViaPassage(ServerPlayer player, NestedFactoryBlockEntity factory,
                                                 Vec3 destination, float yRot, String sourcePassageId) {
        return enterFactoryInternal(player, factory, destination, yRot, true, sourcePassageId);
    }

    private static boolean enterFactoryInternal(ServerPlayer player, NestedFactoryBlockEntity factory,
                                                Vec3 requestedDestination, float destinationYRot,
                                                boolean allowUnrelatedFactory, String sourcePassageId) {
        abortSessionOutsidePocket(player, "entry_outside_pocket");
        if (Boolean.TRUE.equals(ModAttachments.get(player, ModAttachments.PASSAGE_RESUME_PENDING))) {
            resumeSessionForPlayer(player);
            if (Boolean.TRUE.equals(ModAttachments.get(player, ModAttachments.PASSAGE_RESUME_PENDING))) return false;
        }
        if (factory == null || !factory.isEnterable()) {
            if (factory != null) {
                factory.blackboxDebug("factory_entry_rejected", () -> "reason=not_enterable, player="
                        + player.getScoreboardName() + ", " + factory.debugEntryBlockers());
            } else if (Config.blackboxDebugLogging) {
                LOGGER.info("[CNF-BLACKBOX] event=factory_entry_rejected factory=<missing> dimension={} pos={} "
                                + "reason=target_factory_missing player={}",
                        player.serverLevel().dimension().location(), player.blockPosition(),
                        player.getScoreboardName());
            }
            PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.factory.not_enterable").withStyle(ChatFormatting.RED), false);
            return false;
        }

        if (factory.getOperationMode() != OperationMode.CHUNK_LOADED) {
            factory.blackboxDebug("factory_entry_rejected", () -> "reason=simulated_mode, player="
                    + player.getScoreboardName());
            PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.factory.blackbox_entry_blocked").withStyle(ChatFormatting.RED), false);
            return false;
        }
        MinecraftServer server = player.serverLevel().getServer();
        ServerLevel pocketLevel = server.getLevel(POCKET_DIMENSION);
        if (pocketLevel == null) return false;

        if (factory.isRoomMutationLocked() || !factory.requestRoomBuild()) {
            factory.blackboxDebug("factory_entry_rejected", () -> "reason=room_mutation_or_build_pending, player="
                    + player.getScoreboardName());
            PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.room_mutation.wait").withStyle(ChatFormatting.YELLOW), false);
            return false;
        }
        BlockPos origin = factory.roomOrigin();

        ModAttachments.FactorySession session = ModAttachments.get(player, ModAttachments.FACTORY_SESSION);
        ModAttachments.FactorySession originalSession = session;
        boolean startingNewSession = !session.isActive();
        if (allowUnrelatedFactory && !startingNewSession
                && resolveSessionFactory(server, session.currentFactory()).status() == FactoryPassageNavigation.Status.RETRY_LATER) {
            notifyPassageLoading(player);
            return false;
        }
        ModAttachments.FactoryReference targetReference = factoryReference(factory);
        if (startingNewSession) {
            session = new ModAttachments.FactorySession(factory.getFactoryId(), factory.getFactoryId(), targetReference,
                    true, player.getAbilities().mayfly, player.getAbilities().flying,
                    true, player.hasEffect(MobEffects.NIGHT_VISION), new ArrayList<>(),
                    Optional.ofNullable(player.getEffect(MobEffects.NIGHT_VISION))
                            .map(effect -> effect.save(new CompoundTag())), server.overworld().getGameTime(), flightModeName(player));
        } else if (!allowUnrelatedFactory) {
            if (!session.hasCurrentFactoryReference() || !session.currentFactoryId().equals(factory.getParentFactoryId())) {
                factory.blackboxDebug("factory_entry_rejected", () -> "reason=nested_session_mismatch, player="
                        + player.getScoreboardName());
                PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.factory.nested_entry_blocked").withStyle(ChatFormatting.RED), false);
                return false;
            }
        } else if (!session.hasCurrentFactoryReference()
                || !isCurrentSessionValid(server, player, session,
                resolveFactory(server, session.currentFactory()))) {
            recoverOrEndSession(player, session, "invalid_passage_source_session");
            return false;
        }

        List<ModAttachments.ReturnFrame> stack = new ArrayList<>(session.stack());
        if (allowUnrelatedFactory && !sourcePassageId.isBlank()) {
            var source = FactoryPassageNavigation.resolve(server,
                    FactoryPassageSavedData.get(server).findByPassageId(sourcePassageId));
            if (source.status() != FactoryPassageNavigation.Status.READY) {
                return false;
            }
        }
        if (startingNewSession) {
            stack.add(allowUnrelatedFactory
                    ? ModAttachments.ReturnFrame.externalPassage(player.serverLevel().dimension(), player.position(),
                    player.getYRot(), player.getXRot(), factory.getFactoryId(), sourcePassageId)
                    : ModAttachments.ReturnFrame.external(player.serverLevel().dimension(), player.position(),
                    player.getYRot(), player.getXRot(), factory.getFactoryId()));
        } else {
            stack.add(new ModAttachments.ReturnFrame(player.serverLevel().dimension(), player.position(),
                    player.getYRot(), player.getXRot(), session.currentFactoryId(), factory.getFactoryId(),
                    session.currentFactory(), allowUnrelatedFactory,
                    allowUnrelatedFactory ? sourcePassageId : ""));
        }
        ModAttachments.set(player, ModAttachments.FACTORY_SESSION,
                session.withCurrentFactory(factory.getRootFactoryId(), factory.getFactoryId(), targetReference, stack));

        factory.onPlayerEntered(player.serverLevel().dimension(), player.blockPosition());
        factory.blackboxDebug("factory_entry_started", () -> "player=" + player.getScoreboardName()
                + ", pocketOrigin=" + origin);
        try {
            Vec3 destination = requestedDestination == null
                    ? new Vec3(origin.getX() + 1.5, origin.getY() + 2.0, origin.getZ() + 1.5)
                    : requestedDestination;
            float yaw = requestedDestination == null ? player.getYRot() : destinationYRot;
            if (!teleportToLevel(player, pocketLevel, destination, yaw, player.getXRot(),
                    allowUnrelatedFactory ? Vec3.ZERO : null)) {
                rollbackFactoryEntry(player, factory, originalSession, startingNewSession);
                return false;
            }
            grantExplorationAbilities(player, startingNewSession);
            return true;
        } catch (RuntimeException exception) {
            if (allowUnrelatedFactory) {
                rollbackFactoryEntry(player, factory, originalSession, startingNewSession);
                LOGGER.warn("Passage entry teleport failed for {}", player.getScoreboardName(), exception);
            } else {
                factory.onPlayerExited();
                recoverOrEndSession(player, ModAttachments.get(player, ModAttachments.FACTORY_SESSION), "entry_teleport_exception", exception);
            }
            return false;
        }
    }

    private static void rollbackFactoryEntry(ServerPlayer player, NestedFactoryBlockEntity factory,
                                              ModAttachments.FactorySession originalSession, boolean startingNewSession) {
        factory.onPlayerExited();
        if (startingNewSession) finishSessionWithoutCurrent(player, ModAttachments.get(player, ModAttachments.FACTORY_SESSION), false);
        ModAttachments.set(player, ModAttachments.FACTORY_SESSION, originalSession);
        syncPlayerState(player);
    }

    public static void exitCurrentFactory(ServerPlayer player) {
        exitCurrentFactoryInternal(player, null, null, player.getYRot());
    }

    /** Returns through the session's next frame using a static passage endpoint. */
    public static boolean exitCurrentFactoryViaPassage(ServerPlayer player, ResourceKey<Level> dimension,
                                                       Vec3 destination, float yRot) {
        return exitCurrentFactoryInternal(player, dimension, destination, yRot);
    }

    public static boolean exitCurrentFactoryViaPassage(ServerPlayer player, FactoryPassageSavedData.Endpoint endpoint) {
        return exitCurrentFactoryInternal(player, null, null, player.getYRot(),
                FactoryPassageNavigation.arrival(player, endpoint));
    }

    private static void notifyPassageLoading(ServerPlayer player) {
        PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.passage.loading")
                .withStyle(ChatFormatting.YELLOW), false);
    }

    private static boolean exitCurrentFactoryInternal(ServerPlayer player, ResourceKey<Level> passageDimension,
                                                      Vec3 passageDestination, float passageYRot) {
        return exitCurrentFactoryInternal(player, passageDimension, passageDestination, passageYRot, null);
    }

    private static boolean exitCurrentFactoryInternal(ServerPlayer player, ResourceKey<Level> passageDimension,
                                                      Vec3 passageDestination, float passageYRot,
                                                      FactoryPassageNavigation.Arrival passageArrival) {
        if (Boolean.TRUE.equals(ModAttachments.get(player, ModAttachments.PASSAGE_RESUME_PENDING))) {
            resumeSessionForPlayer(player);
            if (Boolean.TRUE.equals(ModAttachments.get(player, ModAttachments.PASSAGE_RESUME_PENDING))) return false;
        }
        ModAttachments.FactorySession session = ModAttachments.get(player, ModAttachments.FACTORY_SESSION);
        if (!session.isActive()) return false;
        MinecraftServer server = player.serverLevel().getServer();
        var currentResolution = resolveSessionFactory(server, session.currentFactory());
        if (currentResolution.status() == FactoryPassageNavigation.Status.RETRY_LATER
                && (passageArrival != null || session.stack().stream().anyMatch(ModAttachments.ReturnFrame::passage))) {
            notifyPassageLoading(player);
            return false;
        }
        NestedFactoryBlockEntity current = currentResolution.factory();

        if (!isCurrentSessionValid(server, player, session, current)) {
            if (current != null && player.serverLevel().dimension().equals(POCKET_DIMENSION)) current.onPlayerExited();
            recoverOrEndSession(player, session, "invalid_current_factory");
            return false;
        }

        List<ModAttachments.ReturnFrame> stack = new ArrayList<>(session.stack());
        ModAttachments.ReturnFrame nextFrame = stack.isEmpty() ? null : stack.get(stack.size() - 1);
        if (passageArrival == null && passageDimension == null && passageDestination == null && nextFrame != null
                && nextFrame.passage() && !nextFrame.returnPassageId().isBlank()) {
            passageArrival = FactoryPassageNavigation.returnArrival(player, nextFrame);
        }
        if (passageArrival != null) {
            if (passageArrival.status() == FactoryPassageNavigation.Status.RETRY_LATER) {
                notifyPassageLoading(player);
                return false;
            }
            if (passageArrival.status() == FactoryPassageNavigation.Status.INVALID) {
                current.onPlayerExited();
                releaseStackedFactories(server, session);
                recoverOrEndSession(player, session, "return_passage_missing");
                return false;
            }
            passageDimension = passageArrival.destination().dimension();
            passageDestination = passageArrival.destination().position();
            passageYRot = passageArrival.destination().yRot();
        }

        if (passageDimension != null && passageDestination != null
                && !passageDimension.equals(POCKET_DIMENSION)) {
            boolean teleported = passageArrival == null
                    ? teleportExact(player, passageDimension, passageDestination, passageYRot,
                            player.getXRot(), "external_passage_return")
                    : teleportPassage(player, passageArrival);
            if (!teleported) return false;
            if (current != null) current.onPlayerExited();
            releaseStackedFactories(server, session);
            finishSessionWithoutCurrent(player, session);
            return true;
        }

        if (stack.isEmpty()) {
            current.onPlayerExited();
            finishSessionWithoutCurrent(player, session, passageDimension == null || passageDestination == null);
            if (passageDimension != null && passageDestination != null) {
                teleportExactOrSpawn(player, passageDimension, passageDestination, passageYRot, player.getXRot(),
                        "passage_return_without_frame");
                return true;
            }
            return false;
        }
        ModAttachments.ReturnFrame frame = stack.remove(stack.size() - 1);
        if (!isReturnFrameStructurallyValid(session, frame)) {
            current.onPlayerExited();
            recoverOrEndSession(player, session, "invalid_return_frame");
            return false;
        }

        if (frame.sourceFactoryId().isEmpty()) {

            if (passageDimension == null && frame.passage()) {
                guardPassageReturn(player, frame.returnPassageId(), frame.dimension(), frame.pos());
            }
            Vec3 frameDestination = passageDimension == null && frame.passage()
                    ? safePassageReturnPosition(player, frame.dimension(), frame.pos()) : frame.pos();
            if (!teleportExact(player,
                    passageDimension == null ? frame.dimension() : passageDimension,
                    passageDestination == null ? frameDestination : passageDestination,
                    passageDestination == null ? frame.yRot() : passageYRot,
                    frame.xRot(), "external_return")) return false;
            if (current != null) current.onPlayerExited();
            finishSessionWithoutCurrent(player, session);
            return true;
        }

        var parentResolution = resolveSessionFactory(server, frame.sourceFactory());
        if (frame.passage() && parentResolution.status() == FactoryPassageNavigation.Status.RETRY_LATER) {
            notifyPassageLoading(player);
            return false;
        }
        NestedFactoryBlockEntity parent = parentResolution.factory();
        if (parent == null || !parent.getFactoryId().equals(frame.sourceFactoryId())
                || (!frame.passage() && !parent.getRootFactoryId().equals(session.rootFactoryId()))
                || parent.getOperationMode() != OperationMode.CHUNK_LOADED) {
            current.onPlayerExited();
            recoverOrEndSession(player, session, "invalid_parent_factory");
            return false;
        }

        ResourceKey<Level> destinationDimension = passageDimension == null ? frame.dimension() : passageDimension;
        ServerLevel target = server.getLevel(destinationDimension);
        if (target == null) {
            current.onPlayerExited();
            recoverOrEndSession(player, session, "missing_return_dimension");
            return false;
        }
        boolean currentReleased = false;
        try {
            Vec3 frameDestination = passageDimension == null && frame.passage()
                    ? safePassageReturnPosition(player, frame.dimension(), frame.pos()) : frame.pos();
            Vec3 destination = passageDestination == null ? frameDestination : passageDestination;
            if (passageDimension == null && frame.passage()) {
                guardPassageReturn(player, frame.returnPassageId(), frame.dimension(), frame.pos());
            }
            if (passageArrival != null) {
                if (!teleportPassage(player, passageArrival)) return false;
            } else {
                if (!teleportToLevel(player, target, destination,
                        passageDestination == null ? frame.yRot() : passageYRot, frame.xRot(), null)) return false;
            }
            if (current != null) current.onPlayerExited();
            currentReleased = true;
            ModAttachments.set(player, ModAttachments.FACTORY_SESSION,
                    session.withCurrentFactory(parent.getRootFactoryId(), frame.sourceFactoryId(), frame.sourceFactory(), stack));
            grantExplorationAbilities(player, false);
            return true;
        } catch (RuntimeException exception) {
            if (!currentReleased) current.onPlayerExited();
            recoverOrEndSession(player, session, "return_teleport_exception", exception);
            return false;
        }
    }

    private static boolean teleportPassage(ServerPlayer player, FactoryPassageNavigation.Arrival arrival) {
        ModAttachments.PassageTravelGuard previous = ModAttachments.get(player, ModAttachments.PASSAGE_TRAVEL_GUARD);
        FactoryPassageNavigation.guard(player, arrival.passage());
        if (teleportResolved(player, arrival.destination(), "passage_return")) {
            return true;
        }
        ModAttachments.set(player, ModAttachments.PASSAGE_TRAVEL_GUARD, previous);
        return false;
    }

    public static void endSessionForPlayer(ServerPlayer player) {
        boolean pending = Boolean.TRUE.equals(ModAttachments.get(player, ModAttachments.PASSAGE_RESUME_PENDING));
        ModAttachments.set(player, ModAttachments.PASSAGE_RESUME_PENDING, false);
        ModAttachments.FactorySession session = ModAttachments.get(player, ModAttachments.FACTORY_SESSION);
        if (!session.isActive()) {
            ModAttachments.set(player, ModAttachments.FACTORY_SESSION, ModAttachments.FactorySession.defaults());
            return;
        }
        NestedFactoryBlockEntity current = resolveFactory(player.serverLevel().getServer(), session.currentFactory());
        if (!pending && current != null && player.serverLevel().dimension().equals(POCKET_DIMENSION)) current.onPlayerExited();
        finishSessionWithoutCurrent(player, session);
    }

    /**
     * Ends a live factory session after another command or mod moved the player out of Pocket.
     * The external destination is authoritative, so this cleanup never teleports the player.
     */
    public static boolean abortSessionOutsidePocket(ServerPlayer player, String reason) {
        ModAttachments.FactorySession session = ModAttachments.get(player, ModAttachments.FACTORY_SESSION);
        boolean inPocket = player.serverLevel().dimension().equals(POCKET_DIMENSION);
        if (FactorySessionLocationPolicy.action(session.isActive(), inPocket)
                != FactorySessionLocationPolicy.Action.ABORT) {
            return false;
        }

        MinecraftServer server = player.serverLevel().getServer();
        ModAttachments.set(player, ModAttachments.FACTORY_SESSION, ModAttachments.FactorySession.defaults());
        try {
            NestedFactoryBlockEntity current = resolveFactory(server, session.currentFactory());
            if (!Boolean.TRUE.equals(ModAttachments.get(player, ModAttachments.PASSAGE_RESUME_PENDING))
                    && current != null && current.getFactoryId().equals(session.currentFactoryId())) {
                current.onPlayerExited();
            }
            releaseStackedFactories(server, session);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to release factories for externally moved player {}: {}",
                    player.getGameProfile().getName(), reason, exception);
        } finally {
            finishSessionWithoutCurrent(player, session);
            ModAttachments.set(player, ModAttachments.PASSAGE_TRAVEL_GUARD, ModAttachments.PassageTravelGuard.empty());
        }
        LOGGER.info("Factory session recovered for externally moved player {}: reason={}, currentFactory={}",
                player.getGameProfile().getName(), reason, session.currentFactoryId());
        return true;
    }

    public static void suspendSessionForPlayer(ServerPlayer player) {
        if (Boolean.TRUE.equals(ModAttachments.get(player, ModAttachments.PASSAGE_RESUME_PENDING))) {
            ModAttachments.set(player, ModAttachments.PASSAGE_RESUME_PENDING, false);
            return;
        }
        ModAttachments.FactorySession session = ModAttachments.get(player, ModAttachments.FACTORY_SESSION);
        if (!session.isActive() || !player.serverLevel().dimension().equals(POCKET_DIMENSION)) return;
        NestedFactoryBlockEntity current = resolveFactory(player.serverLevel().getServer(), session.currentFactory());
        if (current != null && current.getFactoryId().equals(session.currentFactoryId())) current.onPlayerExited();
    }

    public static void resumeSessionForPlayer(ServerPlayer player) {
        ModAttachments.FactorySession session = ModAttachments.get(player, ModAttachments.FACTORY_SESSION);
        if (!session.isActive()) {
            ModAttachments.set(player, ModAttachments.PASSAGE_RESUME_PENDING, false);
            if (player.serverLevel().dimension().equals(POCKET_DIMENSION)) teleportToOverworldSpawn(player, "pocket_without_session");
            return;
        }
        if (!player.serverLevel().dimension().equals(POCKET_DIMENSION)) {
            finishSessionWithoutCurrent(player, session);
            ModAttachments.set(player, ModAttachments.PASSAGE_TRAVEL_GUARD, ModAttachments.PassageTravelGuard.empty());
            return;
        }

        MinecraftServer server = player.serverLevel().getServer();
        NestedFactoryBlockEntity current;
        try {
            var resolution = resolveSessionFactory(server, session.currentFactory());
            if (resolution.status() == FactoryPassageNavigation.Status.RETRY_LATER
                    && session.stack().stream().anyMatch(ModAttachments.ReturnFrame::passage)) {
                if (!Boolean.TRUE.equals(ModAttachments.get(player, ModAttachments.PASSAGE_RESUME_PENDING))) {
                    notifyPassageLoading(player);
                }
                ModAttachments.set(player, ModAttachments.PASSAGE_RESUME_PENDING, true);
                return;
            }
            current = resolution.factory();
        } catch (RuntimeException exception) {
            recoverOrEndSession(player, session, "current_factory_load_exception", exception);
            return;
        }
        if (!isCurrentSessionValid(server, player, session, current)) {
            recoverOrEndSession(player, session, "invalid_login_session");
            return;
        }
        ModAttachments.ReturnFrame entryFrame = session.stack().isEmpty()
                ? null
                : session.stack().get(session.stack().size() - 1);
        if (entryFrame != null) {
            current.onPlayerEntered(entryFrame.dimension(), BlockPos.containing(entryFrame.pos()));
        } else {
            current.onPlayerEntered(player.serverLevel().dimension(), player.blockPosition());
        }
        grantExplorationAbilities(player, false);
        ModAttachments.set(player, ModAttachments.PASSAGE_RESUME_PENDING, false);
    }

    private static ModAttachments.FactoryReference factoryReference(NestedFactoryBlockEntity factory) {
        return new ModAttachments.FactoryReference(factory.getLevel().dimension(), factory.getBlockPos().immutable(),
                factory.getFactoryId(), factory.getRootFactoryId());
    }

    /** True when a current or stacked passage route still visits the supplied factory tree. */
    public static boolean sessionReferencesRoot(ModAttachments.FactorySession session, String rootFactoryId) {
        if (session == null || !session.isActive() || rootFactoryId == null || rootFactoryId.isBlank()) {
            return false;
        }
        if (session.currentFactory() != null
                && rootFactoryId.equals(session.currentFactory().rootFactoryId())) {
            return true;
        }
        for (ModAttachments.ReturnFrame frame : session.stack()) {
            if (frame.sourceFactory() != null
                    && rootFactoryId.equals(frame.sourceFactory().rootFactoryId())) {
                return true;
            }
        }
        return false;
    }

    private static NestedFactoryBlockEntity resolveFactory(MinecraftServer server, ModAttachments.FactoryReference reference) {
        return resolveSessionFactory(server, reference).factory();
    }

    private static FactoryPassageNavigation.FactoryResolution resolveSessionFactory(
            MinecraftServer server, ModAttachments.FactoryReference reference) {
        var resolved = FactoryPassageNavigation.resolveFactory(server, reference);
        if (resolved.factory() != null && !resolved.factory().getRootFactoryId().equals(reference.rootFactoryId())) {
            return new FactoryPassageNavigation.FactoryResolution(FactoryPassageNavigation.Status.INVALID, null);
        }
        return resolved;
    }

    private static boolean isCurrentSessionValid(MinecraftServer server, ServerPlayer player,
                                                 ModAttachments.FactorySession session,
                                                 NestedFactoryBlockEntity current) {
        if (current == null || !session.hasCurrentFactoryReference()
                || !session.currentFactoryId().equals(current.getFactoryId())
                || !session.rootFactoryId().equals(current.getRootFactoryId())
                || !current.isEnterable()
                || current.getOperationMode() != OperationMode.CHUNK_LOADED
                || !isReturnStackStructurallyValid(session)) return false;
        ServerLevel pocket = server.getLevel(POCKET_DIMENSION);
        return pocket != null && player.serverLevel() == pocket
                && current.getBounds().contains(current.roomOrigin(), player.blockPosition())
                && !pocket.getBlockState(current.roomOrigin()).isAir();
    }

    private static boolean isReturnStackStructurallyValid(ModAttachments.FactorySession session) {
        boolean containsPassage = session.stack().stream().anyMatch(ModAttachments.ReturnFrame::passage);
        for (ModAttachments.ReturnFrame frame : session.stack()) {
            if (!isReturnFrameBasicStructureValid(frame)) return false;
            if (!containsPassage && !frame.sourceFactoryId().isEmpty()
                    && !session.rootFactoryId().equals(frame.sourceFactory().rootFactoryId())) return false;
        }
        return true;
    }

    private static boolean isReturnFrameStructurallyValid(ModAttachments.FactorySession session,
                                                           ModAttachments.ReturnFrame frame) {
        return isReturnFrameBasicStructureValid(frame)
                && (frame.sourceFactoryId().isEmpty() || frame.passage()
                || session.rootFactoryId().equals(frame.sourceFactory().rootFactoryId()));
    }

    private static boolean isReturnFrameBasicStructureValid(ModAttachments.ReturnFrame frame) {
        if (frame.sourceFactoryId().isEmpty()) return frame.sourceFactory() == null;
        return frame.sourceFactory() != null && frame.sourceFactory().isComplete()
                && frame.sourceFactoryId().equals(frame.sourceFactory().factoryId());
    }

    private static void recoverOrEndSession(ServerPlayer player, ModAttachments.FactorySession session, String reason) {
        recoverOrEndSession(player, session, reason, null);
    }

    private static void recoverOrEndSession(ServerPlayer player, ModAttachments.FactorySession session,
                                            String reason, RuntimeException exception) {
        if (exception != null) LOGGER.error("Failed Pocket session recovery for {}: {}", player.getGameProfile().getName(), reason, exception);
        ModAttachments.ReturnFrame anchor = null;
        for (ModAttachments.ReturnFrame frame : session.stack()) {
            if (frame.sourceFactoryId().isEmpty()) {
                anchor = frame;
                break;
            }
        }
        finishSessionWithoutCurrent(player, session, false);
        if (anchor != null && anchor.passage() && !anchor.returnPassageId().isBlank()) {
            var arrival = FactoryPassageNavigation.returnArrival(player, anchor);
            if (arrival.status() == FactoryPassageNavigation.Status.READY && teleportPassage(player, arrival)) {
                syncPlayerState(player);
                return;
            }
            teleportToOverworldSpawn(player, reason);
        } else if (anchor != null) {
            teleportExactOrSpawn(player, anchor.dimension(), anchor.pos(), anchor.yRot(), anchor.xRot(), reason);
        } else {
            teleportToOverworldSpawn(player, reason);
        }
    }

    private static void teleportExactOrSpawn(ServerPlayer player, ResourceKey<Level> dimension,
                                             net.minecraft.world.phys.Vec3 pos, float yRot, float xRot, String reason) {
        if (teleportExact(player, dimension, pos, yRot, xRot, reason)) {
            syncPlayerState(player);
            return;
        }
        teleportToOverworldSpawn(player, reason);
    }

    private static boolean teleportResolved(ServerPlayer player, FactoryPassageNavigation.Destination destination,
                                             String reason) {
        try {
            ServerLevel target = player.server.getLevel(destination.dimension());
            if (target != null) {
                Vec3 pos = destination.position();
                if (!teleportToLevel(player, target, pos, destination.yRot(), destination.xRot(), destination.velocity())) {
                    FactoryPassageNavigation.trace(player, "return_failed", "reason=" + reason
                            + ", target=" + destination.dimension().location() + ", actual=" + player.serverLevel().dimension().location());
                    return false;
                }
                FactoryPassageNavigation.trace(player, "return_completed", "reason=" + reason
                        + ", dimension=" + destination.dimension().location() + ", position=" + pos);
                return true;
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed passage Pocket return for {}: {}",
                    player.getGameProfile().getName(), reason, exception);
        }
        return false;
    }

    private static boolean teleportExact(ServerPlayer player, ResourceKey<Level> dimension, Vec3 pos,
                                         float yRot, float xRot, String reason) {
        try {
            ServerLevel target = player.server.getLevel(dimension);
            if (target == null) return false;
            return teleportToLevel(player, target, pos, yRot, xRot, null);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed exact Pocket teleport for {}: {}",
                    player.getGameProfile().getName(), reason, exception);
            return false;
        }
    }

    private static boolean teleportToLevel(ServerPlayer player, ServerLevel target, Vec3 pos,
                                            float yRot, float xRot, Vec3 velocity) {
        player.fallDistance = 0f;
        // The 1.20.1 boolean overload can report success after a cancelled dimension change.
        if (!player.teleportTo(target, pos.x, pos.y, pos.z, Set.of(), yRot, xRot)
                || player.serverLevel() != target) return false;
        if (velocity != null) {
            player.setDeltaMovement(velocity);
            player.hurtMarked = true;
        }
        return true;
    }

    private static void guardPassageReturn(ServerPlayer player, String passageId,
                                           ResourceKey<Level> dimension, Vec3 position) {
        BlockPos candidate = BlockPos.containing(position);
        String resolvedPassageId = passageId == null ? "" : passageId;
        BlockPos guardPos = candidate;
        if (resolvedPassageId.isBlank()) {
            ServerLevel target = player.server.getLevel(dimension);
            if (target == null) return;
            target.getChunkAt(candidate);
            BlockState state = target.getBlockState(candidate);
            if (!state.is(ModBlocks.FACTORY_PASSAGE.get())) return;
            guardPos = FactoryPassageBlock.getBasePos(candidate, state);
            if (!(target.getBlockEntity(guardPos) instanceof FactoryPassageBlockEntity passage)) {
                return;
            }
            resolvedPassageId = passage.getPassageId();
        }
        ModAttachments.set(player, ModAttachments.PASSAGE_TRAVEL_GUARD,
                ModAttachments.PassageTravelGuard.arrival(resolvedPassageId, dimension, guardPos));
    }

    private static Vec3 safePassageReturnPosition(ServerPlayer player, ResourceKey<Level> dimension,
                                                   Vec3 preferred) {
        ServerLevel target = player.server.getLevel(dimension);
        if (target == null) return preferred;
        target.getChunkAt(BlockPos.containing(preferred));
        if (canOccupy(player, target, preferred)) return preferred;

        BlockPos center = BlockPos.containing(preferred);
        for (int radius = 1; radius <= 3; radius++) {
            for (int y = -1; y <= 2; y++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                        BlockPos feet = center.offset(x, y, z);
                        Vec3 candidate = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                        if (canOccupy(player, target, candidate)) return candidate;
                    }
                }
            }
        }
        return preferred;
    }

    private static boolean canOccupy(ServerPlayer player, ServerLevel level, Vec3 position) {
        Vec3 offset = position.subtract(player.position());
        return level.noCollision(player, player.getBoundingBox().move(offset));
    }

    private static void releaseStackedFactories(MinecraftServer server, ModAttachments.FactorySession session) {
        for (ModAttachments.ReturnFrame frame : session.stack()) {
            if (frame.sourceFactory() == null) {
                continue;
            }
            NestedFactoryBlockEntity factory = resolveFactory(server, frame.sourceFactory());
            if (factory != null) {
                factory.onPlayerExited();
            }
        }
    }

    private static void teleportToOverworldSpawn(ServerPlayer player, String reason) {
        try {
            ServerLevel overworld = player.server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            if (!teleportToLevel(player, overworld, new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5),
                    player.getYRot(), player.getXRot(), null)) {
                LOGGER.warn("Overworld Pocket recovery teleport was cancelled for {}: {}", player.getScoreboardName(), reason);
            }
            syncPlayerState(player);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed overworld Pocket recovery teleport for {}: {}", player.getGameProfile().getName(), reason, exception);
        }
    }

    private static void finishSessionWithoutCurrent(ServerPlayer player, ModAttachments.FactorySession session) {
        finishSessionWithoutCurrent(player, session, true);
    }

    private static void finishSessionWithoutCurrent(ServerPlayer player, ModAttachments.FactorySession session, boolean sync) {
        ModAttachments.set(player, ModAttachments.PASSAGE_RESUME_PENDING, false);

        if (session.grantedFlight()) {
            session = reconcileFlightMode(player, session);
            GameType mode = currentGameMode(player);
            player.getAbilities().mayfly = mode == GameType.CREATIVE
                    || mode == GameType.SPECTATOR || session.originalMayFly();
            player.getAbilities().flying = mode == GameType.SPECTATOR
                    || player.getAbilities().mayfly && session.originalFlying();
        }
        if (session.nightVisionGranted()) {
            if (session.explorationStartedAt() >= 0) {
                player.removeEffect(MobEffects.NIGHT_VISION);
                long elapsed = Math.max(0L, player.server.overworld().getGameTime() - session.explorationStartedAt());
                session.originalNightVisionEffect().map(tag -> ageEffect(tag, elapsed))
                        .map(MobEffectInstance::load).ifPresent(player::addEffect);
            } else if (!session.originalNightVision()) {
                player.removeEffect(MobEffects.NIGHT_VISION);
            }
        }
        ModAttachments.set(player, ModAttachments.FACTORY_SESSION, ModAttachments.FactorySession.defaults());
        if (sync) syncPlayerState(player);
    }

    private static void grantExplorationAbilities(ServerPlayer player, boolean startFlying) {
        ModAttachments.FactorySession session = ModAttachments.get(player, ModAttachments.FACTORY_SESSION);
        ModAttachments.FactorySession reconciled = reconcileFlightMode(player, session);
        if (reconciled != session) ModAttachments.set(player, ModAttachments.FACTORY_SESSION, reconciled);
        ModAttachments.set(player, ModAttachments.EXPLORATION_FLIGHT_MODE, flightModeName(player));
        player.getAbilities().mayfly = true;
        if (startFlying) player.getAbilities().flying = true;
        ensureExplorationNightVision(player);
        syncPlayerState(player);
    }

    private static GameType currentGameMode(ServerPlayer player) {
        return player.gameMode == null ? null : player.gameMode.getGameModeForPlayer();
    }

    private static String flightModeName(ServerPlayer player) {
        var mode = currentGameMode(player);
        return mode == null ? "" : mode.getName();
    }

    private static ModAttachments.FactorySession reconcileFlightMode(ServerPlayer player,
                                                                      ModAttachments.FactorySession session) {
        var mode = currentGameMode(player);
        if (mode == null) return session;
        if (session.flightGameMode().isEmpty()) {
            // Legacy sessions preserve their original non-vanilla grant; establish a baseline for future changes.
            return session.withFlightSnapshot(mode.getName(), session.originalMayFly(), session.originalFlying());
        }
        if (mode.getName().equals(session.flightGameMode())) return session;
        boolean mayFly = mode == GameType.CREATIVE
                || mode == GameType.SPECTATOR;
        boolean flying = mode == GameType.SPECTATOR
                || mayFly && player.getAbilities().flying;
        return session.withFlightSnapshot(mode.getName(), mayFly, flying);
    }

    /** Observe successful mode changes on the next tick, after Forge cancellation and vanilla ability updates. */
    public static void refreshExplorationFlight(ServerPlayer player) {
        String mode = flightModeName(player);
        if (mode.equals(ModAttachments.get(player, ModAttachments.EXPLORATION_FLIGHT_MODE))) return;
        ModAttachments.set(player, ModAttachments.EXPLORATION_FLIGHT_MODE, mode);
        ModAttachments.FactorySession session = ModAttachments.get(player, ModAttachments.FACTORY_SESSION);
        if (!session.isActive() || ModAttachments.get(player, ModAttachments.PASSAGE_RESUME_PENDING)) return;
        ModAttachments.set(player, ModAttachments.FACTORY_SESSION, reconcileFlightMode(player, session));
        player.getAbilities().mayfly = true;
        player.onUpdateAbilities();
    }

    /** Milk/effect removal should replenish night vision without changing the player's flight state. */
    public static void ensureExplorationNightVision(ServerPlayer player) {
        MobEffectInstance effect = player.getEffect(MobEffects.NIGHT_VISION);
        if (effect == null || !effect.isInfiniteDuration()) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION,
                    effect == null ? 0 : effect.getAmplifier(), false, false, true));
        }
    }

    private static void syncPlayerState(ServerPlayer player) {
        player.onUpdateAbilities();
        // Respawn rebuilds the client player. Existing server effects need explicit packets even when addEffect is a no-op.
        if (player.connection != null) {
            for (MobEffectInstance effect : player.getActiveEffects()) {
                player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), effect));
            }
        }
    }

    private static CompoundTag ageEffect(CompoundTag original, long elapsed) {
        CompoundTag tag = original.copy();
        CompoundTag hidden = tag.contains("HiddenEffect", 10) ? ageEffect(tag.getCompound("HiddenEffect"), elapsed) : null;
        if (hidden == null) tag.remove("HiddenEffect"); else tag.put("HiddenEffect", hidden);
        int duration = tag.getInt("Duration");
        if (duration != MobEffectInstance.INFINITE_DURATION) {
            int remaining = (int) Math.max(0L, duration - elapsed);
            if (remaining == 0) return hidden;
            tag.putInt("Duration", remaining);
        }
        return tag;
    }

    /** Returns the coordinate for a newly allocated persistent root-room slot. */
    public static BlockPos getRootRoomOrigin(int slotId) {
        int slotX = Math.floorMod(slotId, ROOT_SLOT_GRID_WIDTH);
        int slotZ = Math.floorDiv(slotId, ROOT_SLOT_GRID_WIDTH);
        return new BlockPos(slotX * ROOT_GRID_SIZE, ROOT_AREA_Y, slotZ * ROOT_GRID_SIZE);
    }

    /**
     * Legacy-only coordinate mapping used when migrating roots created before persistent slots.
     * New roots must obtain their origin from {@link NestedFactorySaveData} instead.
     */
    public static BlockPos getLegacyPocketOrigin(BlockPos factoryPos) {
        return new BlockPos(factoryPos.getX() * ROOT_GRID_SIZE, LEGACY_ROOT_AREA_Y, factoryPos.getZ() * ROOT_GRID_SIZE);
    }

    public static BlockPos getNestedRoomOrigin(int slotX, int slotZ) {
        return new BlockPos(slotX * NESTED_SLOT_SIZE + NESTED_SLOT_OFFSET,
                NESTED_AREA_Y,
                slotZ * NESTED_SLOT_SIZE + NESTED_SLOT_OFFSET);
    }

    public static BlockState wallState(int x, int y, int z) {
        return ((x + y + z) & 1) == 0
                ? ModBlocks.SNOW_WALL.get().defaultBlockState()
                : ModBlocks.WHITE_CONCRETE_WALL.get().defaultBlockState();
    }

    public static boolean isWallBlock(BlockState state) {
        return state.is(ModBlocks.SNOW_WALL.get()) || state.is(ModBlocks.WHITE_CONCRETE_WALL.get());
    }

    public static BlockPos findRoomOrigin(ServerLevel pocketLevel, BlockPos pocketPos) {
        if (pocketPos.getY() < LEGACY_ROOT_AREA_Y) {
            int slotX = Math.floorDiv(pocketPos.getX(), NESTED_SLOT_SIZE);
            int slotZ = Math.floorDiv(pocketPos.getZ(), NESTED_SLOT_SIZE);
            PocketRegistry.NestedSlot slot = PocketRegistry.getNestedSlot(pocketLevel.getServer(), slotX, slotZ);
            if (slot != null) {
                BlockPos origin = getNestedRoomOrigin(slot.slotX(), slot.slotZ());
                ServerLevel factoryLevel = pocketLevel.getServer().getLevel(slot.location().dimension());
                if (factoryLevel != null && factoryLevel.getBlockEntity(slot.location().pos()) instanceof NestedFactoryBlockEntity be
                        && be.getBounds().contains(origin, pocketPos)) {
                    return origin;
                }
            }
            return null;
        }

        int regionX = Math.floorDiv(pocketPos.getX(), PocketRegistry.ROOT_REGION_SIZE);
        int regionZ = Math.floorDiv(pocketPos.getZ(), PocketRegistry.ROOT_REGION_SIZE);
        Set<BlockPos> candidates = PocketRegistry.getRootOriginsInRegion(pocketLevel.getServer(), regionX, regionZ);
        for (BlockPos origin : candidates) {
            PocketRegistry.FactoryLocation loc = PocketRegistry.get(pocketLevel.getServer(), origin);
            if (loc == null) {
                continue;
            }
            ServerLevel factoryLevel = pocketLevel.getServer().getLevel(loc.dimension());
            if (factoryLevel != null && factoryLevel.getBlockEntity(loc.pos()) instanceof NestedFactoryBlockEntity be
                    && be.getBounds().contains(origin, pocketPos)) {
                return origin;
            }
        }
        return null;
    }

    public static NestedFactoryBlockEntity findFactoryAt(ServerLevel pocketLevel, BlockPos pocketPos) {
        BlockPos roomOrigin = findRoomOrigin(pocketLevel, pocketPos);
        if (roomOrigin == null) {
            return null;
        }
        PocketRegistry.FactoryLocation loc = PocketRegistry.get(pocketLevel.getServer(), roomOrigin);
        if (loc == null && roomOrigin.getY() < LEGACY_ROOT_AREA_Y) {
            int slotX = Math.floorDiv(roomOrigin.getX(), NESTED_SLOT_SIZE);
            int slotZ = Math.floorDiv(roomOrigin.getZ(), NESTED_SLOT_SIZE);
            PocketRegistry.NestedSlot slot = PocketRegistry.getNestedSlot(pocketLevel.getServer(), slotX, slotZ);
            if (slot != null) {
                loc = slot.location();
            }
        }
        if (loc == null) {
            return null;
        }
        ServerLevel factoryLevel = pocketLevel.getServer().getLevel(loc.dimension());
        if (factoryLevel == null) {
            return null;
        }
        return factoryLevel.getBlockEntity(loc.pos()) instanceof NestedFactoryBlockEntity be ? be : null;
    }

}

package com.createnestedfactory.create_nested_factory.block;

import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.mojang.logging.LogUtils;
import com.createnestedfactory.create_nested_factory.NestedFactorySaveData;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

public class NestedFactoryBlock extends HorizontalKineticBlock implements IBE<NestedFactoryBlockEntity>, ContraptionMovementSetting.MovementSettingProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceKey<Level> POCKET_DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "nested_factory"));
    /** Persistent root slots live above all legacy root rooms, so lazy migration cannot claim an unloaded legacy room. */
    public static final int ROOT_AREA_Y = 128;
    public static final int LEGACY_ROOT_AREA_Y = 64;
    public static final int ROOT_GRID_SIZE = 64;
    public static final int ROOT_SLOT_GRID_WIDTH = 1024;
    public static final int NESTED_AREA_Y = 0;
    public static final int NESTED_SLOT_SIZE = 32;
    public static final int NESTED_ROOM_SIZE = 16;
    public static final int NESTED_SLOT_OFFSET = 8;
    private static final ResourceLocation EXPLORATION_FLIGHT_ID = ResourceLocation.fromNamespaceAndPath(
            Create_nested_factory.MODID, "pocket_exploration_flight");
    private static final AttributeModifier EXPLORATION_FLIGHT = new AttributeModifier(EXPLORATION_FLIGHT_ID, 1.0,
            AttributeModifier.Operation.ADD_VALUE);

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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
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
                if (player.isCrouching()) {
                    if (!be.isEnterable()) {
                        PlayerMessagePayload.sendTo(serverPlayer, Component.translatable("message.create_nested_factory.factory.child_factory_exists").withStyle(ChatFormatting.RED), false);
                    } else {
                        enterFactory(serverPlayer, be);
                    }
                    return InteractionResult.SUCCESS;
                }
                serverPlayer.openMenu(be);
                return InteractionResult.SUCCESS;
            }
            if (player.isCrouching()) {
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
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                               BlockEntity blockEntity, ItemStack tool) {
        if (!level.isClientSide() && level instanceof ServerLevel server
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
        if (factory == null || !factory.isEnterable()) {
            PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.factory.not_enterable").withStyle(ChatFormatting.RED), false);
            return;
        }
        if (factory.getOperationMode() != OperationMode.CHUNK_LOADED) {
            PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.factory.blackbox_entry_blocked").withStyle(ChatFormatting.RED), false);
            return;
        }
        MinecraftServer server = player.serverLevel().getServer();
        ServerLevel pocketLevel = server.getLevel(POCKET_DIMENSION);
        if (pocketLevel == null) return;

        if (factory.isRoomMutationLocked() || !factory.requestRoomBuild()) {
            PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.room_mutation.wait").withStyle(ChatFormatting.YELLOW), false);
            return;
        }
        BlockPos origin = factory.roomOrigin();

        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        boolean startingNewSession = !session.isActive();
        ModAttachments.FactoryReference targetReference = factoryReference(factory);
        if (startingNewSession) {
            session = new ModAttachments.FactorySession(factory.getFactoryId(), factory.getFactoryId(), targetReference,
                    true, player.mayFly(), player.getAbilities().flying,
                    true, player.hasEffect(MobEffects.NIGHT_VISION), new ArrayList<>());
            player.setData(ModAttachments.FACTORY_SESSION, session);
            grantExplorationAbilities(player);
        } else {
            if (!session.hasCurrentFactoryReference()
                    || !session.currentFactoryId().equals(factory.getParentFactoryId())) {
                PlayerMessagePayload.sendTo(player, Component.translatable("message.create_nested_factory.factory.nested_entry_blocked").withStyle(ChatFormatting.RED), false);
                return;
            }
        }

        List<ModAttachments.ReturnFrame> stack = new ArrayList<>(session.stack());
        if (startingNewSession) {
            stack.add(ModAttachments.ReturnFrame.external(player.serverLevel().dimension(), player.position(),
                    player.getYRot(), player.getXRot(), factory.getFactoryId()));
        } else {
            stack.add(new ModAttachments.ReturnFrame(player.serverLevel().dimension(), player.position(),
                    player.getYRot(), player.getXRot(), session.currentFactoryId(), factory.getFactoryId(),
                    session.currentFactory()));
        }
        player.setData(ModAttachments.FACTORY_SESSION,
                new ModAttachments.FactorySession(session.rootFactoryId(), factory.getFactoryId(), targetReference,
                        session.grantedFlight(), session.originalMayFly(), session.originalFlying(),
                        session.nightVisionGranted(), session.originalNightVision(), stack));

        factory.onPlayerEntered();
        try {
            player.fallDistance = 0f;
            player.teleportTo(pocketLevel, origin.getX() + 1.5, origin.getY() + 2.0, origin.getZ() + 1.5,
                    player.getYRot(), player.getXRot());
        } catch (RuntimeException exception) {
            factory.onPlayerExited();
            recoverOrEndSession(player, player.getData(ModAttachments.FACTORY_SESSION), "entry_teleport_exception", exception);
        }
    }

    public static void exitCurrentFactory(ServerPlayer player) {
        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        if (!session.isActive()) return;
        MinecraftServer server = player.serverLevel().getServer();
        NestedFactoryBlockEntity current = resolveFactory(server, session.currentFactory());
        if (current != null && player.serverLevel().dimension().equals(POCKET_DIMENSION)) current.onPlayerExited();

        if (!isCurrentSessionValid(server, player, session, current)) {
            recoverOrEndSession(player, session, "invalid_current_factory");
            return;
        }

        List<ModAttachments.ReturnFrame> stack = new ArrayList<>(session.stack());
        if (stack.isEmpty()) {
            finishSessionWithoutCurrent(player, session);
            return;
        }
        ModAttachments.ReturnFrame frame = stack.remove(stack.size() - 1);
        if (!isReturnFrameStructurallyValid(session, frame)) {
            recoverOrEndSession(player, session, "invalid_return_frame");
            return;
        }

        if (frame.sourceFactoryId().isEmpty()) {
            finishSessionWithoutCurrent(player, session);
            teleportExactOrSpawn(player, frame.dimension(), frame.pos(), frame.yRot(), frame.xRot(), "external_return");
            return;
        }

        NestedFactoryBlockEntity parent = resolveFactory(server, frame.sourceFactory());
        if (parent == null || !parent.getFactoryId().equals(frame.sourceFactoryId())
                || !parent.getRootFactoryId().equals(session.rootFactoryId())
                || parent.getOperationMode() != OperationMode.CHUNK_LOADED) {
            recoverOrEndSession(player, session, "invalid_parent_factory");
            return;
        }

        ServerLevel target = server.getLevel(frame.dimension());
        if (target == null) {
            recoverOrEndSession(player, session, "missing_return_dimension");
            return;
        }
        try {
            player.fallDistance = 0f;
            player.teleportTo(target, frame.pos().x, frame.pos().y, frame.pos().z, frame.yRot(), frame.xRot());
            player.setData(ModAttachments.FACTORY_SESSION,
                    new ModAttachments.FactorySession(session.rootFactoryId(), frame.sourceFactoryId(), frame.sourceFactory(),
                            session.grantedFlight(), session.originalMayFly(), session.originalFlying(),
                            session.nightVisionGranted(), session.originalNightVision(), stack));
        } catch (RuntimeException exception) {
            recoverOrEndSession(player, session, "return_teleport_exception", exception);
        }
    }

    public static void endSessionForPlayer(ServerPlayer player) {
        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        if (!session.isActive()) {
            player.setData(ModAttachments.FACTORY_SESSION, ModAttachments.FactorySession.defaults());
            return;
        }
        NestedFactoryBlockEntity current = resolveFactory(player.serverLevel().getServer(), session.currentFactory());
        if (current != null && player.serverLevel().dimension().equals(POCKET_DIMENSION)) current.onPlayerExited();
        finishSessionWithoutCurrent(player, session);
    }

    public static void suspendSessionForPlayer(ServerPlayer player) {
        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        if (!session.isActive() || !player.serverLevel().dimension().equals(POCKET_DIMENSION)) return;
        NestedFactoryBlockEntity current = resolveFactory(player.serverLevel().getServer(), session.currentFactory());
        if (current != null && current.getFactoryId().equals(session.currentFactoryId())) current.onPlayerExited();
    }

    public static void resumeSessionForPlayer(ServerPlayer player) {
        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        if (!session.isActive()) {
            if (player.serverLevel().dimension().equals(POCKET_DIMENSION)) teleportToOverworldSpawn(player, "pocket_without_session");
            return;
        }
        if (!player.serverLevel().dimension().equals(POCKET_DIMENSION)) {
            finishSessionWithoutCurrent(player, session);
            return;
        }

        MinecraftServer server = player.serverLevel().getServer();
        NestedFactoryBlockEntity current;
        try {
            current = resolveFactory(server, session.currentFactory());
        } catch (RuntimeException exception) {
            recoverOrEndSession(player, session, "current_factory_load_exception", exception);
            return;
        }
        if (!isCurrentSessionValid(server, player, session, current)) {
            recoverOrEndSession(player, session, "invalid_login_session");
            return;
        }
        current.onPlayerEntered();
        grantExplorationAbilities(player);
    }

    private static ModAttachments.FactoryReference factoryReference(NestedFactoryBlockEntity factory) {
        return new ModAttachments.FactoryReference(factory.getLevel().dimension(), factory.getBlockPos().immutable(),
                factory.getFactoryId(), factory.getRootFactoryId());
    }

    private static NestedFactoryBlockEntity resolveFactory(MinecraftServer server, ModAttachments.FactoryReference reference) {
        if (reference == null || !reference.isComplete()) return null;
        ServerLevel level = server.getLevel(reference.dimension());
        if (level == null) return null;
        level.getChunkAt(reference.pos());
        if (!(level.getBlockEntity(reference.pos()) instanceof NestedFactoryBlockEntity factory)) return null;
        if (!factory.getFactoryId().equals(reference.factoryId())
                || !factory.getRootFactoryId().equals(reference.rootFactoryId())) return null;
        return factory;
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
        for (ModAttachments.ReturnFrame frame : session.stack()) {
            if (!isReturnFrameStructurallyValid(session, frame)) return false;
        }
        return true;
    }

    private static boolean isReturnFrameStructurallyValid(ModAttachments.FactorySession session,
                                                           ModAttachments.ReturnFrame frame) {
        if (frame.sourceFactoryId().isEmpty()) return frame.sourceFactory() == null;
        return frame.sourceFactory() != null && frame.sourceFactory().isComplete()
                && frame.sourceFactoryId().equals(frame.sourceFactory().factoryId())
                && session.rootFactoryId().equals(frame.sourceFactory().rootFactoryId());
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
        finishSessionWithoutCurrent(player, session);
        if (anchor != null) {
            teleportExactOrSpawn(player, anchor.dimension(), anchor.pos(), anchor.yRot(), anchor.xRot(), reason);
        } else {
            teleportToOverworldSpawn(player, reason);
        }
    }

    private static void teleportExactOrSpawn(ServerPlayer player, ResourceKey<Level> dimension,
                                             net.minecraft.world.phys.Vec3 pos, float yRot, float xRot, String reason) {
        try {
            ServerLevel target = player.server.getLevel(dimension);
            if (target != null) {
                player.fallDistance = 0f;
                player.teleportTo(target, pos.x, pos.y, pos.z, yRot, xRot);
                return;
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed exact Pocket recovery teleport for {}: {}", player.getGameProfile().getName(), reason, exception);
        }
        teleportToOverworldSpawn(player, reason);
    }

    private static void teleportToOverworldSpawn(ServerPlayer player, String reason) {
        try {
            ServerLevel overworld = player.server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.fallDistance = 0f;
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    player.getYRot(), player.getXRot());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed overworld Pocket recovery teleport for {}: {}", player.getGameProfile().getName(), reason, exception);
        }
    }

    private static void finishSessionWithoutCurrent(ServerPlayer player, ModAttachments.FactorySession session) {
        if (session.grantedFlight()) {
            AttributeInstance creativeFlight = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
            if (creativeFlight != null) {
                creativeFlight.removeModifier(EXPLORATION_FLIGHT_ID);
            }
            player.getAbilities().flying = session.originalFlying();
            player.onUpdateAbilities();
        }
        if (session.nightVisionGranted() && !session.originalNightVision()) player.removeEffect(MobEffects.NIGHT_VISION);
        player.setData(ModAttachments.FACTORY_SESSION, ModAttachments.FactorySession.defaults());
    }

    private static void grantExplorationAbilities(ServerPlayer player) {
        AttributeInstance creativeFlight = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
        if (creativeFlight != null) {
            creativeFlight.addOrReplacePermanentModifier(EXPLORATION_FLIGHT);
        }
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
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
            PocketRegistry.NestedSlot slot = PocketRegistry.getNestedSlot(slotX, slotZ);
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
        Set<BlockPos> candidates = PocketRegistry.getRootOriginsInRegion(regionX, regionZ);
        for (BlockPos origin : candidates) {
            PocketRegistry.FactoryLocation loc = PocketRegistry.get(origin);
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
        PocketRegistry.FactoryLocation loc = PocketRegistry.get(roomOrigin);
        if (loc == null && roomOrigin.getY() < LEGACY_ROOT_AREA_Y) {
            int slotX = Math.floorDiv(roomOrigin.getX(), NESTED_SLOT_SIZE);
            int slotZ = Math.floorDiv(roomOrigin.getZ(), NESTED_SLOT_SIZE);
            PocketRegistry.NestedSlot slot = PocketRegistry.getNestedSlot(slotX, slotZ);
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

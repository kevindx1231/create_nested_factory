package com.createnestedfactory.create_nested_factory.block;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class NestedFactoryBlock extends HorizontalKineticBlock implements IBE<NestedFactoryBlockEntity> {
    public static final ResourceKey<Level> POCKET_DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "nested_factory"));
    public static final int ROOT_AREA_Y = 64;
    public static final int ROOT_GRID_SIZE = 64;
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
                        serverPlayer.displayClientMessage(Component.literal("§c该工厂空间已有可进入的嵌套工厂"), false);
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
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    public static void enterFactory(ServerPlayer player, NestedFactoryBlockEntity factory) {
        if (factory == null || !factory.isEnterable()) {
            player.displayClientMessage(Component.literal("§c该工厂不可进入"), false);
            return;
        }
        if (factory.getOperationMode() != OperationMode.CHUNK_LOADED) {
            player.displayClientMessage(Component.literal("§c工厂处于黑盒流程中，暂时无法进入"), false);
            return;
        }
        MinecraftServer server = player.serverLevel().getServer();
        ServerLevel pocketLevel = server.getLevel(POCKET_DIMENSION);
        if (pocketLevel == null) {
            return;
        }

        BlockPos origin = factory.roomOrigin();
        if (pocketLevel.getBlockState(origin).isAir()) {
            buildRoom(pocketLevel, origin, NESTED_ROOM_SIZE);
        }

        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        boolean startingNewSession = !session.isActive();
        if (startingNewSession) {
            boolean mayfly = player.getAbilities().mayfly;
            boolean flying = player.getAbilities().flying;
            boolean originalNightVision = player.hasEffect(MobEffects.NIGHT_VISION);
            session = new ModAttachments.FactorySession(factory.getFactoryId(), factory.getFactoryId(),
                    true, mayfly, flying, true, originalNightVision, new ArrayList<>());
            player.setData(ModAttachments.FACTORY_SESSION, session);
            grantExplorationAbilities(player);
        }

        if (!startingNewSession && !session.currentFactoryId().equals(factory.getParentFactoryId())) {
            player.displayClientMessage(Component.literal("§c无法从当前工厂进入目标嵌套工厂"), false);
            return;
        }

        List<ModAttachments.ReturnFrame> stack = new ArrayList<>(session.stack());
        String sourceFactoryId = startingNewSession ? "" : session.currentFactoryId();
        stack.add(new ModAttachments.ReturnFrame(player.serverLevel().dimension(), player.position(), player.getYRot(), player.getXRot(),
                sourceFactoryId, factory.getFactoryId()));
        player.setData(ModAttachments.FACTORY_SESSION,
                new ModAttachments.FactorySession(session.rootFactoryId(), factory.getFactoryId(),
                        session.grantedFlight(), session.originalMayFly(), session.originalFlying(),
                        session.nightVisionGranted(), session.originalNightVision(), stack));

        factory.onPlayerEntered();
        player.fallDistance = 0f;

        player.teleportTo(pocketLevel,
                origin.getX() + 1.5, origin.getY() + 2.0, origin.getZ() + 1.5,
                player.getYRot(), player.getXRot());
    }

    public static void exitCurrentFactory(ServerPlayer player) {
        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        if (!session.isActive()) {
            return;
        }

        NestedFactoryBlockEntity current = findFactoryAt(player.serverLevel(), player.blockPosition());
        if (current != null) {
            current.onPlayerExited();
        }

        List<ModAttachments.ReturnFrame> stack = new ArrayList<>(session.stack());
        if (stack.isEmpty()) {
            finishSessionWithoutCurrent(player, session);
            return;
        }
        ModAttachments.ReturnFrame frame = stack.remove(stack.size() - 1);
        player.fallDistance = 0f;

        MinecraftServer server = player.serverLevel().getServer();
        ServerLevel target = server.getLevel(frame.dimension());
        if (target == null) {
            target = server.overworld();
        }
        player.teleportTo(target, frame.pos().x, frame.pos().y, frame.pos().z, frame.yRot(), frame.xRot());

        if (frame.sourceFactoryId().isEmpty()) {
            endSessionForPlayer(player);
        } else {
            player.setData(ModAttachments.FACTORY_SESSION,
                    new ModAttachments.FactorySession(session.rootFactoryId(), frame.sourceFactoryId(),
                            session.grantedFlight(), session.originalMayFly(), session.originalFlying(),
                            session.nightVisionGranted(), session.originalNightVision(), stack));
        }
    }

    public static void endSessionForPlayer(ServerPlayer player) {
        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        if (!session.isActive()) {
            player.setData(ModAttachments.FACTORY_SESSION, ModAttachments.FactorySession.defaults());
            return;
        }
        NestedFactoryBlockEntity current = findFactoryAt(player.serverLevel(), player.blockPosition());
        if (current != null) {
            current.onPlayerExited();
        }
        if (session.grantedFlight()) {
            player.getAbilities().mayfly = session.originalMayFly();
            player.getAbilities().flying = session.originalFlying();
            player.onUpdateAbilities();
        }
        if (session.nightVisionGranted() && !session.originalNightVision()) {
            player.removeEffect(MobEffects.NIGHT_VISION);
        }
        player.setData(ModAttachments.FACTORY_SESSION, ModAttachments.FactorySession.defaults());
    }

    /**
     * Releases only the runtime player-presence reference when a player disconnects inside a factory.
     * The serialized session and its temporary exploration state must survive reconnecting.
     */
    public static void suspendSessionForPlayer(ServerPlayer player) {
        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        if (!session.isActive() || !player.serverLevel().dimension().equals(POCKET_DIMENSION)) {
            return;
        }

        NestedFactoryBlockEntity current = findFactoryAt(player.serverLevel(), player.blockPosition());
        if (current != null && current.getFactoryId().equals(session.currentFactoryId())) {
            current.onPlayerExited();
        }
    }

    /**
     * Re-establishes the runtime factory-presence reference after a player reconnects to a valid
     * serialized factory session. Reasserting the temporary abilities also covers any reconnect-time
     * synchronization that removed them.
     */
    public static void resumeSessionForPlayer(ServerPlayer player) {
        ModAttachments.FactorySession session = player.getData(ModAttachments.FACTORY_SESSION);
        if (!session.isActive() || !player.serverLevel().dimension().equals(POCKET_DIMENSION)) {
            return;
        }

        NestedFactoryBlockEntity current = findFactoryAt(player.serverLevel(), player.blockPosition());
        if (current == null || !current.getFactoryId().equals(session.currentFactoryId())) {
            return;
        }

        current.onPlayerEntered();
        grantExplorationAbilities(player);
    }
    private static void finishSessionWithoutCurrent(ServerPlayer player, ModAttachments.FactorySession session) {
        if (session.grantedFlight()) {
            player.getAbilities().mayfly = session.originalMayFly();
            player.getAbilities().flying = session.originalFlying();
            player.onUpdateAbilities();
        }
        if (session.nightVisionGranted() && !session.originalNightVision()) {
            player.removeEffect(MobEffects.NIGHT_VISION);
        }
        player.setData(ModAttachments.FACTORY_SESSION, ModAttachments.FactorySession.defaults());
    }

    private static void grantExplorationAbilities(ServerPlayer player) {
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
    }

    public static BlockPos getPocketOrigin(BlockPos factoryPos) {
        return new BlockPos(factoryPos.getX() * ROOT_GRID_SIZE, ROOT_AREA_Y, factoryPos.getZ() * ROOT_GRID_SIZE);
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
        if (pocketPos.getY() < ROOT_AREA_Y) {
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
            return new BlockPos(slotX * NESTED_SLOT_SIZE, NESTED_AREA_Y, slotZ * NESTED_SLOT_SIZE);
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
        int cx = Math.floorDiv(pocketPos.getX(), ROOT_GRID_SIZE);
        int cz = Math.floorDiv(pocketPos.getZ(), ROOT_GRID_SIZE);
        return new BlockPos(cx * ROOT_GRID_SIZE, ROOT_AREA_Y, cz * ROOT_GRID_SIZE);
    }

    public static NestedFactoryBlockEntity findFactoryAt(ServerLevel pocketLevel, BlockPos pocketPos) {
        BlockPos roomOrigin = findRoomOrigin(pocketLevel, pocketPos);
        PocketRegistry.FactoryLocation loc = PocketRegistry.get(roomOrigin);
        if (loc == null && roomOrigin.getY() < ROOT_AREA_Y) {
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

    public static void buildRoom(ServerLevel level, BlockPos origin, int size) {
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    boolean border = x == 0 || x == size - 1 || y == 0 || y == size - 1 || z == 0 || z == size - 1;
                    level.setBlockAndUpdate(pos, border ? wallState(pos.getX(), pos.getY(), pos.getZ()) : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }
}

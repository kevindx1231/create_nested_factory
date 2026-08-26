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

public class NestedFactoryBlock extends HorizontalKineticBlock implements IBE<NestedFactoryBlockEntity> {
    public static final ResourceKey<Level> POCKET_DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "nested_factory"));

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
        if (level.dimension().equals(POCKET_DIMENSION)) {
            return InteractionResult.PASS;
        }
        if (player.isCrouching()) {
            enterPocket(serverPlayer, pos);
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof NestedFactoryBlockEntity be) {
            serverPlayer.openMenu(be);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    public static void enterPocket(ServerPlayer player, BlockPos factoryPos) {
        if (player.serverLevel().getBlockEntity(factoryPos) instanceof NestedFactoryBlockEntity be
                && be.getOperationMode() != OperationMode.CHUNK_LOADED) {
            player.displayClientMessage(Component.literal("§c工厂处于黑盒流程中，暂时无法进入"), false);
            return;
        }
        MinecraftServer server = player.serverLevel().getServer();
        ServerLevel pocketLevel = server.getLevel(POCKET_DIMENSION);
        if (pocketLevel == null) {
            return;
        }

        BlockPos origin = getPocketOrigin(factoryPos);
        if (pocketLevel.getBlockState(origin).isAir()) {
            buildPocketRoom(pocketLevel, origin);
        }

        if (player.serverLevel().getBlockEntity(factoryPos) instanceof NestedFactoryBlockEntity be) {
            be.onPlayerEntered();
        }

        player.setData(ModAttachments.RETURN_DATA,
                new ModAttachments.ReturnData(player.serverLevel().dimension(), player.position(), player.getYRot(), player.getXRot(),
                        player.getAbilities().mayfly, player.getAbilities().flying));

        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();

        player.teleportTo(pocketLevel,
                origin.getX() + 1.5, origin.getY() + 2.0, origin.getZ() + 1.5,
                player.getYRot(), player.getXRot());
    }

    public static BlockPos getPocketOrigin(BlockPos factoryPos) {
        return new BlockPos(factoryPos.getX() * 64, 64, factoryPos.getZ() * 64);
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
        int cx = Math.floorDiv(pocketPos.getX(), 64);
        int cz = Math.floorDiv(pocketPos.getZ(), 64);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos origin = new BlockPos((cx + dx) * 64, 64, (cz + dz) * 64);
                PocketRegistry.FactoryLocation loc = PocketRegistry.get(origin);
                if (loc == null) {
                    continue;
                }
                ServerLevel factoryLevel = pocketLevel.getServer().getLevel(loc.dimension());
                if (factoryLevel == null) {
                    continue;
                }
                if (factoryLevel.getBlockEntity(loc.pos()) instanceof NestedFactoryBlockEntity be
                        && be.getBounds().contains(origin, pocketPos)) {
                    return origin;
                }
            }
        }
        return new BlockPos(cx * 64, 64, cz * 64);
    }

    public static NestedFactoryBlockEntity findFactoryAt(ServerLevel pocketLevel, BlockPos pocketPos) {
        PocketRegistry.FactoryLocation loc = PocketRegistry.get(findRoomOrigin(pocketLevel, pocketPos));
        if (loc == null) {
            return null;
        }
        ServerLevel factoryLevel = pocketLevel.getServer().getLevel(loc.dimension());
        if (factoryLevel == null) {
            return null;
        }
        return factoryLevel.getBlockEntity(loc.pos()) instanceof NestedFactoryBlockEntity be ? be : null;
    }

    private static void buildPocketRoom(ServerLevel level, BlockPos origin) {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    boolean border = x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15;
                    level.setBlockAndUpdate(pos, border ? wallState(pos.getX(), pos.getY(), pos.getZ()) : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }
}

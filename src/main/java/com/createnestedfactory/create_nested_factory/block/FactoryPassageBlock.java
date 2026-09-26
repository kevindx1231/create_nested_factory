package com.createnestedfactory.create_nested_factory.block;

import com.createnestedfactory.create_nested_factory.FactoryPassageAvailability;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.contraption.ContraptionMovementSetting;

import java.util.EnumMap;
import java.util.Map;

/** Two-block-high, independently opened passage endpoint. The lower half owns all persistent data. */
public final class FactoryPassageBlock extends HorizontalDirectionalBlock implements EntityBlock,
        ContraptionMovementSetting.MovementSettingProvider {
    public static final MapCodec<FactoryPassageBlock> CODEC = simpleCodec(FactoryPassageBlock::new);
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    private static final VoxelShape LOWER_CLOSED_NORTH = Shapes.or(
            box(0, 0, 0, 16, 1, 16),
            box(0, 1, 0, 2, 16, 2), box(14, 1, 0, 16, 16, 2),
            box(0, 1, 14, 2, 16, 16), box(14, 1, 14, 16, 16, 16),
            box(0, 1, 2, 2, 16, 14), box(14, 1, 2, 16, 16, 14),
            box(2, 1, 14, 14, 16, 16), box(2, 1, 1, 14, 16, 2));
    private static final VoxelShape LOWER_OPEN_NORTH = Shapes.or(
            box(0, 0, 0, 16, 1, 16),
            box(0, 1, 0, 2, 16, 2), box(14, 1, 0, 16, 16, 2),
            box(0, 1, 14, 2, 16, 16), box(14, 1, 14, 16, 16, 16),
            box(0, 1, 2, 2, 16, 14), box(14, 1, 2, 16, 16, 14),
            box(2, 1, 14, 14, 16, 16), box(2, 1, 1, 3, 16, 14));
    private static final VoxelShape UPPER_CLOSED_NORTH = Shapes.or(
            box(0, 15, 0, 16, 16, 16),
            box(0, 0, 0, 2, 15, 2), box(14, 0, 0, 16, 15, 2),
            box(0, 0, 14, 2, 15, 16), box(14, 0, 14, 16, 15, 16),
            box(0, 0, 2, 2, 15, 14), box(14, 0, 2, 16, 15, 14),
            box(2, 0, 14, 14, 15, 16), box(2, 0, 1, 14, 15, 2));
    private static final VoxelShape UPPER_OPEN_NORTH = Shapes.or(
            box(0, 15, 0, 16, 16, 16),
            box(0, 0, 0, 2, 15, 2), box(14, 0, 0, 16, 15, 2),
            box(0, 0, 14, 2, 15, 16), box(14, 0, 14, 16, 15, 16),
            box(0, 0, 2, 2, 15, 14), box(14, 0, 2, 16, 15, 14),
            box(2, 0, 14, 14, 15, 16), box(2, 0, 1, 3, 15, 14));
    private static final Map<Direction, VoxelShape> LOWER_CLOSED = rotatedShapes(LOWER_CLOSED_NORTH);
    private static final Map<Direction, VoxelShape> LOWER_OPEN = rotatedShapes(LOWER_OPEN_NORTH);
    private static final Map<Direction, VoxelShape> UPPER_CLOSED = rotatedShapes(UPPER_CLOSED_NORTH);
    private static final Map<Direction, VoxelShape> UPPER_OPEN = rotatedShapes(UPPER_OPEN_NORTH);

    public FactoryPassageBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(OPEN, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public ContraptionMovementSetting getContraptionMovementSetting() {
        return ContraptionMovementSetting.UNMOVABLE;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos above = context.getClickedPos().above();
        if (above.getY() >= context.getLevel().getMaxBuildHeight()
                || !context.getLevel().getBlockState(above).canBeReplaced(context)) {
            return null;
        }
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(OPEN, false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        if (!level.isClientSide() && placer instanceof ServerPlayer player
                && level.getBlockEntity(pos) instanceof FactoryPassageBlockEntity passage) {
            ModAttachments.PassageSelection selection = player.getData(ModAttachments.PASSAGE_SELECTION);
            FactoryPassageBlockEntity.BindingResult result = passage.setBinding(
                    selection.isPresent() ? selection.target() : null);
            if (result == FactoryPassageBlockEntity.BindingResult.BOUND) {
                PlayerMessagePayload.sendTo(player,
                        Component.translatable("message.create_nested_factory.passage.bound")
                                .withStyle(ChatFormatting.AQUA), false);
            } else if (result == FactoryPassageBlockEntity.BindingResult.PHYSICALIZED_PASSAGE) {
                sendPhysicalizedMessage(player, "message.create_nested_factory.passage.physicalized_passage");
            } else if (result == FactoryPassageBlockEntity.BindingResult.PHYSICALIZED_TARGET) {
                sendPhysicalizedMessage(player, "message.create_nested_factory.passage.physicalized_target");
            }
            player.setData(ModAttachments.PASSAGE_SELECTION, ModAttachments.PassageSelection.empty());
        }
    }

    public static boolean isMarkerSlotHit(BlockState state, BlockPos pos, BlockHitResult hitResult) {
        if (!state.is(ModBlocks.FACTORY_PASSAGE.get())
                || state.getValue(HALF) != DoubleBlockHalf.UPPER
                || hitResult.getDirection() != state.getValue(FACING)) {
            return false;
        }

        return FactoryPassageMarkerGeometry.isHit(state.getValue(FACING), pos, hitResult);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                                BlockHitResult hitResult) {
        BlockPos basePos = getBasePos(pos, state);
        if (!level.isClientSide() && level.getBlockEntity(basePos) instanceof FactoryPassageBlockEntity passage
                && FactoryPassageAvailability.isPhysicalized(passage)) {
            if (player instanceof ServerPlayer serverPlayer) {
                sendPhysicalizedMessage(serverPlayer,
                        "message.create_nested_factory.passage.physicalized_passage");
            }
            return InteractionResult.SUCCESS;
        }
        if (player.isCrouching() && player instanceof ServerPlayer serverPlayer
                && level.dimension().equals(NestedFactoryBlock.POCKET_DIMENSION)) {
            if (level.getBlockEntity(basePos) instanceof FactoryPassageBlockEntity passage) {
                passage.tryTravel(serverPlayer);
            }
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide()) {
            BlockState lower = level.getBlockState(basePos);
            if (!lower.is(this)) {
                return InteractionResult.PASS;
            }
            boolean open = !lower.getValue(OPEN);
            setOpen(level, basePos, lower, open);
            level.playSound(null, basePos, open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE,
                    SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return InteractionResult.SUCCESS;
    }

    public static void setOpen(Level level, BlockPos basePos, BlockState lower, boolean open) {
        level.setBlock(basePos, lower.setValue(OPEN, open), Block.UPDATE_CLIENTS);
        BlockState upper = level.getBlockState(basePos.above());
        if (upper.is(ModBlocks.FACTORY_PASSAGE.get()) && upper.getValue(HALF) == DoubleBlockHalf.UPPER) {
            level.setBlock(basePos.above(), upper.setValue(OPEN, open), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide() || !(entity instanceof ServerPlayer player)) {
            return;
        }
        BlockPos basePos = getBasePos(pos, state);
        BlockState lower = level.getBlockState(basePos);
        if (!lower.is(this) || !lower.getValue(OPEN)
                || !(level.getBlockEntity(basePos) instanceof FactoryPassageBlockEntity passage)) {
            return;
        }
        if (FactoryPassageAvailability.isPhysicalized(passage)) return;
        ModAttachments.PassageTravelGuard guard = player.getData(ModAttachments.PASSAGE_TRAVEL_GUARD);
        if (guard.blocks(passage.getPassageId(), level.dimension(), basePos)) {
            ModAttachments.PassageTravelGuard contacted = guard.onCollision(
                    passage.getPassageId(), level.dimension(), basePos, level.getGameTime());
            if (!contacted.equals(guard)) {
                player.setData(ModAttachments.PASSAGE_TRAVEL_GUARD, contacted);
            }
            return;
        }
        if (!isInside(player, basePos)) return;
        passage.tryTravel(player);
    }

    private static void sendPhysicalizedMessage(ServerPlayer player, String key) {
        PlayerMessagePayload.sendTo(player, Component.translatable(key).withStyle(ChatFormatting.RED), false);
    }

    public static void updateTravelGuard(ServerPlayer player) {
        ModAttachments.PassageTravelGuard guard = player.getData(ModAttachments.PASSAGE_TRAVEL_GUARD);
        if (!guard.isActive()) {
            return;
        }
        ModAttachments.PassageTravelGuard updated = guard.afterTick(
                player.serverLevel().dimension(), player.serverLevel().getGameTime());
        if (!updated.equals(guard)) {
            player.setData(ModAttachments.PASSAGE_TRAVEL_GUARD, updated);
        }
    }

    public static boolean isInside(Entity entity, BlockPos basePos) {
        double localX = entity.getX() - basePos.getX();
        double localZ = entity.getZ() - basePos.getZ();
        double localY = entity.getY() - basePos.getY();
        return localX > 0.12 && localX < 0.88 && localZ > 0.12 && localZ < 0.88
                && localY >= 0.0 && localY < 2.0;
    }

    public static BlockPos getBasePos(BlockPos pos, BlockState state) {
        return state.hasProperty(HALF) && state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockPos basePos = getBasePos(pos, state);
            if (!level.isClientSide() && level.getBlockEntity(basePos) instanceof FactoryPassageBlockEntity passage) {
                passage.onDestroyed();
            }
            BlockPos otherPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState other = level.getBlockState(otherPos);
            if (other.is(this) && other.getValue(HALF) != state.getValue(HALF)) {
                level.setBlock(otherPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (!level.isClientSide() && !player.isCreative()) {
            popResource(level, pos, new ItemStack(ModBlocks.FACTORY_PASSAGE.get()));
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER
                ? new FactoryPassageBlockEntity(pos, state)
                : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = shapeFor(state);
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            return Shapes.or(shape, FactoryPassageMarkerGeometry.selectionShape(state.getValue(FACING)));
        }
        return shape;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return shapeFor(state);
    }

    private static VoxelShape shapeFor(BlockState state) {
        boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
        boolean open = state.getValue(OPEN);
        Map<Direction, VoxelShape> shapes = lower
                ? (open ? LOWER_OPEN : LOWER_CLOSED)
                : (open ? UPPER_OPEN : UPPER_CLOSED);
        return shapes.get(state.getValue(FACING));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, OPEN);
    }

    private static Map<Direction, VoxelShape> rotatedShapes(VoxelShape north) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.NORTH, north);
        shapes.put(Direction.EAST, rotateY(north));
        shapes.put(Direction.SOUTH, rotateY(shapes.get(Direction.EAST)));
        shapes.put(Direction.WEST, rotateY(shapes.get(Direction.SOUTH)));
        return shapes;
    }

    private static VoxelShape rotateY(VoxelShape source) {
        final VoxelShape[] result = {Shapes.empty()};
        source.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> result[0] = Shapes.or(result[0],
                Shapes.box(1.0 - maxZ, minY, minX, 1.0 - minZ, maxY, maxX)));
        return result[0];
    }
}

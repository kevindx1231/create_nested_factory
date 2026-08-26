package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;
import java.util.Locale;

public class NestedPortBlockEntity extends SyncedBlockEntity implements IHaveGoggleInformation {
    private int targetPortId = 1;
    private PortMode mappedFaceMode = PortMode.NONE;

    public NestedPortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NESTED_PORT.get(), pos, state);
    }

    public int getTargetPortId() {
        return targetPortId;
    }

    public void cycleTargetPortId() {
        int old = targetPortId;
        targetPortId = targetPortId % 6 + 1;
        refreshMappedMode();
        setChanged();
        sendData();
        if (level != null && !level.isClientSide()) {
            PocketRegistry.unregisterPort(NestedFactoryBlock.findRoomOrigin((ServerLevel) level, worldPosition), old);
            registerPort();
        }
    }

    public IItemHandler getItemHandler() {
        NestedFactoryBlockEntity factory = findFactory();
        return factory == null ? null : factory.getRoomItemHandler(targetPortId);
    }

    public IFluidHandler getFluidHandler() {
        NestedFactoryBlockEntity factory = findFactory();
        return factory == null ? null : factory.getRoomFluidHandler(targetPortId);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(GoggleTooltips.title(getBlockState().getBlock().getName()));
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.target_port",
                String.valueOf(targetPortId), ChatFormatting.AQUA));
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.mapped_mode",
                Component.translatable(modeKey(mappedFaceMode)).withStyle(modeColor(mappedFaceMode))));
        return true;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, NestedPortBlockEntity be) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        PortMode before = be.mappedFaceMode;
        be.refreshMappedMode();
        if (be.mappedFaceMode != before) {
            be.sendData();
        }
    }

    private void refreshMappedMode() {
        NestedFactoryBlockEntity factory = findFactory();
        PortMode mode = PortMode.NONE;
        if (factory != null) {
            Direction face = factory.getFaceForPortId(targetPortId);
            if (face != null) {
                mode = factory.getFaceMode(face);
            }
        }
        if (mode != mappedFaceMode) {
            mappedFaceMode = mode;
            setChanged();
        }
    }

    private static String modeKey(PortMode mode) {
        return switch (mode) {
            case INPUT -> "goggles.create_nested_factory.port_mode.input";
            case OUTPUT -> "goggles.create_nested_factory.port_mode.output";
            case NONE -> "goggles.create_nested_factory.port_mode.none";
        };
    }

    private static ChatFormatting modeColor(PortMode mode) {
        return switch (mode) {
            case INPUT -> ChatFormatting.GREEN;
            case OUTPUT -> ChatFormatting.AQUA;
            case NONE -> ChatFormatting.GRAY;
        };
    }

    private NestedFactoryBlockEntity findFactory() {
        if (level == null || level.isClientSide()) {
            return null;
        }
        return NestedFactoryBlock.findFactoryAt((ServerLevel) level, worldPosition);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            registerPort();
            refreshMappedMode();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) {
            PocketRegistry.unregisterPort(NestedFactoryBlock.findRoomOrigin((ServerLevel) level, worldPosition), targetPortId);
        }
    }

    private void registerPort() {
        if (level == null || level.isClientSide()) {
            return;
        }
        PocketRegistry.registerPort(NestedFactoryBlock.findRoomOrigin((ServerLevel) level, worldPosition), targetPortId, worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("TargetPortId", targetPortId);
        tag.putString("MappedFaceMode", mappedFaceMode.getSerializedName());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        targetPortId = tag.getInt("TargetPortId");
        if (targetPortId < 1 || targetPortId > 6) {
            targetPortId = 1;
        }
        String mode = tag.getString("MappedFaceMode");
        mappedFaceMode = mode.isEmpty() ? PortMode.NONE : PortMode.valueOf(mode.toUpperCase(Locale.ROOT));
    }
}

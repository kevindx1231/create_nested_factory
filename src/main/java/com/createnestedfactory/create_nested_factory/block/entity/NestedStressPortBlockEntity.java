package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;

public class NestedStressPortBlockEntity extends GeneratingKineticBlockEntity {
    private float incomingSpeed = 0f;
    private float incomingCapacity = 0f;

    public NestedStressPortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NESTED_STRESS_PORT.get(), pos, state);
    }

    public void setIncomingSpeed(float speed) {
        if (this.incomingSpeed != speed) {
            this.incomingSpeed = speed;
            updateGeneratedRotation();
            setChanged();
            sendData();
        }
    }

    public void setIncomingCapacity(float capacity) {
        if (this.incomingCapacity != capacity) {
            this.incomingCapacity = capacity;
            updateGeneratedRotation();
            setChanged();
            sendData();
        }
    }

    private BlockPos roomOrigin() {
        return NestedFactoryBlock.findRoomOrigin((ServerLevel) level, worldPosition);
    }

    @Override
    public float getGeneratedSpeed() {
        return incomingSpeed;
    }

    @Override
    public float calculateAddedStressCapacity() {
        return incomingCapacity;
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.putFloat("IncomingSpeed", incomingSpeed);
        tag.putFloat("IncomingCapacity", incomingCapacity);
        super.write(tag, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        incomingSpeed = tag.getFloat("IncomingSpeed");
        incomingCapacity = tag.getFloat("IncomingCapacity");
        super.read(tag, registries, clientPacket);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(GoggleTooltips.title(getBlockState().getBlock().getName()));
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.incoming_speed",
                String.format(Locale.ROOT, "%.1f", incomingSpeed) + " RPM", ChatFormatting.AQUA));
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.incoming_capacity",
                String.format(Locale.ROOT, "%.0f", incomingCapacity * Math.abs(incomingSpeed)) + " su", ChatFormatting.AQUA));
        return true;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            registerStressPort();
        }
    }

    @Override
    public void remove() {
        super.remove();
        if (level != null && !level.isClientSide()) {
            PocketRegistry.unregisterStressPort(roomOrigin(), worldPosition);
        }
    }

    private void registerStressPort() {
        if (level == null || level.isClientSide()) {
            return;
        }
        PocketRegistry.registerStressPort(roomOrigin(), worldPosition);
    }
}

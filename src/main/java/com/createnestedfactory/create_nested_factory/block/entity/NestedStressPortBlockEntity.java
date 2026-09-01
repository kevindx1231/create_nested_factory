package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.KineticNetwork;
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

/**
 * A room-side kinetic source whose complete budget is assigned by its owning factory.
 * The fields below are runtime settlement results, never persisted as server authority.
 */
public class NestedStressPortBlockEntity extends GeneratingKineticBlockEntity {
    private static final float SPEED_EPSILON = 0.0001f;

    /**
     * Sign transform from the external input to this port's local kinetic coordinate system.
     * It is runtime-only: a topology change or reload recalibrates it from the live network.
     */
    private KineticNetwork directionMappingNetwork;
    private float externalToLocalDirection = 1f;
    private boolean hasDirectionMapping = false;
    private int lastExternalDirection = 0;

    private float incomingSpeed = 0f;
    /** Create base capacity; actual supplied SU is incomingCapacity * abs(incomingSpeed). */
    private float incomingCapacity = 0f;
    private float requestedSU = 0f;
    private float allocatedSU = 0f;
    private boolean sourceSatisfied = false;

    public NestedStressPortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NESTED_STRESS_PORT.get(), pos, state);
    }

    /**
     * Atomically applies this port's share of the factory's single external stress reservation.
     * A zero allocation can retain speed so Create builds/measures the internal network without
     * granting a free capacity window.
     */
    public void setStressAllocation(float requestedSU, float allocatedSU, float speed, boolean sourceSatisfied) {
        float safeRequested = Math.max(0f, Float.isFinite(requestedSU) ? requestedSU : 0f);
        float safeAllocated = Math.max(0f, Math.min(safeRequested,
                Float.isFinite(allocatedSU) ? allocatedSU : 0f));
        float safeSpeed = resolveLocalGeneratedSpeed(speed);
        float safeCapacity = Math.abs(safeSpeed) > SPEED_EPSILON
                ? safeAllocated / Math.abs(safeSpeed)
                : 0f;

        boolean rotationChanged = Float.compare(incomingSpeed, safeSpeed) != 0
                || Float.compare(incomingCapacity, safeCapacity) != 0;
        boolean changed = rotationChanged
                || Float.compare(this.requestedSU, safeRequested) != 0
                || Float.compare(this.allocatedSU, safeAllocated) != 0
                || this.sourceSatisfied != sourceSatisfied;
        if (!changed) {
            return;
        }

        incomingSpeed = safeSpeed;
        incomingCapacity = safeCapacity;
        this.requestedSU = safeRequested;
        this.allocatedSU = safeAllocated;
        this.sourceSatisfied = sourceSatisfied;
        if (rotationChanged) {
            updateGeneratedRotation();
        }
        setChanged();
        sendData();
    }

    public void clearStressAllocation() {
        setStressAllocation(0f, 0f, 0f, false);
    }

    /**
     * Preserves the external source's signed direction while retaining the local polarity that the
     * current internal network established through shafts and gearboxes. The first non-zero local
     * speed calibrates a +/-1 transform for this network; later external reversals therefore reverse
     * this port as well, without forcing every port in a geared network to use the same raw sign.
     */
    private float resolveLocalGeneratedSpeed(float externalSpeed) {
        if (!Float.isFinite(externalSpeed) || Math.abs(externalSpeed) <= SPEED_EPSILON) {
            directionMappingNetwork = null;
            externalToLocalDirection = 1f;
            hasDirectionMapping = false;
            lastExternalDirection = 0;
            return 0f;
        }

        int externalDirection = externalSpeed > 0f ? 1 : -1;
        if (!hasNetwork()) {
            // A detached relay is the first source for its network, so it starts in the exact
            // external direction. The mapping will be calibrated after the network is established.
            directionMappingNetwork = null;
            externalToLocalDirection = 1f;
            hasDirectionMapping = false;
            lastExternalDirection = externalDirection;
            return externalSpeed;
        }

        KineticNetwork currentNetwork = getOrCreateNetwork();
        float localSpeed = getTheoreticalSpeed();
        boolean externalDirectionChanged = lastExternalDirection != 0
                && lastExternalDirection != externalDirection;
        if (Math.abs(localSpeed) > SPEED_EPSILON
                && (!hasDirectionMapping
                || (!externalDirectionChanged && directionMappingNetwork != currentNetwork))) {
            externalToLocalDirection = Math.signum(localSpeed) * externalDirection;
            directionMappingNetwork = currentNetwork;
            hasDirectionMapping = true;
        }

        // Do not recalibrate while an external reversal is being propagated: Create may recreate the
        // kinetic network during that update, while its reported local speed still has the old sign.
        lastExternalDirection = externalDirection;
        return externalSpeed * externalToLocalDirection;
    }

    public float getRequestedSU() {
        return requestedSU;
    }

    public float getAllocatedSU() {
        return allocatedSU;
    }

    public boolean isSourceSatisfied() {
        return sourceSatisfied;
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
    public float calculateStressApplied() {
        return 0f;
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        // Preserve only the last local RPM on disk. Capacity and reservation are intentionally
        // not restored: the owning factory must validate and reserve stress again after loading.
        tag.putFloat("IncomingSpeed", Float.isFinite(incomingSpeed) ? incomingSpeed : 0f);
        if (clientPacket) {
            tag.putFloat("IncomingCapacity", incomingCapacity);
            tag.putFloat("RequestedSU", requestedSU);
            tag.putFloat("AllocatedSU", allocatedSU);
            tag.putBoolean("SourceSatisfied", sourceSatisfied);
        }
        super.write(tag, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        float restoredSpeed = tag.getFloat("IncomingSpeed");
        incomingSpeed = Float.isFinite(restoredSpeed) ? restoredSpeed : 0f;
        if (clientPacket) {
            incomingCapacity = tag.getFloat("IncomingCapacity");
            requestedSU = tag.getFloat("RequestedSU");
            allocatedSU = tag.getFloat("AllocatedSU");
            sourceSatisfied = tag.getBoolean("SourceSatisfied");
        } else {
            // Keep rotation available while Create rebuilds the loaded kinetic network, but do
            // not restore any reservation or capacity until the factory settles this port again.
            incomingCapacity = 0f;
            requestedSU = 0f;
            allocatedSU = 0f;
            sourceSatisfied = false;
            lastCapacityProvided = 0f;
            lastStressApplied = 0f;
        }
        super.read(tag, registries, clientPacket);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(GoggleTooltips.title(getBlockState().getBlock().getName()));
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.incoming_speed",
                String.format(Locale.ROOT, "%.1f", incomingSpeed) + " RPM", ChatFormatting.AQUA));
        tooltip.add(GoggleTooltips.stat("goggles.create_nested_factory.incoming_capacity",
                String.format(Locale.ROOT, "%.0f", allocatedSU) + " su", ChatFormatting.AQUA));
        tooltip.add(GoggleTooltips.message("goggles.create_nested_factory.stress_port.load_input",
                0xC7954B));
        return true;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            // Do not clear the restored RPM here. Create is still rebuilding the surrounding
            // kinetic network; the factory will replace it with a validated allocation on tick.
            registerStressPort();
        }
    }

    @Override
    public void remove() {
        super.remove();
        if (level != null && !level.isClientSide()) {
            BlockPos roomOrigin = roomOrigin();
            if (roomOrigin != null) {
                PocketRegistry.unregisterStressPort(roomOrigin, worldPosition);
            }
        }
    }

    private void registerStressPort() {
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockPos roomOrigin = roomOrigin();
        if (roomOrigin != null) {
            PocketRegistry.registerStressPort(roomOrigin, worldPosition);
        }
    }
}

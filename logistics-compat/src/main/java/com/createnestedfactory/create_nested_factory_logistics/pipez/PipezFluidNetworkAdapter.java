package com.createnestedfactory.create_nested_factory_logistics.pipez;

import com.createnestedfactory.create_nested_factory.block.entity.FluidNetworkEndpointResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Dedicated Pipez fluid integration. Pipez owns its own pull network and intentionally does not
 * expose a usable IFluidHandler on every pipe segment, so this adapter reports a connected Pipez
 * fluid network to the existing nested-factory fluid bridge without bypassing Pipez transfer.
 */
public final class PipezFluidNetworkAdapter implements FluidNetworkEndpointResolver.FluidNetworkAdapter {
    public static final PipezFluidNetworkAdapter INSTANCE = new PipezFluidNetworkAdapter();

    private static final String PIPE_BLOCK_CLASS = "de.maxhenkel.pipez.blocks.PipeBlock";
    private static final String FLUID_PIPE_CLASS = "de.maxhenkel.pipez.blocks.FluidPipeBlock";
    private static final String UNIVERSAL_PIPE_CLASS = "de.maxhenkel.pipez.blocks.UniversalPipeBlock";

    private volatile Boolean available;
    private volatile Class<?> pipeBlockClass;
    private volatile Class<?> fluidPipeClass;
    private volatile Class<?> universalPipeClass;
    private volatile Method isConnected;

    private PipezFluidNetworkAdapter() {
    }

    @Override
    public List<net.neoforged.neoforge.fluids.capability.IFluidHandler> find(
            Level level, BlockPos sourcePos, Direction startSide,
            net.neoforged.neoforge.fluids.FluidStack request,
            FluidNetworkEndpointResolver.Operation operation) {
        // Pipez must keep ownership of its network transfer. The core bridge uses hasEndpoint()
        // to permit its shared handoff ledger instead of directly injecting into Pipez.
        return List.of();
    }

    @Override
    public List<net.neoforged.neoforge.fluids.capability.IFluidHandler> findDrain(
            Level level, BlockPos sourcePos, Direction startSide, int maxDrain) {
        return List.of();
    }

    @Override
    public boolean hasEndpoint(Level level, BlockPos boundaryPos, Direction side, int portId) {
        if (level == null || boundaryPos == null || side == null || !isPipezAvailable()) {
            return false;
        }
        BlockPos pipePos = boundaryPos.relative(side);
        Block block = level.getBlockState(pipePos).getBlock();
        if (!pipeBlockClass.isInstance(block)
                || (!fluidPipeClass.isInstance(block) && !universalPipeClass.isInstance(block))) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isConnected.invoke(block, level, pipePos, side.getOpposite()));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private boolean isPipezAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (available != null) {
                return available;
            }
            if (!ModList.get().isLoaded("pipez")) {
                available = false;
                return false;
            }
            try {
                pipeBlockClass = Class.forName(PIPE_BLOCK_CLASS);
                fluidPipeClass = Class.forName(FLUID_PIPE_CLASS);
                universalPipeClass = Class.forName(UNIVERSAL_PIPE_CLASS);
                isConnected = pipeBlockClass.getMethod(
                        "isConnected", Level.class, BlockPos.class, Direction.class);
                available = true;
            } catch (ReflectiveOperationException | LinkageError error) {
                available = false;
            }
            return available;
        }
    }
}


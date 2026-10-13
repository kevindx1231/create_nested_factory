package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.entity.FluidNetworkEndpointResolver;
import com.createnestedfactory.create_nested_factory_logistics.pipez.PipezFluidNetworkAdapter;
import de.maxhenkel.pipez.blocks.FluidPipeBlock;
import de.maxhenkel.pipez.blocks.PipeBlock;
import de.maxhenkel.pipez.blocks.UniversalPipeBlock;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real adapter's initialization/reflection and endpoint gating with Pipez's 1.20.1 API shape. */
public final class PipezFluidAdapterRegression {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ModList mods = mock(ModList.class);
        when(mods.isLoaded("pipez")).thenReturn(true);
        try (var loader = mockStatic(ModList.class)) {
            loader.when(ModList::get).thenReturn(mods);
            for (PipeBlock pipe : new PipeBlock[]{mock(FluidPipeBlock.class), mock(UniversalPipeBlock.class)}) {
                var adapter = adapter();
                Level level = mock(Level.class);
                BlockState state = mock(BlockState.class);
                when(state.getBlock()).thenReturn(pipe);
                when(level.getBlockState(any())).thenReturn(state);
                BlockPos boundary = new BlockPos(10, 64, 10);
                FluidNetworkEndpointResolver.registerAdapter(adapter);
                try {
                    for (Direction side : Direction.values()) {
                        BlockPos pipePos = boundary.relative(side);
                        when(pipe.isConnected(any(LevelAccessor.class), any(), any())).thenReturn(false);
                        require(!adapter.hasEndpoint(level, boundary, side, 1), "disconnected Pipez side counted as an endpoint");
                        when(pipe.isConnected(level, pipePos, side.getOpposite())).thenReturn(true);
                        require(FluidNetworkEndpointResolver.hasEndpoint(level, boundary, side, 1),
                                "connected Pipez endpoint missing: reflection signature or queried face is wrong");
                        verify(pipe, atLeastOnce()).isConnected(level, pipePos, side.getOpposite());
                    }
                    require(adapter.findDrain(level, boundary, Direction.NORTH, 1000).isEmpty(), "adapter bypassed Pipez pull ownership");
                    when(state.getBlock()).thenReturn(mock(Block.class));
                    require(!adapter.hasEndpoint(level, boundary, Direction.NORTH, 1), "non-Pipez block counted as an endpoint");
                } finally {
                    FluidNetworkEndpointResolver.unregisterAdapter(adapter);
                }
            }
            when(mods.isLoaded("pipez")).thenReturn(false);
            require(!adapter().hasEndpoint(mock(Level.class), BlockPos.ZERO, Direction.NORTH, 1), "optional adapter required Pipez when absent");
        }
        System.out.println("Pipez 1.20.1 reflection, fluid/universal endpoint directions and optional-loading regressions passed.");
    }

    private static PipezFluidNetworkAdapter adapter() throws Exception {
        var constructor = PipezFluidNetworkAdapter.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

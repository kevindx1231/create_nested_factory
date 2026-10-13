package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryLogicalPortEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat;
import com.createnestedfactory.create_nested_factory_logistics.MekanismExtensionDelegate;
import com.createnestedfactory.create_nested_factory_logistics.LiveMekanismEndpoint;
import com.createnestedfactory.create_nested_factory_logistics.heat.MekanismHeatHandler;
import mekanism.api.heat.IHeatHandler;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.util.LazyOptional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

/** Checks the real thermal bridge, including same-tick topology changes and live thermal values. */
public final class MekanismHeatRegression {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var capabilities = mockStatic(CapabilityManager.class)) {
            capabilities.when(() -> CapabilityManager.get(any(CapabilityToken.class)))
                    .thenAnswer(call -> mock(net.minecraftforge.common.capabilities.Capability.class));
            Fixture f = new Fixture();
            IHeatHandler bridge = MekanismHeatHandler.forPort(f.port, Direction.NORTH);
            require(bridge != null && bridge.getHeatCapacitorCount() == 1, "thermal endpoint absent");
            require(bridge.getTemperature(0) == 450 && bridge.getHeatCapacity(0) == 40
                    && bridge.getInverseConduction(0) == 5, "weighted thermal values changed");
            bridge.handleHeat(0, 40);
            verify(f.first).handleHeat(0, 10);
            verify(f.second).handleHeat(0, 30);
            verify(f.first, times(1)).getHeatCapacitorCount();
            verify(f.second, times(1)).getHeatCapacitorCount();

            when(f.first.getTemperature(0)).thenReturn(700.0);
            require(bridge.getTemperature(0) == 550, "temperature was cached across operations");
            when(f.first.getHeatCapacity(0)).thenReturn(30.0);
            require(bridge.getHeatCapacity(0) == 60, "heat capacity was cached across operations");
            verify(f.first, times(1)).getHeatCapacitorCount();

            f.tick.incrementAndGet();
            bridge.getTemperature(0);
            verify(f.first, times(2)).getHeatCapacitorCount();

            // A capability invalidation must be visible even before the world advances a tick.
            f.firstCapability.invalidate();
            when(f.firstBlock.getCapability(MekanismLogisticsCompat.HEAT, Direction.SOUTH))
                    .thenReturn(LazyOptional.empty());
            require(bridge.getHeatCapacity(0) == 30 && bridge.getTemperature(0) == 500,
                    "invalidated heat capability remained in the same-tick snapshot");

            // Replacement must also be detected when the old block forgot to invalidate its capability.
            when(f.world.getBlockEntity(BlockPos.ZERO.relative(Direction.SOUTH))).thenReturn(null);
            require(bridge.getHeatCapacitorCount() == 0, "removed thermal block remained a target");
            when(f.world.getBlockEntity(BlockPos.ZERO.relative(Direction.SOUTH))).thenReturn(f.secondBlock);
            require(bridge.getHeatCapacitorCount() == 1, "new endpoint was hidden by an empty snapshot");

            clearInvocations(f.second);
            f.revision.incrementAndGet();
            bridge.getTemperature(0);
            verify(f.second, times(1)).getHeatCapacitorCount();
            when(f.factory.isLiveResourceTransferMode()).thenReturn(false);
            require(bridge.getHeatCapacitorCount() == 0, "disabled mode retained a thermal endpoint");
            clearInvocations(f.first, f.second);
            bridge.handleHeat(0, 20);
            verifyNoInteractions(f.first, f.second);
            when(f.factory.isLiveResourceTransferMode()).thenReturn(true);
            require(bridge.getHeatCapacitorCount() == 1, "thermal bridge failed to recover after enable");
            delegateReuse();
        }
        System.out.println("Thermal conservation, live values and endpoint snapshot/invalidation regressions passed.");
    }

    private static void delegateReuse() {
        var extension = mock(NestedExtensionInterfaceBlockEntity.class);
        var factory = mock(NestedFactoryBlockEntity.class);
        when(factory.isLiveResourceTransferMode()).thenReturn(true);
        var endpoint = new FactoryLogicalPortEndpoint(factory, Direction.NORTH, PortMode.INPUT, 1);
        var binding = new AtomicReference<>(endpoint);
        when(extension.resolvePort(Direction.EAST)).thenAnswer(call -> binding.get());
        var creations = new AtomicInteger();
        var delegates = new MekanismExtensionDelegate<LiveMekanismEndpoint>(extension, Direction.EAST, ignored -> {
            creations.incrementAndGet();
            return () -> true;
        });
        var first = delegates.get();
        for (int i = 0; i < 100; i++) require(delegates.get() == first, "extension recreated an unchanged delegate");
        require(creations.get() == 1, "extension allocation count increased on cache hits");
        binding.set(null);
        require(delegates.get() == null, "redstone/unbound extension retained a delegate");
        binding.set(endpoint);
        require(delegates.get() != null && creations.get() == 2, "extension failed to rebuild after enable");
        binding.set(new FactoryLogicalPortEndpoint(factory, Direction.NORTH, PortMode.OUTPUT, 2));
        require(delegates.get() != null && creations.get() == 3, "routing change did not replace the delegate");
        when(factory.isRemoved()).thenReturn(true);
        require(delegates.get() == null, "removed host retained an extension delegate");
    }

    private static final class Fixture {
        final NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        final NestedPortBlockEntity port = mock(NestedPortBlockEntity.class);
        final ServerLevel world = mock(ServerLevel.class);
        final BlockEntity firstBlock = mock(BlockEntity.class);
        final BlockEntity secondBlock = mock(BlockEntity.class);
        final IHeatHandler first = mock(IHeatHandler.class);
        final IHeatHandler second = mock(IHeatHandler.class);
        final LazyOptional<IHeatHandler> firstCapability = LazyOptional.of(() -> first);
        final AtomicLong tick = new AtomicLong(100);
        final AtomicLong revision = new AtomicLong();

        Fixture() {
            when(factory.getLevel()).thenReturn(world);
            when(world.isClientSide()).thenReturn(false);
            when(world.hasChunkAt(any())).thenReturn(true);
            when(world.getGameTime()).thenAnswer(call -> tick.get());
            when(factory.getLogisticsRevision()).thenAnswer(call -> revision.get());
            when(factory.isLiveResourceTransferMode()).thenReturn(true);
            when(port.getFactory()).thenReturn(factory);
            when(port.getTargetPortId()).thenReturn(1);
            when(factory.resolveLogicalPort(1)).thenReturn(new FactoryLogicalPortEndpoint(
                    factory, Direction.NORTH, PortMode.INPUT, 1));
            when(factory.getFacesForPortId(1)).thenReturn(List.of(Direction.NORTH, Direction.SOUTH));
            for (Direction side : List.of(Direction.NORTH, Direction.SOUTH)) {
                when(factory.getExternalAccessPoints(side)).thenReturn(List.of(
                        new NestedFactoryBlockEntity.ExternalAccessPoint(BlockPos.ZERO, side)));
            }
            when(world.getBlockEntity(BlockPos.ZERO.relative(Direction.NORTH))).thenReturn(firstBlock);
            when(world.getBlockEntity(BlockPos.ZERO.relative(Direction.SOUTH))).thenReturn(secondBlock);
            when(firstBlock.getCapability(MekanismLogisticsCompat.HEAT, Direction.SOUTH)).thenReturn(firstCapability);
            when(secondBlock.getCapability(MekanismLogisticsCompat.HEAT, Direction.NORTH)).thenReturn(LazyOptional.of(() -> second));
            when(first.getHeatCapacitorCount()).thenReturn(1);
            when(second.getHeatCapacitorCount()).thenReturn(1);
            when(first.getHeatCapacity(0)).thenReturn(10.0);
            when(second.getHeatCapacity(0)).thenReturn(30.0);
            when(first.getTemperature(0)).thenReturn(300.0);
            when(second.getTemperature(0)).thenReturn(500.0);
            when(first.getInverseConduction(0)).thenReturn(2.0);
            when(second.getInverseConduction(0)).thenReturn(6.0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

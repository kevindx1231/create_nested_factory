package com.createnestedfactory.create_nested_factory.regression;

import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryLogicalPortEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat;
import com.createnestedfactory.create_nested_factory_logistics.heat.MekanismHeatHandler;
import com.createnestedfactory.create_nested_factory_logistics.heat.MekanismExtensionHeatHandler;
import mekanism.api.heat.IHeatHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class MekanismHeatRegression {
    public static void main(String[] args) throws Exception {
        MekanismLogisticsRegression.bootstrap();
        for (boolean external : new boolean[]{false, true}) lifecycle(external);
        extensionLifecycle();
        System.out.println("Mekanism heat conservation and endpoint lifecycle regressions passed (both sides and extension).");
    }

    private static void lifecycle(boolean external) {
        Fixture f = new Fixture(external);
        int endpoints = external ? 6 : 1;
        IHeatHandler bridge = external ? MekanismHeatHandler.forFactory(f.factory, Direction.NORTH)
                : MekanismHeatHandler.forPort(f.port, Direction.NORTH);
        for (int i = 0; i < 100; i++) {
            bridge.getHeatCapacitorCount();
            require(bridge.getTemperature(0) == 500, "temperature weighting changed");
            require(bridge.getHeatCapacity(0) == 40, "capacity changed");
        }
        require(f.queries == endpoints, "same tick repeatedly queried endpoints: " + f.queries);
        f.capacity[0] = 20;
        f.capacity[1] = 20;
        f.temperature[0] = 400;
        require(bridge.getTemperature(0) == 500, "cached snapshot hid live temperature/capacity");
        bridge.handleHeat(0, 100);
        require(f.transferred[0] == 50 && f.transferred[1] == 50, "heat transfer did not conserve 100 units");
        f.tick++;
        bridge.getTemperature(0);
        require(f.queries == endpoints * 2, "next tick did not refresh endpoints");
        f.listeners.forEach(ICapabilityInvalidationListener::onInvalidate);
        bridge.getTemperature(0);
        require(f.queries == endpoints * 3, "same tick capability invalidation was ignored");
        f.neighbor = mock(BlockEntity.class);
        bridge.getTemperature(0);
        require(f.queries == endpoints * 4, "replacement block entity retained stale endpoint");
        f.factory.invalidateLogisticsTopology();
        bridge.getTemperature(0);
        require(f.queries == endpoints * 5, "topology generation did not refresh endpoints");
        f.loaded = false;
        require(bridge.getHeatCapacitorCount() == 0, "unloaded endpoint remained usable");
        f.loaded = true;
        require(bridge.getHeatCapacitorCount() == 1, "reloaded endpoint did not recover");
        when(f.factory.isLiveResourceTransferMode()).thenReturn(false);
        double before = f.transferred[0] + f.transferred[1];
        bridge.handleHeat(0, 100);
        require(bridge.getHeatCapacitorCount() == 0 && f.transferred[0] + f.transferred[1] == before,
                "disabled factory transferred heat");
        when(f.factory.isLiveResourceTransferMode()).thenReturn(true);
        require(bridge.getHeatCapacitorCount() == 1, "factory did not recover after enabling");
        PocketRegistry.releaseServer(f.server);
    }

    private static void extensionLifecycle() {
        NestedExtensionInterfaceBlockEntity extension = mock(NestedExtensionInterfaceBlockEntity.class);
        NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        when(factory.isLiveResourceTransferMode()).thenReturn(true);
        var route = new FactoryLogicalPortEndpoint(factory, Direction.NORTH, PortMode.INPUT, 1);
        when(extension.isExternalSideAvailable(Direction.UP)).thenReturn(true);
        when(extension.resolvePort(Direction.UP)).thenReturn(route);
        IHeatHandler delegate = mock(IHeatHandler.class);
        when(delegate.getHeatCapacitorCount()).thenReturn(1);
        try (var handlers = mockStatic(MekanismHeatHandler.class)) {
            handlers.when(() -> MekanismHeatHandler.forExtension(route)).thenReturn(delegate);
            IHeatHandler proxy = MekanismExtensionHeatHandler.create(extension, Direction.UP);
            for (int i = 0; i < 100; i++) require(proxy.getHeatCapacitorCount() == 1, "extension lost heat endpoint");
            handlers.verify(() -> MekanismHeatHandler.forExtension(route), times(1));
            when(extension.resolvePort(Direction.UP)).thenReturn(null);
            proxy.handleHeat(0, 100);
            verify(delegate, never()).handleHeat(anyInt(), anyDouble());
            require(proxy.getHeatCapacitorCount() == 0, "disabled extension exposed heat endpoint");
            when(extension.resolvePort(Direction.UP)).thenReturn(route);
            require(proxy.getHeatCapacitorCount() == 1, "reenabled heat extension did not recover");
            handlers.verify(() -> MekanismHeatHandler.forExtension(route), times(2));
            when(extension.isRemoved()).thenReturn(true);
            require(proxy.getHeatCapacitorCount() == 0, "removed extension retained heat delegate");
        }
    }

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        final NestedPortBlockEntity port = mock(NestedPortBlockEntity.class);
        final List<ICapabilityInvalidationListener> listeners = new ArrayList<>();
        final double[] capacity = {10, 30};
        final double[] temperature = {200, 600};
        final double[] transferred = {0, 0};
        BlockEntity neighbor = mock(BlockEntity.class);
        long tick = 1;
        int queries;
        boolean loaded = true;

        Fixture(boolean external) {
            when(factory.getLevel()).thenReturn(level);
            when(factory.getBlockPos()).thenReturn(BlockPos.ZERO);
            when(factory.roomOrigin()).thenReturn(BlockPos.ZERO);
            when(factory.isLiveResourceTransferMode()).thenReturn(true);
            when(factory.getLogisticsTopologyVersion()).thenCallRealMethod();
            doCallRealMethod().when(factory).invalidateLogisticsTopology();
            when(factory.getFacesForPortId(1)).thenReturn(List.of(Direction.NORTH));
            when(factory.getExternalAccessPoints(Direction.NORTH)).thenReturn(List.of(
                    new NestedFactoryBlockEntity.ExternalAccessPoint(BlockPos.ZERO, Direction.NORTH)));
            when(factory.resolveLogicalPort(1)).thenReturn(
                    new FactoryLogicalPortEndpoint(factory, Direction.NORTH, PortMode.INPUT, 1));
            when(factory.resolveLogicalPort(Direction.NORTH)).thenReturn(
                    new FactoryLogicalPortEndpoint(factory, Direction.NORTH, PortMode.OUTPUT, 1));
            when(factory.getFaceMode(Direction.NORTH)).thenReturn(PortMode.OUTPUT);
            when(factory.getPortId(Direction.NORTH)).thenReturn(1);
            when(factory.getPocketLevel()).thenReturn(level);
            when(level.getServer()).thenReturn(server);
            when(port.getFactory()).thenReturn(factory);
            when(port.getTargetPortId()).thenReturn(1);
            when(level.getGameTime()).thenAnswer(call -> tick);
            when(level.isLoaded(any(BlockPos.class))).thenAnswer(call -> loaded);
            when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.defaultBlockState());
            BlockPos portPos = new BlockPos(10, 0, 0);
            when(level.getBlockEntity(any(BlockPos.class))).thenAnswer(call ->
                    external && portPos.equals(call.getArgument(0)) ? port : neighbor);
            if (external) PocketRegistry.registerPort(server, BlockPos.ZERO, 1, portPos);
            doAnswer(call -> { listeners.add(call.getArgument(1)); return null; })
                    .when(level).registerCapabilityListener(any(BlockPos.class), any());
            IHeatHandler source = mock(IHeatHandler.class);
            when(source.getHeatCapacitorCount()).thenReturn(2);
            when(source.getHeatCapacity(anyInt())).thenAnswer(call -> capacity[(int) call.getArgument(0)]);
            when(source.getTemperature(anyInt())).thenAnswer(call -> temperature[(int) call.getArgument(0)]);
            when(source.getInverseConduction(anyInt())).thenReturn(1.0);
            doAnswer(call -> { transferred[(int) call.getArgument(0)] += (double) call.getArgument(1); return null; })
                    .when(source).handleHeat(anyInt(), anyDouble());
            when(level.getCapability(eq(MekanismLogisticsCompat.HEAT), any(BlockPos.class), any(Direction.class)))
                    .thenAnswer(call -> { queries++; return source; });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

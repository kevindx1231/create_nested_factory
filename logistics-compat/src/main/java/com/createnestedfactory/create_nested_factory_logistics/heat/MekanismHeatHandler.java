package com.createnestedfactory.create_nested_factory_logistics.heat;

import com.createnestedfactory.create_nested_factory_logistics.LiveMekanismEndpoint;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryLogicalPortEndpoint;
import com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat;
import mekanism.api.heat.HeatAPI;
import mekanism.api.heat.IHeatHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.LazyOptional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Live, synchronous Mekanism thermal bridge. It intentionally has no cross-tick heat buffer. */
public final class MekanismHeatHandler implements IHeatHandler, LiveMekanismEndpoint {
    private final NestedFactoryBlockEntity factory;
    private final int portId;
    private final boolean externalSide;
    private final Direction externalFace;
    private final boolean extensionAccess;
    private NestedPortBlockEntity roomPort;
    private long cachedTick = Long.MIN_VALUE;
    private long cachedRevision = Long.MIN_VALUE;
    private List<Target> cachedTargets;
    private List<Connection> cachedConnections = List.of();
    private Set<BlockPos> cachedRoomPorts = Set.of();
    private VirtualHeatCapacitor cachedCapacitor;

    private MekanismHeatHandler(NestedFactoryBlockEntity factory, int portId, boolean externalSide,
                                Direction externalFace, boolean extensionAccess) {
        this.factory = factory;
        this.portId = portId;
        this.externalSide = externalSide;
        this.externalFace = externalFace;
        this.extensionAccess = extensionAccess;
    }

    public static IHeatHandler forFactory(NestedFactoryBlockEntity factory, Direction side) {
        if (factory == null || side == null || !factory.isLiveResourceTransferMode()
                || factory.isFaceTakenOver(side)) {
            return null;
        }
        return createExternal(factory.resolveLogicalPort(side), false);
    }

    public static MekanismHeatHandler forExtension(FactoryLogicalPortEndpoint endpoint) {
        if (endpoint == null || !endpoint.factory().isLiveResourceTransferMode()) {
            return null;
        }
        return createExternal(endpoint, true);
    }

    private static MekanismHeatHandler createExternal(FactoryLogicalPortEndpoint endpoint,
                                               boolean extensionAccess) {
        if (endpoint == null || !endpoint.isConfigured()) return null;
        MekanismHeatHandler handler = new MekanismHeatHandler(endpoint.factory(), endpoint.portId(), true,
                endpoint.face(), extensionAccess);
        return handler.hasEndpoints() ? handler : null;
    }

    public static IHeatHandler forPort(NestedPortBlockEntity port, Direction side) {
        if (port == null || side == null) {
            return null;
        }
        NestedFactoryBlockEntity factory = port.getFactory();
        if (factory == null || !factory.isLiveResourceTransferMode()) {
            return null;
        }
        FactoryLogicalPortEndpoint endpoint = factory.resolveLogicalPort(port.getTargetPortId());
        if (endpoint == null || !endpoint.isConfigured()) return null;
        MekanismHeatHandler handler = new MekanismHeatHandler(factory, endpoint.portId(), false,
                null, false);
        handler.roomPort = port;
        return handler.hasEndpoints() ? handler : null;
    }

    private boolean active() {
        if (factory.isRemoved() || !factory.isLiveResourceTransferMode()) {
            return false;
        }
        if (!externalSide) {
            if (roomPort != null && (roomPort.isRemoved() || roomPort.getFactory() != factory
                    || roomPort.getTargetPortId() != portId)) return false;
            FactoryLogicalPortEndpoint endpoint = factory.resolveLogicalPort(portId);
            return endpoint != null && endpoint.isConfigured();
        }
        return externalFace == null
                || (factory.getFaceMode(externalFace) != PortMode.NONE
                && factory.getPortId(externalFace) == portId
                && (extensionAccess || !factory.isFaceTakenOver(externalFace)));
    }

    @Override
    public int getHeatCapacitorCount() {
        return hasEndpoints() ? 1 : 0;
    }

    @Override
    public double getTemperature(int capacitor) {
        return active() && capacitor == 0
                ? virtualCapacitor().getTemperature() : HeatAPI.AMBIENT_TEMP;
    }

    @Override
    public double getInverseConduction(int capacitor) {
        return active() && capacitor == 0 ? virtualCapacitor().getInverseConduction()
                : HeatAPI.DEFAULT_INVERSE_CONDUCTION;
    }

    @Override
    public double getHeatCapacity(int capacitor) {
        return active() && capacitor == 0
                ? virtualCapacitor().getHeatCapacity() : HeatAPI.DEFAULT_HEAT_CAPACITY;
    }

    @Override
    public void handleHeat(int capacitor, double transfer) {
        if (active() && capacitor == 0 && Double.isFinite(transfer) && transfer != 0) {
            virtualCapacitor().handleHeat(transfer);
        }
    }

    @Override
    public boolean isAvailable() {
        return active() && !resolveTargets().isEmpty();
    }

    private boolean hasEndpoints() {
        return isAvailable();
    }

    private VirtualHeatCapacitor virtualCapacitor() {
        List<Target> targets = resolveTargets();
        if (cachedCapacitor == null || cachedCapacitor.targets != targets) {
            cachedCapacitor = new VirtualHeatCapacitor(targets, factory);
        }
        return cachedCapacitor;
    }

    private List<Target> resolveTargets() {
        var level = factory.getLevel();
        if (level == null || !active()) {
            cachedTargets = null;
            return List.of();
        }
        ServerLevel pocket = factory.getPocketLevel();
        Set<BlockPos> roomPorts = externalSide && pocket != null
                ? PocketRegistry.getPorts(pocket.getServer(), factory.roomOrigin(), portId) : Set.of();
        long tick = level.getGameTime();
        long revision = factory.getLogisticsRevision();
        if (cachedTargets != null && cachedTick == tick && cachedRevision == revision
                && cachedRoomPorts.equals(roomPorts) && cachedConnections.stream().allMatch(Connection::valid)) {
            return cachedTargets;
        }
        cachedConnections = new ArrayList<>();
        cachedTargets = scanTargets();
        cachedRoomPorts = roomPorts;
        cachedTick = tick;
        cachedRevision = revision;
        return cachedTargets;
    }

    private List<Target> scanTargets() {
        List<Target> targets = new ArrayList<>();
        Set<IHeatHandler> seenHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IHeatHandler> handlers = externalSide ? roomHandlers() : externalHandlers();
        for (IHeatHandler handler : handlers) {
            if (!seenHandlers.add(handler) || handler instanceof MekanismHeatHandler
                    || handler instanceof MekanismExtensionHeatHandler) {
                continue;
            }
            try {
                int capacitors = handler.getHeatCapacitorCount();
                for (int index = 0; index < capacitors; index++) {
                    targets.add(new Target(handler, index));
                }
            } catch (RuntimeException ignored) {
                // Optional or transient endpoints must not break the remaining bridge targets.
            }
        }
        return targets;
    }

    private List<IHeatHandler> externalHandlers() {
        if (factory.getLevel() == null || factory.getLevel().isClientSide()) {
            return List.of();
        }
        List<IHeatHandler> handlers = new ArrayList<>();
        Set<IHeatHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction face : factory.getFacesForPortId(portId)) {
            for (NestedFactoryBlockEntity.ExternalAccessPoint access : factory.getExternalAccessPoints(face)) {
                BlockPos pos = access.origin().relative(access.face());
                IHeatHandler handler = connectedHandler(factory.getLevel(), pos, access.face().getOpposite());
                if (handler != null && seen.add(handler)) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
    }

    private List<IHeatHandler> roomHandlers() {
        ServerLevel pocket = factory.getPocketLevel();
        if (pocket == null) {
            return List.of();
        }
        List<IHeatHandler> handlers = new ArrayList<>();
        Set<IHeatHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), factory.roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos pos = portPos.relative(side);
                IHeatHandler handler = connectedHandler(pocket, pos, side.getOpposite());
                if (handler != null && seen.add(handler)) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
    }

    private IHeatHandler connectedHandler(Level level, BlockPos pos, Direction side) {
        if (!level.hasChunkAt(pos)) return null;
        var blockEntity = level.getBlockEntity(pos);
        var capability = blockEntity == null || blockEntity.isRemoved()
                ? LazyOptional.<IHeatHandler>empty()
                : blockEntity.getCapability(MekanismLogisticsCompat.HEAT, side);
        IHeatHandler handler = capability.orElse(null);
        cachedConnections.add(new Connection(level, pos, blockEntity, capability, handler != null));
        return handler;
    }

    private record Connection(Level level, BlockPos pos,
                              BlockEntity blockEntity,
                              LazyOptional<IHeatHandler> capability, boolean present) {
        boolean valid() {
            return level.hasChunkAt(pos) && level.getBlockEntity(pos) == blockEntity
                    && (blockEntity == null || !blockEntity.isRemoved()) && capability.isPresent() == present;
        }
    }

    private record Target(IHeatHandler handler, int index) {
    }

    private static final class VirtualHeatCapacitor {
        private final List<Target> targets;
        private final NestedFactoryBlockEntity factory;

        private VirtualHeatCapacitor(List<Target> targets, NestedFactoryBlockEntity factory) {
            this.targets = targets;
            this.factory = factory;
        }

        private double getTemperature() {
            double totalCapacity = 0;
            double weighted = 0;
            for (Target target : targets) {
                double capacity = capacity(target);
                totalCapacity += capacity;
                weighted += target.handler().getTemperature(target.index()) * capacity;
            }
            return weighted / Math.max(1, totalCapacity);
        }

        private double getHeatCapacity() {
            double result = 0;
            for (Target target : targets) {
                result += capacity(target);
            }
            return Math.max(1, result);
        }

        private void handleHeat(double transfer) {
            double totalCapacity = getHeatCapacity();
            if (totalCapacity <= 0 || !Double.isFinite(transfer)) return;
            for (Target target : targets) {
                target.handler().handleHeat(target.index(), transfer * capacity(target) / totalCapacity);
            }
            factory.setChanged();
        }

        private double capacity(Target target) {
            return Math.max(1, target.handler().getHeatCapacity(target.index()));
        }

        private double getInverseConduction() {
            double totalCapacity = 0;
            double weighted = 0;
            for (Target target : targets) {
                double capacity = capacity(target);
                totalCapacity += capacity;
                weighted += target.handler().getInverseConduction(target.index()) * capacity;
            }
            return Math.max(1, weighted / Math.max(1, totalCapacity));
        }
    }
}




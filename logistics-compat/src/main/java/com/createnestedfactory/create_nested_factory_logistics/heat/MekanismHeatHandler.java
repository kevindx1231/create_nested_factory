package com.createnestedfactory.create_nested_factory_logistics.heat;

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
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Live, synchronous Mekanism thermal bridge. It intentionally has no cross-tick heat buffer. */
public final class MekanismHeatHandler implements IHeatHandler {
    private final NestedFactoryBlockEntity factory;
    private final int portId;
    private final boolean externalSide;
    private final Direction externalFace;
    private final boolean extensionAccess;
    private long snapshotTick = Long.MIN_VALUE;
    private long snapshotTopology = Long.MIN_VALUE;
    private long snapshotGeneration;
    private boolean snapshotValid;
    private Level snapshotOwnerLevel;
    private ServerLevel snapshotLevel;
    private BlockPos snapshotFactoryPos;
    private BlockPos snapshotRoomOrigin;
    private Set<BlockPos> snapshotPorts = Set.of();
    private final List<PortIdentity> roomPortIdentities = new ArrayList<>();
    private final List<Connection> connections = new ArrayList<>();
    private VirtualHeatCapacitor snapshot;

    private MekanismHeatHandler(NestedFactoryBlockEntity factory, int portId, boolean externalSide,
                                Direction externalFace, boolean extensionAccess) {
        this.factory = factory;
        this.portId = portId;
        this.externalSide = externalSide;
        this.externalFace = externalFace;
        this.extensionAccess = extensionAccess;
    }

    public static IHeatHandler forFactory(NestedFactoryBlockEntity factory, Direction side) {
        if (factory == null || factory.isRemoved() || side == null || !factory.isLiveResourceTransferMode()
                || factory.isFaceTakenOver(side)) {
            return null;
        }
        return createExternal(factory.resolveLogicalPort(side), false);
    }

    public static IHeatHandler forExtension(FactoryLogicalPortEndpoint endpoint) {
        if (endpoint == null || endpoint.factory().isRemoved() || !endpoint.factory().isLiveResourceTransferMode()) {
            return null;
        }
        return createExternal(endpoint, true);
    }

    private static IHeatHandler createExternal(FactoryLogicalPortEndpoint endpoint,
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
        if (factory == null || factory.isRemoved() || !factory.isLiveResourceTransferMode()) {
            return null;
        }
        FactoryLogicalPortEndpoint endpoint = factory.resolveLogicalPort(port.getTargetPortId());
        if (endpoint == null || !endpoint.isConfigured()) return null;
        MekanismHeatHandler handler = new MekanismHeatHandler(factory, endpoint.portId(), false,
                null, false);
        return handler.hasEndpoints() ? handler : null;
    }

    private boolean active() {
        if (factory.isRemoved() || !factory.isLiveResourceTransferMode()) {
            snapshotValid = false;
            return false;
        }
        return !externalSide || externalFace == null
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

    private boolean hasEndpoints() {
        return active() && !virtualCapacitor().targets.isEmpty();
    }

    private VirtualHeatCapacitor virtualCapacitor() {
        ServerLevel level = externalSide ? factory.getPocketLevel()
                : factory.getLevel() instanceof ServerLevel serverLevel ? serverLevel : null;
        long tick = level == null ? Long.MIN_VALUE : level.getGameTime();
        long topology = factory.getLogisticsTopologyVersion();
        Set<BlockPos> ports = externalSide && level != null
                ? PocketRegistry.getPorts(level.getServer(), factory.roomOrigin(), portId) : Set.of();
        if (snapshotValid && tick == snapshotTick && topology == snapshotTopology
                && level == snapshotLevel && factory.getLevel() == snapshotOwnerLevel
                && factory.getBlockPos().equals(snapshotFactoryPos)
                && factory.roomOrigin().equals(snapshotRoomOrigin) && ports.equals(snapshotPorts)
                && roomPortIdentities.stream().allMatch(port -> port.valid(level))
                && connections.stream().allMatch(Connection::valid)) {
            return snapshot;
        }
        snapshotValid = false;
        snapshotGeneration++;
        connections.clear();
        roomPortIdentities.clear();
        List<Target> targets = new ArrayList<>();
        Set<IHeatHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (level != null) {
            if (externalSide) {
                for (BlockPos portPos : ports) {
                    if (!level.isLoaded(portPos)) {
                        roomPortIdentities.add(new PortIdentity(portPos, null, false));
                        continue;
                    }
                    BlockEntity entity = level.getBlockEntity(portPos);
                    roomPortIdentities.add(new PortIdentity(portPos, entity, true));
                    if (!(entity instanceof NestedPortBlockEntity port) || port.getTargetPortId() != portId) continue;
                    for (Direction side : Direction.values()) {
                        addConnection(level, portPos.relative(side), side.getOpposite(), targets, seen);
                    }
                }
            } else {
                for (Direction face : factory.getFacesForPortId(portId)) {
                    for (NestedFactoryBlockEntity.ExternalAccessPoint access : factory.getExternalAccessPoints(face)) {
                        addConnection(level, access.origin().relative(access.face()), access.face().getOpposite(), targets, seen);
                    }
                }
            }
        }
        snapshot = new VirtualHeatCapacitor(List.copyOf(targets), factory);
        snapshotTick = tick;
        snapshotTopology = topology;
        snapshotLevel = level;
        snapshotOwnerLevel = factory.getLevel();
        snapshotFactoryPos = factory.getBlockPos().immutable();
        snapshotRoomOrigin = factory.roomOrigin().immutable();
        snapshotPorts = ports;
        snapshotValid = true;
        return snapshot;
    }

    private void addConnection(ServerLevel level, BlockPos pos, Direction side, List<Target> targets, Set<IHeatHandler> seen) {
        long generation = snapshotGeneration;
        var cache = BlockCapabilityCache.create(MekanismLogisticsCompat.HEAT, level, pos, side,
                () -> !factory.isRemoved() && generation == snapshotGeneration,
                () -> snapshotValid = false);
        IHeatHandler handler = cache.getCapability();
        boolean loaded = level.isLoaded(pos);
        connections.add(new Connection(cache, handler, loaded,
                loaded ? level.getBlockEntity(pos) : null, loaded ? level.getBlockState(pos) : null));
        if (handler == null || !seen.add(handler) || handler instanceof MekanismHeatHandler
                || handler instanceof MekanismExtensionHeatHandler) return;
        try {
            int count = handler.getHeatCapacitorCount();
            for (int index = 0; index < count; index++) {
                targets.add(new Target(handler, index));
            }
        } catch (RuntimeException ignored) {
            // Optional or transient endpoints must not break the remaining bridge targets.
        }
    }

    private record PortIdentity(BlockPos pos, BlockEntity entity, boolean loaded) {
        boolean valid(ServerLevel level) {
            return level.isLoaded(pos) == loaded && (!loaded || (level.getBlockEntity(pos) == entity
                    && (entity == null || !entity.isRemoved())));
        }
    }

    private record Connection(BlockCapabilityCache<IHeatHandler, Direction> cache, IHeatHandler handler,
                               boolean loaded, BlockEntity entity, BlockState state) {
        boolean valid() {
            ServerLevel level = cache.level();
            BlockPos pos = cache.pos();
            if (level.isLoaded(pos) != loaded) return false;
            if (loaded && (level.getBlockEntity(pos) != entity || level.getBlockState(pos) != state
                    || (entity != null && entity.isRemoved()))) return false;
            return cache.getCapability() == handler;
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
            if (targets.isEmpty()) return HeatAPI.AMBIENT_TEMP;
            double totalCapacity = 0;
            double weighted = 0;
            for (Target target : targets) {
                double capacity = capacity(target);
                totalCapacity += capacity;
                weighted += target.handler().getTemperature(target.index()) * capacity;
            }
            return weighted / totalCapacity;
        }

        private double getInverseConduction() {
            if (targets.isEmpty()) return HeatAPI.DEFAULT_INVERSE_CONDUCTION;
            double totalCapacity = 0;
            double weighted = 0;
            for (Target target : targets) {
                double capacity = capacity(target);
                totalCapacity += capacity;
                weighted += target.handler().getInverseConduction(target.index()) * capacity;
            }
            return Math.max(1, weighted / totalCapacity);
        }

        private double getHeatCapacity() {
            double result = 0;
            for (Target target : targets) {
                result += capacity(target);
            }
            return Math.max(1, result);
        }

        private void handleHeat(double transfer) {
            if (targets.isEmpty() || !Double.isFinite(transfer)) return;
            double[] capacities = new double[targets.size()];
            double totalCapacity = 0;
            for (int i = 0; i < targets.size(); i++) {
                capacities[i] = capacity(targets.get(i));
                totalCapacity += capacities[i];
            }
            for (int i = 0; i < targets.size(); i++) {
                Target target = targets.get(i);
                target.handler().handleHeat(target.index(), transfer * capacities[i] / totalCapacity);
            }
            factory.setChanged();
        }

        private double capacity(Target target) {
            return Math.max(1, target.handler().getHeatCapacity(target.index()));
        }

    }
}




package com.createnestedfactory.create_nested_factory_logistics.heat;

import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat;
import mekanism.api.heat.HeatAPI;
import mekanism.api.heat.IHeatHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

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

    private MekanismHeatHandler(NestedFactoryBlockEntity factory, int portId, boolean externalSide) {
        this.factory = factory;
        this.portId = portId;
        this.externalSide = externalSide;
    }

    public static IHeatHandler forFactory(NestedFactoryBlockEntity factory, Direction side) {
        if (factory == null || side == null || !factory.isLiveResourceTransferMode()
                || factory.getFaceMode(side) == PortMode.NONE) {
            return null;
        }
        MekanismHeatHandler handler = new MekanismHeatHandler(factory, factory.getPortId(side), true);
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
        MekanismHeatHandler handler = new MekanismHeatHandler(factory, port.getTargetPortId(), false);
        return handler.hasEndpoints() ? handler : null;
    }

    @Override
    public int getHeatCapacitorCount() {
        return hasEndpoints() ? 1 : 0;
    }

    @Override
    public double getTemperature(int capacitor) {
        return capacitor == 0 ? virtualCapacitor().getTemperature() : HeatAPI.AMBIENT_TEMP;
    }

    @Override
    public double getInverseConduction(int capacitor) {
        return capacitor == 0 ? virtualCapacitor().getInverseConduction()
                : HeatAPI.DEFAULT_INVERSE_CONDUCTION;
    }

    @Override
    public double getHeatCapacity(int capacitor) {
        return capacitor == 0 ? virtualCapacitor().getHeatCapacity() : HeatAPI.DEFAULT_HEAT_CAPACITY;
    }

    @Override
    public void handleHeat(int capacitor, double transfer) {
        if (capacitor == 0 && Double.isFinite(transfer) && transfer != 0) {
            virtualCapacitor().handleHeat(transfer);
        }
    }

    private boolean hasEndpoints() {
        return !resolveTargets().isEmpty();
    }

    private VirtualHeatCapacitor virtualCapacitor() {
        return new VirtualHeatCapacitor(resolveTargets(), factory);
    }

    private List<Target> resolveTargets() {
        List<Target> targets = new ArrayList<>();
        Set<IHeatHandler> seenHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IHeatHandler> handlers = externalSide ? roomHandlers() : externalHandlers();
        for (IHeatHandler handler : handlers) {
            if (!seenHandlers.add(handler) || handler instanceof MekanismHeatHandler) {
                continue;
            }
            try {
                for (int index = 0; index < handler.getHeatCapacitorCount(); index++) {
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
            BlockPos pos = factory.getBlockPos().relative(face);
            IHeatHandler handler = factory.getLevel().getCapability(
                    MekanismLogisticsCompat.HEAT, pos, face.getOpposite());
            if (handler != null && seen.add(handler)) {
                handlers.add(handler);
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
        for (BlockPos portPos : PocketRegistry.getPorts(factory.roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos pos = portPos.relative(side);
                IHeatHandler handler = pocket.getCapability(
                        MekanismLogisticsCompat.HEAT, pos, side.getOpposite());
                if (handler != null && seen.add(handler)) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
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
            double totalCapacity = getHeatCapacity();
            if (totalCapacity <= 0) return HeatAPI.AMBIENT_TEMP;
            double weighted = 0;
            for (Target target : targets) {
                double capacity = capacity(target);
                weighted += target.handler().getTemperature(target.index()) * capacity;
            }
            return weighted / totalCapacity;
        }

        private double getInverseConduction() {
            return weightedAverage(true);
        }

        private double getHeatCapacity() {
            double result = 0;
            for (Target target : targets) {
                result += capacity(target);
            }
            return Math.max(1, result);
        }

        private double getHeat() {
            double result = 0;
            for (Target target : targets) {
                result += target.handler().getTemperature(target.index()) * capacity(target);
            }
            return result;
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

        private double weightedAverage(boolean conduction) {
            double totalCapacity = getHeatCapacity();
            if (totalCapacity <= 0) return HeatAPI.DEFAULT_INVERSE_CONDUCTION;
            double result = 0;
            for (Target target : targets) {
                double value = conduction
                        ? target.handler().getInverseConduction(target.index())
                        : HeatAPI.DEFAULT_INVERSE_INSULATION;
                result += value * capacity(target) / totalCapacity;
            }
            return Math.max(1, result);
        }
    }
}




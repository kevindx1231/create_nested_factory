package com.createnestedfactory.create_nested_factory.block.entity;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Finds real fluid endpoints beyond a factory/port boundary.
 *
 * Standard capability endpoints are preferred: a pipe that exposes IFluidHandler is allowed to
 * route the request itself. Create pipes are additionally traversed because Create keeps their
 * graph in FluidTransportBehaviour rather than exposing a capability on every pipe block. The
 * resolver only performs SIMULATE calls; callers remain responsible for the single EXECUTE pass.
 */
public final class FluidNetworkEndpointResolver {
    public enum Operation {
        FILL,
        DRAIN
    }

    /**
     * Optional integration seam for third-party pipe networks that do not expose a traversable
     * block graph through NeoForge capabilities. An adapter must return only real network
     * endpoints; the resolver still SIMULATE-checks every returned handler before using it.
     */
    public interface FluidNetworkAdapter {
        List<IFluidHandler> find(Level level, BlockPos sourcePos, Direction startSide,
                                 FluidStack request, Operation operation);

        default List<IFluidHandler> findDrain(Level level, BlockPos sourcePos, Direction startSide,
                                              int maxDrain) {
            return List.of();
        }
    }

    public static void registerAdapter(FluidNetworkAdapter adapter) {
        if (adapter != null && !ADAPTERS.contains(adapter)) {
            ADAPTERS.add(adapter);
        }
    }

    public static void unregisterAdapter(FluidNetworkAdapter adapter) {
        ADAPTERS.remove(adapter);
    }

    private static final int MAX_TRAVERSAL_DISTANCE = 256;
    private static final List<FluidNetworkAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    private FluidNetworkEndpointResolver() {
    }

    public static List<IFluidHandler> find(Level level, BlockPos sourcePos, Direction startSide,
                                           FluidStack request, Operation operation) {
        if (request == null || request.isEmpty()) {
            return List.of();
        }
        return findInternal(level, sourcePos, startSide, request, request.getAmount(), operation);
    }

    public static List<IFluidHandler> findDrain(Level level, BlockPos sourcePos, Direction startSide,
                                                int maxDrain) {
        if (maxDrain <= 0) {
            return List.of();
        }
        return findInternal(level, sourcePos, startSide, null, maxDrain, Operation.DRAIN);
    }

    private static List<IFluidHandler> findInternal(Level level, BlockPos sourcePos, Direction startSide,
                                                    FluidStack request, int maxDrain, Operation operation) {
        if (level == null || sourcePos == null || startSide == null) {
            return List.of();
        }
        Set<IFluidHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IFluidHandler> result = new ArrayList<>();
        BlockPos firstPos = sourcePos.relative(startSide);
        IFluidHandler direct = level.getCapability(Capabilities.FluidHandler.BLOCK,
                firstPos, startSide.getOpposite());
        if (isUsable(direct, request, maxDrain, operation) && seen.add(direct)) {
            result.add(direct);
            return result;
        }

        for (FluidNetworkAdapter adapter : ADAPTERS) {
            List<IFluidHandler> adapted = operation == Operation.DRAIN && request == null
                    ? adapter.findDrain(level, sourcePos, startSide, maxDrain)
                    : adapter.find(level, sourcePos, startSide, request, operation);
            if (adapted == null) {
                continue;
            }
            for (IFluidHandler handler : adapted) {
                if (isUsable(handler, request, maxDrain, operation) && seen.add(handler)) {
                    result.add(handler);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        FluidTransportBehaviour firstPipe = FluidPropagator.getPipe(level, firstPos);
        if (firstPipe == null) {
            return result;
        }

        ArrayDeque<PipeNode> pending = new ArrayDeque<>();
        Set<BlockPos> visited = new java.util.HashSet<>();
        pending.add(new PipeNode(firstPos, 1));
        while (!pending.isEmpty()) {
            PipeNode node = pending.removeFirst();
            if (node.distance > MAX_TRAVERSAL_DISTANCE || !level.isLoaded(node.pos)
                    || !visited.add(node.pos)) {
                continue;
            }
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, node.pos);
            if (pipe == null) {
                continue;
            }
            for (Direction face : FluidPropagator.getPipeConnections(level.getBlockState(node.pos), pipe)) {
                BlockPos connectedPos = node.pos.relative(face);
                if (!level.isLoaded(connectedPos)) {
                    continue;
                }
                if (FluidPropagator.getPipe(level, connectedPos) != null) {
                    if (node.distance < MAX_TRAVERSAL_DISTANCE) {
                        pending.addLast(new PipeNode(connectedPos, node.distance + 1));
                    }
                    continue;
                }
                IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK,
                        connectedPos, face.getOpposite());
                if (isUsable(handler, request, maxDrain, operation) && seen.add(handler)) {
                    result.add(handler);
                }
            }
        }
        return result;
    }

    private static boolean isUsable(IFluidHandler handler, FluidStack request, int maxDrain,
                                    Operation operation) {
        if (handler == null) {
            return false;
        }
        if (operation == Operation.FILL) {
            return request != null && handler.fill(request, IFluidHandler.FluidAction.SIMULATE) > 0;
        }
        return request == null
                ? !handler.drain(maxDrain, IFluidHandler.FluidAction.SIMULATE).isEmpty()
                : !handler.drain(request, IFluidHandler.FluidAction.SIMULATE).isEmpty();
    }

    private record PipeNode(BlockPos pos, int distance) {
    }
}

package com.createnestedfactory.create_nested_factory.block.entity;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared Create pipe pressure traversal used by both sides of a nested factory fluid port. */
public final class FluidPressureBridge {
    private FluidPressureBridge() {
    }

    public static boolean apply(Level level, BlockPos sourcePos, Direction startSide, boolean pull, float pressure) {
        if (level == null || pressure <= 0f) {
            return false;
        }
        BlockFace start = new BlockFace(sourcePos, startSide);
        BlockPos firstPipePos = start.getConnectedPos();
        FluidTransportBehaviour firstPipe = FluidPropagator.getPipe(level, firstPipePos);
        if (firstPipe == null) {
            return false;
        }

        Set<BlockFace> targets = new HashSet<>();
        Map<BlockPos, PipeGraphNode> pipeGraph = new HashMap<>();
        if (!pull) {
            FluidPropagator.resetAffectedFluidNetworks(level, firstPipePos, startSide.getOpposite());
        }

        if (!hasReachedValidEndpoint(level, start, pull)) {
            node(pipeGraph, sourcePos, 0).connections.put(startSide, pull);
            node(pipeGraph, firstPipePos, 1).connections.put(startSide.getOpposite(), !pull);

            List<PipePathNode> frontier = new ArrayList<>();
            Set<BlockPos> visited = new HashSet<>();
            int maxDistance = FluidPropagator.getPumpRange();
            frontier.add(new PipePathNode(1, firstPipePos));

            while (!frontier.isEmpty()) {
                PipePathNode entry = frontier.remove(0);
                int distance = entry.distance();
                BlockPos currentPos = entry.pos();
                if (!level.isLoaded(currentPos) || !visited.add(currentPos)) {
                    continue;
                }

                BlockState currentState = level.getBlockState(currentPos);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, currentPos);
                if (pipe == null) {
                    continue;
                }

                for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                    BlockFace blockFace = new BlockFace(currentPos, face);
                    BlockPos connectedPos = blockFace.getConnectedPos();
                    if (!level.isLoaded(connectedPos) || blockFace.isEquivalent(start)) {
                        continue;
                    }

                    if (hasReachedValidEndpoint(level, blockFace, pull)) {
                        node(pipeGraph, currentPos, distance).connections.put(face, pull);
                        targets.add(blockFace);
                        continue;
                    }

                    FluidTransportBehaviour connectedPipe = FluidPropagator.getPipe(level, connectedPos);
                    if (connectedPipe == null || level.getBlockEntity(connectedPos) instanceof PumpBlockEntity
                            || visited.contains(connectedPos)) {
                        continue;
                    }
                    if (distance + 1 >= maxDistance) {
                        node(pipeGraph, currentPos, distance).connections.put(face, pull);
                        targets.add(blockFace);
                        continue;
                    }

                    node(pipeGraph, currentPos, distance).connections.put(face, pull);
                    node(pipeGraph, connectedPos, distance + 1).connections.put(face.getOpposite(), !pull);
                    frontier.add(new PipePathNode(distance + 1, connectedPos));
                }
            }
        }

        Map<Integer, Set<BlockFace>> validFaces = new HashMap<>();
        searchForEndpointRecursively(pipeGraph, targets, validFaces,
                new BlockFace(start.getPos(), start.getOppositeFace()), pull);

        boolean applied = false;
        for (Set<BlockFace> faces : validFaces.values()) {
            int parallelBranches = Math.max(1, faces.size() - 1);
            for (BlockFace face : faces) {
                BlockPos pipePos = face.getPos();
                if (pipePos.equals(sourcePos)) {
                    continue;
                }
                PipeGraphNode graphNode = pipeGraph.get(pipePos);
                if (graphNode == null) {
                    continue;
                }
                Boolean inbound = graphNode.connections.get(face.getFace());
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
                if (inbound == null || pipe == null) {
                    continue;
                }
                pipe.addPressure(face.getFace(), inbound, pressure / parallelBranches);
                applied = true;
            }
        }
        return applied;
    }

    private static PipeGraphNode node(Map<BlockPos, PipeGraphNode> graph, BlockPos pos, int distance) {
        return graph.computeIfAbsent(pos, ignored -> new PipeGraphNode(distance));
    }

    private static boolean hasReachedValidEndpoint(Level world, BlockFace blockFace, boolean pull) {
        BlockPos connectedPos = blockFace.getConnectedPos();
        BlockState connectedState = world.getBlockState(connectedPos);
        BlockEntity blockEntity = world.getBlockEntity(connectedPos);
        Direction face = blockFace.getFace();

        if (PumpBlock.isPump(connectedState)
                && connectedState.getValue(PumpBlock.FACING).getAxis() == face.getAxis()
                && blockEntity instanceof PumpBlockEntity pump) {
            boolean pumpFrontFacesCurrentPipe = connectedState.getValue(PumpBlock.FACING) == face.getOpposite();
            return pump.isPullingOnSide(pumpFrontFacesCurrentPipe) != pull;
        }

        FluidTransportBehaviour pipe = FluidPropagator.getPipe(world, connectedPos);
        if (pipe != null && pipe.canHaveFlowToward(connectedState, face.getOpposite())) {
            return false;
        }

        if (blockEntity != null) {
            IFluidHandler capability = world.getCapability(Capabilities.FluidHandler.BLOCK, connectedPos,
                    face.getOpposite());
            if (capability != null) {
                return true;
            }
        }

        return FluidPropagator.isOpenEnd(world, blockFace.getPos(), face);
    }

    private static boolean searchForEndpointRecursively(Map<BlockPos, PipeGraphNode> pipeGraph,
                                                         Set<BlockFace> targets,
                                                         Map<Integer, Set<BlockFace>> validFaces,
                                                         BlockFace currentFace,
                                                         boolean pull) {
        PipeGraphNode current = pipeGraph.get(currentFace.getPos());
        if (current == null) {
            return false;
        }

        boolean successful = false;
        for (Direction nextFacing : Direction.values()) {
            if (nextFacing == currentFace.getFace()) {
                continue;
            }
            Boolean directionPull = current.connections.get(nextFacing);
            if (directionPull == null) {
                continue;
            }

            BlockFace localTarget = new BlockFace(currentFace.getPos(), nextFacing);
            if (targets.contains(localTarget)) {
                validFaces.computeIfAbsent(current.distance, ignored -> new HashSet<>()).add(localTarget);
                successful = true;
                continue;
            }
            if (directionPull != pull) {
                continue;
            }
            if (!searchForEndpointRecursively(pipeGraph, targets, validFaces,
                    new BlockFace(currentFace.getPos().relative(nextFacing), nextFacing.getOpposite()), pull)) {
                continue;
            }

            validFaces.computeIfAbsent(current.distance, ignored -> new HashSet<>()).add(localTarget);
            successful = true;
        }

        if (successful) {
            validFaces.computeIfAbsent(current.distance, ignored -> new HashSet<>()).add(currentFace);
        }
        return successful;
    }

    private record PipePathNode(int distance, BlockPos pos) {
    }

    private static final class PipeGraphNode {
        private final int distance;
        private final Map<Direction, Boolean> connections = new HashMap<>();

        private PipeGraphNode(int distance) {
            this.distance = distance;
        }
    }
}

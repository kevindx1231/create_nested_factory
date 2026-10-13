package com.createnestedfactory.create_nested_factory_logistics.chemical;

import com.createnestedfactory.create_nested_factory_logistics.LiveMekanismEndpoint;
import com.createnestedfactory.create_nested_factory.compat.LegacyCapabilities;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryTransit;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryLogicalPortEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat;
import mekanism.api.Action;
import mekanism.api.chemical.slurry.SlurryStack;
import mekanism.api.chemical.slurry.ISlurryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Capability bridge for Mekanism chemicals and Pipez gas pipes. */
public final class MekanismSlurryHandler implements ISlurryHandler, LiveMekanismEndpoint {
    private static final String INPUT_KEY = "InputChemicals";
    private static final String OUTPUT_KEY = "OutputChemicals";

    private final NestedFactoryBlockEntity factory;
    private final int portId;
    private final boolean externalSide;
    private final PortMode mode;
    private final Direction externalFace;
    private final boolean extensionAccess;
    private NestedPortBlockEntity roomPort;

    private MekanismSlurryHandler(NestedFactoryBlockEntity factory, int portId,
                                    boolean externalSide, PortMode mode,
                                    Direction externalFace, boolean extensionAccess) {
        this.factory = factory;
        this.portId = portId;
        this.externalSide = externalSide;
        this.mode = mode;
        this.externalFace = externalFace;
        this.extensionAccess = extensionAccess;
    }

    public static ISlurryHandler forFactory(NestedFactoryBlockEntity factory, Direction side) {
        if (factory == null || side == null || factory.isFaceTakenOver(side)
                || !factory.isLiveResourceTransferMode()) {
            return null;
        }
        return createExternal(factory.resolveLogicalPort(side), false);
    }

    public static MekanismSlurryHandler forExtension(FactoryLogicalPortEndpoint endpoint) {
        if (endpoint == null || !endpoint.factory().isLiveResourceTransferMode()) {
            return null;
        }
        return createExternal(endpoint, true);
    }

    private static MekanismSlurryHandler createExternal(FactoryLogicalPortEndpoint endpoint,
                                                    boolean extensionAccess) {
        if (endpoint == null || !endpoint.isConfigured()) {
            return null;
        }
        return new MekanismSlurryHandler(endpoint.factory(), endpoint.portId(), true, endpoint.mode(),
                endpoint.face(), extensionAccess);
    }

    public static ISlurryHandler forPort(NestedPortBlockEntity port, Direction side) {
        if (port == null || side == null) {
            return null;
        }
        NestedFactoryBlockEntity factory = port.getFactory();
        if (factory == null || !factory.isLiveResourceTransferMode()) {
            return null;
        }
        FactoryLogicalPortEndpoint endpoint = factory.resolveLogicalPort(port.getTargetPortId());
        if (endpoint == null || !endpoint.isConfigured()) {
            return null;
        }
        MekanismSlurryHandler handler = new MekanismSlurryHandler(factory, port.getTargetPortId(), false,
                endpoint.mode(), null, false);
        handler.roomPort = port;
        return handler;
    }

    @Override
    public boolean isAvailable() {
        if (factory.isRemoved() || !factory.isLiveResourceTransferMode() || mode == PortMode.NONE) {
            return false;
        }
        if (!externalSide) {
            if (roomPort != null && (roomPort.isRemoved() || roomPort.getFactory() != factory
                    || roomPort.getTargetPortId() != portId)) return false;
            FactoryLogicalPortEndpoint endpoint = factory.resolveLogicalPort(portId);
            return endpoint != null && endpoint.mode() == mode;
        }
        return externalFace == null
                || (factory.getFaceMode(externalFace) == mode
                && factory.getPortId(externalFace) == portId
                && (extensionAccess || !factory.isFaceTakenOver(externalFace)));
    }

    private boolean isInput() {
        return mode == PortMode.INPUT;
    }

    private boolean hasRoomPort() {
        ServerLevel pocket = factory.getPocketLevel();
        return pocket != null && !PocketRegistry.getPorts(pocket.getServer(), factory.roomOrigin(), portId).isEmpty();
    }

    private String ledgerKey() {
        return isInput() ? INPUT_KEY : OUTPUT_KEY;
    }

    private SlurryLedger ledger() {
        FactoryTransit.PortResourceChannel channel = factory.getPortChannel(portId);
        return channel.participant(SlurryLedger.PARTICIPANT_ID, SlurryLedger.class);
    }

    @Override
    public int getTanks() {
        return isAvailable() && hasRoomPort() ? 1 : 0;
    }

    @Override
    public SlurryStack getChemicalInTank(int tank) {
        if (tank != 0 || !isAvailable()) {
            return SlurryStack.EMPTY;
        }
        // Only expose the side's consumable/output balance. A write-only input endpoint and a
        // read-only output endpoint must not accidentally become bidirectional inventories.
        if ((!externalSide && isInput()) || (externalSide && !isInput())) {
            SlurryStack buffered = ledger().peek(ledgerKey());
            if (!buffered.isEmpty()) {
                return buffered;
            }
            List<ISlurryHandler> sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
            return peekFrom(sources);
        }
        return SlurryStack.EMPTY;
    }

    @Override
    public void setChemicalInTank(int tank, SlurryStack stack) {
        throw new UnsupportedOperationException("Nested factory chemical tanks are transport endpoints");
    }

    @Override
    public long getTankCapacity(int tank) {
        return tank == 0 && isAvailable() && hasRoomPort() ? Long.MAX_VALUE : 0;
    }

    @Override
    public boolean isValid(int tank, SlurryStack stack) {
        return tank == 0 && !stack.isEmpty() && isAvailable()
                && ((externalSide && isInput()) || (!externalSide && !isInput()));
    }

    @Override
    public SlurryStack insertChemical(int tank, SlurryStack stack, Action action) {
        if (tank != 0 || stack.isEmpty() || !isAvailable()) {
            return stack;
        }
        if (externalSide) {
            return isInput() ? insertIntoRoom(stack, action) : stack;
        }
        return isInput() ? stack : insertIntoExternal(stack, action);
    }

    private boolean canExtract(int tank) {
        return tank == 0 && isAvailable() && (externalSide != isInput());
    }

    @Override
    public SlurryStack extractChemical(int tank, long amount, Action action) {
        return amount <= 0 || !canExtract(tank) ? SlurryStack.EMPTY : extract(amount, null, action);
    }

    @Override
    public SlurryStack insertChemical(SlurryStack stack, Action action) {
        return insertChemical(0, stack, action);
    }

    @Override
    public SlurryStack extractChemical(long amount, Action action) {
        return extractChemical(0, amount, action);
    }

    @Override
    public SlurryStack extractChemical(SlurryStack stack, Action action) {
        return stack.isEmpty() || !canExtract(0) ? SlurryStack.EMPTY : extract(stack.getAmount(), stack, action);
    }

    private SlurryStack insertIntoRoom(SlurryStack stack, Action action) {
        SlurryStack remaining = transferInto(chemicalsInRoom(), stack, action);
        if (!remaining.isEmpty()) {
            remaining = ledger().insert(INPUT_KEY, remaining, action);
        }
        if (action.execute() && remaining.getAmount() < stack.getAmount()) {
            ledger().markBoundaryTransfer();
            factory.setChanged();
        }
        return remaining;
    }

    private SlurryStack insertIntoExternal(SlurryStack stack, Action action) {
        SlurryStack remaining = transferInto(chemicalsOutside(), stack, action);
        if (!remaining.isEmpty()) {
            remaining = ledger().insert(OUTPUT_KEY, remaining, action);
        }
        if (action.execute() && remaining.getAmount() < stack.getAmount()) {
            ledger().markBoundaryTransfer();
            factory.setChanged();
        }
        return remaining;
    }

    /** One extraction has one type, across both cached ownership and a live source. */
    private SlurryStack extract(long amount, SlurryStack requested, Action action) {
        SlurryLedger buffer = ledger();
        String key = ledgerKey();
        SlurryStack buffered = buffer.peek(key);
        // Keep the existing FIFO tank surface; a typed request cannot skip its visible head.
        if (requested != null && !buffered.isEmpty() && !buffered.isTypeEqual(requested)) {
            return SlurryStack.EMPTY;
        }
        List<ISlurryHandler> sources = null;
        if (requested == null) {
            requested = buffered;
            if (requested.isEmpty()) {
                sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
                for (ISlurryHandler source : sources) {
                    requested = source.extractChemical(amount, Action.SIMULATE);
                    if (!requested.isEmpty()) break;
                }
            }
            if (requested.isEmpty()) return SlurryStack.EMPTY;
        }

        SlurryStack result = buffered.isEmpty() ? SlurryStack.EMPTY : buffer.extract(key, amount, action);
        long remaining = amount - result.getAmount();
        boolean sourceTransferred = false;
        if (remaining > 0) {
            if (sources == null) sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
            for (ISlurryHandler source : sources) {
                SlurryStack taken = source.extractChemical(new SlurryStack(requested, remaining), action);
                if (taken.isEmpty()) continue;
                // A source must honour Mekanism's typed, bounded extraction contract.
                if (!taken.isTypeEqual(requested) || taken.getAmount() > remaining) {
                    throw new IllegalStateException("Chemical source violated its extraction contract");
                }
                result = new SlurryStack(requested, result.getAmount() + taken.getAmount());
                sourceTransferred = true;
                break;
            }
        }
        if (action.execute() && !result.isEmpty()) {
            if (sourceTransferred || !externalSide) buffer.markBoundaryTransfer();
            factory.setChanged();
        }
        return result;
    }

    private List<ISlurryHandler> chemicalsOutside() {
        if (factory.getLevel() == null || factory.getLevel().isClientSide()) {
            return List.of();
        }
        List<ISlurryHandler> handlers = new ArrayList<>();
        Set<ISlurryHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction face : factory.getFacesForPortId(portId)) {
            for (NestedFactoryBlockEntity.ExternalAccessPoint access : factory.getExternalAccessPoints(face)) {
                BlockPos pos = access.origin().relative(access.face());
                ISlurryHandler handler = LegacyCapabilities.get(factory.getLevel(), MekanismLogisticsCompat.SLURRY, pos, access.face().getOpposite());
                if (handler != null && seen.add(handler)) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
    }

    private List<ISlurryHandler> chemicalsInRoom() {
        ServerLevel pocket = factory.getPocketLevel();
        if (pocket == null) {
            return List.of();
        }
        List<ISlurryHandler> handlers = new ArrayList<>();
        Set<ISlurryHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), factory.roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos pos = portPos.relative(side);
                ISlurryHandler handler = LegacyCapabilities.get(pocket, MekanismLogisticsCompat.SLURRY, pos, side.getOpposite());
                if (handler != null && !(handler instanceof MekanismSlurryHandler) && seen.add(handler)) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
    }

    private static SlurryStack peekFrom(List<ISlurryHandler> sources) {
        for (ISlurryHandler source : sources) {
            for (int tank = 0; tank < source.getTanks(); tank++) {
                SlurryStack stack = source.getChemicalInTank(tank);
                if (!stack.isEmpty()) {
                    return stack.copy();
                }
            }
        }
        return SlurryStack.EMPTY;
    }

    private static SlurryStack transferInto(List<ISlurryHandler> destinations,
                                               SlurryStack stack, Action action) {
        SlurryStack remaining = stack;
        for (ISlurryHandler destination : destinations) {
            if (remaining.isEmpty()) {
                break;
            }
            remaining = destination.insertChemical(remaining, action);
        }
        return remaining;
    }

}

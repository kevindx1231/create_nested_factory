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
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.infuse.IInfusionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Capability bridge for Mekanism chemicals and Pipez gas pipes. */
public final class MekanismInfusionHandler implements IInfusionHandler, LiveMekanismEndpoint {
    private static final String INPUT_KEY = "InputChemicals";
    private static final String OUTPUT_KEY = "OutputChemicals";

    private final NestedFactoryBlockEntity factory;
    private final int portId;
    private final boolean externalSide;
    private final PortMode mode;
    private final Direction externalFace;
    private final boolean extensionAccess;
    private NestedPortBlockEntity roomPort;

    private MekanismInfusionHandler(NestedFactoryBlockEntity factory, int portId,
                                    boolean externalSide, PortMode mode,
                                    Direction externalFace, boolean extensionAccess) {
        this.factory = factory;
        this.portId = portId;
        this.externalSide = externalSide;
        this.mode = mode;
        this.externalFace = externalFace;
        this.extensionAccess = extensionAccess;
    }

    public static IInfusionHandler forFactory(NestedFactoryBlockEntity factory, Direction side) {
        if (factory == null || side == null || factory.isFaceTakenOver(side)
                || !factory.isLiveResourceTransferMode()) {
            return null;
        }
        return createExternal(factory.resolveLogicalPort(side), false);
    }

    public static MekanismInfusionHandler forExtension(FactoryLogicalPortEndpoint endpoint) {
        if (endpoint == null || !endpoint.factory().isLiveResourceTransferMode()) {
            return null;
        }
        return createExternal(endpoint, true);
    }

    private static MekanismInfusionHandler createExternal(FactoryLogicalPortEndpoint endpoint,
                                                    boolean extensionAccess) {
        if (endpoint == null || !endpoint.isConfigured()) {
            return null;
        }
        return new MekanismInfusionHandler(endpoint.factory(), endpoint.portId(), true, endpoint.mode(),
                endpoint.face(), extensionAccess);
    }

    public static IInfusionHandler forPort(NestedPortBlockEntity port, Direction side) {
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
        MekanismInfusionHandler handler = new MekanismInfusionHandler(factory, port.getTargetPortId(), false,
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

    private InfusionLedger ledger() {
        FactoryTransit.PortResourceChannel channel = factory.getPortChannel(portId);
        return channel.participant(InfusionLedger.PARTICIPANT_ID, InfusionLedger.class);
    }

    @Override
    public int getTanks() {
        return isAvailable() && hasRoomPort() ? 1 : 0;
    }

    @Override
    public InfusionStack getChemicalInTank(int tank) {
        if (tank != 0 || !isAvailable()) {
            return InfusionStack.EMPTY;
        }
        // Only expose the side's consumable/output balance. A write-only input endpoint and a
        // read-only output endpoint must not accidentally become bidirectional inventories.
        if ((!externalSide && isInput()) || (externalSide && !isInput())) {
            InfusionStack buffered = ledger().peek(ledgerKey());
            if (!buffered.isEmpty()) {
                return buffered;
            }
            List<IInfusionHandler> sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
            return peekFrom(sources);
        }
        return InfusionStack.EMPTY;
    }

    @Override
    public void setChemicalInTank(int tank, InfusionStack stack) {
        throw new UnsupportedOperationException("Nested factory chemical tanks are transport endpoints");
    }

    @Override
    public long getTankCapacity(int tank) {
        return tank == 0 && isAvailable() && hasRoomPort() ? Long.MAX_VALUE : 0;
    }

    @Override
    public boolean isValid(int tank, InfusionStack stack) {
        return tank == 0 && !stack.isEmpty() && isAvailable()
                && ((externalSide && isInput()) || (!externalSide && !isInput()));
    }

    @Override
    public InfusionStack insertChemical(int tank, InfusionStack stack, Action action) {
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
    public InfusionStack extractChemical(int tank, long amount, Action action) {
        return amount <= 0 || !canExtract(tank) ? InfusionStack.EMPTY : extract(amount, null, action);
    }

    @Override
    public InfusionStack insertChemical(InfusionStack stack, Action action) {
        return insertChemical(0, stack, action);
    }

    @Override
    public InfusionStack extractChemical(long amount, Action action) {
        return extractChemical(0, amount, action);
    }

    @Override
    public InfusionStack extractChemical(InfusionStack stack, Action action) {
        return stack.isEmpty() || !canExtract(0) ? InfusionStack.EMPTY : extract(stack.getAmount(), stack, action);
    }

    private InfusionStack insertIntoRoom(InfusionStack stack, Action action) {
        InfusionStack remaining = transferInto(chemicalsInRoom(), stack, action);
        if (!remaining.isEmpty()) {
            remaining = ledger().insert(INPUT_KEY, remaining, action);
        }
        if (action.execute() && remaining.getAmount() < stack.getAmount()) {
            ledger().markBoundaryTransfer();
            factory.setChanged();
        }
        return remaining;
    }

    private InfusionStack insertIntoExternal(InfusionStack stack, Action action) {
        InfusionStack remaining = transferInto(chemicalsOutside(), stack, action);
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
    private InfusionStack extract(long amount, InfusionStack requested, Action action) {
        InfusionLedger buffer = ledger();
        String key = ledgerKey();
        InfusionStack buffered = buffer.peek(key);
        // Keep the existing FIFO tank surface; a typed request cannot skip its visible head.
        if (requested != null && !buffered.isEmpty() && !buffered.isTypeEqual(requested)) {
            return InfusionStack.EMPTY;
        }
        List<IInfusionHandler> sources = null;
        if (requested == null) {
            requested = buffered;
            if (requested.isEmpty()) {
                sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
                for (IInfusionHandler source : sources) {
                    requested = source.extractChemical(amount, Action.SIMULATE);
                    if (!requested.isEmpty()) break;
                }
            }
            if (requested.isEmpty()) return InfusionStack.EMPTY;
        }

        InfusionStack result = buffered.isEmpty() ? InfusionStack.EMPTY : buffer.extract(key, amount, action);
        long remaining = amount - result.getAmount();
        boolean sourceTransferred = false;
        if (remaining > 0) {
            if (sources == null) sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
            for (IInfusionHandler source : sources) {
                InfusionStack taken = source.extractChemical(new InfusionStack(requested, remaining), action);
                if (taken.isEmpty()) continue;
                // A source must honour Mekanism's typed, bounded extraction contract.
                if (!taken.isTypeEqual(requested) || taken.getAmount() > remaining) {
                    throw new IllegalStateException("Chemical source violated its extraction contract");
                }
                result = new InfusionStack(requested, result.getAmount() + taken.getAmount());
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

    private List<IInfusionHandler> chemicalsOutside() {
        if (factory.getLevel() == null || factory.getLevel().isClientSide()) {
            return List.of();
        }
        List<IInfusionHandler> handlers = new ArrayList<>();
        Set<IInfusionHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction face : factory.getFacesForPortId(portId)) {
            for (NestedFactoryBlockEntity.ExternalAccessPoint access : factory.getExternalAccessPoints(face)) {
                BlockPos pos = access.origin().relative(access.face());
                IInfusionHandler handler = LegacyCapabilities.get(factory.getLevel(), MekanismLogisticsCompat.INFUSION, pos, access.face().getOpposite());
                if (handler != null && seen.add(handler)) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
    }

    private List<IInfusionHandler> chemicalsInRoom() {
        ServerLevel pocket = factory.getPocketLevel();
        if (pocket == null) {
            return List.of();
        }
        List<IInfusionHandler> handlers = new ArrayList<>();
        Set<IInfusionHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), factory.roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos pos = portPos.relative(side);
                IInfusionHandler handler = LegacyCapabilities.get(pocket, MekanismLogisticsCompat.INFUSION, pos, side.getOpposite());
                if (handler != null && !(handler instanceof MekanismInfusionHandler) && seen.add(handler)) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
    }

    private static InfusionStack peekFrom(List<IInfusionHandler> sources) {
        for (IInfusionHandler source : sources) {
            for (int tank = 0; tank < source.getTanks(); tank++) {
                InfusionStack stack = source.getChemicalInTank(tank);
                if (!stack.isEmpty()) {
                    return stack.copy();
                }
            }
        }
        return InfusionStack.EMPTY;
    }

    private static InfusionStack transferInto(List<IInfusionHandler> destinations,
                                               InfusionStack stack, Action action) {
        InfusionStack remaining = stack;
        for (IInfusionHandler destination : destinations) {
            if (remaining.isEmpty()) {
                break;
            }
            remaining = destination.insertChemical(remaining, action);
        }
        return remaining;
    }

}

package com.createnestedfactory.create_nested_factory_logistics.chemical;

import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryTransit;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryLogicalPortEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Capability bridge for Mekanism chemicals and Pipez gas pipes. */
public final class MekanismChemicalHandler implements IChemicalHandler {
    private static final String INPUT_KEY = "InputChemicals";
    private static final String OUTPUT_KEY = "OutputChemicals";

    private final NestedFactoryBlockEntity factory;
    private final int portId;
    private final boolean externalSide;
    private final PortMode mode;
    private final Direction externalFace;
    private final boolean extensionAccess;

    private MekanismChemicalHandler(NestedFactoryBlockEntity factory, int portId,
                                    boolean externalSide, PortMode mode,
                                    Direction externalFace, boolean extensionAccess) {
        this.factory = factory;
        this.portId = portId;
        this.externalSide = externalSide;
        this.mode = mode;
        this.externalFace = externalFace;
        this.extensionAccess = extensionAccess;
    }

    public static IChemicalHandler forFactory(NestedFactoryBlockEntity factory, Direction side) {
        if (factory == null || factory.isRemoved() || side == null || factory.isFaceTakenOver(side)
                || !factory.isLiveResourceTransferMode()) {
            return null;
        }
        return createExternal(factory.resolveLogicalPort(side), false);
    }

    public static IChemicalHandler forExtension(FactoryLogicalPortEndpoint endpoint) {
        if (endpoint == null || endpoint.factory().isRemoved() || !endpoint.factory().isLiveResourceTransferMode()) {
            return null;
        }
        return createExternal(endpoint, true);
    }

    private static IChemicalHandler createExternal(FactoryLogicalPortEndpoint endpoint,
                                                    boolean extensionAccess) {
        if (endpoint == null || !endpoint.isConfigured()) {
            return null;
        }
        return new MekanismChemicalHandler(endpoint.factory(), endpoint.portId(), true, endpoint.mode(),
                endpoint.face(), extensionAccess);
    }

    public static IChemicalHandler forPort(NestedPortBlockEntity port, Direction side) {
        if (port == null || side == null) {
            return null;
        }
        NestedFactoryBlockEntity factory = port.getFactory();
        if (factory == null || factory.isRemoved() || !factory.isLiveResourceTransferMode()) {
            return null;
        }
        FactoryLogicalPortEndpoint endpoint = factory.resolveLogicalPort(port.getTargetPortId());
        if (endpoint == null || !endpoint.isConfigured()) {
            return null;
        }
        return new MekanismChemicalHandler(factory, port.getTargetPortId(), false,
                endpoint.mode(), null, false);
    }

    private boolean active() {
        if (factory.isRemoved() || !factory.isLiveResourceTransferMode() || mode == PortMode.NONE) {
            return false;
        }
        return !externalSide || externalFace == null
                || (factory.getFaceMode(externalFace) == mode
                && factory.getPortId(externalFace) == portId
                && (extensionAccess || !factory.isFaceTakenOver(externalFace)));
    }

    private boolean isInput() {
        return mode == PortMode.INPUT;
    }

    private boolean hasRoomPort() {
        return factory.getPocketLevel() != null
                && !PocketRegistry.getPorts(factory.getPocketLevel().getServer(), factory.roomOrigin(), portId).isEmpty();
    }

    private String ledgerKey() {
        return isInput() ? INPUT_KEY : OUTPUT_KEY;
    }

    private ChemicalLedger ledger() {
        FactoryTransit.PortResourceChannel channel = factory.getPortChannel(portId);
        return channel.participant(ChemicalLedger.PARTICIPANT_ID, ChemicalLedger.class);
    }

    @Override
    public int getChemicalTanks() {
        return active() && hasRoomPort() ? 1 : 0;
    }

    @Override
    public ChemicalStack getChemicalInTank(int tank) {
        if (tank != 0 || !active()) {
            return ChemicalStack.EMPTY;
        }
        // Only expose the side's consumable/output balance. A write-only input endpoint and a
        // read-only output endpoint must not accidentally become bidirectional inventories.
        if ((!externalSide && isInput()) || (externalSide && !isInput())) {
            ChemicalStack buffered = ledger().peek(ledgerKey());
            if (!buffered.isEmpty()) {
                return buffered;
            }
            List<IChemicalHandler> sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
            return peekFrom(sources);
        }
        return ChemicalStack.EMPTY;
    }

    @Override
    public void setChemicalInTank(int tank, ChemicalStack stack) {
        throw new UnsupportedOperationException("Nested factory chemical tanks are transport endpoints");
    }

    @Override
    public long getChemicalTankCapacity(int tank) {
        return tank == 0 && active() && hasRoomPort() ? Long.MAX_VALUE : 0;
    }

    @Override
    public boolean isValid(int tank, ChemicalStack stack) {
        return tank == 0 && !stack.isEmpty() && active()
                && ((externalSide && isInput()) || (!externalSide && !isInput()));
    }

    @Override
    public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {
        if (tank != 0 || stack.isEmpty() || !active()) {
            return stack;
        }
        if (externalSide) {
            return isInput() ? insertIntoRoom(stack, action) : stack;
        }
        return isInput() ? stack : insertIntoExternal(stack, action);
    }

    @Override
    public ChemicalStack extractChemical(int tank, long amount, Action action) {
        return amount <= 0 || !canExtract(tank) ? ChemicalStack.EMPTY : extract(amount, null, action);
    }

    @Override
    public ChemicalStack insertChemical(ChemicalStack stack, Action action) {
        return insertChemical(0, stack, action);
    }

    @Override
    public ChemicalStack extractChemical(long amount, Action action) {
        return extractChemical(0, amount, action);
    }

    @Override
    public ChemicalStack extractChemical(ChemicalStack stack, Action action) {
        return stack.isEmpty() || !canExtract(0) ? ChemicalStack.EMPTY : extract(stack.getAmount(), stack, action);
    }

    private boolean canExtract(int tank) {
        return tank == 0 && active() && externalSide != isInput();
    }

    private ChemicalStack insertIntoRoom(ChemicalStack stack, Action action) {
        ChemicalStack remaining = transferInto(chemicalsInRoom(), stack, action);
        if (!remaining.isEmpty()) {
            remaining = ledger().insert(INPUT_KEY, remaining, action);
        }
        if (action.execute() && remaining.getAmount() < stack.getAmount()) {
            ledger().markBoundaryTransfer();
            factory.setChanged();
        }
        return remaining;
    }

    private ChemicalStack insertIntoExternal(ChemicalStack stack, Action action) {
        ChemicalStack remaining = transferInto(chemicalsOutside(), stack, action);
        if (!remaining.isEmpty()) {
            remaining = ledger().insert(OUTPUT_KEY, remaining, action);
        }
        if (action.execute() && remaining.getAmount() < stack.getAmount()) {
            ledger().markBoundaryTransfer();
            factory.setChanged();
        }
        return remaining;
    }

    private ChemicalStack extract(long amount, ChemicalStack requested, Action action) {
        ChemicalLedger buffer = ledger();
        String key = ledgerKey();
        ChemicalStack buffered = buffer.peek(key);
        if (requested != null && !buffered.isEmpty() && !ChemicalStack.isSameChemical(buffered, requested)) {
            return ChemicalStack.EMPTY;
        }
        List<IChemicalHandler> sources = null;
        if (requested == null) {
            requested = buffered;
            if (requested.isEmpty()) {
                sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
                for (IChemicalHandler source : sources) {
                    requested = source.extractChemical(amount, Action.SIMULATE);
                    if (!requested.isEmpty()) break;
                }
            }
            if (requested.isEmpty()) return ChemicalStack.EMPTY;
        }
        ChemicalStack result = buffered.isEmpty() ? ChemicalStack.EMPTY : buffer.extract(key, amount, action);
        long remaining = amount - result.getAmount();
        boolean sourceTransferred = false;
        if (remaining > 0) {
            if (sources == null) sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
            for (IChemicalHandler source : sources) {
                ChemicalStack taken = source.extractChemical(requested.copyWithAmount(remaining), action);
                if (taken.isEmpty()) continue;
                if (!ChemicalStack.isSameChemical(taken, requested) || taken.getAmount() > remaining) {
                    throw new IllegalStateException("Chemical source violated its extraction contract");
                }
                result = requested.copyWithAmount(result.getAmount() + taken.getAmount());
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

    private List<IChemicalHandler> chemicalsOutside() {
        if (factory.getLevel() == null || factory.getLevel().isClientSide()) {
            return List.of();
        }
        List<IChemicalHandler> handlers = new ArrayList<>();
        Set<IChemicalHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction face : factory.getFacesForPortId(portId)) {
            for (NestedFactoryBlockEntity.ExternalAccessPoint access : factory.getExternalAccessPoints(face)) {
                BlockPos pos = access.origin().relative(access.face());
                IChemicalHandler handler = factory.getLevel().getCapability(
                        MekanismLogisticsCompat.CHEMICAL, pos, access.face().getOpposite());
                if (handler != null && seen.add(handler)) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
    }

    private List<IChemicalHandler> chemicalsInRoom() {
        ServerLevel pocket = factory.getPocketLevel();
        if (pocket == null) {
            return List.of();
        }
        List<IChemicalHandler> handlers = new ArrayList<>();
        Set<IChemicalHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockPos portPos : PocketRegistry.getPorts(pocket.getServer(), factory.roomOrigin(), portId)) {
            if (!(pocket.getBlockEntity(portPos) instanceof NestedPortBlockEntity port)
                    || port.getTargetPortId() != portId) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos pos = portPos.relative(side);
                IChemicalHandler handler = pocket.getCapability(
                        MekanismLogisticsCompat.CHEMICAL, pos, side.getOpposite());
                if (handler != null && !(handler instanceof MekanismChemicalHandler) && seen.add(handler)) {
                    handlers.add(handler);
                }
            }
        }
        return handlers;
    }

    private static ChemicalStack peekFrom(List<IChemicalHandler> sources) {
        for (IChemicalHandler source : sources) {
            for (int tank = 0; tank < source.getChemicalTanks(); tank++) {
                ChemicalStack stack = source.getChemicalInTank(tank);
                if (!stack.isEmpty()) {
                    return stack.copy();
                }
            }
        }
        return ChemicalStack.EMPTY;
    }

    private static ChemicalStack transferInto(List<IChemicalHandler> destinations,
                                               ChemicalStack stack, Action action) {
        ChemicalStack remaining = stack;
        for (IChemicalHandler destination : destinations) {
            if (remaining.isEmpty()) {
                break;
            }
            remaining = destination.insertChemical(remaining, action);
        }
        return remaining;
    }

}

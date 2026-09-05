package com.createnestedfactory.create_nested_factory_logistics.chemical;

import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPortChannels;
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
    private static final String EXTENSION_NAMESPACE = "create_nested_factory_logistics";
    private static final String INPUT_KEY = "InputChemicals";
    private static final String OUTPUT_KEY = "OutputChemicals";

    private final NestedFactoryBlockEntity factory;
    private final int portId;
    private final boolean externalSide;
    private final PortMode mode;

    private MekanismChemicalHandler(NestedFactoryBlockEntity factory, int portId,
                                    boolean externalSide, PortMode mode) {
        this.factory = factory;
        this.portId = portId;
        this.externalSide = externalSide;
        this.mode = mode;
    }

    public static IChemicalHandler forFactory(NestedFactoryBlockEntity factory, Direction side) {
        if (factory == null || side == null || !factory.isLiveResourceTransferMode()) {
            return null;
        }
        PortMode mode = factory.getFaceMode(side);
        if (mode == PortMode.NONE) {
            return null;
        }
        return new MekanismChemicalHandler(factory, factory.getPortId(side), true, mode);
    }

    public static IChemicalHandler forPort(NestedPortBlockEntity port, Direction side) {
        if (port == null || side == null) {
            return null;
        }
        NestedFactoryBlockEntity factory = port.getFactory();
        if (factory == null || !factory.isLiveResourceTransferMode()) {
            return null;
        }
        Direction face = factory.getFaceForPortId(port.getTargetPortId());
        if (face == null || factory.getFaceMode(face) == PortMode.NONE) {
            return null;
        }
        return new MekanismChemicalHandler(factory, port.getTargetPortId(), false,
                factory.getFaceMode(face));
    }

    private boolean isInput() {
        return mode == PortMode.INPUT;
    }

    private boolean hasRoomPort() {
        return factory.getPocketLevel() != null
                && !PocketRegistry.getPorts(factory.roomOrigin(), portId).isEmpty();
    }

    private String ledgerKey() {
        return isInput() ? INPUT_KEY : OUTPUT_KEY;
    }

    private ChemicalLedger ledger() {
        FactoryPortChannels.PortResourceChannel channel = factory.getPortChannel(portId);
        return new ChemicalLedger(channel.extensionData(EXTENSION_NAMESPACE), factory);
    }

    @Override
    public int getChemicalTanks() {
        return factory.isLiveResourceTransferMode() && mode != PortMode.NONE && hasRoomPort() ? 1 : 0;
    }

    @Override
    public ChemicalStack getChemicalInTank(int tank) {
        if (tank != 0 || !factory.isLiveResourceTransferMode()) {
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
        return tank == 0 && factory.isLiveResourceTransferMode() && hasRoomPort() ? Long.MAX_VALUE : 0;
    }

    @Override
    public boolean isValid(int tank, ChemicalStack stack) {
        return tank == 0 && !stack.isEmpty() && factory.isLiveResourceTransferMode()
                && ((externalSide && isInput()) || (!externalSide && !isInput()));
    }

    @Override
    public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {
        if (tank != 0 || stack.isEmpty() || !factory.isLiveResourceTransferMode()) {
            return stack;
        }
        if (externalSide) {
            return isInput() ? insertIntoRoom(stack, action) : stack;
        }
        return isInput() ? stack : insertIntoExternal(stack, action);
    }

    @Override
    public ChemicalStack extractChemical(int tank, long amount, Action action) {
        if (tank != 0 || amount <= 0 || !factory.isLiveResourceTransferMode()) {
            return ChemicalStack.EMPTY;
        }
        if (externalSide) {
            return isInput() ? ChemicalStack.EMPTY : extractFromRoom(amount, action);
        }
        return isInput() ? extractFromExternal(amount, action) : ChemicalStack.EMPTY;
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
        if (stack.isEmpty()) {
            return ChemicalStack.EMPTY;
        }
        ChemicalStack extracted = extractChemical(stack.getAmount(), action);
        return extracted.isEmpty() || !ChemicalStack.isSameChemical(extracted, stack)
                ? ChemicalStack.EMPTY : extracted;
    }

    private ChemicalStack insertIntoRoom(ChemicalStack stack, Action action) {
        ChemicalStack remaining = transferInto(chemicalsInRoom(), stack, action);
        if (!remaining.isEmpty()) {
            remaining = ledger().insert(INPUT_KEY, remaining, action);
        }
        if (action.execute() && !remaining.equals(stack)) {
            factory.setChanged();
        }
        return remaining;
    }

    private ChemicalStack insertIntoExternal(ChemicalStack stack, Action action) {
        ChemicalStack remaining = transferInto(chemicalsOutside(), stack, action);
        if (!remaining.isEmpty()) {
            remaining = ledger().insert(OUTPUT_KEY, remaining, action);
        }
        if (action.execute() && !remaining.equals(stack)) {
            factory.setChanged();
        }
        return remaining;
    }

    private ChemicalStack extractFromRoom(long amount, Action action) {
        ChemicalStack result = ledger().extract(OUTPUT_KEY, amount, action);
        long remaining = amount - result.getAmount();
        if (remaining > 0) {
            ChemicalStack fromRoom = extractFrom(chemicalsInRoom(), remaining, action);
            result = merge(result, fromRoom);
        }
        if (action.execute() && !result.isEmpty()) {
            factory.setChanged();
        }
        return result;
    }

    private ChemicalStack extractFromExternal(long amount, Action action) {
        ChemicalStack result = ledger().extract(INPUT_KEY, amount, action);
        long remaining = amount - result.getAmount();
        if (remaining > 0) {
            ChemicalStack external = extractFrom(chemicalsOutside(), remaining, action);
            result = merge(result, external);
        }
        if (action.execute() && !result.isEmpty()) {
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
            BlockPos pos = factory.getBlockPos().relative(face);
            IChemicalHandler handler = factory.getLevel().getCapability(
                    MekanismLogisticsCompat.CHEMICAL, pos, face.getOpposite());
            if (handler != null && seen.add(handler)) {
                handlers.add(handler);
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
        for (BlockPos portPos : PocketRegistry.getPorts(factory.roomOrigin(), portId)) {
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

    private static ChemicalStack extractFrom(List<IChemicalHandler> sources, long amount, Action action) {
        for (IChemicalHandler source : sources) {
            ChemicalStack result = source.extractChemical(amount, action);
            if (!result.isEmpty()) {
                return result;
            }
        }
        return ChemicalStack.EMPTY;
    }

    private static ChemicalStack merge(ChemicalStack first, ChemicalStack second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty() || !ChemicalStack.isSameChemical(first, second)) return first;
        return first.copyWithAmount(first.getAmount() + second.getAmount());
    }
}

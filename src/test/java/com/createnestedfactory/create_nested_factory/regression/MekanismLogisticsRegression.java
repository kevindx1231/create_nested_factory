package com.createnestedfactory.create_nested_factory.regression;

import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryLogicalPortEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryTransit;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryCapabilityCache;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat;
import com.createnestedfactory.create_nested_factory_logistics.chemical.ChemicalLedger;
import com.createnestedfactory.create_nested_factory_logistics.chemical.MekanismChemicalHandler;
import com.createnestedfactory.create_nested_factory_logistics.chemical.MekanismExtensionChemicalHandler;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalBuilder;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real bridge and ledger; only world I/O and neighboring machines are test doubles. */
public final class MekanismLogisticsRegression {
    private static Chemical a;
    private static Chemical b;
    private static HolderLookup.Provider registries;

    public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        try (var flags = mockStatic(net.neoforged.neoforge.common.util.flag.FeatureFlagLoader.class)) {
            Bootstrap.bootStrap();
        }
    }

    public static void main(String[] args) throws Exception {
        bootstrap();
        Registry.register(MekanismAPI.CHEMICAL_REGISTRY, MekanismAPI.EMPTY_CHEMICAL_NAME, MekanismAPI.EMPTY_CHEMICAL);
        a = Registry.register(MekanismAPI.CHEMICAL_REGISTRY, ResourceLocation.parse("regression:a"),
                new Chemical(ChemicalBuilder.builder()));
        b = Registry.register(MekanismAPI.CHEMICAL_REGISTRY, ResourceLocation.parse("regression:b"),
                new Chemical(ChemicalBuilder.builder()));
        MekanismAPI.CHEMICAL_REGISTRY.freeze();
        registries = HolderLookup.Provider.create(Stream.of(MekanismAPI.CHEMICAL_REGISTRY.asLookup()));
        FactoryTransit.registerParticipant(ChemicalLedger.PARTICIPANT_ID, ChemicalLedger::new);
        StringBuilder failures = new StringBuilder();
        for (boolean external : new boolean[]{false, true}) {
            for (int scenario = 0; scenario < 6; scenario++) {
                try {
                    scenario(external, scenario);
                } catch (Throwable failure) {
                    failures.append("external=").append(external).append(", scenario=").append(scenario)
                            .append(": ").append(failure).append('\n');
                }
            }
        }
        if (!failures.isEmpty()) throw new AssertionError(failures.toString());
        capabilityLifecycle();
        extensionLifecycle();
        System.out.println("Mekanism chemical conservation regressions passed (12 scenarios).");
    }

    private static void capabilityLifecycle() throws Exception {
        Fixture f = new Fixture(true, a, 50);
        FactoryCapabilityCache cache = new FactoryCapabilityCache();
        List<ICapabilityInvalidationListener> listeners = new ArrayList<>();
        doAnswer(call -> { listeners.add(call.getArgument(1)); return null; })
                .when(f.level).registerCapabilityListener(any(BlockPos.class), any());
        when(f.factory.getBlockPos()).thenReturn(BlockPos.ZERO);
        var route = new FactoryLogicalPortEndpoint(f.factory, Direction.NORTH, PortMode.OUTPUT, 1);
        AtomicInteger allocations = new AtomicInteger();
        java.util.function.Supplier<IChemicalHandler> create = () -> {
            allocations.incrementAndGet();
            return MekanismChemicalHandler.forExtension(route);
        };
        IChemicalHandler first = cache.get(f.factory, MekanismLogisticsCompat.CHEMICAL, Direction.NORTH, route, create);
        for (int i = 0; i < 100; i++) {
            require(first == cache.get(f.factory, MekanismLogisticsCompat.CHEMICAL, Direction.NORTH, route, create),
                    "provider did not reuse its stable handler");
        }
        require(allocations.get() == 1, "repeated capability query allocated handlers");
        listeners.getFirst().onInvalidate();
        IChemicalHandler restored = cache.get(f.factory, MekanismLogisticsCompat.CHEMICAL, Direction.NORTH, route, create);
        require(restored != first && allocations.get() == 2, "invalidated provider retained old handler");
        when(f.factory.isRemoved()).thenReturn(true);
        require(cache.get(f.factory, MekanismLogisticsCompat.CHEMICAL, Direction.NORTH, route, create) == null,
                "removed block still provided capability");
        require(first.extractChemical(10, Action.EXECUTE).isEmpty(), "retained handler transmitted after removal");
        when(f.factory.isRemoved()).thenReturn(false);
        IChemicalHandler reloaded = cache.get(f.factory, MekanismLogisticsCompat.CHEMICAL, Direction.NORTH, route, create);
        require(reloaded != restored, "same-position reload retained invalidated handler/listener");
        when(f.factory.getBlockPos()).thenReturn(new BlockPos(20, 0, 0));
        require(cache.get(f.factory, MekanismLogisticsCompat.CHEMICAL, Direction.NORTH, route, create) != reloaded,
                "moving block retained its old-location cache");
        require(!listeners.getFirst().onInvalidate(), "old-location listener survived relocation");
        PocketRegistry.releaseServer(f.server);
    }

    private static void extensionLifecycle() {
        NestedExtensionInterfaceBlockEntity extension = mock(NestedExtensionInterfaceBlockEntity.class);
        NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        when(factory.isLiveResourceTransferMode()).thenReturn(true);
        var route = new FactoryLogicalPortEndpoint(factory, Direction.NORTH, PortMode.OUTPUT, 1);
        when(extension.isExternalSideAvailable(Direction.UP)).thenReturn(true);
        when(extension.resolvePort(Direction.UP)).thenReturn(route);
        IChemicalHandler delegate = mock(IChemicalHandler.class);
        when(delegate.getChemicalTanks()).thenReturn(1);
        ChemicalStack requested = new ChemicalStack(a, 10);
        when(delegate.extractChemical(requested, Action.SIMULATE)).thenReturn(requested);
        try (var handlers = mockStatic(MekanismChemicalHandler.class)) {
            handlers.when(() -> MekanismChemicalHandler.forExtension(route)).thenReturn(delegate);
            IChemicalHandler proxy = MekanismExtensionChemicalHandler.create(extension, Direction.UP);
            for (int i = 0; i < 100; i++) require(proxy.getChemicalTanks() == 1, "extension did not delegate");
            handlers.verify(() -> MekanismChemicalHandler.forExtension(route), times(1));
            proxy.extractChemical(requested, Action.SIMULATE);
            verify(delegate).extractChemical(requested, Action.SIMULATE);
            when(extension.resolvePort(Direction.UP)).thenReturn(null);
            require(proxy.getChemicalTanks() == 0 && proxy.extractChemical(requested, Action.EXECUTE).isEmpty(),
                    "disabled/unbound extension transmitted resources");
            when(extension.resolvePort(Direction.UP)).thenReturn(route);
            require(proxy.getChemicalTanks() == 1, "reenabled extension did not recover");
            handlers.verify(() -> MekanismChemicalHandler.forExtension(route), times(2));
            var rebound = new FactoryLogicalPortEndpoint(factory, Direction.SOUTH, PortMode.INPUT, 2);
            handlers.when(() -> MekanismChemicalHandler.forExtension(rebound)).thenReturn(delegate);
            when(extension.resolvePort(Direction.UP)).thenReturn(rebound);
            proxy.getChemicalTanks();
            handlers.verify(() -> MekanismChemicalHandler.forExtension(rebound), times(1));
            when(factory.isLiveResourceTransferMode()).thenReturn(false);
            require(proxy.getChemicalTanks() == 0, "inactive host retained delegate");
        }
    }

    private static void scenario(boolean external, int scenario) throws Exception {
        Fixture f = new Fixture(external, scenario == 2 ? a : b, scenario == 4 ? 0 : 50);
        var before = f.ledger.write(registries);
        if (scenario == 0) {
            require(f.handler.extractChemical(new ChemicalStack(b, 10), Action.EXECUTE).isEmpty(), "wrong type returned");
            require(f.buffered() == 50 && f.sourceAmount == 50, "wrong-type request destroyed resources: buffered=" + f.buffered());
            require(before.equals(f.ledger.write(registries)), "rejected request changed persistent ledger");
            ChemicalLedger loaded = new ChemicalLedger();
            loaded.read(before, registries);
            require(before.equals(loaded.write(registries)), "ledger format did not round-trip");
            verify(f.factory, never()).setChanged();
        } else if (scenario == 1) {
            ChemicalStack taken = f.handler.extractChemical(100, Action.EXECUTE);
            require(taken.getChemical() == a && taken.getAmount() == 50 && f.sourceAmount == 50,
                    "mixed source was deducted without delivery: source=" + f.sourceAmount);
            require(f.ledger.blocksLearning() == !external, "historical output was counted as new boundary transfer");
        } else if (scenario == 2) {
            ChemicalStack taken = f.handler.extractChemical(new ChemicalStack(a, 75), Action.EXECUTE);
            require(taken.getAmount() == 75 && f.buffered() == 0 && f.sourceAmount == 25, "matching refill lost resources");
            require(f.ledger.blocksLearning(), "real machine transfer was not recorded");
        } else if (scenario == 3) {
            for (int i = 0; i < 3; i++) f.handler.extractChemical(100, Action.SIMULATE);
            require(before.equals(f.ledger.write(registries)) && f.sourceAmount == 50, "simulation changed ownership");
            verify(f.factory, never()).setChanged();
            require(f.handler.extractChemical(100, Action.EXECUTE).getAmount() == 50 && f.sourceAmount == 50,
                    "execute after simulation lost the second chemical");
        } else if (scenario == 4) {
            require(f.handler.extractChemical(new ChemicalStack(a, 10), Action.EXECUTE).isEmpty()
                    && f.sourceAmount == 50, "empty buffer wrong-type request consumed source");
            ChemicalStack taken = f.handler.extractChemical(10, Action.EXECUTE);
            require(taken.getChemical() == b && taken.getAmount() == 10 && f.sourceAmount == 40, "source discovery failed");
        } else {
            f.handler.extractChemical(-1, Action.EXECUTE);
            f.handler.extractChemical(1, 10, Action.EXECUTE);
            f.handler.extractChemical(ChemicalStack.EMPTY, Action.EXECUTE);
            require(before.equals(f.ledger.write(registries)) && f.sourceAmount == 50, "invalid request changed resources");
        }
        PocketRegistry.releaseServer(f.server);
    }

    private static final class Fixture {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel level = mock(ServerLevel.class);
        final NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        final ChemicalLedger ledger;
        final IChemicalHandler handler;
        final String key;
        long sourceAmount = 50;

        Fixture(boolean external, Chemical sourceType, long buffered) throws Exception {
            key = external ? "OutputChemicals" : "InputChemicals";
            FactoryTransit transit = new FactoryTransit();
            var channel = transit.channel(1);
            ledger = channel.participant(ChemicalLedger.PARTICIPANT_ID, ChemicalLedger.class);
            ledger.sealInputGeneration();
            if (buffered > 0) {
                Method insert = ChemicalLedger.class.getDeclaredMethod("insert", String.class, ChemicalStack.class, Action.class);
                insert.setAccessible(true);
                insert.invoke(ledger, key, new ChemicalStack(a, buffered), Action.EXECUTE);
            }
            when(factory.getPortChannel(1)).thenReturn(channel);
            when(factory.isLiveResourceTransferMode()).thenReturn(true);
            when(factory.getPocketLevel()).thenReturn(level);
            when(factory.getLevel()).thenReturn(level);
            when(factory.roomOrigin()).thenReturn(BlockPos.ZERO);
            when(factory.getFaceMode(Direction.NORTH)).thenReturn(external ? PortMode.OUTPUT : PortMode.INPUT);
            when(factory.getPortId(Direction.NORTH)).thenReturn(1);
            var endpoint = new FactoryLogicalPortEndpoint(factory, Direction.NORTH,
                    external ? PortMode.OUTPUT : PortMode.INPUT, 1);
            when(factory.resolveLogicalPort(1)).thenReturn(endpoint);
            when(factory.getFacesForPortId(1)).thenReturn(List.of(Direction.NORTH));
            when(factory.getExternalAccessPoints(Direction.NORTH)).thenReturn(List.of(
                    new NestedFactoryBlockEntity.ExternalAccessPoint(BlockPos.ZERO, Direction.NORTH)));
            when(level.getServer()).thenReturn(server);
            NestedPortBlockEntity port = mock(NestedPortBlockEntity.class);
            when(port.getFactory()).thenReturn(factory);
            when(port.getTargetPortId()).thenReturn(1);
            BlockPos portPos = new BlockPos(10, 0, 0);
            when(level.getBlockEntity(portPos)).thenReturn(port);
            PocketRegistry.registerPort(server, BlockPos.ZERO, 1, portPos);
            IChemicalHandler source = mock(IChemicalHandler.class);
            when(source.extractChemical(anyLong(), any())).thenAnswer(call -> take(sourceType, call.getArgument(0), call.getArgument(1)));
            when(source.extractChemical(any(ChemicalStack.class), any())).thenAnswer(call -> {
                ChemicalStack request = call.getArgument(0);
                return request.getChemical() == sourceType ? take(sourceType, request.getAmount(), call.getArgument(1)) : ChemicalStack.EMPTY;
            });
            when(level.getCapability(eq(MekanismLogisticsCompat.CHEMICAL), any(BlockPos.class), any(Direction.class))).thenReturn(source);
            handler = external ? MekanismChemicalHandler.forExtension(endpoint) : MekanismChemicalHandler.forPort(port, Direction.NORTH);
        }

        ChemicalStack take(Chemical chemical, long amount, Action action) {
            long taken = Math.min(amount, sourceAmount);
            if (action.execute()) sourceAmount -= taken;
            return taken == 0 ? ChemicalStack.EMPTY : new ChemicalStack(chemical, taken);
        }

        long buffered() throws Exception {
            Method peek = ChemicalLedger.class.getDeclaredMethod("peek", String.class);
            peek.setAccessible(true);
            return ((ChemicalStack) peek.invoke(ledger, key)).getAmount();
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

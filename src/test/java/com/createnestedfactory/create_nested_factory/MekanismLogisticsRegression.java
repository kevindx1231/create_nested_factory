package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryTransit;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryLogicalPortEndpoint;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import com.createnestedfactory.create_nested_factory.compat.LegacyCapabilities;
import com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat;
import com.createnestedfactory.create_nested_factory_logistics.chemical.MekanismGasHandler;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.registries.IForgeRegistry;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.*;

/** Runs real chemical endpoints and ledgers; only registries, worlds and remote machines are fixtures. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class MekanismLogisticsRegression {
    private static final String COMPAT = "com.createnestedfactory.create_nested_factory_logistics.chemical.";
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var capabilities = mockStatic(CapabilityManager.class)) {
            capabilities.when(() -> CapabilityManager.get(any(CapabilityToken.class)))
                    .thenAnswer(call -> mock(net.minecraftforge.common.capabilities.Capability.class));
            for (String[] type : new String[][]{
                    {"Gas", "gas", "Gas", "GAS_REGISTRY"},
                    {"Infusion", "infuse", "InfuseType", "INFUSE_TYPE_REGISTRY"},
                    {"Pigment", "pigment", "Pigment", "PIGMENT_REGISTRY"},
                    {"Slurry", "slurry", "Slurry", "SLURRY_REGISTRY"}}) {
                prepareRegistry(type);
                for (boolean external : new boolean[]{false, true}) {
                    check(type[0] + " typed " + external, () -> typedExtraction(type, external));
                    check(type[0] + " mixed " + external, () -> mixedExtraction(type, external));
                    check(type[0] + " matching " + external, () -> matchingExtraction(type, external));
                    check(type[0] + " simulated " + external, () -> simulatedExtraction(type, external));
                    check(type[0] + " empty cache " + external, () -> emptyCacheExtraction(type, external));
                    check(type[0] + " restart " + external, () -> ledgerRoundTrip(type, external));
                }
                check(type[0] + " extension", () -> extensionExtraction(type));
            }
            MekanismLogisticsCompat.initialize(mock(net.minecraftforge.eventbus.api.IEventBus.class));
            check("gas capability allocation", MekanismLogisticsRegression::capabilityAllocation);
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join(System.lineSeparator(), failures));
        System.out.println("Four Mekanism chemical families: typed, mixed, matching and simulated extraction passed.");
    }

    private static void prepareRegistry(String[] type) throws Exception {
        Class chemical = Class.forName("mekanism.api.chemical." + type[1] + "." + type[2]);
        IForgeRegistry registry = mock(IForgeRegistry.class);
        Map<ResourceLocation, Object> entries = new HashMap<>();
        when(registry.getKey(any())).thenAnswer(call -> {
            ResourceLocation id = new ResourceLocation("cnf_test", "chemical_" + System.identityHashCode(call.getArgument(0)));
            entries.put(id, call.getArgument(0));
            return id;
        });
        when(registry.getValue(any(ResourceLocation.class))).thenAnswer(call -> entries.get(call.getArgument(0)));
        when(registry.getDelegateOrThrow(any(chemical))).thenAnswer(call -> {
            Holder.Reference reference = mock(Holder.Reference.class);
            when(reference.get()).thenReturn(call.getArgument(0));
            return reference;
        });
        var field = MekanismAPI.class.getDeclaredField(type[3]);
        field.setAccessible(true);
        field.set(null, registry);
        Class ledger = Class.forName(COMPAT + type[0] + "Ledger");
        String id = (String) ledger.getField("PARTICIPANT_ID").get(null);
        FactoryTransit.registerParticipant(id, () -> {
            try { return (com.createnestedfactory.create_nested_factory.block.entity.TransitParticipant)
                    ledger.getConstructor().newInstance(); }
            catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
        });
    }

    private static void typedExtraction(String[] type, boolean external) throws Exception {
        try (Fixture f = new Fixture(type, external, false)) {
            ChemicalStack result = f.handler.extractChemical(f.stack(f.second, 10), Action.EXECUTE);
            require(result.isEmpty() && f.bufferedAmount() == 50 && f.sourceAmount.get() == 50,
                    "wrong-type request changed ownership: buffered=" + f.bufferedAmount() + ", source=" + f.sourceAmount);
        }
    }

    private static void mixedExtraction(String[] type, boolean external) throws Exception {
        try (Fixture f = new Fixture(type, external, false)) {
            ChemicalStack result = f.handler.extractChemical(100, Action.EXECUTE);
            require(result.getAmount() == 50 && result.getType() == f.first
                            && f.bufferedAmount() == 0 && f.sourceAmount.get() == 50,
                    "mixed extraction discarded another type: returned=" + result.getAmount() + ", source=" + f.sourceAmount);
        }
    }

    private static void matchingExtraction(String[] type, boolean external) throws Exception {
        try (Fixture f = new Fixture(type, external, true)) {
            ChemicalStack result = f.handler.extractChemical(f.stack(f.first, 75), Action.EXECUTE);
            require(result.getAmount() == 75 && f.bufferedAmount() == 0 && f.sourceAmount.get() == 25,
                    "matching cache/source extraction did not conserve amounts");
        }
    }

    private static void simulatedExtraction(String[] type, boolean external) throws Exception {
        try (Fixture f = new Fixture(type, external, true)) {
            for (int i = 0; i < 3; i++) {
                require(f.handler.extractChemical(f.stack(f.first, 75), Action.SIMULATE).getAmount() == 75,
                        "simulation did not predict matching extraction");
            }
            require(f.bufferedAmount() == 50 && f.sourceAmount.get() == 50, "simulation mutated resource ownership");
            verify(f.factory, never()).setChanged();
            require(f.handler.extractChemical(75, Action.EXECUTE).getAmount() == 75
                    && f.bufferedAmount() == 0 && f.sourceAmount.get() == 25, "execution after simulations duplicated resources");
        }
    }

    private static void capabilityAllocation() throws Exception {
        try (Fixture f = new Fixture(new String[]{"Gas", "gas", "Gas", "GAS_REGISTRY"}, true, true)) {
            // End the world-query fixture: this check exercises the real capability cache.
            f.sources.close();
            try (var providers = mockStatic(MekanismGasHandler.class, CALLS_REAL_METHODS)) {
                var cache = new LegacyCapabilities.Cache();
                var first = cache.get(f.factory, MekanismLogisticsCompat.GAS, Direction.NORTH);
                require(first.isPresent() && cache.get(f.factory, MekanismLogisticsCompat.GAS, Direction.NORTH) == first,
                        "gas capability was absent or unstable");
                providers.verify(() -> MekanismGasHandler.forFactory(f.factory, Direction.NORTH), times(1));
                when(f.factory.isLiveResourceTransferMode()).thenReturn(false);
                require(!cache.get(f.factory, MekanismLogisticsCompat.GAS, Direction.NORTH).isPresent()
                        && !first.isPresent(), "cached capability survived mode disable");
                when(f.factory.isLiveResourceTransferMode()).thenReturn(true);
                require(cache.get(f.factory, MekanismLogisticsCompat.GAS, Direction.NORTH).isPresent(),
                        "absent capability was cached permanently");
            }
        }
    }

    private static void emptyCacheExtraction(String[] type, boolean external) throws Exception {
        try (Fixture f = new Fixture(type, external, false)) {
            var extract = f.ledger.getClass().getDeclaredMethod("extract", String.class, long.class, Action.class);
            extract.setAccessible(true);
            extract.invoke(f.ledger, f.key, 50L, Action.EXECUTE);
            require(f.handler.extractChemical(f.stack(f.first, 10), Action.EXECUTE).isEmpty()
                    && f.sourceAmount.get() == 50, "typed request drained a different live source");
            ChemicalStack preview = f.handler.extractChemical(20, Action.SIMULATE);
            require(preview.getType() == f.second && preview.getAmount() == 20 && f.sourceAmount.get() == 50,
                    "empty cache simulation selected or consumed the wrong source");
            require(f.handler.extractChemical(20, Action.EXECUTE).getAmount() == 20 && f.sourceAmount.get() == 30,
                    "empty cache could not extract a discovered source");
        }
    }

    private static void ledgerRoundTrip(String[] type, boolean external) throws Exception {
        try (Fixture f = new Fixture(type, external, true)) {
            var participant = (com.createnestedfactory.create_nested_factory.block.entity.TransitParticipant) f.ledger;
            CompoundTag before = participant.write(null);
            f.handler.extractChemical(f.stack(f.second, 10), Action.EXECUTE);
            require(before.equals(participant.write(null)), "rejected extraction changed persisted ledger state");
            participant.read(before, null);
            require(f.bufferedAmount() == 50, "existing chemical ledger format did not round trip");
        }
    }

    private static void extensionExtraction(String[] type) throws Exception {
        try (Fixture f = new Fixture(type, true, true)) {
            NestedExtensionInterfaceBlockEntity extension = mock(NestedExtensionInterfaceBlockEntity.class);
            AtomicBoolean powered = new AtomicBoolean();
            when(extension.isExternalSideAvailable(Direction.EAST)).thenAnswer(call -> !powered.get());
            when(extension.resolvePort(Direction.EAST)).thenAnswer(call -> powered.get() ? null
                    : f.factory.resolveLogicalPort(Direction.NORTH));
            Class proxyType = Class.forName(COMPAT + "MekanismExtension" + type[0] + "Handler");
            IChemicalHandler proxy = (IChemicalHandler) proxyType.getMethod("create",
                    NestedExtensionInterfaceBlockEntity.class, Direction.class).invoke(null, extension, Direction.EAST);
            require(proxy != null && proxy.extractChemical(f.stack(f.second, 10), Action.EXECUTE).isEmpty()
                    && f.bufferedAmount() == 50, "extension lost resources on a wrong-type request");
            powered.set(true);
            require(proxy.extractChemical(10, Action.EXECUTE).isEmpty() && f.bufferedAmount() == 50,
                    "cached extension ignored redstone disable");
            powered.set(false);
            require(proxy.extractChemical(10, Action.EXECUTE).getAmount() == 10 && f.bufferedAmount() == 40,
                    "cached extension did not recover after redstone enable");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Class stackType;
        final Chemical first;
        final Chemical second;
        final Object ledger;
        final Method peek;
        final String key;
        final IChemicalHandler handler;
        final NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        final AtomicLong sourceAmount = new AtomicLong(50);

        Fixture(String[] type, boolean external, boolean sameSourceType) throws Exception {
            Class chemicalType = Class.forName("mekanism.api.chemical." + type[1] + "." + type[2]);
            var chemicalConstructor = chemicalType.getConstructors()[0];
            Class builder = chemicalConstructor.getParameterTypes()[0];
            Method makeBuilder = builder.getMethod(type[0].equals("Slurry") ? "clean" : "builder");
            first = (Chemical) chemicalConstructor.newInstance(makeBuilder.invoke(null));
            second = (Chemical) chemicalConstructor.newInstance(makeBuilder.invoke(null));
            stackType = Class.forName("mekanism.api.chemical." + type[1] + "." + type[0] + "Stack");
            Class ledgerType = Class.forName(COMPAT + type[0] + "Ledger");
            var channel = new FactoryTransit().channel(1);
            ledger = channel.participant((String) ledgerType.getField("PARTICIPANT_ID").get(null), ledgerType);
            key = external ? "OutputChemicals" : "InputChemicals";
            var insert = ledgerType.getDeclaredMethod("insert", String.class, stackType, Action.class);
            insert.setAccessible(true);
            insert.invoke(ledger, key, stack(first, 50), Action.EXECUTE);
            peek = ledgerType.getDeclaredMethod("peek", String.class);
            peek.setAccessible(true);
            PortMode mode = external ? PortMode.OUTPUT : PortMode.INPUT;
            when(factory.isLiveResourceTransferMode()).thenReturn(true);
            when(factory.getFaceMode(Direction.NORTH)).thenReturn(mode);
            when(factory.getPortId(Direction.NORTH)).thenReturn(1);
            when(factory.resolveLogicalPort(Direction.NORTH)).thenReturn(
                    new FactoryLogicalPortEndpoint(factory, Direction.NORTH, mode, 1));
            when(factory.resolveLogicalPort(1)).thenReturn(
                    new FactoryLogicalPortEndpoint(factory, Direction.NORTH, mode, 1));
            when(factory.getPortChannel(1)).thenReturn(channel);
            when(factory.getFacesForPortId(1)).thenReturn(List.of(Direction.NORTH));
            when(factory.getExternalAccessPoints(Direction.NORTH)).thenReturn(List.of(
                    new NestedFactoryBlockEntity.ExternalAccessPoint(BlockPos.ZERO, Direction.NORTH)));
            ServerLevel world = mock(ServerLevel.class);
            when(factory.getLevel()).thenReturn(world);
            when(factory.getPocketLevel()).thenReturn(world);
            when(factory.roomOrigin()).thenReturn(BlockPos.ZERO);
            MinecraftServer server = mock(MinecraftServer.class);
            when(world.getServer()).thenReturn(server);
            NestedPortBlockEntity port = mock(NestedPortBlockEntity.class);
            when(port.getTargetPortId()).thenReturn(1);
            when(world.getBlockEntity(BlockPos.ZERO)).thenReturn(port);
            Class handlerType = Class.forName(COMPAT + "Mekanism" + type[0] + "Handler");
            var constructor = handlerType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            handler = (IChemicalHandler) constructor.newInstance(factory, 1, external, mode, Direction.NORTH, false);
            IChemicalHandler source = (IChemicalHandler) mock(Class.forName(
                    "mekanism.api.chemical." + type[1] + ".I" + type[0] + "Handler"));
            Chemical sourceType = sameSourceType ? first : second;
            when(source.getTanks()).thenReturn(1);
            when(source.getChemicalInTank(0)).thenAnswer(call -> stack(sourceType, sourceAmount.get()));
            when(source.extractChemical(anyLong(), any())).thenAnswer(call -> take(sourceType, call.getArgument(0), call.getArgument(1)));
            when(source.extractChemical(any(ChemicalStack.class), any())).thenAnswer(call -> {
                ChemicalStack request = call.getArgument(0);
                return request.getType() == sourceType ? take(sourceType, request.getAmount(), call.getArgument(1))
                        : stack(sourceType, 0);
            });
            // Scope these fixtures to endpoint traversal, without changing the actual ledger or handler code.
            sources = mockStatic(LegacyCapabilities.class);
            sources.when(() -> LegacyCapabilities.get(any(), any(), any(), any())).thenReturn(source);
            ports = mockStatic(PocketRegistry.class);
            ports.when(() -> PocketRegistry.getPorts(server, BlockPos.ZERO, 1)).thenReturn(Set.of(BlockPos.ZERO));
        }

        final org.mockito.MockedStatic<LegacyCapabilities> sources;
        final org.mockito.MockedStatic<PocketRegistry> ports;

        ChemicalStack stack(Chemical chemical, long amount) throws Exception {
            for (var constructor : stackType.getConstructors()) {
                if (constructor.getParameterTypes()[0].isInstance(chemical))
                    return (ChemicalStack) constructor.newInstance(chemical, amount);
            }
            throw new IllegalStateException("Missing chemical stack constructor");
        }

        ChemicalStack take(Chemical chemical, long amount, Action action) throws Exception {
            long taken = Math.min(amount, sourceAmount.get());
            if (action.execute()) sourceAmount.addAndGet(-taken);
            return stack(chemical, taken);
        }

        long bufferedAmount() throws Exception { return ((ChemicalStack) peek.invoke(ledger, key)).getAmount(); }
        public void close() { ports.close(); sources.closeOnDemand(); }
    }

    private static void check(String name, CheckedRunnable test) {
        try { test.run(); }
        catch (Throwable exception) { failures.add(name + ": " + exception); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private interface CheckedRunnable { void run() throws Exception; }
}

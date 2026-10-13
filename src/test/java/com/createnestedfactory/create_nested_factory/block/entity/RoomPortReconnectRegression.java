package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.NestedFactorySaveData;
import com.createnestedfactory.create_nested_factory.PocketChunkForceManager;
import com.createnestedfactory.create_nested_factory.PocketFreezeHooks;
import com.createnestedfactory.create_nested_factory.PocketFreezeSavedData;
import com.createnestedfactory.create_nested_factory.PocketRegistry;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.compat.LegacyCapabilities;
import com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

/** Replays a consumer that stops listening after a null capability, then checks real port neighbor notifications. */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class RoomPortReconnectRegression {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<String> failures = new ArrayList<>();
        try (var capabilities = mockStatic(CapabilityManager.class)) {
            capabilities.when(() -> CapabilityManager.get(any(CapabilityToken.class)))
                    .thenAnswer(call -> mock(Capability.class));
            MekanismLogisticsCompat.initialize(null);
            for (OperationMode mode : List.of(OperationMode.BLACKBOX_ACTIVE, OperationMode.BLUEPRINT)) {
                check(failures, "item " + mode, () -> restoration(ForgeCapabilities.ITEM_HANDLER, mode));
                check(failures, "fluid " + mode, () -> restoration(ForgeCapabilities.FLUID_HANDLER, mode));
                check(failures, "gas " + mode, () -> restoration(MekanismLogisticsCompat.GAS, mode));
            }
            check(failures, "preparing to learning", RoomPortReconnectRegression::preparingToLearning);
            check(failures, "preparing cancelled", () -> restoration(MekanismLogisticsCompat.GAS,
                    OperationMode.BLACKBOX_PREPARING));
            for (FactoryLifecycleTransitions.Event repair : List.of(
                    FactoryLifecycleTransitions.Event.REPAIR_INCOMPLETE_PLAN,
                    FactoryLifecycleTransitions.Event.REPAIR_MISSING_BLUEPRINT,
                    FactoryLifecycleTransitions.Event.REPAIR_MISSING_FREEZE_MANIFEST)) {
                check(failures, repair.name(), () -> repairedMode(repair));
            }
            check(failures, "late port load", RoomPortReconnectRegression::latePortLoad);
            check(failures, "thaw completion", RoomPortReconnectRegression::thawCompletion);
            check(failures, "deferred/coalesced notification", RoomPortReconnectRegression::boundedRefresh);
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join(System.lineSeparator(), failures));
        System.out.println("Room port reconnect regressions passed: modes, late loads, thaw, unloaded neighbors and bounded notifications.");
    }

    private static <T> void restoration(Capability<T> capability, OperationMode disabled) throws Exception {
        try (Fixture f = new Fixture()) {
            Watcher<T> watcher = f.watch(capability);
            f.disable(watcher, disabled);
            f.transition(disabled == OperationMode.BLUEPRINT
                    ? FactoryLifecycleTransitions.Event.CANCEL_BLUEPRINT_TO_LOADED
                    : FactoryLifecycleTransitions.Event.STOP_BLACKBOX);
            require(!watcher.connected, "consumer reconnected without a neighbor notification");
            f.tickPort();
            require(watcher.connected, "available room capability was not rediscovered after " + disabled);
            require(f.notifications.get() == 6, "restoration did not notify exactly six loaded neighbors");
            f.tickPort();
            require(f.notifications.get() == 6, "healthy port was refreshed again on the next tick");
        }
    }

    private static void preparingToLearning() throws Exception {
        try (Fixture f = new Fixture()) {
            var watcher = f.watch(MekanismLogisticsCompat.GAS);
            f.disable(watcher, OperationMode.BLACKBOX_PREPARING);
            f.transition(FactoryLifecycleTransitions.Event.ENTER_LEARNING);
            f.tickPort();
            require(watcher.connected, "gas consumer remained disconnected after entering learning");
        }
    }

    private static void latePortLoad() throws Exception {
        try (Fixture f = new Fixture()) {
            var watcher = f.watch(ForgeCapabilities.ITEM_HANDLER);
            f.disable(watcher, OperationMode.BLACKBOX_ACTIVE);
            f.portLoaded.set(false);
            clearInvocations(f.pocket);
            f.transition(FactoryLifecycleTransitions.Event.STOP_BLACKBOX);
            verify(f.pocket, never()).getBlockEntity(Fixture.PORT);
            f.portLoaded.set(true);
            f.port.onLoad();
            f.tickPort();
            require(watcher.connected, "late-loaded room port did not announce its restored capability");
        }
    }

    private static void repairedMode(FactoryLifecycleTransitions.Event repair) throws Exception {
        try (Fixture f = new Fixture()) {
            var watcher = f.watch(ForgeCapabilities.ITEM_HANDLER);
            f.disable(watcher, OperationMode.BLUEPRINT);
            f.transition(repair);
            f.tickPort();
            require(watcher.connected, "repair did not announce restored room endpoints: " + repair);
        }
    }

    private static void thawCompletion() throws Exception {
        try (Fixture f = new Fixture()) {
            var watcher = f.watch(ForgeCapabilities.FLUID_HANDLER);
            f.disable(watcher, OperationMode.BLACKBOX_ACTIVE);
            f.mode(OperationMode.CHUNK_LOADED);
            BlockPos childHost = new BlockPos(4, 65, 4);
            BlockPos childOrigin = BlockPos.ZERO;
            BlockPos childPortPos = new BlockPos(2, 1, 2);
            NestedPortBlockEntity childPort = mock(NestedPortBlockEntity.class);
            NestedFactoryBlockEntity child = mock(NestedFactoryBlockEntity.class, call -> {
                if (List.of("setAncestorFrozen", "requestRoomPortConnectionRefresh")
                        .contains(call.getMethod().getName())) return call.callRealMethod();
                return RETURNS_DEFAULTS.answer(call);
            });
            field(BlockEntity.class, child, "level", f.pocket);
            field(NestedFactoryBlockEntity.class, child, "ancestorFrozen", true);
            when(child.getLevel()).thenReturn(f.pocket);
            when(child.getFactoryId()).thenReturn("child");
            when(child.hasPhysicalRoom()).thenReturn(true);
            when(child.roomOrigin()).thenReturn(childOrigin);
            when(f.pocket.getBlockEntity(childHost)).thenReturn(child);
            when(f.pocket.getBlockEntity(childPortPos)).thenReturn(childPort);
            f.registry.when(() -> PocketRegistry.getPorts(f.server, childOrigin, 1))
                    .thenReturn(Set.of(childPortPos));
            f.slots.observeFactoryParent("child", "root");
            f.freeze.beginFreeze("root", List.of(
                    new PocketFreezeSavedData.FrozenRoom("root", "minecraft:overworld", Fixture.HOST,
                            0, 64, 0, 15, 79, 15, 0),
                    new PocketFreezeSavedData.FrozenRoom("child",
                            NestedFactoryBlock.POCKET_DIMENSION.location().toString(), childHost,
                            0, 0, 0, 15, 15, 15, 0)));
            f.freeze.beginThaw("root");
            f.slots.acquireFreezeLease("root");
            ServerChunkCache chunks = mock(ServerChunkCache.class);
            AtomicBoolean ready = new AtomicBoolean();
            when(f.pocket.getChunkSource()).thenReturn(chunks);
            when(chunks.hasChunk(anyInt(), anyInt())).thenAnswer(call -> ready.get());
            PocketFreezeManager.tick(f.server);
            f.tickPort();
            require(f.notifications.get() == 0 && !watcher.connected,
                    "port notified consumers before thaw chunks became ready");
            ready.set(true);
            PocketFreezeManager.tick(f.server);
            require(!f.freeze.hasTree("root"), "ready thaw did not remove its freeze manifest");
            Field ancestor = NestedFactoryBlockEntity.class.getDeclaredField("ancestorFrozen");
            ancestor.setAccessible(true);
            require(!ancestor.getBoolean(child), "thaw did not release the child's ancestor freeze flag");
            verify(childPort).requestLogisticsConnectionRefresh();
            f.tickPort();
            require(watcher.connected, "completed thaw did not request a room port reconnect");
        }
    }

    private static void boundedRefresh() throws Exception {
        try (Fixture f = new Fixture()) {
            var watcher = f.watch(ForgeCapabilities.ITEM_HANDLER);
            f.disable(watcher, OperationMode.BLUEPRINT);
            f.frozen.set(true);
            f.transition(FactoryLifecycleTransitions.Event.CANCEL_BLUEPRINT_TO_LOADED);
            f.requestPortRefresh();
            f.requestPortRefresh();
            f.tickPort();
            require(f.notifications.get() == 0, "frozen room announced a restored endpoint");
            f.frozen.set(false);
            f.unloadedNeighbor = Fixture.PORT.relative(Direction.EAST);
            f.tickPort();
            require(watcher.connected, "deferred port request did not reconnect after unfreeze");
            require(f.notifications.get() == 5, "unloaded neighbor was notified or duplicate requests were not coalesced");
            verify(f.pocket, never()).neighborChanged(eq(f.unloadedNeighbor), any(), any());
            f.tickPort();
            require(f.notifications.get() == 5, "neighbor callbacks caused recurring refreshes");
        }
    }

    private static final class Watcher<T> {
        private final Fixture fixture;
        private final Capability<T> capability;
        private LazyOptional<T> observed;
        private boolean connected;

        private Watcher(Fixture fixture, Capability<T> capability) {
            this.fixture = fixture;
            this.capability = capability;
            refresh();
        }

        private void refresh() {
            LazyOptional<T> endpoint = fixture.cache.get(fixture.port, capability, Direction.NORTH);
            connected = endpoint.isPresent();
            // Like Mekanism, attach a listener only when a new present capability is discovered.
            if (connected && endpoint != observed) {
                observed = endpoint;
                endpoint.addListener(ignored -> refresh());
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private static final BlockPos HOST = new BlockPos(40, 64, 40);
        private static final BlockPos PORT = new BlockPos(2, 65, 2);
        private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final ServerLevel exterior = mock(ServerLevel.class);
        private final ServerLevel pocket = mock(ServerLevel.class);
        private final NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class, call -> {
            if (List.of("requestRoomPortConnectionRefresh", "getRoomItemHandler", "getRoomFluidHandler",
                    "isLiveResourceTransferMode", "onRoomPortLogisticsConnectionChanged")
                    .contains(call.getMethod().getName())) return call.callRealMethod();
            return RETURNS_DEFAULTS.answer(call);
        });
        private final NestedPortBlockEntity port = mock(NestedPortBlockEntity.class, call -> {
            if (List.of("requestLogisticsConnectionRefresh", "invalidatePortCapabilities", "getItemHandler",
                    "getFluidHandler", "onLoad").contains(call.getMethod().getName())) return call.callRealMethod();
            return RETURNS_DEFAULTS.answer(call);
        });
        private final LegacyCapabilities.Cache cache = new LegacyCapabilities.Cache();
        private final PocketFreezeSavedData freeze = new PocketFreezeSavedData();
        private final NestedFactorySaveData slots = new NestedFactorySaveData();
        private final AtomicBoolean portLoaded = new AtomicBoolean(true);
        private final AtomicBoolean frozen = new AtomicBoolean();
        private final AtomicInteger notifications = new AtomicInteger();
        private BlockPos unloadedNeighbor;
        private Watcher<?> watcher;
        private final MockedStatic<PocketRegistry> registry = mockStatic(PocketRegistry.class);
        private final MockedStatic<NestedFactoryBlock> factories = mockStatic(NestedFactoryBlock.class);
        private final MockedStatic<PocketFreezeHooks> hooks = mockStatic(PocketFreezeHooks.class);
        private final MockedStatic<PocketFreezeSavedData> freezeAccess = mockStatic(PocketFreezeSavedData.class);
        private final MockedStatic<NestedFactorySaveData> slotAccess = mockStatic(NestedFactorySaveData.class);
        private final MockedStatic<PocketChunkForceManager> tickets = mockStatic(PocketChunkForceManager.class);

        private Fixture() throws Exception {
            field(BlockEntity.class, factory, "level", exterior);
            field(BlockEntity.class, factory, "worldPosition", HOST);
            field(BlockEntity.class, port, "level", pocket);
            field(BlockEntity.class, port, "worldPosition", PORT);
            field(NestedFactoryBlockEntity.class, factory, "factoryId", "root");
            PortMode[] modes = new PortMode[6];
            java.util.Arrays.fill(modes, PortMode.NONE);
            modes[Direction.NORTH.get3DDataValue()] = PortMode.INPUT;
            field(NestedFactoryBlockEntity.class, factory, "faceModes", modes);
            field(NestedPortBlockEntity.class, port, "targetPortId", 1);
            field(NestedPortBlockEntity.class, port, "boundFactoryId", "");
            field(NestedPortBlockEntity.class, port, "mappedFaceMode", PortMode.INPUT);
            field(NestedPortBlockEntity.class, port, "portCapabilities", cache);
            field(NestedPortBlockEntity.class, port, "itemInputConsumers", new Block[6]);
            field(NestedPortBlockEntity.class, port, "fluidInputConsumers", new Block[6]);
            mode(OperationMode.CHUNK_LOADED);
            when(exterior.getServer()).thenReturn(server);
            when(pocket.getServer()).thenReturn(server);
            when(pocket.dimension()).thenReturn(NestedFactoryBlock.POCKET_DIMENSION);
            when(server.getLevel(NestedFactoryBlock.POCKET_DIMENSION)).thenReturn(pocket);
            when(server.getLevel(Level.OVERWORLD)).thenReturn(exterior);
            when(exterior.getBlockEntity(HOST)).thenReturn(factory);
            when(exterior.hasChunkAt(HOST)).thenReturn(true);
            when(pocket.hasChunkAt(any())).thenAnswer(call ->
                    !call.getArgument(0).equals(unloadedNeighbor)
                            && (!call.getArgument(0).equals(PORT) || portLoaded.get()));
            when(pocket.getBlockEntity(PORT)).thenReturn(port);
            when(pocket.getGameTime()).thenReturn(1L);
            when(pocket.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
            when(port.getBlockState()).thenReturn(Blocks.CHEST.defaultBlockState());
            when(port.getFactory()).thenReturn(factory);
            when(port.getTargetPortId()).thenReturn(1);
            when(factory.getLevel()).thenReturn(exterior);
            when(factory.getFactoryId()).thenReturn("root");
            when(factory.hasPhysicalRoom()).thenReturn(true);
            when(factory.roomOrigin()).thenReturn(ORIGIN);
            when(factory.getFaceForPortId(1)).thenReturn(Direction.NORTH);
            when(factory.resolveLogicalPort(1)).thenReturn(
                    new FactoryLogicalPortEndpoint(factory, Direction.NORTH, PortMode.INPUT, 1));
            registry.when(() -> PocketRegistry.getPorts(server, ORIGIN, 1)).thenReturn(Set.of(PORT));
            factories.when(() -> NestedFactoryBlock.findRoomOrigin(pocket, PORT)).thenReturn(ORIGIN);
            factories.when(() -> NestedFactoryBlock.findFactoryAt(pocket, PORT)).thenReturn(factory);
            hooks.when(() -> PocketFreezeHooks.isFrozen(pocket, PORT))
                    .thenAnswer(call -> frozen.get() || freeze.isFrozen(PORT));
            freezeAccess.when(() -> PocketFreezeSavedData.get(server)).thenReturn(freeze);
            slotAccess.when(() -> NestedFactorySaveData.get(server)).thenReturn(slots);
            doAnswer(call -> {
                notifications.incrementAndGet();
                if (watcher != null) watcher.refresh();
                return null;
            }).when(pocket).neighborChanged(any(), any(), eq(PORT));
        }

        private <T> Watcher<T> watch(Capability<T> capability) {
            Watcher<T> consumer = new Watcher<>(this, capability);
            require(consumer.connected, "fixture endpoint was initially unavailable");
            watcher = consumer;
            return consumer;
        }

        private void disable(Watcher<?> consumer, OperationMode mode) throws Exception {
            mode(mode);
            cache.invalidate();
            require(!consumer.connected, "fixture did not reproduce the null capability disconnect");
        }

        private void mode(OperationMode mode) throws Exception {
            field(NestedFactoryBlockEntity.class, factory, "operationMode", mode);
        }

        private void transition(FactoryLifecycleTransitions.Event event) throws Exception {
            Method method = NestedFactoryBlockEntity.class.getDeclaredMethod("transitionOperationMode",
                    FactoryLifecycleTransitions.Event.class);
            method.setAccessible(true);
            method.invoke(factory, event);
        }

        private void requestPortRefresh() {
            port.requestLogisticsConnectionRefresh();
        }

        private void tickPort() {
            NestedPortBlockEntity.serverTick(pocket, PORT, port.getBlockState(), port);
        }

        @Override
        public void close() {
            tickets.close();
            slotAccess.close();
            freezeAccess.close();
            hooks.close();
            factories.close();
            registry.close();
        }
    }

    private static void field(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(List<String> failures, String name, CheckedRunnable test) {
        try { test.run(); }
        catch (Throwable failure) { failures.add(name + ": " + failure); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface CheckedRunnable { void run() throws Exception; }
}

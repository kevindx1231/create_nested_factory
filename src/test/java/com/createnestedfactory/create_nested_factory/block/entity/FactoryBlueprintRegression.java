package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.blueprint.NestedFactoryBlueprint;
import com.createnestedfactory.create_nested_factory.blueprint.FactoryBlueprintInteractions;
import com.createnestedfactory.create_nested_factory.compat.LegacyCapabilities;
import com.createnestedfactory.create_nested_factory.event.PocketEvents;
import com.createnestedfactory.create_nested_factory.network.PlayerMessagePayload;
import com.google.gson.JsonParser;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.schematics.SchematicItem;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

/** Exercises the old blueprint NBT crash boundary and synchronous capability refresh during application. */
@SuppressWarnings({"unchecked", "deprecation", "removal"})
public final class FactoryBlueprintRegression {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registerSchematic();
        StringBuilder failures = new StringBuilder();
        check(failures, "Create blueprint reader", FactoryBlueprintRegression::clientRecognition);
        check(failures, "Create blueprint editor", FactoryBlueprintRegression::editorRecognition);
        check(failures, "copy and apply interaction", FactoryBlueprintRegression::interactions);
        check(failures, "blueprint refresh order", FactoryBlueprintRegression::applicationRefresh);
        if (!failures.isEmpty()) throw new AssertionError(failures.toString());
        System.out.println("Factory blueprint recognition, NBT and capability refresh regressions passed.");
    }

    private static void registerSchematic() throws Exception {
        // Registrate needs FML's name mapper even though these methods are already named in a plain JVM.
        try (var names = mockStatic(ObfuscationReflectionHelper.class, CALLS_REAL_METHODS)) {
            names.when(() -> ObfuscationReflectionHelper.remapName(any(), anyString()))
                    .thenAnswer(call -> call.getArgument(1));
            Class.forName(AllItems.class.getName());
        }
        for (Class<?> type = BuiltInRegistries.ITEM.getClass(); type != null; type = type.getSuperclass()) {
            for (String name : new String[]{"frozen", "locked"}) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(BuiltInRegistries.ITEM, false);
                } catch (NoSuchFieldException ignored) {}
            }
        }
        ((net.minecraftforge.registries.ForgeRegistry<?>) net.minecraftforge.registries.ForgeRegistries.ITEMS).unfreeze();
        Registry.register(BuiltInRegistries.ITEM, new ResourceLocation("create", "schematic"),
                new SchematicItem(new Item.Properties().stacksTo(1)));
        Registry.register(BuiltInRegistries.ITEM, new ResourceLocation("create", "empty_schematic"),
                new Item(new Item.Properties().stacksTo(1)));
        BuiltInRegistries.ITEM.freeze();
        AllItems.SCHEMATIC.updateReference(net.minecraftforge.registries.ForgeRegistries.ITEMS);
        AllItems.EMPTY_SCHEMATIC.updateReference(net.minecraftforge.registries.ForgeRegistries.ITEMS);
    }

    private static NestedFactoryBlueprint blueprint() {
        FactoryPlan plan = FactoryPlan.compiled(
                Map.of(ItemVariant.of(new ItemStack(Items.IRON_ORE)), 1L),
                Map.of(ItemVariant.of(new ItemStack(Items.IRON_INGOT)), 1L),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                20, 0, FactoryPlan.SourceKind.NONE, 3, false, 0, 0, 0, 0, 0, List.of());
        CompoundTag tag = new CompoundTag();
        tag.putString("Marker", "create_nested_factory:blueprint_v8");
        tag.putString("SourceFactoryId", "source");
        tag.put("Plan", plan.write(new CompoundTag(), null));
        tag.putString("FaceMode" + Direction.NORTH.get3DDataValue(), "input");
        tag.putInt("PortId" + Direction.NORTH.get3DDataValue(), 1);
        tag.putString("FaceMode" + Direction.EAST.get3DDataValue(), "output");
        tag.putInt("PortId" + Direction.EAST.get3DDataValue(), 2);
        NestedFactoryBlueprint blueprint = NestedFactoryBlueprint.fromTag(tag, null);
        require(blueprint != null, "test blueprint plan is incomplete");
        return blueprint;
    }

    private static void clientRecognition() throws Exception {
        ItemStack oldBlueprint = AllItems.SCHEMATIC.asStack();
        blueprint().writeToItem(oldBlueprint, null);
        require(!oldBlueprint.getTag().contains("Rotation") && !oldBlueprint.getTag().contains("Mirror"),
                "the old NBT fixture no longer reproduces the crash");
        boolean reproduced = false;
        try {
            SchematicItem.getSettings(oldBlueprint);
        } catch (IllegalArgumentException exception) {
            reproduced = exception.getMessage().contains("Rotation.");
        }
        require(reproduced, "Create's actual reader did not reproduce the reported Rotation crash");

        Class<?> mixin = Class.forName("com.createnestedfactory.create_nested_factory.mixin.client.SchematicHandlerMixin");
        Object interceptor = mock(mixin, CALLS_REAL_METHODS);
        Method guard = mixin.getDeclaredMethod("createNestedFactory$skipFactoryBlueprint", Player.class,
                CallbackInfoReturnable.class);
        guard.setAccessible(true);
        Player player = mock(Player.class);
        for (ItemStack factoryStack : List.of(oldBlueprint, oldBlueprint.copy(), corruptBlueprint())) {
            when(player.getMainHandItem()).thenReturn(factoryStack);
            var callback = new CallbackInfoReturnable<ItemStack>("findBlueprintInHand", true);
            guard.invoke(interceptor, player, callback);
            require(callback.isCancelled() && callback.getReturnValue() == null,
                    "a factory blueprint still reaches Create's Rotation reader");
        }

        ItemStack nativeBlueprint = AllItems.SCHEMATIC.asStack();
        nativeBlueprint.getOrCreateTag().putString("Rotation", Rotation.NONE.name());
        nativeBlueprint.getOrCreateTag().putString("Mirror", Mirror.NONE.name());
        for (ItemStack nativeStack : List.of(nativeBlueprint, AllItems.SCHEMATIC.asStack(), new ItemStack(Items.PAPER))) {
            when(player.getMainHandItem()).thenReturn(nativeStack);
            var callback = new CallbackInfoReturnable<ItemStack>("findBlueprintInHand", true);
            guard.invoke(interceptor, player, callback);
            require(!callback.isCancelled(), "ordinary Create schematics or other items were intercepted");
        }
        require(SchematicItem.getSettings(nativeBlueprint).getRotation() == Rotation.NONE,
                "native schematic settings changed");
        CompoundTag original = oldBlueprint.getTag().copy();
        NestedFactoryBlueprint read = NestedFactoryBlueprint.fromItem(oldBlueprint, null);
        require(read != null && original.equals(oldBlueprint.getTag()), "reading mutated blueprint item NBT");
        read.read(new CompoundTag(), null);
        require(original.equals(oldBlueprint.getTag()), "parsed blueprint retained mutable item state");
        require(NestedFactoryBlueprint.fromItem(corruptBlueprint(), null) == null,
                "invalid blueprints were accepted for application");

        var config = JsonParser.parseString(Files.readString(Path.of("src/main/resources/create_nested_factory.mixins.json")))
                .getAsJsonObject();
        require(config.getAsJsonArray("client").toString().contains("client.SchematicHandlerMixin"),
                "schematic interceptor was not registered for clients");
        require(!config.getAsJsonArray("mixins").toString().contains("SchematicHandlerMixin"),
                "client schematic interceptor would load on a dedicated server");
        require(Class.forName("com.simibubi.create.content.schematics.client.SchematicHandler", false,
                        FactoryBlueprintRegression.class.getClassLoader())
                        .getDeclaredMethod("findBlueprintInHand", Player.class).getReturnType() == ItemStack.class,
                "client interceptor no longer matches Create's real method signature");
    }

    private static ItemStack corruptBlueprint() {
        ItemStack stack = AllItems.SCHEMATIC.asStack();
        stack.getOrCreateTag().putString(NestedFactoryBlueprint.ITEM_KEY, "broken");
        return stack;
    }

    private static void editorRecognition() throws Exception {
        ItemStack stack = AllItems.SCHEMATIC.asStack();
        blueprint().writeToItem(stack, null);
        Player player = mock(Player.class);
        net.minecraft.world.level.Level level = mock(net.minecraft.world.level.Level.class);
        when(player.isShiftKeyDown()).thenReturn(true);
        when(player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND)).thenReturn(stack);
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(true);
        Method nativeUse = SchematicItem.class.getDeclaredMethod("onItemUse", Player.class,
                net.minecraft.world.InteractionHand.class);
        nativeUse.setAccessible(true);
        try (var screens = mockStatic(net.minecraftforge.fml.DistExecutor.class)) {
            boolean[] opened = {false};
            screens.when(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(any(), any()))
                    .thenAnswer(call -> { opened[0] = true; return null; });
            require(Boolean.TRUE.equals(nativeUse.invoke(stack.getItem(), player,
                            net.minecraft.world.InteractionHand.MAIN_HAND)) && opened[0],
                    "the original item use did not attempt to open Create's schematic editor");
        }
        Class<?> mixin = Class.forName("com.createnestedfactory.create_nested_factory.mixin.SchematicItemMixin");
        Object interceptor = mock(mixin, CALLS_REAL_METHODS);
        Method guard = mixin.getDeclaredMethod("createNestedFactory$skipFactoryEditor", Player.class,
                net.minecraft.world.InteractionHand.class, CallbackInfoReturnable.class);
        guard.setAccessible(true);
        var callback = new CallbackInfoReturnable<Boolean>("onItemUse", true);
        guard.invoke(interceptor, player, net.minecraft.world.InteractionHand.MAIN_HAND, callback);
        require(callback.isCancelled() && !callback.getReturnValue(),
                "factory blueprints still enter the native schematic editor");
        when(player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND))
                .thenReturn(AllItems.SCHEMATIC.asStack());
        callback = new CallbackInfoReturnable<>("onItemUse", true);
        guard.invoke(interceptor, player, net.minecraft.world.InteractionHand.MAIN_HAND, callback);
        require(!callback.isCancelled(), "native schematic editor use was intercepted");
    }

    private static void interactions() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        NestedFactoryBlockEntity source = mock(NestedFactoryBlockEntity.class);
        NestedFactoryBlueprint template = blueprint();
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(source.isEnterable()).thenReturn(true);
        when(source.getOperationMode()).thenReturn(OperationMode.BLACKBOX_ACTIVE);
        when(source.getFactoryId()).thenReturn("source");
        when(source.getCustomName()).thenReturn("Source factory");
        when(source.getLevel()).thenReturn(level);
        when(source.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(source.getPlan()).thenReturn(template.plan());
        for (Direction face : Direction.values()) {
            when(source.getFaceMode(face)).thenReturn(template.faceMode(face.get3DDataValue()));
            when(source.getPortId(face)).thenReturn(template.portId(face.get3DDataValue()));
        }
        var held = new java.util.concurrent.atomic.AtomicReference<>(AllItems.EMPTY_SCHEMATIC.asStack());
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenAnswer(call -> held.get());
        doAnswer(call -> { held.set(call.getArgument(1)); return null; })
                .when(player).setItemInHand(eq(InteractionHand.MAIN_HAND), any());
        try (var messages = mockStatic(PlayerMessagePayload.class)) {
            require(FactoryBlueprintInteractions.tryCopy(player, source, InteractionHand.MAIN_HAND),
                    "valid factory did not copy its blueprint");
            require(NestedFactoryBlueprint.fromItem(held.get(), null) != null,
                    "copy interaction created an unusable blueprint");
            require(!held.get().getTag().contains("Rotation"), "copy changed the existing blueprint NBT format");
            NestedFactoryBlockEntity target = mock(NestedFactoryBlockEntity.class);
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(target);
            when(player.isShiftKeyDown()).thenReturn(true);
            var event = mock(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock.class);
            when(event.getLevel()).thenReturn(level);
            when(event.getEntity()).thenReturn(player);
            when(event.getItemStack()).thenAnswer(call -> held.get());
            when(event.getHand()).thenReturn(InteractionHand.MAIN_HAND);
            when(event.getPos()).thenReturn(BlockPos.ZERO);
            try (var parsing = mockStatic(NestedFactoryBlueprint.class, CALLS_REAL_METHODS)) {
                PocketEvents.onRightClickBlock(event);
                parsing.verify(() -> NestedFactoryBlueprint.fromItem(held.get(), null), times(1));
            }
            verify(target).applyBlueprint(any(NestedFactoryBlueprint.class), eq(player));
            verify(event).setCanceled(true);
            clearInvocations(target);
            held.set(corruptBlueprint());
            require(FactoryBlueprintInteractions.tryApply(player, target, InteractionHand.MAIN_HAND),
                    "a corrupt factory blueprint was not handled");
            verify(target, never()).applyBlueprint(any(), any());
            held.set(new ItemStack(Items.PAPER));
            require(!FactoryBlueprintInteractions.tryApply(player, target, InteractionHand.MAIN_HAND),
                    "unrelated items were consumed by blueprint application");
        }
    }

    private static void applicationRefresh() throws Exception {
        try (var capabilities = mockStatic(CapabilityManager.class);
             var freezing = mockStatic(PocketFreezeManager.class)) {
            capabilities.when(() -> CapabilityManager.get(any(CapabilityToken.class)))
                    .thenAnswer(call -> mock(net.minecraftforge.common.capabilities.Capability.class));
            NestedFactoryBlockEntity target = mock(NestedFactoryBlockEntity.class);
            ServerLevel level = mock(ServerLevel.class);
            PortMode[] modes = new PortMode[6];
            java.util.Arrays.fill(modes, PortMode.NONE);
            modes[Direction.NORTH.get3DDataValue()] = PortMode.OUTPUT;
            int[] ids = new int[6];
            ids[Direction.NORTH.get3DDataValue()] = 1;
            LegacyCapabilities.Cache cache = new LegacyCapabilities.Cache();
            field(BlockEntity.class, target, "level", level);
            field(BlockEntity.class, target, "worldPosition", BlockPos.ZERO);
            field(NestedFactoryBlockEntity.class, target, "factoryId", "target");
            field(NestedFactoryBlockEntity.class, target, "operationMode", OperationMode.CHUNK_LOADED);
            field(NestedFactoryBlockEntity.class, target, "plan", new FactoryPlan());
            field(NestedFactoryBlockEntity.class, target, "faceModes", modes);
            field(NestedFactoryBlockEntity.class, target, "portIds", ids);
            field(NestedFactoryBlockEntity.class, target, "runtimeScheduler", new FactoryRuntimeScheduler());
            field(NestedFactoryBlockEntity.class, target, "factoryTransit", new FactoryTransit());
            field(NestedFactoryBlockEntity.class, target, "transferTelemetry", new FactoryTransferTelemetry());
            field(NestedFactoryBlockEntity.class, target, "portCapabilities", cache);
            field(NestedFactoryBlockEntity.class, target, "chunkLeases", mock(FactoryChunkLeaseController.class));
            when(target.isRoot()).thenReturn(true);
            when(target.getLevel()).thenReturn(level);
            when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(target);
            when(target.getItemHandler(any())).thenAnswer(call ->
                    modes[((Direction) call.getArgument(0)).get3DDataValue()] == PortMode.NONE
                            ? null : new ItemStackHandler(1));
            doCallRealMethod().when(target).invalidatePortCapabilities();
            doCallRealMethod().when(target).applyBlueprint(any(), any());
            freezing.when(() -> PocketFreezeManager.freezeLoadedTree(target)).thenReturn(true);
            var old = cache.get(target, ForgeCapabilities.ITEM_HANDLER, Direction.NORTH);
            boolean[] notified = {false};
            old.addListener(ignored -> {
                notified[0] = true;
                require(modes[Direction.NORTH.get3DDataValue()] == PortMode.INPUT,
                        "capability listener observed the old face mode during blueprint application");
                require(cache.get(target, ForgeCapabilities.ITEM_HANDLER, Direction.EAST).isPresent(),
                        "capability listener could not discover the newly enabled output face");
            });
            NestedFactoryBlueprint incoming = blueprint();
            require(target.applyBlueprint(incoming, null) == null, "valid blueprint was rejected");
            require(notified[0] && !old.isPresent(), "blueprint application did not refresh capabilities");
            incoming.read(new CompoundTag(), null);
            require(((FactoryPlan) value(target, "plan")).hasCompleteRecipe(),
                    "applied plan retained the caller's mutable blueprint state");
            require(((NestedFactoryBlueprint) value(target, "appliedBlueprint")).hasCompleteRunData(),
                    "stored blueprint retained the caller's mutable blueprint state");
            Method cancel = NestedFactoryBlockEntity.class.getDeclaredMethod("cancelBlueprint", Player.class,
                    OperationMode.class);
            cancel.setAccessible(true);
            cancel.invoke(target, null, OperationMode.CHUNK_LOADED);
            require(modes[Direction.NORTH.get3DDataValue()] == PortMode.OUTPUT
                            && modes[Direction.EAST.get3DDataValue()] == PortMode.NONE,
                    "cancellation did not restore the old face configuration");
            require(value(target, "appliedBlueprint") == null && value(target, "preBlueprintSnapshot") == null
                            && Boolean.FALSE.equals(value(target, "blueprintApplied")),
                    "cancellation retained applied blueprint state");
            require(value(target, "operationMode") == OperationMode.CHUNK_LOADED,
                    "cancellation did not restore the requested operation mode");
        }
    }

    private static void field(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object value(Object target, String name) throws Exception {
        Field field = NestedFactoryBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void check(StringBuilder failures, String name, CheckedRunnable check) {
        try { check.run(); }
        catch (Throwable failure) {
            failures.append(name).append(": ").append(failure).append(System.lineSeparator());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface CheckedRunnable { void run() throws Exception; }
}

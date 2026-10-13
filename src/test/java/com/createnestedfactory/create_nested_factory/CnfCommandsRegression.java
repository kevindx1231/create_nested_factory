package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.FactoryDirectorySavedData.Entry;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.command.CnfCommands;
import com.createnestedfactory.create_nested_factory.command.FactoryCommandService;
import com.createnestedfactory.create_nested_factory.item.FactoryBlockItem;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;
import com.createnestedfactory.create_nested_factory.registry.ModItems;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.material.Fluids;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises real Brigadier commands, item data, persistence and the room cleanup completion boundary. */
@SuppressWarnings({"unchecked", "deprecation"})
public final class CnfCommandsRegression {
    private static JsonObject chinese;
    private static JsonObject english;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        {
            Bootstrap.bootStrap();
            registerFactoryTypes();
        }
        chinese = language("zh_cn");
        english = language("en_us");
        require(chinese.keySet().stream().filter(key -> key.startsWith("command.create_nested_factory."))
                        .allMatch(english::has), "every Chinese command message needs an English translation");
        directoryPersistence();
        slotRecycling();
        inspectionAndPermissions();
        aimedFactoryInfo();
        copyAndPlacement();
        deletionBlockers();
        deletionCompletionAndRestart();
        frozenAncestorCleanup();
        System.out.println("CNF command, aimed-info, binding-copy, deletion and slot-recycling regressions passed.");
    }

    private static JsonObject language(String name) throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/create_nested_factory/lang/"
                + name + ".json"))).getAsJsonObject();
    }

    private static void reopen(Object registry) throws Exception {
        LegacyRegistryFixture.reopenWrapper(registry);
        if (registry == BuiltInRegistries.BLOCK)
            ((net.minecraftforge.registries.ForgeRegistry<?>) net.minecraftforge.registries.ForgeRegistries.BLOCKS).unfreeze();
        if (registry == BuiltInRegistries.ITEM)
            ((net.minecraftforge.registries.ForgeRegistry<?>) net.minecraftforge.registries.ForgeRegistries.ITEMS).unfreeze();
        if (registry == BuiltInRegistries.BLOCK_ENTITY_TYPE)
            ((net.minecraftforge.registries.ForgeRegistry<?>) net.minecraftforge.registries.ForgeRegistries.BLOCK_ENTITY_TYPES).unfreeze();
        Field frozen = MappedRegistry.class.getDeclaredField("frozen");
        frozen.setAccessible(true);
        frozen.set(registry, false);
        Field holders = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
        holders.setAccessible(true);
        holders.set(registry, new IdentityHashMap<>());
    }

    private static void registerFactoryTypes() throws Exception {
        ResourceLocation id = new ResourceLocation("create_nested_factory", "nested_factory");
        reopen(BuiltInRegistries.BLOCK);
        NestedFactoryBlock block = Registry.register(BuiltInRegistries.BLOCK, id,
                new NestedFactoryBlock(BlockBehaviour.Properties.of()));
        BuiltInRegistries.BLOCK.freeze();
        reopen(BuiltInRegistries.ITEM);
        Registry.register(BuiltInRegistries.ITEM, id, new FactoryBlockItem(block, new Item.Properties().stacksTo(1)));
        BuiltInRegistries.ITEM.freeze();
        reopen(BuiltInRegistries.BLOCK_ENTITY_TYPE);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id,
                BlockEntityType.Builder.of(NestedFactoryBlockEntity::new, block).build(null));
        BuiltInRegistries.BLOCK_ENTITY_TYPE.freeze();
    }

    private static Entry entry(String id, String parent, boolean room, BlockPos pos) {
        return new Entry(id, parent, !parent.isBlank(), room, OperationMode.CHUNK_LOADED,
                "Factory " + id, pos == null ? "" : "minecraft:overworld", pos);
    }

    private static CompoundTag rootData(String id, BlockPos origin, int slot) {
        CompoundTag data = new CompoundTag();
        data.putString("FactoryId", id);
        data.putString("RootFactoryId", id);
        data.putString("OperationMode", "chunk_loaded");
        data.putString("CustomName", "Factory " + id);
        data.putBoolean(NestedFactoryBlockEntity.PORTABLE_BINDING_KEY, true);
        data.putBoolean("RootRoomAllocated", true);
        data.putInt("RootSlotId", slot);
        data.putLong("RootRoomOrigin", origin.asLong());
        data.putIntArray("Bounds", new int[6]);
        return data;
    }

    private static void directoryPersistence() {
        FactoryDirectorySavedData directory = new FactoryDirectorySavedData();
        directory.observe(entry("root", "", true, new BlockPos(1, 64, 2)));
        directory.observe(entry("child", "root", true, null));
        directory.observe(entry("grandchild", "child", true, new BlockPos(3, 64, 4)));
        directory.observe(entry("terminal", "grandchild", false, null));
        require(directory.rooms(false).size() == 1 && directory.rooms(true).size() == 2, "room lists must exclude roomless terminals");
        require(directory.descendants("root").size() == 3, "all descendant levels must be traversed");
        CompoundTag data = rootData("root", NestedFactoryBlock.getRootRoomOrigin(0), 0);
        directory.observeBindingData("root", data);
        data.putString("CustomName", "mutated caller");
        require(!directory.bindingData("root").getString("CustomName").equals("mutated caller"), "snapshots must own their data");
        CompoundTag exposed = directory.bindingData("root");
        exposed.putString("CustomName", "mutated reader");
        require(!directory.bindingData("root").getString("CustomName").equals("mutated reader"), "snapshot readers need independent data");
        directory.beginDeletion(directory.find("root"), directory.bindingData("root"), UUID.randomUUID().toString());
        FactoryDirectorySavedData restored = FactoryDirectorySavedData.load(directory.save(new CompoundTag(), null), null);
        require(restored.isDeleting("root") && !restored.factoryIds().contains("root"), "pending deletion must survive and leave completion suggestions");
        restored.forget("root");
        require(restored.pendingDeletions().size() == 1 && restored.bindingData("root") != null, "scheduler cleanup must retain command completion metadata");
        restored.completeDeletion("root");
        restored.observe(entry("root", "", true, null));
        require(restored.find("root") == null, "deleted identities must never be re-observed");
        restored = FactoryDirectorySavedData.load(restored.save(new CompoundTag(), null), null);
        require(restored.isRetired("root"), "retired IDs must survive a restart");
        restored.observe(entry("cycle-a", "cycle-b", true, null));
        restored.observe(entry("cycle-b", "cycle-a", true, null));
        require(restored.descendants("cycle-a").size() == 1, "corrupt parent cycles must terminate without listing self");
    }

    private static void slotRecycling() {
        NestedFactorySaveData slots = new NestedFactorySaveData();
        var first = slots.claimRootAllocation("old-id", -1, null);
        slots.releaseRootAllocation("old-id");
        var second = slots.claimRootAllocation("other-id", -1, null);
        require(second.slotId() != first.slotId(), "unregistration alone must not recycle a room");
        slots.recycleCommandSlot(false, first.slotId(), first.roomOrigin());
        slots = NestedFactorySaveData.load(slots.save(new CompoundTag(), null), null);
        var replacement = slots.claimRootAllocation("new-id", -1, null);
        require(replacement.equals(first), "completed root deletions must recycle the physical slot under a different ID");
        slots.recycleCommandSlot(false, replacement.slotId(), replacement.roomOrigin());
        require(slots.claimRootAllocation("another-id", -1, null).slotId() != replacement.slotId(), "occupied roots must not enter the free pool");
        int nested = slots.allocateSlotId();
        slots.recycleCommandSlot(true, nested, BlockPos.ZERO);
        slots = NestedFactorySaveData.load(slots.save(new CompoundTag(), null), null);
        require(slots.allocateSlotId() == nested && slots.allocateSlotId() != nested, "nested free slots must persist and be consumed once");
        BlockPos legacy = new BlockPos(8192, 128, -8192);
        slots.claimRootAllocation("legacy", -1, legacy);
        slots.releaseRootAllocation("legacy");
        slots.recycleCommandSlot(false, -1, legacy);
        slots = NestedFactorySaveData.load(slots.save(new CompoundTag(), null), null);
        require(slots.claimRootAllocation("new-legacy-owner", -1, null).roomOrigin().equals(legacy), "legacy root origins must also be reusable");
    }

    private static void inspectionAndPermissions() throws Exception {
        try (Fixture f = new Fixture()) {
            f.directory.observe(entry("child", "root", true, null));
            f.directory.observe(new Entry("terminal", "child", true, false, OperationMode.BLUEPRINT, "", "", null));
            f.slots.observeFactoryParent("child", "root");
            f.slots.acquireFreezeLease("root");
            require(f.commands.execute("cnf list root", f.source) == 1, "root listing should return root count");
            require(f.commands.execute("cnf list nested", f.source) == 1, "nested listing should exclude terminals");
            require(f.commands.execute("cnf nested root", f.source) == 1, "nested query must omit terminals and self");
            require(f.commands.execute("cnf mode child", f.source) == 1, "mode lookup should work for portable factories");
            require(f.messages.stream().anyMatch(CnfCommandsRegression::containsAncestorFrozen), "mode output must explain ancestor freezing");
            for (String command : List.of("mode", "nested", "copy", "delete")) {
                var suggestions = f.commands.getCompletionSuggestions(f.commands.parse("cnf " + command + " ", f.source)).get();
                require(suggestions.getList().stream().anyMatch(s -> s.getText().equals("root")), command + " needs ID completion");
            }
            expectSyntaxFailure(() -> f.commands.execute("cnf mode missing", f.source));
            CommandSourceStack ordinary = mock(CommandSourceStack.class);
            when(ordinary.getServer()).thenReturn(f.server);
            expectSyntaxFailure(() -> f.commands.execute("cnf copy root", ordinary));
            expectSyntaxFailure(() -> f.commands.execute("cnf delete root", ordinary));
            expectSyntaxFailure(() -> f.commands.execute("cnf copy root", f.source)); // Console has no player.
        }
    }

    private static boolean containsAncestorFrozen(Component component) {
        if (component.getContents() instanceof TranslatableContents translated) {
            if (translated.getKey().endsWith("ancestor_frozen")) return true;
            for (Object arg : translated.getArgs()) if (arg instanceof Component c && containsAncestorFrozen(c)) return true;
        }
        return component.getSiblings().stream().anyMatch(CnfCommandsRegression::containsAncestorFrozen);
    }

    /** Calls the real Entity.pick and BlockGetter.clip methods, including solid-block occlusion. */
    private static NestedFactoryBlockEntity aim(Fixture f, ServerPlayer player, BlockPos target) {
        when(f.source.getEntity()).thenReturn(player);
        when(f.source.hasPermission(2)).thenReturn(false);
        when(player.serverLevel()).thenReturn(f.overworld);
        when(player.level()).thenReturn(f.overworld);
        when(player.getBlockReach()).thenReturn(4.5);
        when(player.getEyePosition(1.0F)).thenReturn(new Vec3(0.5, 64.5, 0.5));
        when(player.getViewVector(1.0F)).thenReturn(new Vec3(0, 0, 1));
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(player.pick(anyDouble(), anyFloat(), anyBoolean())).thenCallRealMethod();
        when(f.overworld.clip(any(ClipContext.class))).thenCallRealMethod();
        when(f.overworld.clipWithInteractionOverride(any(Vec3.class), any(Vec3.class), any(BlockPos.class),
                any(VoxelShape.class), any())).thenCallRealMethod();
        when(f.overworld.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
        when(f.overworld.getFluidState(any())).thenReturn(Fluids.EMPTY.defaultFluidState());
        when(f.overworld.getBlockState(target)).thenReturn(ModItems.NESTED_FACTORY.get().getBlock().defaultBlockState());
        NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        when(factory.getFactoryId()).thenReturn("aimed-root");
        when(factory.getCustomName()).thenReturn("Live factory name");
        when(factory.getOperationMode()).thenReturn(OperationMode.BLACKBOX_ACTIVE);
        when(factory.isRoot()).thenReturn(true);
        when(factory.hasPhysicalRoom()).thenReturn(true);
        when(factory.getBlockPos()).thenReturn(target);
        when(f.overworld.getBlockEntity(target)).thenReturn(factory);
        return factory;
    }

    private static TranslatableContents message(Fixture f, String suffix) {
        return f.messages.stream().map(Component::getContents).filter(TranslatableContents.class::isInstance)
                .map(TranslatableContents.class::cast)
                .filter(contents -> contents.getKey().equals("command.create_nested_factory." + suffix))
                .findFirst().orElse(null);
    }

    private static void aimedFactoryInfo() throws Exception {
        BlockPos target = new BlockPos(0, 64, 3);
        try (Fixture f = new Fixture()) {
            ServerPlayer player = mock(ServerPlayer.class);
            NestedFactoryBlockEntity factory = aim(f, player, target);
            require(f.commands.execute("cnf info", f.source) == 1, "ordinary players must be able to inspect an aimed factory");
            require(f.messages.size() == 6 && message(f, "info_descendant_ids") == null
                    && message(f, "info_parent_id") == null, "roots without descendants must omit both optional lines");
            require(((Component) message(f, "info_name").getArgs()[0]).getString().equals("Live factory name"), "info must read the live name");
            Component id = (Component) message(f, "info_id").getArgs()[0];
            require(id.getString().equals("aimed-root") && id.getStyle().getClickEvent() != null, "info IDs must keep the existing command-click interaction");
            require(java.util.Arrays.equals(message(f, "info_coordinates").getArgs(),
                    new Object[]{"minecraft:overworld", 0, 64, 3}), "coordinates must identify the aimed block rather than its pocket room");
            require(((Component) message(f, "info_mode").getArgs()[0]).getContents() instanceof TranslatableContents mode
                    && mode.getKey().equals("gui.create_nested_factory.mode.blackbox_active"), "info must read the current selected mode");
            require(((Component) message(f, "info_depth").getArgs()[0]).getContents() instanceof TranslatableContents depth
                    && depth.getKey().endsWith("info_root_depth") && depth.getArgs()[0].equals(0), "root depth must be zero and labelled as root");
            require(f.directory.find("aimed-root") == null, "info must work without registering or changing the target's directory record");
            verify(factory, never()).exportCommandBindingData(any());

            f.messages.clear();
            when(factory.getCustomName()).thenReturn(null);
            when(factory.getOperationMode()).thenReturn(OperationMode.BLACKBOX_LEARNING);
            require(f.commands.execute("cnf info", f.source) == 1, "unnamed factories must still be inspectable");
            require(((Component) message(f, "info_name").getArgs()[0]).getContents() instanceof TranslatableContents name
                    && name.getKey().equals("block.create_nested_factory.nested_factory"), "unnamed factories must use the translated block name");
            require(((Component) message(f, "info_mode").getArgs()[0]).getContents() instanceof TranslatableContents mode
                    && mode.getKey().endsWith("blackbox_learning"), "blackbox learning must keep its distinct mode label");

            f.messages.clear();
            when(factory.isRoot()).thenReturn(false);
            when(factory.getNestingDepth()).thenReturn(3);
            when(factory.getParentFactoryId()).thenReturn("parent-space");
            when(factory.getChildFactoryId()).thenReturn("unindexed-child");
            when(factory.getOperationMode()).thenReturn(OperationMode.BLUEPRINT);
            f.slots.observeFactoryParent("aimed-root", "parent-space");
            f.slots.acquireFreezeLease("parent-space");
            f.directory.observe(entry("portable-child", "aimed-root", true, null));
            f.directory.observe(entry("terminal", "portable-child", false, null));
            f.slots.observeFactoryParent("grandchild", "portable-child");
            f.directory.observe(entry("great-grandchild", "grandchild", true, null));
            f.slots.observeFactoryParent("allocation-only", "aimed-root");
            f.slots.observeFactoryParent("old-child", "aimed-root");
            f.directory.forget("old-child");
            // A corrupt cross-graph cycle must not list the queried factory or loop forever.
            f.slots.observeFactoryParent("aimed-root", "great-grandchild");
            f.slots.observeFactoryParent("great-grandchild", "parent-space");
            require(f.commands.execute("cnf info", f.source) == 1 && f.messages.size() == 8,
                    "nested factories with descendants need both optional fields");
            require(((Component) message(f, "info_descendant_ids").getArgs()[0]).getString().equals(
                    "allocation-only, grandchild, great-grandchild, portable-child, terminal, unindexed-child"),
                    "info must combine both parent graphs and direct-child records, sort, deduplicate, include terminals and exclude retired/self IDs");
            require(((Component) message(f, "info_parent_id").getArgs()[0]).getString().equals("parent-space"), "parent info must use the direct parent ID");
            require(((Component) message(f, "info_depth").getArgs()[0]).getString().equals("3"), "nested depth must use the live depth");
            require(containsAncestorFrozen((Component) message(f, "info_mode").getArgs()[0]), "info must explain ancestor freezing without losing the selected mode");
            verify(f.source, never()).sendSuccess(any(), eq(true));
        }
        try (Fixture f = new Fixture()) {
            ServerPlayer player = mock(ServerPlayer.class);
            NestedFactoryBlockEntity factory = aim(f, player, target);
            when(player.getBlockReach()).thenReturn(1.0);
            require(f.commands.execute("cnf info", f.source) == 0 && message(f, "info_no_target") != null,
                    "the command must respect the player's actual interaction range");
            f.messages.clear();
            when(player.getBlockReach()).thenReturn(4.5);
            BlockPos obstruction = new BlockPos(0, 64, 1);
            when(f.overworld.getBlockState(obstruction)).thenReturn(Blocks.STONE.defaultBlockState());
            require(f.commands.execute("cnf info", f.source) == 0 && message(f, "info_wrong_target") != null
                    && message(f, "info_id") == null, "a solid block must obstruct the factory rather than allowing inspection through it");
            f.messages.clear();
            when(f.overworld.getBlockState(obstruction)).thenReturn(Blocks.AIR.defaultBlockState());
            when(f.overworld.getBlockEntity(target)).thenReturn(null);
            require(f.commands.execute("cnf info", f.source) == 0 && message(f, "info_unavailable") != null,
                    "a factory block without a ready block entity must report unavailable");
            f.messages.clear();
            when(f.overworld.getBlockEntity(target)).thenReturn(factory);
            when(factory.isRemoved()).thenReturn(true);
            require(f.commands.execute("cnf info", f.source) == 0 && message(f, "info_unavailable") != null,
                    "removed block entities must not expose stale information");
            f.messages.clear();
            when(factory.isRemoved()).thenReturn(false);
            Entry pending = entry("aimed-root", "", true, target);
            f.directory.observe(pending);
            f.directory.beginDeletion(pending, f.data, "");
            require(f.commands.execute("cnf info", f.source) == 0 && message(f, "info_unavailable") != null,
                    "a deleting factory must be reported unavailable");
        }
        try (Fixture f = new Fixture()) {
            try {
                f.commands.execute("cnf info", f.source);
                throw new AssertionError("Console info must require a player");
            } catch (CommandSyntaxException expected) {
                require(expected.getRawMessage() instanceof Component c
                        && c.getContents() instanceof TranslatableContents t && t.getKey().endsWith("info_player_only"),
                        "console rejection must use the command's translated player-only message");
                translated((Component) expected.getRawMessage());
            }
        }
    }

    private static void copyAndPlacement() throws Exception {
        try (Fixture f = new Fixture()) {
            ServerPlayer player = mock(ServerPlayer.class);
            Inventory inventory = mock(Inventory.class);
            when(player.getInventory()).thenReturn(inventory);
            doReturn(player).when(f.source).getPlayerOrException();
            List<ItemStack> given = new ArrayList<>();
            when(inventory.add(any())).thenAnswer(call -> {
                ItemStack item = call.getArgument(0);
                given.add(item.copy());
                item.setCount(0);
                return true;
            });
            require(f.commands.execute("cnf copy root", f.source) == 1, "copy should give the player one bound factory");
            ItemStack copy = given.get(0);
            CompoundTag copiedData = NestedFactoryBlockEntity.getFactoryItemData(copy);
            require(copiedData.getString("FactoryId").equals("root") && copiedData.getBoolean(FactoryCommandService.COPY_KEY), "copy must keep the existing ID and identify its non-owning binding");
            require(f.directory.find("root").isItem(), "copy must not alter the original binding");
            require(!FactoryCommandService.canDestroyPortableRoom(f.server, copiedData), "destroying a copy must not delete its shared room");
            f.directory.observe(entry("root", "", true, new BlockPos(9, 64, 9)));
            BlockPlaceContext context = mock(BlockPlaceContext.class);
            when(context.getLevel()).thenReturn(f.overworld);
            when(context.getItemInHand()).thenReturn(copy);
            require(!FactoryCommandService.preparePlacement(context), "an unloaded placed binding must still block duplicate placement");
            NestedFactoryBlockEntity live = mock(NestedFactoryBlockEntity.class);
            when(live.getFactoryId()).thenReturn("root");
            when(live.saveWithFullMetadata()).thenAnswer(call -> f.data.copy());
            when(live.exportCommandBindingData(any())).thenCallRealMethod();
            when(f.overworld.getBlockEntity(new BlockPos(9, 64, 9))).thenReturn(live);
            doReturn(false).when(inventory).add(any());
            require(f.commands.execute("cnf copy root", f.source) == 1, "placed factories must export a copy without being dismantled");
            verify(live, never()).createPortableItem(any());
            verify(player).drop(any(ItemStack.class), eq(false));
            require(!f.directory.find("root").isItem(), "copying a live block must preserve its position binding");
            f.directory.observe(entry("root", "", true, null));
            CompoundTag latest = f.data.copy();
            latest.putString("CustomName", "Latest name");
            latest.putString("OperationMode", "blueprint");
            latest.putString("RuntimeProof", "latest-state");
            f.directory.observeBindingData("root", latest);
            require(FactoryCommandService.preparePlacement(context), "copy should become placeable after the original binding is released");
            CompoundTag refreshed = NestedFactoryBlockEntity.getFactoryItemData(copy);
            require(refreshed.getString("RuntimeProof").equals("latest-state")
                    && copy.getHoverName().getString().equals("Latest name"), "placing an old copy must use the latest authoritative state");
            when(context.getClickedPos()).thenReturn(new BlockPos(9, 64, 9));
            require(f.directory.find("root").isItem(), "placement validation alone must not claim a binding");
            FactoryCommandService.recordPlacement(context);
            require(!f.directory.find("root").isItem() && !FactoryCommandService.preparePlacement(context),
                    "successful placement must block a second copy before deferred block entity onLoad");
            f.directory.forget("root");
            require(!FactoryCommandService.preparePlacement(context), "a retired binding must not be placeable");
            NestedFactoryBlockEntity.observePortableItem(f.server, copy);
            require(f.directory.find("root") == null, "inventory observation must not resurrect a retired room");
        }
    }

    private static void deletionBlockers() throws Exception {
        try (Fixture f = new Fixture()) {
            f.directory.observe(entry("child", "root", true, null));
            f.directory.observe(entry("grandchild", "child", true, null));
            f.directory.observe(entry("terminal", "grandchild", false, null));
            f.slots.observeFactoryParent("unloaded-child", "root");
            List<String> children = FactoryCommandService.deletionBlockers(f.server, "root", f.data);
            require(children.equals(List.of("child", "grandchild", "terminal", "unloaded-child")), "every descendant, including portable and roomless children, must block deletion");
            require(f.commands.execute("cnf delete root", f.source) == 0 && !f.directory.isDeleting("root"), "blocked deletion must have no lifecycle side effects");
            require(f.messages.stream().anyMatch(c -> c.getContents() instanceof TranslatableContents t
                    && t.getKey().endsWith("delete_children")), "blocked deletion needs a translated list of child IDs");
            f.directory.forget("unloaded-child");
            require(!FactoryCommandService.deletionBlockers(f.server, "root", f.data).contains("unloaded-child"), "retired parent links must not block deletion forever");
        }
        try (Fixture f = new Fixture()) {
            ServerPlayer inside = mock(ServerPlayer.class);
            doReturn(List.of(inside)).when(f.pocket).getEntitiesOfClass(eq(ServerPlayer.class), any(AABB.class), any());
            require(f.commands.execute("cnf delete root", f.source) == 0 && !f.directory.isDeleting("root"), "players inside must prevent clearing without marking the room for deletion");
        }
        try (Fixture f = new Fixture()) {
            BlockPos childPos = f.origin.offset(1, 1, 1);
            BlockPos nestedOrigin = NestedFactoryBlock.getNestedRoomOrigin(0, 0);
            BlockPos grandchildPos = nestedOrigin.offset(1, 1, 1);
            NestedFactoryBlockEntity child = mock(NestedFactoryBlockEntity.class);
            when(child.getFactoryId()).thenReturn("physical-child");
            when(child.getBlockPos()).thenReturn(childPos);
            CompoundTag childData = rootData("physical-child", nestedOrigin, 0);
            childData.putBoolean("Nested", true);
            childData.putInt("NestedSlotId", 0);
            childData.putLong("NestedRoomOrigin", nestedOrigin.asLong());
            childData.putString("ChildFactoryId", "physical-grandchild");
            childData.putLong("ChildFactoryPos", grandchildPos.asLong());
            when(child.exportCommandBindingData(any())).thenReturn(childData);
            LevelChunk populated = mock(LevelChunk.class);
            when(populated.getBlockEntities()).thenReturn(Map.of(childPos, child));
            when(f.pocket.getChunk(Math.floorDiv(f.origin.getX(), 16), Math.floorDiv(f.origin.getZ(), 16))).thenReturn(populated);
            NestedFactoryBlockEntity grandchild = mock(NestedFactoryBlockEntity.class);
            when(grandchild.getFactoryId()).thenReturn("physical-grandchild");
            CompoundTag terminal = new CompoundTag();
            terminal.putString("FactoryId", "physical-grandchild");
            when(grandchild.exportCommandBindingData(any())).thenReturn(terminal);
            when(f.pocket.getBlockEntity(grandchildPos)).thenReturn(grandchild);
            require(FactoryCommandService.deletionBlockers(f.server, "root", f.data)
                    .equals(List.of("physical-child", "physical-grandchild")), "old unindexed physical descendants must be discovered recursively before deletion");
        }
    }

    private static void deletionCompletionAndRestart() throws Exception {
        try (Fixture f = new Fixture()) {
            Config.roomMutationBlocksPerTick = 65536;
            require(f.commands.execute("cnf delete root", f.source) == 1, "console deletion should schedule physical cleanup");
            require(f.directory.isDeleting("root") && f.directory.find("root") != null, "scheduled cleanup must retain its metadata until completion");
            var allocatedWhileDeleting = f.slots.claimRootAllocation("while-deleting", -1, null);
            require(!allocatedWhileDeleting.roomOrigin().equals(f.origin), "a pending deletion must not release its slot");
            CompoundTag savedTasks = f.tasks.save(new CompoundTag());
            f.tasks = RoomMutationTaskManager.load(savedTasks, null);
            f.directory = FactoryDirectorySavedData.load(f.directory.save(new CompoundTag(), null), null);
            FactoryCommandService.resumeDeletions(f.server);
            require(f.tasks.save(new CompoundTag()).getList("Tasks", Tag.TAG_COMPOUND).size() == 1, "restart recovery must not duplicate a persisted room task");
            f.tasks.tick(f.server);
            require(f.directory.isRetired("root") && f.directory.find("root") == null, "actual cleanup completion must retire the ID and remove the directory entry");
            require(f.slots.claimRootAllocation("replacement", -1, null).roomOrigin().equals(f.origin), "completed cleanup must make its exact slot available to a new factory");
            verify(f.pocket, times(4096)).setBlock(any(BlockPos.class), eq(Blocks.AIR.defaultBlockState()), anyInt());
            require(!FactoryCommandService.canDestroyPortableRoom(f.server, f.data), "an old original item must not be able to erase a reused slot");
            verify(f.server).sendSystemMessage(argThat(c -> c.getContents() instanceof TranslatableContents t
                    && t.getKey().endsWith("delete_complete")));
        }
        try (Fixture f = new Fixture()) {
            f.directory.beginDeletion(f.directory.find("root"), f.data, "");
            f.directory = FactoryDirectorySavedData.load(f.directory.save(new CompoundTag(), null), null);
            FactoryCommandService.resumeDeletions(f.server);
            require(f.tasks.isRoomLocked(NestedFactoryBlock.POCKET_DIMENSION, f.origin), "a saved command without a saved room task must reschedule cleanup");
            require(!f.directory.isRetired("root"), "recovery must not recycle an uncleared room");
        }
        try (Fixture f = new Fixture()) {
            int slot = f.slots.allocateSlotId();
            BlockPos nestedOrigin = NestedFactoryBlock.getNestedRoomOrigin(slot, 0);
            BlockPos exterior = f.origin.offset(1, 1, 1);
            NestedFactoryBlockEntity factory = new NestedFactoryBlockEntity(exterior,
                    ModItems.NESTED_FACTORY.get().getBlock().defaultBlockState());
            factory.setLevel(f.pocket);
            for (var fieldValue : Map.<String, Object>of("factoryId", "nested", "rootFactoryId", "root",
                    "nested", true, "nestedSlotId", slot, "nestedRoomOrigin", nestedOrigin,
                    "enterable", false, "bindingConflict", true, "factoryStateInitialized", true).entrySet()) {
                Field field = NestedFactoryBlockEntity.class.getDeclaredField(fieldValue.getKey());
                field.setAccessible(true);
                field.set(factory, fieldValue.getValue());
            }
            when(f.pocket.getGameRules()).thenReturn(mock(GameRules.class));
            Field random = Level.class.getDeclaredField("random");
            random.setAccessible(true);
            random.set(f.pocket, net.minecraft.util.RandomSource.create(42));
            when(f.pocket.getBlockEntity(exterior)).thenReturn(factory);
            Entry nested = new Entry("nested", "root", true, true, OperationMode.CHUNK_LOADED, "",
                    NestedFactoryBlock.POCKET_DIMENSION.location().toString(), exterior);
            CompoundTag data = rootData("nested", nestedOrigin, 0);
            data.putBoolean("Nested", true);
            data.putInt("NestedSlotId", slot);
            data.putLong("NestedRoomOrigin", nestedOrigin.asLong());
            f.directory.observe(nested);
            f.directory.beginDeletion(nested, data, "");
            FactoryCommandService.resumeDeletions(f.server);
            require(f.tasks.isRoomLocked(NestedFactoryBlock.POCKET_DIMENSION, nestedOrigin),
                    "a paused nested binding must remain deletable when recovering a saved command");
        }
    }

    private static void frozenAncestorCleanup() {
        PocketFreezeSavedData frozen = new PocketFreezeSavedData();
        var parent = new PocketFreezeSavedData.FrozenRoom("parent", "minecraft:overworld", BlockPos.ZERO,
                0, 0, 0, 15, 15, 15, 0);
        var child = new PocketFreezeSavedData.FrozenRoom("child", "minecraft:overworld", BlockPos.ZERO,
                32, 0, 0, 47, 15, 15, 0);
        frozen.beginFreeze("parent", List.of(parent, child));
        frozen.captureTick(new BlockPos(33, 1, 1), PocketFreezeSavedData.TickKind.BLOCK, "minecraft:stone", 1, "NORMAL", 0);
        frozen.captureBlockEvent(new BlockPos(33, 1, 1), "minecraft:stone", 0, 0);
        frozen.removeDeletedFactoryRoom("child");
        require(frozen.isFrozen(new BlockPos(1, 1, 1)) && !frozen.isFrozen(new BlockPos(33, 1, 1)), "deleting a child must preserve its ancestor freeze and remove only the reused room");
        var saved = frozen.removeTree("parent");
        require(saved.scheduledTicks().isEmpty() && saved.blockEvents().isEmpty(), "old paused work must not replay in a reused room");
    }

    private static void translated(Component component) {
        if (component.getContents() instanceof TranslatableContents t) {
            if (t.getKey().startsWith("command.create_nested_factory.")) {
                require(chinese.has(t.getKey()) && english.has(t.getKey()), "missing translation: " + t.getKey());
            }
            for (Object arg : t.getArgs()) if (arg instanceof Component c) translated(c);
        }
        component.getSiblings().forEach(CnfCommandsRegression::translated);
    }

    private static void expectSyntaxFailure(CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected command syntax failure");
        } catch (CommandSyntaxException expected) { }
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable {
        private FactoryDirectorySavedData directory = new FactoryDirectorySavedData();
        private final NestedFactorySaveData slots = new NestedFactorySaveData();
        private RoomMutationTaskManager tasks = new RoomMutationTaskManager();
        private final PocketFreezeSavedData frozen = new PocketFreezeSavedData();
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final ServerLevel overworld = mock(ServerLevel.class);
        private final ServerLevel pocket = mock(ServerLevel.class);
        private final CommandSourceStack source = mock(CommandSourceStack.class);
        private final CommandDispatcher<CommandSourceStack> commands = new CommandDispatcher<>();
        private final List<Component> messages = new ArrayList<>();
        private final BlockPos origin = slots.claimRootAllocation("root", -1, null).roomOrigin();
        private final CompoundTag data = rootData("root", origin, 0);
        private final MockedStatic<FactoryDirectorySavedData> directoryAccess = mockStatic(FactoryDirectorySavedData.class,
                call -> call.getMethod().getName().equals("get") ? directory : call.callRealMethod());
        private final MockedStatic<NestedFactorySaveData> slotAccess = mockStatic(NestedFactorySaveData.class,
                call -> call.getMethod().getName().equals("get") ? slots : call.callRealMethod());
        private final MockedStatic<RoomMutationTaskManager> taskAccess = mockStatic(RoomMutationTaskManager.class,
                call -> call.getMethod().getName().equals("get") ? tasks : call.callRealMethod());
        private final MockedStatic<PocketFreezeSavedData> freezeAccess = mockStatic(PocketFreezeSavedData.class,
                call -> call.getMethod().getName().equals("get") ? frozen : call.callRealMethod());
        private final MockedStatic<PocketChunkForceManager> chunkAccess = mockStatic(PocketChunkForceManager.class);

        private Fixture() throws Exception {
            when(server.overworld()).thenReturn(overworld);
            when(server.getLevel(Level.OVERWORLD)).thenReturn(overworld);
            when(server.getLevel(NestedFactoryBlock.POCKET_DIMENSION)).thenReturn(pocket);
            when(server.getPlayerList()).thenReturn(mock(PlayerList.class));
            when(overworld.getServer()).thenReturn(server);
            when(overworld.dimension()).thenReturn(Level.OVERWORLD);
            when(pocket.getServer()).thenReturn(server);
            when(pocket.dimension()).thenReturn(NestedFactoryBlock.POCKET_DIMENSION);
            LevelChunk roomChunk = mock(LevelChunk.class);
            when(roomChunk.getBlockEntities()).thenReturn(Map.of());
            when(pocket.getChunk(anyInt(), anyInt())).thenReturn(roomChunk);
            when(pocket.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
            when(source.getServer()).thenReturn(server);
            when(source.hasPermission(2)).thenReturn(true);
            when(source.getPlayerOrException()).thenThrow(new CommandSyntaxException(null, Component.literal("Player required")));
            doAnswer(call -> {
                Component component = ((Supplier<Component>) call.getArgument(0)).get();
                translated(component);
                messages.add(component);
                return null;
            }).when(source).sendSuccess(any(), anyBoolean());
            doAnswer(call -> {
                Component component = call.getArgument(0);
                translated(component);
                messages.add(component);
                return null;
            }).when(source).sendFailure(any());
            directory.observe(entry("root", "", true, null));
            directory.observeBindingData("root", data);
            CnfCommands.register(commands);
        }

        @Override
        public void close() {
            chunkAccess.close();
            freezeAccess.close();
            taskAccess.close();
            slotAccess.close();
            directoryAccess.close();
            PocketRegistry.releaseServer(server);
        }
    }
}

package com.createnestedfactory.create_nested_factory.command;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.FactoryDirectorySavedData;
import com.createnestedfactory.create_nested_factory.FactoryDirectorySavedData.Entry;
import com.createnestedfactory.create_nested_factory.NestedFactorySaveData;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/** Public inspection commands and operator-only binding copy / space deletion. */
@EventBusSubscriber(modid = Create_nested_factory.MODID)
public final class CnfCommands {
    private static final String PREFIX = "command.create_nested_factory.";
    private static final DynamicCommandExceptionType UNKNOWN_FACTORY = new DynamicCommandExceptionType(
            id -> Component.translatable(PREFIX + "unknown_id", id));
    private static final DynamicCommandExceptionType DELETING_FACTORY = new DynamicCommandExceptionType(
            id -> Component.translatable(PREFIX + "delete_pending", id));
    private static final SimpleCommandExceptionType INFO_PLAYER_ONLY = new SimpleCommandExceptionType(
            Component.translatable(PREFIX + "info_player_only"));

    private CnfCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cnf")
                .then(Commands.literal("info").executes(context -> info(context.getSource())))
                .then(Commands.literal("list")
                        .then(Commands.literal("root").executes(context -> list(context.getSource(), false)))
                        .then(Commands.literal("nested").executes(context -> list(context.getSource(), true))))
                .then(idCommand("mode", 0, context -> mode(context.getSource(), StringArgumentType.getString(context, "id"))))
                .then(idCommand("nested", 0, context -> nested(context.getSource(), StringArgumentType.getString(context, "id"))))
                .then(idCommand("copy", 2, context -> copy(context.getSource(), StringArgumentType.getString(context, "id"))))
                .then(idCommand("delete", 2, context -> delete(context.getSource(), StringArgumentType.getString(context, "id")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> idCommand(String name, int permission,
                                                                       Command<CommandSourceStack> action) {
        var command = Commands.literal(name);
        if (permission > 0) command.requires(source -> source.hasPermission(permission));
        return command.then(Commands.argument("id", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        directory(context.getSource()).factoryIds(), builder)).executes(action));
    }

    private static FactoryDirectorySavedData directory(CommandSourceStack source) {
        return FactoryDirectorySavedData.get(source.getServer());
    }

    private static Entry requireEntry(CommandSourceStack source, String id)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        FactoryDirectorySavedData directory = directory(source);
        if (directory.isDeleting(id)) throw DELETING_FACTORY.create(id);
        Entry entry = directory.find(id);
        if (entry == null || directory.isUnavailable(id)) throw UNKNOWN_FACTORY.create(id);
        return entry;
    }

    private static int list(CommandSourceStack source, boolean nested) {
        List<Entry> entries = directory(source).rooms(nested);
        source.sendSuccess(() -> Component.translatable(PREFIX + (nested ? "nested_list" : "root_list"),
                entries.size()).withStyle(ChatFormatting.GOLD), false);
        for (Entry entry : entries) sendRoom(source, entry, 0);
        return entries.size();
    }

    private static int info(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!(source.getEntity() instanceof ServerPlayer player)) throw INFO_PLAYER_ONLY.create();
        HitResult hit = player.pick(player.getBlockReach(), 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.translatable(PREFIX + "info_no_target"));
            return 0;
        }
        var level = player.serverLevel();
        var pos = blockHit.getBlockPos();
        if (!(level.getBlockState(pos).getBlock() instanceof NestedFactoryBlock)) {
            source.sendFailure(Component.translatable(PREFIX + "info_wrong_target"));
            return 0;
        }
        if (!(level.getBlockEntity(pos) instanceof NestedFactoryBlockEntity factory) || factory.isRemoved()
                || directory(source).isUnavailable(factory.getFactoryId())) {
            source.sendFailure(Component.translatable(PREFIX + "info_unavailable"));
            return 0;
        }

        String id = factory.getFactoryId();
        NestedFactorySaveData saved = NestedFactorySaveData.get(source.getServer());
        Component selectedMode = modeComponent(factory.getOperationMode());
        Component displayedMode = factory.hasPhysicalRoom() && saved.hasFreezeLeaseInAncestors(id)
                ? Component.translatable(PREFIX + "ancestor_frozen", selectedMode) : selectedMode;
        Component depth = factory.isRoot()
                ? Component.translatable(PREFIX + "info_root_depth", factory.getNestingDepth())
                : Component.literal(Integer.toString(factory.getNestingDepth()));
        source.sendSuccess(() -> Component.translatable(PREFIX + "info_header").withStyle(ChatFormatting.GOLD), false);
        sendInfo(source, "name", factoryName(factory.getCustomName()));
        sendInfo(source, "id", idComponent(id));
        sendInfo(source, "coordinates", level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
        sendInfo(source, "mode", displayedMode);
        sendInfo(source, "depth", depth);
        List<String> descendants = descendantIds(directory(source), saved, id, factory.getChildFactoryId());
        if (!descendants.isEmpty()) sendInfo(source, "descendant_ids", idList(descendants));
        String parentId = factory.getParentFactoryId();
        if (!factory.isRoot() && parentId != null && !parentId.isBlank()) {
            sendInfo(source, "parent_id", idComponent(parentId));
        }
        return 1;
    }

    private static void sendInfo(CommandSourceStack source, String field, Object... values) {
        source.sendSuccess(() -> Component.translatable(PREFIX + "info_" + field, values), false);
    }

    /** Follow both saved graphs so a path can cross directory and allocation records without losing descendants. */
    private static List<String> descendantIds(FactoryDirectorySavedData directory, NestedFactorySaveData saved,
                                              String id, String recordedChildId) {
        TreeSet<String> ids = new TreeSet<>();
        HashSet<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(id);
        if (recordedChildId != null && !recordedChildId.isBlank()) pending.add(recordedChildId);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current) || directory.isRetired(current)) continue;
            if (!current.equals(id)) ids.add(current);
            directory.descendants(current).forEach(child -> pending.add(child.entry().factoryId()));
            pending.addAll(saved.descendantFactoryIds(current));
        }
        return List.copyOf(ids);
    }

    private static int nested(CommandSourceStack source, String id)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireEntry(source, id);
        var descendants = directory(source).descendants(id).stream()
                .filter(descendant -> descendant.entry().hasRoom()).toList();
        source.sendSuccess(() -> Component.translatable(PREFIX + "descendants", id, descendants.size())
                .withStyle(ChatFormatting.GOLD), false);
        for (var descendant : descendants) sendRoom(source, descendant.entry(), descendant.depth());
        return descendants.size();
    }

    private static int mode(CommandSourceStack source, String id)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Entry entry = requireEntry(source, id);
        Component mode = modeComponent(entry.mode());
        source.sendSuccess(() -> Component.translatable(PREFIX + "mode", idComponent(id), mode), false);
        Component roomMode = entry.hasRoom() ? mode : Component.translatable(PREFIX + "no_room");
        // A descendant keeps its own selected mode while an ancestor freezes its physical room.
        if (entry.hasRoom() && NestedFactorySaveData.get(source.getServer()).hasFreezeLeaseInAncestors(id)) {
            roomMode = Component.translatable(PREFIX + "ancestor_frozen", mode);
        }
        Component displayedRoomMode = roomMode;
        source.sendSuccess(() -> Component.translatable(PREFIX + "room_mode", displayedRoomMode), false);
        return 1;
    }

    private static int copy(CommandSourceStack source, String id)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entry entry = requireEntry(source, id);
        if (!entry.hasRoom()) {
            source.sendFailure(Component.translatable(PREFIX + "copy_no_room", id));
            return 0;
        }
        var data = FactoryCommandService.bindingData(source.getServer(), entry);
        if (data == null) {
            source.sendFailure(Component.translatable(PREFIX + "data_missing", id));
            return 0;
        }
        ItemStack stack = FactoryCommandService.copyItem(data);
        player.getInventory().add(stack);
        boolean dropped = !stack.isEmpty();
        if (dropped) player.drop(stack, false);
        source.sendSuccess(() -> Component.translatable(PREFIX + (dropped ? "copy_dropped" : "copy_success"),
                idComponent(id)), true);
        return 1;
    }

    private static int delete(CommandSourceStack source, String id)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Entry entry = requireEntry(source, id);
        if (!entry.hasRoom()) {
            source.sendFailure(Component.translatable(PREFIX + "delete_no_room", id));
            return 0;
        }
        var data = FactoryCommandService.bindingData(source.getServer(), entry);
        if (data == null) {
            source.sendFailure(Component.translatable(PREFIX + "data_missing", id));
            return 0;
        }
        List<String> children = FactoryCommandService.deletionBlockers(source.getServer(), id, data);
        if (!children.isEmpty()) {
            source.sendFailure(Component.translatable(PREFIX + "delete_children", idComponent(id), idList(children)));
            return 0;
        }
        String requesterId = source.getEntity() instanceof ServerPlayer player ? player.getUUID().toString() : "";
        Component failure = FactoryCommandService.requestDeletion(source.getServer(), entry, data, requesterId);
        if (failure != null) {
            source.sendFailure(failure);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(PREFIX + "delete_started", idComponent(id)), true);
        return 1;
    }

    private static void sendRoom(CommandSourceStack source, Entry entry, int depth) {
        Component name = factoryName(entry.customName());
        Component binding = entry.isItem() ? Component.literal("item")
                : Component.literal(entry.dimension() + " (" + entry.pos().getX() + ", "
                        + entry.pos().getY() + ", " + entry.pos().getZ() + ")");
        MutableComponent line = Component.literal("  ".repeat(Math.min(depth, 32)))
                .append(Component.translatable(PREFIX + "room", idComponent(entry.factoryId()), name, binding));
        if (depth > 0) line.append(Component.translatable(PREFIX + "parent", entry.parentFactoryId()));
        source.sendSuccess(() -> line, false);
    }

    private static Component factoryName(String customName) {
        return customName == null || customName.isBlank()
                ? Component.translatable("block.create_nested_factory.nested_factory") : Component.literal(customName);
    }

    private static Component modeComponent(OperationMode mode) {
        return Component.translatable("gui.create_nested_factory.mode." + mode.getSerializedName());
    }

    private static Component idList(List<String> ids) {
        MutableComponent result = Component.empty();
        for (String id : ids) {
            if (!result.getSiblings().isEmpty()) result.append(Component.literal(", "));
            result.append(idComponent(id));
        }
        return result;
    }

    private static Component idComponent(String id) {
        return Component.literal(id).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/cnf mode " + id)));
    }
}

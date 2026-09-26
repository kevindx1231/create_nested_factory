package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.block.OperationMode;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * The explicit state-transition table for a factory operation mode.
 *
 * <p>This module deliberately owns only transition legality. World mutations, resource
 * compensation, chunk leases and capability invalidation remain ordered by the calling factory
 * transaction. Persisted/client state is restored through a separate path and never passes as a
 * player- or tick-driven transition.</p>
 */
public final class FactoryLifecycleTransitions {
    public enum Event {
        APPLY_BLUEPRINT,
        CANCEL_BLUEPRINT_TO_LOADED,
        CANCEL_BLUEPRINT_TO_ACTIVE,
        START_PREPARING,
        STOP_BLACKBOX,
        ENTER_LEARNING,
        ACTIVATE,
        ABORT_LEARNING,
        REPAIR_INTERRUPTED_LOAD,
        REPAIR_INCOMPLETE_PLAN,
        REPAIR_MISSING_BLUEPRINT,
        REPAIR_MISSING_FREEZE_MANIFEST
    }

    public record Transition(OperationMode from, OperationMode to, Event event) {
        public boolean changed() {
            return from != to;
        }
    }

    private static final Map<Event, Map<OperationMode, OperationMode>> TABLE = buildTable();

    private FactoryLifecycleTransitions() {
    }

    public static Transition require(OperationMode current, Event event) {
        if (current == null || event == null) {
            throw new IllegalArgumentException("Lifecycle transition requires a mode and event");
        }
        OperationMode target = TABLE.getOrDefault(event, Map.of()).get(current);
        if (target == null) {
            throw new IllegalStateException("Illegal factory lifecycle transition: "
                    + current.getSerializedName() + " + " + event);
        }
        return new Transition(current, target, event);
    }

    public static boolean allows(OperationMode current, Event event) {
        return current != null && event != null
                && TABLE.getOrDefault(event, Map.of()).containsKey(current);
    }

    private static Map<Event, Map<OperationMode, OperationMode>> buildTable() {
        EnumMap<Event, Map<OperationMode, OperationMode>> table = new EnumMap<>(Event.class);
        add(table, Event.APPLY_BLUEPRINT, OperationMode.BLUEPRINT,
                EnumSet.allOf(OperationMode.class));
        add(table, Event.CANCEL_BLUEPRINT_TO_LOADED, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLUEPRINT));
        add(table, Event.CANCEL_BLUEPRINT_TO_ACTIVE, OperationMode.BLACKBOX_ACTIVE,
                EnumSet.of(OperationMode.BLUEPRINT));
        add(table, Event.START_PREPARING, OperationMode.BLACKBOX_PREPARING,
                EnumSet.of(OperationMode.CHUNK_LOADED));
        add(table, Event.STOP_BLACKBOX, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLACKBOX_PREPARING, OperationMode.BLACKBOX_LEARNING,
                        OperationMode.BLACKBOX_ACTIVE));
        add(table, Event.ENTER_LEARNING, OperationMode.BLACKBOX_LEARNING,
                EnumSet.of(OperationMode.BLACKBOX_PREPARING));
        add(table, Event.ACTIVATE, OperationMode.BLACKBOX_ACTIVE,
                EnumSet.of(OperationMode.BLACKBOX_LEARNING));
        add(table, Event.ABORT_LEARNING, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLACKBOX_PREPARING, OperationMode.BLACKBOX_LEARNING));
        add(table, Event.REPAIR_INTERRUPTED_LOAD, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLACKBOX_PREPARING, OperationMode.BLACKBOX_LEARNING));
        add(table, Event.REPAIR_INCOMPLETE_PLAN, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLACKBOX_ACTIVE, OperationMode.BLUEPRINT));
        add(table, Event.REPAIR_MISSING_BLUEPRINT, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLUEPRINT));
        add(table, Event.REPAIR_MISSING_FREEZE_MANIFEST, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLACKBOX_ACTIVE, OperationMode.BLUEPRINT));
        return Map.copyOf(table);
    }

    private static void add(EnumMap<Event, Map<OperationMode, OperationMode>> table, Event event,
                            OperationMode target, EnumSet<OperationMode> sources) {
        EnumMap<OperationMode, OperationMode> edges = new EnumMap<>(OperationMode.class);
        for (OperationMode source : sources) {
            edges.put(source, target);
        }
        table.put(event, Map.copyOf(edges));
    }
}

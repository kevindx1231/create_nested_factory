package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.block.OperationMode;

import java.util.EnumSet;

/** Interface-level checks for every legal and rejected factory lifecycle edge. */
public final class FactoryLifecycleTransitionsRegression {
    private FactoryLifecycleTransitionsRegression() {
    }

    public static void main(String[] args) {
        expect(FactoryLifecycleTransitions.Event.APPLY_BLUEPRINT, OperationMode.BLUEPRINT,
                EnumSet.allOf(OperationMode.class));
        expect(FactoryLifecycleTransitions.Event.CANCEL_BLUEPRINT_TO_LOADED, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLUEPRINT));
        expect(FactoryLifecycleTransitions.Event.CANCEL_BLUEPRINT_TO_ACTIVE, OperationMode.BLACKBOX_ACTIVE,
                EnumSet.of(OperationMode.BLUEPRINT));
        expect(FactoryLifecycleTransitions.Event.START_PREPARING, OperationMode.BLACKBOX_PREPARING,
                EnumSet.of(OperationMode.CHUNK_LOADED));
        expect(FactoryLifecycleTransitions.Event.STOP_BLACKBOX, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLACKBOX_PREPARING, OperationMode.BLACKBOX_LEARNING,
                        OperationMode.BLACKBOX_ACTIVE));
        expect(FactoryLifecycleTransitions.Event.ENTER_LEARNING, OperationMode.BLACKBOX_LEARNING,
                EnumSet.of(OperationMode.BLACKBOX_PREPARING));
        expect(FactoryLifecycleTransitions.Event.ACTIVATE, OperationMode.BLACKBOX_ACTIVE,
                EnumSet.of(OperationMode.BLACKBOX_LEARNING));
        expect(FactoryLifecycleTransitions.Event.ABORT_LEARNING, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLACKBOX_PREPARING, OperationMode.BLACKBOX_LEARNING));
        expect(FactoryLifecycleTransitions.Event.REPAIR_INTERRUPTED_LOAD, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLACKBOX_PREPARING, OperationMode.BLACKBOX_LEARNING));
        expect(FactoryLifecycleTransitions.Event.REPAIR_INCOMPLETE_PLAN, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLACKBOX_ACTIVE, OperationMode.BLUEPRINT));
        expect(FactoryLifecycleTransitions.Event.REPAIR_MISSING_BLUEPRINT, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLUEPRINT));
        expect(FactoryLifecycleTransitions.Event.REPAIR_MISSING_FREEZE_MANIFEST, OperationMode.CHUNK_LOADED,
                EnumSet.of(OperationMode.BLACKBOX_ACTIVE, OperationMode.BLUEPRINT));
        System.out.println("Factory lifecycle-transition regression passed.");
    }

    private static void expect(FactoryLifecycleTransitions.Event event, OperationMode target,
                               EnumSet<OperationMode> allowedSources) {
        for (OperationMode source : OperationMode.values()) {
            boolean allowed = FactoryLifecycleTransitions.allows(source, event);
            require(allowed == allowedSources.contains(source),
                    event + " unexpectedly " + (allowed ? "accepted " : "rejected ") + source);
            if (allowed) {
                FactoryLifecycleTransitions.Transition transition =
                        FactoryLifecycleTransitions.require(source, event);
                require(transition.from() == source && transition.to() == target
                                && transition.event() == event,
                        event + " returned an incorrect transition from " + source);
            } else {
                try {
                    FactoryLifecycleTransitions.require(source, event);
                    throw new AssertionError(event + " did not reject " + source);
                } catch (IllegalStateException expected) {
                    // Expected: rejected edges are part of the interface contract.
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

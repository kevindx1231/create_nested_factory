package com.createnestedfactory.create_nested_factory.block;

final class FactorySessionLocationPolicy {
    enum Action {
        KEEP,
        ABORT
    }

    private FactorySessionLocationPolicy() {
    }

    static Action action(boolean active, boolean inPocket) {
        return active && !inPocket ? Action.ABORT : Action.KEEP;
    }
}

package com.createnestedfactory.create_nested_factory.block;

public final class FactorySessionLocationPolicyRegression {
    private FactorySessionLocationPolicyRegression() {
    }

    public static void main(String[] args) {
        require(FactorySessionLocationPolicy.action(false, false)
                        == FactorySessionLocationPolicy.Action.KEEP,
                "inactive overworld session must remain inactive");
        require(FactorySessionLocationPolicy.action(false, true)
                        == FactorySessionLocationPolicy.Action.KEEP,
                "inactive Pocket session must not be treated as an external escape");
        require(FactorySessionLocationPolicy.action(true, true)
                        == FactorySessionLocationPolicy.Action.KEEP,
                "active Pocket session must remain available for nested entry");
        require(FactorySessionLocationPolicy.action(true, false)
                        == FactorySessionLocationPolicy.Action.ABORT,
                "active session outside Pocket must be aborted before another factory entry");

        System.out.println("Factory session location-policy regression passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

package com.createnestedfactory.create_nested_factory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class PocketRegistryScopeRegression {
    private PocketRegistryScopeRegression() {
    }

    public static void main(String[] args) {
        Object firstServer = new Object();
        Object secondServer = new Object();
        PocketRegistry.ServerStates<Object> states = new PocketRegistry.ServerStates<>();
        BlockPos sharedOrigin = new BlockPos(0, 128, 0);
        PocketRegistry.FactoryLocation firstFactory = new PocketRegistry.FactoryLocation(
                "first-factory", Level.OVERWORLD, new BlockPos(1, 64, 1));
        PocketRegistry.FactoryLocation secondFactory = new PocketRegistry.FactoryLocation(
                "second-factory", Level.OVERWORLD, new BlockPos(2, 64, 2));

        require(states.get(firstServer).registerRoot(sharedOrigin, firstFactory),
                "first registration should succeed");
        require(!states.get(firstServer).registerRoot(sharedOrigin, secondFactory),
                "a second factory must not take the same origin within one server");
        require(states.get(secondServer).registerRoot(sharedOrigin, secondFactory),
                "different servers must be able to use the same room origin");

        states.remove(firstServer);
        require(states.get(firstServer).registerRoot(sharedOrigin, secondFactory),
                "a released server scope must not retain old registrations");

        System.out.println("Pocket registry server-scope regression passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

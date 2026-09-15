package com.createnestedfactory.create_nested_factory.block.entity;

/** Immutable route view published by a factory plan and owned by the runtime scheduler. */
public record FactoryRoute(String routeId, Type type, FactoryPlan contract) {
    public enum Type {
        PROCESSING,
        REGENERATIVE,
        COUPLED
    }
}

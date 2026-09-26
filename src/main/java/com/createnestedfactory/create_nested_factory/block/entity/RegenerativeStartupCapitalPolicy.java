package com.createnestedfactory.create_nested_factory.block.entity;

/** Defines which regenerative source contracts may copy real startup capital into blueprints. */
final class RegenerativeStartupCapitalPolicy {
    static final String TREE_ADAPTER_ID = "create_nested_factory:vanilla_tree";
    static final String CROP_ADAPTER_ID = "create_nested_factory:crop";
    static final int TREE_RULE_VERSION = 7;
    static final int CROP_RULE_VERSION = 2;

    private RegenerativeStartupCapitalPolicy() {
    }

    static boolean copiesEvidenceCapital(String adapterId) {
        return !TREE_ADAPTER_ID.equals(adapterId) && !CROP_ADAPTER_ID.equals(adapterId);
    }
}

package com.createnestedfactory.create_nested_factory.block.entity;

import java.util.Arrays;

/** Regression checks for capital-free built-in tree and crop blueprints. */
public final class PlantBlueprintCapitalRegression {
    private PlantBlueprintCapitalRegression() {
    }

    public static void main(String[] args) throws Exception {
        require(RegenerativeStartupCapitalPolicy.TREE_RULE_VERSION == 7,
                "the capital-free tree proof version changed unexpectedly");
        require(RegenerativeStartupCapitalPolicy.CROP_RULE_VERSION == 2,
                "the capital-free crop proof version changed unexpectedly");
        require(!RegenerativeStartupCapitalPolicy.copiesEvidenceCapital(
                        RegenerativeStartupCapitalPolicy.TREE_ADAPTER_ID),
                "tree evidence still contributes sapling startup capital");
        require(!RegenerativeStartupCapitalPolicy.copiesEvidenceCapital(
                        RegenerativeStartupCapitalPolicy.CROP_ADAPTER_ID),
                "crop evidence still contributes planting startup capital");
        require(RegenerativeStartupCapitalPolicy.copiesEvidenceCapital("example:third_party"),
                "third-party startup capital was accidentally disabled");
        require(RegenerativeStartupCapitalPolicy.copiesEvidenceCapital("create_nested_factory:cobblestone"),
                "unrelated built-in source capital policy changed");

        assertNoPlantCapitalState("RegenerativeSourceAdapters$TreeEvidence", "startupSaplings");
        assertNoPlantCapitalState("RegenerativeSourceAdapters$CropEvidence", "startupItems");

        System.out.println("Plant blueprint startup-capital regression passed.");
    }

    private static void assertNoPlantCapitalState(String nestedClass, String removedField) throws Exception {
        Class<?> type = Class.forName(PlantBlueprintCapitalRegression.class.getPackageName() + "." + nestedClass,
                false, PlantBlueprintCapitalRegression.class.getClassLoader());
        require(Arrays.stream(type.getDeclaredFields()).noneMatch(field -> removedField.equals(field.getName())),
                nestedClass + " retained redundant planting-capital state");
        require(Arrays.stream(type.getDeclaredMethods())
                        .noneMatch(method -> "startupCapitalItems".equals(method.getName())),
                nestedClass + " still overrides the empty planting-capital contract");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

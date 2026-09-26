package com.createnestedfactory.create_nested_factory.block.entity;

/** Interface-level regression checks for the extracted learning protocol state. */
public final class FactoryLearningControllerRegression {
    private FactoryLearningControllerRegression() {
    }

    public static void main(String[] args) {
        FactoryLearningController learning = new FactoryLearningController();
        require(learning.stage() == FactoryLearningController.Stage.WARMUP,
                "new learning controller did not start in warmup");

        learning.prepare(1_000L);
        require(learning.preparingTicks() == 1,
                "preparing duration changed during extraction");
        require(learning.tickPreparing(),
                "the one-tick preparing stage no longer completes on its first tick");

        learning.readClientStage("OBSERVING");
        require(learning.stage() == FactoryLearningController.Stage.OBSERVING,
                "valid client learning stage was not restored");
        learning.readClientStage("unknown-future-stage");
        require(learning.stage() == FactoryLearningController.Stage.WARMUP,
                "unknown client learning stage did not fail closed to warmup");

        learning.stop();
        require(learning.stage() == FactoryLearningController.Stage.WARMUP,
                "stopping learning did not restore warmup state");

        System.out.println("Factory learning-controller regression passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

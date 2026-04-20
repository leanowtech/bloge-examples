package com.leanowtech.bloge.examples.golden;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that representative example executions still match their checked-in golden baselines.
 *
 * <p>The suite replays end-to-end examples against snapshot fixtures, so it runs in the
 * integration-test phase to keep the default unit-test loop for {@code bloge-examples} fast.</p>
 */
class ExampleGoldenFileIT {

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void exampleExecutionMatchesGolden(ExampleGoldenScenarios.GoldenScenario scenario) throws Exception {
        GoldenSnapshotSupport.assertMatchesGolden(scenario);
    }

    static java.util.stream.Stream<ExampleGoldenScenarios.GoldenScenario> scenarios() {
        return ExampleGoldenScenarios.scenarios();
    }
}

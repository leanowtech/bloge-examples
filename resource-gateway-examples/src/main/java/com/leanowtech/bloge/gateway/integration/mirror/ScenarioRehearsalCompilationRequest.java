package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Fully resolved immutable inputs to the pure ScenarioPack rehearsal compiler.
 *
 * <p>Online application services own registry lookup and authorization. This request carries the
 * exact returned artifacts so the compiler can independently verify every reference and reject a
 * dishonest, stale, or cross-scope repository result.</p>
 *
 * @param pack exact source ScenarioPack
 * @param cases resolved cases in pack order
 * @param assertions exact pack-wide assertion closure
 * @param validatedAt trusted policy time
 */
public record ScenarioRehearsalCompilationRequest(
        ScenarioPack pack,
        List<ResolvedCase> cases,
        List<CaseHandlingAssertion> assertions,
        Instant validatedAt
) {
    /** Freezes one complete resolved compilation request. */
    public ScenarioRehearsalCompilationRequest {
        pack = Objects.requireNonNull(pack, "pack");
        cases = cases == null ? List.of() : List.copyOf(cases);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        validatedAt = Objects.requireNonNull(validatedAt, "validatedAt");
    }

    /**
     * Exact existing test and mirror assets resolved for one ScenarioCase.
     *
     * @param scenarioCase exact ScenarioCase
     * @param testSuite exact stored TestSuite revision
     * @param fixtureBundle exact stored FixtureBundle revision
     * @param mirrorPlan exact reusable MirrorPlan generation
     * @param sessionCheckpoint optional signed checkpoint bundle
     */
    public record ResolvedCase(
            ScenarioCase scenarioCase,
            StoredTestSuite testSuite,
            StoredFixtureBundle fixtureBundle,
            MirrorPlan mirrorPlan,
            MirrorSessionCheckpointBundle sessionCheckpoint
    ) {
        /** Requires the four mandatory immutable case dependencies. */
        public ResolvedCase {
            scenarioCase = Objects.requireNonNull(scenarioCase, "scenarioCase");
            testSuite = Objects.requireNonNull(testSuite, "testSuite");
            fixtureBundle = Objects.requireNonNull(fixtureBundle, "fixtureBundle");
            mirrorPlan = Objects.requireNonNull(mirrorPlan, "mirrorPlan");
        }
    }
}

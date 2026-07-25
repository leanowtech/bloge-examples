package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class ScenarioRehearsalRemediationComparisonTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void comparesOnlyExactSignedWorkbookCommitmentsDeterministically() {
        ScenarioRehearsalRemediationComparisonTestFixtures
                .Fixture fixture =
                ScenarioRehearsalRemediationComparisonTestFixtures
                        .resolved(mapper);

        ScenarioRehearsalRemediationComparison first =
                ScenarioRehearsalRemediationComparison.project(
                        mapper,
                        fixture.lineage(),
                        fixture.predecessor(),
                        fixture.successor());
        ScenarioRehearsalRemediationComparison second =
                ScenarioRehearsalRemediationComparison.project(
                        mapper,
                        fixture.lineage(),
                        fixture.predecessor(),
                        fixture.successor());

        assertThat(first).isEqualTo(second);
        assertThat(first.gateTransition()).isEqualTo(
                ScenarioRehearsalRemediationComparison
                        .GateTransition.RESOLVED);
        assertThat(first.resolvedBlockers())
                .containsExactly(
                        "BATCH_ITEM_FAILED",
                        "BATCH_STATUS_FAILED",
                        "CHILD_WORKBOOK_BLOCKED");
        assertThat(first.remainingBlockers()).isEmpty();
        assertThat(first.introducedBlockers()).isEmpty();
        assertThat(first.entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.planChanged()).isTrue();
                    assertThat(entry.gateTransition())
                            .isEqualTo(
                                    ScenarioRehearsalRemediationComparison
                                            .GateTransition
                                            .RESOLVED);
                    assertThat(entry.predecessor()
                            .summary().failedCases())
                            .isOne();
                    assertThat(entry.successor()
                            .summary().passedCases())
                            .isOne();
                });
        assertThat(first.predecessor()
                .correctnessSummary().blockerFailures())
                .isOne();
        assertThat(first.successor()
                .correctnessSummary().blockerFailures())
                .isZero();
        first.verify(mapper);
    }

    @Test
    void rejectsSelfConsistentSourcesThatDriftFromTheReviewedLineage() {
        ScenarioRehearsalRemediationComparisonTestFixtures
                .Fixture fixture =
                ScenarioRehearsalRemediationComparisonTestFixtures
                        .resolved(mapper);
        when(fixture.successor().requestFingerprint())
                .thenReturn(
                        "sha256:" + "f".repeat(64));

        assertThatThrownBy(() ->
                ScenarioRehearsalRemediationComparison.project(
                        mapper,
                        fixture.lineage(),
                        fixture.predecessor(),
                        fixture.successor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineage");
    }
}

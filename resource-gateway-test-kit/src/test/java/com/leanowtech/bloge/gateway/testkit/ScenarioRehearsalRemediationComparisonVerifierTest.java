package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRehearsalRemediationComparisonVerifierTest {
    private final ScenarioRehearsalRemediationComparisonVerifier
            verifier =
            new ScenarioRehearsalRemediationComparisonVerifier();

    @Test
    void independentlyReconstructsResolvedSignedWorkbookComparison() {
        ScenarioRehearsalRemediationComparisonTestFixtures
                .Fixture fixture =
                ScenarioRehearsalRemediationComparisonTestFixtures
                        .resolved();

        ScenarioRehearsalRemediationComparisonVerifier
                .VerificationResult result =
                verifier.verify(
                        fixture.comparison(),
                        fixture.lineage(),
                        fixture.predecessor(),
                        fixture.successor());

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.reasonCode())
                .isEqualTo("VERIFIED");
        assertThat(result.remediationId())
                .isEqualTo(
                        ScenarioRehearsalRemediationTestFixtures
                                .REMEDIATION_ID);
        assertThat(result.gateTransition())
                .isEqualTo("RESOLVED");
        assertThat(result.entryCount()).isOne();
        assertThat(result.resolvedBlockers())
                .containsExactly(
                        "BATCH_ITEM_FAILED",
                        "BATCH_STATUS_FAILED",
                        "CHILD_WORKBOOK_BLOCKED");
        assertThat(result.remainingBlockers())
                .isEmpty();
        assertThat(result.introducedBlockers())
                .isEmpty();
    }

    @Test
    void rejectsComparisonWhoseFingerprintWasNotRecomputed() {
        ScenarioRehearsalRemediationComparisonTestFixtures
                .Fixture fixture =
                ScenarioRehearsalRemediationComparisonTestFixtures
                        .resolved();
        ObjectNode comparison =
                fixture.comparison().deepCopy();
        comparison.withArray("resolvedBlockers")
                .remove(0);

        ScenarioRehearsalRemediationComparisonVerifier
                .VerificationResult result =
                verifier.verify(
                        comparison,
                        fixture.lineage(),
                        fixture.predecessor(),
                        fixture.successor());

        assertThat(result.verified()).isFalse();
        assertThat(result.reasonCode())
                .isEqualTo(
                        "SCENARIO_REMEDIATION_COMPARISON_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsSelfConsistentComparisonThatLiesAboutSourceProjection() {
        ScenarioRehearsalRemediationComparisonTestFixtures
                .Fixture fixture =
                ScenarioRehearsalRemediationComparisonTestFixtures
                        .resolved();
        ObjectNode comparison =
                fixture.comparison().deepCopy();
        comparison.withArray("resolvedBlockers")
                .remove(0);
        reseal(comparison);

        ScenarioRehearsalRemediationComparisonVerifier
                .VerificationResult result =
                verifier.verify(
                        comparison,
                        fixture.lineage(),
                        fixture.predecessor(),
                        fixture.successor());

        assertThat(result.verified()).isFalse();
        assertThat(result.reasonCode())
                .startsWith(
                        "SCENARIO_REMEDIATION_COMPARISON_PROJECTION_");
    }

    @Test
    void rejectsSuccessorWorkbookThatEscapesFrozenRequest() {
        ScenarioRehearsalRemediationComparisonTestFixtures
                .Fixture fixture =
                ScenarioRehearsalRemediationComparisonTestFixtures
                        .resolved();
        ObjectNode successor =
                fixture.successor().deepCopy();
        successor.put(
                "requestFingerprint",
                "sha256:" + "0".repeat(64));

        ScenarioRehearsalRemediationComparisonVerifier
                .VerificationResult result =
                verifier.verify(
                        fixture.comparison(),
                        fixture.lineage(),
                        fixture.predecessor(),
                        successor);

        assertThat(result.verified()).isFalse();
        assertThat(result.reasonCode())
                .isEqualTo(
                        "SCENARIO_REMEDIATION_COMPARISON_LINEAGE_CLOSURE_INVALID");
    }

    private static void reseal(
            ObjectNode comparison) {
        comparison.put("comparisonFingerprint", "");
        comparison.put(
                "comparisonFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                comparison,
                                ScenarioRehearsalRemediationComparisonVerifier
                                        .MAXIMUM_COMPARISON_BYTES));
    }
}

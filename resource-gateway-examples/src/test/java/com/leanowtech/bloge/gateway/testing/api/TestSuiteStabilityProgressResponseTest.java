package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityProgressResponseTest {
    private static final String RUN_ID = "stability-" + "a".repeat(64);
    private static final TestSuiteExecutionRequest.SuiteRef SUITE_REF =
            new TestSuiteExecutionRequest.SuiteRef(
                    "orders-suite", 7, "sha256:" + "b".repeat(64));
    private static final Instant START = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void preservesHistoricalV1FullHorizonSemantics() {
        TestSuiteStabilityProgressResponse progress = new TestSuiteStabilityProgressResponse(
                TestSuiteStabilityProgressResponse.SCHEMA_VERSION_V1, RUN_ID,
                TestSuiteStabilityProgressResponse.Status.COMPLETED, SUITE_REF,
                30, 30, null, START, START.plusSeconds(30));

        assertThat(progress.terminalReason()).isNull();
        assertThatThrownBy(() -> new TestSuiteStabilityProgressResponse(
                TestSuiteStabilityProgressResponse.SCHEMA_VERSION_V1, RUN_ID,
                TestSuiteStabilityProgressResponse.Status.COMPLETED, SUITE_REF,
                30, 29, null, START, START.plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesOnlyAnAllowedV2EarlyTerminalReason() {
        TestSuiteStabilityProgressResponse progress = new TestSuiteStabilityProgressResponse(
                TestSuiteStabilityProgressResponse.SCHEMA_VERSION, RUN_ID,
                TestSuiteStabilityProgressResponse.Status.COMPLETED, SUITE_REF,
                100, 57,
                TestSuiteStabilityEvidence.StatisticalStopReason.E_VALUE_THRESHOLD_REACHED,
                START, START.plusSeconds(57));

        assertThat(progress.completedAttempts()).isEqualTo(57);
        assertThat(progress.terminalReason())
                .isEqualTo(TestSuiteStabilityEvidence.StatisticalStopReason
                        .E_VALUE_THRESHOLD_REACHED);
        assertThatThrownBy(() -> new TestSuiteStabilityProgressResponse(
                TestSuiteStabilityProgressResponse.SCHEMA_VERSION, RUN_ID,
                TestSuiteStabilityProgressResponse.Status.COMPLETED, SUITE_REF,
                100, 57,
                TestSuiteStabilityEvidence.StatisticalStopReason.FIXED_HORIZON_REACHED,
                START, START.plusSeconds(57)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forbidsTerminalReasonsOnActiveV2Progress() {
        assertThatThrownBy(() -> new TestSuiteStabilityProgressResponse(
                TestSuiteStabilityProgressResponse.SCHEMA_VERSION, RUN_ID,
                TestSuiteStabilityProgressResponse.Status.RECOVERABLE, SUITE_REF,
                100, 57,
                TestSuiteStabilityEvidence.StatisticalStopReason.E_VALUE_THRESHOLD_REACHED,
                START, START.plusSeconds(57)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

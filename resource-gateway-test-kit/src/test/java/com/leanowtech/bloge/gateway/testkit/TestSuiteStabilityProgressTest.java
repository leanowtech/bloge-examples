package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityProgressTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesAStrictPayloadFreeRecoverableProjection() throws Exception {
        ObjectNode response = progress("RECOVERABLE", 11);

        TestSuiteStabilityProgress progress = TestSuiteStabilityProgress.from(response);

        assertThat(progress.status()).isEqualTo(TestSuiteStabilityProgress.Status.RECOVERABLE);
        assertThat(progress.plannedAttempts()).isEqualTo(29);
        assertThat(progress.completedAttempts()).isEqualTo(11);
        assertThat(progress.suiteRef().suiteId()).isEqualTo("orders-suite");
        assertThat(progress.rawResponse().has("ownerId")).isFalse();
        ((ObjectNode) progress.rawResponse()).put("status", "RUNNING");
        assertThat(progress.rawResponse().path("status").asText()).isEqualTo("RECOVERABLE");
    }

    @Test
    void rejectsUnknownInternalCoordinatesAndContradictoryTerminalCounts() throws Exception {
        ObjectNode leaked = progress("RUNNING", 11).put("ownerId", "replica-a");
        ObjectNode incompleteTerminal = progress("COMPLETED", 28);

        assertThatThrownBy(() -> TestSuiteStabilityProgress.from(leaked))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSuiteStabilityProgress.from(incompleteTerminal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void parsesV2EarlyTerminalReasonWithoutConfusingPlanAndObservedPrefix() throws Exception {
        ObjectNode response = progressV2("COMPLETED", 57,
                "E_VALUE_THRESHOLD_REACHED");

        TestSuiteStabilityProgress progress = TestSuiteStabilityProgress.from(response);

        assertThat(progress.schemaVersion())
                .isEqualTo(TestingProtocol.TEST_SUITE_STABILITY_PROGRESS_V2);
        assertThat(progress.plannedAttempts()).isEqualTo(100);
        assertThat(progress.completedAttempts()).isEqualTo(57);
        assertThat(progress.terminalReason())
                .isEqualTo(TestSuiteStabilityRun.StatisticalStopReason
                        .E_VALUE_THRESHOLD_REACHED);
    }

    @Test
    void rejectsV2ReasonsOnActiveRunsAndEarlyMaximumHorizonClaims() throws Exception {
        ObjectNode activeWithReason = progressV2("RUNNING", 11,
                "E_VALUE_THRESHOLD_REACHED");
        ObjectNode earlyMaximum = progressV2("COMPLETED", 99,
                "MAXIMUM_HORIZON_REACHED");

        assertThatThrownBy(() -> TestSuiteStabilityProgress.from(activeWithReason))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSuiteStabilityProgress.from(earlyMaximum))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode progress(String status, int completed) throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"schemaVersion":"bloge.testSuiteStabilityProgress.v1",
                 "stabilityRunId":"stability-2222222222222222222222222222222222222222222222222222222222222222",
                 "status":"%s",
                 "suiteRef":{"suiteId":"orders-suite","revision":7,
                   "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "plannedAttempts":29,"completedAttempts":%d,
                 "createdAt":"2026-07-18T01:02:03Z",
                 "updatedAt":"2026-07-18T01:03:03Z"}
                """.formatted(status, completed));
    }

    private static ObjectNode progressV2(
            String status,
            int completed,
            String terminalReason) throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"schemaVersion":"bloge.testSuiteStabilityProgress.v2",
                 "stabilityRunId":"stability-2222222222222222222222222222222222222222222222222222222222222222",
                 "status":"%s",
                 "suiteRef":{"suiteId":"orders-suite","revision":7,
                   "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "plannedAttempts":100,"completedAttempts":%d,
                 "terminalReason":"%s",
                 "createdAt":"2026-07-18T01:02:03Z",
                 "updatedAt":"2026-07-18T01:03:03Z"}
                """.formatted(status, completed, terminalReason));
    }
}

package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityJobCancellationReceiptTest {

    private static final String JOB_ID = "stability-job-" + "1".repeat(64);
    private static final String FINGERPRINT = "sha256:" + "2".repeat(64);
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-18T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void validatesEveryClosedCancellationOutcome() {
        assertReceipt(TestSuiteStabilityJobRecord.Status.QUEUED,
                TestSuiteStabilityJobRecord.Status.CANCELLED,
                TestSuiteStabilityJobCancellationReceipt.Outcome.CANCELLED_BEFORE_START);
        assertReceipt(TestSuiteStabilityJobRecord.Status.RUNNING,
                TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED,
                TestSuiteStabilityJobCancellationReceipt.Outcome.CANCELLATION_REQUESTED);
        assertReceipt(TestSuiteStabilityJobRecord.Status.COMMITTING,
                TestSuiteStabilityJobRecord.Status.COMMITTING,
                TestSuiteStabilityJobCancellationReceipt.Outcome.TOO_LATE_TO_CANCEL);
        assertReceipt(TestSuiteStabilityJobRecord.Status.FAILED,
                TestSuiteStabilityJobRecord.Status.FAILED,
                TestSuiteStabilityJobCancellationReceipt.Outcome.ALREADY_TERMINAL);
        assertReceipt(TestSuiteStabilityJobRecord.Status.RUNNING,
                TestSuiteStabilityJobRecord.Status.SUCCEEDED,
                TestSuiteStabilityJobCancellationReceipt.Outcome.PARENT_ALREADY_COMPLETED);
    }

    @Test
    void rejectsContradictoryOutcomeAndScope() {
        assertThatThrownBy(() -> new TestSuiteStabilityJobCancellationReceipt(
                command(principal("tenant-a", "test", "correlation-a")),
                TestSuiteStabilityJobRecord.Status.COMMITTING,
                TestSuiteStabilityJobRecord.Status.CANCELLED,
                TestSuiteStabilityJobCancellationReceipt.Outcome.TOO_LATE_TO_CANCEL,
                OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new TestSuiteStabilityJobCancellationCommand(
                "tenant-a", "test", JOB_ID, "cancel-a", FINGERPRINT,
                principal("tenant-b", "test", "correlation-a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emitsDeterministicPayloadFreeAuditFacts() {
        TestSuiteStabilityJobCancellationReceipt receipt =
                new TestSuiteStabilityJobCancellationReceipt(
                        command(principal("tenant-a", "test", "correlation-a")),
                        TestSuiteStabilityJobRecord.Status.COMMITTING,
                        TestSuiteStabilityJobRecord.Status.COMMITTING,
                        TestSuiteStabilityJobCancellationReceipt.Outcome.TOO_LATE_TO_CANCEL,
                        OCCURRED_AT);

        TestSecurityEvent event = receipt.toSecurityEvent(mapper);

        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.correlationId()).isEqualTo("correlation-a");
        assertThat(event.reasonCode())
                .isEqualTo("RG.TEST.STABILITY_JOB_CANCELLATION_TOO_LATE");
        assertThat(event.facts())
                .containsEntry("groupCount", 2)
                .containsEntry("previousStatus", "COMMITTING")
                .containsEntry("resultingStatus", "COMMITTING")
                .containsEntry("commandFingerprint", FINGERPRINT)
                .containsKeys("groupFingerprint", "semanticFingerprint")
                .doesNotContainKeys("groups", "request", "metadata", "context",
                        "credential", "payload");
        assertThat(event.facts().get("semanticFingerprint").toString())
                .matches("sha256:[a-f0-9]{64}");
        assertThat(TestSuiteStabilityJobCancellationReceipt.verifySecurityEvent(mapper, event))
                .isSameAs(event);

        LinkedHashMap<String, Object> changedFacts = new LinkedHashMap<>(event.facts());
        changedFacts.put("resultingStatus", "SUCCEEDED");
        TestSecurityEvent tampered = new TestSecurityEvent(
                event.sequence(), event.occurredAt(), event.correlationId(), event.tenantId(),
                event.environmentId(), event.actorId(), event.eventType(), event.outcome(),
                event.reasonCode(), changedFacts);
        assertThatThrownBy(() ->
                TestSuiteStabilityJobCancellationReceipt.verifySecurityEvent(mapper, tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    private void assertReceipt(
            TestSuiteStabilityJobRecord.Status previous,
            TestSuiteStabilityJobRecord.Status resulting,
            TestSuiteStabilityJobCancellationReceipt.Outcome outcome) {
        assertThat(new TestSuiteStabilityJobCancellationReceipt(
                command(principal("tenant-a", "test", "correlation-a")),
                previous, resulting, outcome, OCCURRED_AT).outcome()).isEqualTo(outcome);
    }

    private static TestSuiteStabilityJobCancellationCommand command(
            TestSuiteStabilityJobPrincipal principal) {
        return new TestSuiteStabilityJobCancellationCommand(
                "tenant-a", "test", JOB_ID, "cancel-a", FINGERPRINT, principal);
    }

    private static TestSuiteStabilityJobPrincipal principal(
            String tenantId, String environmentId, String correlationId) {
        return new TestSuiteStabilityJobPrincipal(
                tenantId, "org-a", "project-a", environmentId, "sg-1", "WORKLOAD",
                "runner-a", "owner-a", "TEST_EXECUTION", correlationId,
                Set.of("quality", "release"), "CONFIDENTIAL", "grant-a");
    }
}

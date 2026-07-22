package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorOperationObservabilityTest {
    private static final Instant DATABASE_TIME = Instant.parse("2026-07-23T04:00:00Z");

    @Test
    void successAuditsExactTraceCoordinatesBeforeRecordingMetrics() {
        RecordingAuditRepository audit = new RecordingAuditRepository();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicLong nanos = new AtomicLong(1_000_000);
        MirrorOperationObservability observability = new MirrorOperationObservability(
                audit, new MirrorOperationTelemetry(meters), nanos::get);
        MirrorOperationObservability.Observation observation = observability.start(
                MirrorOperationAuditEvent.Operation.RUN_CREATE,
                MirrorPersistenceTestFixtures.identity("org-a"),
                "request-1", "plan-1", "");

        nanos.set(26_000_000);
        observation.succeeded("run-1");

        assertThat(audit.events).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(1);
            assertThat(event.occurredAt()).isEqualTo(DATABASE_TIME);
            assertThat(event.organizationId()).isEqualTo("org-a");
            assertThat(event.correlationId()).isEqualTo("corr-mirror-test");
            assertThat(event.operation())
                    .isEqualTo(MirrorOperationAuditEvent.Operation.RUN_CREATE);
            assertThat(event.outcome()).isEqualTo(MirrorOperationAuditEvent.Outcome.SUCCEEDED);
            assertThat(event.reason()).isEqualTo(MirrorOperationAuditEvent.Reason.NONE);
            assertThat(event.reasonCode()).isBlank();
            assertThat(event.requestId()).isEqualTo("request-1");
            assertThat(event.planId()).isEqualTo("plan-1");
            assertThat(event.runId()).isEqualTo("run-1");
            assertThat(event.durationMillis()).isEqualTo(25);
        });
        assertThat(meters.get("resource.gateway.mirror.operations")
                .tags("operation", "run_create", "outcome", "succeeded")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void failureClassificationUsesStableCodesWithoutRetainingExceptionPayloads() {
        RecordingAuditRepository audit = new RecordingAuditRepository();
        MirrorOperationObservability observability = new MirrorOperationObservability(
                audit, MirrorOperationTelemetry.noop(), () -> 0);
        RuntimeException invalid = problem(IntegrationProblem.badRequest(
                "RG.MIRROR.PLAN_INVALID", "invalid customer value", "corr", Map.of()));
        RuntimeException missing = problem(IntegrationProblem.notFound(
                "RG.MIRROR.RUN_NOT_FOUND", "customer C-42 missing", "corr", Map.of()));
        RuntimeException unavailable = problem(IntegrationProblem.serviceUnavailable(
                "RG.MIRROR.RUN_UNAVAILABLE", "secret backend address", "corr", Map.of()));
        RuntimeException unexpected = new IllegalStateException("raw customer payload");

        assertThat(fail(observability, invalid)).isSameAs(invalid);
        assertThat(fail(observability, missing)).isSameAs(missing);
        assertThat(fail(observability, unavailable)).isSameAs(unavailable);
        assertThat(fail(observability, unexpected)).isSameAs(unexpected);

        assertThat(audit.events)
                .extracting(MirrorOperationAuditEvent::outcome,
                        MirrorOperationAuditEvent::reason,
                        MirrorOperationAuditEvent::reasonCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Outcome.REJECTED,
                                MirrorOperationAuditEvent.Reason.INVALID_REQUEST,
                                "RG.MIRROR.PLAN_INVALID"),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Outcome.REJECTED,
                                MirrorOperationAuditEvent.Reason.NOT_FOUND,
                                "RG.MIRROR.RUN_NOT_FOUND"),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Outcome.FAILED,
                                MirrorOperationAuditEvent.Reason.UNAVAILABLE,
                                "RG.MIRROR.RUN_UNAVAILABLE"),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Outcome.FAILED,
                                MirrorOperationAuditEvent.Reason.UNEXPECTED,
                                "RG.MIRROR.UNEXPECTED_FAILURE"));
        assertThat(audit.events.toString()).doesNotContain(
                "invalid customer value", "customer C-42", "secret backend", "raw customer");
    }

    @Test
    void unavailableMandatoryAuditFailsClosedWithSanitizedStableProblem() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MirrorOperationAuditRepository broken = new MirrorOperationAuditRepository() {
            @Override
            public MirrorOperationAuditEvent append(MirrorOperationAuditEvent event) {
                throw new IllegalStateException("customer-secret-value");
            }

            @Override
            public List<MirrorOperationAuditEvent> recent(
                    CapabilitySnapshot.Scope scope, int limit) {
                return List.of();
            }
        };
        MirrorOperationObservability observability = new MirrorOperationObservability(
                broken, new MirrorOperationTelemetry(meters), () -> 0);
        MirrorOperationObservability.Observation observation = observability.start(
                MirrorOperationAuditEvent.Operation.EVIDENCE_READ,
                MirrorPersistenceTestFixtures.identity("org-a"), "", "", "run-1");

        assertThatThrownBy(() -> observation.succeeded("run-1"))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE");
                    assertThat(failure.problem().details()).isEmpty();
                    assertThat(failure.toString()).doesNotContain("customer-secret-value");
                });
        assertThat(meters.get("resource.gateway.mirror.failures")
                .tags("operation", "evidence_read", "reason", "audit_unavailable")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void unrepresentableAuditCoordinatesAlsoFailClosedBeforePublication() {
        RecordingAuditRepository audit = new RecordingAuditRepository();
        IntegrationRequestContext unbounded = new IntegrationRequestContext(
                "tenant-a", "org-a", "support", "test", "sg", "SERVICE",
                "x".repeat(256), "", "MIRROR_REHEARSAL", "corr-1",
                Set.of(), "CONFIDENTIAL", "");
        MirrorOperationObservability.Observation observation =
                new MirrorOperationObservability(
                        audit, MirrorOperationTelemetry.noop(), () -> 0)
                        .start(MirrorOperationAuditEvent.Operation.PLAN_READ,
                                unbounded, "", "plan-1", "");

        assertThatThrownBy(() -> observation.succeeded(""))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE"));
        assertThat(audit.events).isEmpty();
    }

    @Test
    void observationCanPublishExactlyOneTerminalFact() {
        RecordingAuditRepository audit = new RecordingAuditRepository();
        MirrorOperationObservability.Observation observation =
                new MirrorOperationObservability(
                        audit, MirrorOperationTelemetry.noop(), () -> 0)
                        .start(MirrorOperationAuditEvent.Operation.PLAN_READ,
                                MirrorPersistenceTestFixtures.identity("org-a"),
                                "", "plan-1", "");

        observation.succeeded("");

        assertThatThrownBy(() -> observation.failed(new IllegalStateException("late failure")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already terminal");
        assertThat(audit.events).hasSize(1);
    }

    private static RuntimeException fail(
            MirrorOperationObservability observability, RuntimeException failure) {
        return observability.start(MirrorOperationAuditEvent.Operation.PLAN_CREATE,
                        MirrorPersistenceTestFixtures.identity("org-a"), "", "plan-1", "")
                .failed(failure);
    }

    private static IntegrationProblemException problem(IntegrationProblem problem) {
        return new IntegrationProblemException(problem);
    }

    private static final class RecordingAuditRepository
            implements MirrorOperationAuditRepository {
        private final List<MirrorOperationAuditEvent> events = new ArrayList<>();

        @Override
        public MirrorOperationAuditEvent append(MirrorOperationAuditEvent event) {
            MirrorOperationAuditEvent persisted = event.persisted(
                    events.size() + 1L, DATABASE_TIME.plusSeconds(events.size()));
            events.add(persisted);
            return persisted;
        }

        @Override
        public List<MirrorOperationAuditEvent> recent(
                CapabilitySnapshot.Scope scope, int limit) {
            return List.copyOf(events);
        }
    }
}

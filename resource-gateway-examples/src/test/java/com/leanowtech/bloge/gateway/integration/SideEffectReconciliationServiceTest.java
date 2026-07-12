package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSideEffectAttempt;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SideEffectReconciliationServiceTest {
    private final List<SideEffectReconciliationService> services = new ArrayList<>();

    @AfterEach
    void closeServices() {
        services.forEach(SideEffectReconciliationService::closeExecutor);
    }

    @Test
    void appendsSignedCommitResolutionAndMakesSupplementalGovernanceViewReady() {
        Fixture fixture = fixture(true);
        AtomicInteger invocations = new AtomicInteger();
        SideEffectReconciler reconciler = committedReconciler(invocations);
        SideEffectReconciliationService service = service(fixture,
                new InMemorySideEffectReconciliationRepository(), reconciler);
        SideEffectReconciliationRequest request = request(fixture, "reconcile-1");

        SideEffectReconciliationRecord first = service.reconcile(
                fixture.run().runId(), fixture.attempt().attemptId(), request, reconciliationContext()).payload();
        SideEffectReconciliationRecord repeated = service.reconcile(
                fixture.run().runId(), fixture.attempt().attemptId(), request, reconciliationContext()).payload();
        SideEffectReconciliationSummary summary = service.summary(
                fixture.run().runId(), evidenceContext()).payload();

        assertThat(first).isEqualTo(repeated);
        assertThat(invocations).hasValue(1);
        assertThat(first.fingerprintVerified()).isTrue();
        assertThat(first.verify(fixture.runs().evidenceSigner()).valid()).isTrue();
        assertThat(first.resolution().outcome()).isEqualTo("COMMITTED");
        assertThat(first.resolution().receipt().receiptId()).isEqualTo("receipt-42");
        assertThat(first.target().reconciliationLookupRef()).isEqualTo("vault://commands/charge-42");
        assertThat(first.toString()).doesNotContain("raw-idempotency-secret");
        assertThat(summary.status()).isEqualTo("RESOLVED");
        assertThat(summary.governanceStatus()).isEqualTo("READY");
        assertThat(summary.outstandingAttemptIds()).isEmpty();
        assertThat(summary.remainingEvidenceGaps()).isEmpty();
        assertThat(fixture.evidence().manifest().evidenceStatus()).isEqualTo("QUARANTINED");
    }

    @Test
    void rejectsStaleTargetAndConflictingSecondResolutionBeforeProviderInvocation() {
        Fixture fixture = fixture(true);
        AtomicInteger invocations = new AtomicInteger();
        SideEffectReconciliationService service = service(fixture,
                new InMemorySideEffectReconciliationRepository(), committedReconciler(invocations));
        SideEffectReconciliationRequest stale = new SideEffectReconciliationRequest(
                "", "stale-1", "sha256:" + "0".repeat(64), fixture.attempt().attemptFingerprint());

        assertThatThrownBy(() -> service.reconcile(
                fixture.run().runId(), fixture.attempt().attemptId(), stale, reconciliationContext()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(problem(error).code())
                        .isEqualTo("RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_STALE"));

        service.reconcile(fixture.run().runId(), fixture.attempt().attemptId(),
                request(fixture, "reconcile-1"), reconciliationContext());
        assertThatThrownBy(() -> service.reconcile(
                fixture.run().runId(), fixture.attempt().attemptId(),
                request(fixture, "reconcile-2"), reconciliationContext()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(problem(error).code())
                        .isEqualTo("RG.INTEGRATION.SIDE_EFFECT_ALREADY_RECONCILED"));
        assertThat(invocations).hasValue(1);
    }

    @Test
    void failsClosedWhenOperatorDidNotPersistOpaqueLookupReference() {
        Fixture fixture = fixture(false);
        AtomicInteger invocations = new AtomicInteger();
        SideEffectReconciliationService service = service(fixture,
                new InMemorySideEffectReconciliationRepository(), committedReconciler(invocations));

        assertThatThrownBy(() -> service.reconcile(
                fixture.run().runId(), fixture.attempt().attemptId(),
                request(fixture, "reconcile-1"), reconciliationContext()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(problem(error).code())
                        .isEqualTo("RG.INTEGRATION.SIDE_EFFECT_NOT_RECONCILABLE"));
        assertThat(invocations).hasValue(0);
    }

    @Test
    void hidesCrossTenantRunAndBoundsFailingProviderAsRetryableServiceError() {
        Fixture fixture = fixture(true);
        SideEffectReconciler failing = new SideEffectReconciler() {
            @Override
            public String reconcilerRef() {
                return "payments.status";
            }

            @Override
            public Resolution reconcile(Query query) {
                throw new IllegalStateException("provider unavailable");
            }
        };
        SideEffectReconciliationService service = service(fixture,
                new InMemorySideEffectReconciliationRepository(), failing);

        assertThatThrownBy(() -> service.summary(fixture.run().runId(), otherTenantContext()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(problem(error).status()).isEqualTo(404));
        assertThatThrownBy(() -> service.reconcile(
                fixture.run().runId(), fixture.attempt().attemptId(),
                request(fixture, "reconcile-1"), reconciliationContext()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> {
                    assertThat(problem(error).code())
                            .isEqualTo("RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_FAILED");
                    assertThat(problem(error).retryable()).isTrue();
                });
    }

    @Test
    void keepsLegacyUnattributedUnknownCommitQuarantined() {
        Fixture fixture = fixture(true, false);
        SideEffectReconciliationService service = service(fixture,
                new InMemorySideEffectReconciliationRepository(), committedReconciler(new AtomicInteger()));

        SideEffectReconciliationSummary summary = service.summary(
                fixture.run().runId(), evidenceContext()).payload();

        assertThat(summary.status()).isEqualTo("OUTSTANDING");
        assertThat(summary.governanceStatus()).isEqualTo("QUARANTINED");
        assertThat(summary.outstandingAttemptIds())
                .containsExactly("node:charge:unattributed-unknown-commit");
        assertThat(summary.remainingEvidenceGaps()).contains(SideEffectReconciliationService.SIDE_EFFECT_GAP);
    }

    private SideEffectReconciliationService service(Fixture fixture,
                                                     SideEffectReconciliationRepository repository,
                                                     SideEffectReconciler reconciler) {
        SideEffectReconciliationService service = new SideEffectReconciliationService(
                fixture.runs(), repository, new SideEffectReconcilerRegistry(List.of(reconciler)),
                Clock.fixed(Instant.parse("2026-07-12T12:01:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30), Duration.ofSeconds(2));
        services.add(service);
        return service;
    }

    private static SideEffectReconciler committedReconciler(AtomicInteger invocations) {
        return new SideEffectReconciler() {
            @Override
            public String reconcilerRef() {
                return "payments.status";
            }

            @Override
            public Resolution reconcile(Query query) {
                invocations.incrementAndGet();
                assertThat(query.attempt().reconciliationLookupRef())
                        .isEqualTo("vault://commands/charge-42");
                return new Resolution("COMMITTED", new RunEvidenceBundle.SideEffectReceipt(
                        "receipt-42", "payments", "txn-42",
                        Instant.parse("2026-07-12T12:00:30Z"),
                        new RunEvidenceBundle.SideEffectProof(
                                "kms://receipts/42", "sha256:" + "a".repeat(64))),
                        "PROVIDER_STATUS_CONFIRMED", Instant.parse("2026-07-12T12:00:31Z"));
            }
        };
    }

    private static SideEffectReconciliationRequest request(Fixture fixture, String requestId) {
        return new SideEffectReconciliationRequest("", requestId,
                fixture.evidence().manifest().manifestHash(), fixture.attempt().attemptFingerprint());
    }

    private static Fixture fixture(boolean lookupRef) {
        return fixture(lookupRef, true);
    }

    private static Fixture fixture(boolean lookupRef, boolean structuredAttempt) {
        OperatorDefinition operator = new OperatorDefinition(
                "", "payments:charge", "1.0.0",
                new OperatorDefinition.Display("Charge", "", List.of("payments")),
                new OperatorDefinition.Source("user-library", "", "", "", false, "payments"),
                new OperatorDefinition.Ports(List.of(), List.of()), SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities("EXTERNAL_WRITE", "IDEMPOTENT", false, false, true),
                new OperatorDefinition.Lowering("native", "payments:charge", Map.of()), List.of());
        GraphDraft.DraftNode node = new GraphDraft.DraftNode(
                "charge", operator.operatorRef(), "Charge", Map.of(), Map.of(), new GraphDraft.Position(100, 100));
        GraphDraft draft = new GraphDraft(
                "", "draft-charge", 1, "chargeGraph", "tenant-a", "payments", "prod", "DRAFT",
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(node), List.of(), Map.of(), Map.of(),
                new GraphDraft.OutputSelection("charge", ""), Map.of("charge", operator.fingerprint()),
                Map.of("charge", operator), GraphDraft.RevisionMetadata.empty());
        VisualSideEffectAttempt visualAttempt = new VisualSideEffectAttempt(
                "attempt-42",
                new VisualSideEffectAttempt.Request(
                        "payments.charge", "sha256:" + "A".repeat(43), "payments.status",
                        lookupRef ? "vault://commands/charge-42" : "",
                        Instant.parse("2026-07-12T12:00:00Z"), 0),
                "UNKNOWN_COMMIT", null,
                List.of(
                        new VisualSideEffectAttempt.Transition(1, "PREPARED",
                                Instant.parse("2026-07-12T12:00:00Z"), "ATTEMPT_PREPARED", null),
                        new VisualSideEffectAttempt.Transition(2, "UNKNOWN_COMMIT",
                                Instant.parse("2026-07-12T12:00:10Z"), "REQUEST_TIMEOUT", null)));
        VisualNodeExecutionFact fact = new VisualNodeExecutionFact(
                "TIMEOUT", "NODE_TIMEOUT", "ENGINE_RESILIENCE_EVENT", List.of(),
                new VisualNodeExecutionFact.Retry(1, 1, false, "OperatorTimeoutException"),
                new VisualNodeExecutionFact.Timeout(true, 10000, true),
                new VisualNodeExecutionFact.Fallback(false, false, "NONE", ""),
                "UNKNOWN_COMMIT", structuredAttempt ? List.of(visualAttempt) : List.of(),
                List.of(new VisualNodeExecutionFact.Event(1, "TIMEOUT",
                        Instant.parse("2026-07-12T12:00:10Z"), 0, "OperatorTimeoutException")));
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, false, draft.graphName(), "charge", null, Map.of(),
                Map.of("charge", "FAILED"), 10000, Map.of("charge", 10000L),
                List.of(), List.of("charge timed out"), null, null, "graph chargeGraph {}",
                new VisualValidationResult(true, List.of()), "",
                Map.of("charge", List.of(new VisualNodeExecutionAttempt(
                        0, Map.of("amount", 42), null, "FAILED",
                        Instant.parse("2026-07-12T12:00:00Z"), 10000,
                        "OperatorTimeoutException", "timed out"))), Map.of("charge", fact));
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord run = runs.create(VisualGraphRunRecord.storedDraft(
                draft, Map.of("amount", 42, "idempotencyKey", "raw-idempotency-secret"), response));
        RunEvidenceBundle evidence = RunEvidenceBundle.from(run, runs.evidenceSigner());
        RunEvidenceBundle.SideEffectAttempt attempt = structuredAttempt
                ? evidence.nodes().getFirst().sideEffectAttempts().getFirst() : null;
        return new Fixture(runs, run, evidence, attempt);
    }

    private static IntegrationRequestContext reconciliationContext() {
        return new IntegrationRequestContext(
                "tenant-a", "payments", "tool-studio", "prod", "ap-southeast-1",
                "WORKLOAD", "reconciliation-worker", "", "SIDE_EFFECT_RECONCILIATION", "corr-reconcile");
    }

    private static IntegrationRequestContext evidenceContext() {
        return new IntegrationRequestContext(
                "tenant-a", "payments", "tool-studio", "prod", "ap-southeast-1",
                "WORKLOAD", "aneke", "", "GOVERNANCE_EVIDENCE_INGESTION", "corr-evidence");
    }

    private static IntegrationRequestContext otherTenantContext() {
        return new IntegrationRequestContext(
                "tenant-b", "payments", "tool-studio", "prod", "ap-southeast-1",
                "WORKLOAD", "aneke", "", "GOVERNANCE_EVIDENCE_INGESTION", "corr-other");
    }

    private static IntegrationProblem problem(Throwable error) {
        return ((IntegrationProblemException) error).problem();
    }

    private record Fixture(InMemoryVisualGraphRunRepository runs,
                           VisualGraphRunRecord run,
                           RunEvidenceBundle evidence,
                           RunEvidenceBundle.SideEffectAttempt attempt) {
    }
}

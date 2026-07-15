package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fail-closed anti-entropy service for suite runs abandoned by a crashed runtime process.
 *
 * <p>The service never re-executes a case because doing so could duplicate external effects. It
 * preserves every completed child reference, marks pending cases evidence-incomplete, blocks
 * promotion, and relies on the repository's status/version/lease compare-and-set for concurrency.</p>
 */
public final class TestSuiteRunReconciliationService {
    /** Stable evidence diagnostic emitted by lease-expiry terminalization. */
    public static final String ABANDONED_RUN_RECONCILED = "ABANDONED_RUN_RECONCILED";

    private final TestSuiteRunRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Creates the production service using persistence-authoritative time.
     *
     * @param repository durable checkpoint, lease, and reconciliation boundary
     * @param objectMapper canonical evidence fingerprint serializer
     */
    public TestSuiteRunReconciliationService(TestSuiteRunRepository repository,
                                             ObjectMapper objectMapper) {
        this(repository, objectMapper, null);
    }

    TestSuiteRunReconciliationService(TestSuiteRunRepository repository,
                                      ObjectMapper objectMapper, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = clock;
    }

    /**
     * Reconciles a bounded oldest-first batch and continues after isolated candidate failures.
     * Repository-wide scan failure escapes so the scheduler can report it and retry.
     *
     * @param limit requested candidate bound, normalized to 1-1000
     * @return payload-free sweep counters and successfully reconciled run ids
     */
    public TestSuiteRunReconciliationResult reconcileExpired(int limit) {
        Instant sweptAt = clock == null ? repository.currentTime() : clock.instant();
        int boundedLimit = Math.max(1, Math.min(limit, 1000));
        List<AbandonedTestSuiteRun> candidates = repository.findAbandoned(sweptAt, boundedLimit);
        int reconciled = 0;
        int raced = 0;
        int failed = 0;
        List<String> reconciledIds = new ArrayList<>();
        for (AbandonedTestSuiteRun candidate : candidates) {
            try {
                TestSuiteRunRecord terminal = terminal(candidate, sweptAt);
                if (repository.reconcileAbandoned(candidate, terminal, sweptAt)) {
                    reconciled++;
                    reconciledIds.add(terminal.suiteRunId());
                } else {
                    raced++;
                }
            } catch (RuntimeException failure) {
                failed++;
            }
        }
        return new TestSuiteRunReconciliationResult("", sweptAt, candidates.size(), reconciled,
                raced, failed, reconciledIds);
    }

    private TestSuiteRunRecord terminal(AbandonedTestSuiteRun abandoned, Instant completedAt) {
        TestSuiteRunRecord record = abandoned.record();
        TestSuiteRunEvidence previous = record.evidence();
        List<TestSuiteRunEvidence.CaseResult> cases = previous.caseResults().stream()
                .map(this::terminalCase).toList();
        int completedCases = (int) cases.stream().filter(this::hasCompletedChildEvidence).count();
        List<TestSuite.CaseType> observedTypes = cases.stream()
                .filter(this::hasCompletedChildEvidence)
                .map(TestSuiteRunEvidence.CaseResult::caseType)
                .filter(Objects::nonNull).distinct().toList();
        TestSuiteRunEvidence.CoverageVerdict oldCoverage = previous.coverage();
        TestSuiteRunEvidence.CoverageVerdict coverage = new TestSuiteRunEvidence.CoverageVerdict(
                TestSuiteRunEvidence.CoverageStatus.INCOMPLETE,
                oldCoverage.minimumCases(), completedCases, oldCoverage.requiredCaseTypes(), observedTypes,
                oldCoverage.missingCaseTypes(), oldCoverage.requiredInvocationSiteIds(),
                oldCoverage.observedInvocationSiteIds(), oldCoverage.missingInvocationSiteIds(),
                oldCoverage.requiredEdgeTransfers(), oldCoverage.observedEdgeTransfers(),
                oldCoverage.missingEdgeTransfers(), oldCoverage.minimumAssertionsPerCase(),
                oldCoverage.assertionDensityViolations(), oldCoverage.fixtureConsumptionViolations(), false);
        TestSuiteRunEvidence.PromotionVerdict oldPromotion = previous.promotion();
        List<String> reasons = merged(oldPromotion.reasons(),
                List.of(ABANDONED_RUN_RECONCILED, "EVIDENCE_INCOMPLETE"));
        TestSuiteRunEvidence.PromotionVerdict promotion = new TestSuiteRunEvidence.PromotionVerdict(
                TestSuiteRunEvidence.PromotionStatus.BLOCKED, reasons, false,
                oldPromotion.certifiableCases(), oldPromotion.minimumCertifiableCases(),
                oldPromotion.targetCertificationEligible(), false, false);
        Map<String, Object> metadata = new LinkedHashMap<>(previous.metadata());
        metadata.put("reconciliationMode", "LEASE_EXPIRY_TERMINALIZATION");
        metadata.put("expiredLeaseOwnerFingerprint", ProtocolFingerprint.of(
                objectMapper, Map.of("ownerId", abandoned.leaseOwner())));
        metadata.put("expiredLeaseAt", abandoned.leaseExpiresAt());
        metadata.put("expiredCheckpointVersion", abandoned.checkpointVersion());
        TestSuiteRunEvidence terminal = new TestSuiteRunEvidence("", previous.suiteRunId(),
                previous.clientRequestId(), TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE,
                previous.executionPurpose(), previous.suiteRef(), previous.target(), previous.startedAt(),
                completedAt, cases, coverage, promotion,
                merged(previous.diagnostics(), List.of(ABANDONED_RUN_RECONCILED)), metadata);
        String fingerprint = ProtocolFingerprint.of(objectMapper, terminal);
        return new TestSuiteRunRecord(record.suiteRunId(), record.clientRequestId(),
                record.requestFingerprint(), record.tenantId(), record.organizationId(), record.projectId(),
                record.environmentId(), record.actorId(), record.classification(), fingerprint, terminal,
                record.createdAt(), record.expiresAt());
    }

    private TestSuiteRunEvidence.CaseResult terminalCase(TestSuiteRunEvidence.CaseResult result) {
        if (result.status() != TestSuiteRunEvidence.CaseStatus.PENDING) {
            return result;
        }
        return new TestSuiteRunEvidence.CaseResult(result.caseId(), result.caseType(),
                result.fixtureBundleRef(), TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE,
                "", null, null, 0, 0, ABANDONED_RUN_RECONCILED,
                "The owning runtime stopped renewing its lease before this case produced evidence.");
    }

    private boolean hasCompletedChildEvidence(TestSuiteRunEvidence.CaseResult result) {
        return result.status() == TestSuiteRunEvidence.CaseStatus.PASSED
                || result.status() == TestSuiteRunEvidence.CaseStatus.FAILED;
    }

    private static List<String> merged(List<String> current, List<String> added) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (current != null) {
            values.addAll(current);
        }
        values.addAll(added);
        return List.copyOf(values);
    }
}

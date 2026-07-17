package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoverageVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestPropertySuiteEvidenceEvaluator;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

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
 * promotion, and relies on the repository's status/version/lease compare-and-set for concurrency.
 * Schema-admission checkpoints retain their exact typed validator observations and empty business
 * child closure while unfinished admission cases become explicitly incomplete. Property
 * checkpoints retain completed root/shrink outcomes and terminalize only pending coordinates;
 * they never regenerate input or repeat a potentially effectful child invocation.</p>
 */
public final class TestSuiteRunReconciliationService {
    /** Stable evidence diagnostic emitted by lease-expiry terminalization. */
    public static final String ABANDONED_RUN_RECONCILED = "ABANDONED_RUN_RECONCILED";

    private final TestSuiteRunRepository repository;
    private final ObjectMapper objectMapper;
    private final TestSuiteRunAttestationService attestations;
    private final TestSchemaAdmissionEvaluator schemaAdmissions;
    private final TestPropertySuiteEvidenceEvaluator propertyEvidence;
    private final Clock clock;

    /**
     * Creates the production service using persistence-authoritative time.
     *
     * @param repository durable checkpoint, lease, and reconciliation boundary
     * @param objectMapper canonical evidence fingerprint serializer
     */
    public TestSuiteRunReconciliationService(TestSuiteRunRepository repository,
                                             ObjectMapper objectMapper) {
        this(repository, objectMapper,
                new TestSuiteRunAttestationService(objectMapper, VisualEvidenceSigner.unavailable()), null);
    }

    /**
     * Creates the production service with an explicit aggregate signing authority.
     *
     * @param repository durable checkpoint, lease, and reconciliation boundary
     * @param objectMapper canonical evidence fingerprint serializer
     * @param attestations checkpoint and terminal signing boundary
     */
    public TestSuiteRunReconciliationService(TestSuiteRunRepository repository,
                                             ObjectMapper objectMapper,
                                             TestSuiteRunAttestationService attestations) {
        this(repository, objectMapper, attestations, null);
    }

    TestSuiteRunReconciliationService(TestSuiteRunRepository repository,
                                      ObjectMapper objectMapper, Clock clock) {
        this(repository, objectMapper,
                new TestSuiteRunAttestationService(objectMapper, VisualEvidenceSigner.unavailable()), clock);
    }

    TestSuiteRunReconciliationService(TestSuiteRunRepository repository,
                                      ObjectMapper objectMapper,
                                      TestSuiteRunAttestationService attestations,
                                      Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.attestations = Objects.requireNonNull(attestations, "attestations");
        this.schemaAdmissions = new TestSchemaAdmissionEvaluator(objectMapper);
        this.propertyEvidence = new TestPropertySuiteEvidenceEvaluator();
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
        TestSuiteRunEvidenceProtocol previous = record.evidence();
        requireTrustedCheckpoint(record);
        List<TestSuiteRunEvidence.CaseResult> cases = previous.caseResults().stream()
                .map(this::terminalCase).toList();
        Map<String, Object> metadata = new LinkedHashMap<>(previous.metadata());
        metadata.put("reconciliationMode", "LEASE_EXPIRY_TERMINALIZATION");
        metadata.put("expiredLeaseOwnerFingerprint", ProtocolFingerprint.of(
                objectMapper, Map.of("ownerId", abandoned.leaseOwner())));
        metadata.put("expiredLeaseAt", abandoned.leaseExpiresAt());
        metadata.put("expiredCheckpointVersion", abandoned.checkpointVersion());
        TestSuiteRunEvidenceProtocol terminal;
        if (previous instanceof TestSuiteRunEvidenceV3 admission) {
            terminal = reconciledAdmissionEvidence(admission, completedAt, cases, metadata);
        } else {
            int completedCases = (int) cases.stream()
                    .filter(this::hasCompletedChildEvidence).count();
            List<TestSuite.CaseType> observedTypes = cases.stream()
                    .filter(this::hasCompletedChildEvidence)
                    .map(TestSuiteRunEvidence.CaseResult::caseType)
                    .filter(Objects::nonNull).distinct().toList();
            TestSuiteRunEvidence.CoverageVerdict oldCoverage = previous.coverage();
            TestSuiteRunEvidence.CoverageVerdict coverage =
                    new TestSuiteRunEvidence.CoverageVerdict(
                            TestSuiteRunEvidence.CoverageStatus.INCOMPLETE,
                            oldCoverage.minimumCases(), completedCases,
                            oldCoverage.requiredCaseTypes(), observedTypes,
                            oldCoverage.missingCaseTypes(), oldCoverage.requiredInvocationSiteIds(),
                            oldCoverage.observedInvocationSiteIds(),
                            oldCoverage.missingInvocationSiteIds(),
                            oldCoverage.requiredEdgeTransfers(), oldCoverage.observedEdgeTransfers(),
                            oldCoverage.missingEdgeTransfers(),
                            oldCoverage.minimumAssertionsPerCase(),
                            oldCoverage.assertionDensityViolations(),
                            oldCoverage.fixtureConsumptionViolations(), false);
            TestSuiteRunEvidence.PromotionVerdict oldPromotion = previous.promotion();
            List<String> reasons = merged(oldPromotion.reasons(),
                    List.of(ABANDONED_RUN_RECONCILED, "EVIDENCE_INCOMPLETE"));
            TestSuiteRunEvidence.PromotionVerdict promotion =
                    new TestSuiteRunEvidence.PromotionVerdict(
                            TestSuiteRunEvidence.PromotionStatus.BLOCKED, reasons, false,
                            oldPromotion.certifiableCases(),
                            oldPromotion.minimumCertifiableCases(),
                            oldPromotion.targetCertificationEligible(), false, false);
            terminal = reconciledEvidence(
                    previous, completedAt, cases, coverage, promotion, metadata);
        }
        TestSuiteRunAttestationService.SealResult seal = attestations.seal(terminal,
                record.requestFingerprint(), record.attestation().childEvidenceRefs(),
                TestSuiteRunAttestation.Scope.TERMINAL);
        if (!seal.verified()) {
            throw new IllegalStateException("Reconciled terminal evidence could not be signed: "
                    + seal.failureCode());
        }
        String fingerprint = seal.attestation().aggregateEvidenceFingerprint();
        return new TestSuiteRunRecord(record.suiteRunId(), record.clientRequestId(),
                record.requestFingerprint(), record.tenantId(), record.organizationId(), record.projectId(),
                record.environmentId(), record.actorId(), record.classification(), fingerprint, terminal,
                seal.attestation(),
                record.createdAt(), record.expiresAt());
    }

    /** Builds a same-generation v3 terminal without entering business-child coverage logic. */
    private TestSuiteRunEvidenceV3 reconciledAdmissionEvidence(
            TestSuiteRunEvidenceV3 previous,
            Instant completedAt,
            List<TestSuiteRunEvidence.CaseResult> cases,
            Map<String, Object> metadata) {
        List<TestSuiteRunEvidenceV3.AdmissionCaseResult> admissionResults =
                previous.admissionResults().stream()
                        .map(TestSuiteRunReconciliationService::terminalAdmissionCase)
                        .toList();
        TestSuiteRunEvidenceV3.AdmissionCoverageVerdict derivedCoverage =
                schemaAdmissions.coverage(admissionResults);
        TestSuiteRunEvidenceV3.AdmissionCoverageVerdict admissionCoverage =
                new TestSuiteRunEvidenceV3.AdmissionCoverageVerdict(
                        TestSuiteRunEvidenceV3.AdmissionCoverageStatus.INCOMPLETE,
                        derivedCoverage.requiredCases(), derivedCoverage.evaluatedCases(),
                        derivedCoverage.matchedCases(),
                        derivedCoverage.expectationMismatchCaseIds(),
                        derivedCoverage.provenanceMismatchCaseIds(),
                        derivedCoverage.incompleteCaseIds(),
                        derivedCoverage.allCasesCompleted());
        List<String> reasons = merged(previous.promotion().reasons(),
                List.of(ABANDONED_RUN_RECONCILED, "EVIDENCE_INCOMPLETE"));
        TestSuiteRunEvidence.PromotionVerdict admissionPromotion =
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.BLOCKED, reasons,
                        false, 0, 0, false, false,
                        admissionCoverage.allCasesCompleted());
        List<String> diagnostics = merged(
                previous.diagnostics(), List.of(ABANDONED_RUN_RECONCILED));
        return new TestSuiteRunEvidenceV3("", previous.suiteRunId(),
                previous.clientRequestId(), TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE,
                previous.executionPurpose(), previous.suiteRef(), previous.target(),
                previous.startedAt(), completedAt, cases,
                TestSuiteRunEvidence.CoverageVerdict.notEvaluated(), admissionPromotion,
                previous.evaluationMode(), previous.boundaryPlanFingerprint(),
                previous.inputSchemaFingerprint(), previous.generatorVersion(),
                previous.verificationMode(), previous.sourcePlanStatus(),
                previous.sourceCoverageGapCount(), previous.coverageGapsAccepted(),
                admissionResults, admissionCoverage, diagnostics, metadata);
    }

    private TestSuiteRunEvidenceProtocol reconciledEvidence(
            TestSuiteRunEvidenceProtocol previous, Instant completedAt,
            List<TestSuiteRunEvidence.CaseResult> cases,
            TestSuiteRunEvidence.CoverageVerdict coverage,
            TestSuiteRunEvidence.PromotionVerdict promotion, Map<String, Object> metadata) {
        List<String> diagnostics = merged(previous.diagnostics(), List.of(ABANDONED_RUN_RECONCILED));
        if (previous instanceof TestSuiteRunEvidenceV4 v4) {
            java.util.Set<String> unfinished = previous.caseResults().stream()
                    .filter(result -> result.status() == TestSuiteRunEvidence.CaseStatus.PENDING)
                    .map(TestSuiteRunEvidence.CaseResult::caseId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            TestPropertySuiteEvidenceEvaluator.Evaluation property =
                    propertyEvidence.markIncomplete(v4.propertyTrialResults(), unfinished,
                            ABANDONED_RUN_RECONCILED);
            return new TestSuiteRunEvidenceV4("", previous.suiteRunId(),
                    previous.clientRequestId(), TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE,
                    previous.executionPurpose(), previous.suiteRef(), previous.target(),
                    previous.startedAt(), completedAt, cases, coverage, promotion,
                    v4.evaluationMode(), v4.quantification(), v4.exhaustive(),
                    v4.propertyPlanFingerprint(), v4.inputSchemaFingerprint(),
                    v4.generationPolicy(), v4.sourcePlanStatus(), v4.generationGapsAccepted(),
                    v4.generationGaps(), property.trialResults(), property.coverage(), diagnostics,
                    metadata);
        }
        if (previous instanceof TestSuiteRunEvidenceV2 v2) {
            List<String> observed = v2.semanticCoverage().observed().stream()
                    .map(SemanticCoverageVerdict.Observation::requirementId).toList();
            List<SemanticCoverageVerdict.Unavailable> unavailable = new ArrayList<>(
                    v2.semanticCoverage().unavailable());
            v2.semanticCoverage().required().stream()
                    .map(requirement -> requirement.requirementId())
                    .filter(requirementId -> !observed.contains(requirementId))
                    .filter(requirementId -> unavailable.stream().noneMatch(item ->
                            requirementId.equals(item.requirementId())))
                    .map(requirementId -> new SemanticCoverageVerdict.Unavailable(
                            requirementId, "SEMANTIC_EVIDENCE_INCOMPLETE"))
                    .forEach(unavailable::add);
            SemanticCoverageVerdict semanticCoverage = new SemanticCoverageVerdict(
                    SemanticCoverageVerdict.Status.INCOMPLETE, v2.semanticCoverage().required(),
                    v2.semanticCoverage().observed(), List.of(), unavailable);
            return new TestSuiteRunEvidenceV2("", previous.suiteRunId(), previous.clientRequestId(),
                    TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE, previous.executionPurpose(),
                    previous.suiteRef(), previous.target(), previous.startedAt(), completedAt, cases,
                    coverage, semanticCoverage, promotion, diagnostics, metadata);
        }
        return new TestSuiteRunEvidence("", previous.suiteRunId(), previous.clientRequestId(),
                TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE, previous.executionPurpose(),
                previous.suiteRef(), previous.target(), previous.startedAt(), completedAt, cases,
                coverage, promotion, diagnostics, metadata);
    }

    /**
     * Converts only an admission case that never reached the validator into an explicit recovery
     * gap. Completed validator observations remain byte-for-byte stable evidence facts.
     */
    private static TestSuiteRunEvidenceV3.AdmissionCaseResult terminalAdmissionCase(
            TestSuiteRunEvidenceV3.AdmissionCaseResult result) {
        if (result.status() != TestSuiteRunEvidenceV3.AdmissionCaseStatus.PENDING) {
            return result;
        }
        return new TestSuiteRunEvidenceV3.AdmissionCaseResult(
                result.caseId(), TestSuiteRunEvidenceV3.AdmissionCaseStatus.EVIDENCE_INCOMPLETE,
                result.expectedOutcome(), null, result.expectedValidationCodes(), List.of(),
                ABANDONED_RUN_RECONCILED);
    }

    private void requireTrustedCheckpoint(TestSuiteRunRecord record) {
        if (record.evidence() == null
                || record.evidence().status() != TestSuiteRunEvidence.Status.RUNNING
                || record.attestation().scope() != TestSuiteRunAttestation.Scope.CHECKPOINT
                || !record.requestFingerprint().equals(record.attestation().requestFingerprint())
                || attestations.verify(record.evidence(), record.attestation())
                != TestSuiteRunAttestationService.Verification.VERIFIED
                || !closureMatches(record)) {
            throw new IllegalStateException("Abandoned suite checkpoint failed integrity verification");
        }
    }

    private static boolean closureMatches(TestSuiteRunRecord record) {
        List<TestSuiteRunAttestation.ChildEvidenceRef> children = record.attestation().childEvidenceRefs();
        int childIndex = 0;
        for (TestSuiteRunEvidence.CaseResult result : record.evidence().caseResults()) {
            if (result.runId().isBlank()) {
                continue;
            }
            if (childIndex >= children.size()) {
                return false;
            }
            TestSuiteRunAttestation.ChildEvidenceRef child = children.get(childIndex++);
            if (!result.caseId().equals(child.caseId()) || !result.runId().equals(child.runId())) {
                return false;
            }
        }
        return childIndex == children.size();
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

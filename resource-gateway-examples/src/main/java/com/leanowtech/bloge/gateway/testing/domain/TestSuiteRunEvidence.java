package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Durable aggregate evidence for one execution of an immutable test-suite revision.
 *
 * <p>The aggregate intentionally references child run identifiers instead of copying node payloads.
 * Coverage and promotion facts are server-derived from the sanitized child evidence. An
 * {@link PromotionStatus#ELIGIBLE} verdict means the suite policy was satisfied; it is not a
 * certification signature, approval, or publication decision.</p>
 *
 * @param schemaVersion suite-run evidence protocol version
 * @param suiteRunId server-minted aggregate run identifier
 * @param clientRequestId caller idempotency key
 * @param status aggregate execution status
 * @param executionPurpose fixed server-authorized suite execution purpose
 * @param suiteRef exact suite revision executed
 * @param target exact graph or operator target frozen by the suite
 * @param startedAt authoritative suite-run start time
 * @param completedAt terminal time; absent while running
 * @param caseResults ordered case outcomes and child run references
 * @param coverage server-derived orchestration coverage verdict
 * @param promotion server-derived policy eligibility verdict
 * @param diagnostics bounded stable aggregate diagnostics
 * @param metadata bounded scope and caller provenance
 */
public record TestSuiteRunEvidence(
        String schemaVersion,
        String suiteRunId,
        String clientRequestId,
        Status status,
        String executionPurpose,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        TestSuite.Target target,
        Instant startedAt,
        Instant completedAt,
        List<CaseResult> caseResults,
        CoverageVerdict coverage,
        PromotionVerdict promotion,
        List<String> diagnostics,
        Map<String, Object> metadata
) implements TestSuiteRunEvidenceProtocol {
    /** Current aggregate evidence protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteRunEvidence.v1";

    /** Aggregate lifecycle and terminal outcomes. */
    public enum Status {
        RUNNING,
        PASSED,
        COMPLETED_WITH_FAILURES,
        PARTIAL,
        EVIDENCE_INCOMPLETE
    }

    /** Per-case scheduling and terminal outcomes. */
    public enum CaseStatus {
        PENDING,
        PASSED,
        FAILED,
        NOT_SCHEDULED,
        EVIDENCE_INCOMPLETE
    }

    /** Coverage evaluation state. */
    public enum CoverageStatus {
        NOT_EVALUATED,
        SATISFIED,
        UNSATISFIED,
        INCOMPLETE
    }

    /** Promotion-policy eligibility state. */
    public enum PromotionStatus {
        NOT_EVALUATED,
        ELIGIBLE,
        BLOCKED
    }

    /** Normalizes aggregate fields and freezes every collection. */
    public TestSuiteRunEvidence {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        suiteRunId = trimmed(suiteRunId);
        clientRequestId = trimmed(clientRequestId);
        status = status == null ? Status.EVIDENCE_INCOMPLETE : status;
        executionPurpose = trimmed(executionPurpose);
        caseResults = immutableList(caseResults);
        coverage = coverage == null ? CoverageVerdict.notEvaluated() : coverage;
        promotion = promotion == null ? PromotionVerdict.notEvaluated() : promotion;
        diagnostics = immutableList(diagnostics);
        metadata = immutableMap(metadata);
    }

    /**
     * One suite case outcome linked to its independently persisted child evidence.
     *
     * @param caseId suite-local stable case id
     * @param caseType declared case intent
     * @param fixtureBundleRef exact governed fixture dependency
     * @param status case scheduling or terminal status
     * @param runId child test-run id; absent when no child run was produced
     * @param evidenceStatus child evidence terminal status
     * @param evidenceClass child evidence trust class
     * @param assertionsEvaluated number of assertions evaluated by the child run
     * @param assertionsPassed number of passing assertions
     * @param diagnosticCode stable failure or scheduling code
     * @param diagnostic bounded human diagnostic without raw payloads
     */
    public record CaseResult(
            String caseId,
            TestSuite.CaseType caseType,
            TestSuite.FixtureBundleRef fixtureBundleRef,
            CaseStatus status,
            String runId,
            TestRunEvidence.Status evidenceStatus,
            TestRunEvidence.EvidenceClass evidenceClass,
            int assertionsEvaluated,
            int assertionsPassed,
            String diagnosticCode,
            String diagnostic
    ) {
        /** Normalizes case labels and rejects negative aggregate counters. */
        public CaseResult {
            caseId = trimmed(caseId);
            status = status == null ? CaseStatus.EVIDENCE_INCOMPLETE : status;
            runId = trimmed(runId);
            diagnosticCode = trimmed(diagnosticCode);
            diagnostic = diagnostic == null ? "" : diagnostic;
            if (assertionsEvaluated < 0 || assertionsPassed < 0
                    || assertionsPassed > assertionsEvaluated) {
                throw new IllegalArgumentException("Invalid suite case assertion counters");
            }
        }
    }

    /**
     * Aggregate semantic coverage computed from child evidence.
     *
     * @param status coverage state
     * @param minimumCases required completed-case minimum
     * @param completedCases cases that produced terminal child evidence
     * @param requiredCaseTypes required case intents
     * @param observedCaseTypes intents represented by completed evidence
     * @param missingCaseTypes required intents without completed evidence
     * @param requiredInvocationSiteIds required structural sites
     * @param observedInvocationSiteIds observed non-skipped structural sites
     * @param missingInvocationSiteIds required sites absent from evidence
     * @param requiredEdgeTransfers required structural edge endpoint pairs
     * @param observedEdgeTransfers transferred structural endpoint pairs
     * @param missingEdgeTransfers required transfers absent from evidence
     * @param minimumAssertionsPerCase required assertion density
     * @param assertionDensityViolations case ids below assertion density
     * @param fixtureConsumptionViolations case ids with unsatisfied required fixture consumption
     * @param allCasesCompleted whether every suite case produced terminal child evidence
     */
    public record CoverageVerdict(
            CoverageStatus status,
            int minimumCases,
            int completedCases,
            List<TestSuite.CaseType> requiredCaseTypes,
            List<TestSuite.CaseType> observedCaseTypes,
            List<TestSuite.CaseType> missingCaseTypes,
            List<String> requiredInvocationSiteIds,
            List<String> observedInvocationSiteIds,
            List<String> missingInvocationSiteIds,
            List<TestSuite.EdgeTransferRef> requiredEdgeTransfers,
            List<TestSuite.EdgeTransferRef> observedEdgeTransfers,
            List<TestSuite.EdgeTransferRef> missingEdgeTransfers,
            int minimumAssertionsPerCase,
            List<String> assertionDensityViolations,
            List<String> fixtureConsumptionViolations,
            boolean allCasesCompleted
    ) {
        /** Canonicalizes set-like coverage facts for stable aggregate fingerprints. */
        public CoverageVerdict {
            status = status == null ? CoverageStatus.NOT_EVALUATED : status;
            requiredCaseTypes = sortedCaseTypes(requiredCaseTypes);
            observedCaseTypes = sortedCaseTypes(observedCaseTypes);
            missingCaseTypes = sortedCaseTypes(missingCaseTypes);
            requiredInvocationSiteIds = sortedStrings(requiredInvocationSiteIds);
            observedInvocationSiteIds = sortedStrings(observedInvocationSiteIds);
            missingInvocationSiteIds = sortedStrings(missingInvocationSiteIds);
            requiredEdgeTransfers = sortedEdges(requiredEdgeTransfers);
            observedEdgeTransfers = sortedEdges(observedEdgeTransfers);
            missingEdgeTransfers = sortedEdges(missingEdgeTransfers);
            assertionDensityViolations = sortedStrings(assertionDensityViolations);
            fixtureConsumptionViolations = sortedStrings(fixtureConsumptionViolations);
        }

        /** @return placeholder used while the suite is still executing */
        public static CoverageVerdict notEvaluated() {
            return new CoverageVerdict(CoverageStatus.NOT_EVALUATED, 0, 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), 0, List.of(), List.of(), false);
        }
    }

    /**
     * Server-owned promotion eligibility evaluation.
     *
     * @param status eligibility state
     * @param reasons stable fail-closed reason codes
     * @param allCasesPassed whether every completed case passed
     * @param certifiableCases cases that emitted certifiable child evidence
     * @param minimumCertifiableCases suite policy minimum
     * @param targetCertificationEligible current target eligibility at execution time
     * @param coverageSatisfied whether aggregate coverage is satisfied
     * @param allCasesCompleted whether all suite cases completed
     */
    public record PromotionVerdict(
            PromotionStatus status,
            List<String> reasons,
            boolean allCasesPassed,
            int certifiableCases,
            int minimumCertifiableCases,
            boolean targetCertificationEligible,
            boolean coverageSatisfied,
            boolean allCasesCompleted
    ) {
        /** Canonicalizes reason codes and validates counters. */
        public PromotionVerdict {
            status = status == null ? PromotionStatus.NOT_EVALUATED : status;
            reasons = sortedStrings(reasons);
            if (certifiableCases < 0 || minimumCertifiableCases < 0) {
                throw new IllegalArgumentException("Promotion case counters must be non-negative");
            }
        }

        /** @return placeholder used while the suite is still executing */
        public static PromotionVerdict notEvaluated() {
            return new PromotionVerdict(PromotionStatus.NOT_EVALUATED, List.of(), false,
                    0, 0, false, false, false);
        }
    }

    private static List<TestSuite.CaseType> sortedCaseTypes(List<TestSuite.CaseType> values) {
        if (values == null) {
            return List.of();
        }
        List<TestSuite.CaseType> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        sorted.sort(Comparator.comparing(Enum::name));
        return List.copyOf(sorted);
    }

    private static List<String> sortedStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static List<TestSuite.EdgeTransferRef> sortedEdges(List<TestSuite.EdgeTransferRef> values) {
        if (values == null) {
            return List.of();
        }
        List<TestSuite.EdgeTransferRef> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        sorted.sort(Comparator.comparing(TestSuite.EdgeTransferRef::fromInvocationSiteId)
                .thenComparing(TestSuite.EdgeTransferRef::toInvocationSiteId));
        return List.copyOf(sorted);
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        return values == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure evaluator for bounded reruns of one exact executable suite revision.
 *
 * <p>The caller must cryptographically verify source suite and child evidence before setting the
 * corresponding trust flags. This evaluator independently rechecks all structural identities and
 * canonical fingerprints. Effective-plan drift is evidence incompleteness rather than flakiness,
 * because executions under different plans are not equivalent experiments.</p>
 */
public final class TestSuiteStabilityEvidenceEvaluator {
    /** Stable reason used when a source suite run is absent or untrusted. */
    public static final String SOURCE_EVIDENCE_INVALID = "STABILITY_SOURCE_EVIDENCE_INVALID";
    /** Stable reason used when a child closure is absent, altered, or untrusted. */
    public static final String CHILD_EVIDENCE_INVALID = "STABILITY_CHILD_EVIDENCE_INVALID";
    /** Stable reason used when equivalent attempts did not retain one effective plan. */
    public static final String PLAN_DRIFT = "STABILITY_EFFECTIVE_PLAN_DRIFT";
    /** Stable reason used when one source suite run is reused as multiple attempts. */
    public static final String SOURCE_RUN_REUSED = "STABILITY_SOURCE_RUN_REUSED";
    /** Stable reason used when one child run is reused as multiple case observations. */
    public static final String CHILD_RUN_REUSED = "STABILITY_CHILD_RUN_REUSED";

    private final ObjectMapper objectMapper;
    private final TestSuiteRunEvidenceProtocolCodec suiteEvidenceCodec;

    /**
     * @param objectMapper canonical protocol mapper used for independent fingerprint checks
     */
    public TestSuiteStabilityEvidenceEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.suiteEvidenceCodec = new TestSuiteRunEvidenceProtocolCodec(objectMapper);
    }

    /**
     * Evaluates an exact bounded attempt closure.
     *
     * @param suite immutable executable suite
     * @param suiteRef exact content-addressed suite reference
     * @param stabilityRunId deterministic scope-and-request identity
     * @param clientRequestId caller idempotency key
     * @param requestedAttempts bounded requested attempts
     * @param attemptObservations source suite and child observations; missing coordinates are
     *                            materialized as inconclusive
     * @param metadata bounded caller provenance copied without payloads
     * @return immutable, internally re-derived stability evidence
     */
    public TestSuiteStabilityEvidence evaluate(
            TestSuiteProtocol suite,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            String stabilityRunId,
            String clientRequestId,
            int requestedAttempts,
            List<AttemptObservation> attemptObservations,
            Map<String, Object> metadata) {
        requireSupportedSuite(suite, suiteRef);
        if (requestedAttempts < TestSuiteStabilityEvidence.MIN_ATTEMPTS
                || requestedAttempts > TestSuiteStabilityEvidence.MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Stability attempt count is outside protocol bounds");
        }

        List<AttemptObservation> ordered = orderedAttempts(
                requestedAttempts, attemptObservations);
        List<SourceEvaluation> sources = ordered.stream()
                .map(source -> evaluateSource(suite, suiteRef, source))
                .toList();
        Set<Integer> reusedSourceAttempts = reusedSourceAttempts(sources);

        List<List<TestSuiteStabilityEvidence.CaseObservation>> byCase = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < suite.cases().size(); caseIndex++) {
            List<TestSuiteStabilityEvidence.CaseObservation> observations = new ArrayList<>();
            for (SourceEvaluation source : sources) {
                TestSuiteStabilityEvidence.CaseObservation observation =
                        source.caseObservations().get(caseIndex);
                observations.add(reusedSourceAttempts.contains(source.attempt())
                        ? incomplete(source.attempt(), SOURCE_RUN_REUSED) : observation);
            }
            byCase.add(List.copyOf(observations));
        }
        byCase = closeReusedChildRuns(byCase, requestedAttempts);
        byCase = byCase.stream().map(
                TestSuiteStabilityEvidenceEvaluator::closePlanDrift).toList();

        List<TestSuiteStabilityEvidence.CaseStabilityResult> caseResults = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < suite.cases().size(); caseIndex++) {
            TestSuite.TestCase testCase = suite.cases().get(caseIndex);
            List<TestSuiteStabilityEvidence.CaseObservation> observations = byCase.get(caseIndex);
            Set<String> outcomes = new LinkedHashSet<>();
            List<String> diagnostics = new ArrayList<>();
            for (TestSuiteStabilityEvidence.CaseObservation observation : observations) {
                if (observation.status()
                        == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED) {
                    outcomes.add(observation.outcomeIdentity());
                } else if (!observation.diagnosticCode().isBlank()) {
                    diagnostics.add(observation.diagnosticCode());
                }
            }
            TestSuiteStabilityEvidence.CaseStatus status = caseStatus(observations, outcomes);
            caseResults.add(new TestSuiteStabilityEvidence.CaseStabilityResult(
                    testCase.caseId(), testCase.caseType(), testCase.fixtureBundleRef(), status,
                    observations, outcomes.size(), diagnostics));
        }

        List<TestSuiteStabilityEvidence.AttemptResult> attempts = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (int attemptIndex = 0; attemptIndex < sources.size(); attemptIndex++) {
            SourceEvaluation source = sources.get(attemptIndex);
            boolean childrenVerified = true;
            List<String> attemptDiagnostics = new ArrayList<>();
            for (List<TestSuiteStabilityEvidence.CaseObservation> observations : byCase) {
                TestSuiteStabilityEvidence.CaseObservation observation =
                        observations.get(attemptIndex);
                if (observation.status()
                        != TestSuiteStabilityEvidence.ObservationStatus.VERIFIED) {
                    childrenVerified = false;
                    if (!observation.diagnosticCode().isBlank()) {
                        attemptDiagnostics.add(observation.diagnosticCode());
                    }
                }
            }
            boolean verified = source.sourceVerified() && childrenVerified;
            String diagnostic = verified ? "" : firstDiagnostic(
                    attemptDiagnostics, source.diagnosticCode());
            diagnostics.addAll(attemptDiagnostics);
            if (!source.diagnosticCode().isBlank()) {
                diagnostics.add(source.diagnosticCode());
            }
            attempts.add(new TestSuiteStabilityEvidence.AttemptResult(
                    source.attempt(), verified
                    ? TestSuiteStabilityEvidence.AttemptStatus.VERIFIED
                    : TestSuiteStabilityEvidence.AttemptStatus.INCONCLUSIVE,
                    source.suiteRunId(), source.aggregateEvidenceFingerprint(),
                    source.suiteStatus(), source.sourcePromotionStatus(),
                    source.sourcePromotionReasons(), source.startedAt(), source.completedAt(),
                    diagnostic));
        }

        TestSuiteStabilityEvidence.Status aggregateStatus = aggregateStatus(caseResults);
        TestSuiteStabilityEvidence.PromotionVerdict promotion =
                TestSuiteStabilityEvidence.derivePromotion(attempts, caseResults, aggregateStatus);
        TestSuiteStabilityEvidence.QuarantineVerdict quarantine =
                TestSuiteStabilityEvidence.deriveQuarantine(caseResults, aggregateStatus);
        Instant startedAt = sources.stream().map(SourceEvaluation::startedAt)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        Instant completedAt = sources.stream().map(SourceEvaluation::completedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(startedAt);
        return new TestSuiteStabilityEvidence("", stabilityRunId, clientRequestId, suiteRef,
                suite.target(), requestedAttempts, aggregateStatus, attempts, caseResults,
                promotion, quarantine, startedAt, completedAt, diagnostics, metadata);
    }

    private SourceEvaluation evaluateSource(
            TestSuiteProtocol suite,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            AttemptObservation source) {
        TestSuiteExecutionResponse response = source.suiteExecution();
        Instant observedAt = source.observedAt();
        if (!validSuiteSource(suite, suiteRef, source)) {
            return incompleteSource(suite, source.attempt(), response, observedAt,
                    source.diagnosticCode().isBlank()
                            ? SOURCE_EVIDENCE_INVALID : source.diagnosticCode());
        }

        TestSuiteRunEvidenceProtocol evidence = response.evidence();
        Map<String, TestSuiteRunAttestation.ChildEvidenceRef> childRefs = new LinkedHashMap<>();
        Set<String> childRunIds = new LinkedHashSet<>();
        for (TestSuiteRunAttestation.ChildEvidenceRef child
                : response.attestation().childEvidenceRefs()) {
            if (childRefs.putIfAbsent(child.caseId(), child) != null
                    || !childRunIds.add(child.runId())) {
                return incompleteSource(suite, source.attempt(), response, observedAt,
                        SOURCE_EVIDENCE_INVALID);
            }
        }
        List<TestSuiteStabilityEvidence.CaseObservation> cases = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < suite.cases().size(); caseIndex++) {
            cases.add(evaluateCase(source.attempt(), suite,
                    suite.cases().get(caseIndex), evidence.caseResults().get(caseIndex),
                    childRefs.get(suite.cases().get(caseIndex).caseId()), source.childrenByRunId()));
        }
        return new SourceEvaluation(source.attempt(), true, response.suiteRunId(),
                response.evidenceFingerprint(), evidence.status(), evidence.promotion().status(),
                evidence.promotion().reasons(), evidence.startedAt(), evidence.completedAt(), "",
                List.copyOf(cases));
    }

    private TestSuiteStabilityEvidence.CaseObservation evaluateCase(
            int attempt,
            TestSuiteProtocol suite,
            TestSuite.TestCase testCase,
            TestSuiteRunEvidence.CaseResult result,
            TestSuiteRunAttestation.ChildEvidenceRef childRef,
            Map<String, ChildObservation> children) {
        if (!testCase.caseId().equals(result.caseId())
                || testCase.caseType() != result.caseType()
                || !Objects.equals(testCase.fixtureBundleRef(), result.fixtureBundleRef())
                || !List.of(TestSuiteRunEvidence.CaseStatus.PASSED,
                TestSuiteRunEvidence.CaseStatus.FAILED).contains(result.status())
                || result.runId().isBlank() || childRef == null
                || !testCase.caseId().equals(childRef.caseId())
                || !result.runId().equals(childRef.runId())) {
            return incomplete(attempt, CHILD_EVIDENCE_INVALID);
        }
        ChildObservation child = children.get(result.runId());
        if (!validChild(suite, testCase, result, childRef, child)) {
            return incomplete(attempt, CHILD_EVIDENCE_INVALID);
        }
        TestRunEvidence evidence = child.execution().evidence();
        return new TestSuiteStabilityEvidence.CaseObservation(attempt,
                TestSuiteStabilityEvidence.ObservationStatus.VERIFIED,
                evidence.runId(), childRef.evidenceFingerprint(), evidence.status(),
                evidence.evidenceClass(), evidence.fixtureBundleFingerprint(),
                evidence.planFingerprint(), evidence.semanticResultFingerprint(), "");
    }

    private boolean validSuiteSource(
            TestSuiteProtocol suite,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            AttemptObservation source) {
        TestSuiteExecutionResponse response = source.suiteExecution();
        if (!source.suiteEvidenceVerified() || response == null || response.evidence() == null
                || response.attestation() == null || response.suiteRunId().isBlank()
                || !response.suiteRunId().equals(response.evidence().suiteRunId())
                || !Objects.equals(suiteRef, response.evidence().suiteRef())
                || !Objects.equals(suite.target(), response.evidence().target())
                || response.evidence().status() == TestSuiteRunEvidence.Status.RUNNING
                || response.evidence().promotion() == null
                || response.evidence().promotion().status()
                == TestSuiteRunEvidence.PromotionStatus.NOT_EVALUATED
                || response.evidence().completedAt() == null
                || !response.attestation().terminallyVerifiable()
                || !response.suiteRunId().equals(response.attestation().suiteRunId())
                || !Objects.equals(suiteRef, response.attestation().suiteRef())
                || !response.evidenceFingerprint().equals(
                response.attestation().aggregateEvidenceFingerprint())
                || response.evidence().caseResults().size() != suite.cases().size()
                || response.attestation().childEvidenceRefs().size() != suite.cases().size()) {
            return false;
        }
        boolean allCasesPassed = response.evidence().caseResults().stream().allMatch(
                value -> value.status() == TestSuiteRunEvidence.CaseStatus.PASSED);
        boolean allCasesTerminal = response.evidence().caseResults().stream().allMatch(
                value -> List.of(TestSuiteRunEvidence.CaseStatus.PASSED,
                        TestSuiteRunEvidence.CaseStatus.FAILED).contains(value.status()));
        TestSuiteRunEvidence.Status expectedStatus = allCasesPassed
                ? TestSuiteRunEvidence.Status.PASSED
                : TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES;
        if (!allCasesTerminal || response.evidence().status() != expectedStatus) {
            return false;
        }
        try {
            return response.evidenceFingerprint().equals(
                    suiteEvidenceCodec.fingerprint(response.evidence()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean validChild(
            TestSuiteProtocol suite,
            TestSuite.TestCase testCase,
            TestSuiteRunEvidence.CaseResult result,
            TestSuiteRunAttestation.ChildEvidenceRef childRef,
            ChildObservation child) {
        if (child == null || !child.evidenceVerified() || child.execution() == null
                || child.execution().evidence() == null || child.execution().integrity() == null) {
            return false;
        }
        TestExecutionApiResponse response = child.execution();
        TestRunEvidence evidence = response.evidence();
        TestEvidenceIntegrity integrity = response.integrity();
        if (!evidence.runId().equals(result.runId()) || !response.runId().equals(result.runId())
                || integrity.signatureStatus() != TestEvidenceIntegrity.SignatureStatus.VERIFIED
                || integrity.projection() != TestEvidenceIntegrity.Projection.FULL
                || !integrity.independentlyVerifiable()
                || !childRef.evidenceFingerprint().equals(integrity.evidenceFingerprint())
                || !integrity.evidenceFingerprint().equals(integrity.projectionFingerprint())
                || evidence.status() != result.evidenceStatus()
                || evidence.evidenceClass() != result.evidenceClass()
                || !suite.target().fingerprint().equals(evidence.targetFingerprint())
                || !testCase.fixtureBundleRef().fingerprint()
                .equals(evidence.fixtureBundleFingerprint())
                || response.target() == null
                || !suite.target().kind().equals(response.target().kind())
                || !suite.target().id().equals(response.target().id())
                || !suite.target().fingerprint().equals(response.target().fingerprint())
                || response.fixtureBundleRef() == null
                || !testCase.fixtureBundleRef().fixtureBundleId()
                .equals(response.fixtureBundleRef().fixtureBundleId())
                || testCase.fixtureBundleRef().revision()
                != response.fixtureBundleRef().revision()
                || !testCase.fixtureBundleRef().fingerprint()
                .equals(response.fixtureBundleRef().fingerprint())
                || evidence.planFingerprint().isBlank()
                || !TestRunEvidence.SCHEMA_VERSION.equals(evidence.schemaVersion())
                || !TestSemanticResultFingerprint.matches(objectMapper, evidence)) {
            return false;
        }
        boolean passingCase = result.status() == TestSuiteRunEvidence.CaseStatus.PASSED;
        if (passingCase != (evidence.status() == TestRunEvidence.Status.PASSED)) {
            return false;
        }
        int assertions = evidence.assertionResults().size();
        int passed = (int) evidence.assertionResults().stream()
                .filter(TestRunEvidence.AssertionResult::passed).count();
        if (assertions != result.assertionsEvaluated() || passed != result.assertionsPassed()) {
            return false;
        }
        try {
            return childRef.evidenceFingerprint().equals(
                    ProtocolFingerprint.of(objectMapper, evidence));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static List<TestSuiteStabilityEvidence.CaseObservation> closePlanDrift(
            List<TestSuiteStabilityEvidence.CaseObservation> observations) {
        Set<String> plans = new LinkedHashSet<>();
        observations.stream()
                .filter(value -> value.status()
                        == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED)
                .map(TestSuiteStabilityEvidence.CaseObservation::planFingerprint)
                .forEach(plans::add);
        if (plans.size() <= 1) {
            return List.copyOf(observations);
        }
        return observations.stream().map(value -> value.status()
                == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED
                ? incomplete(value.attempt(), PLAN_DRIFT) : value).toList();
    }

    private static List<List<TestSuiteStabilityEvidence.CaseObservation>> closeReusedChildRuns(
            List<List<TestSuiteStabilityEvidence.CaseObservation>> observationsByCase,
            int attempts) {
        List<List<TestSuiteStabilityEvidence.CaseObservation>> closed = new ArrayList<>();
        observationsByCase.forEach(row -> closed.add(new ArrayList<>(row)));
        Set<String> observed = new LinkedHashSet<>();
        for (int attemptIndex = 0; attemptIndex < attempts; attemptIndex++) {
            for (List<TestSuiteStabilityEvidence.CaseObservation> observations : closed) {
                TestSuiteStabilityEvidence.CaseObservation value = observations.get(attemptIndex);
                if (value.status() == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED
                        && !observed.add(value.runId())) {
                    observations.set(attemptIndex,
                            incomplete(value.attempt(), CHILD_RUN_REUSED));
                }
            }
        }
        return closed.stream().map(List::copyOf).toList();
    }

    private static Set<Integer> reusedSourceAttempts(List<SourceEvaluation> sources) {
        Set<String> observed = new LinkedHashSet<>();
        Set<Integer> reused = new LinkedHashSet<>();
        for (SourceEvaluation source : sources) {
            if (source.sourceVerified() && !source.suiteRunId().isBlank()
                    && !observed.add(source.suiteRunId())) {
                reused.add(source.attempt());
            }
        }
        return Set.copyOf(reused);
    }

    private static SourceEvaluation incompleteSource(
            TestSuiteProtocol suite,
            int attempt,
            TestSuiteExecutionResponse response,
            Instant observedAt,
            String diagnostic) {
        List<TestSuiteStabilityEvidence.CaseObservation> cases = suite.cases().stream()
                .map(ignored -> incomplete(attempt, diagnostic)).toList();
        TestSuiteRunEvidenceProtocol evidence = response == null ? null : response.evidence();
        Instant time = observedAt == null ? Instant.EPOCH : observedAt;
        return new SourceEvaluation(attempt, false,
                response == null ? "" : response.suiteRunId(),
                response == null ? "" : response.evidenceFingerprint(),
                evidence == null ? null : evidence.status(),
                null, List.of(),
                evidence == null || evidence.startedAt() == null ? time : evidence.startedAt(),
                evidence == null || evidence.completedAt() == null ? time : evidence.completedAt(),
                diagnostic, cases);
    }

    private static TestSuiteStabilityEvidence.CaseObservation incomplete(
            int attempt, String diagnostic) {
        return new TestSuiteStabilityEvidence.CaseObservation(attempt,
                TestSuiteStabilityEvidence.ObservationStatus.INCONCLUSIVE,
                "", "", null, null, "", "", "", diagnostic);
    }

    private static List<AttemptObservation> orderedAttempts(
            int requested,
            List<AttemptObservation> supplied) {
        Map<Integer, AttemptObservation> indexed = new LinkedHashMap<>();
        if (supplied != null) {
            for (AttemptObservation source : supplied) {
                if (source == null || source.attempt() < 1 || source.attempt() > requested
                        || indexed.putIfAbsent(source.attempt(), source) != null) {
                    throw new IllegalArgumentException(
                            "Stability attempt coordinates must be unique and bounded");
                }
            }
        }
        List<AttemptObservation> ordered = new ArrayList<>();
        for (int attempt = 1; attempt <= requested; attempt++) {
            ordered.add(indexed.getOrDefault(attempt,
                    AttemptObservation.missing(attempt, Instant.EPOCH,
                            "STABILITY_ATTEMPT_NOT_EXECUTED")));
        }
        return List.copyOf(ordered);
    }

    private static void requireSupportedSuite(
            TestSuiteProtocol suite,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
        if (suite == null || suiteRef == null || suite.cases().isEmpty()
                || !suite.suiteId().equals(suiteRef.suiteId())
                || suite.revision() != suiteRef.revision()) {
            throw new IllegalArgumentException("Exact non-empty suite identity is required");
        }
        if (suite instanceof TestSuiteV3 || suite instanceof TestSuiteV5) {
            throw new IllegalArgumentException(
                    "Stability analysis requires executable child evidence for every suite case");
        }
    }

    private static TestSuiteStabilityEvidence.CaseStatus caseStatus(
            List<TestSuiteStabilityEvidence.CaseObservation> observations,
            Set<String> outcomes) {
        if (outcomes.size() > 1) {
            return TestSuiteStabilityEvidence.CaseStatus.FLAKY;
        }
        boolean complete = observations.stream().allMatch(value -> value.status()
                == TestSuiteStabilityEvidence.ObservationStatus.VERIFIED);
        if (!complete || outcomes.isEmpty()) {
            return TestSuiteStabilityEvidence.CaseStatus.INCONCLUSIVE;
        }
        return observations.stream().allMatch(
                value -> value.evidenceStatus() == TestRunEvidence.Status.PASSED)
                ? TestSuiteStabilityEvidence.CaseStatus.STABLE_PASS
                : TestSuiteStabilityEvidence.CaseStatus.CONSISTENT_FAILURE;
    }

    private static TestSuiteStabilityEvidence.Status aggregateStatus(
            List<TestSuiteStabilityEvidence.CaseStabilityResult> cases) {
        if (cases.stream().anyMatch(value -> value.status()
                == TestSuiteStabilityEvidence.CaseStatus.FLAKY)) {
            return TestSuiteStabilityEvidence.Status.FLAKY;
        }
        if (cases.stream().anyMatch(value -> value.status()
                == TestSuiteStabilityEvidence.CaseStatus.INCONCLUSIVE)) {
            return TestSuiteStabilityEvidence.Status.INCONCLUSIVE;
        }
        if (cases.stream().anyMatch(value -> value.status()
                == TestSuiteStabilityEvidence.CaseStatus.CONSISTENT_FAILURE)) {
            return TestSuiteStabilityEvidence.Status.CONSISTENT_FAILURE;
        }
        return TestSuiteStabilityEvidence.Status.STABLE;
    }

    private static String firstDiagnostic(List<String> candidates, String fallback) {
        return candidates.stream().filter(value -> value != null && !value.isBlank())
                .sorted().findFirst().orElseGet(() -> fallback == null || fallback.isBlank()
                        ? SOURCE_EVIDENCE_INVALID : fallback);
    }

    /**
     * One source suite run and its already-resolved child evidence.
     *
     * @param attempt one-based attempt coordinate
     * @param suiteExecution source suite response
     * @param suiteEvidenceVerified true only after cryptographic source verification
     * @param childrenByRunId exact child run lookup
     * @param observedAt authoritative observation time used when execution is absent
     * @param diagnosticCode bounded infrastructure diagnostic for a missing source
     */
    public record AttemptObservation(
            int attempt,
            TestSuiteExecutionResponse suiteExecution,
            boolean suiteEvidenceVerified,
            Map<String, ChildObservation> childrenByRunId,
            Instant observedAt,
            String diagnosticCode
    ) {
        /** Defensively freezes source lookups and normalizes diagnostics. */
        public AttemptObservation {
            childrenByRunId = childrenByRunId == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(childrenByRunId));
            observedAt = observedAt == null ? Instant.EPOCH : observedAt;
            diagnosticCode = diagnosticCode == null ? "" : diagnosticCode.trim();
            if (attempt < 1) {
                throw new IllegalArgumentException("Stability attempt must be one-based");
            }
        }

        /**
         * Creates a bounded absent-attempt observation.
         *
         * @param attempt one-based coordinate
         * @param observedAt observation time
         * @param diagnosticCode stable absence reason
         * @return incomplete source observation
         */
        public static AttemptObservation missing(
                int attempt, Instant observedAt, String diagnosticCode) {
            return new AttemptObservation(attempt, null, false, Map.of(), observedAt,
                    diagnosticCode);
        }
    }

    /**
     * One child response and its upstream cryptographic verification result.
     *
     * @param execution full child execution response
     * @param evidenceVerified true only after full evidence integrity verification
     */
    public record ChildObservation(
            TestExecutionApiResponse execution,
            boolean evidenceVerified
    ) {
    }

    private record SourceEvaluation(
            int attempt,
            boolean sourceVerified,
            String suiteRunId,
            String aggregateEvidenceFingerprint,
            TestSuiteRunEvidence.Status suiteStatus,
            TestSuiteRunEvidence.PromotionStatus sourcePromotionStatus,
            List<String> sourcePromotionReasons,
            Instant startedAt,
            Instant completedAt,
            String diagnosticCode,
            List<TestSuiteStabilityEvidence.CaseObservation> caseObservations
    ) {
    }
}

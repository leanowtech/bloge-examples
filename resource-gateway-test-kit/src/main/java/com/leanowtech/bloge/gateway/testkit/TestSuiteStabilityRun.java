package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Strongly typed, payload-free projection of one terminal suite-stability analysis.
 *
 * <p>The constructor independently re-derives every case classification, aggregate status,
 * promotion gate, quarantine recommendation, ordered attempt closure, source attestation closure,
 * and canonical evidence fingerprint. Parsing a producer status is never treated as proof.</p>
 *
 * @param stabilityRunId deterministic analysis id
 * @param clientRequestId caller-owned parent idempotency key
 * @param status independently rechecked aggregate status
 * @param suiteRef exact immutable suite identity
 * @param target exact graph or operator target
 * @param requestedAttempts bounded requested rerun count
 * @param evidenceFingerprint canonical evidence fingerprint
 * @param attempts ordered source suite-run observations
 * @param caseResults ordered case stability results
 * @param promotion independently rechecked promotion verdict
 * @param quarantine independently rechecked quarantine recommendation
 * @param startedAt earliest source start
 * @param completedAt latest source completion
 * @param diagnostics bounded payload-free diagnostic codes
 * @param attestation detached terminal signature manifest
 * @param rawResponse defensive complete protocol response
 */
public record TestSuiteStabilityRun(
        String stabilityRunId,
        String clientRequestId,
        Status status,
        TestSuiteStabilityAttestation.SuiteRef suiteRef,
        TargetRef target,
        int requestedAttempts,
        String evidenceFingerprint,
        List<AttemptResult> attempts,
        List<CaseStabilityResult> caseResults,
        PromotionVerdict promotion,
        QuarantineVerdict quarantine,
        Instant startedAt,
        Instant completedAt,
        List<String> diagnostics,
        TestSuiteStabilityAttestation attestation,
        JsonNode rawResponse
) {
    /** Aggregate stability classification. */
    public enum Status {
        /** Every case has one invariant verified passing outcome. */
        STABLE,
        /** At least one case has two different verified outcomes. */
        FLAKY,
        /** Complete evidence contains one or more invariant failures. */
        CONSISTENT_FAILURE,
        /** Available evidence cannot establish one of the stronger outcomes. */
        INCONCLUSIVE
    }

    /** Trust state of one source suite-run attempt. */
    public enum AttemptStatus {
        /** Source and complete child closure were verified by the producer. */
        VERIFIED,
        /** Source or child closure lacks complete proof. */
        INCONCLUSIVE
    }

    /** Terminal source suite outcome. */
    public enum SuiteStatus {
        /** Source execution is not terminal and is invalid for stability evidence. */
        RUNNING,
        /** Every source suite case passed. */
        PASSED,
        /** Source scheduling completed with failures. */
        COMPLETED_WITH_FAILURES,
        /** Source execution terminated with unscheduled work. */
        PARTIAL,
        /** Source evidence closure is incomplete. */
        EVIDENCE_INCOMPLETE
    }

    /** Independently derived case stability state. */
    public enum CaseStatus {
        /** Complete observations prove one invariant passing outcome. */
        STABLE_PASS,
        /** Complete observations prove one invariant non-passing outcome. */
        CONSISTENT_FAILURE,
        /** Verified observations prove at least two distinct outcomes. */
        FLAKY,
        /** Complete independent observations are unavailable. */
        INCONCLUSIVE
    }

    /** Trust state of one child observation. */
    public enum ObservationStatus {
        /** Complete child semantic identity is present. */
        VERIFIED,
        /** The child semantic identity cannot be trusted. */
        INCONCLUSIVE
    }

    /** Child execution outcome used in semantic outcome identity. */
    public enum EvidenceStatus {
        /** Execution and every declared assertion passed. */
        PASSED,
        /** Execution completed but at least one assertion failed. */
        ASSERTION_FAILED,
        /** Business execution failed before assertions could establish success. */
        EXECUTION_FAILED,
        /** The requested test-control plan was rejected before execution. */
        CONTROL_PLAN_REJECTED,
        /** No governed fixture matched a required data acquisition. */
        FIXTURE_UNMATCHED,
        /** At least one required governed fixture was not consumed. */
        FIXTURE_UNUSED,
        /** The runtime could not establish the requested test-control plan. */
        CONTROL_PLAN_UNAVAILABLE,
        /** Required terminal evidence could not be closed. */
        EVIDENCE_INCOMPLETE,
        /** The child execution was cancelled. */
        CANCELLED,
        /** The child execution exceeded its bounded deadline. */
        TIMED_OUT
    }

    /** Child evidence trust class. */
    public enum EvidenceClass {
        /** Evidence is useful for diagnosis but cannot enter a release gate. */
        EXPLORATORY,
        /** Evidence satisfies the declared certification preconditions. */
        CERTIFIABLE
    }

    /** External promotion input, not a publication decision. */
    public enum PromotionStatus {
        /** Complete stability evidence permits an external gate to continue. */
        ELIGIBLE,
        /** Stability evidence requires an external gate to stop. */
        BLOCKED
    }

    /** Non-destructive quarantine recommendation. */
    public enum QuarantineStatus {
        /** Complete evidence proves that no flaky case requires quarantine. */
        NOT_REQUIRED,
        /** Proven flakiness identifies cases that should be quarantined. */
        REQUIRED,
        /** Incomplete evidence cannot establish a quarantine decision. */
        UNDETERMINED
    }

    /**
     * Exact target identity.
     *
     * @param kind graph or operator
     * @param id registered target id
     * @param fingerprint immutable target fingerprint
     */
    public record TargetRef(String kind, String id, String fingerprint) {
        /** Normalizes and validates one exact target. */
        public TargetRef {
            kind = normalized(kind);
            id = normalized(id);
            fingerprint = normalized(fingerprint);
            if (!Set.of("GRAPH", "OPERATOR").contains(kind) || id.isBlank()
                    || !TestSuiteStabilityRun.fingerprint(fingerprint)) {
                throw new IllegalArgumentException("Stability target is incomplete");
            }
        }
    }

    /**
     * Exact governed fixture identity.
     *
     * @param fixtureBundleId stable fixture id
     * @param revision immutable positive revision
     * @param fingerprint fixture content fingerprint
     */
    public record FixtureRef(String fixtureBundleId, long revision, String fingerprint) {
        /** Normalizes and validates one exact fixture. */
        public FixtureRef {
            fixtureBundleId = normalized(fixtureBundleId);
            fingerprint = normalized(fingerprint);
            if (fixtureBundleId.isBlank() || revision < 1
                    || !TestSuiteStabilityRun.fingerprint(fingerprint)) {
                throw new IllegalArgumentException("Stability fixture reference is incomplete");
            }
        }
    }

    /**
     * One source suite-run attempt.
     *
     * @param attempt one-based rerun coordinate
     * @param status source trust status
     * @param suiteRunId durable source id when observed
     * @param aggregateEvidenceFingerprint source aggregate fingerprint when observed
     * @param suiteStatus source suite terminal status when observed
     * @param startedAt source start when observed
     * @param completedAt source completion when observed
     * @param diagnosticCode stable reason when inconclusive
     */
    public record AttemptResult(
            int attempt,
            AttemptStatus status,
            String suiteRunId,
            String aggregateEvidenceFingerprint,
            SuiteStatus suiteStatus,
            Instant startedAt,
            Instant completedAt,
            String diagnosticCode
    ) {
        /** Normalizes one source reference and rejects false verified claims. */
        public AttemptResult {
            suiteRunId = normalized(suiteRunId);
            aggregateEvidenceFingerprint = normalized(aggregateEvidenceFingerprint);
            diagnosticCode = machineCode(diagnosticCode);
            if (attempt < 1 || attempt > 20 || status == null) {
                throw new IllegalArgumentException("Stability attempt coordinate is invalid");
            }
            boolean complete = !suiteRunId.isBlank()
                    && fingerprint(aggregateEvidenceFingerprint) && suiteStatus != null
                    && suiteStatus != SuiteStatus.RUNNING && startedAt != null
                    && completedAt != null && !completedAt.isBefore(startedAt);
            if (status == AttemptStatus.VERIFIED && (!complete || !diagnosticCode.isBlank())
                    || status == AttemptStatus.INCONCLUSIVE && diagnosticCode.isBlank()) {
                throw new IllegalArgumentException("Stability attempt trust claim is contradictory");
            }
        }

        private boolean completeSourceIdentity() {
            return !suiteRunId.isBlank() && fingerprint(aggregateEvidenceFingerprint);
        }
    }

    /**
     * One payload-free child semantic observation.
     *
     * @param attempt one-based rerun coordinate
     * @param status observation trust state
     * @param runId child run id when verified
     * @param evidenceFingerprint complete child evidence fingerprint
     * @param evidenceStatus child business/test outcome
     * @param evidenceClass child trust class
     * @param fixtureBundleFingerprint exact fixture identity
     * @param planFingerprint exact effective plan identity
     * @param semanticResultFingerprint canonical business-result identity
     * @param diagnosticCode stable reason when inconclusive
     */
    public record CaseObservation(
            int attempt,
            ObservationStatus status,
            String runId,
            String evidenceFingerprint,
            EvidenceStatus evidenceStatus,
            EvidenceClass evidenceClass,
            String fixtureBundleFingerprint,
            String planFingerprint,
            String semanticResultFingerprint,
            String diagnosticCode
    ) {
        /** Normalizes one observation and rejects false verified claims. */
        public CaseObservation {
            runId = normalized(runId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            fixtureBundleFingerprint = normalized(fixtureBundleFingerprint);
            planFingerprint = normalized(planFingerprint);
            semanticResultFingerprint = normalized(semanticResultFingerprint);
            diagnosticCode = machineCode(diagnosticCode);
            if (attempt < 1 || attempt > 20 || status == null) {
                throw new IllegalArgumentException("Stability observation coordinate is invalid");
            }
            boolean complete = !runId.isBlank() && fingerprint(evidenceFingerprint)
                    && evidenceStatus != null && evidenceClass != null
                    && fingerprint(fixtureBundleFingerprint) && fingerprint(planFingerprint)
                    && fingerprint(semanticResultFingerprint);
            if (status == ObservationStatus.VERIFIED
                    && (!complete || !diagnosticCode.isBlank())
                    || status == ObservationStatus.INCONCLUSIVE && diagnosticCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Stability child observation trust claim is contradictory");
            }
        }

        private String outcomeIdentity() {
            return status == ObservationStatus.VERIFIED
                    ? evidenceStatus.name() + ':' + semanticResultFingerprint : "";
        }
    }

    /**
     * Independently classified result for one exact suite case.
     *
     * @param caseId suite-local stable case id
     * @param caseType declared case intent
     * @param fixtureRef exact fixture dependency
     * @param status producer case classification checked by the constructor
     * @param observations one observation per requested attempt
     * @param distinctVerifiedOutcomes producer count checked by the constructor
     * @param diagnosticCodes sorted payload-free diagnostics
     */
    public record CaseStabilityResult(
            String caseId,
            String caseType,
            FixtureRef fixtureRef,
            CaseStatus status,
            List<CaseObservation> observations,
            int distinctVerifiedOutcomes,
            List<String> diagnosticCodes
    ) {
        /** Re-derives the case outcome instead of trusting producer aggregate fields. */
        public CaseStabilityResult {
            caseId = normalized(caseId);
            caseType = normalized(caseType);
            observations = observations == null ? List.of() : List.copyOf(observations);
            diagnosticCodes = immutableCodes(diagnosticCodes);
            Set<String> outcomes = new LinkedHashSet<>();
            observations.stream().filter(value ->
                    value.status() == ObservationStatus.VERIFIED)
                    .map(CaseObservation::outcomeIdentity).forEach(outcomes::add);
            CaseStatus derived = deriveCaseStatus(observations, outcomes);
            List<String> derivedDiagnostics = immutableCodes(observations.stream()
                    .map(CaseObservation::diagnosticCode).filter(value -> !value.isBlank())
                    .toList());
            if (caseId.isBlank() || !Set.of("GOLDEN", "NEGATIVE", "BOUNDARY", "REGRESSION",
                    "PROPERTY").contains(caseType) || fixtureRef == null || status != derived
                    || distinctVerifiedOutcomes != outcomes.size()
                    || !diagnosticCodes.equals(derivedDiagnostics)) {
                throw new IllegalArgumentException("Stability case aggregate is contradictory");
            }
        }
    }

    /**
     * Independently checked promotion verdict.
     *
     * @param status eligibility status
     * @param reasons stable blocking reasons
     * @param stableCases stable passing case count
     * @param flakyCases proven flaky case count
     * @param consistentFailureCases invariant failure case count
     * @param inconclusiveCases incomplete case count
     * @param allAttemptsVerified complete source-closure flag
     */
    public record PromotionVerdict(
            PromotionStatus status,
            List<String> reasons,
            int stableCases,
            int flakyCases,
            int consistentFailureCases,
            int inconclusiveCases,
            boolean allAttemptsVerified
    ) {
        /** Normalizes one producer verdict before aggregate equality is checked. */
        public PromotionVerdict {
            reasons = immutableCodes(reasons);
            if (status == null || stableCases < 0 || flakyCases < 0
                    || consistentFailureCases < 0 || inconclusiveCases < 0) {
                throw new IllegalArgumentException("Stability promotion verdict is invalid");
            }
        }
    }

    /**
     * Independently checked non-destructive quarantine recommendation.
     *
     * @param status recommendation state
     * @param caseIds proven flaky case ids
     * @param reason stable recommendation reason
     */
    public record QuarantineVerdict(
            QuarantineStatus status,
            List<String> caseIds,
            String reason
    ) {
        /** Normalizes one producer recommendation before aggregate equality is checked. */
        public QuarantineVerdict {
            caseIds = immutableIds(caseIds);
            reason = machineCode(reason);
            if (status == null) {
                throw new IllegalArgumentException("Stability quarantine status is required");
            }
        }
    }

    /** Re-derives complete aggregate semantics and immutable source closure. */
    public TestSuiteStabilityRun {
        stabilityRunId = normalized(stabilityRunId);
        clientRequestId = normalized(clientRequestId);
        evidenceFingerprint = normalized(evidenceFingerprint);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        diagnostics = immutableCodes(diagnostics);
        if (!stabilityRunId(stabilityRunId) || clientRequestId.isBlank() || status == null
                || suiteRef == null || target == null || requestedAttempts < 3
                || requestedAttempts > 20 || attempts.size() != requestedAttempts
                || caseResults.isEmpty() || !fingerprint(evidenceFingerprint)
                || promotion == null || quarantine == null || startedAt == null
                || completedAt == null || completedAt.isBefore(startedAt)
                || attestation == null || rawResponse == null || !rawResponse.isObject()) {
            throw new IllegalArgumentException("Complete stability analysis is required");
        }
        rawResponse = rawResponse.deepCopy();
        requireAttemptClosure(attempts, requestedAttempts);
        requireCaseClosure(caseResults, requestedAttempts);
        requireAttemptObservationConsistency(attempts, caseResults);
        Status derivedStatus = deriveStatus(caseResults);
        PromotionVerdict derivedPromotion = derivePromotion(attempts, caseResults, derivedStatus);
        QuarantineVerdict derivedQuarantine = deriveQuarantine(caseResults, derivedStatus);
        Instant derivedStartedAt = attempts.stream().map(AttemptResult::startedAt)
                .filter(value -> value != null).min(Comparator.naturalOrder()).orElse(null);
        Instant derivedCompletedAt = attempts.stream().map(AttemptResult::completedAt)
                .filter(value -> value != null).max(Comparator.naturalOrder()).orElse(null);
        List<String> derivedDiagnostics = new ArrayList<>();
        attempts.stream().map(AttemptResult::diagnosticCode)
                .filter(value -> !value.isBlank()).forEach(derivedDiagnostics::add);
        caseResults.stream().flatMap(value -> value.diagnosticCodes().stream())
                .forEach(derivedDiagnostics::add);
        derivedDiagnostics = immutableCodes(derivedDiagnostics);
        List<TestSuiteStabilityAttestation.SourceSuiteEvidenceRef> expectedSources = attempts.stream()
                .filter(AttemptResult::completeSourceIdentity)
                .map(value -> new TestSuiteStabilityAttestation.SourceSuiteEvidenceRef(
                        value.attempt(), value.suiteRunId(), value.aggregateEvidenceFingerprint()))
                .toList();
        JsonNode evidence = rawResponse.path("evidence");
        if (status != derivedStatus || !promotion.equals(derivedPromotion)
                || !quarantine.equals(derivedQuarantine)
                || !stabilityRunId.equals(evidence.path("stabilityRunId").asText())
                || !startedAt.equals(derivedStartedAt) || !completedAt.equals(derivedCompletedAt)
                || !diagnostics.equals(derivedDiagnostics)
                || !stabilityRunId.equals(attestation.stabilityRunId())
                || !suiteRef.equals(attestation.suiteRef())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !expectedSources.equals(attestation.sourceSuiteEvidenceRefs())
                || !evidenceFingerprint.equals(EvidenceVerificationSupport.sha256(evidence))) {
            throw new IllegalArgumentException(
                    "Stability aggregate, source closure, or fingerprint is invalid");
        }
    }

    /**
     * Decodes and independently re-derives one authoritative response.
     *
     * @param response exact stability execution response
     * @return typed immutable projection
     */
    public static TestSuiteStabilityRun from(JsonNode response) {
        TestingProtocolSchemaValidator.require(response, "testSuiteStabilityExecutionResponse");
        JsonNode evidence = response.path("evidence");
        JsonNode suite = evidence.path("suiteRef");
        JsonNode target = evidence.path("target");
        List<AttemptResult> attempts = new ArrayList<>();
        evidence.path("attempts").forEach(value -> attempts.add(new AttemptResult(
                value.path("attempt").asInt(), enumValue(AttemptStatus.class,
                value.path("status").asText(), "attempt status"),
                value.path("suiteRunId").asText(),
                value.path("aggregateEvidenceFingerprint").asText(),
                nullableEnum(SuiteStatus.class, value.path("suiteStatus"), "suite status"),
                nullableInstant(value.path("startedAt")), nullableInstant(value.path("completedAt")),
                value.path("diagnosticCode").asText())));
        List<CaseStabilityResult> cases = new ArrayList<>();
        evidence.path("caseResults").forEach(value -> {
            JsonNode fixture = value.path("fixtureBundleRef");
            List<CaseObservation> observations = new ArrayList<>();
            value.path("observations").forEach(observation -> observations.add(
                    new CaseObservation(observation.path("attempt").asInt(),
                            enumValue(ObservationStatus.class,
                                    observation.path("status").asText(), "observation status"),
                            observation.path("runId").asText(),
                            observation.path("evidenceFingerprint").asText(),
                            nullableEnum(EvidenceStatus.class,
                                    observation.path("evidenceStatus"), "evidence status"),
                            nullableEnum(EvidenceClass.class,
                                    observation.path("evidenceClass"), "evidence class"),
                            observation.path("fixtureBundleFingerprint").asText(),
                            observation.path("planFingerprint").asText(),
                            observation.path("semanticResultFingerprint").asText(),
                            observation.path("diagnosticCode").asText())));
            cases.add(new CaseStabilityResult(value.path("caseId").asText(),
                    value.path("caseType").asText(), new FixtureRef(
                    fixture.path("fixtureBundleId").asText(), fixture.path("revision").asLong(),
                    fixture.path("fingerprint").asText()), enumValue(CaseStatus.class,
                    value.path("status").asText(), "case status"), observations,
                    value.path("distinctVerifiedOutcomes").asInt(),
                    strings(value.path("diagnosticCodes"))));
        });
        JsonNode promotion = evidence.path("promotion");
        JsonNode quarantine = evidence.path("quarantine");
        return new TestSuiteStabilityRun(response.path("stabilityRunId").asText(),
                evidence.path("clientRequestId").asText(), enumValue(Status.class,
                evidence.path("status").asText(), "aggregate status"),
                new TestSuiteStabilityAttestation.SuiteRef(suite.path("suiteId").asText(),
                        suite.path("revision").asLong(), suite.path("fingerprint").asText()),
                new TargetRef(target.path("kind").asText(), target.path("id").asText(),
                        target.path("fingerprint").asText()),
                evidence.path("requestedAttempts").asInt(),
                response.path("evidenceFingerprint").asText(), attempts, cases,
                new PromotionVerdict(enumValue(PromotionStatus.class,
                        promotion.path("status").asText(), "promotion status"),
                        strings(promotion.path("reasons")), promotion.path("stableCases").asInt(),
                        promotion.path("flakyCases").asInt(),
                        promotion.path("consistentFailureCases").asInt(),
                        promotion.path("inconclusiveCases").asInt(),
                        promotion.path("allAttemptsVerified").asBoolean()),
                new QuarantineVerdict(enumValue(QuarantineStatus.class,
                        quarantine.path("status").asText(), "quarantine status"),
                        strings(quarantine.path("caseIds")), quarantine.path("reason").asText()),
                instant(evidence.path("startedAt")), instant(evidence.path("completedAt")),
                strings(evidence.path("diagnostics")),
                TestSuiteStabilityAttestation.from(response.path("attestation")), response);
    }

    /**
     * Reports whether complete evidence proves invariant passing behavior.
     *
     * @return true only for a stable aggregate
     */
    public boolean stable() {
        return status == Status.STABLE;
    }

    /**
     * Reports whether the independently checked promotion verdict is eligible.
     *
     * @return true only when stability permits an external release gate to continue
     */
    public boolean promotionEligible() {
        return promotion.status() == PromotionStatus.ELIGIBLE;
    }

    /**
     * Reports whether proven flakiness requires a quarantine recommendation.
     *
     * @return true only when at least one case is proven flaky
     */
    public boolean quarantineRequired() {
        return quarantine.status() == QuarantineStatus.REQUIRED;
    }

    /**
     * Requires this result to match an exact caller-owned execution intent.
     *
     * @param expectedSuiteId requested suite id
     * @param expectedRevision requested suite revision
     * @param expectedFingerprint requested suite fingerprint
     * @param expectedClientRequestId caller parent idempotency key
     */
    void requireExecutionIdentity(
            String expectedSuiteId,
            long expectedRevision,
            String expectedFingerprint,
            String expectedClientRequestId) {
        if (!suiteRef.suiteId().equals(normalized(expectedSuiteId))
                || suiteRef.revision() != expectedRevision
                || !suiteRef.fingerprint().equals(normalized(expectedFingerprint))
                || !clientRequestId.equals(normalized(expectedClientRequestId))) {
            throw new IllegalArgumentException(
                    "Stability response identity does not match the request");
        }
    }

    /** Requires this result to match one requested deterministic analysis id. */
    void requireRunIdentity(String expectedStabilityRunId) {
        if (!stabilityRunId.equals(normalized(expectedStabilityRunId))) {
            throw new IllegalArgumentException(
                    "Stability response identity does not match the request");
        }
    }

    /**
     * Returns a defensive copy of the authorized complete response.
     *
     * @return copied schema-validated protocol response
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse.deepCopy();
    }

    private static void requireAttemptClosure(List<AttemptResult> attempts, int requested) {
        Set<String> verifiedSourceIds = new LinkedHashSet<>();
        for (int index = 0; index < requested; index++) {
            AttemptResult result = attempts.get(index);
            if (result.attempt() != index + 1
                    || result.status() == AttemptStatus.VERIFIED
                    && !verifiedSourceIds.add(result.suiteRunId())) {
                throw new IllegalArgumentException(
                        "Stability attempts are not independent ordered samples");
            }
        }
    }

    private static void requireCaseClosure(List<CaseStabilityResult> cases, int requested) {
        Set<String> caseIds = new LinkedHashSet<>();
        Set<String> verifiedChildIds = new LinkedHashSet<>();
        for (CaseStabilityResult result : cases) {
            if (!caseIds.add(result.caseId()) || result.observations().size() != requested) {
                throw new IllegalArgumentException("Stability case closure is incomplete");
            }
            for (int index = 0; index < requested; index++) {
                CaseObservation observation = result.observations().get(index);
                if (observation.attempt() != index + 1
                        || observation.status() == ObservationStatus.VERIFIED
                        && !verifiedChildIds.add(observation.runId())) {
                    throw new IllegalArgumentException(
                            "Stability child observations are not independent ordered samples");
                }
            }
        }
    }

    private static void requireAttemptObservationConsistency(
            List<AttemptResult> attempts,
            List<CaseStabilityResult> cases) {
        for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
            boolean allChildrenVerified = true;
            for (CaseStabilityResult result : cases) {
                allChildrenVerified &= result.observations().get(attemptIndex).status()
                        == ObservationStatus.VERIFIED;
            }
            if ((attempts.get(attemptIndex).status() == AttemptStatus.VERIFIED)
                    != allChildrenVerified) {
                throw new IllegalArgumentException(
                        "Stability source and child trust states are contradictory");
            }
        }
    }

    private static CaseStatus deriveCaseStatus(
            List<CaseObservation> observations,
            Set<String> outcomes) {
        if (outcomes.size() > 1) {
            return CaseStatus.FLAKY;
        }
        boolean complete = !observations.isEmpty() && observations.stream().allMatch(
                value -> value.status() == ObservationStatus.VERIFIED);
        if (!complete || outcomes.isEmpty()) {
            return CaseStatus.INCONCLUSIVE;
        }
        return observations.stream().allMatch(
                value -> value.evidenceStatus() == EvidenceStatus.PASSED)
                ? CaseStatus.STABLE_PASS : CaseStatus.CONSISTENT_FAILURE;
    }

    private static Status deriveStatus(List<CaseStabilityResult> cases) {
        if (cases.stream().anyMatch(value -> value.status() == CaseStatus.FLAKY)) {
            return Status.FLAKY;
        }
        if (cases.stream().anyMatch(value -> value.status() == CaseStatus.INCONCLUSIVE)) {
            return Status.INCONCLUSIVE;
        }
        if (cases.stream().anyMatch(value ->
                value.status() == CaseStatus.CONSISTENT_FAILURE)) {
            return Status.CONSISTENT_FAILURE;
        }
        return Status.STABLE;
    }

    private static PromotionVerdict derivePromotion(
            List<AttemptResult> attempts,
            List<CaseStabilityResult> cases,
            Status status) {
        int stable = count(cases, CaseStatus.STABLE_PASS);
        int flaky = count(cases, CaseStatus.FLAKY);
        int failures = count(cases, CaseStatus.CONSISTENT_FAILURE);
        int incomplete = count(cases, CaseStatus.INCONCLUSIVE);
        boolean allVerified = attempts.stream().allMatch(
                value -> value.status() == AttemptStatus.VERIFIED);
        List<String> reasons = new ArrayList<>();
        if (flaky > 0) {
            reasons.add("FLAKY_CASE_OBSERVED");
        }
        if (failures > 0) {
            reasons.add("CONSISTENT_TEST_FAILURE");
        }
        if (incomplete > 0 || !allVerified) {
            reasons.add("STABILITY_EVIDENCE_INCOMPLETE");
        }
        return new PromotionVerdict(status == Status.STABLE && allVerified
                ? PromotionStatus.ELIGIBLE : PromotionStatus.BLOCKED,
                reasons, stable, flaky, failures, incomplete, allVerified);
    }

    private static QuarantineVerdict deriveQuarantine(
            List<CaseStabilityResult> cases,
            Status status) {
        List<String> flaky = cases.stream()
                .filter(value -> value.status() == CaseStatus.FLAKY)
                .map(CaseStabilityResult::caseId).sorted().toList();
        if (!flaky.isEmpty()) {
            return new QuarantineVerdict(
                    QuarantineStatus.REQUIRED, flaky, "FLAKY_CASE_OBSERVED");
        }
        return status == Status.INCONCLUSIVE
                ? new QuarantineVerdict(QuarantineStatus.UNDETERMINED, List.of(),
                "STABILITY_EVIDENCE_INCOMPLETE")
                : new QuarantineVerdict(QuarantineStatus.NOT_REQUIRED, List.of(), "");
    }

    private static int count(List<CaseStabilityResult> cases, CaseStatus status) {
        return (int) cases.stream().filter(value -> value.status() == status).count();
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static List<String> immutableCodes(List<String> values) {
        List<String> result = values == null ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(values));
        result.replaceAll(TestSuiteStabilityRun::machineCode);
        result.removeIf(String::isBlank);
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private static List<String> immutableIds(List<String> values) {
        List<String> result = values == null ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(values));
        result.replaceAll(TestSuiteStabilityRun::normalized);
        result.removeIf(String::isBlank);
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private static String machineCode(String value) {
        String normalized = normalized(value);
        if (!normalized.isBlank() && !normalized.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Stability diagnostic must be a machine code");
        }
        return normalized;
    }

    private static Instant instant(JsonNode value) {
        Instant result = nullableInstant(value);
        if (result == null) {
            throw new IllegalArgumentException("Stability timestamp is absent");
        }
        return result;
    }

    private static Instant nullableInstant(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Stability timestamp is invalid");
        }
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value,
            String field) {
        E result = nullableEnum(type, new com.fasterxml.jackson.databind.node.TextNode(value), field);
        if (result == null) {
            throw new IllegalArgumentException("Missing stability " + field);
        }
        return result;
    }

    private static <E extends Enum<E>> E nullableEnum(
            Class<E> type,
            JsonNode value,
            String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Enum.valueOf(type, normalized(value.asText()));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unknown stability " + field);
        }
    }

    private static boolean stabilityRunId(String value) {
        return normalized(value).matches("stability-[0-9a-f]{64}");
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}

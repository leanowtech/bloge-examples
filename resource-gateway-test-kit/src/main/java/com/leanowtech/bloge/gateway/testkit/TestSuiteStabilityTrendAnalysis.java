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
 * Strict payload-free projection of one signed retained-window stability trend analysis.
 *
 * <p>Construction validates Schema, request identity, evidence fingerprint, ordered source
 * closure, temporal ordering, and producer cross-field invariants. It does not by itself trust the
 * producer signature or derived trend labels; use {@link TestSuiteStabilityTrendEvidenceVerifier}
 * with every referenced source before consuming the result as evidence.</p>
 *
 * @param schemaVersion exact response generation
 * @param trendAnalysisId deterministic trend identity
 * @param evidenceFingerprint canonical trend evidence fingerprint
 * @param request exact request reconstructed from signed evidence
 * @param observedRuns retained source count
 * @param expiredMatchingRuns matching rows lost to evidence retention
 * @param completeWindow whether the signed storage window reports no gap or truncation
 * @param status producer aggregate trend label
 * @param sources ordered source projections
 * @param caseTrends ordered producer case trends
 * @param correlationSignals bounded non-causal producer signals
 * @param diagnostics sorted machine-readable completeness reasons
 * @param evaluatedAt signed database observation boundary
 * @param attestation detached trend signature manifest
 * @param rawResponse defensive schema-validated protocol response
 */
public record TestSuiteStabilityTrendAnalysis(
        String schemaVersion,
        String trendAnalysisId,
        String evidenceFingerprint,
        TestSuiteStabilityTrendRequest request,
        int observedRuns,
        int expiredMatchingRuns,
        boolean completeWindow,
        Status status,
        List<SourceObservation> sources,
        List<CaseTrend> caseTrends,
        List<CorrelationSignal> correlationSignals,
        List<String> diagnostics,
        Instant evaluatedAt,
        Attestation attestation,
        JsonNode rawResponse
) {
    /** Aggregate historical states without a prediction or publication claim. */
    public enum Status {
        /** Every retained source passed stably inside one execution regime. */
        STABLE_PASS,
        /** Complete observations contain invariant business or assertion failures. */
        CONSISTENT_FAILURE_OBSERVED,
        /** A source is flaky or a same-regime outcome changed across sources. */
        INSTABILITY_OBSERVED,
        /** Complete observations span more than one fixture or effective-plan regime. */
        REGIME_DRIFT_OBSERVED,
        /** Missing, censored, expired, truncated, or insufficient evidence blocks a conclusion. */
        INCONCLUSIVE
    }

    /** Per-case historical states. */
    public enum CaseTrendStatus {
        /** The case passed invariantly inside one regime. */
        STABLE_PASS,
        /** The case failed invariantly inside one regime. */
        CONSISTENT_FAILURE_OBSERVED,
        /** The case was flaky or changed outcome inside one regime. */
        INSTABILITY_OBSERVED,
        /** The case spans more than one fixture or effective-plan regime. */
        REGIME_DRIFT_OBSERVED,
        /** The case lacks a complete observation in every source. */
        INCONCLUSIVE
    }

    /** Bounded correlation candidate types; neither proves causation. */
    public enum CorrelationSignalType {
        /** At least two cases are independently classified flaky in one source. */
        MULTI_CASE_FLAKINESS,
        /** At least two cases change outcome between adjacent same-regime sources. */
        COINCIDENT_OUTCOME_SHIFT
    }

    /**
     * One source case summary used for independent reconstruction.
     *
     * @param caseId exact suite-local case id
     * @param status source stability classification
     * @param outcomeSetFingerprint fingerprint of sorted verified outcome identities
     * @param fixtureSetFingerprint fingerprint of sorted verified fixture identities
     * @param planSetFingerprint fingerprint of sorted verified effective-plan identities
     */
    public record CaseSnapshot(
            String caseId,
            TestSuiteStabilityRun.CaseStatus status,
            String outcomeSetFingerprint,
            String fixtureSetFingerprint,
            String planSetFingerprint
    ) {
        /** Validates complete payload-free case summary material. */
        public CaseSnapshot {
            caseId = normalized(caseId);
            outcomeSetFingerprint = normalized(outcomeSetFingerprint);
            fixtureSetFingerprint = normalized(fixtureSetFingerprint);
            planSetFingerprint = normalized(planSetFingerprint);
            if (caseId.isBlank() || status == null || !fingerprint(outcomeSetFingerprint)
                    || !fingerprint(fixtureSetFingerprint) || !fingerprint(planSetFingerprint)) {
                throw new IllegalArgumentException("Trend case snapshot is incomplete");
            }
        }
    }

    /**
     * One signed source projection in database terminal order.
     *
     * @param stabilityRunId exact source stability id
     * @param evidenceFingerprint source evidence fingerprint
     * @param attestationFingerprint complete source attestation-object fingerprint
     * @param evidenceSchemaVersion source evidence generation
     * @param targetFingerprint immutable source target identity
     * @param status source aggregate stability status
     * @param promotionStatus source promotion verdict
     * @param quarantineStatus source quarantine recommendation
     * @param statisticalStatus optional fixed-horizon or anytime-valid conclusion
     * @param regimeFingerprint derived suite, target, fixture, and plan regime
     * @param cases sorted source case summaries
     * @param startedAt source execution start
     * @param completedAt source execution completion
     * @param createdAt producer-authoritative terminal persistence time
     */
    public record SourceObservation(
            String stabilityRunId,
            String evidenceFingerprint,
            String attestationFingerprint,
            String evidenceSchemaVersion,
            String targetFingerprint,
            TestSuiteStabilityRun.Status status,
            TestSuiteStabilityRun.PromotionStatus promotionStatus,
            TestSuiteStabilityRun.QuarantineStatus quarantineStatus,
            TestSuiteStabilityRun.StatisticalStatus statisticalStatus,
            String regimeFingerprint,
            List<CaseSnapshot> cases,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt
    ) {
        /** Validates one complete source summary without trusting its producer labels. */
        public SourceObservation {
            stabilityRunId = normalized(stabilityRunId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            attestationFingerprint = normalized(attestationFingerprint);
            evidenceSchemaVersion = normalized(evidenceSchemaVersion);
            targetFingerprint = normalized(targetFingerprint);
            regimeFingerprint = normalized(regimeFingerprint);
            cases = cases == null ? List.of() : List.copyOf(cases);
            if (!stabilityRunId.matches("stability-[0-9a-f]{64}")
                    || !fingerprint(evidenceFingerprint) || !fingerprint(attestationFingerprint)
                    || !Set.of(TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V1,
                    TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V2,
                    TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V3,
                    TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V4,
                    TestingProtocol.TEST_SUITE_STABILITY_EVIDENCE_V5)
                    .contains(evidenceSchemaVersion)
                    || !fingerprint(targetFingerprint) || status == null
                    || promotionStatus == null || quarantineStatus == null
                    || !fingerprint(regimeFingerprint) || cases.isEmpty()
                    || cases.stream().map(CaseSnapshot::caseId).distinct().count() != cases.size()
                    || startedAt == null || completedAt == null || createdAt == null
                    || completedAt.isBefore(startedAt) || createdAt.isBefore(completedAt)) {
                throw new IllegalArgumentException("Trend source observation is incomplete");
            }
        }
    }

    /**
     * Producer case trend that the offline verifier independently reconstructs.
     *
     * @param caseId exact suite-local case id
     * @param status derived historical case state
     * @param sourceRunIds ordered contributing source ids
     * @param changedAtRunIds sources where a same-regime outcome changed
     * @param regimeCount number of distinct fixture and plan regimes
     */
    public record CaseTrend(
            String caseId,
            CaseTrendStatus status,
            List<String> sourceRunIds,
            List<String> changedAtRunIds,
            int regimeCount
    ) {
        /** Validates one bounded case trend closure. */
        public CaseTrend {
            caseId = normalized(caseId);
            sourceRunIds = immutable(sourceRunIds);
            changedAtRunIds = immutable(changedAtRunIds);
            if (caseId.isBlank() || status == null || sourceRunIds.isEmpty()
                    || regimeCount < 1 || regimeCount > 100
                    || !sourceRunIds.containsAll(changedAtRunIds)) {
                throw new IllegalArgumentException("Trend case result is incomplete");
            }
        }
    }

    /**
     * Producer non-causal signal that the offline verifier independently reconstructs.
     *
     * @param type bounded signal type
     * @param previousRunId previous adjacent source for an outcome shift; blank for flakiness
     * @param currentRunId source where the signal is observed
     * @param regimeFingerprint exact comparable execution regime
     * @param caseIds sorted affected case identities
     */
    public record CorrelationSignal(
            CorrelationSignalType type,
            String previousRunId,
            String currentRunId,
            String regimeFingerprint,
            List<String> caseIds
    ) {
        /** Validates one bounded non-causal signal. */
        public CorrelationSignal {
            previousRunId = normalized(previousRunId);
            currentRunId = normalized(currentRunId);
            regimeFingerprint = normalized(regimeFingerprint);
            caseIds = sorted(caseIds);
            boolean shift = type == CorrelationSignalType.COINCIDENT_OUTCOME_SHIFT;
            if (type == null || !currentRunId.matches("stability-[0-9a-f]{64}")
                    || shift && !previousRunId.matches("stability-[0-9a-f]{64}")
                    || !shift && !previousRunId.isBlank() || !fingerprint(regimeFingerprint)
                    || caseIds.size() < 2) {
                throw new IllegalArgumentException("Trend correlation signal is incomplete");
            }
        }
    }

    /**
     * Exact source closure inside the detached trend signature.
     *
     * @param stabilityRunId exact source stability id
     * @param evidenceFingerprint source evidence fingerprint
     * @param attestationFingerprint complete source attestation-object fingerprint
     */
    public record SourceEvidenceRef(
            String stabilityRunId,
            String evidenceFingerprint,
            String attestationFingerprint
    ) {
        /** Validates one exact source identity. */
        public SourceEvidenceRef {
            stabilityRunId = normalized(stabilityRunId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            attestationFingerprint = normalized(attestationFingerprint);
            if (!stabilityRunId.matches("stability-[0-9a-f]{64}")
                    || !fingerprint(evidenceFingerprint) || !fingerprint(attestationFingerprint)) {
                throw new IllegalArgumentException("Trend source evidence reference is incomplete");
            }
        }
    }

    /**
     * Strict detached signature manifest for the complete trend evidence and source closure.
     *
     * @param schemaVersion exact trend attestation generation
     * @param trendAnalysisId deterministic trend identity
     * @param requestFingerprint canonical request fingerprint
     * @param evidenceFingerprint canonical trend evidence fingerprint
     * @param sourceEvidenceRefs exact ordered source closure
     * @param signedAt time included in detached signature material
     * @param keyId public verification key id
     * @param algorithm detached signature algorithm
     * @param signature base64 detached signature bytes
     * @param independentlyVerifiable producer completeness claim
     */
    public record Attestation(
            String schemaVersion,
            String trendAnalysisId,
            String requestFingerprint,
            String evidenceFingerprint,
            List<SourceEvidenceRef> sourceEvidenceRefs,
            Instant signedAt,
            String keyId,
            String algorithm,
            String signature,
            boolean independentlyVerifiable
    ) {
        /** Rejects unsigned, incomplete, or generation-mismatched trend attestations. */
        public Attestation {
            schemaVersion = normalized(schemaVersion);
            trendAnalysisId = normalized(trendAnalysisId);
            requestFingerprint = normalized(requestFingerprint);
            evidenceFingerprint = normalized(evidenceFingerprint);
            sourceEvidenceRefs = sourceEvidenceRefs == null
                    ? List.of() : List.copyOf(sourceEvidenceRefs);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            if (!TestingProtocol.TEST_SUITE_STABILITY_TREND_ATTESTATION_V1
                    .equals(schemaVersion)
                    || !trendAnalysisId.matches("stability-trend-[0-9a-f]{64}")
                    || !fingerprint(requestFingerprint) || !fingerprint(evidenceFingerprint)
                    || sourceEvidenceRefs.stream().map(SourceEvidenceRef::stabilityRunId)
                    .distinct().count() != sourceEvidenceRefs.size()
                    || signedAt == null || Instant.EPOCH.equals(signedAt)
                    || keyId.isBlank() || !"Ed25519".equals(algorithm)
                    || signature.isBlank() || !independentlyVerifiable) {
                throw new IllegalArgumentException("Verified trend attestation is incomplete");
            }
        }
    }

    /** Freezes all collections and verifies every producer-internal identity invariant. */
    public TestSuiteStabilityTrendAnalysis {
        schemaVersion = normalized(schemaVersion);
        trendAnalysisId = normalized(trendAnalysisId);
        evidenceFingerprint = normalized(evidenceFingerprint);
        sources = sources == null ? List.of() : List.copyOf(sources);
        caseTrends = caseTrends == null ? List.of() : List.copyOf(caseTrends);
        correlationSignals = correlationSignals == null
                ? List.of() : List.copyOf(correlationSignals);
        diagnostics = sorted(diagnostics);
        if (!TestingProtocol.TEST_SUITE_STABILITY_TREND_ANALYSIS_RESPONSE_V1
                .equals(schemaVersion)
                || !trendAnalysisId.matches("stability-trend-[0-9a-f]{64}")
                || !fingerprint(evidenceFingerprint) || request == null
                || observedRuns != sources.size() || observedRuns > request.maximumRuns()
                || expiredMatchingRuns < 0 || status == null || evaluatedAt == null
                || evaluatedAt.isBefore(request.toExclusive())
                || attestation == null || rawResponse == null || !rawResponse.isObject()
                || !trendAnalysisId.equals(attestation.trendAnalysisId())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !request.requestFingerprint().equals(attestation.requestFingerprint())
                || completeWindow != (expiredMatchingRuns == 0
                && !diagnostics.contains("SOURCE_WINDOW_TRUNCATED"))
                || sources.stream().map(SourceObservation::stabilityRunId).distinct().count()
                != sources.size()
                || !chronological(sources)
                || sources.stream().anyMatch(value ->
                value.createdAt().isBefore(request.fromInclusive())
                        || !value.createdAt().isBefore(request.toExclusive()))
                || caseTrends.stream().map(CaseTrend::caseId).distinct().count()
                != caseTrends.size()
                || status != Status.INCONCLUSIVE
                && (!completeWindow || observedRuns < request.minimumRuns())) {
            throw new IllegalArgumentException("Complete consistent stability trend is required");
        }
        List<SourceEvidenceRef> expectedClosure = sources.stream().map(source ->
                new SourceEvidenceRef(source.stabilityRunId(), source.evidenceFingerprint(),
                        source.attestationFingerprint())).toList();
        if (!expectedClosure.equals(attestation.sourceEvidenceRefs())) {
            throw new IllegalArgumentException("Trend source closure is inconsistent");
        }
        rawResponse = rawResponse.deepCopy();
    }

    /**
     * Decodes one strict response after authoritative JSON Schema validation.
     *
     * @param response complete trend analysis response
     * @return immutable typed projection
     */
    public static TestSuiteStabilityTrendAnalysis from(JsonNode response) {
        TestingProtocolSchemaValidator.require(
                response, "testSuiteStabilityTrendAnalysisResponse");
        JsonNode evidence = response.path("evidence");
        JsonNode suite = evidence.path("suiteRef");
        TestSuiteStabilityTrendRequest request = new TestSuiteStabilityTrendRequest(
                suite.path("suiteId").asText(), suite.path("revision").asLong(),
                suite.path("fingerprint").asText(), instant(evidence.path("fromInclusive")),
                instant(evidence.path("toExclusive")), evidence.path("minimumRuns").asInt(),
                evidence.path("maximumRuns").asInt());
        if (!request.requestFingerprint().equals(evidence.path("requestFingerprint").asText())) {
            throw new IllegalArgumentException("Trend request fingerprint is inconsistent");
        }
        List<SourceObservation> sources = new ArrayList<>();
        evidence.path("sources").forEach(source -> {
            List<CaseSnapshot> cases = new ArrayList<>();
            source.path("cases").forEach(value -> cases.add(new CaseSnapshot(
                    value.path("caseId").asText(), enumValue(TestSuiteStabilityRun.CaseStatus.class,
                    value.path("status").asText(), "source case status"),
                    value.path("outcomeSetFingerprint").asText(),
                    value.path("fixtureSetFingerprint").asText(),
                    value.path("planSetFingerprint").asText())));
            sources.add(new SourceObservation(source.path("stabilityRunId").asText(),
                    source.path("evidenceFingerprint").asText(),
                    source.path("attestationFingerprint").asText(),
                    source.path("evidenceSchemaVersion").asText(),
                    source.path("targetFingerprint").asText(),
                    enumValue(TestSuiteStabilityRun.Status.class,
                            source.path("status").asText(), "source status"),
                    enumValue(TestSuiteStabilityRun.PromotionStatus.class,
                            source.path("promotionStatus").asText(), "source promotion"),
                    enumValue(TestSuiteStabilityRun.QuarantineStatus.class,
                            source.path("quarantineStatus").asText(), "source quarantine"),
                    nullableEnum(TestSuiteStabilityRun.StatisticalStatus.class,
                            source.path("statisticalStatus"), "source statistical status"),
                    source.path("regimeFingerprint").asText(), cases,
                    instant(source.path("startedAt")), instant(source.path("completedAt")),
                    instant(source.path("createdAt"))));
        });
        List<CaseTrend> trends = new ArrayList<>();
        evidence.path("caseTrends").forEach(value -> trends.add(new CaseTrend(
                value.path("caseId").asText(), enumValue(CaseTrendStatus.class,
                value.path("status").asText(), "case trend status"),
                strings(value.path("sourceRunIds")), strings(value.path("changedAtRunIds")),
                value.path("regimeCount").asInt())));
        List<CorrelationSignal> signals = new ArrayList<>();
        evidence.path("correlationSignals").forEach(value -> signals.add(new CorrelationSignal(
                enumValue(CorrelationSignalType.class, value.path("type").asText(),
                        "correlation type"), value.path("previousRunId").asText(),
                value.path("currentRunId").asText(), value.path("regimeFingerprint").asText(),
                strings(value.path("caseIds")))));
        JsonNode seal = response.path("attestation");
        if (!"VERIFIED".equals(seal.path("signatureStatus").asText())) {
            throw new IllegalArgumentException("Verified trend attestation is required");
        }
        List<SourceEvidenceRef> closure = new ArrayList<>();
        seal.path("sourceEvidenceRefs").forEach(value -> closure.add(new SourceEvidenceRef(
                value.path("stabilityRunId").asText(),
                value.path("evidenceFingerprint").asText(),
                value.path("attestationFingerprint").asText())));
        Attestation attestation = new Attestation(seal.path("schemaVersion").asText(),
                seal.path("trendAnalysisId").asText(), seal.path("requestFingerprint").asText(),
                seal.path("evidenceFingerprint").asText(), closure,
                instant(seal.path("signedAt")), seal.path("keyId").asText(),
                seal.path("algorithm").asText(), seal.path("signature").asText(),
                seal.path("independentlyVerifiable").asBoolean(false));
        String actualFingerprint = EvidenceVerificationSupport.sha256(evidence);
        if (!response.path("trendAnalysisId").asText().equals(
                evidence.path("trendAnalysisId").asText())
                || !actualFingerprint.equals(response.path("evidenceFingerprint").asText())) {
            throw new IllegalArgumentException("Trend evidence fingerprint is invalid");
        }
        return new TestSuiteStabilityTrendAnalysis(response.path("schemaVersion").asText(),
                response.path("trendAnalysisId").asText(), actualFingerprint, request,
                evidence.path("observedRuns").asInt(), evidence.path("expiredMatchingRuns").asInt(),
                evidence.path("completeWindow").asBoolean(), enumValue(Status.class,
                evidence.path("status").asText(), "trend status"), sources, trends, signals,
                strings(evidence.path("diagnostics")), instant(evidence.path("evaluatedAt")),
                attestation, response);
    }

    /**
     * Returns a defensive copy of the complete authorized response.
     *
     * @return defensive schema-validated protocol response
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse.deepCopy();
    }

    private static boolean chronological(List<SourceObservation> values) {
        Comparator<SourceObservation> order = Comparator.comparing(SourceObservation::createdAt)
                .thenComparing(SourceObservation::stabilityRunId);
        for (int index = 1; index < values.size(); index++) {
            if (order.compare(values.get(index - 1), values.get(index)) > 0) {
                return false;
            }
        }
        return true;
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<String> sorted(List<String> values) {
        List<String> result = new ArrayList<>(new LinkedHashSet<>(
                values == null ? List.of() : values));
        result.replaceAll(TestSuiteStabilityTrendAnalysis::normalized);
        result.removeIf(String::isBlank);
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Trend timestamp is invalid");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, normalized(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unknown trend " + field);
        }
    }

    private static <E extends Enum<E>> E nullableEnum(
            Class<E> type, JsonNode value, String field) {
        return value == null || value.isMissingNode() || value.isNull()
                ? null : enumValue(type, value.asText(), field);
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}

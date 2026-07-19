package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Strict payload-free projection of one signed compact-observation ledger range trend.
 *
 * <p>Construction validates the authoritative Schema, exact request identity, every canonical
 * content fingerprint, floor/head/cursor/page closure, and detached-signature references. It does
 * not trust observation signatures, the outer signature, or producer trend labels; use
 * {@link TestSuiteStabilityCrossRetentionTrendEvidenceVerifier} before consuming the result as
 * evidence.</p>
 *
 * @param schemaVersion exact response generation
 * @param trendAnalysisId deterministic range trend identity
 * @param evidenceFingerprint canonical complete evidence identity
 * @param request exact request embedded in signed evidence
 * @param observedRuns number of compact observations in this page
 * @param sourceOrder deterministic source order used for trend projection
 * @param range producer floor/head/cursor-pinned ledger page
 * @param status producer aggregate trend label
 * @param caseTrends producer per-case trend labels
 * @param correlationSignals bounded producer non-causal signals
 * @param diagnostics sorted bounded producer reasons
 * @param evaluatedAt producer database observation boundary
 * @param attestation detached outer range signature
 * @param rawResponse defensive schema-validated response
 */
public record TestSuiteStabilityCrossRetentionTrendAnalysis(
        String schemaVersion,
        String trendAnalysisId,
        String evidenceFingerprint,
        TestSuiteStabilityCrossRetentionTrendRequest request,
        int observedRuns,
        SourceOrder sourceOrder,
        LedgerRange range,
        TestSuiteStabilityTrendAnalysis.Status status,
        List<TestSuiteStabilityTrendAnalysis.CaseTrend> caseTrends,
        List<TestSuiteStabilityTrendAnalysis.CorrelationSignal> correlationSignals,
        List<String> diagnostics,
        Instant evaluatedAt,
        Attestation attestation,
        JsonNode rawResponse
) {
    /** Only supported deterministic source ordering generation. */
    public enum SourceOrder {
        /** Sort by signed source creation time, then stable run identity. */
        SOURCE_CREATED_AT_THEN_STABILITY_RUN_ID
    }

    /**
     * Immutable exact suite coordinate repeated in producer range material.
     *
     * @param suiteId immutable suite id
     * @param revision immutable positive revision
     * @param fingerprint immutable suite content identity
     */
    public record SuiteRef(String suiteId, long revision, String fingerprint) {
        /** Validates one complete immutable suite coordinate. */
        public SuiteRef {
            suiteId = normalized(suiteId);
            fingerprint = normalized(fingerprint);
            if (suiteId.isBlank() || suiteId.length() > 255 || revision < 1
                    || !TestSuiteStabilityCrossRetentionTrendAnalysis
                    .fingerprint(fingerprint)) {
                throw new IllegalArgumentException("Cross-retention suite reference is incomplete");
            }
        }

        private boolean matches(TestSuiteStabilityCrossRetentionTrendRequest value) {
            return suiteId.equals(value.suiteId()) && revision == value.revision()
                    && fingerprint.equals(value.fingerprint());
        }
    }

    /**
     * Verified-shape compact-observation signature manifest.
     *
     * @param schemaVersion exact signature generation
     * @param observationId deterministic observation identity
     * @param observationFingerprint canonical observation evidence identity
     * @param sourceEvidenceFingerprint original stability evidence identity
     * @param sourceAttestationFingerprint original stability attestation identity
     * @param signedAt signature material time
     * @param keyId public verification key id
     * @param algorithm detached signature algorithm
     * @param signature base64 detached signature
     */
    public record ObservationAttestation(
            String schemaVersion,
            String observationId,
            String observationFingerprint,
            String sourceEvidenceFingerprint,
            String sourceAttestationFingerprint,
            Instant signedAt,
            String keyId,
            String algorithm,
            String signature
    ) {
        /** Rejects incomplete or non-verifiable compact-observation manifests. */
        public ObservationAttestation {
            schemaVersion = normalized(schemaVersion);
            observationId = normalized(observationId);
            observationFingerprint = normalized(observationFingerprint);
            sourceEvidenceFingerprint = normalized(sourceEvidenceFingerprint);
            sourceAttestationFingerprint = normalized(sourceAttestationFingerprint);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_ATTESTATION_V1
                    .equals(schemaVersion)
                    || !TestSuiteStabilityCrossRetentionTrendAnalysis
                    .observationId(observationId)
                    || !fingerprint(observationFingerprint)
                    || !fingerprint(sourceEvidenceFingerprint)
                    || !fingerprint(sourceAttestationFingerprint)
                    || signedAt == null || Instant.EPOCH.equals(signedAt) || keyId.isBlank()
                    || !"Ed25519".equals(algorithm) || signature.isBlank()) {
                throw new IllegalArgumentException(
                        "Verified compact-observation attestation is incomplete");
            }
        }
    }

    /**
     * One independently signed compact stability observation.
     *
     * @param evidenceFingerprint canonical observation evidence identity
     * @param observationId deterministic observation identity
     * @param scopeFingerprint payload-free producer scope identity
     * @param suiteRef exact immutable suite revision
     * @param sourceRequestFingerprint original stability request identity
     * @param source payload-free source projection
     * @param attestationFingerprint canonical complete observation attestation identity
     * @param attestation detached observation signature
     */
    public record Observation(
            String evidenceFingerprint,
            String observationId,
            String scopeFingerprint,
            SuiteRef suiteRef,
            String sourceRequestFingerprint,
            TestSuiteStabilityTrendAnalysis.SourceObservation source,
            String attestationFingerprint,
            ObservationAttestation attestation
    ) {
        /** Validates cross-object observation identity closure without trusting the signature. */
        public Observation {
            evidenceFingerprint = normalized(evidenceFingerprint);
            observationId = normalized(observationId);
            scopeFingerprint = normalized(scopeFingerprint);
            sourceRequestFingerprint = normalized(sourceRequestFingerprint);
            attestationFingerprint = normalized(attestationFingerprint);
            if (!fingerprint(evidenceFingerprint)
                    || !TestSuiteStabilityCrossRetentionTrendAnalysis
                    .observationId(observationId)
                    || !fingerprint(scopeFingerprint) || suiteRef == null
                    || !fingerprint(sourceRequestFingerprint) || source == null
                    || !fingerprint(attestationFingerprint) || attestation == null
                    || !observationId.equals(attestation.observationId())
                    || !evidenceFingerprint.equals(attestation.observationFingerprint())
                    || !source.evidenceFingerprint().equals(
                    attestation.sourceEvidenceFingerprint())
                    || !source.attestationFingerprint().equals(
                    attestation.sourceAttestationFingerprint())) {
                throw new IllegalArgumentException("Compact observation closure is inconsistent");
            }
        }
    }

    /**
     * One producer-ordered compact-observation ledger envelope.
     *
     * @param schemaVersion exact entry generation
     * @param scopeFingerprint payload-free producer scope identity
     * @param sequence one-based contiguous producer sequence
     * @param previousObservationId predecessor observation id; blank only for sequence one
     * @param observation signed compact observation
     * @param appendedAt producer database append time
     * @param entryFingerprint canonical entry identity excluding itself
     */
    public record LedgerEntry(
            String schemaVersion,
            String scopeFingerprint,
            long sequence,
            String previousObservationId,
            Observation observation,
            Instant appendedAt,
            String entryFingerprint
    ) {
        /** Validates one bounded chain coordinate without trusting producer ordering facts. */
        public LedgerEntry {
            schemaVersion = normalized(schemaVersion);
            scopeFingerprint = normalized(scopeFingerprint);
            previousObservationId = normalized(previousObservationId);
            entryFingerprint = normalized(entryFingerprint);
            boolean predecessor = sequence == 1 ? previousObservationId.isBlank()
                    : observationId(previousObservationId);
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_ENTRY_V1
                    .equals(schemaVersion) || !fingerprint(scopeFingerprint) || sequence < 1
                    || !predecessor || observation == null
                    || !scopeFingerprint.equals(observation.scopeFingerprint())
                    || appendedAt == null || Instant.EPOCH.equals(appendedAt)
                    || !fingerprint(entryFingerprint)) {
                throw new IllegalArgumentException("Observation ledger entry is incomplete");
            }
        }
    }

    /**
     * Producer-authoritative committed ledger head.
     *
     * @param schemaVersion exact head generation
     * @param scopeFingerprint payload-free producer scope identity
     * @param suiteRef exact immutable suite revision
     * @param coverageFrom first retained producer append time
     * @param latestSequence latest committed sequence
     * @param latestObservationId latest committed observation id
     * @param latestEntryFingerprint latest committed entry identity
     * @param updatedAt producer database head time
     * @param headFingerprint canonical head identity excluding itself
     */
    public record LedgerHead(
            String schemaVersion,
            String scopeFingerprint,
            SuiteRef suiteRef,
            Instant coverageFrom,
            long latestSequence,
            String latestObservationId,
            String latestEntryFingerprint,
            Instant updatedAt,
            String headFingerprint
    ) {
        /** Validates one complete non-empty committed head. */
        public LedgerHead {
            schemaVersion = normalized(schemaVersion);
            scopeFingerprint = normalized(scopeFingerprint);
            latestObservationId = normalized(latestObservationId);
            latestEntryFingerprint = normalized(latestEntryFingerprint);
            headFingerprint = normalized(headFingerprint);
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_HEAD_V1
                    .equals(schemaVersion) || !fingerprint(scopeFingerprint) || suiteRef == null
                    || coverageFrom == null || Instant.EPOCH.equals(coverageFrom)
                    || latestSequence < 1 || !observationId(latestObservationId)
                    || !fingerprint(latestEntryFingerprint) || updatedAt == null
                    || updatedAt.isBefore(coverageFrom) || !fingerprint(headFingerprint)) {
                throw new IllegalArgumentException("Observation ledger head is incomplete");
            }
        }
    }

    /**
     * Exact producer floor/head/cursor-pinned ledger page.
     *
     * @param schemaVersion exact range generation
     * @param scopeFingerprint payload-free producer scope identity
     * @param suiteRef exact immutable suite revision
     * @param floorSequence first retained sequence
     * @param floorPreviousObservationId predecessor retired before the floor
     * @param floorPreviousEntryFingerprint predecessor entry identity
     * @param floorObservationId first retained observation identity
     * @param floorEntryFingerprint first retained entry identity
     * @param head committed head pinned for this page
     * @param afterSequence exclusive request cursor
     * @param previousObservationId observation at the cursor
     * @param previousEntryFingerprint entry identity at the cursor
     * @param entries bounded contiguous page
     * @param hasMore whether the pinned head contains another entry
     * @param observedAt producer database snapshot time
     * @param rangeFingerprint canonical whole-range identity excluding itself
     */
    public record LedgerRange(
            String schemaVersion,
            String scopeFingerprint,
            SuiteRef suiteRef,
            long floorSequence,
            String floorPreviousObservationId,
            String floorPreviousEntryFingerprint,
            String floorObservationId,
            String floorEntryFingerprint,
            LedgerHead head,
            long afterSequence,
            String previousObservationId,
            String previousEntryFingerprint,
            List<LedgerEntry> entries,
            boolean hasMore,
            Instant observedAt,
            String rangeFingerprint
    ) {
        /** Validates floor, head, predecessor, page, and visible chain closure. */
        public LedgerRange {
            schemaVersion = normalized(schemaVersion);
            scopeFingerprint = normalized(scopeFingerprint);
            floorPreviousObservationId = normalized(floorPreviousObservationId);
            floorPreviousEntryFingerprint = normalized(floorPreviousEntryFingerprint);
            floorObservationId = normalized(floorObservationId);
            floorEntryFingerprint = normalized(floorEntryFingerprint);
            previousObservationId = normalized(previousObservationId);
            previousEntryFingerprint = normalized(previousEntryFingerprint);
            entries = entries == null ? List.of() : List.copyOf(entries);
            rangeFingerprint = normalized(rangeFingerprint);
            boolean rolloutFloor = floorSequence == 1
                    && floorPreviousObservationId.isBlank()
                    && floorPreviousEntryFingerprint.isBlank();
            boolean retiredFloor = floorSequence > 1
                    && observationId(floorPreviousObservationId)
                    && fingerprint(floorPreviousEntryFingerprint);
            boolean base = TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_RANGE_V1
                    .equals(schemaVersion) && fingerprint(scopeFingerprint) && suiteRef != null
                    && (rolloutFloor || retiredFloor) && observationId(floorObservationId)
                    && fingerprint(floorEntryFingerprint) && head != null
                    && scopeFingerprint.equals(head.scopeFingerprint())
                    && suiteRef.equals(head.suiteRef()) && floorSequence <= head.latestSequence()
                    && afterSequence >= floorSequence - 1
                    && afterSequence <= head.latestSequence() && entries.size() <= 100
                    && observedAt != null && !observedAt.isBefore(head.updatedAt())
                    && fingerprint(rangeFingerprint);
            boolean predecessor = afterSequence == floorSequence - 1
                    ? previousObservationId.equals(floorPreviousObservationId)
                    && previousEntryFingerprint.equals(floorPreviousEntryFingerprint)
                    : observationId(previousObservationId)
                    && fingerprint(previousEntryFingerprint);
            if (!base || !predecessor || !validEntries(entries, scopeFingerprint,
                    afterSequence, previousObservationId, observedAt)) {
                throw new IllegalArgumentException("Observation ledger range is inconsistent");
            }
            long last = entries.isEmpty() ? afterSequence : entries.getLast().sequence();
            if (entries.isEmpty() ? afterSequence != head.latestSequence() || hasMore
                    : hasMore != (last < head.latestSequence())
                    || !visible(entries, floorSequence, floorObservationId,
                    floorEntryFingerprint)
                    || !visible(entries, head.latestSequence(), head.latestObservationId(),
                    head.latestEntryFingerprint())) {
                throw new IllegalArgumentException("Observation ledger page closure is invalid");
            }
        }
    }

    /**
     * Exact compact-observation coordinate bound by the outer signature.
     *
     * @param sequence producer ledger sequence
     * @param observationId compact observation identity
     * @param observationFingerprint compact evidence identity
     * @param observationAttestationFingerprint compact signature-object identity
     * @param entryFingerprint producer entry identity
     */
    public record ObservationRef(
            long sequence,
            String observationId,
            String observationFingerprint,
            String observationAttestationFingerprint,
            String entryFingerprint
    ) {
        /** Validates one complete ordered observation reference. */
        public ObservationRef {
            observationId = normalized(observationId);
            observationFingerprint = normalized(observationFingerprint);
            observationAttestationFingerprint = normalized(
                    observationAttestationFingerprint);
            entryFingerprint = normalized(entryFingerprint);
            if (sequence < 1
                    || !TestSuiteStabilityCrossRetentionTrendAnalysis
                    .observationId(observationId)
                    || !fingerprint(observationFingerprint)
                    || !fingerprint(observationAttestationFingerprint)
                    || !fingerprint(entryFingerprint)) {
                throw new IllegalArgumentException("Observation reference is incomplete");
            }
        }
    }

    /**
     * Verified-shape outer signature over evidence, range, and exact observation references.
     *
     * @param schemaVersion exact signature generation
     * @param trendAnalysisId exact trend identity
     * @param requestFingerprint canonical embedded request identity
     * @param evidenceFingerprint canonical complete evidence identity
     * @param rangeFingerprint canonical complete range identity
     * @param observationRefs exact ledger-ordered source closure
     * @param signedAt signature material time
     * @param keyId public verification key id
     * @param algorithm detached signature algorithm
     * @param signature base64 detached signature
     */
    public record Attestation(
            String schemaVersion,
            String trendAnalysisId,
            String requestFingerprint,
            String evidenceFingerprint,
            String rangeFingerprint,
            List<ObservationRef> observationRefs,
            Instant signedAt,
            String keyId,
            String algorithm,
            String signature
    ) {
        /** Rejects incomplete or non-verifiable outer manifests. */
        public Attestation {
            schemaVersion = normalized(schemaVersion);
            trendAnalysisId = normalized(trendAnalysisId);
            requestFingerprint = normalized(requestFingerprint);
            evidenceFingerprint = normalized(evidenceFingerprint);
            rangeFingerprint = normalized(rangeFingerprint);
            observationRefs = observationRefs == null ? List.of() : List.copyOf(observationRefs);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            if (!TestingProtocol.TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_ATTESTATION_V1
                    .equals(schemaVersion) || !trendId(trendAnalysisId)
                    || !fingerprint(requestFingerprint) || !fingerprint(evidenceFingerprint)
                    || !fingerprint(rangeFingerprint) || !ordered(observationRefs)
                    || signedAt == null || Instant.EPOCH.equals(signedAt) || keyId.isBlank()
                    || !"Ed25519".equals(algorithm) || signature.isBlank()) {
                throw new IllegalArgumentException(
                        "Verified cross-retention trend attestation is incomplete");
            }
        }
    }

    /** Freezes collections and verifies every unsigned producer-internal invariant. */
    public TestSuiteStabilityCrossRetentionTrendAnalysis {
        schemaVersion = normalized(schemaVersion);
        trendAnalysisId = normalized(trendAnalysisId);
        evidenceFingerprint = normalized(evidenceFingerprint);
        caseTrends = caseTrends == null ? List.of() : List.copyOf(caseTrends);
        correlationSignals = correlationSignals == null
                ? List.of() : List.copyOf(correlationSignals);
        diagnostics = sorted(diagnostics);
        if (!TestingProtocol.TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_RESPONSE_V1
                .equals(schemaVersion) || !trendId(trendAnalysisId)
                || !fingerprint(evidenceFingerprint) || request == null || observedRuns < 0
                || observedRuns > request.maximumRuns() || sourceOrder == null || range == null
                || !range.suiteRef().matches(request)
                || request.afterSequence() != range.afterSequence()
                || (!request.expectedHeadFingerprint().isBlank()
                && !request.expectedHeadFingerprint().equals(range.head().headFingerprint()))
                || observedRuns != range.entries().size() || status == null
                || caseTrends.stream().map(TestSuiteStabilityTrendAnalysis.CaseTrend::caseId)
                .distinct().count() != caseTrends.size()
                || evaluatedAt == null || !evaluatedAt.equals(range.observedAt())
                || attestation == null || rawResponse == null || !rawResponse.isObject()
                || !trendAnalysisId.equals(attestation.trendAnalysisId())
                || !request.requestFingerprint().equals(attestation.requestFingerprint())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !range.rangeFingerprint().equals(attestation.rangeFingerprint())
                || status != TestSuiteStabilityTrendAnalysis.Status.INCONCLUSIVE
                && observedRuns < request.minimumRuns()) {
            throw new IllegalArgumentException(
                    "Complete consistent cross-retention trend is required");
        }
        List<ObservationRef> closure = range.entries().stream().map(entry ->
                new ObservationRef(entry.sequence(), entry.observation().observationId(),
                        entry.observation().evidenceFingerprint(),
                        entry.observation().attestationFingerprint(),
                        entry.entryFingerprint())).toList();
        if (!closure.equals(attestation.observationRefs())) {
            throw new IllegalArgumentException("Cross-retention source closure is inconsistent");
        }
        rawResponse = rawResponse.deepCopy();
    }

    /**
     * Decodes one response after strict Schema and canonical fingerprint verification.
     *
     * @param response complete server response
     * @return immutable typed projection
     */
    public static TestSuiteStabilityCrossRetentionTrendAnalysis from(JsonNode response) {
        TestingProtocolSchemaValidator.require(
                response, "testSuiteStabilityCrossRetentionTrendAnalysisResponse");
        JsonNode evidence = response.path("evidence");
        JsonNode requestJson = evidence.path("request");
        JsonNode requestSuite = requestJson.path("suiteRef");
        TestSuiteStabilityCrossRetentionTrendRequest request =
                new TestSuiteStabilityCrossRetentionTrendRequest(
                        requestSuite.path("suiteId").asText(),
                        requestSuite.path("revision").asLong(),
                        requestSuite.path("fingerprint").asText(),
                        requestJson.path("afterSequence").asLong(),
                        requestJson.path("minimumRuns").asInt(),
                        requestJson.path("maximumRuns").asInt(),
                        requestJson.path("expectedHeadFingerprint").asText());
        if (!request.requestFingerprint().equals(
                evidence.path("requestFingerprint").asText())) {
            throw new IllegalArgumentException(
                    "Cross-retention request fingerprint is inconsistent");
        }
        LedgerRange range = range(evidence.path("range"));
        requireCanonicalFingerprints(response);
        List<TestSuiteStabilityTrendAnalysis.CaseTrend> trends = new ArrayList<>();
        evidence.path("caseTrends").forEach(value -> trends.add(
                new TestSuiteStabilityTrendAnalysis.CaseTrend(
                        value.path("caseId").asText(),
                        enumValue(TestSuiteStabilityTrendAnalysis.CaseTrendStatus.class,
                                value.path("status").asText(), "case trend status"),
                        strings(value.path("sourceRunIds")),
                        strings(value.path("changedAtRunIds")),
                        value.path("regimeCount").asInt())));
        List<TestSuiteStabilityTrendAnalysis.CorrelationSignal> signals = new ArrayList<>();
        evidence.path("correlationSignals").forEach(value -> signals.add(
                new TestSuiteStabilityTrendAnalysis.CorrelationSignal(
                        enumValue(TestSuiteStabilityTrendAnalysis.CorrelationSignalType.class,
                                value.path("type").asText(), "correlation type"),
                        value.path("previousRunId").asText(),
                        value.path("currentRunId").asText(),
                        value.path("regimeFingerprint").asText(),
                        strings(value.path("caseIds")))));
        JsonNode seal = response.path("attestation");
        List<ObservationRef> refs = new ArrayList<>();
        seal.path("observationRefs").forEach(value -> refs.add(new ObservationRef(
                value.path("sequence").asLong(), value.path("observationId").asText(),
                value.path("observationFingerprint").asText(),
                value.path("observationAttestationFingerprint").asText(),
                value.path("entryFingerprint").asText())));
        Attestation attestation = new Attestation(
                seal.path("schemaVersion").asText(), seal.path("trendAnalysisId").asText(),
                seal.path("requestFingerprint").asText(),
                seal.path("evidenceFingerprint").asText(),
                seal.path("rangeFingerprint").asText(), refs,
                instant(seal.path("signedAt")), seal.path("keyId").asText(),
                seal.path("algorithm").asText(), seal.path("signature").asText());
        String evidenceFingerprint = EvidenceVerificationSupport.sha256(evidence);
        if (!response.path("trendAnalysisId").asText().equals(
                evidence.path("trendAnalysisId").asText())
                || !evidenceFingerprint.equals(
                response.path("evidenceFingerprint").asText())) {
            throw new IllegalArgumentException(
                    "Cross-retention trend evidence fingerprint is invalid");
        }
        return new TestSuiteStabilityCrossRetentionTrendAnalysis(
                response.path("schemaVersion").asText(),
                response.path("trendAnalysisId").asText(), evidenceFingerprint, request,
                evidence.path("observedRuns").asInt(), enumValue(SourceOrder.class,
                evidence.path("sourceOrder").asText(), "source order"), range,
                enumValue(TestSuiteStabilityTrendAnalysis.Status.class,
                        evidence.path("status").asText(), "aggregate status"),
                trends, signals, strings(evidence.path("diagnostics")),
                instant(evidence.path("evaluatedAt")), attestation, response);
    }

    /**
     * Returns a defensive copy of the complete authorized response.
     *
     * @return defensive strict protocol JSON
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse.deepCopy();
    }

    private static LedgerRange range(JsonNode value) {
        SuiteRef suite = suite(value.path("suiteRef"));
        JsonNode headValue = value.path("head");
        LedgerHead head = new LedgerHead(
                headValue.path("schemaVersion").asText(),
                headValue.path("scopeFingerprint").asText(), suite(headValue.path("suiteRef")),
                instant(headValue.path("coverageFrom")),
                headValue.path("latestSequence").asLong(),
                headValue.path("latestObservationId").asText(),
                headValue.path("latestEntryFingerprint").asText(),
                instant(headValue.path("updatedAt")),
                headValue.path("headFingerprint").asText());
        List<LedgerEntry> entries = new ArrayList<>();
        value.path("entries").forEach(entry -> entries.add(entry(entry)));
        return new LedgerRange(value.path("schemaVersion").asText(),
                value.path("scopeFingerprint").asText(), suite,
                value.path("floorSequence").asLong(),
                value.path("floorPreviousObservationId").asText(),
                value.path("floorPreviousEntryFingerprint").asText(),
                value.path("floorObservationId").asText(),
                value.path("floorEntryFingerprint").asText(), head,
                value.path("afterSequence").asLong(),
                value.path("previousObservationId").asText(),
                value.path("previousEntryFingerprint").asText(), entries,
                value.path("hasMore").asBoolean(), instant(value.path("observedAt")),
                value.path("rangeFingerprint").asText());
    }

    private static LedgerEntry entry(JsonNode value) {
        JsonNode observationValue = value.path("observation");
        JsonNode evidence = observationValue.path("evidence");
        JsonNode seal = observationValue.path("attestation");
        ObservationAttestation attestation = new ObservationAttestation(
                seal.path("schemaVersion").asText(), seal.path("observationId").asText(),
                seal.path("observationFingerprint").asText(),
                seal.path("sourceEvidenceFingerprint").asText(),
                seal.path("sourceAttestationFingerprint").asText(),
                instant(seal.path("signedAt")), seal.path("keyId").asText(),
                seal.path("algorithm").asText(), seal.path("signature").asText());
        Observation observation = new Observation(
                observationValue.path("evidenceFingerprint").asText(),
                evidence.path("observationId").asText(),
                evidence.path("scopeFingerprint").asText(), suite(evidence.path("suiteRef")),
                evidence.path("sourceRequestFingerprint").asText(),
                source(evidence.path("source")),
                observationValue.path("attestationFingerprint").asText(), attestation);
        return new LedgerEntry(value.path("schemaVersion").asText(),
                value.path("scopeFingerprint").asText(), value.path("sequence").asLong(),
                value.path("previousObservationId").asText(), observation,
                instant(value.path("appendedAt")), value.path("entryFingerprint").asText());
    }

    private static TestSuiteStabilityTrendAnalysis.SourceObservation source(JsonNode value) {
        List<TestSuiteStabilityTrendAnalysis.CaseSnapshot> cases = new ArrayList<>();
        value.path("cases").forEach(snapshot -> cases.add(
                new TestSuiteStabilityTrendAnalysis.CaseSnapshot(
                        snapshot.path("caseId").asText(),
                        enumValue(TestSuiteStabilityRun.CaseStatus.class,
                                snapshot.path("status").asText(), "source case status"),
                        snapshot.path("outcomeSetFingerprint").asText(),
                        snapshot.path("fixtureSetFingerprint").asText(),
                        snapshot.path("planSetFingerprint").asText())));
        return new TestSuiteStabilityTrendAnalysis.SourceObservation(
                value.path("stabilityRunId").asText(),
                value.path("evidenceFingerprint").asText(),
                value.path("attestationFingerprint").asText(),
                value.path("evidenceSchemaVersion").asText(),
                value.path("targetFingerprint").asText(),
                enumValue(TestSuiteStabilityRun.Status.class,
                        value.path("status").asText(), "source status"),
                enumValue(TestSuiteStabilityRun.PromotionStatus.class,
                        value.path("promotionStatus").asText(), "source promotion"),
                enumValue(TestSuiteStabilityRun.QuarantineStatus.class,
                        value.path("quarantineStatus").asText(), "source quarantine"),
                nullableEnum(TestSuiteStabilityRun.StatisticalStatus.class,
                        value.path("statisticalStatus"), "source statistical status"),
                value.path("regimeFingerprint").asText(), cases,
                instant(value.path("startedAt")), instant(value.path("completedAt")),
                instant(value.path("createdAt")));
    }

    private static SuiteRef suite(JsonNode value) {
        return new SuiteRef(value.path("suiteId").asText(), value.path("revision").asLong(),
                value.path("fingerprint").asText());
    }

    private static void requireCanonicalFingerprints(JsonNode response) {
        JsonNode range = response.path("evidence").path("range");
        for (JsonNode entry : range.path("entries")) {
            JsonNode observation = entry.path("observation");
            requireFingerprint(observation.path("evidenceFingerprint").asText(),
                    observation.path("evidence"), "observation evidence");
            requireFingerprint(observation.path("attestationFingerprint").asText(),
                    observation.path("attestation"), "observation attestation");
            requireFingerprint(entry.path("entryFingerprint").asText(),
                    without(entry, "entryFingerprint"), "observation entry");
        }
        requireFingerprint(range.path("head").path("headFingerprint").asText(),
                without(range.path("head"), "headFingerprint"), "observation head");
        requireFingerprint(range.path("rangeFingerprint").asText(),
                without(range, "rangeFingerprint"), "observation range");
    }

    private static void requireFingerprint(String expected, JsonNode material, String field) {
        if (!normalized(expected).equals(EvidenceVerificationSupport.sha256(material))) {
            throw new IllegalArgumentException(field + " fingerprint is invalid");
        }
    }

    private static JsonNode without(JsonNode value, String field) {
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        copy.remove(field);
        return copy;
    }

    private static boolean validEntries(
            List<LedgerEntry> entries,
            String scopeFingerprint,
            long afterSequence,
            String predecessor,
            Instant observedAt) {
        long expected = afterSequence + 1;
        String previous = predecessor;
        for (LedgerEntry entry : entries) {
            if (entry == null || entry.sequence() != expected
                    || !scopeFingerprint.equals(entry.scopeFingerprint())
                    || !previous.equals(entry.previousObservationId())
                    || entry.appendedAt().isAfter(observedAt)) {
                return false;
            }
            previous = entry.observation().observationId();
            expected++;
        }
        return true;
    }

    private static boolean visible(
            List<LedgerEntry> entries,
            long sequence,
            String observationId,
            String entryFingerprint) {
        return entries.stream().filter(value -> value.sequence() == sequence).findFirst()
                .map(value -> observationId.equals(value.observation().observationId())
                        && entryFingerprint.equals(value.entryFingerprint()))
                .orElse(true);
    }

    private static boolean ordered(List<ObservationRef> values) {
        long previous = -1;
        for (ObservationRef value : values) {
            if (value == null || value.sequence() <= previous) {
                return false;
            }
            previous = value.sequence();
        }
        return values.stream().map(ObservationRef::observationId).distinct().count()
                == values.size();
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static List<String> sorted(List<String> values) {
        List<String> result = new ArrayList<>(new LinkedHashSet<>(
                values == null ? List.of() : values));
        result.replaceAll(TestSuiteStabilityCrossRetentionTrendAnalysis::normalized);
        result.removeIf(String::isBlank);
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Cross-retention timestamp is invalid");
        }
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value,
            String field) {
        try {
            return Enum.valueOf(type, normalized(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unknown cross-retention " + field);
        }
    }

    private static <E extends Enum<E>> E nullableEnum(
            Class<E> type,
            JsonNode value,
            String field) {
        return value == null || value.isMissingNode() || value.isNull()
                ? null : enumValue(type, value.asText(), field);
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static boolean observationId(String value) {
        return normalized(value).matches("stability-observation-[0-9a-f]{64}");
    }

    private static boolean trendId(String value) {
        return normalized(value).matches("stability-cross-retention-trend-[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence.RunObservation;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityCrossRetentionTrendAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerRangeIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerEntryIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerHeadIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityTrendEvidenceEvaluator;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authorized range-pinned trend service over independently signed compact observations.
 *
 * <p>The service resolves suite classification before ledger access, reads a floor/head-pinned
 * range under the repository lock, verifies the range fingerprint and every compact observation
 * signature, rederives trend labels, and signs the exact range closure. It does not claim that
 * compact observations older than the retained floor remain available.</p>
 */
public final class TestSuiteStabilityCrossRetentionTrendAnalysisService {
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> ACCEPTED_PURPOSES = Set.of(
            "TEST_EXECUTION", "TEST_REPLAY");
    private static final List<String> CLASSIFICATIONS = List.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final TestSuiteRegistryService suiteRegistry;
    private final TestSuiteStabilityRunRepository repository;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityObservationAttestationService observationAttestations;
    private final TestSuiteStabilityCrossRetentionTrendAttestationService trendAttestations;
    private final TestSuiteStabilityTrendEvidenceEvaluator evaluator;

    /**
     * @param suiteRegistry immutable suite authorization boundary
     * @param repository compact-observation ledger authority
     * @param objectMapper canonical protocol mapper
     * @param observationAttestations compact observation signature verifier
     * @param trendAttestations range-closing trend signature boundary
     */
    public TestSuiteStabilityCrossRetentionTrendAnalysisService(
            TestSuiteRegistryService suiteRegistry,
            TestSuiteStabilityRunRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationAttestationService observationAttestations,
            TestSuiteStabilityCrossRetentionTrendAttestationService trendAttestations) {
        this.suiteRegistry = Objects.requireNonNull(suiteRegistry, "suiteRegistry");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.observationAttestations = Objects.requireNonNull(
                observationAttestations, "observationAttestations");
        this.trendAttestations = Objects.requireNonNull(
                trendAttestations, "trendAttestations");
        this.evaluator = new TestSuiteStabilityTrendEvidenceEvaluator(objectMapper);
    }

    /**
     * Produces one portable signed exact-suite observation-range trend.
     *
     * @param suiteId path-bound suite identity
     * @param request exact bounded cursor and optional head pin
     * @param identity verified test-runtime identity
     * @return signed payload-free range trend
     */
    public TestSuiteStabilityCrossRetentionTrendAnalysisResponse analyze(
            String suiteId,
            TestSuiteStabilityCrossRetentionTrendAnalysisRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        validateRequest(suiteId, request, identity);
        StoredTestSuite stored = suiteRegistry.find(
                request.suiteRef().suiteId(), request.suiteRef().revision(), identity);
        requireClearance(stored.suite().classification(), identity);
        if (!request.suiteRef().fingerprint().equals(stored.fingerprint())) {
            throw conflict(identity,
                    "RG.TEST.STABILITY_CROSS_RETENTION_SUITE_FINGERPRINT_CONFLICT",
                    "Stored suite differs from the exact cross-retention trend reference.");
        }

        TestSuiteStabilityObservationLedgerRange range;
        try {
            range = repository.observationRange(
                    identity.tenantId(), identity.environmentId(), request.suiteRef(),
                    request.afterSequence(), request.maximumRuns()).orElseThrow(() ->
                    notFound(identity, "RG.TEST.STABILITY_OBSERVATION_LEDGER_NOT_FOUND",
                            "No compact stability observation exists for this exact suite."));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IllegalArgumentException invalidCursor) {
            throw badRequest(identity, "RG.TEST.STABILITY_OBSERVATION_CURSOR_INVALID",
                    "The compact-observation cursor is outside the retained ledger.");
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.STABILITY_OBSERVATION_LEDGER_UNAVAILABLE",
                    "The compact stability observation ledger is unavailable.");
        }
        if (!TestSuiteStabilityObservationLedgerRangeIntegrity.valid(objectMapper, range)) {
            throw conflict(identity, "RG.TEST.STABILITY_OBSERVATION_RANGE_INVALID",
                    "The compact-observation range failed whole-record verification.");
        }
        if (!TestSuiteStabilityObservationLedgerHeadIntegrity.valid(
                objectMapper, range.head())) {
            throw conflict(identity, "RG.TEST.STABILITY_OBSERVATION_HEAD_INVALID",
                    "The compact-observation head failed whole-record verification.");
        }
        if (!request.expectedHeadFingerprint().isBlank()
                && !request.expectedHeadFingerprint().equals(
                range.head().headFingerprint())) {
            throw conflict(identity, "RG.TEST.STABILITY_OBSERVATION_HEAD_CHANGED",
                    "The compact-observation head changed after the caller pinned its snapshot.");
        }
        for (TestSuiteStabilityObservationLedgerEntry entry : range.entries()) {
            verifyObservation(entry, range, identity);
        }

        String requestFingerprint = ProtocolFingerprint.of(objectMapper, request);
        List<RunObservation> sources = range.entries().stream()
                .map(value -> value.observation().evidence().source())
                .sorted(Comparator.comparing(RunObservation::createdAt)
                        .thenComparing(RunObservation::stabilityRunId))
                .toList();
        TestSuiteStabilityTrendEvidenceEvaluator.Projection projection = evaluator.project(
                sources, request.minimumRuns(), true, List.of());
        String trendAnalysisId = "stability-cross-retention-trend-"
                + ProtocolFingerprint.of(objectMapper, new AnalysisIdentity(
                TestSuiteStabilityCrossRetentionTrendEvidence.SCHEMA_VERSION,
                requestFingerprint, range.rangeFingerprint()))
                .substring("sha256:".length());
        TestSuiteStabilityCrossRetentionTrendEvidence evidence =
                new TestSuiteStabilityCrossRetentionTrendEvidence(
                        TestSuiteStabilityCrossRetentionTrendEvidence.SCHEMA_VERSION,
                        trendAnalysisId, requestFingerprint, request, sources.size(),
                        TestSuiteStabilityCrossRetentionTrendEvidence.SourceOrder
                                .SOURCE_CREATED_AT_THEN_STABILITY_RUN_ID,
                        range,
                        projection.status(), projection.caseTrends(),
                        projection.correlationSignals(),
                        TestSuiteStabilityTrendEvidence.CausalityStatus.NOT_PROVEN,
                        projection.diagnostics(), range.observedAt());
        TestSuiteStabilityCrossRetentionTrendAttestationService.SealResult sealed =
                trendAttestations.seal(evidence);
        if (!sealed.verified()) {
            throw unavailable(identity,
                    "RG.TEST.STABILITY_CROSS_RETENTION_ATTESTATION_UNAVAILABLE",
                    "Signed cross-retention stability trend evidence could not be produced.");
        }
        return new TestSuiteStabilityCrossRetentionTrendAnalysisResponse(
                TestSuiteStabilityCrossRetentionTrendAnalysisResponse.SCHEMA_VERSION,
                trendAnalysisId, ProtocolFingerprint.of(objectMapper, evidence),
                evidence, sealed.attestation());
    }

    private void verifyObservation(
            TestSuiteStabilityObservationLedgerEntry entry,
            TestSuiteStabilityObservationLedgerRange range,
            IntegrationRequestContext identity) {
        TestSuiteStabilityObservationAttestationService.Verification verification =
                observationAttestations.verify(entry.observation());
        if (verification == TestSuiteStabilityObservationAttestationService.Verification
                .UNAVAILABLE) {
            throw unavailable(identity,
                    "RG.TEST.STABILITY_OBSERVATION_VERIFICATION_UNAVAILABLE",
                    "A compact stability observation signature cannot currently be verified.");
        }
        if (verification != TestSuiteStabilityObservationAttestationService.Verification.VERIFIED
                || !TestSuiteStabilityObservationLedgerEntryIntegrity.valid(
                objectMapper, entry)
                || !range.scopeFingerprint().equals(entry.scopeFingerprint())
                || !range.scopeFingerprint().equals(
                entry.observation().evidence().scopeFingerprint())
                || !range.suiteRef().equals(entry.observation().evidence().suiteRef())) {
            throw conflict(identity, "RG.TEST.STABILITY_OBSERVATION_INVALID",
                    "A compact stability observation failed integrity verification.");
        }
    }

    private static void validateRequest(
            String pathSuiteId,
            TestSuiteStabilityCrossRetentionTrendAnalysisRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !TestSuiteStabilityCrossRetentionTrendAnalysisRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || normalized(pathSuiteId).isBlank()
                || !normalized(pathSuiteId).equals(request.suiteRef().suiteId())
                || !FINGERPRINT.matcher(request.suiteRef().fingerprint()).matches()) {
            throw badRequest(identity,
                    "RG.TEST.STABILITY_CROSS_RETENTION_REQUEST_INVALID",
                    "A complete exact-suite cross-retention trend request is required.");
        }
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ACCEPTED_PURPOSES.contains(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.STABILITY_CROSS_RETENTION_PURPOSE_FORBIDDEN",
                    "Cross-retention trend analysis requires TEST_EXECUTION or TEST_REPLAY purpose.",
                    identity.correlationId(), Map.of()));
        }
        if (!ENABLED_ENVIRONMENTS.contains(
                identity.environmentId().toLowerCase(Locale.ROOT))) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Cross-retention trend analysis is restricted to test and staging identities.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireClearance(
            String classification,
            IntegrationRequestContext identity) {
        String required = normalized(classification).toUpperCase(Locale.ROOT);
        if (!CLASSIFICATIONS.contains(required)) {
            throw badRequest(identity, "RG.TEST.SUITE_CLASSIFICATION_INVALID",
                    "Suite classification is not recognized.");
        }
        if (!identity.hasClearanceAtLeast(required)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.SUITE_CLEARANCE_FORBIDDEN",
                    "Verified workload clearance cannot read this suite observation ledger.",
                    identity.correlationId(), Map.of("classification", required)));
        }
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String detail) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, detail, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String detail) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, detail, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity,
            String code,
            String detail) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                code, detail, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String detail) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, detail, identity.correlationId(), Map.of()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record AnalysisIdentity(
            String schemaVersion,
            String requestFingerprint,
            String rangeFingerprint) {
    }
}

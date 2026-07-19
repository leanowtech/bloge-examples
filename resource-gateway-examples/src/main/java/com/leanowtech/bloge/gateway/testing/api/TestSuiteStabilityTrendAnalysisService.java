package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityTrendAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityTrendEvidenceEvaluator;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authorized retained-window trend service over independently verified stability records.
 *
 * <p>The service resolves the immutable suite before history access, verifies every retained source
 * signature, derives the projection through a pure evaluator, and refuses to return unsigned trend
 * material. It does not mutate suite, quarantine, or publication state.</p>
 */
public final class TestSuiteStabilityTrendAnalysisService {
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> ACCEPTED_PURPOSES = Set.of("TEST_EXECUTION", "TEST_REPLAY");
    private static final List<String> CLASSIFICATIONS = List.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final TestSuiteRegistryService suiteRegistry;
    private final TestSuiteStabilityRunRepository repository;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityAttestationService sourceAttestations;
    private final TestSuiteStabilityTrendAttestationService trendAttestations;
    private final TestSuiteStabilityTrendEvidenceEvaluator evaluator;
    private final Duration maximumWindow;

    /**
     * @param suiteRegistry immutable suite authorization boundary
     * @param repository stability terminal and history store
     * @param objectMapper canonical protocol mapper
     * @param sourceAttestations source stability signature verifier
     * @param trendAttestations trend signature boundary
     * @param maximumWindow hard retained-history window bound
     */
    public TestSuiteStabilityTrendAnalysisService(
            TestSuiteRegistryService suiteRegistry,
            TestSuiteStabilityRunRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityAttestationService sourceAttestations,
            TestSuiteStabilityTrendAttestationService trendAttestations,
            Duration maximumWindow) {
        this.suiteRegistry = Objects.requireNonNull(suiteRegistry, "suiteRegistry");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.sourceAttestations = Objects.requireNonNull(sourceAttestations, "sourceAttestations");
        this.trendAttestations = Objects.requireNonNull(trendAttestations, "trendAttestations");
        this.evaluator = new TestSuiteStabilityTrendEvidenceEvaluator(objectMapper);
        this.maximumWindow = maximumWindow == null || maximumWindow.isNegative()
                || maximumWindow.isZero() ? Duration.ofDays(30) : maximumWindow;
    }

    /**
     * Produces one signed exact-suite trend projection.
     *
     * @param suiteId path-bound suite identity
     * @param request exact bounded retained-window intent
     * @param identity verified test-runtime identity
     * @return signed payload-free trend analysis
     */
    public TestSuiteStabilityTrendAnalysisResponse analyze(
            String suiteId,
            TestSuiteStabilityTrendAnalysisRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        validateRequest(suiteId, request, identity);
        StoredTestSuite stored = suiteRegistry.find(
                request.suiteRef().suiteId(), request.suiteRef().revision(), identity);
        requireClearance(stored.suite().classification(), identity);
        if (!request.suiteRef().fingerprint().equals(stored.fingerprint())) {
            throw conflict(identity, "RG.TEST.STABILITY_TREND_SUITE_FINGERPRINT_CONFLICT",
                    "Stored suite differs from the exact trend-analysis reference.");
        }
        Instant databaseNow;
        try {
            databaseNow = repository.currentTime();
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.STABILITY_TREND_STORE_UNAVAILABLE",
                    "The stability history authority is unavailable.");
        }
        if (request.toExclusive().isAfter(databaseNow)) {
            throw badRequest(identity, "RG.TEST.STABILITY_TREND_FUTURE_WINDOW",
                    "Trend analysis requires a closed non-future evidence window.");
        }
        if (Duration.between(request.fromInclusive(), request.toExclusive())
                .compareTo(maximumWindow) > 0) {
            throw badRequest(identity, "RG.TEST.STABILITY_TREND_WINDOW_TOO_LARGE",
                    "Trend analysis window exceeds the configured evidence-retention bound.");
        }

        TestSuiteStabilityHistoryWindow window;
        try {
            window = repository.history(identity.tenantId(), identity.environmentId(),
                    request.suiteRef(), request.fromInclusive(), request.toExclusive(),
                    request.maximumRuns());
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.STABILITY_TREND_STORE_UNAVAILABLE",
                    "The bounded stability history window is unavailable.");
        }
        for (TestSuiteStabilityRunRecord source : window.records()) {
            requireClearance(source.classification(), identity);
            verifySource(source, identity);
        }
        String requestFingerprint = ProtocolFingerprint.of(objectMapper, request);
        TestSuiteStabilityTrendEvidence evidence = evaluator.evaluate(
                identity.tenantId(), identity.environmentId(), request,
                requestFingerprint, window);
        TestSuiteStabilityTrendAttestationService.SealResult sealed =
                trendAttestations.seal(evidence);
        if (!sealed.verified()) {
            throw unavailable(identity, "RG.TEST.STABILITY_TREND_ATTESTATION_UNAVAILABLE",
                    "Signed stability trend evidence could not be produced.");
        }
        return new TestSuiteStabilityTrendAnalysisResponse(
                TestSuiteStabilityTrendAnalysisResponse.SCHEMA_VERSION,
                evidence.trendAnalysisId(),
                ProtocolFingerprint.of(objectMapper, evidence),
                evidence, sealed.attestation());
    }

    private void verifySource(
            TestSuiteStabilityRunRecord source,
            IntegrationRequestContext identity) {
        TestSuiteStabilityAttestationService.Verification verification =
                sourceAttestations.verify(source.evidence(), source.attestation());
        if (verification == TestSuiteStabilityAttestationService.Verification.UNAVAILABLE) {
            throw unavailable(identity, "RG.TEST.STABILITY_TREND_SOURCE_VERIFICATION_UNAVAILABLE",
                    "A source stability signature cannot currently be verified.");
        }
        String evidenceFingerprint;
        try {
            evidenceFingerprint = ProtocolFingerprint.of(objectMapper, source.evidence());
        } catch (RuntimeException invalid) {
            evidenceFingerprint = "";
        }
        if (verification != TestSuiteStabilityAttestationService.Verification.VERIFIED
                || !source.stabilityRunId().equals(source.evidence().stabilityRunId())
                || !source.requestFingerprint().equals(
                source.attestation().requestFingerprint())
                || !source.evidenceFingerprint().equals(evidenceFingerprint)
                || !source.evidenceFingerprint().equals(
                source.attestation().evidenceFingerprint())) {
            throw conflict(identity, "RG.TEST.STABILITY_TREND_SOURCE_INVALID",
                    "A retained stability source failed integrity verification.");
        }
    }

    private void validateRequest(
            String pathSuiteId,
            TestSuiteStabilityTrendAnalysisRequest request,
            IntegrationRequestContext identity) {
        if (request == null || !TestSuiteStabilityTrendAnalysisRequest.SCHEMA_VERSION.equals(
                request.schemaVersion()) || request.suiteRef() == null
                || normalized(pathSuiteId).isBlank()
                || !normalized(pathSuiteId).equals(request.suiteRef().suiteId())
                || request.suiteRef().revision() < 1
                || !FINGERPRINT.matcher(request.suiteRef().fingerprint()).matches()) {
            throw badRequest(identity, "RG.TEST.STABILITY_TREND_REQUEST_INVALID",
                    "A complete exact-suite trend-analysis request is required.");
        }
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ACCEPTED_PURPOSES.contains(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.STABILITY_TREND_PURPOSE_FORBIDDEN",
                    "Stability trend analysis requires TEST_EXECUTION or TEST_REPLAY purpose.",
                    identity.correlationId(), Map.of()));
        }
        if (!ENABLED_ENVIRONMENTS.contains(
                identity.environmentId().toLowerCase(Locale.ROOT))) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Stability trend analysis is restricted to test and staging identities.",
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
                    "Verified workload clearance cannot read this suite history.",
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
}

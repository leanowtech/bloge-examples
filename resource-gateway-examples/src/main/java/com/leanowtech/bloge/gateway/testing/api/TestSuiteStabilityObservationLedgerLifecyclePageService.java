package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecycleAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecyclePageIntegrity;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authorized, snapshot-pinned reader for an exact compact-observation floor lifecycle.
 *
 * <p>The service authorizes the immutable suite before ledger access, rejects stale continuation
 * pins, verifies every retirement signature, verifies the whole transition page, and finally signs
 * the exact page closure. It does not claim that the same-database archives are external WORM or
 * that a restored database cannot equivocate with an independently witnessed history.</p>
 */
public final class TestSuiteStabilityObservationLedgerLifecyclePageService {
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> ACCEPTED_PURPOSES = Set.of(
            "TEST_EXECUTION", "TEST_REPLAY");
    private static final List<String> CLASSIFICATIONS = List.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final TestSuiteRegistryService suiteRegistry;
    private final TestSuiteStabilityRunRepository repository;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityObservationFloorRetirementAttestationService
            retirementAttestations;
    private final TestSuiteStabilityObservationLedgerLifecycleAttestationService
            lifecycleAttestations;

    /**
     * @param suiteRegistry immutable suite authorization boundary
     * @param repository compact-observation lifecycle authority
     * @param objectMapper canonical protocol mapper
     * @param retirementAttestations retirement signature verifier
     * @param lifecycleAttestations page-closing signature boundary
     */
    public TestSuiteStabilityObservationLedgerLifecyclePageService(
            TestSuiteRegistryService suiteRegistry,
            TestSuiteStabilityRunRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationFloorRetirementAttestationService
                    retirementAttestations,
            TestSuiteStabilityObservationLedgerLifecycleAttestationService
                    lifecycleAttestations) {
        this.suiteRegistry = Objects.requireNonNull(suiteRegistry, "suiteRegistry");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.retirementAttestations = Objects.requireNonNull(
                retirementAttestations, "retirementAttestations");
        this.lifecycleAttestations = Objects.requireNonNull(
                lifecycleAttestations, "lifecycleAttestations");
    }

    /**
     * Produces one independently verifiable lifecycle page.
     *
     * @param suiteId path-bound suite identity
     * @param request exact bounded generation cursor and continuation pins
     * @param identity verified test-runtime identity
     * @return signed lifecycle page
     */
    public TestSuiteStabilityObservationLedgerLifecyclePageResponse read(
            String suiteId,
            TestSuiteStabilityObservationLedgerLifecyclePageRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        validateRequest(suiteId, request, identity);
        StoredTestSuite stored = suiteRegistry.find(
                request.suiteRef().suiteId(), request.suiteRef().revision(), identity);
        requireClearance(stored.suite().classification(), identity);
        if (!request.suiteRef().fingerprint().equals(stored.fingerprint())) {
            throw conflict(identity,
                    "RG.TEST.STABILITY_LIFECYCLE_SUITE_FINGERPRINT_CONFLICT",
                    "Stored suite differs from the exact lifecycle reference.");
        }

        TestSuiteStabilityObservationLedgerLifecyclePage page;
        try {
            page = repository.observationLedgerLifecyclePage(
                    identity.tenantId(), identity.environmentId(), request).orElseThrow(() ->
                    notFound(identity, "RG.TEST.STABILITY_OBSERVATION_LEDGER_NOT_FOUND",
                            "No compact stability observation exists for this exact suite."));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IllegalArgumentException invalidCursor) {
            throw badRequest(identity, "RG.TEST.STABILITY_LIFECYCLE_CURSOR_INVALID",
                    "The lifecycle cursor is outside the committed retirement chain.");
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.STABILITY_LIFECYCLE_UNAVAILABLE",
                    "The compact-observation lifecycle is unavailable.");
        }
        if ((!request.expectedCurrentFloorFingerprint().isBlank()
                && !request.expectedCurrentFloorFingerprint().equals(
                page.currentFloor().floorFingerprint()))
                || (!request.expectedHeadFingerprint().isBlank()
                && !request.expectedHeadFingerprint().equals(
                page.head().headFingerprint()))) {
            throw conflict(identity, "RG.TEST.STABILITY_LIFECYCLE_SNAPSHOT_CHANGED",
                    "The observation-ledger floor or head changed after the caller pinned it.");
        }
        if (!TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.valid(
                objectMapper, page)) {
            throw conflict(identity, "RG.TEST.STABILITY_LIFECYCLE_PAGE_INVALID",
                    "The observation-ledger lifecycle page failed whole-record verification.");
        }
        for (TestSuiteStabilityObservationFloorRetirement retirement : page.retirements()) {
            TestSuiteStabilityObservationFloorRetirementAttestationService.Verification result =
                    retirementAttestations.verify(
                            retirement.evidence(), retirement.attestation());
            if (result == TestSuiteStabilityObservationFloorRetirementAttestationService
                    .Verification.UNAVAILABLE) {
                throw unavailable(identity,
                        "RG.TEST.STABILITY_RETIREMENT_VERIFICATION_UNAVAILABLE",
                        "A floor-retirement signature cannot currently be verified.");
            }
            if (result != TestSuiteStabilityObservationFloorRetirementAttestationService
                    .Verification.VERIFIED) {
                throw conflict(identity, "RG.TEST.STABILITY_RETIREMENT_INVALID",
                        "A floor retirement failed integrity verification.");
            }
        }
        TestSuiteStabilityObservationLedgerLifecycleAttestationService.SealResult sealed =
                lifecycleAttestations.seal(page);
        if (!sealed.verified()) {
            throw unavailable(identity, "RG.TEST.STABILITY_LIFECYCLE_ATTESTATION_UNAVAILABLE",
                    "Signed observation-ledger lifecycle evidence could not be produced.");
        }
        String pageId = TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.lifecyclePageId(
                objectMapper, page.requestFingerprint(), page.pageFingerprint());
        return new TestSuiteStabilityObservationLedgerLifecyclePageResponse(
                TestSuiteStabilityObservationLedgerLifecyclePageResponse.SCHEMA_VERSION,
                pageId, page.pageFingerprint(), page, sealed.attestation());
    }

    private static void validateRequest(
            String pathSuiteId,
            TestSuiteStabilityObservationLedgerLifecyclePageRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !TestSuiteStabilityObservationLedgerLifecyclePageRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || normalized(pathSuiteId).isBlank()
                || !normalized(pathSuiteId).equals(request.suiteRef().suiteId())
                || !FINGERPRINT.matcher(request.suiteRef().fingerprint()).matches()) {
            throw badRequest(identity, "RG.TEST.STABILITY_LIFECYCLE_REQUEST_INVALID",
                    "A complete exact-suite lifecycle-page request is required.");
        }
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ACCEPTED_PURPOSES.contains(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.STABILITY_LIFECYCLE_PURPOSE_FORBIDDEN",
                    "Lifecycle reads require TEST_EXECUTION or TEST_REPLAY purpose.",
                    identity.correlationId(), Map.of()));
        }
        if (!ENABLED_ENVIRONMENTS.contains(
                identity.environmentId().toLowerCase(Locale.ROOT))) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Lifecycle reads are restricted to test and staging identities.",
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
                    "Verified workload clearance cannot read this suite observation lifecycle.",
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
}

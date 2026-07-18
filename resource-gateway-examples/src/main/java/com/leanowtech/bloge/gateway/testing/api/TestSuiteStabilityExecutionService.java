package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityEvidenceEvaluator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Idempotent bounded rerun service for signed suite-stability evidence.
 *
 * <p>Each attempt delegates to the ordinary immutable suite runner with a deterministic derived
 * idempotency key and {@code COLLECT_ALL}. A process crash therefore leaves reusable durable source
 * runs rather than an ambiguous parent checkpoint. Only a fully signed terminal analysis crosses
 * the stability repository boundary; concurrent creators converge on the stored winner.</p>
 */
public final class TestSuiteStabilityExecutionService {
    private static final int MAX_CLIENT_REQUEST_ID_LENGTH = 255;
    private static final int MAX_METADATA_BYTES = 16_384;
    private static final int MAX_METADATA_PROPERTIES = 32;
    private static final int MAX_METADATA_STRING_LENGTH = 512;
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> EXECUTION_PURPOSES = Set.of("TEST_EXECUTION", "TEST_REPLAY");
    private static final List<String> CLASSIFICATIONS = List.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern METADATA_KEY = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_.-]{0,127}");

    private final TestSuiteRegistryService suiteRegistry;
    private final TestSuiteExecutionService suiteExecutions;
    private final TestExecutionApiService childExecutions;
    private final TestSuiteStabilityRunRepository repository;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityEvidenceEvaluator evaluator;
    private final TestSuiteStabilityAttestationService attestations;
    private final Duration retention;

    /**
     * @param suiteRegistry immutable suite registry
     * @param suiteExecutions ordinary durable suite runner
     * @param childExecutions verified full child-evidence reader
     * @param repository immutable terminal stability store
     * @param objectMapper canonical protocol mapper
     * @param attestations stability-specific signing boundary
     * @param retention maximum analysis retention, bounded by earliest source retention
     */
    public TestSuiteStabilityExecutionService(
            TestSuiteRegistryService suiteRegistry,
            TestSuiteExecutionService suiteExecutions,
            TestExecutionApiService childExecutions,
            TestSuiteStabilityRunRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityAttestationService attestations,
            Duration retention) {
        this.suiteRegistry = Objects.requireNonNull(suiteRegistry, "suiteRegistry");
        this.suiteExecutions = Objects.requireNonNull(suiteExecutions, "suiteExecutions");
        this.childExecutions = Objects.requireNonNull(childExecutions, "childExecutions");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.evaluator = new TestSuiteStabilityEvidenceEvaluator(objectMapper);
        this.attestations = Objects.requireNonNull(attestations, "attestations");
        this.retention = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofDays(30) : retention;
    }

    /**
     * Executes or idempotently resolves one exact bounded stability rerun.
     *
     * @param suiteId path-bound suite id
     * @param request exact suite, idempotency, attempt, and provenance intent
     * @param identity verified test-runtime identity
     * @return signed terminal stability evidence
     */
    public TestSuiteStabilityExecutionResponse execute(
            String suiteId,
            TestSuiteStabilityExecutionRequest request,
            IntegrationRequestContext identity) {
        requireExecutionIdentity(identity);
        validateRequest(suiteId, request, identity);
        String requestFingerprint = ProtocolFingerprint.of(objectMapper, request);
        Optional<TestSuiteStabilityRunRecord> existing = findByClientRequestId(
                request.clientRequestId(), identity);
        if (existing.isPresent()) {
            return idempotentResponse(existing.get(), requestFingerprint, identity);
        }

        StoredTestSuite stored = suiteRegistry.find(request.suiteRef().suiteId(),
                request.suiteRef().revision(), identity);
        requireClearance(stored.suite().classification(), identity);
        if (!request.suiteRef().fingerprint().equals(stored.fingerprint())) {
            throw conflict(identity, "RG.TEST.STABILITY_SUITE_FINGERPRINT_CONFLICT",
                    "Stored suite differs from the exact stability execution reference.");
        }
        requireSupportedSuite(stored.suite(), identity);

        String stabilityRunId = stabilityRunId(identity, requestFingerprint);
        List<TestSuiteStabilityEvidenceEvaluator.AttemptObservation> observations =
                new ArrayList<>();
        for (int attempt = 1; attempt <= request.attempts(); attempt++) {
            TestSuiteExecutionRequest attemptRequest = new TestSuiteExecutionRequest("",
                    request.suiteRef(), attemptClientRequestId(
                    identity, request.clientRequestId(), attempt),
                    TestSuiteExecutionRequest.Strategy.COLLECT_ALL,
                    Map.of("stabilityRunId", stabilityRunId,
                            "stabilityAttempt", attempt,
                            "stabilityRequestFingerprint", requestFingerprint));
            TestSuiteExecutionResponse executed = suiteExecutions.execute(
                    suiteId, attemptRequest, identity);
            TestSuiteExecutionResponse source = suiteExecutions.find(
                    executed.suiteRunId(), identity);
            Map<String, TestSuiteStabilityEvidenceEvaluator.ChildObservation> children =
                    new LinkedHashMap<>();
            source.attestation().childEvidenceRefs().forEach(child -> {
                TestExecutionApiResponse full = childExecutions.find(child.runId(),
                        TestExecutionApiRequest.Verbosity.FULL, identity);
                children.put(child.runId(),
                        new TestSuiteStabilityEvidenceEvaluator.ChildObservation(full, true));
            });
            observations.add(new TestSuiteStabilityEvidenceEvaluator.AttemptObservation(
                    attempt, source, true, children, Instant.now(), ""));
        }

        TestSuiteStabilityEvidence evidence = evaluator.evaluate(stored.suite(),
                request.suiteRef(), stabilityRunId, request.clientRequestId(), request.attempts(),
                observations, request.metadata());
        TestSuiteStabilityAttestationService.SealResult seal =
                attestations.seal(evidence, requestFingerprint);
        if (!seal.verified()) {
            throw unavailable(identity, "RG.TEST.STABILITY_ATTESTATION_UNAVAILABLE",
                    "Stability evidence cannot be retained because its terminal signature is unavailable.");
        }
        String evidenceFingerprint = seal.attestation().evidenceFingerprint();
        Instant createdAt = Instant.now();
        Instant expiresAt = evidence.startedAt().plus(retention);
        if (!expiresAt.isAfter(createdAt)) {
            throw conflict(identity, "RG.TEST.STABILITY_SOURCE_RETENTION_EXHAUSTED",
                    "A source suite run is too close to expiry for a durable stability analysis.");
        }
        TestSuiteStabilityRunRecord record = new TestSuiteStabilityRunRecord(
                stabilityRunId, request.clientRequestId(), requestFingerprint,
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.actorId(), stored.suite().classification(),
                evidenceFingerprint, evidence, seal.attestation(), createdAt, expiresAt);
        try {
            return response(repository.create(record));
        } catch (TestSuiteStabilityRunConflictException race) {
            TestSuiteStabilityRunRecord winner = findByClientRequestId(
                    request.clientRequestId(), identity).orElseThrow(() -> conflict(identity,
                    "RG.TEST.STABILITY_IDEMPOTENCY_RETIRED",
                    "The stability idempotency key is already reserved by expired evidence."));
            return idempotentResponse(winner, requestFingerprint, identity);
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.TEST.STABILITY_STORE_UNAVAILABLE",
                    "The independent stability evidence store is unavailable.");
        }
    }

    /**
     * Resolves one retained signed stability analysis.
     *
     * @param stabilityRunId deterministic analysis id
     * @param identity verified test-runtime identity
     * @return retained signed terminal response
     */
    public TestSuiteStabilityExecutionResponse find(
            String stabilityRunId,
            IntegrationRequestContext identity) {
        requireExecutionIdentity(identity);
        TestSuiteStabilityRunRecord record;
        try {
            record = repository.find(identity.tenantId(), identity.environmentId(),
                    normalized(stabilityRunId)).orElseThrow(() ->
                    new IntegrationProblemException(IntegrationProblem.notFound(
                            "RG.TEST.STABILITY_RUN_NOT_FOUND",
                            "Stability analysis was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.TEST.STABILITY_STORE_UNAVAILABLE",
                    "The independent stability evidence store is unavailable.");
        }
        requireClearance(record.classification(), identity);
        verifyRecord(record, identity);
        return response(record);
    }

    private TestSuiteStabilityExecutionResponse idempotentResponse(
            TestSuiteStabilityRunRecord existing,
            String requestFingerprint,
            IntegrationRequestContext identity) {
        requireClearance(existing.classification(), identity);
        if (!requestFingerprint.equals(existing.requestFingerprint())) {
            throw conflict(identity, "RG.TEST.STABILITY_IDEMPOTENCY_CONFLICT",
                    "clientRequestId already identifies a different stability execution intent.");
        }
        verifyRecord(existing, identity);
        return response(existing);
    }

    private void verifyRecord(
            TestSuiteStabilityRunRecord record,
            IntegrationRequestContext identity) {
        TestSuiteStabilityAttestationService.Verification verification =
                attestations.verify(record.evidence(), record.attestation());
        if (verification == TestSuiteStabilityAttestationService.Verification.UNAVAILABLE) {
            throw unavailable(identity, "RG.TEST.STABILITY_ATTESTATION_VERIFICATION_UNAVAILABLE",
                    "Stability evidence cannot be read while its verification key is unavailable.");
        }
        String fingerprint;
        try {
            fingerprint = ProtocolFingerprint.of(objectMapper, record.evidence());
        } catch (RuntimeException invalid) {
            fingerprint = "";
        }
        if (verification != TestSuiteStabilityAttestationService.Verification.VERIFIED
                || !record.stabilityRunId().equals(record.evidence().stabilityRunId())
                || !record.requestFingerprint().equals(record.attestation().requestFingerprint())
                || !record.evidenceFingerprint().equals(fingerprint)
                || !record.evidenceFingerprint().equals(record.attestation().evidenceFingerprint())) {
            throw conflict(identity, "RG.TEST.STABILITY_ATTESTATION_INVALID",
                    "Stability evidence or its ordered source closure failed integrity verification.");
        }
    }

    private Optional<TestSuiteStabilityRunRecord> findByClientRequestId(
            String clientRequestId,
            IntegrationRequestContext identity) {
        try {
            return repository.findByClientRequestId(identity.tenantId(), identity.environmentId(),
                    clientRequestId);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.STABILITY_STORE_UNAVAILABLE",
                    "The independent stability evidence store is unavailable.");
        }
    }

    private void validateRequest(
            String pathSuiteId,
            TestSuiteStabilityExecutionRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !TestSuiteStabilityExecutionRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || request.suiteRef() == null
                || normalized(pathSuiteId).isBlank()
                || !normalized(pathSuiteId).equals(request.suiteRef().suiteId())
                || request.suiteRef().revision() <= 0
                || !fingerprint(request.suiteRef().fingerprint())
                || request.attempts() < TestSuiteStabilityEvidence.MIN_ATTEMPTS
                || request.attempts() > TestSuiteStabilityEvidence.MAX_ATTEMPTS) {
            throw badRequest(identity, "RG.TEST.STABILITY_REQUEST_INVALID",
                    "An exact suite reference and 3..20 bounded attempts are required.");
        }
        if (request.clientRequestId().isBlank()
                || request.clientRequestId().length() > MAX_CLIENT_REQUEST_ID_LENGTH) {
            throw badRequest(identity, "RG.TEST.STABILITY_IDEMPOTENCY_KEY_INVALID",
                    "clientRequestId must be a bounded non-empty idempotency key.");
        }
        if (request.metadata().size() > MAX_METADATA_PROPERTIES
                || request.metadata().entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || !METADATA_KEY.matcher(entry.getKey()).matches()
                        || !metadataValue(entry.getValue()))) {
            throw badRequest(identity, "RG.TEST.STABILITY_METADATA_INVALID",
                    "Stability metadata must contain only bounded scalar provenance facts.");
        }
        try {
            if (objectMapper.writeValueAsBytes(request.metadata()).length > MAX_METADATA_BYTES) {
                throw badRequest(identity, "RG.TEST.STABILITY_METADATA_TOO_LARGE",
                        "Stability metadata exceeds the bounded protocol size.");
            }
        } catch (JsonProcessingException invalid) {
            throw badRequest(identity, "RG.TEST.STABILITY_METADATA_INVALID",
                    "Stability metadata cannot be serialized as protocol JSON.");
        }
    }

    private static boolean metadataValue(Object value) {
        if (value instanceof Double number) {
            return Double.isFinite(number);
        }
        if (value instanceof Float number) {
            return Float.isFinite(number);
        }
        return value instanceof Boolean || value instanceof Number
                || value instanceof String text && text.length() <= MAX_METADATA_STRING_LENGTH;
    }

    private static void requireSupportedSuite(
            TestSuiteProtocol suite,
            IntegrationRequestContext identity) {
        if (suite instanceof TestSuiteV3 || suite instanceof TestSuiteV5) {
            throw badRequest(identity, "RG.TEST.STABILITY_SUITE_GENERATION_UNSUPPORTED",
                    "Stability v1 requires executable child evidence for every suite case.");
        }
    }

    private String stabilityRunId(
            IntegrationRequestContext identity,
            String requestFingerprint) {
        String fingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testSuiteStabilityRunIdentity.v1",
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "requestFingerprint", requestFingerprint));
        return "stability-" + fingerprint.substring("sha256:".length());
    }

    private String attemptClientRequestId(
            IntegrationRequestContext identity,
            String parentClientRequestId,
            int attempt) {
        String namespace = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testSuiteStabilityAttemptNamespace.v1",
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "clientRequestId", parentClientRequestId));
        return "stability-attempt-" + namespace.substring("sha256:".length())
                + "-%02d".formatted(attempt);
    }

    private void requireExecutionIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!EXECUTION_PURPOSES.contains(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.STABILITY_PURPOSE_FORBIDDEN",
                    "Stability execution requires TEST_EXECUTION or TEST_REPLAY purpose.",
                    identity.correlationId(), Map.of()));
        }
        if (!ENABLED_ENVIRONMENTS.contains(
                identity.environmentId().toLowerCase(Locale.ROOT))) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Stability execution is restricted to test and staging identities.",
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
                    "Verified workload clearance cannot execute this suite.",
                    identity.correlationId(), Map.of("classification", required)));
        }
    }

    private static TestSuiteStabilityExecutionResponse response(
            TestSuiteStabilityRunRecord record) {
        return new TestSuiteStabilityExecutionResponse("", record.stabilityRunId(),
                record.evidenceFingerprint(), record.evidence(), record.attestation());
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
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
}

package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authoritative application service for immutable, dependency-closed test suites.
 *
 * <p>Registration is intentionally fail closed. It resolves the current target and every fixture
 * dependency in the caller's verified scope before committing a suite revision. A successfully
 * stored suite therefore cannot hide a stale target, a mutable fixture lookup, or a classification
 * downgrade.</p>
 */
public final class TestSuiteRegistryService {

    /** Maximum cases in one suite revision and later one bounded suite execution. */
    public static final int MAX_CASES = 100;
    /** Maximum canonical JSON bytes retained in one suite revision. */
    public static final int MAX_SUITE_BYTES = 8 * 1_048_576;
    /** Maximum canonical JSON bytes accepted for one test-case input. */
    public static final int MAX_CASE_INPUT_BYTES = 1_048_576;

    private static final int MAX_METADATA_BYTES = 16_384;
    private static final int MAX_IDENTIFIER_LENGTH = 255;
    private static final int MAX_TARGET_ID_LENGTH = 512;
    private static final int MAX_COVERAGE_IDENTIFIERS = 10_000;
    private static final int MAX_TAGS = 64;
    private static final int MAX_TAG_LENGTH = 128;
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final List<String> CLASSIFICATIONS = List.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final GatewayGraphService graphService;
    private final OperatorRegistry operatorRegistry;
    private final ResourceRegistry resourceRegistry;
    private final ObjectMapper objectMapper;
    private final FixtureBundleRepository fixtureRepository;
    private final TestSuiteRepository suiteRepository;
    private final TestSecurityEventRepository securityEvents;

    /**
     * Creates a registry service over frozen target discovery and independent test stores.
     *
     * @param graphService graph catalog and contract service
     * @param operatorRegistry current operator binding registry
     * @param resourceRegistry current resource dependency registry
     * @param objectMapper canonical protocol serializer
     * @param fixtureRepository immutable fixture dependency registry
     * @param suiteRepository immutable suite registry
     * @param securityEvents mandatory fail-closed security audit sink
     */
    public TestSuiteRegistryService(GatewayGraphService graphService,
                                    OperatorRegistry operatorRegistry,
                                    ResourceRegistry resourceRegistry,
                                    ObjectMapper objectMapper,
                                    FixtureBundleRepository fixtureRepository,
                                    TestSuiteRepository suiteRepository,
                                    TestSecurityEventRepository securityEvents) {
        this.graphService = Objects.requireNonNull(graphService, "graphService");
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.resourceRegistry = Objects.requireNonNull(resourceRegistry, "resourceRegistry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.fixtureRepository = Objects.requireNonNull(fixtureRepository, "fixtureRepository");
        this.suiteRepository = Objects.requireNonNull(suiteRepository, "suiteRepository");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
    }

    /**
     * Registers one exact suite revision after validating its full dependency closure.
     *
     * @param suiteId path-bound suite id
     * @param request versioned immutable suite registration request
     * @param identity verified workload identity
     * @return newly stored or content-equivalent existing revision
     */
    public StoredTestSuite register(String suiteId,
                                    TestSuiteRegistrationRequest request,
                                    IntegrationRequestContext identity) {
        requireTestIdentity(identity);
        if (request == null || !TestSuiteRegistrationRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                || request.testSuite() == null) {
            throw badRequest(identity, "RG.TEST.SUITE_REQUEST_INVALID",
                    "A versioned test-suite registration request is required.", Map.of());
        }
        TestSuite suite = request.testSuite();
        validateIdentity(suiteId, suite, identity);
        requireClearance(suite.classification(), identity);
        requireBounded(suite.metadata(), MAX_METADATA_BYTES, "testSuite.metadata", identity);
        requireMetadata(suite.metadata(), "testSuite.metadata", identity);
        requireBounded(suite, MAX_SUITE_BYTES, "testSuite", identity);

        ResolvedTarget currentTarget = currentTarget(suite.target(), identity);
        if (!currentTarget.fingerprint().equals(suite.target().fingerprint())) {
            throw conflict(identity, "RG.TEST.SUITE_TARGET_STALE",
                    "Test-suite target fingerprint does not identify the current frozen dependencies.",
                    Map.of("currentTargetFingerprint", currentTarget.fingerprint()));
        }
        if (suite.promotionPolicy().requireTargetCertificationEligible()
                && !currentTarget.certificationEligible()) {
            throw badRequest(identity, "RG.TEST.SUITE_TARGET_NOT_CERTIFIABLE",
                    "Promotion policy requires a target revision that is certification eligible.", Map.of());
        }

        validatePoliciesAndCases(suite, identity);
        String fingerprint = ProtocolFingerprint.of(objectMapper, suite);
        StoredTestSuite stored = new StoredTestSuite("", identity.tenantId(), identity.environmentId(),
                suite.suiteId(), suite.revision(), fingerprint, suite, Instant.now(), identity.actorId());
        try {
            return suiteRepository.create(stored);
        } catch (TestSuiteConflictException immutableConflict) {
            throw conflict(identity, "RG.TEST.SUITE_REVISION_CONFLICT",
                    immutableConflict.getMessage(), Map.of());
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.SUITE_STORE_UNAVAILABLE",
                    "The independent test-suite registry is unavailable.");
        }
    }

    /**
     * Resolves one exact suite revision after tenant, environment, and clearance checks.
     *
     * @param suiteId stable suite id
     * @param revision exact positive revision
     * @param identity verified workload identity
     * @return immutable stored suite revision
     */
    public StoredTestSuite find(String suiteId, long revision, IntegrationRequestContext identity) {
        requireTestIdentity(identity);
        if (normalized(suiteId).isBlank() || revision <= 0) {
            throw badRequest(identity, "RG.TEST.SUITE_IDENTITY_INVALID",
                    "A non-empty suiteId and positive revision are required.", Map.of());
        }
        StoredTestSuite stored;
        try {
            stored = suiteRepository.find(identity.tenantId(), identity.environmentId(),
                            normalized(suiteId), revision)
                    .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                            "RG.TEST.SUITE_NOT_FOUND",
                            "Test suite was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
        } catch (IntegrationProblemException notFound) {
            throw notFound;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.SUITE_STORE_UNAVAILABLE",
                    "The independent test-suite registry is unavailable.");
        }
        requireClearance(stored.suite().classification(), identity);
        return stored;
    }

    private void validateIdentity(String pathSuiteId, TestSuite suite,
                                  IntegrationRequestContext identity) {
        if (!TestSuite.SCHEMA_VERSION.equals(suite.schemaVersion())
                || normalized(pathSuiteId).isBlank()
                || !normalized(pathSuiteId).equals(suite.suiteId())
                || suite.suiteId().length() > MAX_IDENTIFIER_LENGTH || suite.revision() <= 0) {
            throw badRequest(identity, "RG.TEST.SUITE_IDENTITY_INVALID",
                    "Path id, suiteId, positive revision, and suite schemaVersion must identify one revision.",
                    Map.of());
        }
        TestSuite.Target target = suite.target();
        if (target == null || !("GRAPH".equals(target.kind()) || "OPERATOR".equals(target.kind()))
                || target.id().isBlank() || target.id().length() > MAX_TARGET_ID_LENGTH
                || !validFingerprint(target.fingerprint())) {
            throw badRequest(identity, "RG.TEST.SUITE_TARGET_INVALID",
                    "A GRAPH or OPERATOR target with an exact sha256 fingerprint is required.", Map.of());
        }
    }

    private void validatePoliciesAndCases(TestSuite suite, IntegrationRequestContext identity) {
        if (suite.cases().isEmpty() || suite.cases().size() > MAX_CASES) {
            throw badRequest(identity, "RG.TEST.SUITE_CASE_COUNT_INVALID",
                    "A test suite must contain between 1 and 100 cases.", Map.of("maximum", MAX_CASES));
        }
        validateCoveragePolicy(suite, identity);
        validatePromotionPolicy(suite, identity);

        Set<String> caseIds = new HashSet<>();
        Set<TestSuite.CaseType> representedTypes = new HashSet<>();
        for (TestSuite.TestCase testCase : suite.cases()) {
            validateCaseShape(suite, testCase, caseIds, identity);
            representedTypes.add(testCase.caseType());
            StoredFixtureBundle fixture = requireFixture(testCase.fixtureBundleRef(), identity);
            validateFixtureDependency(suite, testCase, fixture, identity);
        }
        if (!representedTypes.containsAll(suite.coveragePolicy().requiredCaseTypes())) {
            Set<TestSuite.CaseType> missing = new HashSet<>(suite.coveragePolicy().requiredCaseTypes());
            missing.removeAll(representedTypes);
            throw badRequest(identity, "RG.TEST.SUITE_CASE_TYPE_COVERAGE_UNMET",
                    "The suite does not contain every required case type.", Map.of("missing", missing));
        }
    }

    private void validateCoveragePolicy(TestSuite suite, IntegrationRequestContext identity) {
        TestSuite.CoveragePolicy policy = suite.coveragePolicy();
        if (policy == null || policy.minimumCases() < 1 || policy.minimumCases() > suite.cases().size()
                || policy.minimumAssertionsPerCase() < 0 || policy.minimumAssertionsPerCase() > 1_000
                || policy.requiredCaseTypes().stream().anyMatch(Objects::isNull)) {
            throw badRequest(identity, "RG.TEST.SUITE_COVERAGE_POLICY_INVALID",
                    "Coverage minima must be bounded and satisfiable by the registered revision.", Map.of());
        }
        requireCoverageIdentifiers(policy.requiredInvocationSiteIds(),
                "requiredInvocationSiteIds", identity);
        requireEdgeTransfers(policy.requiredEdgeTransfers(), identity);
    }

    private void validatePromotionPolicy(TestSuite suite, IntegrationRequestContext identity) {
        TestSuite.PromotionPolicy policy = suite.promotionPolicy();
        if (policy == null || policy.minimumCertifiableCases() < 0
                || policy.minimumCertifiableCases() > suite.cases().size()) {
            throw badRequest(identity, "RG.TEST.SUITE_PROMOTION_POLICY_INVALID",
                    "Promotion evidence minima must be bounded by the registered case count.", Map.of());
        }
    }

    private void validateCaseShape(TestSuite suite, TestSuite.TestCase testCase, Set<String> caseIds,
                                   IntegrationRequestContext identity) {
        if (testCase == null || testCase.caseId().isBlank()
                || testCase.caseId().length() > MAX_IDENTIFIER_LENGTH || testCase.caseType() == null
                || !caseIds.add(testCase.caseId())) {
            throw badRequest(identity, "RG.TEST.SUITE_CASE_IDENTITY_INVALID",
                    "Every case requires a unique bounded caseId and a supported caseType.", Map.of());
        }
        if ("GRAPH".equals(suite.target().kind()) && !(testCase.input() instanceof Map<?, ?>)) {
            throw badRequest(identity, "RG.TEST.SUITE_GRAPH_INPUT_INVALID",
                    "Every GRAPH suite case input must be a JSON object.", Map.of("caseId", testCase.caseId()));
        }
        if (testCase.tags().size() > MAX_TAGS || testCase.tags().stream()
                .anyMatch(tag -> tag.isBlank() || tag.length() > MAX_TAG_LENGTH)) {
            throw badRequest(identity, "RG.TEST.SUITE_CASE_TAGS_INVALID",
                    "Case tags must be bounded non-empty identifiers.", Map.of("caseId", testCase.caseId()));
        }
        requireBounded(testCase.input(), MAX_CASE_INPUT_BYTES,
                "testSuite.cases[" + testCase.caseId() + "].input", identity);
        requireBounded(testCase.metadata(), MAX_METADATA_BYTES,
                "testSuite.cases[" + testCase.caseId() + "].metadata", identity);
        requireMetadata(testCase.metadata(), "testSuite.case.metadata", identity);
        TestSuite.FixtureBundleRef reference = testCase.fixtureBundleRef();
        if (reference == null || reference.fixtureBundleId().isBlank()
                || reference.fixtureBundleId().length() > MAX_IDENTIFIER_LENGTH
                || reference.revision() <= 0 || !validFingerprint(reference.fingerprint())) {
            throw badRequest(identity, "RG.TEST.SUITE_FIXTURE_REF_INVALID",
                    "Every case requires an exact fixture id, revision, and sha256 fingerprint.",
                    Map.of("caseId", testCase.caseId()));
        }
    }

    private StoredFixtureBundle requireFixture(TestSuite.FixtureBundleRef reference,
                                                IntegrationRequestContext identity) {
        try {
            return fixtureRepository.find(identity.tenantId(), identity.environmentId(),
                            reference.fixtureBundleId(), reference.revision())
                    .orElseThrow(() -> conflict(identity, "RG.TEST.SUITE_FIXTURE_NOT_FOUND",
                            "A referenced fixture revision is absent from the authorized scope.",
                            Map.of("fixtureBundleId", reference.fixtureBundleId(),
                                    "revision", reference.revision())));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.FIXTURE_STORE_UNAVAILABLE",
                    "The independent fixture registry is unavailable.");
        }
    }

    private void validateFixtureDependency(TestSuite suite, TestSuite.TestCase testCase,
                                           StoredFixtureBundle fixture,
                                           IntegrationRequestContext identity) {
        requireClearance(fixture.bundle().classification(), identity);
        TestSuite.FixtureBundleRef reference = testCase.fixtureBundleRef();
        if (!reference.fingerprint().equals(fixture.fingerprint())) {
            throw conflict(identity, "RG.TEST.SUITE_FIXTURE_FINGERPRINT_CONFLICT",
                    "A fixture dependency differs from the immutable suite reference.",
                    Map.of("caseId", testCase.caseId()));
        }
        if (!suite.target().fingerprint().equals(fixture.bundle().targetFingerprint())) {
            throw conflict(identity, "RG.TEST.SUITE_FIXTURE_TARGET_STALE",
                    "A fixture dependency targets a different artifact snapshot.",
                    Map.of("caseId", testCase.caseId()));
        }
        if (classificationRank(suite.classification())
                < classificationRank(fixture.bundle().classification())) {
            throw badRequest(identity, "RG.TEST.SUITE_CLASSIFICATION_DOWNGRADE",
                    "Suite classification must dominate every referenced fixture classification.",
                    Map.of("caseId", testCase.caseId(),
                            "fixtureClassification", fixture.bundle().classification()));
        }
        if (fixture.bundle().assertions().size()
                < suite.coveragePolicy().minimumAssertionsPerCase()) {
            throw badRequest(identity, "RG.TEST.SUITE_ASSERTION_DENSITY_UNMET",
                    "A case fixture does not satisfy minimumAssertionsPerCase.",
                    Map.of("caseId", testCase.caseId(),
                            "actualAssertions", fixture.bundle().assertions().size()));
        }
    }

    private ResolvedTarget currentTarget(TestSuite.Target target,
                                         IntegrationRequestContext identity) {
        if ("GRAPH".equals(target.kind())) {
            Graph graph;
            try {
                graph = graphService.requireGraph(target.id());
            } catch (IllegalArgumentException notFound) {
                throw notFound(identity, "RG.TEST.SUITE_TARGET_NOT_FOUND", "Graph target was not found.");
            }
            GraphExecutionTargetSnapshot snapshot = GraphExecutionTargetSnapshot.capture(
                    objectMapper, graph, resourceRegistry);
            return new ResolvedTarget(snapshot.fingerprint(), snapshot.certificationEligible());
        }
        try {
            OperatorExecutionTargetSnapshot snapshot = OperatorExecutionTargetSnapshot.capture(
                    objectMapper, target.id(), operatorRegistry, resourceRegistry);
            return new ResolvedTarget(snapshot.fingerprint(), snapshot.certificationEligible());
        } catch (IllegalArgumentException notFound) {
            throw notFound(identity, "RG.TEST.SUITE_TARGET_NOT_FOUND", "Operator target was not found.");
        }
    }

    private void requireCoverageIdentifiers(List<String> identifiers, String field,
                                            IntegrationRequestContext identity) {
        if (identifiers.size() > MAX_COVERAGE_IDENTIFIERS || identifiers.stream()
                .anyMatch(value -> value.isBlank() || value.length() > MAX_TARGET_ID_LENGTH)) {
            throw badRequest(identity, "RG.TEST.SUITE_COVERAGE_POLICY_INVALID",
                    field + " must contain bounded non-empty identifiers.", Map.of("field", field));
        }
    }

    private void requireEdgeTransfers(List<TestSuite.EdgeTransferRef> transfers,
                                      IntegrationRequestContext identity) {
        if (transfers.size() > MAX_COVERAGE_IDENTIFIERS || transfers.stream().anyMatch(transfer ->
                transfer == null || transfer.fromInvocationSiteId().isBlank()
                        || transfer.toInvocationSiteId().isBlank()
                        || transfer.fromInvocationSiteId().length() > MAX_TARGET_ID_LENGTH
                        || transfer.toInvocationSiteId().length() > MAX_TARGET_ID_LENGTH)) {
            throw badRequest(identity, "RG.TEST.SUITE_COVERAGE_POLICY_INVALID",
                    "requiredEdgeTransfers must contain bounded structural endpoint pairs.",
                    Map.of("field", "requiredEdgeTransfers"));
        }
    }

    private void requireTestIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ENABLED_ENVIRONMENTS.contains(identity.environmentId().toLowerCase(Locale.ROOT))) {
            securityEvent(identity, "TEST_PURPOSE_PRODUCTION_TOUCH", "REJECTED",
                    "RG.TEST.ENVIRONMENT_FORBIDDEN", Map.of("allowedEnvironments", ENABLED_ENVIRONMENTS));
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Test-suite governance is restricted to test and staging identities.",
                    identity.correlationId(), Map.of("environmentId", identity.environmentId())));
        }
    }

    private void requireClearance(String classification, IntegrationRequestContext identity) {
        String required = normalized(classification).toUpperCase(Locale.ROOT);
        if (!CLASSIFICATIONS.contains(required)) {
            throw badRequest(identity, "RG.TEST.SUITE_CLASSIFICATION_INVALID",
                    "Classification must be PUBLIC, INTERNAL, CONFIDENTIAL, or RESTRICTED.",
                    Map.of("classification", required));
        }
        if (!identity.hasClearanceAtLeast(required)) {
            securityEvent(identity, "TEST_SUITE_CLEARANCE_VIOLATION", "REJECTED",
                    "RG.TEST.SUITE_CLEARANCE_FORBIDDEN", Map.of("classification", required));
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.SUITE_CLEARANCE_FORBIDDEN",
                    "Verified workload clearance cannot access this test-suite dependency.",
                    identity.correlationId(), Map.of("classification", required)));
        }
    }

    private void requireBounded(Object value, int maximumBytes, String field,
                                IntegrationRequestContext identity) {
        try {
            if (objectMapper.writeValueAsBytes(value).length > maximumBytes) {
                throw badRequest(identity, "RG.TEST.REQUEST_FIELD_TOO_LARGE",
                        field + " exceeds the bounded protocol size.",
                        Map.of("field", field, "maximumBytes", maximumBytes));
            }
        } catch (JsonProcessingException failure) {
            throw badRequest(identity, "RG.TEST.REQUEST_FIELD_INVALID",
                    field + " cannot be serialized as protocol JSON.", Map.of("field", field));
        }
    }

    private void requireMetadata(Map<String, Object> metadata, String field,
                                 IntegrationRequestContext identity) {
        if (metadata.containsKey(null) || metadata.containsValue(null)) {
            throw badRequest(identity, "RG.TEST.METADATA_INVALID",
                    field + " keys and values must be non-null protocol facts.", Map.of("field", field));
        }
    }

    private void securityEvent(IntegrationRequestContext identity, String type, String outcome,
                               String reason, Map<String, Object> facts) {
        try {
            securityEvents.append(new TestSecurityEvent(0, Instant.now(), identity.correlationId(),
                    identity.tenantId(), identity.environmentId(), identity.actorId(), type, outcome,
                    reason, facts));
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Test-suite governance is unavailable because the security audit sink cannot commit.");
        }
    }

    private static boolean validFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static int classificationRank(String value) {
        return CLASSIFICATIONS.indexOf(normalized(value).toUpperCase(Locale.ROOT));
    }

    private static IntegrationProblemException badRequest(IntegrationRequestContext identity,
                                                          String code, String title,
                                                          Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException conflict(IntegrationRequestContext identity,
                                                        String code, String title,
                                                        Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext identity,
                                                        String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(IntegrationRequestContext identity,
                                                           String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record ResolvedTarget(String fingerprint, boolean certificationEligible) {
    }
}

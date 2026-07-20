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
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteProtocolCodec;

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
    private static final int MAX_SEMANTIC_REQUIREMENTS = 1_000;
    private static final int MAX_TAGS = 64;
    private static final int MAX_TAG_LENGTH = 128;
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final List<String> CLASSIFICATIONS = List.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    private final GatewayGraphService graphService;
    private final OperatorRegistry operatorRegistry;
    private final ResourceRegistry resourceRegistry;
    private final ObjectMapper objectMapper;
    private final FixtureBundleRepository fixtureRepository;
    private final TestSuiteRepository suiteRepository;
    private final TestSecurityEventRepository securityEvents;
    private final TestSuiteProtocolCodec suiteCodec;

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
        this.suiteCodec = new TestSuiteProtocolCodec(objectMapper);
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
        return register(suiteId, request, null, null, null, identity);
    }

    /**
     * Registers V4 only when the materializer supplies the exact plan regenerated in this request.
     *
     * <p>This package-private boundary prevents a generic registration client from asserting an
     * arbitrary property-plan fingerprint. The registry still performs every normal target,
     * fixture, classification, and size check before committing the revision.</p>
     */
    StoredTestSuite registerPropertySuite(String suiteId,
                                          TestSuiteRegistrationRequest request,
                                          TestPropertyCasePlan exactPlan,
                                          IntegrationRequestContext identity) {
        if (exactPlan == null || request == null
                || !(request.testSuite() instanceof TestSuiteV4)) {
            throw badRequest(identity, "RG.TEST.PROPERTY_SUITE_PROOF_INVALID",
                    "Property-suite registration requires an exact regenerated plan and V4 suite.",
                    Map.of());
        }
        return register(suiteId, request, exactPlan, null, null, identity);
    }

    /**
     * Registers V5 only with the exact regenerated mutation plan and oracle suite resolved by the
     * trusted materializer in the same request.
     *
     * <p>Generic registration cannot mint a mutation plan fingerprint, substitute a weaker oracle,
     * or trim the server-generated mutant closure.</p>
     */
    StoredTestSuite registerMutationSuite(String suiteId,
                                          TestSuiteRegistrationRequest request,
                                          TestMutationCasePlan exactPlan,
                                          StoredTestSuite exactOracleSuite,
                                          IntegrationRequestContext identity) {
        if (exactPlan == null || exactOracleSuite == null || request == null
                || !(request.testSuite() instanceof TestSuiteV5)) {
            throw badRequest(identity, "RG.TEST.MUTATION_SUITE_PROOF_INVALID",
                    "Mutation-suite registration requires an exact plan, oracle, and V5 suite.",
                    Map.of());
        }
        return register(suiteId, request, null, exactPlan, exactOracleSuite, identity);
    }

    private StoredTestSuite register(String suiteId,
                                     TestSuiteRegistrationRequest request,
                                     TestPropertyCasePlan exactPropertyPlan,
                                     TestMutationCasePlan exactMutationPlan,
                                     StoredTestSuite exactOracleSuite,
                                     IntegrationRequestContext identity) {
        requireTestIdentity(identity);
        if (request == null || !TestSuiteRegistrationRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                || request.testSuite() == null) {
            throw badRequest(identity, "RG.TEST.SUITE_REQUEST_INVALID",
                    "A versioned test-suite registration request is required.", Map.of());
        }
        TestSuiteProtocol suite = canonicalRequestSuite(request.testSuite(), identity);
        validateIdentity(suiteId, suite, identity);
        if (suite instanceof TestSuiteV4 && exactPropertyPlan == null) {
            throw badRequest(identity, "RG.TEST.PROPERTY_SUITE_MATERIALIZATION_REQUIRED",
                    "Property suites must be created through the exact-plan materialization endpoint.",
                    Map.of("schemaVersion", suite.schemaVersion()));
        }
        if (suite instanceof TestSuiteV5
                && (exactMutationPlan == null || exactOracleSuite == null)) {
            throw badRequest(identity, "RG.TEST.MUTATION_SUITE_MATERIALIZATION_REQUIRED",
                    "Mutation suites must be created through the exact-plan materialization endpoint.",
                    Map.of("schemaVersion", suite.schemaVersion()));
        }
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

        validatePoliciesAndCases(suite, exactPropertyPlan, exactMutationPlan,
                exactOracleSuite, identity);
        String fingerprint = suiteCodec.fingerprint(suite);
        StoredTestSuite stored = StoredTestSuiteIntegrity.verifiedSnapshot(objectMapper,
                new StoredTestSuite("", identity.tenantId(), identity.environmentId(),
                        suite.suiteId(), suite.revision(), fingerprint, suite, Instant.now(),
                        identity.actorId()));
        try {
            return StoredTestSuiteIntegrity.verifiedSnapshot(objectMapper,
                    suiteRepository.create(stored), stored);
        } catch (TestSuiteIntegrityException corrupt) {
            securityEvent(identity, "TEST_SUITE_INTEGRITY_INVALID", "REJECTED",
                    "RG.TEST.SUITE_INTEGRITY_INVALID", Map.of());
            throw unavailable(identity, "RG.TEST.SUITE_INTEGRITY_INVALID",
                    "The stored test-suite revision failed immutable-content verification.");
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
        String normalizedSuiteId = normalized(suiteId);
        StoredTestSuite stored;
        try {
            stored = suiteRepository.find(identity.tenantId(), identity.environmentId(),
                            normalizedSuiteId, revision)
                    .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                            "RG.TEST.SUITE_NOT_FOUND",
                            "Test suite was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
            stored = StoredTestSuiteIntegrity.verifiedSnapshot(objectMapper, stored,
                    identity.tenantId(), identity.environmentId(), normalizedSuiteId, revision);
        } catch (IntegrationProblemException notFound) {
            throw notFound;
        } catch (TestSuiteIntegrityException corrupt) {
            securityEvent(identity, "TEST_SUITE_INTEGRITY_INVALID", "REJECTED",
                    "RG.TEST.SUITE_INTEGRITY_INVALID", Map.of());
            throw unavailable(identity, "RG.TEST.SUITE_INTEGRITY_INVALID",
                    "The stored test-suite revision failed immutable-content verification.");
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.SUITE_STORE_UNAVAILABLE",
                    "The independent test-suite registry is unavailable.");
        }
        requireClearance(stored.suite().classification(), identity);
        return stored;
    }

    private TestSuiteProtocol canonicalRequestSuite(TestSuiteProtocol suite,
                                                     IntegrationRequestContext identity) {
        try {
            return suiteCodec.read(suiteCodec.write(suite));
        } catch (RuntimeException invalid) {
            throw badRequest(identity, "RG.TEST.SUITE_REQUEST_INVALID",
                    "Test-suite content must be a supported canonical protocol value.", Map.of());
        }
    }

    private void validateIdentity(String pathSuiteId, TestSuiteProtocol suite,
                                  IntegrationRequestContext identity) {
        boolean supportedGeneration = (suite instanceof TestSuite
                && TestSuite.SCHEMA_VERSION.equals(suite.schemaVersion()))
                || (suite instanceof TestSuiteV2
                && TestSuiteV2.SCHEMA_VERSION.equals(suite.schemaVersion()))
                || (suite instanceof TestSuiteV3
                && TestSuiteV3.SCHEMA_VERSION.equals(suite.schemaVersion()))
                || (suite instanceof TestSuiteV4
                && TestSuiteV4.SCHEMA_VERSION.equals(suite.schemaVersion()))
                || (suite instanceof TestSuiteV5
                && TestSuiteV5.SCHEMA_VERSION.equals(suite.schemaVersion()));
        if (!supportedGeneration
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

    private void validatePoliciesAndCases(TestSuiteProtocol suite,
                                          TestPropertyCasePlan exactPropertyPlan,
                                          TestMutationCasePlan exactMutationPlan,
                                          StoredTestSuite exactOracleSuite,
                                          IntegrationRequestContext identity) {
        if (suite.cases().isEmpty() || suite.cases().size() > MAX_CASES) {
            throw badRequest(identity, "RG.TEST.SUITE_CASE_COUNT_INVALID",
                    "A test suite must contain between 1 and 100 cases.", Map.of("maximum", MAX_CASES));
        }
        validateCoveragePolicy(suite, identity);
        validateSemanticCoveragePolicy(suite, identity);
        validatePromotionPolicy(suite, identity);

        Set<String> caseIds = new HashSet<>();
        Set<TestSuite.CaseType> representedTypes = new HashSet<>();
        for (TestSuite.TestCase testCase : suite.cases()) {
            validateCaseShape(suite, testCase, caseIds, identity);
            representedTypes.add(testCase.caseType());
            StoredFixtureBundle fixture = requireFixture(testCase.fixtureBundleRef(), identity);
            validateFixtureDependency(suite, testCase, fixture, identity);
        }
        validateSchemaAdmissionPolicy(suite, caseIds, identity);
        validatePropertyPolicy(suite, exactPropertyPlan, identity);
        validateMutationPolicy(suite, exactMutationPlan, exactOracleSuite, identity);
        if (!representedTypes.containsAll(suite.coveragePolicy().requiredCaseTypes())) {
            Set<TestSuite.CaseType> missing = new HashSet<>(suite.coveragePolicy().requiredCaseTypes());
            missing.removeAll(representedTypes);
            throw badRequest(identity, "RG.TEST.SUITE_CASE_TYPE_COVERAGE_UNMET",
                    "The suite does not contain every required case type.", Map.of("missing", missing));
        }
    }

    private void validateCoveragePolicy(TestSuiteProtocol suite,
                                        IntegrationRequestContext identity) {
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

    private void validateSemanticCoveragePolicy(TestSuiteProtocol suite,
                                                IntegrationRequestContext identity) {
        SemanticCoveragePolicy semanticCoverage = switch (suite) {
            case TestSuiteV2 semanticSuite -> semanticSuite.semanticCoveragePolicy();
            case TestSuiteV3 admissionSuite -> admissionSuite.semanticCoveragePolicy();
            case TestSuiteV4 propertySuite -> propertySuite.semanticCoveragePolicy();
            case TestSuiteV5 mutationSuite -> mutationSuite.semanticCoveragePolicy();
            default -> null;
        };
        if (semanticCoverage == null) {
            return;
        }
        List<SemanticCoveragePolicy.Requirement> requirements =
                semanticCoverage.requirements();
        if ((suite instanceof TestSuiteV3 || suite instanceof TestSuiteV4
                || suite instanceof TestSuiteV5)
                && requirements.isEmpty()) {
            return;
        }
        if (requirements.isEmpty() || requirements.size() > MAX_SEMANTIC_REQUIREMENTS) {
            throw badRequest(identity, "RG.TEST.SUITE_SEMANTIC_POLICY_INVALID",
                    "A v2 suite must contain between 1 and 1000 semantic requirements.",
                    Map.of("maximum", MAX_SEMANTIC_REQUIREMENTS));
        }
        for (SemanticCoveragePolicy.Requirement requirement : requirements) {
            if (requirement.requirementId().isBlank()
                    || requirement.requirementId().length() > MAX_IDENTIFIER_LENGTH) {
                throw semanticPolicyInvalid(identity,
                        "Semantic requirement identities must be bounded and non-empty.", requirement);
            }
            if (requirement instanceof SemanticCoveragePolicy.BranchRequirement branch) {
                requireSemanticSite(branch.fromInvocationSiteId(), identity, requirement);
                requireSemanticSite(branch.toInvocationSiteId(), identity, requirement);
            } else if (requirement instanceof SemanticCoveragePolicy.DecisionRuleRequirement decision) {
                requireSemanticSite(decision.invocationSiteId(), identity, requirement);
                if (!decision.outputJsonPointer().startsWith("/")
                        || decision.outputJsonPointer().length() > MAX_TARGET_ID_LENGTH
                        || !objectMapper.valueToTree(decision.expectedScalar()).isValueNode()) {
                    throw semanticPolicyInvalid(identity,
                            "Decision requirements need a bounded JSON Pointer and scalar expectation.",
                            requirement);
                }
            } else if (requirement instanceof SemanticCoveragePolicy.RetryRequirement retry) {
                requireSemanticSite(retry.invocationSiteId(), identity, requirement);
                if (retry.minimumAttempts() > 100_000) {
                    throw semanticPolicyInvalid(identity,
                            "Retry minimumAttempts must be between 2 and 100000.", requirement);
                }
            } else if (requirement instanceof SemanticCoveragePolicy.SiteRequirement site) {
                requireSemanticSite(site.invocationSiteId(), identity, requirement);
                if (!site.errorCode().isBlank() && !MACHINE_CODE.matcher(site.errorCode()).matches()) {
                    throw semanticPolicyInvalid(identity,
                            "Timeout errorCode must be a stable bounded machine code.", requirement);
                }
                if (site.kind() == SemanticCoveragePolicy.Kind.COMPENSATION
                        && !site.invocationSiteId().endsWith("#COMPENSATION")) {
                    throw semanticPolicyInvalid(identity,
                            "Compensation requirements must address a COMPENSATION invocation site.",
                            requirement);
                }
            }
        }
    }

    private void validatePromotionPolicy(TestSuiteProtocol suite,
                                         IntegrationRequestContext identity) {
        TestSuite.PromotionPolicy policy = suite.promotionPolicy();
        if (policy == null || policy.minimumCertifiableCases() < 0
                || policy.minimumCertifiableCases() > suite.cases().size()) {
            throw badRequest(identity, "RG.TEST.SUITE_PROMOTION_POLICY_INVALID",
                    "Promotion evidence minima must be bounded by the registered case count.", Map.of());
        }
    }

    private void validateSchemaAdmissionPolicy(TestSuiteProtocol suite,
                                               Set<String> caseIds,
                                               IntegrationRequestContext identity) {
        if (!(suite instanceof TestSuiteV3 admissionSuite)) {
            return;
        }
        TestSuite.CoveragePolicy coverage = suite.coveragePolicy();
        TestSuite.PromotionPolicy promotion = suite.promotionPolicy();
        boolean inertPolicy = coverage.requiredInvocationSiteIds().isEmpty()
                && coverage.requiredEdgeTransfers().isEmpty()
                && coverage.minimumAssertionsPerCase() == 0
                && !coverage.requireAllFixtureRulesConsumed()
                && promotion.requireAllCasesPassed()
                && promotion.minimumCertifiableCases() == 0
                && !promotion.requireTargetCertificationEligible()
                && admissionSuite.semanticCoveragePolicy().requirements().isEmpty();
        if (!inertPolicy) {
            throw badRequest(identity, "RG.TEST.SUITE_ADMISSION_POLICY_INVALID",
                    "Schema-admission suites require inert execution/semantic coverage and cannot claim business promotion evidence.",
                    Map.of());
        }
        Map<String, TestSuiteV3.AdmissionExpectation> expectations =
                admissionSuite.admissionExpectations();
        if (!expectations.keySet().equals(caseIds) || expectations.values().stream()
                .anyMatch(Objects::isNull)) {
            throw badRequest(identity, "RG.TEST.SUITE_ADMISSION_EXPECTATIONS_INVALID",
                    "Schema-admission suites require exactly one expectation for every caseId.",
                    Map.of("caseCount", caseIds.size(), "expectationCount", expectations.size()));
        }
        expectations.forEach((caseId, expectation) -> {
            if (expectation.validationCodes().size() > 64
                    || expectation.validationCodes().stream()
                    .anyMatch(code -> code.isBlank() || code.length() > MAX_IDENTIFIER_LENGTH)) {
                throw badRequest(identity, "RG.TEST.SUITE_ADMISSION_EXPECTATIONS_INVALID",
                        "Admission validation codes must be bounded stable identifiers.",
                        Map.of("caseId", caseId));
            }
        });
    }

    private void validatePropertyPolicy(TestSuiteProtocol suite,
                                        TestPropertyCasePlan exactPlan,
                                        IntegrationRequestContext identity) {
        if (!(suite instanceof TestSuiteV4 propertySuite)) {
            return;
        }
        if (exactPlan == null || exactPlan.status() == TestPropertyCasePlan.Status.UNAVAILABLE) {
            throw badRequest(identity, "RG.TEST.PROPERTY_SUITE_PROOF_INVALID",
                    "A property suite requires a usable exact regenerated plan.", Map.of());
        }
        TestExecutionApiRequest.Target sourceTarget = exactPlan.target();
        boolean targetMatches = propertySuite.target().kind().equals(sourceTarget.kind())
                && propertySuite.target().id().equals(sourceTarget.id())
                && propertySuite.target().fingerprint().equals(sourceTarget.fingerprint());
        boolean fingerprintsMatch = propertySuite.propertyPlanFingerprint()
                .equals(exactPlan.planFingerprint())
                && propertySuite.inputSchemaFingerprint()
                .equals(exactPlan.inputSchemaFingerprint());
        TestSuiteV4.PropertyGenerationPolicy expectedPolicy = propertyPolicy(exactPlan.policy());
        TestSuiteV4.SourcePlanStatus expectedStatus = TestSuiteV4.SourcePlanStatus.valueOf(
                exactPlan.status().name());
        List<TestSuiteV4.PropertyGenerationGap> expectedGaps = exactPlan.gaps().stream()
                .map(TestSuiteRegistryService::propertyGap).toList();
        List<TestSuiteV4.PropertyTrialRef> expectedTrials = exactPlan.trials().stream()
                .map(TestSuiteRegistryService::propertyTrial).toList();
        if (!targetMatches || !fingerprintsMatch
                || !expectedPolicy.equals(propertySuite.generationPolicy())
                || expectedStatus != propertySuite.sourcePlanStatus()
                || !expectedGaps.equals(propertySuite.generationGaps())
                || !expectedTrials.equals(propertySuite.propertyTrials())) {
            throw conflict(identity, "RG.TEST.PROPERTY_SUITE_PLAN_MISMATCH",
                    "The V4 suite does not exactly close over the regenerated property plan.",
                    Map.of("currentPlanFingerprint", exactPlan.planFingerprint()));
        }
        List<TestPropertyCasePlan.PlannedCase> plannedCases = exactPlan.allCases();
        if (plannedCases.size() != propertySuite.cases().size()) {
            throw conflict(identity, "RG.TEST.PROPERTY_SUITE_PLAN_MISMATCH",
                    "The V4 suite case count differs from the regenerated property plan.",
                    Map.of("plannedCases", plannedCases.size(),
                            "suiteCases", propertySuite.cases().size()));
        }
        for (int index = 0; index < plannedCases.size(); index++) {
            TestPropertyCasePlan.PlannedCase plannedCase = plannedCases.get(index);
            TestSuite.TestCase suiteCase = propertySuite.cases().get(index);
            String inputFingerprint = ProtocolFingerprint.of(objectMapper, suiteCase.input());
            if (!plannedCase.caseId().equals(suiteCase.caseId())
                    || !plannedCase.inputFingerprint().equals(inputFingerprint)) {
                throw conflict(identity, "RG.TEST.PROPERTY_SUITE_INPUT_MISMATCH",
                        "A V4 suite input differs from its exact validator-proven plan case.",
                        Map.of("caseId", suiteCase.caseId()));
            }
        }
        TestSuite.CoveragePolicy coverage = propertySuite.coveragePolicy();
        TestSuite.PromotionPolicy promotion = propertySuite.promotionPolicy();
        boolean policyClosed = coverage.minimumCases() == propertySuite.cases().size()
                && coverage.requiredCaseTypes().equals(List.of(TestSuite.CaseType.PROPERTY))
                && coverage.requiredInvocationSiteIds().isEmpty()
                && coverage.requiredEdgeTransfers().isEmpty()
                && coverage.minimumAssertionsPerCase() >= 1
                && !coverage.requireAllFixtureRulesConsumed()
                && propertySuite.semanticCoveragePolicy().requirements().isEmpty()
                && promotion.requireAllCasesPassed()
                && promotion.minimumCertifiableCases() == propertySuite.cases().size()
                && promotion.requireTargetCertificationEligible();
        if (!policyClosed) {
            throw badRequest(identity, "RG.TEST.PROPERTY_SUITE_POLICY_INVALID",
                    "V4 requires full case closure, at least one assertion per case, and fail-closed promotion policy.",
                    Map.of("caseCount", propertySuite.cases().size()));
        }
    }

    private void validateMutationPolicy(TestSuiteProtocol suite,
                                        TestMutationCasePlan exactPlan,
                                        StoredTestSuite exactOracle,
                                        IntegrationRequestContext identity) {
        if (!(suite instanceof TestSuiteV5 mutationSuite)) {
            return;
        }
        if (exactPlan == null || exactOracle == null
                || exactPlan.status() == TestMutationCasePlan.Status.UNAVAILABLE) {
            throw badRequest(identity, "RG.TEST.MUTATION_SUITE_PROOF_INVALID",
                    "A mutation suite requires a usable exact plan and immutable oracle suite.",
                    Map.of());
        }
        TestSuiteProtocol oracle = exactOracle.suite();
        if (oracle instanceof TestSuiteV3 || oracle instanceof TestSuiteV5) {
            throw badRequest(identity, "RG.TEST.MUTATION_ORACLE_SUITE_UNSUPPORTED",
                    "Mutation oracles must be executable v1, v2, or v4 business suites.",
                    Map.of("schemaVersion", oracle.schemaVersion()));
        }
        TestSuiteV5.OracleSuiteRef expectedOracle = new TestSuiteV5.OracleSuiteRef(
                exactOracle.suiteId(), exactOracle.revision(), exactOracle.fingerprint(),
                oracle.schemaVersion());
        TestExecutionApiRequest.Target planTarget = exactPlan.target();
        boolean planClosed = mutationSuite.target().kind().equals(planTarget.kind())
                && mutationSuite.target().id().equals(planTarget.id())
                && mutationSuite.target().fingerprint().equals(planTarget.fingerprint())
                && mutationSuite.sourceFormat().equals(exactPlan.sourceFormat())
                && mutationSuite.baselineSourceFingerprint().equals(exactPlan.sourceFingerprint())
                && mutationSuite.baselineGraphArtifactFingerprint()
                .equals(exactPlan.graphArtifactFingerprint())
                && mutationSuite.mutationPlanFingerprint().equals(exactPlan.planFingerprint())
                && mutationSuite.mutationPolicy().equals(mutationPolicy(exactPlan.policy()))
                && mutationSuite.sourcePlanStatus()
                == TestSuiteV5.SourcePlanStatus.valueOf(exactPlan.status().name())
                && mutationSuite.planningGaps().equals(exactPlan.gaps().stream()
                .map(TestSuiteRegistryService::mutationGap).toList())
                && mutationSuite.mutants().equals(exactPlan.mutants().stream()
                .map(TestSuiteRegistryService::mutationRef).toList());
        if (!planClosed) {
            throw conflict(identity, "RG.TEST.MUTATION_SUITE_PLAN_MISMATCH",
                    "The V5 suite does not exactly close over the regenerated mutation plan.",
                    Map.of("currentPlanFingerprint", exactPlan.planFingerprint()));
        }
        SemanticCoveragePolicy oracleSemantic = oracle instanceof TestSuiteV2 v2
                ? v2.semanticCoveragePolicy()
                : oracle instanceof TestSuiteV4 v4
                ? v4.semanticCoveragePolicy() : SemanticCoveragePolicy.empty();
        boolean oracleClosed = mutationSuite.oracleSuiteRef().equals(expectedOracle)
                && mutationSuite.cases().equals(oracle.cases())
                && mutationSuite.coveragePolicy().equals(oracle.coveragePolicy())
                && mutationSuite.semanticCoveragePolicy().equals(oracleSemantic)
                && mutationSuite.promotionPolicy().equals(oracle.promotionPolicy())
                && classificationRank(mutationSuite.classification())
                >= classificationRank(oracle.classification());
        if (!oracleClosed) {
            throw conflict(identity, "RG.TEST.MUTATION_SUITE_ORACLE_MISMATCH",
                    "The V5 suite does not exactly close over its immutable oracle suite.",
                    Map.of("oracleSuiteId", exactOracle.suiteId(),
                            "oracleRevision", exactOracle.revision()));
        }
        if (mutationSuite.cases().size() > TestSuiteV5.MAX_CASES
                || mutationSuite.mutants().size() > TestSuiteV5.MAX_MUTANTS
                || (long) mutationSuite.cases().size() * mutationSuite.mutants().size()
                > TestSuiteV5.MAX_MUTANT_CASE_EXECUTIONS) {
            throw badRequest(identity, "RG.TEST.MUTATION_SUITE_WORK_LIMIT_EXCEEDED",
                    "The immutable mutation execution matrix exceeds generation-one bounds.",
                    Map.of("caseCount", mutationSuite.cases().size(),
                            "mutantCount", mutationSuite.mutants().size()));
        }
        for (TestSuite.TestCase testCase : mutationSuite.cases()) {
            StoredFixtureBundle fixture = requireFixture(testCase.fixtureBundleRef(), identity);
            if (fixture.bundle().assertions().isEmpty()) {
                throw badRequest(identity, "RG.TEST.MUTATION_ORACLE_ASSERTIONS_REQUIRED",
                        "Every mutation oracle case requires a governed business assertion.",
                        Map.of("caseId", testCase.caseId(),
                                "fixtureBundleId", fixture.fixtureBundleId()));
            }
        }
    }

    private void validateCaseShape(TestSuiteProtocol suite, TestSuite.TestCase testCase,
                                   Set<String> caseIds,
                                   IntegrationRequestContext identity) {
        if (testCase == null || testCase.caseId().isBlank()
                || testCase.caseId().length() > MAX_IDENTIFIER_LENGTH || testCase.caseType() == null
                || !caseIds.add(testCase.caseId())) {
            throw badRequest(identity, "RG.TEST.SUITE_CASE_IDENTITY_INVALID",
                    "Every case requires a unique bounded caseId and a supported caseType.", Map.of());
        }
        if (!(suite instanceof TestSuiteV5) && (suite instanceof TestSuiteV4)
                != (testCase.caseType() == TestSuite.CaseType.PROPERTY)) {
            throw badRequest(identity, "RG.TEST.SUITE_CASE_TYPE_GENERATION_INVALID",
                    "PROPERTY cases are reserved for exact-plan V4 suites, and every V4 case must be PROPERTY.",
                    Map.of("caseId", testCase.caseId()));
        }
        if ("GRAPH".equals(suite.target().kind()) && !(suite instanceof TestSuiteV3)
                && !(testCase.input() instanceof Map<?, ?>)) {
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

    private static TestSuiteV5.MutationPolicy mutationPolicy(
            TestMutationCasePlan.MutationPolicy source) {
        return new TestSuiteV5.MutationPolicy(source.plannerVersion(), source.maxMutants(),
                source.sourceFormat(), source.verificationMode(),
                source.externalOperatorMutation(), source.equivalentMutantDetection());
    }

    private static TestSuiteV5.PlanningGap mutationGap(
            TestMutationCasePlan.PlanningGap source) {
        return new TestSuiteV5.PlanningGap(
                TestSuiteV5.PlanningGapCode.valueOf(source.code().name()),
                source.astPath(), source.mutationKind());
    }

    private static TestSuiteV5.MutantRef mutationRef(
            TestMutationCasePlan.PlannedMutant source) {
        return new TestSuiteV5.MutantRef(source.mutantId(),
                TestSuiteV5.MutationKind.valueOf(source.kind().name()), source.astPath(),
                source.sourceLine(), source.sourceColumn(), source.mutantSourceFingerprint(),
                source.mutantGraphArtifactFingerprint(), source.mutantTargetFingerprint(),
                TestSuiteV5.EquivalenceClassification.valueOf(
                        source.equivalenceClassification().name()));
    }

    private StoredFixtureBundle requireFixture(TestSuite.FixtureBundleRef reference,
                                                IntegrationRequestContext identity) {
        try {
            StoredFixtureBundle stored = fixtureRepository.find(
                            identity.tenantId(), identity.environmentId(),
                            reference.fixtureBundleId(), reference.revision())
                    .orElseThrow(() -> conflict(identity, "RG.TEST.SUITE_FIXTURE_NOT_FOUND",
                            "A referenced fixture revision is absent from the authorized scope.",
                            Map.of("fixtureBundleId", reference.fixtureBundleId(),
                                    "revision", reference.revision())));
            return StoredFixtureBundleIntegrity.verifiedSnapshot(objectMapper, stored,
                    identity.tenantId(), identity.environmentId(), reference.fixtureBundleId(),
                    reference.revision());
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (FixtureBundleIntegrityException corrupt) {
            securityEvent(identity, "FIXTURE_INTEGRITY_INVALID", "REJECTED",
                    "RG.TEST.FIXTURE_INTEGRITY_INVALID", Map.of());
            throw unavailable(identity, "RG.TEST.FIXTURE_INTEGRITY_INVALID",
                    "A referenced fixture failed immutable-content verification.");
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.FIXTURE_STORE_UNAVAILABLE",
                    "The independent fixture registry is unavailable.");
        }
    }

    private void validateFixtureDependency(TestSuiteProtocol suite, TestSuite.TestCase testCase,
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
        if (suite instanceof TestSuiteV3 && (!fixture.bundle().rules().isEmpty()
                || !fixture.bundle().assertions().isEmpty()
                || fixture.bundle().logicalClock() != null
                || fixture.bundle().randomSeed() != null)) {
            throw badRequest(identity, "RG.TEST.SUITE_ADMISSION_FIXTURE_NOT_INERT",
                    "Schema-admission suites may reference only inert provenance fixtures.",
                    Map.of("caseId", testCase.caseId()));
        }
        if (suite instanceof TestSuiteV4 && fixture.bundle().assertions().isEmpty()) {
            throw badRequest(identity, "RG.TEST.PROPERTY_SUITE_ASSERTIONS_REQUIRED",
                    "Every property-suite fixture must contain at least one business assertion.",
                    Map.of("caseId", testCase.caseId()));
        }
    }

    private static TestSuiteV4.PropertyGenerationPolicy propertyPolicy(
            TestPropertyCasePlan.GenerationPolicy source) {
        return new TestSuiteV4.PropertyGenerationPolicy(source.generatorVersion(), source.seed(),
                source.requestedTrials(), source.maxShrinkSteps(), source.maxCases(),
                source.maxGenerationAttempts(), source.maxDepth(), source.maxCollectionItems(),
                source.verificationMode());
    }

    private static TestSuiteV4.PropertyGenerationGap propertyGap(
            TestPropertyCasePlan.CoverageGap source) {
        return new TestSuiteV4.PropertyGenerationGap(
                TestSuiteV4.GenerationGapCode.valueOf(source.code().name()),
                source.schemaPath(), source.keyword());
    }

    private static TestSuiteV4.PropertyTrialRef propertyTrial(
            TestPropertyCasePlan.PropertyTrial source) {
        return new TestSuiteV4.PropertyTrialRef(source.trialId(), source.inputFingerprint(),
                source.complexity(), source.shrinkPath().stream()
                .map(shrink -> new TestSuiteV4.PropertyShrinkRef(shrink.caseId(),
                        shrink.parentCaseId(), shrink.step(), shrink.inputFingerprint(),
                        shrink.complexity())).toList());
    }

    private void requireSemanticSite(String invocationSiteId,
                                     IntegrationRequestContext identity,
                                     SemanticCoveragePolicy.Requirement requirement) {
        if (invocationSiteId.isBlank() || invocationSiteId.length() > MAX_TARGET_ID_LENGTH) {
            throw semanticPolicyInvalid(identity,
                    "Semantic requirements need bounded structural invocation-site ids.", requirement);
        }
    }

    private IntegrationProblemException semanticPolicyInvalid(
            IntegrationRequestContext identity, String detail,
            SemanticCoveragePolicy.Requirement requirement) {
        return badRequest(identity, "RG.TEST.SUITE_SEMANTIC_POLICY_INVALID", detail,
                Map.of("requirementId", requirement.requirementId(),
                        "kind", requirement.kind().name()));
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

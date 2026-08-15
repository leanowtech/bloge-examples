package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionPreflightResponse;
import com.leanowtech.bloge.gateway.testing.api.TestOperatorExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessTestingRegistryGateway;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.planning.ControlPlanRejectedException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Exact Publication-to-execution-plan adapter for the correctness authoring surface.
 *
 * <p>This facade resolves immutable governance coordinates and projects UI-safe facts. It never
 * plans execution itself: every selected case is delegated to the existing trusted
 * {@link TestExecutionApiService} preflight, which owns replay/secret resolution and the sole
 * execution-control compiler.</p>
 */
public final class CorrectnessPreflightFacade {

    private static final String SELECTION_SCHEMA = "bloge.correctnessCaseSelection.v1";
    private static final String PREFLIGHT_MATERIAL_SCHEMA =
            "bloge.correctnessPreflightFingerprintMaterial.v1";

    private final CorrectnessPublicationRepository publications;
    private final CorrectnessTestingRegistryGateway registry;
    private final TestExecutionApiService executions;
    private final ObjectMapper mapper;

    public CorrectnessPreflightFacade(
            CorrectnessPublicationRepository publications,
            CorrectnessTestingRegistryGateway registry,
            TestExecutionApiService executions,
            ObjectMapper mapper
    ) {
        this.publications = Objects.requireNonNull(publications, "publications");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Resolves a canonical, payload-free report without scheduling graph work. */
    public CorrectnessPreflightReport preflight(
            CorrectnessPreflightRequest request,
            IntegrationRequestContext identity
    ) {
        requireIdentity(identity);
        if (request == null) {
            throw failure(400, "RG.CORRECTNESS.PREFLIGHT_REQUEST_REQUIRED",
                    "A versioned correctness preflight request is required", false);
        }
        EnterpriseScope scope = scope(identity);
        StoredCorrectnessPublication storedPublication = publications.findPublication(
                        scope, request.publicationRef().publicationId())
                .orElseThrow(() -> failure(404, "RG.CORRECTNESS.PUBLICATION_NOT_FOUND",
                        "Correctness Publication was not found in the authorized scope", false));
        requirePublication(request.publicationRef(), storedPublication);
        CorrectnessPublication publication = storedPublication.publication();
        ExactAssetRef suiteRef = publication.compiledTestSuiteRef();
        requireKind(suiteRef, "TEST_SUITE", "compiledTestSuiteRef");
        StoredTestSuite storedSuite = registry.findSuite(
                suiteRef.id(), suiteRef.revision(), identity);
        requireSuite(publication, suiteRef, storedSuite);

        List<TestSuite.TestCase> selected = selectedCases(
                request.selection(), storedSuite.suite().cases());
        String expectedSelectionFingerprint = selectionFingerprint(
                request.selection().mode(), selected.stream()
                        .map(TestSuite.TestCase::caseId).toList());
        if (!expectedSelectionFingerprint.equals(
                request.selection().selectionFingerprint())) {
            throw failure(409, "RG.CORRECTNESS.SELECTION_FINGERPRINT_CONFLICT",
                    "Case selection changed after it was reviewed", false);
        }

        Set<ExactAssetRef> publishedFixtures = Set.copyOf(
                publication.compiledFixtureBundleRefs());
        List<CorrectnessPreflightReport.CasePlan> cases = new ArrayList<>();
        List<CorrectnessPreflightReport.Blocker> blockers = new ArrayList<>();
        for (TestSuite.TestCase testCase : selected) {
            ExactAssetRef fixtureRef = fixtureRef(testCase);
            if (!publishedFixtures.contains(fixtureRef)) {
                throw failure(409, "RG.CORRECTNESS.PUBLICATION_FIXTURE_CLOSURE_CONFLICT",
                        "Selected case Fixture is outside the exact Publication closure", false);
            }
            try {
                TestExecutionPreflightResponse execution = preflightCase(
                        storedSuite, testCase, identity);
                requireExecutionClosure(publication, fixtureRef, execution);
                cases.add(projectCase(testCase, fixtureRef, execution));
            } catch (ControlPlanRejectedException rejected) {
                blockers.add(blocker(rejected.code(), testCase.caseId()));
            } catch (IntegrationProblemException rejected) {
                if (rejected.problem().status() == 401 || rejected.problem().status() == 403
                        || rejected.problem().status() >= 500) {
                    throw rejected;
                }
                blockers.add(blocker(rejected.problem().code(), testCase.caseId()));
            }
        }

        CorrectnessPreflightReport.RiskSummary risk = risk(cases);
        CorrectnessPreflightReport.ProofLevel proof = proofLevel(risk);
        Map<String, Object> fingerprintMaterial = new LinkedHashMap<>();
        fingerprintMaterial.put("schemaVersion", PREFLIGHT_MATERIAL_SCHEMA);
        fingerprintMaterial.put("publicationRef", request.publicationRef());
        fingerprintMaterial.put("target", publication.target());
        fingerprintMaterial.put("compiledTestSuiteRef", suiteRef);
        fingerprintMaterial.put("selection", request.selection());
        fingerprintMaterial.put("proofLevel", proof);
        fingerprintMaterial.put("cases", cases);
        fingerprintMaterial.put("riskSummary", risk);
        fingerprintMaterial.put("blockers", blockers);
        String preflightFingerprint = CorrectnessProtocolFingerprint.derivedFingerprint(
                mapper, fingerprintMaterial);
        return new CorrectnessPreflightReport(
                "", request.publicationRef(), publication.target(), suiteRef,
                request.selection(), proof, cases, risk, blockers, preflightFingerprint);
    }

    /** Computes the server-canonical selection fingerprint used by query and command adapters. */
    public String selectionFingerprint(
            CorrectnessRunRequest.Selection.Mode mode,
            List<String> resolvedCaseIds
    ) {
        List<String> caseIds = resolvedCaseIds == null ? List.of() : resolvedCaseIds.stream()
                .map(CorrectnessPreflightFacade::normalized)
                .filter(value -> !value.isEmpty()).distinct().sorted().toList();
        return CorrectnessProtocolFingerprint.derivedFingerprint(mapper, Map.of(
                "schemaVersion", SELECTION_SCHEMA,
                "mode", mode == null ? CorrectnessRunRequest.Selection.Mode.ALL : mode,
                "resolvedCaseIds", caseIds));
    }

    private TestExecutionPreflightResponse preflightCase(
            StoredTestSuite suite,
            TestSuite.TestCase testCase,
            IntegrationRequestContext identity
    ) {
        TestSuite.Target target = suite.suite().target();
        TestExecutionApiRequest.Target exactTarget = new TestExecutionApiRequest.Target(
                target.kind(), target.id(), target.fingerprint());
        TestSuite.FixtureBundleRef fixture = testCase.fixtureBundleRef();
        TestExecutionApiRequest.FixtureBundleRef exactFixture =
                new TestExecutionApiRequest.FixtureBundleRef(
                        fixture.fixtureBundleId(), fixture.revision(), fixture.fingerprint());
        Map<String, Object> metadata = Map.of(
                "suiteId", suite.suiteId(),
                "suiteRevision", suite.revision(),
                "caseId", testCase.caseId(),
                "source", "CORRECTNESS_PREFLIGHT");
        if ("GRAPH".equals(target.kind())) {
            TestExecutionApiRequest request = new TestExecutionApiRequest(
                    "", exactTarget, TestExecutionApiService.AUTHORIZED_PURPOSE,
                    graphContext(testCase.input()), null, exactFixture,
                    TestExecutionApiRequest.Verbosity.SUMMARY, metadata);
            return executions.preflight(request, identity);
        }
        if ("OPERATOR".equals(target.kind())) {
            TestOperatorExecutionApiRequest request = new TestOperatorExecutionApiRequest(
                    "", exactTarget, TestOperatorExecutionApiRequest.EXECUTION_PURPOSE,
                    testCase.input(), null, exactFixture,
                    TestExecutionApiRequest.Verbosity.SUMMARY, metadata);
            return executions.preflightOperator(target.id(), request, identity);
        }
        throw failure(409, "RG.CORRECTNESS.TARGET_KIND_UNSUPPORTED",
                "Correctness preflight supports GRAPH and OPERATOR targets", false);
    }

    private CorrectnessPreflightReport.CasePlan projectCase(
            TestSuite.TestCase testCase,
            ExactAssetRef fixtureRef,
            TestExecutionPreflightResponse execution
    ) {
        Map<String, EffectiveExecutionPlan.ResolvedSite> resolved =
                execution.effectivePlan().resolvedSites().stream().collect(Collectors.toMap(
                        EffectiveExecutionPlan.ResolvedSite::invocationSiteId,
                        Function.identity()));
        List<CorrectnessPreflightReport.InvocationResolution> sites =
                execution.invocationSites().stream().map(descriptor -> {
                    var site = descriptor.site();
                    EffectiveExecutionPlan.ResolvedSite plan = resolved.get(site.invocationSiteId());
                    if (plan == null) {
                        throw failure(503, "RG.CORRECTNESS.PREFLIGHT_PLAN_INTEGRITY_INVALID",
                                "Execution preflight inventory is not closed", true);
                    }
                    return new CorrectnessPreflightReport.InvocationResolution(
                            site.invocationSiteId(), site.graphPath(), site.nodeId(),
                            site.operatorRef(), site.resourceRef(), site.functionRef(),
                            site.runtimeBindingFingerprint(), site.invocationKind(),
                            descriptor.sideEffectType(), plan.resolution(), plan.behavior(),
                            plan.boundary(), plan.ruleRefs(), plan.fidelity());
                }).toList();
        List<CorrectnessPreflightReport.RulePolicy> policies = execution.rulePolicies().stream()
                .map(rule -> new CorrectnessPreflightReport.RulePolicy(
                        rule.ruleId(), rule.behavior(), rule.boundary(), rule.required(),
                        rule.minUses(), rule.maxUses(), rule.onUnmatched(), rule.onExhausted(),
                        rule.schemaCheckMode())).toList();
        List<CorrectnessPreflightReport.ServiceBinding> services =
                execution.effectivePlan().executionServiceBindings().stream()
                        .map(binding -> new CorrectnessPreflightReport.ServiceBinding(
                                binding.service(), binding.mode(), binding.available(),
                                binding.deterministic(), binding.configurationFingerprint(),
                                binding.consumers(), binding.certificationGaps())).toList();
        return new CorrectnessPreflightReport.CasePlan(
                testCase.caseId(), testCase.caseType(), fixtureRef,
                execution.effectivePlan().planFingerprint(), sites, policies, services,
                execution.effectivePlan().replayDependencies().size());
    }

    private static CorrectnessPreflightReport.RiskSummary risk(
            List<CorrectnessPreflightReport.CasePlan> cases
    ) {
        int real = 0;
        int mocked = 0;
        int fault = 0;
        int replay = 0;
        int observe = 0;
        int denied = 0;
        int transport = 0;
        int fallback = 0;
        int secrets = 0;
        boolean logicalClock = false;
        Set<String> effects = new LinkedHashSet<>();
        for (CorrectnessPreflightReport.CasePlan testCase : cases) {
            for (CorrectnessPreflightReport.InvocationResolution site : testCase.invocationSites()) {
                effects.add(site.sideEffectType());
                if (site.boundary() == FixtureRule.DoubleBoundary.TRANSPORT) transport++;
                if (site.resolution() == EffectiveExecutionPlan.Resolution.DENIED) {
                    denied++;
                } else if (site.resolution() == EffectiveExecutionPlan.Resolution.REAL) {
                    real++;
                } else {
                    switch (site.behavior()) {
                        case RETURN, DELAY, STREAM -> mocked++;
                        case THROW, TIMEOUT -> fault++;
                        case REPLAY -> replay++;
                        case SPY -> observe++;
                        case DENY -> denied++;
                        case REAL -> real++;
                    }
                }
            }
            fallback += (int) testCase.rulePolicies().stream()
                    .filter(CorrectnessPreflightReport.RulePolicy::mayFallbackToReal).count();
            for (CorrectnessPreflightReport.ServiceBinding binding : testCase.executionServices()) {
                if ("SECRET".equals(binding.service())) secrets += binding.consumers().size();
                if ("TIME".equals(binding.service())
                        && "LOGICAL_ADVANCING".equals(binding.mode())) logicalClock = true;
            }
        }
        return new CorrectnessPreflightReport.RiskSummary(
                real, mocked, fault, replay, observe, denied, fallback, transport,
                secrets, logicalClock, List.copyOf(effects));
    }

    private static CorrectnessPreflightReport.ProofLevel proofLevel(
            CorrectnessPreflightReport.RiskSummary risk
    ) {
        if (risk.realCount() > 0) {
            return CorrectnessPreflightReport.ProofLevel.CONTROLLED_INTEGRATION;
        }
        if (risk.replayCount() > 0) {
            return CorrectnessPreflightReport.ProofLevel.REPLAY_DERIVED;
        }
        if (risk.mockedCount() + risk.faultCount() + risk.observeCount() + risk.deniedCount() > 0) {
            return CorrectnessPreflightReport.ProofLevel.SIMULATED_BUSINESS;
        }
        return CorrectnessPreflightReport.ProofLevel.STRUCTURAL;
    }

    private static List<TestSuite.TestCase> selectedCases(
            CorrectnessRunRequest.Selection selection,
            List<TestSuite.TestCase> available
    ) {
        Map<String, TestSuite.TestCase> byId = available.stream().collect(Collectors.toMap(
                TestSuite.TestCase::caseId, Function.identity(), (left, right) -> left,
                LinkedHashMap::new));
        if (selection.mode() == CorrectnessRunRequest.Selection.Mode.ALL) {
            return available.stream().sorted(java.util.Comparator.comparing(
                    TestSuite.TestCase::caseId)).toList();
        }
        List<String> missing = selection.caseIds().stream()
                .filter(caseId -> !byId.containsKey(caseId)).toList();
        if (!missing.isEmpty()) {
            throw failure(409, "RG.CORRECTNESS.SELECTION_CASE_NOT_FOUND",
                    "Selected cases are outside the exact compiled Test Suite", false);
        }
        return selection.caseIds().stream().map(byId::get).toList();
    }

    private Map<String, Object> graphContext(Object input) {
        if (input == null) return Map.of();
        if (!(input instanceof Map<?, ?>)) {
            throw failure(409, "RG.CORRECTNESS.GRAPH_INPUT_NOT_OBJECT",
                    "Compiled GRAPH case input must be an object", false);
        }
        try {
            return Collections.unmodifiableMap(mapper.convertValue(
                    input, new TypeReference<LinkedHashMap<String, Object>>() { }));
        } catch (IllegalArgumentException invalid) {
            throw failure(409, "RG.CORRECTNESS.GRAPH_INPUT_INVALID",
                    "Compiled GRAPH case input cannot be projected as context", false);
        }
    }

    private static ExactAssetRef fixtureRef(TestSuite.TestCase testCase) {
        TestSuite.FixtureBundleRef ref = testCase.fixtureBundleRef();
        return new ExactAssetRef("FIXTURE_BUNDLE", ref.fixtureBundleId(),
                ref.revision(), ref.fingerprint());
    }

    private static void requirePublication(
            CorrectnessRunRequest.PublicationRef expected,
            StoredCorrectnessPublication actual
    ) {
        if (!expected.publicationId().equals(actual.publication().publicationId())
                || !expected.fingerprint().equals(actual.publicationFingerprint())) {
            throw failure(409, "RG.CORRECTNESS.PUBLICATION_FINGERPRINT_CONFLICT",
                    "Correctness Publication differs from the exact reviewed reference", false);
        }
    }

    private static void requireSuite(
            CorrectnessPublication publication,
            ExactAssetRef expected,
            StoredTestSuite actual
    ) {
        TestSuite.Target target = actual.suite().target();
        if (!actual.enterpriseScoped()
                || !expected.id().equals(actual.suiteId())
                || expected.revision() != actual.revision()
                || !expected.fingerprint().equals(actual.fingerprint())
                || !expected.id().equals(actual.suite().suiteId())
                || expected.revision() != actual.suite().revision()
                || !publication.target().kind().name().equals(target.kind())
                || !publication.target().id().equals(target.id())
                || !publication.target().fingerprint().equals(target.fingerprint())) {
            throw failure(409, "RG.CORRECTNESS.PUBLICATION_SUITE_CLOSURE_CONFLICT",
                    "Compiled Test Suite differs from the exact Publication closure", false);
        }
    }

    private static void requireExecutionClosure(
            CorrectnessPublication publication,
            ExactAssetRef fixtureRef,
            TestExecutionPreflightResponse execution
    ) {
        if (!publication.target().kind().name().equals(execution.target().kind())
                || !publication.target().id().equals(execution.target().id())
                || !publication.target().fingerprint().equals(execution.target().fingerprint())
                || !fixtureRef.id().equals(execution.fixtureBundleRef().fixtureBundleId())
                || fixtureRef.revision() != execution.fixtureBundleRef().revision()
                || !fixtureRef.fingerprint().equals(execution.fixtureBundleRef().fingerprint())
                || !"STORED".equals(execution.fixtureBundleRef().source())) {
            throw failure(503, "RG.CORRECTNESS.PREFLIGHT_CLOSURE_INVALID",
                    "Trusted execution preflight returned a different immutable closure", true);
        }
    }

    private static CorrectnessPreflightReport.Blocker blocker(String code, String caseId) {
        String safeCode = normalized(code).isEmpty() ? "RG.CORRECTNESS.PREFLIGHT_BLOCKED" : code;
        return new CorrectnessPreflightReport.Blocker(
                safeCode, "correctness.preflight." + safeCode.toLowerCase()
                .replaceAll("[^a-z0-9]+", "."), caseId);
    }

    private static void requireKind(ExactAssetRef ref, String kind, String field) {
        if (!kind.equals(ref.kind())) {
            throw failure(409, "RG.CORRECTNESS.PUBLICATION_ASSET_KIND_INVALID",
                    field + " has an unsupported asset kind", false);
        }
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        if (identity == null) {
            throw failure(401, "RG.CORRECTNESS.IDENTITY_REQUIRED",
                    "Verified integration identity is required", false);
        }
        identity.requireComplete();
        if (identity.projectId().isBlank() || identity.region().isBlank()) {
            throw failure(400, "RG.CORRECTNESS.ENTERPRISE_SCOPE_REQUIRED",
                    "Project and region are required for correctness execution", false);
        }
        String environment = identity.environmentId().toLowerCase(Locale.ROOT);
        if (!("test".equals(environment) || "staging".equals(environment))) {
            throw failure(403, "RG.CORRECTNESS.PRODUCTION_FIXTURE_INJECTION_FORBIDDEN",
                    "Correctness fixture execution is restricted to test and staging", false);
        }
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static CorrectnessRunException failure(
            int status, String code, String message, boolean retryable
    ) {
        return new CorrectnessRunException(status, code, message, retryable);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}

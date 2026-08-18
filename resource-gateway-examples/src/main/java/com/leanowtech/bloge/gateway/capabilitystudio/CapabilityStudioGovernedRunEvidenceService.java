package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompilationPlan;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompiler;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionPreflightResponse;
import com.leanowtech.bloge.gateway.testing.api.TestOperatorExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Re-verifies one persisted Capability Studio child run against the current canonical closure.
 * This service only reads the original run; it never invokes the test execution path.
 */
public final class CapabilityStudioGovernedRunEvidenceService {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Comparator<CapabilityStudioGovernedRunEvidenceProjection.ExactRef>
            EXACT_REF_ORDER = Comparator
            .comparing(CapabilityStudioGovernedRunEvidenceProjection.ExactRef::kind)
            .thenComparing(CapabilityStudioGovernedRunEvidenceProjection.ExactRef::id)
            .thenComparingLong(CapabilityStudioGovernedRunEvidenceProjection.ExactRef::revision)
            .thenComparing(CapabilityStudioGovernedRunEvidenceProjection.ExactRef::fingerprint);
    private static final String WORKLOAD_GROUP = "resource-gateway-test-runtime-operators";
    private static final String ERROR_PREFIX = "RG.CAPABILITY_STUDIO.GOVERNED_RUN.";
    private static final int MAX_PROTOCOL_BYTES = 16 * 1_048_576;

    private final CapabilityStudioGoldenDemoPack pack;
    private final ObjectMapper mapper;
    private final CapabilityStudioScenarioDatasetProjector datasetProjector;
    private final ScenarioGovernedRegistryGateway registry;
    private final CapabilityStudioGovernedCompilationService governedCompilation;
    private final TestExecutionApiService executions;
    private final CapabilityStudioDataLensProjector dataLens;

    public CapabilityStudioGovernedRunEvidenceService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            CapabilityStudioScenarioDatasetProjector datasetProjector,
            ScenarioGovernedRegistryGateway registry,
            CapabilityStudioGovernedCompilationService governedCompilation,
            TestExecutionApiService executions) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
        this.datasetProjector = Objects.requireNonNull(datasetProjector, "datasetProjector");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.governedCompilation = Objects.requireNonNull(governedCompilation, "governedCompilation");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.dataLens = new CapabilityStudioDataLensProjector(this.mapper);
    }

    /** Reads and verifies one already-persisted canonical child run. */
    public CapabilityStudioGovernedRunEvidenceProjection read(
            String runId, String expectedCaseId, IntegrationRequestContext caller) {
        String correlationId = caller == null ? "" : caller.correlationId();
        try {
            return readVerified(runId, expectedCaseId, correlationId);
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (CapabilityStudioGovernedCompilationException failure) {
            throw conflict(correlationId, failure.code());
        } catch (RuntimeException failure) {
            throw conflict(correlationId, "CANONICAL_VERIFICATION_FAILED");
        }
    }

    private CapabilityStudioGovernedRunEvidenceProjection readVerified(
            String runId, String expectedCaseId, String correlationId) {
        String requestedRunId = normalized(runId);
        if (requestedRunId.isBlank()) {
            throw badRequest(correlationId, "RUN_ID_REQUIRED");
        }

        CapabilityStudioGoldenGovernedTarget.Target target =
                CapabilityStudioGoldenGovernedTarget.create(mapper);
        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset =
                CapabilityStudioGoldenGovernedTarget.retarget(datasetProjector.project(), target);
        IntegrationRequestContext executionIdentity = serverOwnedIdentity(dataset, correlationId);
        // Read the caller-selected persisted run first. Everything below is canonical re-verification
        // and preflight planning; no execution method is reachable from this service.
        TestExecutionApiResponse stored = executions.find(
                requestedRunId, TestExecutionApiRequest.Verbosity.FULL, executionIdentity);
        require(stored != null && stored.evidence() != null, "EVIDENCE_MISSING");
        TestExecutionApiRequest.Target runtimeTarget = registry.describeOperatorTarget(
                CapabilityStudioFeatureRehearsalService.TOOL_REF, executionIdentity);
        require(runtimeTarget != null
                        && "OPERATOR".equals(runtimeTarget.kind())
                        && target.operator().operatorRef().equals(runtimeTarget.id())
                        && fingerprint(runtimeTarget.fingerprint()), "RUNTIME_TARGET_NOT_EXACT");

        CapabilityStudioScenarioDatasetCompilation adapterCompilation =
                new CapabilityStudioScenarioDatasetCompiler(mapper).compile(
                        dataset,
                        new CapabilityStudioScenarioDatasetCompiler.ExactCompilationTarget(
                                target.exactTarget(), target.contractFingerprint()),
                        new CapabilityStudioGoldenScenarioMaterialResolver(pack));
        CapabilityStudioGovernedCompilation compilation = governedCompilation.compile(
                null, target.operator(), target.contract(), runtimeTarget, adapterCompilation);
        require(compilation.compiled(), "COMPILATION_BLOCKED");

        TestRunEvidence evidence = stored.evidence();
        String caseId = metadataString(evidence.metadata(), "caseId");
        require(!caseId.isBlank(), "CASE_ID_NOT_PROVEN");
        if (!normalized(expectedCaseId).isBlank() && !normalized(expectedCaseId).equals(caseId)) {
            throw conflict(correlationId, "EXPECTED_CASE_MISMATCH");
        }
        CapabilityStudioScenarioDatasetProjector.DataCase dataCase = dataset.cases().stream()
                .filter(value -> value.caseRef().id().equals(caseId))
                .findFirst().orElseThrow(() -> failure("CASE_NOT_CANONICAL"));
        CapabilityStudioGoldenDemoPack.TestScenario scenario = pack.scenarios().stream()
                .filter(value -> value.id().equals(caseId)).findFirst()
                .orElseThrow(() -> failure("SCENARIO_NOT_CANONICAL"));

        ScenarioGovernedCompilationPlan plan = compilation.plan();
        ScenarioGovernedCompilationPlan.CompiledFixture compiledFixture = plan.fixtures().stream()
                .filter(value -> value.scenarioId().equals(caseId)).findFirst()
                .orElseThrow(() -> failure("FIXTURE_NOT_CANONICAL"));
        FixtureBundle fixture = compiledFixture.request().fixtureBundle();
        TestExecutionApiRequest.FixtureBundleRef fixtureRef = new TestExecutionApiRequest.FixtureBundleRef(
                fixture.fixtureBundleId(), fixture.revision(), compiledFixture.fingerprint());
        TestOperatorExecutionApiRequest preflightRequest = new TestOperatorExecutionApiRequest(
                TestOperatorExecutionApiRequest.SCHEMA_VERSION, runtimeTarget,
                TestOperatorExecutionApiRequest.EXECUTION_PURPOSE,
                plan.suite().testSuite().cases().stream()
                        .filter(value -> value.caseId().equals(caseId)).findFirst()
                        .orElseThrow(() -> failure("CASE_PLAN_NOT_CANONICAL")).input(),
                null, fixtureRef, TestExecutionApiRequest.Verbosity.FULL,
                Map.of("caseId", caseId));
        var preflight = executions.preflightOperator(
                runtimeTarget.id(), preflightRequest, executionIdentity);
        verifyStored(stored, evidence, runtimeTarget, fixtureRef, preflight.effectivePlan(),
                plan, caseId, compilation, correlationId);

        CapabilityStudioDataLensProjection lens = dataLens.project(
                evidence, CapabilityStudioDataLensProjection.PermissionMode.STRUCTURE_ONLY);
        boolean fallbackToReal = fallbackToReal(preflight.rulePolicies());
        require(!fallbackToReal, "FALLBACK_TO_REAL");

        CapabilityStudioGovernedRunEvidenceProjection.ExactRef contractRef = ref(
                dataset.contractRefs().stream().filter(value -> value.id().equals(
                        CapabilityStudioGoldenGovernedTarget.CONTRACT_ID)).findFirst()
                        .orElseThrow(() -> failure("CONTRACT_NOT_CANONICAL")));
        CapabilityStudioGovernedRunEvidenceProjection.ExactRef graphRef = ref(
                pack.featureCapabilities().stream().findFirst()
                        .orElseThrow(() -> failure("FEATURE_NOT_CANONICAL")).ref());
        CapabilityStudioGovernedRunEvidenceProjection.ExactRef capabilityRef = capabilityRef(dataset);
        CapabilityStudioGovernedRunEvidenceProjection.ExactRef primaryContractRef =
                resolvePrimaryContractRef(scenario, dataset);
        List<CapabilityStudioGovernedRunEvidenceProjection.ExactRef> applicableContractRefs =
                applicableContractClosure(primaryContractRef, contractRef,
                        dataCase.applicableContractRefs().stream().map(this::ref).toList());
        List<CapabilityStudioGovernedRunEvidenceProjection.ExactRef> behaviorRefs =
                dataCase.behaviorProfiles().stream().map(value -> ref(value.behaviorRef()))
                        .sorted(Comparator.comparing(CapabilityStudioGovernedRunEvidenceProjection.ExactRef::id))
                        .toList();
        List<CapabilityStudioGovernedRunEvidenceProjection.ExactRef> dependencyRefs =
                dataCase.behaviorProfiles().stream().map(value -> ref(value.dependencyRef()))
                        .distinct().sorted(Comparator.comparing(
                                CapabilityStudioGovernedRunEvidenceProjection.ExactRef::id)).toList();
        String sourceMapFingerprint = metadataString(plan.suite().testSuite().metadata(),
                ScenarioGovernedCompiler.GOVERNED_SOURCE_MAP_FINGERPRINT);
        String provenanceFingerprint = metadataString(plan.suite().testSuite().metadata(),
                ScenarioGovernedCompiler.GOVERNED_PROVENANCE_FINGERPRINT);
        require(fingerprint(sourceMapFingerprint) && fingerprint(provenanceFingerprint),
                "PROVENANCE_NOT_EXACT");

        CapabilityStudioGovernedRunEvidenceProjection.BindingPlan bindingPlan = bindingPlan(
                caseId, fixtureRef, preflight.effectivePlan(), behaviorRefs, dependencyRefs,
                sourceMapFingerprint, provenanceFingerprint);
        CapabilityStudioGovernedRunEvidenceProjection.Run run = new
                CapabilityStudioGovernedRunEvidenceProjection.Run(
                evidence.runId(), evidence.status().name(), evidence.evidenceClass().name(),
                stored.integrity().evidenceFingerprint(), evidence.semanticResultFingerprint(),
                evidence.assertionResults().size(), (int) evidence.assertionResults().stream()
                        .filter(TestRunEvidence.AssertionResult::passed).count(),
                evidence.fixtureConsumptions().size(), (int) evidence.fixtureConsumptions().stream()
                        .filter(value -> "SATISFIED".equals(value.status())).count());
        CapabilityStudioGovernedRunEvidenceProjection.Scenario scenarioProjection = new
                CapabilityStudioGovernedRunEvidenceProjection.Scenario(
                caseId, dataCase.name(), dataCase.businessIntent(), dataCase.category(),
                dataCase.lifecycle(), dataCase.qualityState(), owner(dataCase.owner()),
                ref(scenario.ref()), ref(dataCase.caseRef()), ref(dataCase.sourceRef()),
                ref(dataCase.oracleRef()), applicableContractRefs);
        CapabilityStudioGovernedRunEvidenceProjection.RuntimeTargetRef runtimeRef = new
                CapabilityStudioGovernedRunEvidenceProjection.RuntimeTargetRef(
                runtimeTarget.kind(), runtimeTarget.id(), runtimeTarget.fingerprint());
        CapabilityStudioGovernedRunEvidenceProjection projection = new CapabilityStudioGovernedRunEvidenceProjection(
                CapabilityStudioGovernedRunEvidenceProjection.SCHEMA_VERSION,
                CapabilityStudioGovernedRunEvidenceProjection.EXACT_VERIFIED,
                CapabilityStudioGovernedBaselineService.BASELINE_ID, "",
                scenarioProjection, graphRef, capabilityRef, contractRef, ref(dataset.datasetRef()),
                ref(dataCase.caseRef()), runtimeRef, bindingPlan, run, focusNodeId(evidence), lens);
        String projectionFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, projection.fingerprintMaterial(), MAX_PROTOCOL_BYTES);
        return new CapabilityStudioGovernedRunEvidenceProjection(
                projection.schemaVersion(), projection.verificationStatus(), projection.baselineId(),
                projectionFingerprint, projection.scenario(), projection.graphRef(),
                projection.capabilityRef(), projection.contractRef(), projection.datasetRef(),
                projection.caseRef(), projection.runtimeTarget(), projection.bindingPlan(),
                projection.run(), projection.focusNodeId(), projection.dataLens());
    }

    private void verifyStored(TestExecutionApiResponse stored, TestRunEvidence evidence,
                              TestExecutionApiRequest.Target runtimeTarget,
                              TestExecutionApiRequest.FixtureBundleRef fixtureRef,
                              EffectiveExecutionPlan expectedPlan,
                              ScenarioGovernedCompilationPlan compilationPlan,
                              String caseId, CapabilityStudioGovernedCompilation compilation,
                              String correlationId) {
        require(stored.runId().equals(evidence.runId()), "RUN_ID_DRIFT");
        require(runtimeTarget.equals(stored.target()), "TARGET_DRIFT");
        require(stored.fixtureBundleRef() != null
                        && fixtureRef.fixtureBundleId().equals(stored.fixtureBundleRef().fixtureBundleId())
                        && fixtureRef.revision() == stored.fixtureBundleRef().revision()
                        && fixtureRef.fingerprint().equals(stored.fixtureBundleRef().fingerprint()),
                "FIXTURE_DRIFT");
        require(sameStablePlan(stored.plan(), expectedPlan), "BINDING_PLAN_DRIFT");
        require(expectedPlan.planFingerprint().equals(evidence.planFingerprint())
                        && expectedPlan.targetFingerprint().equals(evidence.targetFingerprint())
                        && expectedPlan.fixtureBundleFingerprint().equals(evidence.fixtureBundleFingerprint()),
                "EVIDENCE_PLAN_DRIFT");
        require(stored.integrity() != null && stored.integrity().independentlyVerifiable()
                        && stored.integrity().projection()
                        == com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity.Projection.FULL,
                "INTEGRITY_NOT_EXACT");
        require(fingerprint(stored.integrity().evidenceFingerprint())
                        && stored.integrity().evidenceFingerprint().equals(
                        ProtocolFingerprint.of(mapper, evidence)), "EVIDENCE_FINGERPRINT_DRIFT");
        Map<String, Object> metadata = evidence.metadata();
        require(caseId.equals(metadataString(metadata, "caseId")), "CASE_METADATA_DRIFT");
        String suiteFingerprint = ProtocolFingerprint.ofBounded(
                mapper, compilationPlan.suite().testSuite(), MAX_PROTOCOL_BYTES);
        require(compilationPlan.suite().testSuite().suiteId().equals(metadataString(metadata, "suiteId"))
                        && Long.toString(compilationPlan.suite().testSuite().revision())
                        .equals(metadataString(metadata, "suiteRevision"))
                        && suiteFingerprint.equals(metadataString(metadata, "suiteFingerprint")),
                "SUITE_REF_DRIFT");
        require(metadataString(metadata, ScenarioGovernedCompiler.GOVERNED_SOURCE_MAP_FINGERPRINT)
                        .equals(metadataString(compilationPlan.suite().testSuite().metadata(),
                        ScenarioGovernedCompiler.GOVERNED_SOURCE_MAP_FINGERPRINT))
                        && metadataString(metadata, ScenarioGovernedCompiler.GOVERNED_PROVENANCE_FINGERPRINT)
                        .equals(metadataString(compilationPlan.suite().testSuite().metadata(),
                        ScenarioGovernedCompiler.GOVERNED_PROVENANCE_FINGERPRINT)),
                "PROVENANCE_DRIFT");
        require(compilation.semanticFingerprint() != null && fingerprint(compilation.semanticFingerprint()),
                "COMPILATION_FINGERPRINT_INVALID");
    }

    private CapabilityStudioGovernedRunEvidenceProjection.BindingPlan bindingPlan(
            String caseId, TestExecutionApiRequest.FixtureBundleRef fixture,
            EffectiveExecutionPlan plan,
            List<CapabilityStudioGovernedRunEvidenceProjection.ExactRef> behaviorRefs,
            List<CapabilityStudioGovernedRunEvidenceProjection.ExactRef> dependencyRefs,
            String sourceMapFingerprint, String provenanceFingerprint) {
        CapabilityStudioGovernedRunEvidenceProjection.ExactRef ref =
                new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                        "BINDING_PLAN", "binding-plan-" + caseId, 1, "");
        CapabilityStudioGovernedRunEvidenceProjection.BindingPlan withoutFingerprint =
                new CapabilityStudioGovernedRunEvidenceProjection.BindingPlan(
                        ref,
                        new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                                "FIXTURE_BUNDLE", fixture.fixtureBundleId(), fixture.revision(), fixture.fingerprint()),
                        plan.planFingerprint(), behaviorRefs, dependencyRefs, false,
                        sourceMapFingerprint, provenanceFingerprint);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, withoutFingerprint.fingerprintMaterial(), MAX_PROTOCOL_BYTES);
        return new CapabilityStudioGovernedRunEvidenceProjection.BindingPlan(
                new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                        "BINDING_PLAN", "binding-plan-" + caseId, 1, fingerprint),
                new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                        "FIXTURE_BUNDLE", fixture.fixtureBundleId(), fixture.revision(), fixture.fingerprint()),
                plan.planFingerprint(), behaviorRefs, dependencyRefs, false,
                sourceMapFingerprint, provenanceFingerprint);
    }

    private IntegrationRequestContext serverOwnedIdentity(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            String correlationId) {
        CapabilityStudioScenarioDatasetProjector.Scope scope = dataset.datasetRef().scope();
        return new IntegrationRequestContext(scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), "WORKLOAD", "capability-studio-evidence-reader", "",
                "TEST_EXECUTION", correlationId, Set.of(WORKLOAD_GROUP), "RESTRICTED", "");
    }

    private CapabilityStudioGovernedRunEvidenceProjection.ExactRef ref(
            CapabilityStudioScenarioDatasetProjector.ExactRef value) {
        return new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                value.kind(), value.id(), value.revision(), value.fingerprint());
    }

    private CapabilityStudioGovernedRunEvidenceProjection.ExactRef ref(
            CapabilityStudioGoldenDemoPack.ExactRef value) {
        return new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                value.kind(), value.id(), value.revision(), value.fingerprint());
    }

    private CapabilityStudioGovernedRunEvidenceProjection.ExactRef resolvePrimaryContractRef(
            CapabilityStudioGoldenDemoPack.TestScenario scenario,
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset) {
        CapabilityStudioGoldenDemoPack.ExactRef authoritative = scenario.contractRef();
        require(authoritative != null
                        && "CONTRACT".equals(authoritative.kind())
                        && !normalized(authoritative.id()).isBlank()
                        && authoritative.revision() > 0
                        && fingerprint(authoritative.fingerprint()),
                "PRIMARY_CONTRACT_INVALID");
        List<CapabilityStudioGovernedRunEvidenceProjection.ExactRef> matches =
                dataset.contractRefs().stream()
                        .filter(value -> authoritative.kind().equals(value.kind())
                                && authoritative.id().equals(value.id())
                                && authoritative.revision() == value.revision())
                        .map(this::ref)
                        .toList();
        require(matches.size() == 1, "PRIMARY_CONTRACT_NOT_CANONICAL");
        return matches.getFirst();
    }

    static List<CapabilityStudioGovernedRunEvidenceProjection.ExactRef> applicableContractClosure(
            CapabilityStudioGovernedRunEvidenceProjection.ExactRef primaryContractRef,
            CapabilityStudioGovernedRunEvidenceProjection.ExactRef topLevelContractRef,
            List<CapabilityStudioGovernedRunEvidenceProjection.ExactRef> dependencyContractRefs) {
        require(exactContractRef(primaryContractRef), "PRIMARY_CONTRACT_INVALID");
        require(primaryContractRef.equals(topLevelContractRef), "PRIMARY_CONTRACT_DRIFT");

        Map<String, CapabilityStudioGovernedRunEvidenceProjection.ExactRef> exactByCoordinate =
                new LinkedHashMap<>();
        List<CapabilityStudioGovernedRunEvidenceProjection.ExactRef> refs = new ArrayList<>();
        refs.add(primaryContractRef);
        refs.addAll(dependencyContractRefs == null ? List.of() : dependencyContractRefs);
        for (CapabilityStudioGovernedRunEvidenceProjection.ExactRef ref : refs) {
            require(exactContractRef(ref), "APPLICABLE_CONTRACT_INVALID");
            String coordinate = ref.kind() + "\u0000" + ref.id() + "\u0000" + ref.revision();
            CapabilityStudioGovernedRunEvidenceProjection.ExactRef existing =
                    exactByCoordinate.putIfAbsent(coordinate, ref);
            require(existing == null || existing.equals(ref), "APPLICABLE_CONTRACT_DRIFT");
        }
        return exactByCoordinate.values().stream().sorted(EXACT_REF_ORDER).toList();
    }

    private static boolean exactContractRef(
            CapabilityStudioGovernedRunEvidenceProjection.ExactRef ref) {
        return ref != null
                && "CONTRACT".equals(ref.kind())
                && !normalized(ref.id()).isBlank()
                && ref.revision() > 0
                && fingerprint(ref.fingerprint());
    }

    static boolean hasRealResolution(EffectiveExecutionPlan plan) {
        return plan != null && plan.resolvedSites().stream()
                .anyMatch(site -> site.resolution() == EffectiveExecutionPlan.Resolution.REAL);
    }

    /** A REAL site is not fallback by itself; only an explicit allow-real rule policy is. */
    static boolean fallbackToReal(List<TestExecutionPreflightResponse.RulePolicyDescriptor> policies) {
        return policies != null && policies.stream().anyMatch(policy ->
                policy != null
                        && (policy.onUnmatched()
                        == FixtureRule.UnmatchedAction.ALLOW_REAL
                        || policy.onExhausted()
                        == FixtureRule.ExhaustedAction.FALLBACK_TO_REAL));
    }

    /** Plan ids are per-compilation handles; the fingerprint and all stable bindings are canonical. */
    private static boolean sameStablePlan(EffectiveExecutionPlan actual,
                                          EffectiveExecutionPlan expected) {
        return actual != null && expected != null
                && Objects.equals(actual.schemaVersion(), expected.schemaVersion())
                && Objects.equals(actual.planFingerprint(), expected.planFingerprint())
                && Objects.equals(actual.authorizedPurpose(), expected.authorizedPurpose())
                && Objects.equals(actual.targetFingerprint(), expected.targetFingerprint())
                && Objects.equals(actual.fixtureBundleFingerprint(), expected.fixtureBundleFingerprint())
                && Objects.equals(actual.resolvedSites(), expected.resolvedSites())
                && Objects.equals(actual.replayDependencies(), expected.replayDependencies())
                && Objects.equals(actual.executionServiceBindings(), expected.executionServiceBindings())
                && Objects.equals(actual.defaultPolicies(), expected.defaultPolicies())
                && Objects.equals(actual.diagnostics(), expected.diagnostics());
    }

    static CapabilityStudioGovernedRunEvidenceProjection.ExactRef capabilityRef(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset) {
        CapabilityStudioScenarioDatasetProjector.ExactRef value = dataset.targetRef();
        return new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                value.kind(), value.id(), value.revision(), value.fingerprint());
    }

    private static CapabilityStudioGovernedRunEvidenceProjection.Owner owner(
            CapabilityStudioScenarioDatasetProjector.Owner value) {
        return new CapabilityStudioGovernedRunEvidenceProjection.Owner(value.id(), value.name());
    }

    private static String focusNodeId(TestRunEvidence evidence) {
        List<TestRunEvidence.NodeTrace> nodes = evidence.nodeTrace();
        for (TestRunEvidence.NodeTrace node : nodes) {
            if (node.attempts().stream().anyMatch(attempt -> "TIMEOUT".equals(attempt.status())
                    || "FAILED".equals(attempt.status()))) {
                return node.nodeId();
            }
        }
        for (TestRunEvidence.NodeTrace node : nodes) {
            if (!Set.of("SUCCESS", "MOCKED").contains(node.status())) {
                return node.nodeId();
            }
        }
        return nodes.isEmpty() ? "" : nodes.getLast().nodeId();
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static void require(boolean condition, String suffix) {
        if (!condition) {
            throw failure(suffix);
        }
    }

    private static CapabilityStudioGovernedCompilationException failure(String suffix) {
        return new CapabilityStudioGovernedCompilationException(ERROR_PREFIX + suffix, "/canonical");
    }

    private static IntegrationProblemException badRequest(String correlationId, String suffix) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                ERROR_PREFIX + suffix, "The governed Capability Studio run reference is invalid.",
                correlationId, Map.of()));
    }

    private static IntegrationProblemException conflict(String correlationId, String suffix) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                ERROR_PREFIX + suffix, "Persisted Capability Studio evidence is not an exact canonical match.",
                correlationId, Map.of()));
    }

}

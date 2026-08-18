package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompiler;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestOperatorExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestOperatorTargetDescriptor;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real governed Stage 0 proof over the existing testing control-plane authorities. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        properties = {
                "spring.profiles.active=test",
                "gateway.capability-studio.demo.enabled=true",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=integration-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=integration-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=integration-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=integration-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode=KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id=integration-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "gateway.capability-studio.acceptance.candidate-build.authority=integration-test",
                "gateway.capability-studio.acceptance.candidate-build.instance-id=integration-replica-a",
                "gateway.capability-studio.acceptance.candidate-build.build-ref=resource-gateway-examples",
                "gateway.capability-studio.acceptance.candidate-build.revision=1.0.0",
                "gateway.capability-studio.acceptance.candidate-build.source-commit=abcdef0",
                "gateway.capability-studio.acceptance.candidate-build.source-tree-status=CLEAN",
                "gateway.capability-studio.acceptance.candidate-build.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "spring.datasource.url=jdbc:h2:mem:capability-studio-governed-candidate;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:capability-studio-governed-candidate-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        })
class CapabilityStudioGovernedCandidateIntegrationTest {

    private static final String TENANT = "tenant-a";
    private static final String ORGANIZATION = "org-a";
    private static final String PROJECT = "project-a";
    private static final String ENVIRONMENT = "test";
    private static final String REGION = "local";

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private OperatorRegistry operatorRegistry;

    @Autowired
    private CapabilityStudioGoldenDemoPack pack;

    @Autowired
    private CapabilityStudioFeatureRehearsalService rehearsal;

    @Autowired
    private ScenarioGovernedCompiler governedCompiler;

    @Autowired
    private ScenarioGovernedRegistryGateway registryGateway;

    @Autowired
    private TestSuiteExecutionService suiteExecutions;

    @Autowired
    private TestExecutionApiService childExecutions;

    @Autowired
    private ResourceRegistry resources;

    @Autowired
    private CapabilityStudioGovernedBaselineService governedBaseline;

    @Autowired
    private CapabilityStudioDeploymentCandidateAuthority candidateAuthority;

    @Autowired
    private CapabilityStudioScenarioDatasetProjector datasetProjector;

    @Autowired
    private CapabilityStudioGovernedCompilationService governedCompilationService;

    private IntegrationRequestContext publicationIdentity;
    private IntegrationRequestContext executionIdentity;
    private CapabilityStudioGoldenGovernedTarget.Target target;
    private CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset;
    private CapabilityStudioScenarioDatasetCompilation compilation;
    private CapabilityStudioGovernedCompilation governedCompilation;
    private CapabilityStudioGovernedCandidateService candidate;
    private CapabilityStudioFeatureRehearsalService.RuntimeAsset runtimeAsset;
    private TestOperatorTargetDescriptor runtimeDescriptor;

    @BeforeEach
    void wireCanonicalRuntimeTargetIntoTheRealRegistry() {
        target = CapabilityStudioGoldenGovernedTarget.create(mapper);
        runtimeAsset = rehearsal.runtimeAsset();
        operatorRegistry.register(CapabilityStudioFeatureRehearsalService.TOOL_REF,
                runtimeAsset.operator());

        publicationIdentity = identity("TEST_SCENARIO_PUBLISH", "publisher");
        executionIdentity = identity("TEST_EXECUTION", "runner");
        TestExecutionApiRequest.Target runtimeTarget = registryGateway.describeOperatorTarget(
                CapabilityStudioFeatureRehearsalService.TOOL_REF, executionIdentity);
        assertThat(runtimeTarget.kind()).isEqualTo("OPERATOR");
        assertThat(runtimeTarget.id()).isEqualTo(target.operator().operatorRef());
        assertThat(runtimeTarget.fingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(runtimeTarget.fingerprint()).isNotEqualTo(target.operator().fingerprint());
        runtimeDescriptor = childExecutions.describeOperatorTarget(
                CapabilityStudioFeatureRehearsalService.TOOL_REF, executionIdentity);
        assertThat(runtimeDescriptor.target()).isEqualTo(runtimeTarget);
        assertThat(runtimeDescriptor.certificationEligible()).isTrue();
        assertThat(runtimeDescriptor.certificationGaps()).isEmpty();

        dataset = CapabilityStudioGoldenGovernedTarget.retarget(
                new CapabilityStudioScenarioDatasetProjector(pack, mapper).project(), target);
        CapabilityStudioScenarioDatasetCompiler adapter =
                new CapabilityStudioScenarioDatasetCompiler(mapper);
        compilation = adapter.compile(
                dataset,
                new CapabilityStudioScenarioDatasetCompiler.ExactCompilationTarget(
                        target.exactTarget(), target.contract().fingerprint(mapper)),
                new CapabilityStudioGoldenScenarioMaterialResolver(pack));
        CapabilityStudioGovernedCompilationService compiler =
                new CapabilityStudioGovernedCompilationService(mapper, governedCompiler);
        governedCompilation = compiler.compile(
                null, target.operator(), target.contract(), runtimeTarget, compilation);
        assertThat(governedCompilation.compiled()).isTrue();
        CapabilityStudioGovernedAssetPublisher publisher =
                new CapabilityStudioGovernedAssetPublisher(mapper, registryGateway);
        candidate = new CapabilityStudioGovernedCandidateService(
                mapper, compiler, publisher, suiteExecutions, childExecutions, candidateAuthority);
    }

    @Test
    void publishesAndExecutesTheSameNineCaseGovernedClosureWithoutRealExternalCalls() {
        CapabilityStudioGovernedCandidateService.CandidateReceipt receipt = run("golden-client-001");

        assertThat(receipt.publication().fixtureRefs()).hasSize(9);
        assertThat(receipt.evidence().childRuns()).hasSize(9);
        assertThat(receipt.evidence().childRuns()).extracting(
                CapabilityStudioGovernedCandidateService.ChildRunRef::runId).doesNotHaveDuplicates();
        assertThat(receipt.evidence().childRuns()).extracting(
                CapabilityStudioGovernedCandidateService.ChildRunRef::fixtureBundleId).doesNotHaveDuplicates();
        assertThat(receipt.evidence().status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED.name());
        assertThat(receipt.evidence().provenanceFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(receipt.evidence().sourceMapFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(receipt.candidateBuild()).isEqualTo(candidateAuthority.current().orElseThrow());
        assertThat(receipt.evidence().candidateIntentFingerprint())
                .matches("sha256:[a-f0-9]{64}");
        assertThat(receipt.evidence().childRuns()).allSatisfy(child ->
        {
            assertThat(child.status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED.name());
            assertThat(child.evidenceStatus()).isEqualTo("PASSED");
            assertThat(child.evidenceClass()).isEqualTo("CERTIFIABLE");
            assertThat(child.evidenceFingerprint()).matches("sha256:[a-f0-9]{64}");
            assertThat(child.semanticResultFingerprint()).matches("sha256:[a-f0-9]{64}");
            assertThat(child.assertionsEvaluated()).isOne();
            assertThat(child.assertionsPassed()).isOne();
            assertThat(child.fixtureControlsEvaluated()).isPositive();
            assertThat(child.fixtureControlsSatisfied())
                    .isEqualTo(child.fixtureControlsEvaluated());
        });
        assertThat(receipt.publication().suiteRef().fingerprint())
                .isEqualTo(ProtocolFingerprint.of(
                        mapper, governedCompilation.plan().suite().testSuite()));

        String exactRefs = String.valueOf(
                governedCompilation.plan().suite().testSuite().metadata().get("governedExactRefs"));
        assertThat(exactRefs).contains("DATASET", "DATA_CASE", "CONTRACT", "BEHAVIOR_PROFILE",
                "API", "TOOL");
        assertThat(runtimeAsset.realExternalCalls()).hasValue(0);
    }

    @Test
    void repeatedClientRequestIsIdempotentAndNewClientRequestReusesPublication() {
        CapabilityStudioGovernedCandidateService.CandidateReceipt first = run("golden-client-002");
        CapabilityStudioGovernedCandidateService.CandidateReceipt retry = run("golden-client-002");
        CapabilityStudioGovernedCandidateService.CandidateReceipt second = run("golden-client-003");

        assertThat(retry).isEqualTo(first);
        assertThat(second.publication()).isEqualTo(first.publication());
        assertThat(second.evidence().suiteRunId()).isNotEqualTo(first.evidence().suiteRunId());
        assertThat(second.evidence().provenanceFingerprint())
                .isEqualTo(first.evidence().provenanceFingerprint());
        assertThat(second.evidence().sourceMapFingerprint())
                .isEqualTo(first.evidence().sourceMapFingerprint());
    }

    @Test
    void runsTheBrowserVisibleNineByThreeBaselineThroughTheRealGovernedControlPlane() {
        CapabilityStudioGovernedBaselineProjection projection = governedBaseline.run();

        assertThat(projection.status())
                .withFailMessage("Governed baseline failed closed: %s", projection.diagnostics())
                .isEqualTo(CapabilityStudioGovernedBaselineProjection.PASSED);
        assertThat(projection.caseCount()).isEqualTo(9);
        assertThat(projection.roundCount()).isEqualTo(3);
        assertThat(projection.suiteRunCount()).isEqualTo(3);
        assertThat(projection.childRunCount()).isEqualTo(27);
        assertThat(projection.oraclePassCount()).isEqualTo(9);
        assertThat(projection.businessCheckCount()).isEqualTo(27);
        assertThat(projection.businessCheckPassCount()).isEqualTo(27);
        assertThat(projection.realExternalCallCount()).isZero();
        assertThat(projection.evidenceClass()).isEqualTo("CERTIFIABLE");
        assertThat(projection.publication()).isNotNull();
        assertThat(projection.publication().fixtureCount()).isEqualTo(9);
        CapabilityStudioDeploymentCandidateAuthority.Binding boundCandidate =
                candidateAuthority.current().orElseThrow();
        assertThat(projection.candidateBuild()).satisfies(candidateBuild -> {
            assertThat(candidateBuild.authority()).isEqualTo(boundCandidate.authority());
            assertThat(candidateBuild.instanceId()).isEqualTo(boundCandidate.instanceId());
            assertThat(candidateBuild.buildRef()).isEqualTo(boundCandidate.buildRef());
            assertThat(candidateBuild.revision()).isEqualTo(boundCandidate.revision());
            assertThat(candidateBuild.sourceCommit()).isEqualTo(boundCandidate.sourceCommit());
            assertThat(candidateBuild.sourceTreeStatus()).isEqualTo(boundCandidate.sourceTreeStatus());
            assertThat(candidateBuild.artifactFingerprint())
                    .isEqualTo(boundCandidate.artifactFingerprint());
        });
        assertThat(projection.candidateIntentFingerprint())
                .matches("sha256:[a-f0-9]{64}");
        assertThat(projection.rounds()).hasSize(3)
                .extracting(CapabilityStudioGovernedBaselineProjection.Round::suiteRunId)
                .doesNotHaveDuplicates();
        assertThat(projection.rounds()).allSatisfy(round -> {
            assertThat(round.status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED.name());
            assertThat(round.childRunCount()).isEqualTo(9);
        });
        assertThat(projection.cases())
                .extracting(CapabilityStudioGovernedBaselineProjection.CaseProjection::caseId)
                .containsExactly(
                        "case-city-policy-missing",
                        "case-compensation-history-empty",
                        "case-compensation-history-timeout",
                        "case-driver-responsible",
                        "case-duplicate-cancellation",
                        "case-forbidden-write-effect",
                        "case-policy-revision-regression",
                        "case-rider-not-responsible",
                        "case-standard-cancellation-fee");
        assertThat(projection.cases()).hasSize(9).allSatisfy(caseProjection -> {
            assertThat(caseProjection.oracleStatus()).isEqualTo("PASS");
            assertThat(caseProjection.semanticResultFingerprint())
                    .matches("sha256:[a-f0-9]{64}");
            assertThat(caseProjection.assertionsEvaluated()).isEqualTo(3);
            assertThat(caseProjection.assertionsPassed()).isEqualTo(3);
            assertThat(caseProjection.fixtureControlsEvaluated()).isPositive();
            assertThat(caseProjection.fixtureControlsSatisfied())
                    .isEqualTo(caseProjection.fixtureControlsEvaluated());
            assertThat(caseProjection.proofs()).contains(
                    "BUSINESS_ASSERTION_PASSED",
                    "SEMANTIC_RESULT_STABLE",
                    "FIXTURE_CONTROL_SATISFIED",
                    "ZERO_REAL_EXTERNAL_CALLS");
            assertThat(caseProjection.rounds()).hasSize(3)
                    .extracting(CapabilityStudioGovernedBaselineProjection.CaseRound::runId)
                    .doesNotHaveDuplicates();
            assertThat(caseProjection.rounds()).allSatisfy(round -> {
                assertThat(round.status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED.name());
                assertThat(round.evidenceFingerprint()).matches("sha256:[a-f0-9]{64}");
                assertThat(round.semanticResultFingerprint())
                        .isEqualTo(caseProjection.semanticResultFingerprint());
                assertThat(round.assertionsEvaluated()).isOne();
                assertThat(round.assertionsPassed()).isOne();
                assertThat(round.fixtureControlsSatisfied())
                        .isEqualTo(round.fixtureControlsEvaluated());
            });
        });
        assertThat(projection.cases().stream()
                .filter(value -> value.caseId().equals("case-compensation-history-timeout"))
                .findFirst().orElseThrow().proofs()).contains("TIMEOUT_FALLBACK_CONFIRMED");
        assertThat(projection.cases().stream()
                .filter(value -> value.caseId().equals("case-duplicate-cancellation"))
                .findFirst().orElseThrow().proofs()).contains("DUPLICATE_IDEMPOTENCY_CONFIRMED");
        assertThat(projection.cases().stream()
                .filter(value -> value.caseId().equals("case-forbidden-write-effect"))
                .findFirst().orElseThrow().proofs()).contains("FORBIDDEN_WRITE_EFFECT_ABSENT");
        assertThat(projection.cases().stream()
                .flatMap(caseProjection -> caseProjection.rounds().stream())
                .map(CapabilityStudioGovernedBaselineProjection.CaseRound::runId))
                .doesNotHaveDuplicates();
        assertThat(projection.limitations()).containsExactly(
                "RUNTIME_ENVIRONMENT_NOT_ATTESTED",
                "DEPLOYMENT_EGRESS_NOT_OBSERVED",
                "OWNER_SIGNOFF_NOT_PRESENT");
        assertThat(projection.diagnostics()).isEmpty();
    }

    @Test
    void readsTheOriginalTimeoutChildRunWithExactStructureOnlyEvidenceAndNoExecution() throws Exception {
        CapabilityStudioGovernedBaselineProjection baseline = governedBaseline.run();
        CapabilityStudioGovernedBaselineProjection.CaseProjection timeoutCase = baseline.cases().stream()
                .filter(value -> value.caseId().equals("case-compensation-history-timeout"))
                .findFirst().orElseThrow();
        String originalRunId = timeoutCase.rounds().getFirst().runId();

        TestExecutionApiService readExecutions = spy(childExecutions);
        clearInvocations(readExecutions);
        CapabilityStudioGovernedRunEvidenceService evidenceService =
                new CapabilityStudioGovernedRunEvidenceService(
                        pack, mapper, datasetProjector, registryGateway,
                        governedCompilationService, readExecutions);

        CapabilityStudioGovernedRunEvidenceProjection first = evidenceService.read(
                originalRunId, timeoutCase.caseId(), executionIdentity);
        CapabilityStudioGovernedRunEvidenceProjection second = evidenceService.read(
                originalRunId, timeoutCase.caseId(), executionIdentity);

        assertThat(first).isEqualTo(second);
        assertThat(mapper.writeValueAsString(first)).isEqualTo(mapper.writeValueAsString(second));
        assertThat(first.schemaVersion())
                .isEqualTo(CapabilityStudioGovernedRunEvidenceProjection.SCHEMA_VERSION);
        assertThat(first.verificationStatus())
                .isEqualTo(CapabilityStudioGovernedRunEvidenceProjection.EXACT_VERIFIED);
        assertThat(first.scenario().caseId()).isEqualTo(timeoutCase.caseId());
        assertThat(first.caseRef()).isEqualTo(first.scenario().caseRef());
        assertThat(first.run().runId()).isEqualTo(originalRunId);
        assertThat(first.dataLens().runId()).isEqualTo(originalRunId);
        assertThat(first.dataLens().runStatus()).isEqualTo(first.run().status());
        assertThat(first.dataLens().schemaVersion())
                .isEqualTo(CapabilityStudioDataLensProjection.SCHEMA_VERSION);
        assertThat(first.run().status()).isEqualTo("PASSED");
        assertThat(first.run().evidenceClass()).isEqualTo("CERTIFIABLE");
        assertExactRef(first.graphRef(), "FEATURE");
        assertThat(first.graphRef().id()).isEqualTo(pack.featureCapabilities().getFirst().ref().id());
        assertExactRef(first.capabilityRef(), "TOOL");
        assertThat(first.capabilityRef().id()).isEqualTo(target.operator().operatorRef());
        assertExactRef(first.contractRef(), "CONTRACT");
        assertThat(first.contractRef().id()).isEqualTo(CapabilityStudioGoldenGovernedTarget.CONTRACT_ID);
        assertThat(first.scenario().applicableContractRefs()).contains(first.contractRef());
        assertThat(first.scenario().applicableContractRefs())
                .containsAll(CapabilityStudioGoldenGovernedTarget.retarget(
                                datasetProjector.project(), target).cases().stream()
                        .filter(value -> value.caseRef().id().equals(timeoutCase.caseId()))
                        .findFirst().orElseThrow().applicableContractRefs().stream()
                        .map(value -> new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                                value.kind(), value.id(), value.revision(), value.fingerprint()))
                        .toList());
        assertThat(first.scenario().applicableContractRefs())
                .extracting(CapabilityStudioGovernedRunEvidenceProjection.ExactRef::id)
                .containsExactly(
                        CapabilityStudioGoldenGovernedTarget.CONTRACT_ID,
                        "contract-compensation-history");
        assertExactRef(first.datasetRef(), "DATASET");
        assertThat(first.datasetRef().id()).isEqualTo(datasetProjector.project().datasetRef().id());
        assertExactRef(first.caseRef(), "DATA_CASE");
        assertThat(first.caseRef().id()).isEqualTo(timeoutCase.caseId());
        assertExactRef(first.bindingPlan().fixtureBundleRef(), "FIXTURE_BUNDLE");
        assertThat(first.bindingPlan().fallbackToReal()).isFalse();
        assertThat(first.bindingPlan().sourceMapFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(first.bindingPlan().provenanceFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(first.bindingPlan().ref().fingerprint())
                .isEqualTo(VisualBundleFingerprint.fromCanonicalValue(
                        mapper, first.bindingPlan().fingerprintMaterial(), 16 * 1_048_576));
        assertThat(first.dataLens().permissionMode())
                .isEqualTo(CapabilityStudioDataLensProjection.PermissionMode.STRUCTURE_ONLY);
        assertThat(first.dataLens().fingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(first.dataLens().firstDifference()).isNull();
        assertThat(first.dataLens().nodes()).isNotEmpty().allSatisfy(node -> {
            assertThat(node.input()).isNull();
            assertThat(node.output()).isNull();
            assertThat(node.attempts()).allSatisfy(attempt -> {
                assertThat(attempt.input()).isNull();
                assertThat(attempt.output()).isNull();
            });
        });
        assertThat(first.dataLens().edges()).allSatisfy(edge -> assertThat(edge.value()).isNull());
        assertThat(first.focusNodeId()).isNotBlank();
        assertThat(first.dataLens().nodes())
                .filteredOn(node -> node.nodeId().equals(first.focusNodeId()))
                .singleElement()
                .satisfies(node -> assertThat(node.attempts()).anySatisfy(attempt ->
                        assertThat(attempt.status()).isIn("TIMEOUT", "FAILED")));

        verify(readExecutions, times(2)).find(
                eq(originalRunId), eq(TestExecutionApiRequest.Verbosity.FULL), any());
        verify(readExecutions, times(2)).preflightOperator(anyString(), any(), any());
        verify(readExecutions, never()).execute(any(TestExecutionApiRequest.class), any());
        verify(readExecutions, never()).executeOperator(anyString(), any(), any());
        verify(readExecutions, never()).executeBatch(any(), any());
    }

    @Test
    void unresolvedResourceCannotBePromotedByAStoredTransportFixture() {
        assertThat(resources.contains("api-order-lookup")).isTrue();
        assertThat(resources.contains("api-resource-does-not-exist")).isFalse();

        TestOperatorTargetDescriptor httpTarget = childExecutions.describeOperatorTarget(
                "httpResource", executionIdentity);
        StoredFixtureBundle fixture = registerHttpFixture(
                "capability-studio-unresolved-resource", httpTarget.target(),
                "api-resource-does-not-exist",
                FixtureRule.Behavior.protocolResponse(
                        "{}", 200, Map.of("Content-Type", "application/json"),
                        FixtureRule.DoubleBoundary.TRANSPORT));

        assertThatThrownBy(() -> executeHttpResource(httpTarget.target(),
                "api-resource-does-not-exist", fixture))
                .isInstanceOf(com.leanowtech.bloge.gateway.integration.IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((com.leanowtech.bloge.gateway.integration.IntegrationProblemException) failure)
                                .problem().code())
                        .isEqualTo("RG.TEST.RESOURCE_DESCRIPTOR_NOT_FOUND"));
    }

    @Test
    void outputLevelResourceFixtureCannotBePromotedByTheProductionHttpOperator() {
        TestOperatorTargetDescriptor httpTarget = childExecutions.describeOperatorTarget(
                "httpResource", executionIdentity);
        StoredFixtureBundle fixture = registerHttpFixture(
                "capability-studio-output-level-resource", httpTarget.target(),
                "api-order-lookup", FixtureRule.Behavior.returning(Map.of(
                        "resourceId", "api-order-lookup",
                        "statusCode", 200,
                        "payload", Map.of("controlled", true))));

        TestExecutionApiResponse response = executeHttpResource(
                httpTarget.target(), "api-order-lookup", fixture);

        assertThat(response.evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
        assertThat(response.evidence().evidenceClass())
                .isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
    }

    @Test
    void productionHttpResourceAssemblyWithoutTransportControlCannotBePromoted() {
        TestOperatorTargetDescriptor httpTarget = childExecutions.describeOperatorTarget(
                "httpResource", executionIdentity);
        StoredFixtureBundle fixture = registerHttpFixture(
                "capability-studio-production-real-call", httpTarget.target(),
                "api-order-lookup", FixtureRule.Behavior.real());

        assertNoCertifiableSuccess(() -> executeHttpResource(httpTarget.target(),
                "api-order-lookup", fixture));
    }

    private CapabilityStudioGovernedCandidateService.CandidateReceipt run(String requestId) {
        TestExecutionApiRequest.Target runtimeTarget = registryGateway.describeOperatorTarget(
                CapabilityStudioFeatureRehearsalService.TOOL_REF, executionIdentity);
        return candidate.run(null, target.operator(), target.contract(), runtimeTarget,
                compilation, requestId, publicationIdentity, executionIdentity);
    }

    private StoredFixtureBundle registerHttpFixture(
            String fixtureId,
            TestExecutionApiRequest.Target target,
            String resourceId,
            FixtureRule.Behavior behavior) {
        FixtureRule rule = new FixtureRule(
                FixtureRule.SCHEMA_VERSION,
                fixtureId + "-rule",
                FixtureRule.Selector.resource(resourceId),
                behavior,
                FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
        FixtureBundle bundle = new FixtureBundle(
                FixtureBundle.SCHEMA_VERSION,
                fixtureId,
                1,
                target.fingerprint(),
                "INTERNAL",
                null,
                null,
                List.of(rule),
                List.of(),
                Map.of("fixtureKind", "CERTIFIABLE_TRANSPORT_NEGATIVE"));
        return childExecutions.registerFixture(fixtureId,
                new FixtureBundleRegistrationRequest("", target, bundle), executionIdentity);
    }

    private TestExecutionApiResponse executeHttpResource(
            TestExecutionApiRequest.Target target,
            String resourceId,
            StoredFixtureBundle fixture) {
        return childExecutions.executeOperator(
                "httpResource",
                new TestOperatorExecutionApiRequest(
                        "",
                        target,
                        TestOperatorExecutionApiRequest.EXECUTION_PURPOSE,
                        Map.of("resourceId", resourceId, "params", Map.of()),
                        null,
                        new TestExecutionApiRequest.FixtureBundleRef(
                                fixture.fixtureBundleId(), fixture.revision(), fixture.fingerprint()),
                        TestExecutionApiRequest.Verbosity.FULL,
                        Map.of("test", "governed-certification-negative")),
                executionIdentity);
    }

    private static void assertNoCertifiableSuccess(
            java.util.function.Supplier<TestExecutionApiResponse> execution) {
        try {
            TestExecutionApiResponse response = execution.get();
            assertThat(response.evidence()).isNotNull();
            assertThat(response.evidence().status() == TestRunEvidence.Status.PASSED
                    && response.evidence().evidenceClass()
                    == TestRunEvidence.EvidenceClass.CERTIFIABLE).isFalse();
        } catch (RuntimeException rejected) {
            // Rejection before evidence publication is also fail-closed.
            assertThat(rejected).isNotNull();
        }
    }

    private static void assertExactRef(
            CapabilityStudioGovernedRunEvidenceProjection.ExactRef ref, String kind) {
        assertThat(ref).isNotNull();
        assertThat(ref.kind()).isEqualTo(kind);
        assertThat(ref.id()).isNotBlank();
        assertThat(ref.revision()).isPositive();
        assertThat(ref.fingerprint()).matches("sha256:[a-f0-9]{64}");
    }

    private static IntegrationRequestContext identity(String purpose, String actor) {
        return new IntegrationRequestContext(TENANT, ORGANIZATION, PROJECT, ENVIRONMENT, REGION,
                "WORKLOAD", actor, "", purpose, "capability-studio-integration-" + actor,
                Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED", "");
    }
}
